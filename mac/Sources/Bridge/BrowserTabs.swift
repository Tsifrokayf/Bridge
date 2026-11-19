import Foundation

/// Reads the address of the front tab through AppleScript. The first use of each browser makes macOS ask the user
/// to let Bridge control it (System Settings → Privacy & Security → Automation).
enum BrowserTabs {
    /// bundle id → true when the browser uses Safari's scripting dictionary (otherwise Chromium's).
    static let known: [String: Bool] = [
        "com.apple.Safari": true,
        "com.google.Chrome": false,
        "ru.yandex.desktop.yandex-browser": false,
        "com.brave.Browser": false,
        "com.microsoft.edgemac": false,
        "company.thebrowser.Browser": false,
    ]

    static func frontURL(bundleId: String) -> String? {
        guard let safari = known[bundleId] else { return nil }
        let body = safari ? "return URL of current tab of front window" : "return URL of active tab of front window"
        let script = "tell application id \"\(bundleId)\"\n\(body)\nend tell"
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        p.arguments = ["-e", script]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = Pipe()
        guard (try? p.run()) != nil else { return nil }
        p.waitUntilExit()
        guard p.terminationStatus == 0 else { return nil }
        let s = String(data: out.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return s.isEmpty ? nil : s
    }
}
