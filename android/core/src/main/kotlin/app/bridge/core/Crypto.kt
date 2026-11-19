package app.bridge.core

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ProtocolException(message: String) : Exception(message)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { ((this[it * 2].digitToInt(16) shl 4) or this[it * 2 + 1].digitToInt(16)).toByte() }
}

/** P-256 key pair for one handshake. [publicRaw] is the 65-byte uncompressed SEC1 point. */
class EphemeralKey(val privateKey: PrivateKey, val publicRaw: ByteArray)

object Crypto {
    private val random = SecureRandom()

    private val curve: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun generateEphemeral(): EphemeralKey {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        return EphemeralKey(kp.private, rawPublic(kp.public as ECPublicKey))
    }

    /** For tests: JCA cannot derive the public point from a scalar, so the caller supplies it. */
    internal fun ephemeralFrom(scalar: ByteArray, publicRaw: ByteArray): EphemeralKey {
        val priv = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), curve))
        return EphemeralKey(priv, publicRaw)
    }

    private fun putFixed32(v: BigInteger, out: ByteArray, off: Int) {
        val b = v.toByteArray()
        val start = if (b.size > 32) b.size - 32 else 0
        val len = b.size - start
        System.arraycopy(b, start, out, off + 32 - len, len)
    }

    fun rawPublic(pub: ECPublicKey): ByteArray {
        val out = ByteArray(65)
        out[0] = 4
        putFixed32(pub.w.affineX, out, 1)
        putFixed32(pub.w.affineY, out, 33)
        return out
    }

    private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
        val p = (curve.curve.field as ECFieldFp).p
        if (x.signum() < 0 || y.signum() < 0 || x >= p || y >= p) return false
        val lhs = y.multiply(y).mod(p)
        val rhs = x.multiply(x).multiply(x).add(curve.curve.a.multiply(x)).add(curve.curve.b).mod(p)
        return lhs == rhs
    }

    fun publicKeyFromRaw(raw: ByteArray): PublicKey {
        if (raw.size != 65 || raw[0] != 4.toByte()) throw ProtocolException("bad public key encoding")
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        if (!isOnCurve(x, y)) throw ProtocolException("public key not on curve")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curve))
    }

    fun ecdh(priv: PrivateKey, peerRaw: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(publicKeyFromRaw(peerRaw), true)
        return ka.generateSecret()
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }
}
