import AppKit
import BridgeCore

/// "Pairing mode" window: while it is open the server accepts new devices.
final class PairingMode {
    private let lock = NSLock()
    private var until: Date?

    var enabled: Bool {
        lock.lock(); defer { lock.unlock() }
        if let u = until, u > Date() { return true }
        until = nil
        return false
    }

    func enable(seconds: TimeInterval) {
        lock.lock(); until = Date().addingTimeInterval(seconds); lock.unlock()
    }

    func disable() {
        lock.lock(); until = nil; lock.unlock()
    }
}

/// Both people must confirm the code. Whichever confirmation arrives last produces the outcome.
final class PairAttempt {
    enum Outcome { case paired, rejected }

    private let lock = NSLock()
    private var user: Bool?
    private var peer: Bool?
    private var done = false

    func setUser(_ v: Bool) -> Outcome? {
        lock.lock(); defer { lock.unlock() }
        user = v
        return evaluate()
    }

    func setPeer(_ v: Bool) -> Outcome? {
        lock.lock(); defer { lock.unlock() }
        peer = v
        return evaluate()
    }

    private func evaluate() -> Outcome? {
        if done { return nil }
        if user == false || peer == false { done = true; return .rejected }
        if user == true && peer == true { done = true; return .paired }
        return nil
    }
}

/// Session delegate for one connection. Callbacks arrive on the connection queue; UI work is hopped to main.
final class PeerHandler: SessionDelegate {
    weak var app: AppDelegate?
    let peerId: Data
    let established: Established
    let attempt: PairAttempt?
    var displayName: String

    init(app: AppDelegate, established: Established, displayName: String) {
        self.app = app
        self.established = established
        self.peerId = established.peerId
        self.attempt = established.mode == .pair ? PairAttempt() : nil
        self.displayName = displayName
    }

    var isPairing: Bool { attempt != nil }

    func session(_ s: Session, didReceiveHello name: String, platform: String) {
        guard !isPairing, !name.isEmpty else { return }
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.displayName = name
            self.app?.refreshStatus()
        }
    }

    func session(_ s: Session, didReceivePairConfirm ok: Bool) {
        guard let a = attempt, let outcome = a.setPeer(ok) else { return }
        finish(outcome, session: s)
    }

    func userDecided(_ ok: Bool, session s: Session) {
        guard let a = attempt else { return }
        s.sendPairConfirm(ok)
        if let outcome = a.setUser(ok) { finish(outcome, session: s) }
    }

    private func finish(_ outcome: PairAttempt.Outcome, session s: Session) {
        switch outcome {
        case .paired:
            guard let app = app, let secret = established.pairingSecret else { return }
            let name = established.peerName ?? "Телефон"
            app.config.addDevice(id: peerId, name: name, secret: secret)
            DispatchQueue.main.async {
                app.notifier.notice("Bridge", "Сопряжено: \(name)")
                app.refreshStatus()
            }
            // The phone closes this session and reconnects with the new secret; this is only a fallback.
            s.queue.asyncAfter(deadline: .now() + 5) { s.close(reason: "paired") }
        case .rejected:
            DispatchQueue.main.async { self.app?.notifier.notice("Bridge", "Сопряжение отменено") }
            s.close(reason: "pairing rejected")
        }
    }

    func session(_ s: Session, didReceiveClipboard text: String) {
        DispatchQueue.main.async { [weak self] in self?.app?.applyIncomingClipboard(text) }
    }

    func session(_ s: Session, didReceiveNotification n: IncomingNotification) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.app?.showPhoneNotification(peer: self.peerId.hex, n)
        }
    }

    func session(_ s: Session, didRemoveNotification key: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.app?.notifier.remove(id: "\(self.peerId.hex):\(key)")
        }
    }

    func session(_ s: Session, didReceiveFile url: URL, kind: String?) {
        DispatchQueue.main.async { [weak self] in
            guard let app = self?.app else { return }
            switch kind {
            case "photo":
                // Coalesced: one notification that is replaced by each new photo.
                app.notifier.show(id: "bridge.photos", title: "Фото с телефона", body: url.lastPathComponent)
            case "camera":
                app.notifier.notice("Снимок с телефона", url.lastPathComponent)
                NSWorkspace.shared.open(url)
            case "clipimg":
                app.applyIncomingImage(url)
            case "pull":
                app.notifier.notice("Файл скачан с телефона", url.lastPathComponent)
                NSWorkspace.shared.activateFileViewerSelecting([url])
            default:
                app.notifier.notice("Файл от телефона", url.lastPathComponent)
            }
        }
    }

    func session(_ s: Session, didReceiveMessage type: String, _ m: [String: Any]) {
        guard !isPairing else { return }
        func int(_ k: String) -> Int? { (m[k] as? NSNumber)?.intValue }
        func str(_ k: String) -> String? { m[k] as? String }
        let peer = peerId.hex
        switch type {
        case "status":
            let st = PhoneStatus(battery: int("battery") ?? -1, charging: (m["charging"] as? Bool) ?? false,
                                 net: str("net") ?? "")
            DispatchQueue.main.async { [weak self] in
                self?.app?.checkBattery(peer: peer, st)
                self?.app?.phoneStatus[peer] = st
                self?.app?.refreshStatus()
            }
        case "pointer", "click", "scroll", "mac_key", "mac_text", "mac_media":
            app?.remoteInput.handle(type, m)
        case "mirror_frame":
            guard let b64 = str("d"), let data = Data(base64Encoded: b64) else { return }
            s.sendJSON(["t": "mirror_ack", "seq": int("seq") ?? 0])
            DispatchQueue.main.async { [weak self] in self?.app?.mirror?.frame(data) }
        case "mirror_audio":
            guard let b64 = str("d"), let data = Data(base64Encoded: b64) else { return }
            DispatchQueue.main.async { [weak self] in self?.app?.mirror?.playAudio(data) }
        case "sms_threads":
            let items = (m["items"] as? [[String: Any]]) ?? []
            let err = str("error")
            let isSearch = (m["search"] as? Bool) ?? false
            DispatchQueue.main.async { [weak self] in self?.app?.messages?.setThreads(items, error: err, isSearch: isSearch) }
        case "sms_thread":
            let items = (m["items"] as? [[String: Any]]) ?? []
            let tid = (m["tid"] as? NSNumber)?.int64Value ?? -1
            DispatchQueue.main.async { [weak self] in self?.app?.messages?.setMessages(tid: tid, items) }
        case "mirror_state":
            let st = str("state") ?? "off"
            let control = (m["control"] as? Bool) ?? false
            DispatchQueue.main.async { [weak self] in self?.app?.mirror?.state(st, control: control) }
        case "find_mac":
            let on = (m["on"] as? Bool) ?? false
            DispatchQueue.main.async { [weak self] in self?.app?.findMac(on) }
        case "lock_mac":
            DispatchQueue.main.async { [weak self] in self?.app?.lockMac() }
        case "open_url":
            guard let url = str("url") else { return }
            DispatchQueue.main.async { [weak self] in self?.app?.showLink(url) }
        case "mac_power":
            let action = str("action") ?? ""
            DispatchQueue.main.async { [weak self] in self?.app?.macPower(action) }
        case "mac_volume":
            let action = str("action") ?? ""
            DispatchQueue.main.async { [weak self] in self?.app?.macVolume(action) }
        case "now_playing":
            let title = str("title") ?? "", artist = str("artist") ?? "", appName = str("app") ?? ""
            let playing = (m["playing"] as? Bool) ?? false
            DispatchQueue.main.async { [weak self] in
                if title.isEmpty { self?.app?.nowPlaying[peer] = nil }
                else { self?.app?.nowPlaying[peer] = (title: title, artist: artist, app: appName, playing: playing) }
            }
        case "phone_location":
            DispatchQueue.main.async { [weak self] in self?.app?.phoneLocated(m) }
        case "mac_screenshot":
            DispatchQueue.main.async { [weak self] in self?.app?.macScreenshotForPhone() }
        case "locate_mac":
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                self.app?.locateMacForPhone(peer: self.peerId.hex)
            }
        case "phone_agenda":
            let ag = PhoneAgenda(m)
            DispatchQueue.main.async { [weak self] in self?.app?.agenda[peer] = ag }
        case "ringer":
            let mode = str("mode") ?? "normal"
            DispatchQueue.main.async { [weak self] in self?.app?.phoneRinger[peer] = mode }
        case "dnd":
            let on = (m["on"] as? Bool) ?? false
            DispatchQueue.main.async { [weak self] in self?.app?.phoneDNDChanged(peer: peer, on: on) }
        case "sms":
            guard let from = str("from"), let text = str("text") else { return }
            let name = str("name")
            let id = int("id") ?? Int(Date().timeIntervalSince1970)
            DispatchQueue.main.async { [weak self] in
                self?.app?.showSMS(peer: peer, id: id, from: from, name: name, text: text)
                self?.app?.messages?.incoming(from: from)
            }
        case "sms_result":
            let ok = (m["ok"] as? Bool) ?? false
            DispatchQueue.main.async { [weak self] in
                self?.app?.notifier.notice(ok ? "SMS отправлено" : "SMS не отправлено",
                                           ok ? "" : "Проверьте разрешения Bridge на телефоне")
            }
        case "call":
            let state = str("state") ?? ""
            let number = str("number"), name = str("name")
            DispatchQueue.main.async { [weak self] in
                self?.app?.showCall(peer: peer, state: state, number: number, name: name)
            }
        case "contacts":
            let raw = (m["items"] as? [[String: Any]]) ?? []
            let items = raw.compactMap { e -> (name: String, number: String)? in
                guard let n = e["n"] as? String, let p = e["p"] as? String else { return nil }
                return (n, p)
            }
            DispatchQueue.main.async { [weak self] in self?.app?.contacts = items }
        case "calls":
            let raw = (m["items"] as? [[String: Any]]) ?? []
            let items = raw.compactMap { e -> RecentCall? in
                guard let number = e["number"] as? String, let type = e["type"] as? String else { return nil }
                let ms = (e["ts"] as? NSNumber)?.doubleValue ?? 0
                return RecentCall(number: number, name: e["name"] as? String, type: type,
                                  date: Date(timeIntervalSince1970: ms / 1000))
            }
            DispatchQueue.main.async { [weak self] in self?.app?.recentCalls = items }
        case "fs_list_result":
            DispatchQueue.main.async { [weak self] in self?.app?.fileBrowser?.handleListResult(m) }
        case "fs_get_result":
            DispatchQueue.main.async { [weak self] in
                if let err = self?.app?.fileBrowser?.handleGetResult(m) { self?.app?.notifier.notice("Bridge", err) }
            }
        default:
            break
        }
    }

    func session(_ s: Session, didCloseWithReason reason: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.app?.handlerClosed(self, session: s)
        }
    }
}
