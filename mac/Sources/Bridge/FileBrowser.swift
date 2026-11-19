import AppKit

/// Window with the phone's files: browse shared storage, download with a double-click or the button.
final class FileBrowser: NSObject, NSTableViewDataSource, NSTableViewDelegate {
    struct Entry {
        let name: String
        let isDir: Bool
        let size: Int64
        let mtime: Date
    }

    let window: NSWindow
    /// Sends a JSON message to the phone.
    var send: (([String: Any]) -> Void)?

    private let table = NSTableView()
    private let pathLabel = NSTextField(labelWithString: "")
    private let statusLabel = NSTextField(labelWithString: "")
    private var path = ""
    private var entries: [Entry] = []
    private var pendingReq: String?
    private var downloads: [String: String] = [:]  // req → file name, for error messages

    private static let sizeFormatter: ByteCountFormatter = {
        let f = ByteCountFormatter()
        f.countStyle = .file
        return f
    }()

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .short
        return f
    }()

    override init() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 640, height: 480),
                          styleMask: [.titled, .closable, .resizable, .miniaturizable],
                          backing: .buffered, defer: false)
        super.init()
        window.title = "Файлы телефона"
        window.isReleasedWhenClosed = false
        window.minSize = NSSize(width: 420, height: 280)
        build()
        window.center()
    }

    private func build() {
        guard let content = window.contentView else { return }
        let w = content.bounds.width, h = content.bounds.height

        func button(_ title: String, _ x: CGFloat, _ width: CGFloat, _ action: Selector) -> NSButton {
            let b = NSButton(title: title, target: self, action: action)
            b.bezelStyle = .rounded
            b.frame = NSRect(x: x, y: h - 34, width: width, height: 26)
            b.autoresizingMask = [.minYMargin]
            return b
        }
        content.addSubview(button("↑ Вверх", 8, 80, #selector(goUp)))
        content.addSubview(button("Обновить", 92, 90, #selector(reload)))
        content.addSubview(button("Скачать", 186, 90, #selector(downloadSelected)))

        pathLabel.frame = NSRect(x: 286, y: h - 31, width: w - 296, height: 20)
        pathLabel.autoresizingMask = [.width, .minYMargin]
        pathLabel.lineBreakMode = .byTruncatingHead
        content.addSubview(pathLabel)

        func column(_ id: String, _ title: String, _ width: CGFloat) -> NSTableColumn {
            let c = NSTableColumn(identifier: NSUserInterfaceItemIdentifier(id))
            c.title = title
            c.width = width
            return c
        }
        table.addTableColumn(column("name", "Имя", 320))
        table.addTableColumn(column("size", "Размер", 90))
        table.addTableColumn(column("date", "Изменён", 160))
        table.dataSource = self
        table.delegate = self
        table.allowsMultipleSelection = true
        table.target = self
        table.doubleAction = #selector(openRow)

        let scroll = NSScrollView(frame: NSRect(x: 0, y: 28, width: w, height: h - 44))
        scroll.autoresizingMask = [.width, .height]
        scroll.hasVerticalScroller = true
        scroll.documentView = table
        content.addSubview(scroll)

        statusLabel.frame = NSRect(x: 8, y: 6, width: w - 16, height: 18)
        statusLabel.autoresizingMask = [.width, .maxYMargin]
        statusLabel.textColor = .secondaryLabelColor
        content.addSubview(statusLabel)
    }

    // ---- public -------------------------------------------------------------------------------------

    func show() {
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        load(path)
    }

    func handleListResult(_ m: [String: Any]) {
        guard let req = m["req"] as? String, req == pendingReq else { return }
        pendingReq = nil
        guard (m["ok"] as? Bool) == true, let raw = m["entries"] as? [[String: Any]] else {
            entries = []
            table.reloadData()
            statusLabel.stringValue = FileBrowser.describe(error: m["error"] as? String)
            return
        }
        entries = raw.compactMap { e in
            guard let name = e["name"] as? String else { return nil }
            let size = (e["size"] as? NSNumber)?.int64Value ?? 0
            let ms = (e["mtime"] as? NSNumber)?.doubleValue ?? 0
            return Entry(name: name, isDir: (e["dir"] as? Bool) ?? false, size: size,
                         mtime: Date(timeIntervalSince1970: ms / 1000))
        }
        table.reloadData()
        statusLabel.stringValue = "\(entries.count) элементов"
    }

    func handleGetResult(_ m: [String: Any]) -> String? {
        guard let req = m["req"] as? String, let name = downloads.removeValue(forKey: req) else { return nil }
        if (m["ok"] as? Bool) == true {
            statusLabel.stringValue = "Скачивается: \(name)"
            return nil
        }
        let text = "Не удалось скачать «\(name)»: " + FileBrowser.describe(error: m["error"] as? String)
        statusLabel.stringValue = text
        return text
    }

    private static func describe(error: String?) -> String {
        switch error {
        case "disabled":
            return "на телефоне выключено «Файлы телефона на Mac» или не выдан доступ ко всем файлам"
        case "path": return "недопустимый путь"
        case "unreadable": return "нет доступа к этой папке/файлу"
        default: return "ошибка телефона"
        }
    }

    // ---- actions ------------------------------------------------------------------------------------

    private func load(_ newPath: String) {
        path = newPath
        pathLabel.stringValue = "/" + newPath
        statusLabel.stringValue = "Загрузка…"
        let req = UUID().uuidString
        pendingReq = req
        send?(["t": "fs_list", "req": req, "path": newPath])
    }

    private func child(_ name: String) -> String { path.isEmpty ? name : path + "/" + name }

    @objc private func goUp() {
        guard !path.isEmpty else { return }
        load(path.contains("/") ? String(path[..<path.lastIndex(of: "/")!]) : "")
    }

    @objc private func reload() { load(path) }

    @objc private func openRow() {
        let row = table.clickedRow
        guard row >= 0, row < entries.count else { return }
        let e = entries[row]
        if e.isDir { load(child(e.name)) } else { download([e]) }
    }

    @objc private func downloadSelected() {
        let files = table.selectedRowIndexes.compactMap { $0 < entries.count ? entries[$0] : nil }.filter { !$0.isDir }
        if files.isEmpty {
            statusLabel.stringValue = "Выберите файлы (папки целиком скачать нельзя)"
        } else {
            download(files)
        }
    }

    private func download(_ files: [Entry]) {
        for f in files {
            let req = UUID().uuidString
            downloads[req] = f.name
            send?(["t": "fs_get", "req": req, "path": child(f.name)])
        }
        statusLabel.stringValue = "Запрошено файлов: \(files.count)"
    }

    // ---- table --------------------------------------------------------------------------------------

    func numberOfRows(in tableView: NSTableView) -> Int { entries.count }

    func tableView(_ tableView: NSTableView, objectValueFor tableColumn: NSTableColumn?, row: Int) -> Any? {
        guard row < entries.count else { return nil }
        let e = entries[row]
        switch tableColumn?.identifier.rawValue {
        case "name": return (e.isDir ? "📁 " : "") + e.name
        case "size": return e.isDir ? "—" : FileBrowser.sizeFormatter.string(fromByteCount: e.size)
        case "date": return FileBrowser.dateFormatter.string(from: e.mtime)
        default: return nil
        }
    }
}
