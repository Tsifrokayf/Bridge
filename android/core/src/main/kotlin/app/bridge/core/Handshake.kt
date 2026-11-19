package app.bridge.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Direction-specific record keys. For the client: send = c2s, recv = s2c. */
class SessionKeys(val sendKey: ByteArray, val recvKey: ByteArray)

class PairResult(
    val keys: SessionKeys,
    /** 6-digit short authentication string; both users must see the same digits. */
    val sas: String,
    val pairingSecret: ByteArray,
    val serverId: ByteArray,
    val serverName: String,
)

class ReconnectResult(val finishBody: ByteArray, val keys: SessionKeys)

/** Client side of the Bridge v1 handshake (see docs/PROTOCOL.md). Pure functions + thin I/O wrappers. */
object Handshake {
    const val VERSION = 1
    const val T_PAIR_HELLO = 0x01
    const val T_RECONNECT_HELLO = 0x02
    const val T_RECONNECT_REPLY = 0x03
    const val T_RECONNECT_FINISH = 0x04
    const val T_PAIR_REPLY = 0x05

    private val UTF8 = Charsets.UTF_8
    private val LABEL_PAIR = "bridge-v1 pair".toByteArray()
    private val LABEL_KEYS = "bridge-v1 keys".toByteArray()
    private val LABEL_SERVER = "bridge-v1 server".toByteArray()
    private val LABEL_CLIENT = "bridge-v1 client".toByteArray()

    /** At most 64 UTF-8 bytes, never cutting a character in half. */
    fun clipName(name: String): ByteArray {
        var b = name.toByteArray(UTF8)
        if (b.size > 64) {
            b = b.copyOf(64)
            val s = UTF8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE)
                .decode(ByteBuffer.wrap(b)).toString()
            b = s.toByteArray(UTF8)
        }
        return b
    }

    fun buildPairHello(deviceId: ByteArray, eph: EphemeralKey, nonce: ByteArray, name: String): ByteArray {
        require(deviceId.size == 16 && nonce.size == 32)
        val nb = clipName(name)
        val out = ByteArrayOutputStream()
        out.write(T_PAIR_HELLO)
        out.write(VERSION)
        out.write(deviceId)
        out.write(eph.publicRaw)
        out.write(nonce)
        out.write(nb.size)
        out.write(nb)
        return out.toByteArray()
    }

    fun finishPair(eph: EphemeralKey, hello: ByteArray, reply: ByteArray): PairResult {
        if (reply.size < 115 || (reply[0].toInt() and 0xFF) != T_PAIR_REPLY) throw ProtocolException("bad pair reply")
        val n = reply[114].toInt() and 0xFF
        if (reply.size != 115 + n) throw ProtocolException("bad pair reply length")
        val serverId = reply.copyOfRange(1, 17)
        val serverPub = reply.copyOfRange(17, 82)
        val serverName = String(reply, 115, n, UTF8)
        val z = Crypto.ecdh(eph.privateKey, serverPub)
        val th = Crypto.sha256(hello, reply)
        val okm = Crypto.hkdf(z, th, LABEL_PAIR, 100)
        val sasInt = ByteBuffer.wrap(okm, 64, 4).int.toLong() and 0xFFFFFFFFL
        val sas = (sasInt % 1_000_000L).toString().padStart(6, '0')
        return PairResult(
            SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64)),
            sas, okm.copyOfRange(68, 100), serverId, serverName,
        )
    }

    fun buildReconnectHello(deviceId: ByteArray, eph: EphemeralKey, nonce: ByteArray): ByteArray {
        require(deviceId.size == 16 && nonce.size == 32)
        val out = ByteArrayOutputStream()
        out.write(T_RECONNECT_HELLO)
        out.write(VERSION)
        out.write(deviceId)
        out.write(eph.publicRaw)
        out.write(nonce)
        return out.toByteArray()
    }

    fun finishReconnect(eph: EphemeralKey, secret: ByteArray, hello: ByteArray, reply: ByteArray): ReconnectResult {
        if (reply.size != 130 || (reply[0].toInt() and 0xFF) != T_RECONNECT_REPLY) throw ProtocolException("bad reconnect reply")
        val serverPub = reply.copyOfRange(1, 66)
        val serverNonce = reply.copyOfRange(66, 98)
        val serverMac = reply.copyOfRange(98, 130)
        val th = Crypto.sha256(hello, serverPub, serverNonce)
        if (!MessageDigest.isEqual(serverMac, Crypto.hmac(secret, LABEL_SERVER, th))) {
            throw ProtocolException("server authentication failed")
        }
        val z = Crypto.ecdh(eph.privateKey, serverPub)
        val finish = byteArrayOf(T_RECONNECT_FINISH.toByte()) + Crypto.hmac(secret, LABEL_CLIENT, th)
        val okm = Crypto.hkdf(z + secret, th, LABEL_KEYS, 64)
        return ReconnectResult(finish, SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64)))
    }

    fun pair(io: FrameIO, deviceId: ByteArray, name: String): PairResult {
        val eph = Crypto.generateEphemeral()
        val hello = buildPairHello(deviceId, eph, Crypto.randomBytes(32), name)
        io.writeFrame(hello)
        return finishPair(eph, hello, io.readFrame())
    }

    fun reconnect(io: FrameIO, deviceId: ByteArray, secret: ByteArray): SessionKeys {
        val eph = Crypto.generateEphemeral()
        val hello = buildReconnectHello(deviceId, eph, Crypto.randomBytes(32))
        io.writeFrame(hello)
        val r = finishReconnect(eph, secret, hello, io.readFrame())
        io.writeFrame(r.finishBody)
        return r.keys
    }
}
