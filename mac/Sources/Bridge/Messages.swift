import AppKit

/// "Сообщения": SMS conversations of the phone. List on the left, history and a reply field on the right.
final class MessagesWindow: NSObject, NSWindowDelegate, NSTableViewDataSource, NSTableViewDelegate, NSTextFieldDelegate, NSSearchFieldDelegate {
    struct Conv {
        var tid: Int64
        var address: String
        var name: String?
        var snippet: String
        var ts: Double
        var unread: Bool
        var title: String { name ?? address }
    }

    struct Message {
        var text: String
        var ts: Double
        var out: Bool
    }

    let window: NSWindow
    var send: (([String: Any]) -> Void)?
    /// Called to send an SMS: (number, text).
    var sendSMS: ((String, String) -> Void)?

    private let table = NSTableView()
    private let history = NSTextView()
    private let input = NSTextField()
    private let notice = NSTextField(labelWithString: "")
    private let search = NSSearchField()
    private var searching = false
    private var threads: [Conv] = []
    private var current: Conv?
    private var messages: [Message] = []
    private var localSent: [String: [Message]] = [:]   // SMS written from the Mac (the phone does not store them)

    private static let timeFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .short
        f.timeStyle = .short
        return f
    }()

    override init() {
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 760, height: 520),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable],
                          backing: .buffered, defer: false)
        super.init()
        window.title = "Сообщения"
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.minSize = NSSize(width: 560, height: 360)

        let root = NSView(frame: window.contentRect(forFrameRect: window.frame))
        window.contentView = root

        // Left: conversation list in a sidebar-style material.
        let side = NSVisualEffectView(frame: NSRect(x: 0, y: 0, width: 240, height: root.bounds.height))
        side.material = .sidebar
        side.blendingMode = .behindWindow
        side.autoresizingMask = [.height]
        root.addSubview(side)
        search.placeholderString = "Поиск по SMS"
        search.frame = NSRect(x: 10, y: root.bounds.height - 38, width: 220, height: 24)
        search.autoresizingMask = [.minYMargin]
        search.delegate = self
        search.target = self
        search.action = #selector(searchChanged)
        side.addSubview(search)
        let col = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("c"))
        col.width = 240
        table.addTableColumn(col)
        table.headerView = nil
        table.rowHeight = 52
        table.backgroundColor = .clear
        table.selectionHighlightStyle = .regular
        table.dataSource = self
        table.delegate = self
        let listScroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 240, height: root.bounds.height - 44))
        listScroll.documentView = table
        listScroll.drawsBackground = false
        listScroll.hasVerticalScroller = true
        listScroll.autoresizingMask = [.width, .height]
        side.addSubview(listScroll)

        // Right: history + input.
        let right = NSView(frame: NSRect(x: 240, y: 0, width: root.bounds.width - 240, height: root.bounds.height))
        right.autoresizingMask = [.width, .height]
        root.addSubview(right)

        input.placeholderString = "SMS"
        input.delegate = self
        input.target = self
        input.action = #selector(sendTapped)
        input.frame = NSRect(x: 12, y: 12, width: right.bounds.width - 24, height: 28)
        input.autoresizingMask = [.width]
        input.bezelStyle = .roundedBezel
        right.addSubview(input)

        notice.font = .systemFont(ofSize: 11)
        notice.textColor = .secondaryLabelColor
        notice.frame = NSRect(x: 12, y: 44, width: right.bounds.width - 24, height: 14)
        notice.autoresizingMask = [.width]
        right.addSubview(notice)

        history.isEditable = false
        history.isSelectable = true
        history.drawsBackground = false
        history.textContainerInset = NSSize(width: 14, height: 12)
        history.isVerticallyResizable = true
        history.autoresizingMask = [.width]
        let hs = NSScrollView(frame: NSRect(x: 0, y: 64, width: right.bounds.width, height: right.bounds.height - 64))
        history.frame = NSRect(x: 0, y: 0, width: hs.contentSize.width, height: hs.contentSize.height)
        history.minSize = NSSize(width: 0, height: hs.contentSize.height)
        history.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        history.textContainer?.widthTracksTextView = true
        hs.documentView = history
        hs.drawsBackground = false
        hs.hasVerticalScroller = true
        hs.autoresizingMask = [.width, .height]
        right.addSubview(hs)

        window.center()
        window.setFrameAutosaveName("BridgeMessages")
    }

    func open() {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        refresh()
    }

    func refresh() {
        notice.stringValue = ""
        send?(["t": "threads_request"])
        if let t = current { send?(["t": "thread_request", "tid": NSNumber(value: t.tid)]) }
    }

    // ---- data from the phone ----------------------------------------------------------------------

    @objc private func searchChanged() {
        let q = search.stringValue.trimmingCharacters(in: .whitespaces)
        if q.count >= 2 {
            searching = true
            send?(["t": "sms_search", "q": q])
        } else if searching {
            searching = false
            send?(["t": "threads_request"])
        }
    }

    func setThreads(_ raw: [[String: Any]], error: String?, isSearch: Bool = false) {
        // Ignore a stale list while a search is active (and the other way round).
        if isSearch != searching { return }
        if error != nil {
            notice.stringValue = "Разрешите «SMS и звонки» в Bridge на телефоне (выключите и включите переключатель)"
            return
        }
        threads = raw.compactMap { e in
            guard let tid = (e["tid"] as? NSNumber)?.int64Value, let addr = e["address"] as? String else { return nil }
            return Conv(tid: tid, address: addr, name: e["name"] as? String, snippet: e["snippet"] as? String ?? "",
                          ts: (e["ts"] as? NSNumber)?.doubleValue ?? 0, unread: (e["unread"] as? Bool) ?? false)
        }
        let keep = current?.tid
        table.reloadData()
        if let k = keep, let i = threads.firstIndex(where: { $0.tid == k }) {
            table.selectRowIndexes(IndexSet(integer: i), byExtendingSelection: false)
        }
    }

    func setMessages(tid: Int64, _ raw: [[String: Any]]) {
        guard tid == current?.tid else { return }
        var list = raw.map { Message(text: $0["text"] as? String ?? "", ts: ($0["ts"] as? NSNumber)?.doubleValue ?? 0,
                                    out: ($0["out"] as? Bool) ?? false) }
        // Add what was written from the Mac and is not on the phone's list.
        if let local = localSent[current?.address ?? ""] {
            for m in local where !list.contains(where: { $0.out && $0.text == m.text && abs($0.ts - m.ts) < 120_000 }) {
                list.append(m)
            }
        }
        messages = list.sorted { $0.ts < $1.ts }
        render()
    }

    /// An SMS that has just arrived on the phone.
    func incoming(from number: String) {
        guard window.isVisible else { return }
        send?(["t": "threads_request"])
        if current?.address == number, let t = current { send?(["t": "thread_request", "tid": NSNumber(value: t.tid)]) }
    }

    private func render() {
        let out = NSMutableAttributedString()
        for m in messages {
            let p = NSMutableParagraphStyle()
            p.alignment = m.out ? .right : .left
            p.paragraphSpacing = 2
            p.firstLineHeadIndent = m.out ? 80 : 0
            p.headIndent = m.out ? 80 : 0
            p.tailIndent = m.out ? 0 : -80
            let attrs: [NSAttributedString.Key: Any] = [
                .font: NSFont.systemFont(ofSize: 13), .foregroundColor: NSColor.labelColor, .paragraphStyle: p,
                .backgroundColor: m.out ? NSColor.controlAccentColor.withAlphaComponent(0.28) : NSColor.labelColor.withAlphaComponent(0.09),
            ]
            out.append(NSAttributedString(string: " \(m.text) ", attributes: attrs))
            let tp = NSMutableParagraphStyle()
            tp.alignment = m.out ? .right : .left
            tp.paragraphSpacing = 8
            let date = Date(timeIntervalSince1970: m.ts / 1000)
            out.append(NSAttributedString(string: "\n" + MessagesWindow.timeFormat.string(from: date) + "\n", attributes: [
                .font: NSFont.systemFont(ofSize: 10), .foregroundColor: NSColor.secondaryLabelColor, .paragraphStyle: tp,
            ]))
        }
        history.textStorage?.setAttributedString(out)
        history.scrollToEndOfDocument(nil)
    }

    // ---- sending ----------------------------------------------------------------------------------

    @objc private func sendTapped() {
        let text = input.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let t = current else { return }
        sendSMS?(t.address, text)
        let m = Message(text: text, ts: Date().timeIntervalSince1970 * 1000, out: true)
        localSent[t.address, default: []].append(m)
        messages.append(m)
        render()
        input.stringValue = ""
    }

    // ---- table ------------------------------------------------------------------------------------

    func numberOfRows(in tableView: NSTableView) -> Int { threads.count }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        let t = threads[row]
        let cell = NSView(frame: NSRect(x: 0, y: 0, width: 240, height: 52))
        let title = NSTextField(labelWithString: t.title)
        title.font = .systemFont(ofSize: 13, weight: t.unread ? .bold : .semibold)
        title.lineBreakMode = .byTruncatingTail
        title.frame = NSRect(x: 12, y: 28, width: 216, height: 17)
        let sub = NSTextField(labelWithString: t.snippet)
        sub.font = .systemFont(ofSize: 12)
        sub.textColor = .secondaryLabelColor
        sub.lineBreakMode = .byTruncatingTail
        sub.frame = NSRect(x: 12, y: 8, width: 216, height: 16)
        cell.addSubview(title)
        cell.addSubview(sub)
        return cell
    }

    func tableViewSelectionDidChange(_ notification: Notification) {
        let i = table.selectedRow
        guard i >= 0, i < threads.count else { return }
        current = threads[i]
        messages = []
        render()
        window.title = threads[i].title
        send?(["t": "thread_request", "tid": NSNumber(value: threads[i].tid)])
    }
}
