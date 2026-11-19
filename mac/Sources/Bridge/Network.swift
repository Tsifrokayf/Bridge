import Foundation
import Network
import BridgeCore

/// A byte pipe to a phone: a TCP connection or a Bluetooth LE link. Callbacks run on the queue given to `start`.
protocol ByteLink: AnyObject {
    var onData: ((Data) -> Void)? { get set }
    var onEnd: ((String) -> Void)? { get set }
    func start(queue: DispatchQueue)
    func send(_ data: Data, completion: @escaping (Error?) -> Void)
    func cancel()
}

final class TCPLink: ByteLink {
    private let connection: NWConnection
    var onData: ((Data) -> Void)?
    var onEnd: ((String) -> Void)?
    private var ended = false

    init(_ connection: NWConnection) { self.connection = connection }

    func start(queue: DispatchQueue) {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed(let e): self?.end("connection failed: \(e)")
            case .cancelled: self?.end("closed")
            default: break
            }
        }
        connection.start(queue: queue)
        receiveLoop()
    }

    private func end(_ reason: String) {
        if ended { return }
        ended = true
        onEnd?(reason)
    }

    private func receiveLoop() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self = self else { return }
            if let data = data, !data.isEmpty { self.onData?(data) }
            if error != nil || isComplete {
                self.end(error.map { "\($0)" } ?? "closed by peer")
                return
            }
            if !self.ended { self.receiveLoop() }
        }
    }

    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        connection.send(content: data, completion: .contentProcessed { completion($0) })
    }

    func cancel() { connection.cancel() }
}

/// One accepted connection (TCP or Bluetooth): runs the server handshake, then hands frames to a Session.
final class PeerConnection: Transport {
    enum Event {
        case established(Established, PeerConnection)
        case failed(String)
    }

    let queue = DispatchQueue(label: "bridge.conn")
    private let link: ByteLink
    private var decoder = FrameDecoder()
    private var handshake: ServerHandshake?
    private(set) var session: Session?
    /// Strong reference holder for the session's (weak) delegate.
    var handler: AnyObject?
    private var finished = false
    private let saveDirectory: URL
    private let photoDirectory: URL
    private let deviceName: String
    private let onEstablished: (Established, PeerConnection) -> Void
    private let onClosed: (PeerConnection, String) -> Void
    private var timeout: DispatchWorkItem?
    var handshakeTimeout: TimeInterval = 10

    init(
        link: ByteLink, handshake: ServerHandshake, saveDirectory: URL, photoDirectory: URL, deviceName: String,
        onEstablished: @escaping (Established, PeerConnection) -> Void,
        onClosed: @escaping (PeerConnection, String) -> Void
    ) {
        self.link = link
        self.handshake = handshake
        self.saveDirectory = saveDirectory
        self.photoDirectory = photoDirectory
        self.deviceName = deviceName
        self.onEstablished = onEstablished
        self.onClosed = onClosed
    }

    func start() {
        link.onEnd = { [weak self] reason in self?.fail(reason) }
        link.onData = { [weak self] data in self?.received(data) }
        link.start(queue: queue)
        let t = DispatchWorkItem { [weak self] in
            guard let self = self, self.session == nil else { return }
            self.fail("handshake timeout")
        }
        timeout = t
        queue.asyncAfter(deadline: .now() + handshakeTimeout, execute: t)
    }

    private func received(_ data: Data) {
        if finished { return }
        decoder.append(data)
        do {
            while let frameBody = try decoder.next() {
                try dispatch(frame: frameBody)
            }
        } catch {
            fail((error as? ProtocolError)?.message ?? "\(error)")
        }
    }

    private func dispatch(frame body: Data) throws {
        if let s = session {
            s.handle(frame: body)
            return
        }
        guard let hs = handshake else { throw ProtocolError("unexpected frame") }
        switch try hs.receive(frame: body) {
        case .reply(let r):
            sendRaw(frame(r))
        case .established(let est, let reply):
            if let r = reply { sendRaw(frame(r)) }
            handshake = nil
            timeout?.cancel()
            let s = Session(codec: est.codec, transport: self, peerId: est.peerId,
                            saveDirectory: saveDirectory, photoDirectory: photoDirectory, deviceName: deviceName, queue: queue)
            // Until the user has confirmed the code, a freshly paired phone may not push anything.
            s.contentEnabled = (est.mode == .reconnect)
            session = s
            onEstablished(est, self)
        }
    }

    private func sendRaw(_ data: Data) {
        link.send(data) { _ in }
    }

    private func fail(_ reason: String) {
        if finished { return }
        finished = true
        timeout?.cancel()
        link.cancel()
        if let s = session {
            s.close(reason: reason)
        }
        onClosed(self, reason)
    }

    // Transport
    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        link.send(data, completion: completion)
    }

    func close() {
        queue.async { [weak self] in
            guard let self = self, !self.finished else { return }
            self.finished = true
            self.timeout?.cancel()
            self.link.cancel()
            self.onClosed(self, "closed")
        }
    }
}

/// TCP listener advertised over Bonjour as _bridgesync._tcp.
final class BridgeServer {
    private var listener: NWListener?
    private let queue = DispatchQueue(label: "bridge.listener")
    private let config: Config
    private let pairingEnabled: () -> Bool
    private let onEstablished: (Established, PeerConnection) -> Void
    private let onClosed: (PeerConnection, String) -> Void
    private let lock = NSLock()
    private var _port: UInt16 = 0
    private var connections: [ObjectIdentifier: PeerConnection] = [:]

    var port: UInt16 {
        lock.lock(); defer { lock.unlock() }
        return _port
    }

    var onPortChange: (() -> Void)?

    init(
        config: Config, pairingEnabled: @escaping () -> Bool,
        onEstablished: @escaping (Established, PeerConnection) -> Void,
        onClosed: @escaping (PeerConnection, String) -> Void
    ) {
        self.config = config
        self.pairingEnabled = pairingEnabled
        self.onEstablished = onEstablished
        self.onClosed = onClosed
    }

    /// DNS-SD TXT record: length-prefixed "key=value" strings.
    private static func txt(_ pairs: [(String, String)]) -> Data {
        var d = Data()
        for (k, v) in pairs {
            let entry = Data("\(k)=\(v)".utf8)
            d.append(UInt8(min(entry.count, 255)))
            d.append(entry.prefix(255))
        }
        return d
    }

    func start() throws { try launch(fixedPort: true) }

    /// Tries the well-known port first (so the phone can find us by scanning the subnet even when
    /// mDNS does not cross between Wi-Fi and Ethernet); falls back to any free port.
    private func launch(fixedPort: Bool) throws {
        let params = NWParameters.tcp
        params.allowLocalEndpointReuse = true
        let l: NWListener
        if fixedPort, let p = NWEndpoint.Port(rawValue: Wire.defaultPort) {
            l = try NWListener(using: params, on: p)
        } else {
            l = try NWListener(using: params)
        }
        l.service = NWListener.Service(
            name: config.deviceName, type: Wire.serviceType, domain: nil,
            txtRecord: BridgeServer.txt([("v", "1"), ("id", config.deviceId.hex)]))
        l.stateUpdateHandler = { [weak self, weak l] state in
            guard let self = self, let l = l else { return }
            switch state {
            case .ready:
                self.lock.lock()
                self._port = l.port?.rawValue ?? 0
                self.lock.unlock()
                self.onPortChange?()
            case .failed:
                l.cancel()
                if fixedPort {
                    // Port taken by someone else: use a random one instead.
                    self.queue.async { try? self.launch(fixedPort: false) }
                }
            default:
                break
            }
        }
        l.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
        listener = l
        l.start(queue: queue)
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func accept(_ conn: NWConnection) { accept(link: TCPLink(conn)) }

    /// Takes over an already-established byte pipe (TCP or Bluetooth).
    func accept(link: ByteLink, handshakeTimeout: TimeInterval = 10) {
        let cfg = config
        let hs = ServerHandshake(
            deviceId: cfg.deviceId, name: cfg.deviceName,
            pairingEnabled: pairingEnabled,
            lookupSecret: { id in cfg.secret(for: id) })
        let pc = PeerConnection(
            link: link, handshake: hs, saveDirectory: config.saveDirectory, photoDirectory: config.photoDirectory, deviceName: config.deviceName,
            onEstablished: { [weak self] est, p in self?.onEstablished(est, p) },
            onClosed: { [weak self] p, reason in
                self?.lock.lock()
                self?.connections[ObjectIdentifier(p)] = nil
                self?.lock.unlock()
                self?.onClosed(p, reason)
            })
        pc.handshakeTimeout = handshakeTimeout
        lock.lock()
        connections[ObjectIdentifier(pc)] = pc  // keeps the connection alive until it closes
        lock.unlock()
        pc.start()
    }
}

/// IPv4 addresses of this Mac on local interfaces, for manual pairing.
func localIPv4Addresses() -> [String] {
    var result: [String] = []
    var ifaddr: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
    defer { freeifaddrs(ifaddr) }
    var ptr: UnsafeMutablePointer<ifaddrs>? = first
    while let p = ptr {
        let ifa = p.pointee
        ptr = ifa.ifa_next
        guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
        let flags = Int32(ifa.ifa_flags)
        guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
        let name = String(cString: ifa.ifa_name)
        guard name.hasPrefix("en") else { continue }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
            let ip = String(cString: host)
            if !ip.hasPrefix("169.254.") { result.append(ip) }
        }
    }
    return result
}
