import Foundation
#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

/// Thin wrappers over CryptoKit matching reference/bridge_proto.py.
enum BCrypto {
    static func sha256(_ parts: Data...) -> Data {
        var h = SHA256()
        for p in parts { h.update(data: p) }
        return Data(h.finalize())
    }

    static func hmac(secret: Data, label: String, th: Data) -> Data {
        var m = HMAC<SHA256>(key: SymmetricKey(data: secret))
        m.update(data: Data(label.utf8))
        m.update(data: th)
        return Data(m.finalize())
    }

    /// Constant-time verification of a tag produced by `hmac`.
    static func hmacMatches(_ tag: Data, secret: Data, label: String, th: Data) -> Bool {
        return HMAC<SHA256>.isValidAuthenticationCode(
            tag, authenticating: Data(label.utf8) + th, using: SymmetricKey(data: secret))
    }

    static func hkdf(ikm: Data, salt: Data, info: Data, length: Int) -> Data {
        let key = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: length)
        return key.withUnsafeBytes { Data($0) }
    }

    static func publicBytes(_ k: P256.KeyAgreement.PrivateKey) -> Data {
        return k.publicKey.x963Representation
    }

    static func ecdh(_ priv: P256.KeyAgreement.PrivateKey, peerPublic: Data) throws -> Data {
        guard peerPublic.count == 65, peerPublic.byte(0) == 4 else {
            throw ProtocolError("bad public key encoding")
        }
        let pub: P256.KeyAgreement.PublicKey
        do {
            pub = try P256.KeyAgreement.PublicKey(x963Representation: peerPublic)
        } catch {
            throw ProtocolError("public key not on curve")
        }
        let secret = try priv.sharedSecretFromKeyAgreement(with: pub)
        return secret.withUnsafeBytes { Data($0) }
    }
}
