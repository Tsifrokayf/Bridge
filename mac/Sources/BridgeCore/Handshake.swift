import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

public struct PairKeys {
    public let c2s: Data
    public let s2c: Data
    /// 6-digit short authentication string; both users compare it.
    public let sas: String
    public let secret: Data
}

public struct DirectionalKeys {
    public let c2s: Data
    public let s2c: Data
}

/// Server (Mac) side of the Bridge v1 handshake. Pure functions, mirroring reference/bridge_proto.py.
public enum Handshake {
    static let labelPair = Data("bridge-v1 pair".utf8)
    static let labelKeys = Data("bridge-v1 keys".utf8)

    /// At most 64 UTF-8 bytes, cut on a character boundary.
    public static func clipName(_ name: String) -> Data {
        let bytes = Data(name.utf8)
        var n = min(64, bytes.count)
        while n > 0 {
            if let s = String(data: bytes.sub(0, n), encoding: .utf8) { return Data(s.utf8) }
            n -= 1
        }
        return Data()
    }

    // ---- encoding -------------------------------------------------------------------------------

    public struct PairHello {
        public let version: UInt8
        public let deviceId: Data
        public let publicKey: Data
        public let nonce: Data
        public let name: String
    }

    public static func parsePairHello(_ body: Data) throws -> PairHello {
        guard body.count >= 116, body.byte(0) == Wire.tPairHello else { throw ProtocolError("bad pair hello") }
        let n = Int(body.byte(115))
        guard body.count == 116 + n else { throw ProtocolError("bad pair hello length") }
        return PairHello(
            version: body.byte(1),
            deviceId: body.sub(2, 18),
            publicKey: body.sub(18, 83),
            nonce: body.sub(83, 115),
            name: String(decoding: body.sub(116, 116 + n), as: UTF8.self))
    }

    public static func encodePairReply(deviceId: Data, publicKey: Data, nonce: Data, name: String) -> Data {
        let nb = clipName(name)
        var d = Data([Wire.tPairReply])
        d.append(deviceId)
        d.append(publicKey)
        d.append(nonce)
        d.append(UInt8(nb.count))
        d.append(nb)
        return d
    }

    public struct ReconnectHello {
        public let version: UInt8
        public let deviceId: Data
        public let publicKey: Data
        public let nonce: Data
    }

    public static func parseReconnectHello(_ body: Data) throws -> ReconnectHello {
        guard body.count == 115, body.byte(0) == Wire.tReconnectHello else {
            throw ProtocolError("bad reconnect hello")
        }
        return ReconnectHello(
            version: body.byte(1), deviceId: body.sub(2, 18), publicKey: body.sub(18, 83), nonce: body.sub(83, 115))
    }

    public static func encodeReconnectReply(publicKey: Data, nonce: Data, serverMac: Data) -> Data {
        var d = Data([Wire.tReconnectReply])
        d.append(publicKey)
        d.append(nonce)
        d.append(serverMac)
        return d
    }

    // ---- key derivation -------------------------------------------------------------------------

    public static func derivePair(z: Data, transcriptHash: Data) -> PairKeys {
        let okm = BCrypto.hkdf(ikm: z, salt: transcriptHash, info: labelPair, length: 100)
        let v = okm.sub(64, 68).beUInt32 % 1_000_000
        return PairKeys(
            c2s: okm.sub(0, 32), s2c: okm.sub(32, 64),
            sas: String(format: "%06u", v), secret: okm.sub(68, 100))
    }

    public static func deriveReconnect(z: Data, secret: Data, transcriptHash: Data) -> DirectionalKeys {
        let okm = BCrypto.hkdf(ikm: z + secret, salt: transcriptHash, info: labelKeys, length: 64)
        return DirectionalKeys(c2s: okm.sub(0, 32), s2c: okm.sub(32, 64))
    }

    public static func pairTranscript(hello: Data, reply: Data) -> Data {
        return BCrypto.sha256(hello, reply)
    }

    public static func reconnectTranscript(hello: Data, serverPublic: Data, serverNonce: Data) -> Data {
        return BCrypto.sha256(hello, serverPublic, serverNonce)
    }

    // ---- server steps ---------------------------------------------------------------------------

    public struct PairResponse {
        public let reply: Data
        public let keys: PairKeys
        public let clientId: Data
        public let clientName: String
    }

    public static func serverPairRespond(
        hello: Data, deviceId: Data, name: String,
        ephemeral: P256.KeyAgreement.PrivateKey, nonce: Data
    ) throws -> PairResponse {
        let h = try parsePairHello(hello)
        guard h.version == Wire.version else { throw ProtocolError("unsupported version") }
        let reply = encodePairReply(
            deviceId: deviceId, publicKey: BCrypto.publicBytes(ephemeral), nonce: nonce, name: name)
        let z = try BCrypto.ecdh(ephemeral, peerPublic: h.publicKey)
        let keys = derivePair(z: z, transcriptHash: pairTranscript(hello: hello, reply: reply))
        return PairResponse(reply: reply, keys: keys, clientId: h.deviceId, clientName: h.name)
    }

    public struct ReconnectResponse {
        public let reply: Data
        public let transcriptHash: Data
        public let sharedSecret: Data
    }

    public static func serverReconnectRespond(
        hello: Data, secret: Data, ephemeral: P256.KeyAgreement.PrivateKey, nonce: Data
    ) throws -> ReconnectResponse {
        let h = try parseReconnectHello(hello)
        guard h.version == Wire.version else { throw ProtocolError("unsupported version") }
        let spub = BCrypto.publicBytes(ephemeral)
        let th = reconnectTranscript(hello: hello, serverPublic: spub, serverNonce: nonce)
        let reply = encodeReconnectReply(
            publicKey: spub, nonce: nonce, serverMac: BCrypto.hmac(secret: secret, label: "bridge-v1 server", th: th))
        let z = try BCrypto.ecdh(ephemeral, peerPublic: h.publicKey)
        return ReconnectResponse(reply: reply, transcriptHash: th, sharedSecret: z)
    }

    public static func serverReconnectFinish(
        finish: Data, secret: Data, transcriptHash: Data, sharedSecret: Data
    ) throws -> DirectionalKeys {
        guard finish.count == 33, finish.byte(0) == Wire.tReconnectFinish else {
            throw ProtocolError("bad reconnect finish")
        }
        guard BCrypto.hmacMatches(finish.sub(1, 33), secret: secret, label: "bridge-v1 client", th: transcriptHash) else {
            throw ProtocolError("client authentication failed")
        }
        return deriveReconnect(z: sharedSecret, secret: secret, transcriptHash: transcriptHash)
    }
}

// ---- handshake state machine -----------------------------------------------------------------------

public struct Established {
    public enum Mode { case pair, reconnect }
    public let mode: Mode
    public let peerId: Data
    /// Only known for pairing (from the hello); reconnects learn the name from the "hello" message.
    public let peerName: String?
    /// Server-to-client / client-to-server keyed codec, ready for the encrypted phase.
    public let codec: RecordCodec
    public let sas: String?
    public let pairingSecret: Data?
}

public enum HandshakeStep {
    /// Send this frame body and wait for the next frame.
    case reply(Data)
    /// Keys are ready; optionally send `reply` first.
    case established(Established, reply: Data?)
}

public final class ServerHandshake {
    private enum State {
        case awaitHello
        case awaitFinish(secret: Data, th: Data, z: Data, clientId: Data)
        case done
    }

    private let deviceId: Data
    private let name: String
    private let pairingEnabled: () -> Bool
    private let lookupSecret: (Data) -> Data?
    private let ephemeral: P256.KeyAgreement.PrivateKey
    private let nonce: Data
    private var state: State = .awaitHello

    public init(
        deviceId: Data, name: String,
        pairingEnabled: @escaping () -> Bool,
        lookupSecret: @escaping (Data) -> Data?,
        ephemeral: P256.KeyAgreement.PrivateKey? = nil,
        nonce: Data? = nil
    ) {
        self.deviceId = deviceId
        self.name = name
        self.pairingEnabled = pairingEnabled
        self.lookupSecret = lookupSecret
        self.ephemeral = ephemeral ?? P256.KeyAgreement.PrivateKey()
        self.nonce = nonce ?? randomBytes(32)
    }

    public func receive(frame: Data) throws -> HandshakeStep {
        guard !frame.isEmpty else { throw ProtocolError("empty frame") }
        switch state {
        case .awaitHello:
            switch frame.byte(0) {
            case Wire.tReconnectHello:
                let h = try Handshake.parseReconnectHello(frame)
                guard let secret = lookupSecret(h.deviceId) else { throw ProtocolError("unknown device") }
                let r = try Handshake.serverReconnectRespond(
                    hello: frame, secret: secret, ephemeral: ephemeral, nonce: nonce)
                state = .awaitFinish(secret: secret, th: r.transcriptHash, z: r.sharedSecret, clientId: h.deviceId)
                return .reply(r.reply)
            case Wire.tPairHello:
                guard pairingEnabled() else { throw ProtocolError("pairing not enabled") }
                let r = try Handshake.serverPairRespond(
                    hello: frame, deviceId: deviceId, name: name, ephemeral: ephemeral, nonce: nonce)
                state = .done
                let codec = RecordCodec(sendKey: r.keys.s2c, recvKey: r.keys.c2s)
                return .established(
                    Established(mode: .pair, peerId: r.clientId, peerName: r.clientName, codec: codec,
                                sas: r.keys.sas, pairingSecret: r.keys.secret),
                    reply: r.reply)
            default:
                throw ProtocolError("unknown handshake type")
            }
        case .awaitFinish(let secret, let th, let z, let clientId):
            let keys = try Handshake.serverReconnectFinish(
                finish: frame, secret: secret, transcriptHash: th, sharedSecret: z)
            state = .done
            let codec = RecordCodec(sendKey: keys.s2c, recvKey: keys.c2s)
            return .established(
                Established(mode: .reconnect, peerId: clientId, peerName: nil, codec: codec,
                            sas: nil, pairingSecret: nil),
                reply: nil)
        case .done:
            throw ProtocolError("handshake already finished")
        }
    }
}
