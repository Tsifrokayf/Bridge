import AppKit
import BridgeCore
import ServiceManagement

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    let config = Config()
    let notifier = Notifier()
    private let pairingMode = PairingMode()
    private var server: BridgeServer!
    private var ble: BleCentral?
    var statusItem: NSStatusItem!
    private var pairingTimer: Timer?

    /// Connected phones by device id (hex). Main thread only.
    var active: [String: (session: Session, handler: PeerHandler, conn: PeerConnection)] = [:]
    private var alertSession: ObjectIdentifier?

    // phone state (main thread only)
    var phoneStatus: [String: PhoneStatus] = [:]
    var isFinding = false
    private var outboxTimer: Timer?
    var dropView: DropView?

    // clipboard
    private var clipboardTimer: Timer?
    var lastChangeCount = NSPasteboard.general.changeCount
    var lastReceivedText: String?
    var lastImageSignature: Int?
    var clipHistory: [String] = []

    // calls, contacts, phone files (main thread only)
    var contacts: [(name: String, number: String)] = []
    var recentCalls: [RecentCall] = []
    var lastCallsRequest = Date.distantPast
    var fileBrowser: FileBrowser?
    var recentSMS: [RecentSMS] = []
    var dashboard: Dashboard?
    var mirror: MirrorWindow?
    var messages: MessagesWindow?
    let remoteInput = RemoteInput()
    var macStatusTimer: Timer?
    var macSound: NSSound?
    var macSoundTimer: Timer?
    var macOldVolume: Int?
    var phoneDND: [String: Bool] = [:]
    var lastFocusOn: Bool?
    var macLocator: LocationProvider?
    var agenda: [String: PhoneAgenda] = [:]
    var lastBrowser: String?   // bundle id of the browser the user used last
    var phoneRinger: [String: String] = [:]
    var autoLockTimer: Timer?
    var nowPlaying: [String: (title: String, artist: String, app: String, playing: Bool)] = [:]
    var focusIgnoreUntil = Date.distantPast   // while our own shortcut is still switching the Focus, polling would see the old state
    var focusTimer: Timer?
    var activeCall: ActiveCall?
    var callSpeaker = false
    var callMuted = false

    // ---- lifecycle ----------------------------------------------------------------------------------

    func applicationDidFinishLaunching(_ notification: Notification) {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let menu = NSMenu()
        menu.delegate = self
        statusItem.menu = menu
        refreshStatus()
        if config.devices.isEmpty { DispatchQueue.main.async { [weak self] in self?.openDashboard() } }

        let mode = pairingMode
        server = BridgeServer(
            config: config,
            pairingEnabled: { mode.enabled },
            onEstablished: { [weak self] est, conn in self?.established(est, conn) },
            onClosed: { _, _ in })
        server.onPortChange = { [weak self] in DispatchQueue.main.async { self?.refreshStatus() } }
        do {
            try server.start()
        } catch {
            alert("Не удалось запустить сервер", "\(error)")
        }
        // Bluetooth LE: finds the phone without any shared network (like Apple's Continuity).
        ble = BleCentral(
            server: server, config: config, pairingEnabled: { mode.enabled },
            hasSession: { [weak self] id in DispatchQueue.main.sync { self?.active[id] != nil } })

        notifier.onAction = { [weak self] action, text, info in self?.handleNotificationAction(action, text, info) }
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
        outboxTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.drainOutbox()
        }
        installDropTarget()
        installPowerObservers()
        if config.syncDND { requestFocusAccess() }
        macStatusTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in self?.sendMacStatus() }
        focusTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in self?.pollFocus() }

        clipboardTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.pollClipboard()
        }
    }

    // ---- connections (called on the connection queue) -----------------------------------------------

    private func established(_ est: Established, _ conn: PeerConnection) {
        guard let session = conn.session else { return }
        let name = est.peerName ?? config.name(for: est.peerId) ?? "Телефон"
        let handler = PeerHandler(app: self, established: est, displayName: name)
        session.delegate = handler
        conn.handler = handler
        // Must happen before the next frame is processed on this queue, hence done synchronously here.
        session.start()
        DispatchQueue.main.async { [weak self] in self?.registered(handler: handler, session: session, conn: conn) }
    }

    // ---- main thread --------------------------------------------------------------------------------

    private func registered(handler: PeerHandler, session: Session, conn: PeerConnection) {
        if handler.isPairing {
            pairingMode.disable()
            refreshPairingTitle()
            askToConfirm(handler: handler, session: session)
            return
        }
        let id = handler.peerId.hex
        if let old = active[id] { old.session.close(reason: "replaced") }
        active[id] = (session, handler, conn)
        autoLockTimer?.invalidate(); autoLockTimer = nil   // the phone is back
        session.sendJSON(["t": "contacts_request"])
        session.sendJSON(["t": "calls_request", "limit": 15])
        lastCallsRequest = Date()
        refreshStatus()
        sendMacStatus()
    }

    func handlerClosed(_ handler: PeerHandler, session: Session) {
        let id = handler.peerId.hex
        if let cur = active[id], cur.session === session {
            active[id] = nil
            phoneStatus[id] = nil
            phoneDND[id] = nil
            nowPlaying[id] = nil
            phoneRinger[id] = nil
            agenda[id] = nil
            if active.isEmpty { scheduleAutoLock() }
            notifier.silenced = phoneDND.values.contains(true) && config.syncDND
            if active.isEmpty { isFinding = false }
        }
        if alertSession == ObjectIdentifier(session) { NSApp.abortModal() }
        refreshStatus()
    }

    private func askToConfirm(handler: PeerHandler, session: Session) {
        guard let sas = handler.established.sas else { return }
        let shown = String(sas.prefix(3)) + " " + String(sas.suffix(3))
        let a = NSAlert()
        a.messageText = "Сопряжение с «\(handler.displayName)»"
        a.informativeText = "Код: \(shown)\n\nЕсли на телефоне показан тот же код — подтвердите. "
            + "Если коды различаются, откажитесь: кто-то может вмешиваться в соединение."
        a.addButton(withTitle: "Код совпадает")
        a.addButton(withTitle: "Не совпадает")
        a.alertStyle = .informational
        NSApp.activate(ignoringOtherApps: true)
        alertSession = ObjectIdentifier(session)
        let r = a.runModal()
        alertSession = nil
        handler.userDecided(r == .alertFirstButtonReturn && !session.isClosed, session: session)
    }

    func sendMacStatus() {
        guard !active.isEmpty else { return }
        let net = NetInfo.addresses()
        var msg: [String: Any] = ["t": "mac_status", "ips": net.ips, "macs": net.macs, "battery": -1, "charging": false]
        if let b = macBatteryStatus() { msg["battery"] = b.percent; msg["charging"] = b.charging }
        for a in active.values { a.session.sendJSON(msg) }
    }

    /// Tells the phone the Mac is going to sleep / was switched off, so it stops searching for it (and can send Wake-on-LAN).
    func sendMacState(_ state: String) {
        for a in active.values { a.session.sendJSON(["t": "mac_state", "state": state]) }
    }

    func installPowerObservers() {
        let nc = NSWorkspace.shared.notificationCenter
        nc.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] n in
            if let app = n.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication,
               let id = app.bundleIdentifier, BrowserTabs.known[id] != nil { self?.lastBrowser = id }
        }
        nc.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { [weak self] _ in
            self?.sendMacStatus()          // last addresses before going away
            self?.sendMacState("sleep")
        }
        nc.addObserver(forName: NSWorkspace.willPowerOffNotification, object: nil, queue: .main) { [weak self] _ in
            self?.sendMacState("shutdown")
        }
        nc.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            // Network comes back a moment after wake; connections that died during sleep are re-made by the phone.
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) { self?.sendMacState("awake"); self?.sendMacStatus() }
        }
    }

    func refreshStatus() {
        guard let button = statusItem?.button else { return }
        let connected = !active.isEmpty
        let symbol = connected ? "iphone" : "iphone.slash"
        if let img = NSImage(systemSymbolName: symbol, accessibilityDescription: "Bridge") {
            img.isTemplate = true
            button.image = img
        } else {
            button.title = "⇄"
        }
        refreshPairingTitle()
        dashboard?.update(connected: connected, searching: false,
                          names: active.values.map { $0.handler.displayName },
                          subtitle: active.values.compactMap { phoneStatus[$0.handler.peerId.hex]?.text }.first ?? "")
    }

    private func refreshPairingTitle() {
        guard let button = statusItem?.button else { return }
        if button.image != nil { button.title = pairingMode.enabled ? " сопряжение…" : "" }
    }

    func applyIncomingClipboard(_ text: String) {
        guard config.applyClipboard else { return }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        lastChangeCount = pb.changeCount
        lastReceivedText = text
        addToHistory(text)
    }

    func showPhoneNotification(peer: String, _ n: IncomingNotification) {
        guard config.showNotifications else { return }
        let app = n.app.isEmpty ? "Телефон" : n.app
        let category: Notifier.Category? = n.canReply ? .reply : (n.clearable ? .dismiss : nil)
        notifier.show(id: "\(peer):\(n.key)", title: app, subtitle: n.title, body: n.text,
                      category: category, userInfo: ["peer": peer, "key": n.key])
    }

    private func pollClipboard() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastChangeCount else { return }
        lastChangeCount = pb.changeCount
        guard !active.isEmpty else { return }
        let types = (pb.types ?? []).map { $0.rawValue }
        if config.syncImages, pb.string(forType: .string) == nil, sendClipboardImageIfAny(pb) { return }
        // Password managers mark secrets with these; never forward them.
        let secret: Set<String> = ["org.nspasteboard.ConcealedType", "org.nspasteboard.TransientType",
                                   "org.nspasteboard.AutoGeneratedType"]
        if types.contains(where: { secret.contains($0) }) { return }
        guard let text = pb.string(forType: .string), !text.isEmpty, text.utf8.count <= 512 * 1024 else { return }
        if text == lastReceivedText { return }
        addToHistory(text)
        for a in active.values { a.session.sendClipboard(text) }
    }

    func alert(_ title: String, _ text: String) {
        let a = NSAlert()
        a.messageText = title
        a.informativeText = text
        a.runModal()
    }

    // ---- menu ---------------------------------------------------------------------------------------

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        if !active.isEmpty, Date().timeIntervalSince(lastCallsRequest) > 5 { requestCalls() }
        func add(_ title: String, _ action: Selector?, enabled: Bool = true, state: Bool? = nil) -> NSMenuItem {
            let it = NSMenuItem(title: title, action: enabled ? action : nil, keyEquivalent: "")
            it.target = self
            it.isEnabled = enabled && action != nil
            if let s = state { it.state = s ? .on : .off }
            let symbols: [(String, String)] = [
                ("Открыть Bridge", "macwindow"), ("Отправить файл", "square.and.arrow.up"),
                ("Отправить буфер", "doc.on.clipboard"), ("Написать SMS", "message"), ("Сообщения", "bubble.left.and.bubble.right"),
                ("Позвонить", "phone"), ("Открыть ссылку", "link.badge.plus"), ("Зеркало экрана", "rectangle.on.rectangle"), ("Выключить сигнал на Mac", "speaker.slash"), ("Файлы телефона", "folder"), ("Найти телефон", "speaker.wave.3"),
                ("Выключить сигнал", "speaker.slash"), ("Не беспокоить", "moon"), ("Сопрячь", "link"),
                ("Режим сопряжения", "link"), ("Выйти", "power"),
            ]
            if state == nil, let sym = symbols.first(where: { title.hasPrefix($0.0) })?.1,
               let img = NSImage(systemSymbolName: sym, accessibilityDescription: nil) {
                it.image = img
            }
            menu.addItem(it)
            return it
        }

        let names = active.values.map { $0.handler.displayName }
        if names.isEmpty {
            _ = add(config.devices.isEmpty ? "Нет сопряжённых телефонов" : "Телефон не подключён", nil, enabled: false)
        } else {
            for n in names { _ = add("Подключено: \(n)", nil, enabled: false) }
        }
        for (id, a) in active {
            if let st = phoneStatus[id], st.battery >= 0 {
                _ = add("\(a.handler.displayName): \(st.text)", nil, enabled: false)
            }
        }
        let port = server?.port ?? 0
        if port != 0 {
            let ips = localIPv4Addresses()
            if let ip = ips.first {
                _ = add("Адрес для ручного ввода: \(ip):\(port)", nil, enabled: false)
            }
        }
        menu.addItem(.separator())

        let connected = !active.isEmpty
        if connected { addActiveCallMenu(to: menu) }
        if macSound != nil { _ = add("Выключить сигнал на Mac", #selector(stopMacSound)) }
        _ = add("Открыть Bridge", #selector(openDashboard))
        menu.addItem(.separator())
        _ = add("Отправить файл на телефон…", #selector(sendFile), enabled: connected)
        _ = add("Отправить буфер обмена на телефон", #selector(sendClipboardNow), enabled: connected)
        _ = add("Зеркало экрана телефона", #selector(openMirror), enabled: connected)
        _ = add("Открыть ссылку на телефоне…", #selector(openLinkOnPhone), enabled: connected)
        _ = add("Открыть вкладку браузера на телефоне", #selector(openBrowserTabOnPhone), enabled: connected)
        _ = add("Скриншот области → на телефон", #selector(screenshotToPhone), enabled: connected)
        _ = add("Проложить маршрут на телефоне…", #selector(routeOnPhone), enabled: connected)
        _ = add("Сообщения…", #selector(openMessages), enabled: connected)
        _ = add("Написать SMS…", #selector(composeSMS), enabled: connected)
        if connected { addRecentSMSMenu(to: menu) }
        _ = add("Быстрый ответ на звонок…", #selector(editQuickReply))
        _ = add("Позвонить с телефона…", #selector(dialFromMac), enabled: connected)
        if connected { addRecentCallsMenu(to: menu) }
        _ = add("Файлы телефона…", #selector(openFileBrowser), enabled: connected)
        if !clipHistory.isEmpty { addHistoryMenu(to: menu) }
        _ = add(isFinding ? "Выключить сигнал на телефоне" : "Найти телефон (громкий сигнал)",
                #selector(toggleFindPhone), enabled: connected)
        for (_, np) in nowPlaying where !np.title.isEmpty {
            let who = np.artist.isEmpty ? np.title : "\(np.artist) — \(np.title)"
            _ = add("\(np.playing ? "▶︎" : "⏸") \(who)  (\(np.app))", nil, enabled: false)
        }
        for (_, ag) in agenda {
            if let a = ag.alarm { _ = add("⏰ Будильник на телефоне: \(PhoneAgenda.timeText(a, withDay: true))", nil, enabled: false) }
            if !ag.events.isEmpty {
                let sub = NSMenu()
                for e in ag.events {
                    let it = NSMenuItem(title: e.menuTitle, action: nil, keyEquivalent: "")
                    it.isEnabled = false
                    sub.addItem(it)
                }
                let parent = NSMenuItem(title: "Календарь телефона (\(ag.events.count))", action: nil, keyEquivalent: "")
                parent.submenu = sub
                menu.addItem(parent)
            }
        }
        _ = add("Показать телефон на карте", #selector(locatePhone), enabled: connected)
        _ = add("Сфотографировать на телефоне", #selector(phoneCamera), enabled: connected)
        let ringer = NSMenu()
        let cur = phoneRinger.values.first
        for (title, mode) in [("Со звуком", "normal"), ("Вибрация", "vibrate"), ("Без звука", "silent")] {
            let it = NSMenuItem(title: title, action: #selector(setPhoneRinger(_:)), keyEquivalent: "")
            it.target = self
            it.representedObject = mode
            it.state = cur == mode ? .on : .off
            it.isEnabled = connected
            ringer.addItem(it)
        }
        let ringerParent = NSMenuItem(title: "Звук телефона", action: nil, keyEquivalent: "")
        ringerParent.submenu = ringer
        ringerParent.isEnabled = connected
        menu.addItem(ringerParent)
        let media = NSMenu()
        for (title, action) in [("Пауза / воспроизведение", "play_pause"), ("Следующий трек", "next"),
                                ("Предыдущий трек", "previous"), ("Громче", "volume_up"), ("Тише", "volume_down")] {
            let it = NSMenuItem(title: title, action: #selector(mediaCommand(_:)), keyEquivalent: "")
            it.target = self
            it.representedObject = action
            it.isEnabled = connected
            media.addItem(it)
        }
        let mediaParent = NSMenuItem(title: "Музыка на телефоне", action: nil, keyEquivalent: "")
        mediaParent.submenu = media
        mediaParent.isEnabled = connected
        menu.addItem(mediaParent)
        menu.addItem(.separator())

        _ = add(pairingMode.enabled ? "Режим сопряжения включён (отменить)" : "Сопрячь телефон…",
                #selector(togglePairing))
        if !config.devices.isEmpty {
            let sub = NSMenu()
            for d in config.devices {
                let it = NSMenuItem(title: "Забыть «\(d.name)»", action: #selector(forget(_:)), keyEquivalent: "")
                it.target = self
                it.representedObject = d.id
                sub.addItem(it)
            }
            let parent = NSMenuItem(title: "Сопряжённые устройства", action: nil, keyEquivalent: "")
            parent.submenu = sub
            menu.addItem(parent)
        }
        menu.addItem(.separator())

        _ = add("Синхронизировать картинки из буфера", #selector(toggleSyncImages), state: config.syncImages)
        _ = add("Принимать буфер обмена с телефона", #selector(toggleApplyClipboard), state: config.applyClipboard)
        _ = add("Синхронизировать «Не беспокоить»", #selector(toggleSyncDND), state: config.syncDND)
        if config.syncDND {
            _ = add("Телефон → Mac: создать команды Focus…", #selector(setupFocusShortcuts))
        }
        if config.syncDND && !focusAccessGranted {
            _ = add("Mac → телефон: разрешить доступ к Focus…", #selector(grantFocusAccess))
        }
        if connected {
            let on = phoneDND.values.contains(true)
            _ = add("Не беспокоить на телефоне", #selector(togglePhoneDND), state: on)
        }
        _ = add("Блокировать Mac, когда телефон ушёл", #selector(toggleAutoLock), state: config.autoLock)
        _ = add("Показывать уведомления с телефона", #selector(toggleNotifications), state: config.showNotifications)
        _ = add("Запускать при входе в систему", #selector(toggleLaunchAtLogin), state: launchAtLoginEnabled)
        _ = add("Открыть папку с файлами", #selector(openFolder))
        _ = add("Открыть папку с фото телефона", #selector(openPhotoFolder))
        menu.addItem(.separator())
        _ = add("Выйти", #selector(quit))
    }

    @objc func sendFile() {
        NSApp.activate(ignoringOtherApps: true)
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.message = "Выберите файлы для отправки на телефон"
        guard panel.runModal() == .OK else { return }
        for url in panel.urls {
            for a in active.values {
                a.session.sendFile(at: url, completion: { [weak self] ok in
                    DispatchQueue.main.async {
                        self?.notifier.notice(ok ? "Файл отправлен" : "Не удалось отправить файл", url.lastPathComponent)
                    }
                })
            }
        }
    }

    @objc func sendClipboardNow() {
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else { return }
        for a in active.values { a.session.sendClipboard(text) }
    }

    @objc private func togglePairing() {
        if pairingMode.enabled {
            pairingMode.disable()
        } else {
            pairingMode.enable(seconds: 120)
            notifier.notice("Bridge", "Режим сопряжения включён на 2 минуты. На телефоне: Bridge → «Сопрячь с Mac».")
            pairingTimer?.invalidate()
            pairingTimer = Timer.scheduledTimer(withTimeInterval: 121, repeats: false) { [weak self] _ in
                self?.refreshStatus()
            }
        }
        refreshStatus()
    }

    @objc private func forget(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        config.removeDevice(id: id)
        if let a = active[id] { a.session.close(reason: "forgotten") }
        refreshStatus()
    }

    @objc private func toggleApplyClipboard() { config.applyClipboard.toggle() }
    @objc private func toggleSyncDND() { config.syncDND.toggle(); notifier.silenced = phoneDND.values.contains(true) && config.syncDND }
    @objc private func toggleSyncImages() { config.syncImages.toggle() }
    @objc private func toggleNotifications() { config.showNotifications.toggle() }

    private var launchAtLoginEnabled: Bool {
        return SMAppService.mainApp.status == .enabled
    }

    @objc private func toggleLaunchAtLogin() {
        do {
            if launchAtLoginEnabled {
                try SMAppService.mainApp.unregister()
            } else {
                try SMAppService.mainApp.register()
            }
        } catch {
            alert("Не удалось изменить автозапуск", "Работает только для собранного Bridge.app.\n\(error)")
        }
    }

    @objc private func openFolder() {
        try? FileManager.default.createDirectory(at: config.saveDirectory, withIntermediateDirectories: true)
        NSWorkspace.shared.open(config.saveDirectory)
    }

    @objc func openPhotoFolder() {
        try? FileManager.default.createDirectory(at: config.photoDirectory, withIntermediateDirectories: true)
        NSWorkspace.shared.open(config.photoDirectory)
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }
}
