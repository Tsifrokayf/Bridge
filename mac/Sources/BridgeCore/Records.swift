import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// AES-256-GCM records: nonce = 00 00 00 00 || counter(8, big endian), no AAD.
/// plaintext = type byte || body. Not thread-safe: callers serialize seal() and open() separately.
public final class RecordCodec {
    private let sendKey: SymmetricKey
    private let recvKey: SymmetricKey
    private var sendCounter: UInt64 = 0
    private var recvCounter: UInt64 = 0

    public init(sendKey: Data, recvKey: Data) {
        self.sendKey = SymmetricKey(data: sendKey)
        self.recvKey = SymmetricKey(data: recvKey)
    }

    static func nonce(_ counter: UInt64) -> Data {
        var d = Data(count: 4)
        for i in (0..<8).reversed() { d.append(UInt8(truncatingIfNeeded: counter >> UInt64(i * 8))) }
        return d
    }

    /// Returns a complete wire frame: 4-byte big-endian length followed by ciphertext||tag.
    public func seal(type: UInt8, body: Data) throws -> Data {
        var plain = Data([type])
        plain.append(body)
        let box = try AES.GCM.seal(plain, using: sendKey, nonce: AES.GCM.Nonce(data: RecordCodec.nonce(sendCounter)))
        sendCounter += 1
        var ct = Data(box.ciphertext)
        ct.append(box.tag)
        return Data.beBytes(UInt32(ct.count)) + ct
    }

    /// `ciphertext` is the frame body (without the length prefix).
    public func open(_ ciphertext: Data) throws -> (type: UInt8, body: Data) {
        guard ciphertext.count >= 17 else { throw ProtocolError("record too short") }
        let n = ciphertext.count - 16
        let plain: Data
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: RecordCodec.nonce(recvCounter)),
                ciphertext: ciphertext.sub(0, n), tag: ciphertext.sub(n, n + 16))
            plain = try AES.GCM.open(box, using: recvKey)
        } catch {
            throw ProtocolError("record authentication failed")
        }
        recvCounter += 1
        guard !plain.isEmpty else { throw ProtocolError("empty record") }
        return (plain.byte(0), plain.sub(1, plain.count))
    }
}

/// Splits a TCP byte stream into frames (4-byte big-endian length + body).
public struct FrameDecoder {
    private var buffer = Data()

    public init() {}

    public mutating func append(_ data: Data) {
        buffer.append(data)
    }

    /// Returns the next complete frame body, or nil if more bytes are needed.
    public mutating func next() throws -> Data? {
        guard buffer.count >= 4 else { return nil }
        let n = Int(buffer.sub(0, 4).beUInt32)
        guard n > 0, n <= Wire.maxFrame else { throw ProtocolError("bad frame length") }
        guard buffer.count >= 4 + n else { return nil }
        let body = buffer.sub(4, 4 + n)
        buffer = buffer.sub(4 + n, buffer.count)
        return body
    }
}

public func frame(_ body: Data) -> Data {
    return Data.beBytes(UInt32(body.count)) + body
}
