import Foundation

public struct ProtocolError: Error, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

public enum Wire {
    public static let version: UInt8 = 1

    // Handshake frame types (plaintext phase)
    public static let tPairHello: UInt8 = 0x01
    public static let tReconnectHello: UInt8 = 0x02
    public static let tReconnectReply: UInt8 = 0x03
    public static let tReconnectFinish: UInt8 = 0x04
    public static let tPairReply: UInt8 = 0x05

    // Record types (encrypted phase)
    public static let recJSON: UInt8 = 0x01
    public static let recChunk: UInt8 = 0x02

    public static let maxFrame = 2 * 1024 * 1024
    public static let chunkSize = 256 * 1024
    public static let maxFileSize: Int64 = 8 * 1024 * 1024 * 1024

    public static let serviceType = "_bridgesync._tcp"

    /// Preferred listening port; lets the phone find the Mac without mDNS (subnet scan, manual IP).
    public static let defaultPort: UInt16 = 47653
}

extension Data {
    /// Parses a hex string (upper or lower case). Returns nil for odd length or bad digits.
    public init?(hex: String) {
        let chars = Array(hex.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out = Data(capacity: chars.count / 2)
        func nibble(_ c: UInt8) -> UInt8? {
            switch c {
            case 48...57: return c - 48
            case 97...102: return c - 97 + 10
            case 65...70: return c - 65 + 10
            default: return nil
            }
        }
        var i = 0
        while i < chars.count {
            guard let hi = nibble(chars[i]), let lo = nibble(chars[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        self = out
    }

    public var hex: String {
        var s = ""
        s.reserveCapacity(count * 2)
        for b in self {
            s += String(format: "%02x", b)
        }
        return s
    }

    /// Copy of bytes [from, to) counted from the first byte, independent of Data's startIndex.
    func sub(_ from: Int, _ to: Int) -> Data {
        return Data(self[(startIndex + from)..<(startIndex + to)])
    }

    func byte(_ i: Int) -> UInt8 {
        return self[startIndex + i]
    }

    var beUInt32: UInt32 {
        precondition(count >= 4)
        var v: UInt32 = 0
        for i in 0..<4 { v = (v << 8) | UInt32(byte(i)) }
        return v
    }

    static func beBytes(_ v: UInt32) -> Data {
        return Data([UInt8(truncatingIfNeeded: v >> 24), UInt8(truncatingIfNeeded: v >> 16),
                     UInt8(truncatingIfNeeded: v >> 8), UInt8(truncatingIfNeeded: v)])
    }
}

public func randomBytes(_ n: Int) -> Data {
    var g = SystemRandomNumberGenerator()
    var d = Data(count: n)
    for i in 0..<n { d[i] = UInt8.random(in: 0...255, using: &g) }
    return d
}
