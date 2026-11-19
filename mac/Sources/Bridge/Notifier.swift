import AppKit
import UserNotifications

/// Shows macOS notifications. UNUserNotificationCenter only works for a real .app bundle;
/// when run as a bare executable (`swift run`) it falls back to osascript (no action buttons).
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    /// Notification categories (which buttons a notification gets).
    enum Category: String {
        case reply = "bridge.reply"       // phone notification that accepts an inline reply
        case dismiss = "bridge.dismiss"   // phone notification that can only be dismissed
        case sms = "bridge.sms"           // incoming SMS: reply by SMS
        case call = "bridge.call"         // incoming call: accept / reject
    }

    /// (actionIdentifier: "reply" | "dismiss" | "reject", typed text, userInfo). Called on the main thread.
    var onAction: ((String, String?, [String: String]) -> Void)?

    /// Do Not Disturb: phone notifications and SMS are not shown (calls still ring).
    var silenced = false

    private let useCenter = Bundle.main.bundleIdentifier != nil

    override init() {
        super.init()
        guard useCenter else { return }
        let c = UNUserNotificationCenter.current()
        c.delegate = self
        let reply = UNTextInputNotificationAction(
            identifier: "reply", title: "Ответить", options: [],
            textInputButtonTitle: "Отправить", textInputPlaceholder: "Ответ")
        let dismiss = UNNotificationAction(identifier: "dismiss", title: "Закрыть на телефоне", options: [])
        let reject = UNNotificationAction(identifier: "reject", title: "Сбросить", options: [.destructive])
        let accept = UNNotificationAction(identifier: "accept", title: "Ответить", options: [])
        let rejectSMS = UNNotificationAction(identifier: "rejectsms", title: "Сбросить и написать SMS", options: [])
        c.setNotificationCategories([
            UNNotificationCategory(identifier: Category.reply.rawValue, actions: [reply, dismiss],
                                   intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Category.dismiss.rawValue, actions: [dismiss],
                                   intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Category.sms.rawValue, actions: [reply],
                                   intentIdentifiers: [], options: []),
            UNNotificationCategory(identifier: Category.call.rawValue, actions: [accept, reject, rejectSMS],
                                   intentIdentifiers: [], options: []),
        ])
        c.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let id = response.actionIdentifier
        if id != UNNotificationDefaultActionIdentifier && id != UNNotificationDismissActionIdentifier {
            let raw = response.notification.request.content.userInfo
            var info: [String: String] = [:]
            for (k, v) in raw { if let k = k as? String, let v = v as? String { info[k] = v } }
            let text = (response as? UNTextInputNotificationResponse)?.userText
            DispatchQueue.main.async { [weak self] in self?.onAction?(id, text, info) }
        }
        if id == UNNotificationDefaultActionIdentifier,
           let url = response.notification.request.content.userInfo["url"] as? String {
            DispatchQueue.main.async { [weak self] in self?.onAction?("open", nil, ["url": url]) }
        }
        completionHandler()
    }

    func show(id: String, title: String, subtitle: String = "", body: String,
              category: Category? = nil, userInfo: [String: String] = [:]) {
        if silenced, let c = category, c != .call { return }
        if useCenter {
            let content = UNMutableNotificationContent()
            content.title = title
            if !subtitle.isEmpty { content.subtitle = subtitle }
            content.body = body
            if let c = category { content.categoryIdentifier = c.rawValue }
            content.userInfo = userInfo
            let req = UNNotificationRequest(identifier: id, content: content, trigger: nil)
            UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
        } else {
            let head = subtitle.isEmpty ? title : "\(title) — \(subtitle)"
            osascript(title: head, body: body)
        }
    }

    func remove(id: String) {
        guard useCenter else { return }
        let c = UNUserNotificationCenter.current()
        c.removeDeliveredNotifications(withIdentifiers: [id])
        c.removePendingNotificationRequests(withIdentifiers: [id])
    }

    func notice(_ title: String, _ body: String) {
        show(id: UUID().uuidString, title: title, body: body)
    }

    private func osascript(title: String, body: String) {
        func esc(_ s: String) -> String {
            return s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        }
        let script = "display notification \"\(esc(body))\" with title \"\(esc(title))\""
        DispatchQueue.global(qos: .utility).async {
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            p.arguments = ["-e", script]
            try? p.run()
            p.waitUntilExit()
        }
    }
}
