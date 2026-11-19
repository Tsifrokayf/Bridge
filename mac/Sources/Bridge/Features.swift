import AppKit
import Intents
import BridgeCore

struct PhoneStatus {
    var battery: Int
    var charging: Bool
    var net: String

    var text: String {
        let netName: String
        switch net {
        case "wifi": netName = "Wi‑Fi"
        case "mobile": netName = "мобильная сеть"
        case "none": netName = "нет сети"
        default: netName = net
        }
        return "заряд \(battery)%\(charging ? " (заряжается)" : "") · \(netName)"
    }
}

/// Phone-specific features: find phone, media keys, SMS/call notifications, notification actions,
/// and everything that sends things to the phone from outside the menu (Services, Share, drag & drop).
extension AppDelegate {
    private func sessions(for peer: String? = nil) -> [Session] {
        if let p = peer { return active[p].map { [$0.session] } ?? [] }
        return active.values.map { $0.session }
    }

    // ---- menu actions -------------------------------------------------------------------------------

    @objc func toggleFindPhone() {
        let on = !isFinding
        for s in sessions() { s.sendFindPhone(on: on) }
        isFinding = on
        if on {
            notifier.notice("Bridge", "Телефон подаёт сигнал. Выключить: меню Bridge → «Выключить сигнал».")
        }
    }

    /// "Where is my phone": the phone answers with its last position, shown in Apple Maps.
    @objc func locatePhone() {
        for s in sessions() { s.sendLocatePhone() }
        notifier.notice("Bridge", "Запрашиваю положение телефона…")
    }

    func phoneLocated(_ m: [String: Any]) {
        if let err = m["error"] as? String {
            notifier.notice("Положение телефона недоступно",
                            err == "permission" ? "Разрешите Bridge доступ к геопозиции на телефоне" : "Не удалось определить положение")
            return
        }
        guard let lat = (m["lat"] as? NSNumber)?.doubleValue, let lon = (m["lon"] as? NSNumber)?.doubleValue else { return }
        let acc = (m["acc"] as? NSNumber)?.intValue ?? 0
        let ts = (m["ts"] as? NSNumber)?.doubleValue ?? 0
        let age = ts > 0 ? Int(Date().timeIntervalSince1970 - ts / 1000) : 0
        let when = age > 90 ? " (данные \(age / 60) мин назад)" : ""
        notifier.notice("Телефон найден", "Точность ≈ \(acc) м\(when)")
        var c = URLComponents(string: "https://maps.apple.com/")!
        c.queryItems = [URLQueryItem(name: "ll", value: "\(lat),\(lon)"), URLQueryItem(name: "q", value: "Телефон")]
        if let url = c.url { NSWorkspace.shared.open(url) }
    }

    /// Phone → Mac: "where is my Mac" (needs the Location permission of macOS; asked on first use).
    func locateMacForPhone(peer: String) {
        let provider = LocationProvider()
        macLocator = provider   // keep alive until it answers
        provider.request { [weak self] result in
            guard let self = self else { return }
            self.macLocator = nil
            var msg: [String: Any] = ["t": "mac_location"]
            switch result {
            case .success(let loc):
                msg["lat"] = loc.coordinate.latitude
                msg["lon"] = loc.coordinate.longitude
                msg["acc"] = Int(loc.horizontalAccuracy)
            case .failure(let e):
                msg["error"] = (e as? LocationProvider.Failure) == .denied ? "permission" : "unavailable"
                if (e as? LocationProvider.Failure) == .denied {
                    self.notifier.notice("Bridge не может определить положение Mac",
                                         "Разрешите геопозицию: Системные настройки → Конфиденциальность → Службы геолокации")
                }
            }
            for s in self.sessions(for: peer) { s.sendJSON(msg) }
        }
    }

    /// Handoff of navigation: an address typed here opens as a route in the phone's maps app.
    @objc func routeOnPhone() {
        NSApp.activate(ignoringOtherApps: true)
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 340, height: 24))
        field.placeholderString = "Адрес или название места"
        let a = NSAlert()
        a.messageText = "Проложить маршрут на телефоне"
        a.informativeText = "Адрес придёт уведомлением; по нажатию откроется навигация."
        a.accessoryView = field
        a.addButton(withTitle: "Отправить")
        a.addButton(withTitle: "Отмена")
        a.window.initialFirstResponder = field
        guard a.runModal() == .alertFirstButtonReturn else { return }
        let q = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        var c = URLComponents(string: "https://www.google.com/maps/dir/")!
        c.queryItems = [URLQueryItem(name: "api", value: "1"), URLQueryItem(name: "destination", value: q)]
        if let url = c.url { for s in sessions() { s.sendOpenURL(url.absoluteString) } }
    }

    /// Handoff of the browser tab: the address of the front tab of the browser used last goes to the phone.
    @objc func openBrowserTabOnPhone() {
        let running = Set(NSWorkspace.shared.runningApplications.compactMap { $0.bundleIdentifier })
        var order: [String] = []
        if let l = lastBrowser, running.contains(l) { order.append(l) }
        order += BrowserTabs.known.keys.sorted().filter { running.contains($0) && !order.contains($0) }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            var found: String?
            for id in order { if let u = BrowserTabs.frontURL(bundleId: id) { found = u; break } }
            DispatchQueue.main.async {
                guard let self = self else { return }
                if let u = found, let url = self.webURL(u) {
                    for s in self.sessions() { s.sendOpenURL(url.absoluteString) }
                    self.notifier.notice("Вкладка отправлена на телефон", url.host ?? url.absoluteString)
                } else {
                    self.notifier.notice("Не удалось получить вкладку",
                                         "Откройте страницу в Safari, Chrome, Яндекс, Brave, Edge или Arc и разрешите Bridge управлять браузером")
                }
            }
        }
    }

    /// Select an area of the screen and send the picture to the phone.
    @objc func screenshotToPhone() {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("Скриншот \(Int(Date().timeIntervalSince1970)).png")
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
            p.arguments = ["-i", "-x", file.path]   // interactive selection, no shutter sound
            guard (try? p.run()) != nil else { return }
            p.waitUntilExit()
            guard FileManager.default.fileExists(atPath: file.path) else { return }   // cancelled with Esc
            DispatchQueue.main.async { self?.sendToPhones(urls: [file], deleteAfter: true) }
        }
    }

    /// Phone → Mac: full-screen capture sent back as a file (macOS asks for Screen Recording on first use).
    func macScreenshotForPhone() {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("Скриншот Mac \(Int(Date().timeIntervalSince1970)).png")
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
            p.arguments = ["-x", file.path]
            guard (try? p.run()) != nil else { return }
            p.waitUntilExit()
            DispatchQueue.main.async {
                guard let self = self else { return }
                if FileManager.default.fileExists(atPath: file.path) {
                    self.sendToPhones(urls: [file], deleteAfter: true)
                } else {
                    self.notifier.notice("Bridge", "Не удалось сделать снимок экрана. Разрешите запись экрана для Bridge в Системных настройках.")
                }
            }
        }
    }

    /// Phone → Mac: put the Mac to sleep.
    func macPower(_ action: String) {
        guard action == "sleep" else { return }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        p.arguments = ["sleepnow"]
        try? p.run()
    }

    /// Phone → Mac: mute toggle / volume steps (AppleScript volume commands need no extra permission).
    func macVolume(_ action: String) {
        let script: String
        switch action {
        case "mute_toggle": script = "set volume output muted (not (output muted of (get volume settings)))"
        case "up": script = "set volume output volume ((output volume of (get volume settings)) + 6)"
        case "down": script = "set volume output volume ((output volume of (get volume settings)) - 6)"
        default: return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            p.arguments = ["-e", script]
            p.standardOutput = Pipe(); p.standardError = Pipe()
            try? p.run()
        }
    }

    @objc func phoneCamera() {
        for s in sessions() { s.sendCameraRequest() }
        notifier.notice("Bridge", "На телефоне открывается камера: нажмите уведомление, если она не открылась сама")
    }

    @objc func setPhoneRinger(_ sender: NSMenuItem) {
        guard let mode = sender.representedObject as? String else { return }
        for s in sessions() { s.sendRinger(mode) }
    }

    @objc func toggleAutoLock() {
        config.autoLock.toggle()
        if !config.autoLock { autoLockTimer?.invalidate(); autoLockTimer = nil }
    }

    /// The phone disappeared (out of range, switched off, left with it): lock the Mac after a grace period.
    /// Only when the user opted in, and not when the Mac itself is going to sleep or the screen is already locked.
    func scheduleAutoLock() {
        guard config.autoLock, !config.devices.isEmpty else { return }
        autoLockTimer?.invalidate()
        autoLockTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in
            guard let self = self, self.config.autoLock, self.active.isEmpty else { return }
            self.lockMac()
        }
    }

    @objc func mediaCommand(_ sender: NSMenuItem) {
        guard let action = sender.representedObject as? String else { return }
        for s in sessions() { s.sendMedia(action) }
    }

    // ---- notification buttons -----------------------------------------------------------------------

    func handleNotificationAction(_ action: String, _ text: String?, _ info: [String: String]) {
        let peer = info["peer"]
        switch action {
        case "reply":
            guard let text = text, !text.isEmpty else { return }
            if let number = info["number"] {
                let req = UUID().uuidString
                for s in sessions(for: peer) { s.sendSMS(to: number, text: text, requestId: req) }
            } else if let key = info["key"] {
                for s in sessions(for: peer) { s.sendNotificationReply(key: key, text: text) }
            }
        case "dismiss":
            if let key = info["key"] { for s in sessions(for: peer) { s.sendNotificationDismiss(key: key) } }
        case "reject":
            for s in sessions(for: peer) { s.sendCallAction("reject") }
        case "open":
            if let u = info["url"] { openLink(u) }
        case "rejectsms":
            for s in sessions(for: peer) {
                s.sendCallAction("reject")
                if let number = info["number"], !number.isEmpty {
                    s.sendSMS(to: number, text: config.quickReply, requestId: UUID().uuidString)
                }
            }
            if (info["number"] ?? "").isEmpty { notifier.notice("Звонок сброшен", "Номер неизвестен: SMS не отправлено") }
        case "accept":
            for s in sessions(for: peer) { s.sendCallAction("accept") }
        default:
            break
        }
    }

    func showSMS(peer: String, id: Int, from: String, name: String?, text: String) {
        recentSMS.removeAll { $0.number == from }
        recentSMS.insert(RecentSMS(number: from, name: name, text: text), at: 0)
        if recentSMS.count > 8 { recentSMS.removeLast() }
        guard config.showNotifications else { return }
        notifier.show(id: "sms:\(peer):\(id)", title: name ?? from, subtitle: name == nil ? "" : from, body: text,
                      category: .sms, userInfo: ["peer": peer, "number": from])
    }

    func showCall(peer: String, state: String, number: String?, name: String?) {
        let id = "call:\(peer)"
        switch state {
        case "ringing", "offhook":
            let prev = activeCall?.peer == peer ? activeCall : nil
            activeCall = ActiveCall(peer: peer, state: state, number: number ?? prev?.number, name: name ?? prev?.name)
        default: activeCall = nil; callSpeaker = false; callMuted = false
        }
        switch state {
        case "ringing":
            guard config.showNotifications else { return }
            notifier.show(id: id, title: "Входящий звонок", subtitle: name ?? "", body: number ?? "Неизвестный номер",
                          category: .call, userInfo: ["peer": peer, "number": number ?? ""])
        default:
            notifier.remove(id: id)
        }
    }

    // ---- active call controls (menu) ----------------------------------------------------------------

    func addActiveCallMenu(to menu: NSMenu) {
        guard let c = activeCall else { return }
        let who = c.name ?? c.number ?? "Неизвестный номер"
        let title = c.state == "ringing" ? "Входящий: \(who)" : "Разговор: \(who)"
        let head = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        head.isEnabled = false
        menu.addItem(head)
        func item(_ t: String, _ sel: Selector, checked: Bool = false) {
            let i = NSMenuItem(title: t, action: sel, keyEquivalent: "")
            i.target = self
            i.state = checked ? .on : .off
            menu.addItem(i)
        }
        if c.state == "ringing" {
            item("Ответить", #selector(callAccept))
            item("Сбросить", #selector(callHangup))
        } else {
            item("Завершить звонок", #selector(callHangup))
            item("Громкая связь на телефоне", #selector(callToggleSpeaker), checked: callSpeaker)
            item("Выключить микрофон телефона", #selector(callToggleMute), checked: callMuted)
        }
        menu.addItem(.separator())
    }

    @objc func callAccept() {
        guard let c = activeCall else { return }
        for s in sessions(for: c.peer) { s.sendCallAction("accept") }
    }

    @objc func callHangup() {
        guard let c = activeCall else { return }
        for s in sessions(for: c.peer) { s.sendCallAction("hangup") }
    }

    @objc func callToggleSpeaker() {
        guard let c = activeCall else { return }
        callSpeaker.toggle()
        for s in sessions(for: c.peer) { s.sendCallAction("speaker", on: callSpeaker) }
    }

    @objc func callToggleMute() {
        guard let c = activeCall else { return }
        callMuted.toggle()
        for s in sessions(for: c.peer) { s.sendCallAction("mute", on: callMuted) }
    }

    // ---- sending from outside the menu --------------------------------------------------------------

    /// Writes outbox/.progress-<batch> so the Share extension can show a progress bar.
    private func writeProgress(_ batch: String, state: String, done: Int64, total: Int64, message: String = "") {
        let url = config.outboxDirectory.appendingPathComponent(".progress-" + batch)
        let obj: [String: Any] = ["state": state, "done": done, "total": total, "message": message]
        if let data = try? JSONSerialization.data(withJSONObject: obj) { try? data.write(to: url, options: .atomic) }
    }

    /// Sends files (and, for .bridgetext, text) to every connected phone.
    /// `batch` is set for items from the Share extension: progress is reported back to it.
    func sendToPhones(urls: [URL], deleteAfter: Bool = false, batch: String? = nil) {
        func cleanup() { if deleteAfter { for u in urls { try? FileManager.default.removeItem(at: u) } } }
        guard !active.isEmpty else {
            notifier.notice("Bridge", "Телефон не подключён — ничего не отправлено")
            if let b = batch { writeProgress(b, state: "failed", done: 0, total: 0, message: "Телефон не подключён") }
            cleanup()
            return
        }
        let all = sessions()
        let sizes = urls.map { u -> Int64 in
            ((try? FileManager.default.attributesOfItem(atPath: u.path))?[.size] as? NSNumber)?.int64Value ?? 0
        }
        // Progress bookkeeping shared by the transfer callbacks (they run on background queues).
        let lock = NSLock()
        var sentByTransfer: [String: Int64] = [:]
        var finished = 0
        var failed = false
        var lastWrite = Date.distantPast
        let fileCount = urls.filter { $0.pathExtension != "bridgetext" }.count
        let totalTransfers = fileCount * all.count
        let totalBytes = zip(urls, sizes).filter { $0.0.pathExtension != "bridgetext" }.map { $0.1 }.reduce(0, +) * Int64(all.count)
        if let b = batch { writeProgress(b, state: "sending", done: 0, total: totalBytes) }

        for url in urls where url.pathExtension == "bridgetext" {
            if let text = try? String(contentsOf: url, encoding: .utf8), !text.isEmpty {
                for s in all { s.sendClipboard(text) }
                notifier.notice("Текст отправлен на телефон", String(text.prefix(80)))
            }
            if deleteAfter { try? FileManager.default.removeItem(at: url) }
        }
        if totalTransfers == 0 {
            if let b = batch { writeProgress(b, state: "done", done: 0, total: 0) }
            return
        }

        for (i, url) in urls.enumerated() where url.pathExtension != "bridgetext" {
            for (j, s) in all.enumerated() {
                let key = "\(i)-\(j)"
                s.sendFile(
                    at: url,
                    progress: { [weak self] sent, _ in
                        guard let self = self, let b = batch else { return }
                        lock.lock()
                        sentByTransfer[key] = sent
                        let done = sentByTransfer.values.reduce(0, +)
                        let due = Date().timeIntervalSince(lastWrite) > 0.2
                        if due { lastWrite = Date() }
                        lock.unlock()
                        if due { self.writeProgress(b, state: "sending", done: done, total: totalBytes) }
                    },
                    completion: { [weak self] ok in
                        lock.lock()
                        finished += 1
                        if !ok { failed = true }
                        let last = finished == totalTransfers
                        let bad = failed
                        lock.unlock()
                        guard last else { return }
                        if deleteAfter { for u in urls { try? FileManager.default.removeItem(at: u) } }
                        if let b = batch {
                            self?.writeProgress(b, state: bad ? "failed" : "done", done: totalBytes, total: totalBytes,
                                                message: bad ? "Не удалось отправить" : "")
                        }
                        DispatchQueue.main.async {
                            self?.notifier.notice(bad ? "Не удалось отправить файл" : "Файл отправлен",
                                                  urls.count == 1 ? url.lastPathComponent : "\(urls.count) файлов")
                        }
                    })
            }
        }
    }

    /// The Share extension drops items into outbox/<id>/…; we pick them up here.
    func drainOutbox() {
        let fm = FileManager.default
        let box = config.outboxDirectory
        guard let dirs = try? fm.contentsOfDirectory(at: box, includingPropertiesForKeys: nil,
                                                     options: [.skipsHiddenFiles]) else { return }
        for dir in dirs {
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: dir.path, isDirectory: &isDir), isDir.boolValue else {
                try? fm.removeItem(at: dir)  // stray file: not ours
                continue
            }
            // Claim the batch first so the next tick does not send it twice.
            let claimed = box.appendingPathComponent(".sending-" + UUID().uuidString, isDirectory: true)
            guard (try? fm.moveItem(at: dir, to: claimed)) != nil else { continue }
            let files = (try? fm.contentsOfDirectory(at: claimed, includingPropertiesForKeys: nil,
                                                     options: [.skipsHiddenFiles])) ?? []
            sendToPhones(urls: files, deleteAfter: true, batch: dir.lastPathComponent)
            // Directory itself is cleaned up lazily: remove it once empty on a later pass.
            DispatchQueue.main.asyncAfter(deadline: .now() + 600) { try? fm.removeItem(at: claimed) }
        }
        // Remove leftovers of finished batches older than 10 minutes (e.g. the app was quit mid-send).
        if let old = try? fm.contentsOfDirectory(at: box, includingPropertiesForKeys: [.contentModificationDateKey],
                                                 options: []) {
            for u in old where u.lastPathComponent.hasPrefix(".sending-") || u.lastPathComponent.hasPrefix(".progress-") {
                let d = (try? u.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
                if let d = d, Date().timeIntervalSince(d) > 3600 { try? fm.removeItem(at: u) }
            }
        }
    }

    /// macOS Services entry: right-click → Services → «Отправить на телефон (Bridge)».
    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String, error: AutoreleasingUnsafeMutablePointer<NSString>) {
        let urls = (pboard.readObjects(forClasses: [NSURL.self],
                                       options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        if !urls.isEmpty {
            sendToPhones(urls: urls)
        } else if let text = pboard.string(forType: .string), !text.isEmpty {
            for s in sessions() { s.sendClipboard(text) }
            notifier.notice("Текст отправлен на телефон", String(text.prefix(80)))
        } else {
            error.pointee = "Нечего отправлять" as NSString
        }
    }

    // ---- drag & drop onto the menu-bar icon ---------------------------------------------------------

    func installDropTarget() {
        guard let button = statusItem.button else { return }
        let v = DropView(frame: button.bounds)
        v.autoresizingMask = [.width, .height]
        v.onDrop = { [weak self] urls in self?.sendToPhones(urls: urls) }
        button.addSubview(v)
        dropView = v
    }
}

/// Transparent overlay on the status-bar button that accepts dropped files. Clicks are forwarded to the button.
final class DropView: NSView {
    var onDrop: (([URL]) -> Void)?

    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) { fatalError() }

    override func mouseDown(with event: NSEvent) {
        (superview as? NSButton)?.performClick(nil)
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { .copy }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let urls = (sender.draggingPasteboard.readObjects(
            forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        guard !urls.isEmpty else { return false }
        onDrop?(urls)
        return true
    }
}

struct RecentSMS {
    var number: String
    var name: String?
    var text: String
    var title: String { "\(name ?? number): \(text.prefix(40))" }
}

struct ActiveCall {
    var peer: String
    var state: String   // ringing | offhook
    var number: String?
    var name: String?
}

struct RecentCall {
    let number: String
    let name: String?
    let type: String   // in / out / missed / other
    let date: Date

    var title: String {
        let arrow: String
        switch type {
        case "in": arrow = "↙︎"
        case "out": arrow = "↗︎"
        case "missed": arrow = "✗"
        default: arrow = "•"
        }
        let f = DateFormatter()
        f.dateFormat = "d MMM HH:mm"
        return "\(arrow) \(name ?? number) — \(f.string(from: date))"
    }
}

/// Calls, contacts, clipboard history/images, phone file browser.
extension AppDelegate {
    private static let numberPattern = try! NSRegularExpression(pattern: "^[+0-9*#() \\-]{1,32}$")

    static func isDialable(_ s: String) -> Bool {
        return numberPattern.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }

    // ---- calls --------------------------------------------------------------------------------------

    func requestCalls() {
        lastCallsRequest = Date()
        for a in active.values { a.session.sendJSON(["t": "calls_request", "limit": 15]) }
    }

    func dial(_ number: String) {
        guard AppDelegate.isDialable(number) else {
            notifier.notice("Bridge", "Некорректный номер")
            return
        }
        for a in active.values { a.session.sendJSON(["t": "dial", "number": number]) }
        notifier.notice("Звонок с телефона", number)
    }

    @objc func dialFromMac() {
        NSApp.activate(ignoringOtherApps: true)
        let combo = NSComboBox(frame: NSRect(x: 0, y: 0, width: 320, height: 26))
        combo.completes = true
        combo.numberOfVisibleItems = 12
        combo.placeholderString = "Номер или имя контакта"
        combo.addItems(withObjectValues: contacts.map { "\($0.name) — \($0.number)" })
        let a = NSAlert()
        a.messageText = "Позвонить с телефона"
        a.informativeText = contacts.isEmpty
            ? "Введите номер. (Контакты подгружаются при подключении, если на телефоне включено «SMS и звонки на Mac».)"
            : "Введите номер или выберите контакт."
        a.accessoryView = combo
        a.addButton(withTitle: "Позвонить")
        a.addButton(withTitle: "Отмена")
        a.window.initialFirstResponder = combo
        guard a.runModal() == .alertFirstButtonReturn else { return }
        let typed = combo.stringValue.trimmingCharacters(in: .whitespaces)
        if combo.indexOfSelectedItem >= 0 && combo.indexOfSelectedItem < contacts.count {
            dial(contacts[combo.indexOfSelectedItem].number)
        } else if let c = contacts.first(where: { "\($0.name) — \($0.number)" == typed }) {
            dial(c.number)
        } else {
            dial(typed)
        }
    }

    /// Text of the "reject with SMS" button on incoming-call notifications.
    @objc func editQuickReply() {
        NSApp.activate(ignoringOtherApps: true)
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 340, height: 24))
        field.stringValue = config.quickReply
        let a = NSAlert()
        a.messageText = "Быстрый ответ на звонок"
        a.informativeText = "Это SMS уйдёт звонящему, когда вы нажмёте «Сбросить и написать SMS» в уведомлении."
        a.accessoryView = field
        a.addButton(withTitle: "Сохранить")
        a.addButton(withTitle: "Отмена")
        a.window.initialFirstResponder = field
        guard a.runModal() == .alertFirstButtonReturn else { return }
        let t = field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if !t.isEmpty { config.quickReply = t }
    }

    // ---- Messages window ------------------------------------------------------------------------------

    @objc func openMessages() {
        if messages == nil {
            let w = MessagesWindow()
            w.send = { [weak self] obj in
                guard let self = self else { return }
                for s in self.sessions() { s.sendJSON(obj) }
            }
            w.sendSMS = { [weak self] number, text in
                guard let self = self else { return }
                let req = UUID().uuidString
                for s in self.sessions() { s.sendSMS(to: number, text: text, requestId: req) }
            }
            messages = w
        }
        messages?.open()
    }

    // ---- screen mirroring ----------------------------------------------------------------------------

    @objc func openMirror() {
        if mirror == nil {
            let w = MirrorWindow()
            w.send = { [weak self] obj in
                guard let self = self else { return }
                for s in self.sessions() { s.sendJSON(obj) }
            }
            mirror = w
        }
        mirror?.open()
    }

    // ---- "Find my Mac" and lock, triggered from the phone ---------------------------------------------

    func findMac(_ on: Bool) {
        stopMacSound()
        guard on else { return }
        macOldVolume = Int(NSAppleScript(source: "output volume of (get volume settings)")?
            .executeAndReturnError(nil).int32Value ?? -1)
        if macOldVolume == -1 { macOldVolume = nil }
        _ = NSAppleScript(source: "set volume output volume 100")?.executeAndReturnError(nil)
        let snd = NSSound(named: NSSound.Name("Sosumi")) ?? NSSound(named: NSSound.Name("Funk"))
        snd?.loops = true
        snd?.play()
        macSound = snd
        notifier.notice("Телефон ищет этот Mac", "Выключить: меню Bridge → «Выключить сигнал на Mac»")
        macSoundTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in self?.stopMacSound() }
    }

    @objc func stopMacSound() {
        macSoundTimer?.invalidate()
        macSoundTimer = nil
        macSound?.stop()
        macSound = nil
        if let v = macOldVolume {
            _ = NSAppleScript(source: "set volume output volume \(v)")?.executeAndReturnError(nil)
            macOldVolume = nil
        }
    }

    /// Puts the display to sleep; with "require password immediately" (default) this locks the Mac.
    func lockMac() {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        p.arguments = ["displaysleepnow"]
        try? p.run()
    }

    // ---- Handoff: links ------------------------------------------------------------------------------

    private func webURL(_ s: String) -> URL? {
        guard let u = URL(string: s.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = u.scheme?.lowercased(), scheme == "http" || scheme == "https", u.host != nil else { return nil }
        return u
    }

    /// A link from the phone: shown as a notification, opened only when the user clicks it.
    func showLink(_ url: String) {
        guard let u = webURL(url) else { return }
        notifier.show(id: "link:\(u.absoluteString.hashValue)", title: "Ссылка с телефона", subtitle: u.host ?? "",
                      body: u.absoluteString, userInfo: ["url": u.absoluteString])
    }

    func openLink(_ url: String) {
        if let u = webURL(url) { NSWorkspace.shared.open(u) }
    }

    /// Menu: sends the link from the clipboard (or asks for one) to open on the phone.
    @objc func openLinkOnPhone() {
        var initial = ""
        if let c = NSPasteboard.general.string(forType: .string), webURL(c) != nil { initial = c }
        NSApp.activate(ignoringOtherApps: true)
        let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 340, height: 24))
        field.placeholderString = "https://…"
        field.stringValue = initial
        let a = NSAlert()
        a.messageText = "Открыть ссылку на телефоне"
        a.informativeText = "Ссылка придёт на телефон уведомлением; откроется после нажатия."
        a.accessoryView = field
        a.addButton(withTitle: "Отправить")
        a.addButton(withTitle: "Отмена")
        a.window.initialFirstResponder = field
        guard a.runModal() == .alertFirstButtonReturn, let u = webURL(field.stringValue) else { return }
        for s in sessions() { s.sendOpenURL(u.absoluteString) }
    }

    // ---- phone battery alerts ------------------------------------------------------------------------

    func checkBattery(peer: String, _ st: PhoneStatus) {
        let old = phoneStatus[peer]
        guard let prev = old, st.battery >= 0 else { return }
        let name = active[peer]?.handler.displayName ?? "Телефон"
        if st.battery <= 20, prev.battery > 20, !st.charging {
            notifier.notice("Низкий заряд: \(st.battery)%", name)
        } else if st.battery >= 100, prev.battery < 100, st.charging {
            notifier.notice("Телефон заряжен", name)
        }
    }

    // ---- Do Not Disturb -----------------------------------------------------------------------------

    func phoneDNDChanged(peer: String, on: Bool) {
        let isFirstReport = phoneDND[peer] == nil   // sent on every connect: it is a state, not a change
        phoneDND[peer] = on
        notifier.silenced = phoneDND.values.contains(true) && config.syncDND
        if config.syncDND && !isFirstReport && lastFocusOn != on { applyMacFocus(on) }
        refreshStatus()
    }

    /// macOS has no public API to switch a Focus. If the user made two Shortcuts named
    /// «Bridge Focus On» / «Bridge Focus Off» (action «Set Focus»), run them; otherwise only Bridge's own banners are silenced.
    private func applyMacFocus(_ on: Bool) {
        let name = on ? "Bridge Focus On" : "Bridge Focus Off"
        lastFocusOn = on   // our own change must not bounce back to the phone as news
        focusIgnoreUntil = Date().addingTimeInterval(12)
        notifier.notice(on ? "На телефоне включён «Не беспокоить»" : "На телефоне «Не беспокоить» выключен",
                        on ? "Баннеры Bridge приглушены" : "")
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let list = Process()
            list.executableURL = URL(fileURLWithPath: "/usr/bin/shortcuts")
            list.arguments = ["list"]
            let pipe = Pipe()
            list.standardOutput = pipe
            list.standardError = Pipe()
            guard (try? list.run()) != nil else { return }
            list.waitUntilExit()
            let out = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
            guard out.split(separator: "\n").contains(where: { $0 == Substring(name) }) else {
                // The real Focus needs the two shortcuts: offer to create them, once.
                DispatchQueue.main.async { self?.offerFocusSetup() }
                return
            }
            let run = Process()
            run.executableURL = URL(fileURLWithPath: "/usr/bin/shortcuts")
            run.arguments = ["run", name]
            run.standardOutput = Pipe()
            run.standardError = Pipe()
            try? run.run()
        }
    }

    private func offerFocusSetup() {
        guard !config.focusSetupAsked else { return }
        config.focusSetupAsked = true
        NSApp.activate(ignoringOtherApps: true)
        let a = NSAlert()
        a.messageText = "Включать «Не беспокоить» на Mac вместе с телефоном?"
        a.informativeText = "macOS позволяет менять Focus только через «Быстрые команды». Bridge создаст две команды; в «Командах» нажмите «Добавить команду» для каждой."
        a.addButton(withTitle: "Создать команды")
        a.addButton(withTitle: "Не сейчас")
        if a.runModal() == .alertFirstButtonReturn { setupFocusShortcuts() }
    }

    var focusAccessGranted: Bool { INFocusStatusCenter.default.authorizationStatus == .authorized }

    @objc func grantFocusAccess() {
        let c = INFocusStatusCenter.default
        if c.authorizationStatus == .notDetermined {
            c.requestAuthorization { [weak self] _ in DispatchQueue.main.async { self?.refreshStatus() } }
        } else if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Focus") {
            NSWorkspace.shared.open(url)
        }
    }

    /// Builds «Bridge Focus On» / «Bridge Focus Off» (action «Set Focus»), signs them with `shortcuts sign` and opens
    /// them: Shortcuts then asks the user to press «Add Shortcut» once for each.
    @objc func setupFocusShortcuts() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("BridgeShortcuts", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        func workflow(on: Bool) -> [String: Any] {
            let action: [String: Any] = [
                "WFWorkflowActionIdentifier": "is.workflow.actions.dnd.set",
                "WFWorkflowActionParameters": [
                    "Enabled": on ? 1 : 0,
                    "FocusMode": [
                        "Identifier": "com.apple.donotdisturb.mode.default",
                        "DisplayString": "Не беспокоить",
                    ],
                ] as [String: Any],
            ]
            return [
                "WFWorkflowActions": [action],
                "WFWorkflowClientVersion": "900",
                "WFWorkflowMinimumClientVersion": 900,
                "WFWorkflowMinimumClientVersionString": "900",
                "WFWorkflowIcon": ["WFWorkflowIconStartColor": 4282601983, "WFWorkflowIconGlyphNumber": 59837],
                "WFWorkflowImportQuestions": [],
                "WFWorkflowInputContentItemClasses": [],
                "WFWorkflowTypes": [],
            ]
        }
        var signed: [URL] = []
        for (name, on) in [("Bridge Focus On", true), ("Bridge Focus Off", false)] {
            let raw = dir.appendingPathComponent("\(name).unsigned.shortcut")
            let out = dir.appendingPathComponent("\(name).shortcut")
            try? FileManager.default.removeItem(at: out)
            guard let data = try? PropertyListSerialization.data(fromPropertyList: workflow(on: on), format: .binary, options: 0),
                  (try? data.write(to: raw)) != nil else { continue }
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/bin/shortcuts")
            p.arguments = ["sign", "--mode", "anyone", "--input", raw.path, "--output", out.path]
            p.standardOutput = Pipe()
            p.standardError = Pipe()
            if (try? p.run()) != nil {
                p.waitUntilExit()
                if p.terminationStatus == 0 { signed.append(out) }
            }
        }
        guard !signed.isEmpty else {
            alert("Не удалось создать команды",
                  "Создайте в «Командах» две команды «Bridge Focus On» и «Bridge Focus Off» с действием «Задать Focus» вручную.")
            return
        }
        for url in signed { NSWorkspace.shared.open(url) }
    }

    /// Asks macOS for permission to read the Focus state (System Settings → Privacy & Security → Focus).
    func requestFocusAccess() {
        INFocusStatusCenter.default.requestAuthorization { _ in }
    }

    /// true/false when the Focus state can be read, nil otherwise. Two independent sources are combined: the system's Focus
    /// status (needs the Focus permission, and only reports Focuses that share their status) and the Do Not Disturb database
    /// (needs Full Disk Access). Either one saying "on" means on, so a Focus switched in Control Center is not missed.
    private func macFocusIsOn() -> Bool? {
        var readings: [Bool] = []
        let center = INFocusStatusCenter.default
        if center.authorizationStatus == .authorized, let focused = center.focusStatus.isFocused { readings.append(focused) }
        let url = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/DoNotDisturb/DB/Assertions.json")
        if let data = try? Data(contentsOf: url),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let list = obj["data"] as? [[String: Any]] {
            readings.append(list.contains { (($0["storeAssertionRecords"] as? [Any]) ?? []).isEmpty == false })
        }
        if readings.isEmpty { return nil }
        return readings.contains(true)
    }

    func setPhoneDND(_ on: Bool) { for s in sessions() { s.sendDND(on) } }

    func dndSyncChanged() {
        notifier.silenced = phoneDND.values.contains(true) && config.syncDND
        refreshStatus()
    }

    @objc func openDashboard() {
        if dashboard == nil { dashboard = Dashboard(app: self) }
        refreshStatus()
        dashboard?.show()
    }

    @objc func sendFileFromDashboard() { sendFile() }

    @objc func togglePhoneDND() {
        let on = !phoneDND.values.contains(true)
        for s in sessions() { s.sendDND(on) }
    }

    /// Mac Focus → phone.
    func pollFocus() {
        guard config.syncDND, !active.isEmpty, Date() >= focusIgnoreUntil, let on = macFocusIsOn() else { return }
        if lastFocusOn == nil { lastFocusOn = on; return }   // first reading: do not change anything
        guard on != lastFocusOn else { return }
        lastFocusOn = on
        for s in sessions() { s.sendDND(on) }
    }

    // ---- compose SMS --------------------------------------------------------------------------------

    @objc func composeSMS() { composeSMSDialog(to: nil) }

    @objc func replyToRecentSMS(_ sender: NSMenuItem) {
        composeSMSDialog(to: sender.representedObject as? String)
    }

    func addRecentSMSMenu(to menu: NSMenu) {
        guard !recentSMS.isEmpty else { return }
        let sub = NSMenu()
        for m in recentSMS {
            let it = NSMenuItem(title: m.title, action: #selector(replyToRecentSMS(_:)), keyEquivalent: "")
            it.target = self
            it.representedObject = m.number
            sub.addItem(it)
        }
        let parent = NSMenuItem(title: "Ответить на SMS", action: nil, keyEquivalent: "")
        parent.submenu = sub
        menu.addItem(parent)
    }

    /// Dialog: recipient (number or contact) + multi-line text. Sent through the phone's SIM.
    func composeSMSDialog(to prefill: String?) {
        NSApp.activate(ignoringOtherApps: true)
        let combo = NSComboBox(frame: NSRect(x: 0, y: 112, width: 340, height: 26))
        combo.completes = true
        combo.numberOfVisibleItems = 12
        combo.placeholderString = "Номер или имя контакта"
        combo.addItems(withObjectValues: contacts.map { "\($0.name) — \($0.number)" })
        if let p = prefill {
            if let c = contacts.first(where: { $0.number == p }) { combo.stringValue = "\(c.name) — \(c.number)" }
            else { combo.stringValue = p }
        }
        let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 340, height: 104))
        scroll.hasVerticalScroller = true
        scroll.borderType = .bezelBorder
        let tv = NSTextView(frame: scroll.bounds)
        tv.font = NSFont.systemFont(ofSize: 13)
        tv.isRichText = false
        tv.isVerticallyResizable = true
        tv.autoresizingMask = [.width]
        tv.textContainerInset = NSSize(width: 4, height: 6)
        scroll.documentView = tv
        let box = NSView(frame: NSRect(x: 0, y: 0, width: 340, height: 140))
        box.addSubview(combo)
        box.addSubview(scroll)
        let a = NSAlert()
        a.messageText = "Новое SMS"
        a.informativeText = "Сообщение уйдёт через SIM-карту телефона."
        a.accessoryView = box
        a.addButton(withTitle: "Отправить")
        a.addButton(withTitle: "Отмена")
        a.window.initialFirstResponder = prefill == nil ? combo : tv
        guard a.runModal() == .alertFirstButtonReturn else { return }
        let text = tv.string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let typed = combo.stringValue.trimmingCharacters(in: .whitespaces)
        var number = typed
        if let c = contacts.first(where: { "\($0.name) — \($0.number)" == typed }) { number = c.number }
        else if combo.indexOfSelectedItem >= 0 && combo.indexOfSelectedItem < contacts.count {
            number = contacts[combo.indexOfSelectedItem].number
        }
        guard !number.isEmpty else { return }
        let req = UUID().uuidString
        for s in sessions() { s.sendSMS(to: number, text: text, requestId: req) }
    }

    func addRecentCallsMenu(to menu: NSMenu) {
        guard !recentCalls.isEmpty else { return }
        let sub = NSMenu()
        for c in recentCalls {
            let it = NSMenuItem(title: c.title, action: #selector(redial(_:)), keyEquivalent: "")
            it.target = self
            it.representedObject = c.number
            sub.addItem(it)
        }
        let parent = NSMenuItem(title: "Последние звонки", action: nil, keyEquivalent: "")
        parent.submenu = sub
        menu.addItem(parent)
    }

    @objc func redial(_ sender: NSMenuItem) {
        if let n = sender.representedObject as? String { dial(n) }
    }

    // ---- phone files --------------------------------------------------------------------------------

    @objc func openFileBrowser() {
        if fileBrowser == nil {
            let b = FileBrowser()
            b.send = { [weak self] obj in
                guard let self = self else { return }
                for a in self.active.values { a.session.sendJSON(obj) }
            }
            fileBrowser = b
        }
        fileBrowser?.show()
    }

    // ---- clipboard history and images ---------------------------------------------------------------

    func addToHistory(_ text: String) {
        clipHistory.removeAll { $0 == text }
        clipHistory.insert(text, at: 0)
        if clipHistory.count > 10 { clipHistory.removeLast(clipHistory.count - 10) }
    }

    func addHistoryMenu(to menu: NSMenu) {
        let sub = NSMenu()
        for t in clipHistory {
            let oneLine = t.replacingOccurrences(of: "\n", with: " ")
            let title = oneLine.count > 60 ? String(oneLine.prefix(60)) + "…" : oneLine
            let it = NSMenuItem(title: title, action: #selector(restoreFromHistory(_:)), keyEquivalent: "")
            it.target = self
            it.representedObject = t
            sub.addItem(it)
        }
        let parent = NSMenuItem(title: "История буфера", action: nil, keyEquivalent: "")
        parent.submenu = sub
        menu.addItem(parent)
    }

    @objc func restoreFromHistory(_ sender: NSMenuItem) {
        guard let t = sender.representedObject as? String else { return }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(t, forType: .string)
        lastChangeCount = pb.changeCount
        lastReceivedText = t   // do not echo it back to the phone
    }

    /// Sends an image from the pasteboard to the phone. Returns true if the pasteboard held an image.
    func sendClipboardImageIfAny(_ pb: NSPasteboard) -> Bool {
        guard let data = pb.data(forType: .png) ?? pb.data(forType: .tiff) else { return false }
        let sig = data.hashValue ^ data.count
        if sig == lastImageSignature { return true }
        lastImageSignature = sig
        guard !active.isEmpty, data.count <= 25 * 1024 * 1024 else { return true }
        let png: Data
        if pb.data(forType: .png) != nil {
            png = data
        } else if let rep = NSBitmapImageRep(data: data), let p = rep.representation(using: .png, properties: [:]) {
            png = p
        } else {
            return true
        }
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("clipboard-\(f.string(from: Date())).png")
        guard (try? png.write(to: url)) != nil else { return true }
        let all = active.values.map { $0.session }
        var remaining = all.count
        let lock = NSLock()
        for s in all {
            s.sendFile(at: url, kind: "clipimg", completion: { _ in
                lock.lock(); remaining -= 1; let last = remaining == 0; lock.unlock()
                if last { try? FileManager.default.removeItem(at: url) }
            })
        }
        return true
    }

    func applyIncomingImage(_ url: URL) {
        guard config.applyClipboard, let img = NSImage(contentsOf: url) else {
            notifier.notice("Картинка от телефона", url.lastPathComponent)
            return
        }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.writeObjects([img])
        lastChangeCount = pb.changeCount
        if let d = pb.data(forType: .png) ?? pb.data(forType: .tiff) { lastImageSignature = d.hashValue ^ d.count }
        notifier.notice("Картинка из буфера телефона", "Скопирована в буфер Mac (файл: \(url.lastPathComponent))")
    }
}
