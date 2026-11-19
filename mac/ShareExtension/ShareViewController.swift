import AppKit
import UniformTypeIdentifiers

/// "Share → Bridge" extension. It runs sandboxed, so it only drops the shared items into
/// ~/Library/Application Support/Bridge/outbox/<id>/ (a path allowed by a temporary-exception entitlement);
/// the menu-bar app picks them up from there and sends them to the phone.
@objc(ShareViewController)
final class ShareViewController: NSViewController {
    private let titleLabel = NSTextField(labelWithString: "Bridge → телефон")
    private let statusLabel = NSTextField(labelWithString: "Подготовка…")
    private let bar = NSProgressIndicator()
    private var poller: Timer?
    private var finished = false

    override func loadView() {
        let v = NSView(frame: NSRect(x: 0, y: 0, width: 340, height: 120))
        titleLabel.font = .boldSystemFont(ofSize: 13)
        titleLabel.frame = NSRect(x: 16, y: 88, width: 308, height: 18)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.frame = NSRect(x: 16, y: 38, width: 308, height: 18)
        bar.style = .bar
        bar.isIndeterminate = true
        bar.minValue = 0
        bar.maxValue = 1
        bar.frame = NSRect(x: 16, y: 62, width: 308, height: 16)
        bar.startAnimation(nil)
        let close = NSButton(title: "Закрыть", target: self, action: #selector(closeTapped))
        close.bezelStyle = .rounded
        close.frame = NSRect(x: 244, y: 6, width: 90, height: 26)
        for sub in [titleLabel, statusLabel, bar, close] as [NSView] { v.addSubview(sub) }
        view = v
        preferredContentSize = v.frame.size
    }

    override func viewDidAppear() {
        super.viewDidAppear()
        process()
    }

    @objc private func closeTapped() { finish() }

    private func finish(after delay: TimeInterval = 0) {
        if finished { return }
        finished = true
        poller?.invalidate()
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        }
    }

    /// Follows outbox/.progress-<id>, written by the menu-bar app while it sends the batch.
    private func watch(batch id: String, in outbox: URL) {
        let file = outbox.appendingPathComponent(".progress-" + id)
        let started = Date()
        statusLabel.stringValue = "Отправка на телефон…"
        poller = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            guard let self = self, !self.finished else { return }
            guard let data = try? Data(contentsOf: file),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                if Date().timeIntervalSince(started) > 10 {
                    self.statusLabel.stringValue = "Bridge не отвечает. Запустите приложение на Mac."
                    self.bar.stopAnimation(nil)
                    self.finish(after: 3)
                }
                return
            }
            let state = obj["state"] as? String ?? ""
            let done = (obj["done"] as? NSNumber)?.doubleValue ?? 0
            let total = (obj["total"] as? NSNumber)?.doubleValue ?? 0
            switch state {
            case "done":
                self.bar.stopAnimation(nil)
                self.bar.isIndeterminate = false
                self.bar.doubleValue = 1
                self.statusLabel.stringValue = "Отправлено ✓"
                self.finish(after: 1)
            case "failed":
                self.bar.stopAnimation(nil)
                let msg = obj["message"] as? String ?? ""
                self.statusLabel.stringValue = msg.isEmpty ? "Не удалось отправить" : msg
                self.finish(after: 3)
            default:
                if total > 0 {
                    self.bar.stopAnimation(nil)
                    self.bar.isIndeterminate = false
                    self.bar.doubleValue = min(1, done / total)
                    self.statusLabel.stringValue = "Отправка на телефон… \(Int(min(1, done / total) * 100))%"
                }
            }
        }
    }

    private var realHome: String {
        if let pw = getpwuid(getuid()), let dir = pw.pointee.pw_dir { return String(cString: dir) }
        return NSHomeDirectory()
    }

    private func process() {
        let fm = FileManager.default
        let outbox = URL(fileURLWithPath: realHome)
            .appendingPathComponent("Library/Application Support/Bridge/outbox", isDirectory: true)
        let id = UUID().uuidString
        // Hidden while being written so the app never sees a half-copied batch.
        let work = outbox.appendingPathComponent(".\(id)", isDirectory: true)
        let final = outbox.appendingPathComponent(id, isDirectory: true)
        do {
            try fm.createDirectory(at: work, withIntermediateDirectories: true)
        } catch {
            extensionContext?.cancelRequest(withError: error)
            return
        }

        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        let group = DispatchGroup()
        let lock = NSLock()
        var count = 0

        func unique(_ name: String) -> URL {
            lock.lock(); defer { lock.unlock() }
            count += 1
            return work.appendingPathComponent("\(count)-" + name)
        }

        func saveText(_ text: String) {
            try? text.write(to: unique("text.bridgetext"), atomically: true, encoding: .utf8)
        }

        for p in providers {
            group.enter()
            if p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                p.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                    defer { group.leave() }
                    var url: URL?
                    if let u = item as? URL { url = u }
                    else if let d = item as? Data { url = URL(dataRepresentation: d, relativeTo: nil) }
                    if let u = url, u.isFileURL {
                        try? fm.copyItem(at: u, to: unique(u.lastPathComponent))
                    }
                }
            } else if p.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                p.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
                    defer { group.leave() }
                    if let u = item as? URL { saveText(u.absoluteString) }
                }
            } else if p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                p.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
                    defer { group.leave() }
                    if let t = item as? String { saveText(t) }
                }
            } else if p.hasItemConformingToTypeIdentifier(UTType.data.identifier) {
                // Images/screenshots and other in-memory data arrive as file representations.
                let name = p.suggestedName ?? "shared"
                p.loadFileRepresentation(forTypeIdentifier: UTType.data.identifier) { tmp, _ in
                    defer { group.leave() }
                    guard let tmp = tmp else { return }
                    let ext = tmp.pathExtension.isEmpty ? "" : "." + tmp.pathExtension
                    let base = name.hasSuffix(ext) ? name : name + ext
                    try? fm.copyItem(at: tmp, to: unique(base))
                }
            } else {
                group.leave()
            }
        }

        group.notify(queue: .main) { [weak self] in
            let any = ((try? fm.contentsOfDirectory(atPath: work.path)) ?? []).isEmpty == false
            guard any else {
                try? fm.removeItem(at: work)
                self?.statusLabel.stringValue = "Нечего отправлять"
                self?.bar.stopAnimation(nil)
                self?.finish(after: 1.5)
                return
            }
            try? fm.moveItem(at: work, to: final)
            self?.watch(batch: id, in: outbox)
        }
    }
}
