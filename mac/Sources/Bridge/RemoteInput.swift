import AppKit
import ApplicationServices
import IOKit.ps

/// Executes pointer / keyboard / media commands that come from the phone (trackpad mode).
/// Needs the Accessibility permission, like every app that synthesises input.
final class RemoteInput {
    private var warned = false

    private func trusted() -> Bool {
        if AXIsProcessTrusted() { return true }
        if !warned {
            warned = true
            let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
            _ = AXIsProcessTrustedWithOptions([key: true] as CFDictionary)
        }
        return false
    }

    private var location: CGPoint { CGEvent(source: nil)?.location ?? .zero }

    func handle(_ type: String, _ m: [String: Any]) {
        func int(_ k: String) -> Int { (m[k] as? NSNumber)?.intValue ?? 0 }
        guard trusted() else { return }
        switch type {
        case "pointer": move(dx: CGFloat(int("dx")), dy: CGFloat(int("dy")))
        case "click": click(right: (m["button"] as? String) == "right", count: max(1, min(3, int("count"))))
        case "scroll": scroll(dx: int("dx"), dy: int("dy"))
        case "mac_key": key(m["key"] as? String ?? "")
        case "mac_text": text(m["text"] as? String ?? "")
        case "mac_media": media(m["action"] as? String ?? "")
        default: break
        }
    }

    private func move(dx: CGFloat, dy: CGFloat) {
        var p = location
        p.x += dx
        p.y += dy
        // Keep the pointer inside the union of all displays.
        var bounds = CGRect.null
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        var n: UInt32 = 0
        if CGGetActiveDisplayList(16, &ids, &n) == .success {
            for i in 0..<Int(n) { bounds = bounds.union(CGDisplayBounds(ids[i])) }
        }
        if !bounds.isNull {
            p.x = min(max(p.x, bounds.minX), bounds.maxX - 1)
            p.y = min(max(p.y, bounds.minY), bounds.maxY - 1)
        }
        CGEvent(mouseEventSource: nil, mouseType: .mouseMoved, mouseCursorPosition: p, mouseButton: .left)?
            .post(tap: .cghidEventTap)
    }

    private func click(right: Bool, count: Int) {
        let p = location
        let down: CGEventType = right ? .rightMouseDown : .leftMouseDown
        let up: CGEventType = right ? .rightMouseUp : .leftMouseUp
        let button: CGMouseButton = right ? .right : .left
        for i in 1...count {
            for t in [down, up] {
                let e = CGEvent(mouseEventSource: nil, mouseType: t, mouseCursorPosition: p, mouseButton: button)
                e?.setIntegerValueField(.mouseEventClickState, value: Int64(i))
                e?.post(tap: .cghidEventTap)
            }
        }
    }

    private func scroll(dx: Int, dy: Int) {
        CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2, wheel1: Int32(dy), wheel2: Int32(dx), wheel3: 0)?
            .post(tap: .cghidEventTap)
    }

    private func key(_ name: String) {
        let codes: [String: CGKeyCode] = ["backspace": 51, "enter": 36, "esc": 53, "tab": 48,
                                          "left": 123, "right": 124, "down": 125, "up": 126]
        guard let code = codes[name] else { return }
        for down in [true, false] {
            CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down)?.post(tap: .cghidEventTap)
        }
    }

    private func text(_ s: String) {
        for scalar in s.unicodeScalars where scalar.value >= 32 {
            var utf16 = Array(String(scalar).utf16)
            for down in [true, false] {
                let e = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: down)
                e?.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: &utf16)
                e?.post(tap: .cghidEventTap)
            }
        }
    }

    /// Media keys are "system defined" events (NX_KEYTYPE_*).
    private func media(_ action: String) {
        let codes: [String: Int] = ["volume_up": 0, "volume_down": 1, "mute": 7, "play_pause": 16, "next": 17, "previous": 18]
        guard let k = codes[action] else { return }
        for down in [true, false] {
            let flags = NSEvent.ModifierFlags(rawValue: down ? 0xa00 : 0xb00)
            let data1 = (k << 16) | ((down ? 0xa : 0xb) << 8)
            NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: flags, timestamp: 0, windowNumber: 0,
                               context: nil, subtype: 8, data1: data1, data2: -1)?
                .cgEvent?.post(tap: .cghidEventTap)
        }
    }
}

/// Battery of this Mac, for the phone's main screen.
func macBatteryStatus() -> (percent: Int, charging: Bool)? {
    guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
          let list = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef] else { return nil }
    for ps in list {
        guard let d = IOPSGetPowerSourceDescription(info, ps)?.takeUnretainedValue() as? [String: Any],
              let cur = d[kIOPSCurrentCapacityKey] as? Int, let full = d[kIOPSMaxCapacityKey] as? Int, full > 0 else { continue }
        let onAC = (d[kIOPSPowerSourceStateKey] as? String) == kIOPSACPowerValue
        return (cur * 100 / full, onAC)
    }
    return nil
}
