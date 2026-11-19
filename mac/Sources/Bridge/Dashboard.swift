import AppKit

// The main Bridge window. Follows the macOS HIG: system materials, semantic colours, SF Symbols,
// 8-pt grid, visible hover states, motion that can be switched off (Reduce Motion / Reduce Transparency).

private var reduceMotion: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceMotion }

/// Phone ⇄ Mac illustration: two discs, a dashed link that flows while connected, soft pulses.
final class LinkHeader: NSView {
    enum Mode { case off, busy, on }

    private let phoneDisc = CAShapeLayer()
    private let macDisc = CAShapeLayer()
    private let link = CAShapeLayer()
    private let pulses = [CAShapeLayer(), CAShapeLayer()]
    private let phoneIcon = NSImageView()
    private let macIcon = NSImageView()
    private(set) var mode: Mode = .off

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.masksToBounds = false
        for l in pulses { l.fillColor = nil; l.lineWidth = 1.5; l.opacity = 0; layer?.addSublayer(l) }
        link.opacity = 0.4
        link.fillColor = nil
        link.lineWidth = 3
        link.lineCap = .round
        link.lineDashPattern = [1, 9]
        layer?.addSublayer(link)
        for d in [phoneDisc, macDisc] { d.lineWidth = 2; layer?.addSublayer(d) }
        for (view, name) in [(phoneIcon, "iphone"), (macIcon, "laptopcomputer")] {
            view.image = NSImage(systemSymbolName: name, accessibilityDescription: nil)
            view.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 26, weight: .regular)
            view.imageScaling = .scaleProportionallyUpOrDown
            addSubview(view)
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    private var tone: NSColor {
        switch mode {
        case .on: return .systemGreen
        case .busy: return .systemOrange
        case .off: return .secondaryLabelColor
        }
    }

    private let r: CGFloat = 32

    override func layout() {
        super.layout()
        let cy = bounds.midY
        let lx = bounds.width * 0.25, rx = bounds.width * 0.75
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let disc: (CGFloat) -> CGPath = { cx in CGPath(ellipseIn: CGRect(x: cx - self.r, y: cy - self.r, width: self.r * 2, height: self.r * 2), transform: nil) }
        phoneDisc.path = disc(lx)
        macDisc.path = disc(rx)
        for (i, l) in pulses.enumerated() {
            l.path = disc(i == 0 ? lx : rx)
            l.frame = bounds
        }
        let p = CGMutablePath()
        p.move(to: CGPoint(x: lx + r + 14, y: cy))
        p.addLine(to: CGPoint(x: rx - r - 14, y: cy))
        link.path = p
        phoneIcon.frame = CGRect(x: lx - 16, y: cy - 16, width: 32, height: 32)
        macIcon.frame = CGRect(x: rx - 18, y: cy - 16, width: 36, height: 32)
        CATransaction.commit()
        applyColors()
    }

    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        applyColors()
    }

    private func applyColors() {
        effectiveAppearance.performAsCurrentDrawingAppearance {
            let c = tone.cgColor
            link.strokeColor = c
            for d in [phoneDisc, macDisc] {
                d.strokeColor = c
                d.fillColor = NSColor.controlBackgroundColor.withAlphaComponent(0.55).cgColor
            }
            for l in pulses { l.strokeColor = c }
            phoneIcon.contentTintColor = tone
            macIcon.contentTintColor = tone
        }
    }

    func setMode(_ m: Mode) {
        guard m != mode else { return }
        mode = m
        applyColors()
        link.removeAllAnimations()
        for l in pulses { l.removeAllAnimations(); l.opacity = 0 }
        link.opacity = m == .off ? 0.4 : 1
        guard m != .off, !reduceMotion else { return }
        // Dots travel from the phone to the Mac.
        let flow = CABasicAnimation(keyPath: "lineDashPhase")
        flow.fromValue = 10
        flow.toValue = 0
        flow.duration = m == .on ? 0.9 : 0.5
        flow.repeatCount = .infinity
        link.add(flow, forKey: "flow")
        // Soft rings that grow and fade, alternating between the two devices.
        let cy = bounds.midY
        for (i, l) in pulses.enumerated() {
            let cx = bounds.width * (i == 0 ? 0.25 : 0.75)
            func ring(_ extra: CGFloat) -> CGPath {
                CGPath(ellipseIn: CGRect(x: cx - r - extra, y: cy - r - extra, width: (r + extra) * 2, height: (r + extra) * 2), transform: nil)
            }
            let grow = CABasicAnimation(keyPath: "path")
            grow.fromValue = ring(0)
            grow.toValue = ring(16)
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 0.5
            fade.toValue = 0
            let g = CAAnimationGroup()
            g.animations = [grow, fade]
            g.duration = 2.4
            g.beginTime = CACurrentMediaTime() + Double(i) * 1.2
            g.repeatCount = .infinity
            g.timingFunction = CAMediaTimingFunction(name: .easeOut)
            l.add(g, forKey: "pulse")
        }
    }
}

/// Rounded quick-action tile with hover and press feedback.
final class ActionTile: NSControl {
    private let icon = NSImageView()
    private let label = NSTextField(labelWithString: "")
    private var hovering = false { didSet { refresh() } }
    private var pressed = false { didSet { refresh() } }
    var handler: (() -> Void)?

    init(symbol: String, title: String, tint: NSColor) {
        super.init(frame: .zero)
        wantsLayer = true
        layer?.cornerRadius = 12
        layer?.cornerCurve = .continuous
        icon.image = NSImage(systemSymbolName: symbol, accessibilityDescription: title)
        icon.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 20, weight: .medium)
        icon.contentTintColor = tint
        label.stringValue = title
        label.font = .systemFont(ofSize: 12, weight: .medium)
        label.textColor = .labelColor
        label.alignment = .center
        label.lineBreakMode = .byTruncatingTail
        addSubview(icon)
        addSubview(label)
        setAccessibilityRole(.button)
        setAccessibilityLabel(title)
        refresh()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override var isEnabled: Bool { didSet { refresh() } }
    override var acceptsFirstResponder: Bool { true }

    override func layout() {
        super.layout()
        icon.frame = NSRect(x: 0, y: bounds.height - 40, width: bounds.width, height: 28)
        label.frame = NSRect(x: 6, y: 12, width: bounds.width - 12, height: 16)
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        for a in trackingAreas { removeTrackingArea(a) }
        addTrackingArea(NSTrackingArea(rect: bounds, options: [.mouseEnteredAndExited, .activeInActiveApp, .inVisibleRect],
                                       owner: self, userInfo: nil))
    }

    override func mouseEntered(with event: NSEvent) { hovering = true }
    override func mouseExited(with event: NSEvent) { hovering = false; pressed = false }

    override func mouseDown(with event: NSEvent) {
        guard isEnabled else { return }
        pressed = true
    }

    override func mouseUp(with event: NSEvent) {
        guard isEnabled else { return }
        let inside = bounds.contains(convert(event.locationInWindow, from: nil))
        pressed = false
        if inside { handler?() }
    }

    override func viewDidChangeEffectiveAppearance() {
        super.viewDidChangeEffectiveAppearance()
        refresh()
    }

    private func refresh() {
        effectiveAppearance.performAsCurrentDrawingAppearance {
            let alpha: CGFloat = pressed ? 0.16 : (hovering ? 0.11 : 0.07)
            layer?.backgroundColor = NSColor.labelColor.withAlphaComponent(alpha).cgColor
        }
        alphaValue = isEnabled ? 1 : 0.4
    }
}

/// A row in the settings list: icon, title, switch.
final class SwitchRow: NSView {
    let toggle = NSSwitch()
    private let title = NSTextField(labelWithString: "")
    private let sub = NSTextField(labelWithString: "")
    private let icon = NSImageView()
    private let tile = NSView()
    var onChange: ((Bool) -> Void)?

    init(symbol: String, tint: NSColor, title: String, subtitle: String) {
        super.init(frame: .zero)
        tile.wantsLayer = true
        tile.layer?.cornerRadius = 6
        tile.layer?.cornerCurve = .continuous
        tile.layer?.backgroundColor = tint.cgColor
        icon.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
        icon.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 12, weight: .semibold)
        icon.contentTintColor = .white
        self.title.stringValue = title
        self.title.font = .systemFont(ofSize: 13, weight: .regular)
        sub.stringValue = subtitle
        sub.font = .systemFont(ofSize: 11)
        sub.textColor = .secondaryLabelColor
        toggle.target = self
        toggle.action = #selector(changed)
        for v in [tile, icon, self.title, sub, toggle] as [NSView] { addSubview(v) }
        setAccessibilityElement(false)
        toggle.setAccessibilityLabel(title)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    @objc private func changed() { onChange?(toggle.state == .on) }

    func set(on: Bool, enabled: Bool = true) {
        toggle.state = on ? .on : .off
        toggle.isEnabled = enabled
    }

    override func layout() {
        super.layout()
        tile.frame = NSRect(x: 12, y: (bounds.height - 24) / 2, width: 24, height: 24)
        icon.frame = tile.frame.insetBy(dx: 3, dy: 3)
        let tw = bounds.width - 12 - 24 - 12 - 64
        title.frame = NSRect(x: 48, y: bounds.height / 2 + 1, width: tw, height: 17)
        sub.frame = NSRect(x: 48, y: bounds.height / 2 - 16, width: tw, height: 14)
        toggle.sizeToFit()
        toggle.frame.origin = NSPoint(x: bounds.width - toggle.frame.width - 12, y: (bounds.height - toggle.frame.height) / 2)
    }
}

final class Dashboard: NSObject, NSWindowDelegate {
    let window: NSWindow
    private let header = LinkHeader(frame: NSRect(x: 0, y: 0, width: 420, height: 112))
    private let titleLabel = NSTextField(labelWithString: "Телефон не подключён")
    private let subtitleLabel = NSTextField(labelWithString: "")
    private var tiles: [ActionTile] = []
    private var rows: [SwitchRow] = []
    unowned let app: AppDelegate

    init(app: AppDelegate) {
        self.app = app
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 460, height: 700),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                          backing: .buffered, defer: false)
        super.init()
        window.title = "Bridge"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isMovableByWindowBackground = true
        window.minSize = NSSize(width: 460, height: 520)
        window.maxSize = NSSize(width: 460, height: 4000)
        window.isReleasedWhenClosed = false
        window.delegate = self
        build()
        window.center()
        window.setFrameAutosaveName("BridgeDashboard")
    }

    private func build() {
        let effect = DropEffectView()
        effect.onDrop = { [unowned self] urls in self.app.sendToPhones(urls: urls) }
        effect.material = .underWindowBackground
        effect.blendingMode = .behindWindow
        effect.state = .followsWindowActiveState
        window.contentView = effect

        let scroll = NSScrollView()
        scroll.drawsBackground = false
        scroll.hasVerticalScroller = true
        scroll.autohidesScrollers = true
        scroll.borderType = .noBorder
        scroll.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(scroll)
        NSLayoutConstraint.activate([
            scroll.leadingAnchor.constraint(equalTo: effect.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: effect.trailingAnchor),
            scroll.topAnchor.constraint(equalTo: effect.topAnchor),
            scroll.bottomAnchor.constraint(equalTo: effect.bottomAnchor),
        ])

        let flipped = FlippedView(frame: NSRect(x: 0, y: 0, width: 460, height: 560))
        scroll.documentView = flipped

        let margin: CGFloat = 20
        var y: CGFloat = 52   // below the transparent title bar

        header.frame = NSRect(x: margin, y: y, width: 420, height: 112)
        flipped.addSubview(header)
        y += 112 + 8

        titleLabel.font = .systemFont(ofSize: 22, weight: .semibold)
        titleLabel.alignment = .center
        titleLabel.frame = NSRect(x: margin, y: y, width: 420, height: 28)
        flipped.addSubview(titleLabel)
        y += 28 + 2
        subtitleLabel.font = .systemFont(ofSize: 13)
        subtitleLabel.textColor = .secondaryLabelColor
        subtitleLabel.alignment = .center
        subtitleLabel.frame = NSRect(x: margin, y: y, width: 420, height: 18)
        flipped.addSubview(subtitleLabel)
        y += 18 + 24

        // Quick actions: 3 × 2 tiles, 12-pt gaps.
        let specs: [(String, String, NSColor, () -> Void)] = [
            ("square.and.arrow.up", "Файл", .systemBlue, { [unowned self] in self.app.sendFileFromDashboard() }),
            ("doc.on.clipboard", "Буфер", .systemOrange, { [unowned self] in self.app.sendClipboardNow() }),
            ("message", "Сообщения", .systemGreen, { [unowned self] in self.app.openMessages() }),
            ("phone", "Звонок", .systemGreen, { [unowned self] in self.app.dialFromMac() }),
            ("folder", "Файлы", .systemTeal, { [unowned self] in self.app.openFileBrowser() }),
            ("speaker.wave.3", "Найти", .systemRed, { [unowned self] in self.app.toggleFindPhone() }),
            ("rectangle.on.rectangle", "Экран", .systemPurple, { [unowned self] in self.app.openMirror() }),
            ("link", "Ссылка", .systemIndigo, { [unowned self] in self.app.openLinkOnPhone() }),
            ("photo.on.rectangle", "Фото", .systemPink, { [unowned self] in self.app.openPhotoFolder() }),
        ]
        let gap: CGFloat = 12
        let tw = (420 - 2 * gap) / 3
        for (i, s) in specs.enumerated() {
            let t = ActionTile(symbol: s.0, title: s.1, tint: s.2)
            t.handler = s.3
            let col = CGFloat(i % 3), row = CGFloat(i / 3)
            t.frame = NSRect(x: margin + col * (tw + gap), y: y + row * (76 + gap), width: tw, height: 76)
            flipped.addSubview(t)
            tiles.append(t)
        }
        y += 76 * 3 + 2 * gap + 24

        // Settings list in a rounded group.
        let group = FlippedView(frame: NSRect(x: margin, y: y, width: 420, height: 0))
        group.wantsLayer = true
        group.layer?.cornerRadius = 10
        group.layer?.cornerCurve = .continuous
        flipped.addSubview(group)
        let defs: [(String, NSColor, String, String, (Bool) -> Void)] = [
            ("moon.fill", .systemIndigo, "Не беспокоить на телефоне", "Включает режим на телефоне", { [unowned self] on in
                self.app.setPhoneDND(on) }),
            ("moon.zzz.fill", .systemPurple, "Синхронизировать «Не беспокоить»", "Режим телефона приглушает уведомления Mac", { [unowned self] on in
                self.app.config.syncDND = on; self.app.dndSyncChanged() }),
            ("doc.on.clipboard.fill", .systemBlue, "Буфер обмена с телефона", "Принимать текст и картинки", { [unowned self] on in
                self.app.config.applyClipboard = on }),
            ("photo.fill", .systemOrange, "Картинки из буфера", "Отправлять скопированные изображения", { [unowned self] on in
                self.app.config.syncImages = on }),
            ("bell.fill", .systemRed, "Уведомления с телефона", "Показывать на Mac, отвечать прямо из них", { [unowned self] on in
                self.app.config.showNotifications = on }),
        ]
        let rowH: CGFloat = 52
        for (i, d) in defs.enumerated() {
            let r = SwitchRow(symbol: d.0, tint: d.1, title: d.2, subtitle: d.3)
            r.onChange = d.4
            r.frame = NSRect(x: 0, y: CGFloat(i) * rowH, width: 420, height: rowH)
            r.autoresizingMask = [.width]
            group.addSubview(r)
            rows.append(r)
            if i > 0 {
                let sep = NSBox(frame: NSRect(x: 48, y: CGFloat(i) * rowH, width: 372, height: 1))
                sep.boxType = .separator
                group.addSubview(sep)
            }
        }
        group.frame.size.height = rowH * CGFloat(defs.count)
        y += group.frame.height + 20
        flipped.frame.size.height = y
        self.groupBackground(group)
        refreshAppearance(group)
    }

    private var groupView: NSView?
    private func groupBackground(_ g: NSView) { groupView = g }
    private func refreshAppearance(_ g: NSView) {
        g.effectiveAppearance.performAsCurrentDrawingAppearance {
            g.layer?.backgroundColor = NSColor.labelColor.withAlphaComponent(0.06).cgColor
        }
    }

    // ---- state ----------------------------------------------------------------------------------

    func update(connected: Bool, searching: Bool, names: [String], subtitle: String) {
        let newTitle: String
        if connected { newTitle = names.count == 1 ? names[0] : "Подключено устройств: \(names.count)" }
        else { newTitle = app.config.devices.isEmpty ? "Нет сопряжённых телефонов" : "Телефон не подключён" }
        setText(titleLabel, newTitle)
        setText(subtitleLabel, connected ? subtitle : (app.config.devices.isEmpty
            ? "Меню Bridge → «Сопрячь телефон…», затем откройте Bridge на телефоне"
            : "Откройте Bridge на телефоне — он подключится сам"))
        header.setMode(connected ? .on : (searching ? .busy : .off))
        for t in tiles { t.isEnabled = connected }
        rows[0].set(on: app.phoneDND.values.contains(true), enabled: connected)
        rows[1].set(on: app.config.syncDND)
        rows[2].set(on: app.config.applyClipboard)
        rows[3].set(on: app.config.syncImages)
        rows[4].set(on: app.config.showNotifications)
        if let g = groupView { refreshAppearance(g) }
    }

    private func setText(_ f: NSTextField, _ s: String) {
        guard f.stringValue != s else { return }
        if reduceMotion || window.isVisible == false { f.stringValue = s; return }
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.12
            f.animator().alphaValue = 0
        }, completionHandler: {
            f.stringValue = s
            NSAnimationContext.runAnimationGroup { ctx in
                ctx.duration = 0.2
                f.animator().alphaValue = 1
            }
        })
    }

    func show() {
        NSApp.activate(ignoringOtherApps: true)
        if reduceMotion { window.makeKeyAndOrderFront(nil); return }
        if !window.isVisible { window.alphaValue = 0 }
        window.makeKeyAndOrderFront(nil)
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.25
            ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
            window.animator().alphaValue = 1
        }
    }
}

private final class FlippedView: NSView {
    override var isFlipped: Bool { true }
}

/// Window background that accepts files dropped from Finder (like AirDrop's drop target).
final class DropEffectView: NSVisualEffectView {
    var onDrop: (([URL]) -> Void)?
    private let hint = NSTextField(labelWithString: "Отпустите, чтобы отправить на телефон")
    private let veil = NSView()

    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
        veil.wantsLayer = true
        veil.layer?.cornerRadius = 14
        veil.layer?.cornerCurve = .continuous
        veil.layer?.borderWidth = 2
        veil.isHidden = true
        hint.font = .systemFont(ofSize: 15, weight: .semibold)
        hint.alignment = .center
        hint.textColor = .controlAccentColor
        veil.addSubview(hint)
        addSubview(veil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    override func layout() {
        super.layout()
        veil.frame = bounds.insetBy(dx: 10, dy: 10)
        hint.frame = NSRect(x: 0, y: veil.bounds.midY - 10, width: veil.bounds.width, height: 20)
    }

    private func setHighlight(_ on: Bool) {
        effectiveAppearance.performAsCurrentDrawingAppearance {
            veil.layer?.borderColor = NSColor.controlAccentColor.cgColor
            veil.layer?.backgroundColor = NSColor.controlAccentColor.withAlphaComponent(0.12).cgColor
        }
        veil.isHidden = !on
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        setHighlight(true)
        return .copy
    }

    override func draggingExited(_ sender: NSDraggingInfo?) { setHighlight(false) }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        setHighlight(false)
        guard let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self],
                                                               options: [.urlReadingFileURLsOnly: true]) as? [URL],
              !urls.isEmpty else { return false }
        onDrop?(urls)
        return true
    }
}
