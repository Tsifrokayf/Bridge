import Foundation

/// Calendar events and the next alarm reported by the phone.
struct PhoneAgenda {
    struct Event {
        let title: String
        let start: Date
        let end: Date
        let allDay: Bool
        let place: String

        var menuTitle: String {
            let when = allDay ? "весь день" : PhoneAgenda.timeText(start, withDay: false)
            let name = title.isEmpty ? "Без названия" : title
            let day = PhoneAgenda.dayText(start)
            return "\(day) \(when) — \(name)" + (place.isEmpty ? "" : " (\(place))")
        }
    }

    var events: [Event] = []
    var alarm: Date?

    init(_ m: [String: Any]) {
        if let ms = (m["alarm"] as? NSNumber)?.doubleValue, ms > 0 { alarm = Date(timeIntervalSince1970: ms / 1000) }
        for e in (m["events"] as? [[String: Any]]) ?? [] {
            guard let s = (e["start"] as? NSNumber)?.doubleValue, let f = (e["end"] as? NSNumber)?.doubleValue else { continue }
            events.append(Event(title: (e["title"] as? String) ?? "", start: Date(timeIntervalSince1970: s / 1000),
                                end: Date(timeIntervalSince1970: f / 1000), allDay: (e["allDay"] as? Bool) ?? false,
                                place: (e["place"] as? String) ?? ""))
        }
    }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm"; return f
    }()

    static func timeText(_ d: Date, withDay: Bool) -> String {
        let t = timeFormatter.string(from: d)
        return withDay ? "\(dayText(d)) \(t)" : t
    }

    static func dayText(_ d: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(d) { return "сегодня" }
        if cal.isDateInTomorrow(d) { return "завтра" }
        let f = DateFormatter(); f.dateFormat = "d MMM"; return f.string(from: d)
    }
}
