package app.bridge.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Wire {
    const val REC_JSON = 0x01
    const val REC_CHUNK = 0x02
    const val MAX_FRAME = 2 * 1024 * 1024
    const val CHUNK_SIZE = 256 * 1024
    const val SERVICE_TYPE = "_bridgesync._tcp"

    /** Port the Mac listens on when it is free; lets the phone find the Mac without mDNS. */
    const val DEFAULT_PORT = 47653

    /** Capabilities announced in the hello message. */
    val CAPS = listOf(
        "clipboard", "notifications", "files", "reply", "status", "find", "media", "sms", "calls", "photos",
        "dial", "contacts", "fs", "clipimg", "dnd", "handoff", "mirror", "findmac",
    )
}

class Record(val type: Int, val body: ByteArray)

/** 4-byte big-endian length prefix + body. One reader thread, any number of writers. */
class FrameIO(input: InputStream, private val output: OutputStream) {
    private val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val writeLock = Any()

    fun readFrame(): ByteArray {
        val n = din.readInt()
        if (n <= 0 || n > Wire.MAX_FRAME) throw ProtocolException("bad frame length")
        val b = ByteArray(n)
        din.readFully(b)
        return b
    }

    fun writeFrame(body: ByteArray, len: Int = body.size) {
        val buf = ByteBuffer.allocate(4 + len).putInt(len).put(body, 0, len).array()
        synchronized(writeLock) {
            output.write(buf)
            output.flush()
        }
    }
}

/** AES-256-GCM records, nonce = 00 00 00 00 || counter (8 bytes BE), no AAD. */
class RecordCodec(sendKey: ByteArray, recvKey: ByteArray) {
    private val sk = SecretKeySpec(sendKey, "AES")
    private val rk = SecretKeySpec(recvKey, "AES")
    var sendCounter = 0L
        private set
    var recvCounter = 0L
        private set

    private fun nonce(counter: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    /** Returns the ciphertext (with tag), without the length prefix. */
    fun seal(type: Int, body: ByteArray, len: Int = body.size): ByteArray {
        val pt = ByteArray(1 + len)
        pt[0] = type.toByte()
        System.arraycopy(body, 0, pt, 1, len)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, sk, GCMParameterSpec(128, nonce(sendCounter)))
        val ct = c.doFinal(pt)
        sendCounter++
        return ct
    }

    fun open(ct: ByteArray): Record {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, rk, GCMParameterSpec(128, nonce(recvCounter)))
        val pt = try {
            c.doFinal(ct)
        } catch (e: GeneralSecurityException) {
            throw ProtocolException("record authentication failed")
        }
        recvCounter++
        if (pt.isEmpty()) throw ProtocolException("empty record")
        return Record(pt[0].toInt() and 0xFF, pt.copyOfRange(1, pt.size))
    }
}

class SecureChannel(private val io: FrameIO, keys: SessionKeys) {
    private val codec = RecordCodec(keys.sendKey, keys.recvKey)
    private val sendLock = Any()

    fun send(type: Int, body: ByteArray, len: Int = body.size) {
        synchronized(sendLock) { io.writeFrame(codec.seal(type, body, len)) }
    }

    /** Call from a single reader thread. */
    fun receive(): Record = codec.open(io.readFrame())
}
