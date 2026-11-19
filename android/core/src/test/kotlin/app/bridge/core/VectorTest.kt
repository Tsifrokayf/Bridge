package app.bridge.core

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun vectors(): JSONObject = JSONObject(File("../../vectors/vectors.json").readText())
private fun JSONObject.hex(k: String): ByteArray = getString(k).hexToBytes()

class VectorTest {
    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Crypto.hkdf(ikm, salt, info, 42).toHex(),
        )
    }

    @Test
    fun pairingMatchesReference() {
        val v = vectors().getJSONObject("pair")
        val eph = Crypto.ephemeralFrom(v.hex("client_priv"), v.hex("client_pub"))
        val hello = Handshake.buildPairHello(
            v.hex("client_device_id"), eph, v.hex("client_nonce"), v.getString("client_name"),
        )
        assertContentEquals(v.hex("hello_body"), hello)
        assertContentEquals(v.hex("shared_secret"), Crypto.ecdh(eph.privateKey, v.hex("server_pub")))
        assertContentEquals(v.hex("transcript_hash"), Crypto.sha256(hello, v.hex("reply_body")))

        val r = Handshake.finishPair(eph, hello, v.hex("reply_body"))
        assertEquals(v.getString("sas"), r.sas)
        assertContentEquals(v.hex("c2s_key"), r.keys.sendKey)
        assertContentEquals(v.hex("s2c_key"), r.keys.recvKey)
        assertContentEquals(v.hex("pairing_secret"), r.pairingSecret)
        assertContentEquals(v.hex("server_device_id"), r.serverId)
        assertEquals(v.getString("server_name"), r.serverName)
    }

    @Test
    fun reconnectMatchesReference() {
        val v = vectors().getJSONObject("reconnect")
        val eph = Crypto.ephemeralFrom(v.hex("client_priv"), v.hex("client_pub"))
        val secret = v.hex("pairing_secret")
        val hello = Handshake.buildReconnectHello(v.hex("client_device_id"), eph, v.hex("client_nonce"))
        assertContentEquals(v.hex("hello_body"), hello)

        val r = Handshake.finishReconnect(eph, secret, hello, v.hex("reply_body"))
        assertContentEquals(v.hex("finish_body"), r.finishBody)
        assertContentEquals(v.hex("c2s_key"), r.keys.sendKey)
        assertContentEquals(v.hex("s2c_key"), r.keys.recvKey)
    }

    @Test
    fun reconnectRejectsForgedServerMac() {
        val v = vectors().getJSONObject("reconnect")
        val eph = Crypto.ephemeralFrom(v.hex("client_priv"), v.hex("client_pub"))
        val hello = Handshake.buildReconnectHello(v.hex("client_device_id"), eph, v.hex("client_nonce"))
        val reply = v.hex("reply_body")
        reply[reply.size - 1] = (reply[reply.size - 1].toInt() xor 1).toByte()
        val e = assertFailsWith<ProtocolException> {
            Handshake.finishReconnect(eph, v.hex("pairing_secret"), hello, reply)
        }
        assertEquals("server authentication failed", e.message)
    }

    @Test
    fun reconnectRejectsWrongSecret() {
        val v = vectors().getJSONObject("reconnect")
        val eph = Crypto.ephemeralFrom(v.hex("client_priv"), v.hex("client_pub"))
        val hello = Handshake.buildReconnectHello(v.hex("client_device_id"), eph, v.hex("client_nonce"))
        assertFailsWith<ProtocolException> {
            Handshake.finishReconnect(eph, ByteArray(32), hello, v.hex("reply_body"))
        }
    }

    @Test
    fun recordsMatchReference() {
        val v = vectors().getJSONObject("records")
        val key = v.hex("key")
        val items = v.getJSONArray("items")
        val sealer = RecordCodec(key, ByteArray(32))
        val opener = RecordCodec(ByteArray(32), key)
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val frame = it.hex("frame")
            val ct = frame.copyOfRange(4, frame.size)
            assertEquals(ct.size, ByteBuffer.wrap(frame).int)
            assertContentEquals(ct, sealer.seal(it.getInt("type"), it.hex("body")))
            val rec = opener.open(ct)
            assertEquals(it.getInt("type"), rec.type)
            assertContentEquals(it.hex("body"), rec.body)
        }
    }

    @Test
    fun recordTamperAndReplayAreRejected() {
        val k = Crypto.randomBytes(32)
        val a = RecordCodec(k, ByteArray(32))
        val b = RecordCodec(ByteArray(32), k)
        val ct0 = a.seal(Wire.REC_JSON, "x".toByteArray())
        val ct1 = a.seal(Wire.REC_JSON, "y".toByteArray())
        assertFailsWith<ProtocolException> { b.open(ct1) } // out of order
        b.open(ct0)
        assertFailsWith<ProtocolException> { b.open(ct0) } // replay
        val bad = ct1.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<ProtocolException> { b.open(bad) }
    }

    @Test
    fun publicKeyValidation() {
        val v = vectors().getJSONObject("pair")
        val good = v.hex("server_pub")
        Crypto.publicKeyFromRaw(good)
        val offCurve = good.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertFailsWith<ProtocolException> { Crypto.publicKeyFromRaw(offCurve) }
        assertFailsWith<ProtocolException> { Crypto.publicKeyFromRaw(good.copyOf(64)) }
        assertFailsWith<ProtocolException> { Crypto.publicKeyFromRaw(good.copyOf().also { it[0] = 2 }) }
    }

    @Test
    fun nameClippingNeverSplitsCharacters() {
        assertEquals(64, Handshake.clipName("😀".repeat(17)).size)
        assertEquals(61, Handshake.clipName("a" + "😀".repeat(16)).size)
        assertEquals("Pixel", String(Handshake.clipName("Pixel")))
    }

    @Test
    fun fileNamesAreSanitised() {
        assertEquals("passwd", FileNames.safe("../../etc/passwd"))
        assertEquals("c.txt", FileNames.safe("a\\b\\c.txt"))
        assertEquals("bad_name_.txt", FileNames.safe("bad<name>.txt"))
        assertEquals("file", FileNames.safe("..."))
        assertEquals("file", FileNames.safe(""))
        assertEquals(200, FileNames.safe("x".repeat(500)).length)
    }
}
