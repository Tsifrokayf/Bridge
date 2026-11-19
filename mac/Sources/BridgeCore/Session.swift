import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// Byte pipe under a Session (a TCP connection in the app, a fake in tests).
public protocol Transport: AnyObject {
    /// Sends already-framed bytes. Must preserve call order.
    func send(_ data: Data, completion: @escaping (Error?) -> Void)
    func close()
}

public struct IncomingNotification {
    public let key: String
    public let app: String
    public let package: String
    public let title: String
    public let text: String
    public let timestamp: Int64
    /// The phone can deliver an inline reply for this notification.
    public let canReply: Bool
    /// The phone allows dismissing it remotely.
    public let clearable: Bool
}

public protocol SessionDelegate: AnyObject {
    func session(_ s: Session, didReceiveHello name: String, platform: String)
    func session(_ s: Session, didReceivePairConfirm ok: Bool)
    func session(_ s: Session, didReceiveClipboard text: String)
    func session(_ s: Session, didReceiveNotification n: IncomingNotification)
    func session(_ s: Session, didRemoveNotification key: String)
    func session(_ s: Session, didReceiveFile url: URL)
    /// Same, with the sender's `kind` tag (photo, pull, clipimg, ...). Defaults to the kind-less callback.
    func session(_ s: Session, didReceiveFile url: URL, kind: String?)
    func session(_ s: Session, didCloseWithReason reason: String)
    /// Messages the session does not handle itself: status, sms, sms_result, call, ...
    func session(_ s: Session, didReceiveMessage type: String, _ message: [String: Any])
}

public extension SessionDelegate {
    func session(_ s: Session, didReceiveMessage type: String, _ message: [String: Any]) {}
    func session(_ s: Session, didReceiveHello name: String, platform: String) {}
    func session(_ s: Session, didReceivePairConfirm ok: Bool) {}
    func session(_ s: Session, didReceiveClipboard text: String) {}
    func session(_ s: Session, didReceiveNotification n: IncomingNotification) {}
    func session(_ s: Session, didRemoveNotification key: String) {}
    func session(_ s: Session, didReceiveFile url: URL) {}
    func session(_ s: Session, didReceiveFile url: URL, kind: String?) { session(s, didReceiveFile: url) }
    func session(_ s: Session, didCloseWithReason reason: String) {}
}

/// Reads a JSON number regardless of how the platform's JSONSerialization boxes it.
func jsonInt(_ v: Any?) -> Int64? {
    if let i = v as? Int { return Int64(i) }
    if let i = v as? Int64 { return i }
    if let u = v as? UInt64 { return Int64(truncatingIfNeeded: u) }
    if let n = v as? NSNumber { return n.int64Value }
    if let d = v as? Double { return Int64(d) }
    return nil
}

private final class Waiter {
    let semaphore = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var result = false
    func complete(_ v: Bool) {
        lock.lock(); result = v; lock.unlock()
        semaphore.signal()
    }
    func wait(seconds: Double) -> Bool {
        if semaphore.wait(timeout: .now() + seconds) == .timedOut { return false }
        lock.lock(); defer { lock.unlock() }
        return result
    }
}

private final class IncomingFile {
    let name: String
    let size: Int64
    let directory: URL
    let kind: String?
    let partialURL: URL
    let handle: FileHandle
    var hasher = SHA256()
    var received: Int64 = 0

    init(name: String, size: Int64, directory: URL, kind: String?, partialURL: URL, handle: FileHandle) {
        self.directory = directory
        self.kind = kind
        self.name = name
        self.size = size
        self.partialURL = partialURL
        self.handle = handle
    }

    func discard() {
        try? handle.close()
        try? FileManager.default.removeItem(at: partialURL)
    }
}

/// An established, encrypted connection to the phone.
///
/// Incoming frames must be passed to `handle(frame:)` on `queue`; delegate callbacks are made on `queue`
/// (except didCloseWithReason, which may come from any thread).
/// All `send…` methods may be called from any thread.
public final class Session {
    public weak var delegate: SessionDelegate?
    public let queue: DispatchQueue
    public let peerId: Data

    /// While false, clipboard/notification/file messages are dropped (used until pairing is confirmed).
    public var contentEnabled: Bool {
        get { stateLock.lock(); defer { stateLock.unlock() }; return _contentEnabled }
        set { stateLock.lock(); _contentEnabled = newValue; stateLock.unlock() }
    }
    private var _contentEnabled = true

    private let codec: RecordCodec
    private let transport: Transport
    private let saveDirectory: URL
    private let photoDirectory: URL
    private let deviceName: String

    private let sendLock = NSLock()   // keeps seal() order == wire order
    private let stateLock = NSLock()  // guards the fields below
    private var closed = false
    private var nextId = 1
    private var accepts: [Int: Waiter] = [:]
    private var acks: [Int: Waiter] = [:]
    private var incoming: [UInt32: IncomingFile] = [:]
    private var lastRx = Date()
    private var timer: DispatchSourceTimer?

    private let transferQueue = DispatchQueue(label: "bridge.transfer", qos: .utility, attributes: .concurrent)

    public init(
        codec: RecordCodec, transport: Transport, peerId: Data,
        saveDirectory: URL, photoDirectory: URL? = nil, deviceName: String, queue: DispatchQueue
    ) {
        self.photoDirectory = photoDirectory ?? saveDirectory
        self.codec = codec
        self.transport = transport
        self.peerId = peerId
        self.saveDirectory = saveDirectory
        self.deviceName = deviceName
        self.queue = queue
    }

    public var isClosed: Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return closed
    }

    /// Sends our hello and starts the idle watchdog (the phone pings every 15 s; 60 s of silence = dead).
    public func start() {
        sendJSON(["t": "hello", "name": deviceName, "platform": "mac",
                  "caps": ["clipboard", "notifications", "files", "reply", "status", "find", "media", "sms", "calls", "photos",
                            "dial", "contacts", "fs", "clipimg", "dnd", "handoff", "mirror", "findmac", "nowplaying", "locate", "ringer", "camera"]])
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 15, repeating: 15)
        t.setEventHandler { [weak self] in
            guard let self = self else { return }
            self.stateLock.lock()
            let idle = Date().timeIntervalSince(self.lastRx)
            self.stateLock.unlock()
            if idle > 60 { self.close(reason: "timeout") }
        }
        stateLock.lock()
        timer = t
        stateLock.unlock()
        t.resume()
    }

    public func close(reason: String = "closed") {
        stateLock.lock()
        if closed { stateLock.unlock(); return }
        closed = true
        let t = timer
        timer = nil
        let waiters = Array(accepts.values) + Array(acks.values)
        let files = Array(incoming.values)
        incoming.removeAll()
        stateLock.unlock()

        t?.cancel()
        for w in waiters { w.complete(false) }
        for f in files { f.discard() }
        transport.close()
        delegate?.session(self, didCloseWithReason: reason)
    }

    // ---- sending ----------------------------------------------------------------------------------

    private func sendRecord(type: UInt8, body: Data, completion: ((Error?) -> Void)?) {
        if isClosed { completion?(ProtocolError("closed")); return }
        sendLock.lock()
        do {
            let frameData = try codec.seal(type: type, body: body)
            transport.send(frameData) { err in completion?(err) }
            sendLock.unlock()
        } catch {
            sendLock.unlock()
            completion?(error)
            close(reason: "encryption failure")
        }
    }

    /// Waits until the transport has accepted the bytes (back-pressure for large transfers).
    private func sendRecordBlocking(type: UInt8, body: Data) -> Bool {
        let sem = DispatchSemaphore(value: 0)
        var ok = false
        sendRecord(type: type, body: body) { err in
            ok = (err == nil)
            sem.signal()
        }
        sem.wait()
        return ok
    }

    public func sendJSON(_ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: [.withoutEscapingSlashes]) else { return }
        sendRecord(type: Wire.recJSON, body: data, completion: nil)
    }

    public func sendPairConfirm(_ ok: Bool) {
        sendJSON(["t": "pair_confirm", "ok": ok])
    }

    public func sendClipboard(_ text: String) {
        sendJSON(["t": "clipboard", "text": text, "ts": Int64(Date().timeIntervalSince1970 * 1000)])
    }

    // ---- v1.1 commands (Mac → phone) ----------------------------------------------------------------

    public func sendFindPhone(on: Bool) { sendJSON(["t": "find_phone", "on": on]) }

    /// action: play_pause, next, previous, volume_up, volume_down
    public func sendMedia(_ action: String) { sendJSON(["t": "media", "action": action]) }

    /// Asks the phone for its position (answered with a phone_location message).
    public func sendLocatePhone() { sendJSON(["t": "locate_phone"]) }

    /// mode: normal, vibrate, silent
    public func sendRinger(_ mode: String) { sendJSON(["t": "ringer_set", "mode": mode]) }

    /// Opens the phone's camera; the picture comes back as a file of kind "camera".
    public func sendCameraRequest() { sendJSON(["t": "camera_request"]) }

    public func sendNotificationReply(key: String, text: String) {
        sendJSON(["t": "notification_reply", "key": key, "text": text])
    }

    public func sendNotificationDismiss(key: String) { sendJSON(["t": "notification_dismiss", "key": key]) }

    public func sendSMS(to number: String, text: String, requestId: String) {
        sendJSON(["t": "sms_send", "to": number, "text": text, "req": requestId])
    }

    /// Handoff-style: open a web link on the phone.
    public func sendOpenURL(_ url: String) { sendJSON(["t": "open_url", "url": url]) }

    /// Ask the phone to turn Do Not Disturb on or off.
    public func sendDND(_ on: Bool) { sendJSON(["t": "dnd_set", "on": on]) }

    /// action: accept | reject | hangup | speaker | mute (the last two carry `on`).
    public func sendCallAction(_ action: String, on: Bool? = nil) {
        var m: [String: Any] = ["t": "call_action", "action": action]
        if let on = on { m["on"] = on }
        sendJSON(m)
    }

    /// Sends a file in the background; `completion` is called from a background queue.
    public func sendFile(
        at url: URL, kind: String? = nil, progress: ((Int64, Int64) -> Void)? = nil,
        completion: @escaping (Bool) -> Void
    ) {
        transferQueue.async { [weak self] in
            guard let self = self else { completion(false); return }
            completion(self.sendFileBlocking(url, kind: kind, progress: progress))
        }
    }

    private func sendFileBlocking(_ url: URL, kind: String?, progress: ((Int64, Int64) -> Void)?) -> Bool {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
              let size = jsonInt(attrs[.size]),
              let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }

        stateLock.lock()
        let id = nextId
        nextId += 1
        let accept = Waiter()
        let ack = Waiter()
        accepts[id] = accept
        acks[id] = ack
        let alreadyClosed = closed
        stateLock.unlock()
        defer {
            stateLock.lock()
            accepts[id] = nil
            acks[id] = nil
            stateLock.unlock()
        }
        if alreadyClosed { return false }

        var offer: [String: Any] = ["t": "file_offer", "id": id, "name": url.lastPathComponent, "size": size]
        if let kind = kind { offer["kind"] = kind }
        sendJSON(offer)
        guard accept.wait(seconds: 60) else { return false }

        var hasher = SHA256()
        var sent: Int64 = 0
        while true {
            let chunk: Data
            do {
                guard let c = try handle.read(upToCount: Wire.chunkSize), !c.isEmpty else { break }
                chunk = c
            } catch {
                sendJSON(["t": "file_cancel", "id": id])
                return false
            }
            hasher.update(data: chunk)
            if !sendRecordBlocking(type: Wire.recChunk, body: Data.beBytes(UInt32(id)) + chunk) { return false }
            sent += Int64(chunk.count)
            progress?(sent, size)
        }
        if sent != size {
            sendJSON(["t": "file_cancel", "id": id])
            return false
        }
        sendJSON(["t": "file_done", "id": id, "sha256": Data(hasher.finalize()).hex])
        return ack.wait(seconds: 300)
    }

    // ---- receiving --------------------------------------------------------------------------------

    /// Processes one frame body (ciphertext). Call on `queue`.
    public func handle(frame body: Data) {
        if isClosed { return }
        do {
            let (type, payload) = try codec.open(body)
            stateLock.lock()
            lastRx = Date()
            stateLock.unlock()
            switch type {
            case Wire.recJSON:
                guard let obj = try JSONSerialization.jsonObject(with: payload) as? [String: Any] else {
                    throw ProtocolError("bad json")
                }
                handleJSON(obj)
            case Wire.recChunk:
                try handleChunk(payload)
            default:
                throw ProtocolError("unknown record type")
            }
        } catch {
            close(reason: (error as? ProtocolError)?.message ?? "\(error)")
        }
    }

    private func int(_ v: Any?) -> Int? { jsonInt(v).map { Int($0) } }

    private func handleJSON(_ m: [String: Any]) {
        guard let t = m["t"] as? String else { return }
        if !contentEnabled {
            switch t {
            case "hello", "pair_confirm", "ping":
                break
            case "file_offer":
                if let id = int(m["id"]) { sendJSON(["t": "file_reject", "id": id, "reason": "not paired"]) }
                return
            default:
                return
            }
        }
        switch t {
        case "hello":
            delegate?.session(self, didReceiveHello: m["name"] as? String ?? "", platform: m["platform"] as? String ?? "")
        case "pair_confirm":
            delegate?.session(self, didReceivePairConfirm: (m["ok"] as? Bool) ?? false)
        case "ping":
            sendJSON(["t": "pong"])
        case "clipboard":
            if let text = m["text"] as? String { delegate?.session(self, didReceiveClipboard: text) }
        case "notification":
            guard let key = m["key"] as? String else { return }
            delegate?.session(self, didReceiveNotification: IncomingNotification(
                key: key,
                app: m["app"] as? String ?? "",
                package: m["pkg"] as? String ?? "",
                title: m["title"] as? String ?? "",
                text: m["text"] as? String ?? "",
                timestamp: jsonInt(m["ts"]) ?? 0,
                canReply: (m["reply"] as? Bool) ?? false,
                clearable: (m["clear"] as? Bool) ?? true))
        case "notification_removed":
            if let key = m["key"] as? String { delegate?.session(self, didRemoveNotification: key) }
        case "file_offer":
            handleOffer(m)
        case "file_accept":
            if let id = int(m["id"]) { waiter(accepts: id)?.complete(true) }
        case "file_reject":
            if let id = int(m["id"]) { waiter(accepts: id)?.complete(false) }
        case "file_ack":
            if let id = int(m["id"]) { waiter(acks: id)?.complete((m["ok"] as? Bool) ?? false) }
        case "file_cancel":
            if let id = int(m["id"]) {
                stateLock.lock()
                let f = incoming.removeValue(forKey: UInt32(truncatingIfNeeded: id))
                stateLock.unlock()
                f?.discard()
            }
        case "file_done":
            handleDone(m)
        default:
            delegate?.session(self, didReceiveMessage: t, m)
        }
    }

    private func waiter(accepts id: Int) -> Waiter? {
        stateLock.lock(); defer { stateLock.unlock() }
        return accepts[id]
    }

    private func waiter(acks id: Int) -> Waiter? {
        stateLock.lock(); defer { stateLock.unlock() }
        return acks[id]
    }

    private func handleOffer(_ m: [String: Any]) {
        guard let id = int(m["id"]), let size = jsonInt(m["size"]) else { return }
        func reject(_ reason: String) { sendJSON(["t": "file_reject", "id": id, "reason": reason]) }
        guard size >= 0, size <= Wire.maxFileSize else { return reject("size") }
        let name = FileNames.safe(m["name"] as? String ?? "")
        let directory = (m["kind"] as? String) == "photo" ? photoDirectory : saveDirectory
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let partial = directory.appendingPathComponent(".bridge-partial-\(UUID().uuidString)")
            guard FileManager.default.createFile(atPath: partial.path, contents: nil) else { return reject("storage") }
            let handle = try FileHandle(forWritingTo: partial)
            let file = IncomingFile(name: name, size: size, directory: directory, kind: m["kind"] as? String,
                                    partialURL: partial, handle: handle)
            stateLock.lock()
            let old = incoming.updateValue(file, forKey: UInt32(truncatingIfNeeded: id))
            stateLock.unlock()
            old?.discard()
            sendJSON(["t": "file_accept", "id": id])
        } catch {
            reject("storage")
        }
    }

    private func handleChunk(_ body: Data) throws {
        guard body.count >= 4 else { throw ProtocolError("short chunk") }
        let id = body.sub(0, 4).beUInt32
        stateLock.lock()
        let f = incoming[id]
        stateLock.unlock()
        guard let file = f else { return }
        let data = body.sub(4, body.count)
        file.received += Int64(data.count)
        if file.received > file.size {
            stateLock.lock()
            incoming[id] = nil
            stateLock.unlock()
            file.discard()
            return
        }
        file.hasher.update(data: data)
        do {
            try file.handle.write(contentsOf: data)
        } catch {
            stateLock.lock()
            incoming[id] = nil
            stateLock.unlock()
            file.discard()
        }
    }

    private func handleDone(_ m: [String: Any]) {
        guard let id = int(m["id"]) else { return }
        stateLock.lock()
        let f = incoming.removeValue(forKey: UInt32(truncatingIfNeeded: id))
        stateLock.unlock()
        var ok = false
        var finalURL: URL?
        var kind: String?
        if let file = f {
            kind = file.kind
            try? file.handle.close()
            let digest = Data(file.hasher.finalize()).hex
            if file.received == file.size && digest == (m["sha256"] as? String ?? "") {
                let dest = FileNames.uniqueURL(in: file.directory, name: file.name)
                do {
                    try FileManager.default.moveItem(at: file.partialURL, to: dest)
                    ok = true
                    finalURL = dest
                } catch {
                    ok = false
                }
            }
            if !ok { try? FileManager.default.removeItem(at: file.partialURL) }
        }
        sendJSON(["t": "file_ack", "id": id, "ok": ok])
        if let url = finalURL { delegate?.session(self, didReceiveFile: url, kind: kind) }
    }
}
