import AppKit
import AVFoundation
import ImageIO

/// Plays the phone's audio (24 kHz mono 16-bit PCM chunks) through the default output.
final class MirrorAudio {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24000, channels: 1, interleaved: false)!
    private var pending = 0           // main thread only
    private var running = false
    var muted = false

    func start() {
        guard !running else { return }
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        do { try engine.start() } catch { return }
        node.play()
        running = true
    }

    func stop() {
        guard running else { return }
        node.stop()
        engine.stop()
        engine.detach(node)
        running = false
        pending = 0
    }

    func play(_ pcm: Data) {
        guard running, !muted, pending < 5 else { return }   // drop when more than ~0.5 s is queued
        let n = pcm.count / 2
        guard n > 0, let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(n)),
              let out = buf.floatChannelData?[0] else { return }
        buf.frameLength = AVAudioFrameCount(n)
        pcm.withUnsafeBytes { raw in
            let src = raw.bindMemory(to: Int16.self)
            for i in 0..<n { out[i] = Float(Int16(littleEndian: src[i])) / 32768 }
        }
        pending += 1
        node.scheduleBuffer(buf) { [weak self] in
            DispatchQueue.main.async { self?.pending -= 1 }
        }
    }
}

/// Screen of the phone, drawn aspect-fit. Mouse → taps/swipes, scroll wheel → swipes, keys → text.
final class MirrorView: NSView {
    var send: (([String: Any]) -> Void)?
    private(set) var imageSize = CGSize(width: 9, height: 19.5)
    private var downPoint: NSPoint?
    private var downTime: TimeInterval = 0
    private var lastScroll: TimeInterval = 0
    private var scrollAccum: CGFloat = 0

    override var acceptsFirstResponder: Bool { true }

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        layer?.contentsGravity = .resizeAspect
        layer?.cornerRadius = 12
        layer?.cornerCurve = .continuous
        layer?.masksToBounds = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func show(_ image: CGImage) {
        imageSize = CGSize(width: image.width, height: image.height)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer?.contents = image
        CATransaction.commit()
    }

    /// The rectangle the picture occupies inside the view.
    private var fit: NSRect {
        let s = min(bounds.width / imageSize.width, bounds.height / imageSize.height)
        let w = imageSize.width * s, h = imageSize.height * s
        return NSRect(x: (bounds.width - w) / 2, y: (bounds.height - h) / 2, width: w, height: h)
    }

    private func norm(_ p: NSPoint) -> (Double, Double)? {
        let f = fit
        guard f.width > 0, f.height > 0, f.contains(p) else { return nil }
        return (Double((p.x - f.minX) / f.width), Double(1 - (p.y - f.minY) / f.height))
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        downPoint = convert(event.locationInWindow, from: nil)
        downTime = event.timestamp
    }

    override func mouseUp(with event: NSEvent) {
        guard let start = downPoint else { return }
        downPoint = nil
        let end = convert(event.locationInWindow, from: nil)
        let ms = Int((event.timestamp - downTime) * 1000)
        guard let a = norm(start) else { return }
        if hypot(end.x - start.x, end.y - start.y) < 6 {
            send?(["t": "mirror_input", "kind": "tap", "x": a.0, "y": a.1, "ms": min(max(ms, 40), 1500)])
        } else if let b = norm(end) {
            send?(["t": "mirror_input", "kind": "swipe", "x1": a.0, "y1": a.1, "x2": b.0, "y2": b.1,
                   "ms": min(max(ms, 80), 900)])
        }
    }

    override func scrollWheel(with event: NSEvent) {
        scrollAccum += event.scrollingDeltaY
        let now = event.timestamp
        guard now - lastScroll > 0.16, abs(scrollAccum) > 6 else { return }
        lastScroll = now
        let len = min(0.4, Double(abs(scrollAccum)) / 500)
        let down = scrollAccum > 0   // content moves down ⇒ finger moves down
        scrollAccum = 0
        let y1 = down ? 0.35 : 0.65
        let y2 = down ? y1 + len : y1 - len   // y grows downwards on the phone
        send?(["t": "mirror_input", "kind": "swipe", "x1": 0.5, "y1": y1, "x2": 0.5, "y2": y2, "ms": 160])
    }

    private var magnifyAccum: CGFloat = 0

    /// Trackpad pinch → two-finger pinch on the phone.
    override func magnify(with event: NSEvent) {
        magnifyAccum += event.magnification
        guard event.phase == .ended || event.phase == .cancelled else { return }
        let total = magnifyAccum
        magnifyAccum = 0
        guard abs(total) > 0.12 else { return }
        let c = norm(convert(event.locationInWindow, from: nil)) ?? (0.5, 0.5)
        send?(["t": "mirror_input", "kind": "pinch", "x": c.0, "y": c.1, "in": total > 0])
    }

    override func keyDown(with event: NSEvent) {
        if event.modifierFlags.contains(.command) { super.keyDown(with: event); return }
        switch event.keyCode {
        case 36, 76: send?(["t": "mirror_input", "kind": "key", "key": "enter"])
        case 51: send?(["t": "mirror_input", "kind": "key", "key": "backspace"])
        case 53: send?(["t": "mirror_input", "kind": "key", "key": "back"])
        default:
            if let s = event.characters, !s.isEmpty, s.unicodeScalars.allSatisfy({ $0.value >= 32 && $0.value != 127 }) {
                send?(["t": "mirror_input", "kind": "text", "text": s])
            }
        }
    }
}

/// Window with the phone's screen and Back / Home / Recents / Lock buttons.
final class MirrorWindow: NSObject, NSWindowDelegate {
    let window: NSWindow
    private let view = MirrorView(frame: .zero)
    private let status = NSTextField(labelWithString: "")
    private let bar = NSView()
    private var buttons: [NSButton] = []
    var send: (([String: Any]) -> Void)? { didSet { view.send = send } }
    private var sized = false
    private let audio = MirrorAudio()
    private let soundButton = NSButton()

    override init() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 330, height: 760),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable],
                          backing: .buffered, defer: false)
        super.init()
        window.title = "Экран телефона"
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.minSize = NSSize(width: 240, height: 480)
        window.backgroundColor = .windowBackgroundColor
        let content = NSView()
        window.contentView = content
        content.addSubview(view)
        status.font = .systemFont(ofSize: 12)
        status.textColor = .secondaryLabelColor
        status.alignment = .center
        content.addSubview(status)
        content.addSubview(bar)
        let defs: [(String, String, String)] = [
            ("chevron.backward", "Назад", "back"), ("circle", "Домой", "home"),
            ("square.on.square", "Недавние", "recents"), ("lock", "Заблокировать телефон", "lock"),
        ]
        for (sym, tip, key) in defs {
            let b = NSButton()
            b.image = NSImage(systemSymbolName: sym, accessibilityDescription: tip)
            b.bezelStyle = .texturedRounded
            b.imagePosition = .imageOnly
            b.toolTip = tip
            b.setAccessibilityLabel(tip)
            b.identifier = NSUserInterfaceItemIdentifier(key)
            b.target = self
            b.action = #selector(pressed(_:))
            bar.addSubview(b)
            buttons.append(b)
        }
        soundButton.image = NSImage(systemSymbolName: "speaker.wave.2", accessibilityDescription: "Звук телефона")
        soundButton.bezelStyle = .texturedRounded
        soundButton.imagePosition = .imageOnly
        soundButton.toolTip = "Звук телефона"
        soundButton.setButtonType(.pushOnPushOff)
        soundButton.target = self
        soundButton.action = #selector(toggleSound)
        bar.addSubview(soundButton)
        layoutAll()
        NotificationCenter.default.addObserver(self, selector: #selector(resized), name: NSWindow.didResizeNotification, object: window)
        window.center()
        window.setFrameAutosaveName("BridgeMirror")
    }

    @objc private func resized() { layoutAll() }

    private func layoutAll() {
        guard let c = window.contentView else { return }
        let b = c.bounds
        let barH: CGFloat = 36, statusH: CGFloat = 20, m: CGFloat = 8
        bar.frame = NSRect(x: 0, y: m, width: b.width, height: barH)
        let bw: CGFloat = 44, gap: CGFloat = 8
        let all = buttons + [soundButton]
        let total = CGFloat(all.count) * bw + CGFloat(all.count - 1) * gap
        for (i, btn) in all.enumerated() {
            btn.frame = NSRect(x: (b.width - total) / 2 + CGFloat(i) * (bw + gap), y: 4, width: bw, height: 28)
        }
        status.frame = NSRect(x: m, y: m + barH, width: b.width - 2 * m, height: statusH)
        let top = m + barH + statusH
        view.frame = NSRect(x: m, y: top, width: b.width - 2 * m, height: b.height - top - m)
    }

    @objc private func pressed(_ sender: NSButton) {
        guard let key = sender.identifier?.rawValue else { return }
        send?(["t": "mirror_input", "kind": "key", "key": key])
    }

    @objc private func toggleSound() {
        audio.muted.toggle()
        let name = audio.muted ? "speaker.slash" : "speaker.wave.2"
        soundButton.image = NSImage(systemSymbolName: name, accessibilityDescription: "Звук телефона")
    }

    func playAudio(_ pcm: Data) { audio.play(pcm) }

    func open() {
        audio.start()
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        status.stringValue = "Подключаюсь… подтвердите показ экрана на телефоне"
        send?(["t": "mirror_start", "w": 720, "fps": 10, "q": 60])
    }

    func windowWillClose(_ notification: Notification) {
        audio.stop()
        send?(["t": "mirror_stop"])
    }

    func state(_ s: String, control: Bool) {
        switch s {
        case "asking": status.stringValue = "Подтвердите показ экрана на телефоне"
        case "on":
            status.stringValue = control ? "" : "Только просмотр: включите «Управление с Mac» в Bridge на телефоне"
        case "denied": status.stringValue = "Показ экрана отклонён на телефоне"
        case "nonet": status.stringValue = "Для зеркала нужна общая сеть Wi‑Fi: сейчас связь только по Bluetooth"
        default: status.stringValue = "Трансляция остановлена"
        }
    }

    func frame(_ jpeg: Data) {
        guard let src = CGImageSourceCreateWithData(jpeg as CFData, nil),
              let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else { return }
        view.show(img)
        if !sized {
            sized = true
            // Fit the window to the phone's proportions once.
            let ratio = CGFloat(img.height) / CGFloat(img.width)
            let w: CGFloat = 340
            let content = NSSize(width: w, height: (w - 16) * ratio + 8 + 36 + 20 + 8)
            window.setContentSize(content)
            layoutAll()
        }
        status.stringValue = status.stringValue.hasPrefix("Только") ? status.stringValue : ""
    }
}
