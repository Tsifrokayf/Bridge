package app.bridge.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Destination of a file that the peer is sending to us. */
interface IncomingFile {
    fun write(buf: ByteArray, off: Int, len: Int)

    /** Called once after the last chunk; [ok] is false if size or SHA-256 did not match (delete the file then). */
    fun complete(ok: Boolean)

    /** Called if the transfer dies before completion. */
    fun abort()
}

interface SessionListener {
    fun onHello(name: String, platform: String) {}
    fun onPairConfirm(ok: Boolean) {}
    fun onClipboard(text: String) {}

    /** Return a sink to accept the file, or null to reject it. */
    fun openIncoming(id: Int, name: String, size: Long): IncomingFile? = null
    fun onClosed(reason: String) {}

    /** Any JSON message type the session itself does not handle (status, sms, find_phone, ...). */
    fun onMessage(type: String, message: JSONObject) {}
}

/**
 * One encrypted connection to the peer. Reads on its own thread; sending is thread-safe.
 * [closeTransport] must close the underlying socket.
 */
class Session(
    private val channel: SecureChannel,
    private val listener: SessionListener,
    private val deviceName: String,
    private val platform: String,
    private val closeTransport: () -> Unit,
) {
    private class Incoming(val file: IncomingFile, val size: Long) {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var received = 0L
    }

    private val incoming = ConcurrentHashMap<Int, Incoming>()
    private val accepts = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val acks = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val nextId = AtomicInteger(1)
    private val closedFlag = AtomicBoolean(false)

    @Volatile
    private var lastRx = System.nanoTime()

    val isClosed: Boolean get() = closedFlag.get()

    fun start() {
        sendJson(
            JSONObject().put("t", "hello").put("name", deviceName).put("platform", platform)
                .put("caps", JSONArray(Wire.CAPS)),
        )
        thread(name = "bridge-reader", isDaemon = true) { readLoop() }
        thread(name = "bridge-keepalive", isDaemon = true) { keepAlive() }
    }

    fun close(reason: String = "closed") = shutdown(reason)

    // ---- sending -------------------------------------------------------

    fun sendJson(o: JSONObject) {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        channel.send(Wire.REC_JSON, b)
    }

    fun sendPairConfirm(ok: Boolean) = sendJson(JSONObject().put("t", "pair_confirm").put("ok", ok))

    fun sendClipboard(text: String) =
        sendJson(JSONObject().put("t", "clipboard").put("text", text).put("ts", System.currentTimeMillis()))

    fun sendNotification(
        key: String, app: String, pkg: String, title: String, text: String, ts: Long,
        canReply: Boolean = false, clearable: Boolean = true,
    ) = sendJson(
        JSONObject().put("t", "notification").put("key", key).put("app", app).put("pkg", pkg)
            .put("title", title).put("text", text).put("ts", ts)
            .put("reply", canReply).put("clear", clearable),
    )

    fun sendNotificationRemoved(key: String) =
        sendJson(JSONObject().put("t", "notification_removed").put("key", key))

    /**
     * Blocking: offers the file, streams it once accepted and waits for the peer's verdict.
     * Call from a worker thread. [size] must be the exact number of bytes [input] will deliver.
     */
    fun sendFile(
        name: String, size: Long, input: InputStream, kind: String? = null, onProgress: (Long) -> Unit = {},
    ): Boolean {
        val id = nextId.getAndIncrement()
        val accept = CompletableFuture<Boolean>()
        val ack = CompletableFuture<Boolean>()
        accepts[id] = accept
        acks[id] = ack
        try {
            sendJson(
                JSONObject().put("t", "file_offer").put("id", id).put("name", name).put("size", size)
                    .apply { if (kind != null) put("kind", kind) },
            )
            if (!accept.get(60, TimeUnit.SECONDS)) return false
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(4 + Wire.CHUNK_SIZE)
            ByteBuffer.wrap(buf).putInt(id)
            var sent = 0L
            while (true) {
                val n = input.read(buf, 4, Wire.CHUNK_SIZE)
                if (n < 0) break
                if (n == 0) continue
                md.update(buf, 4, n)
                channel.send(Wire.REC_CHUNK, buf, 4 + n)
                sent += n
                onProgress(sent)
            }
            if (sent != size) {
                sendJson(JSONObject().put("t", "file_cancel").put("id", id))
                return false
            }
            sendJson(JSONObject().put("t", "file_done").put("id", id).put("sha256", md.digest().toHex()))
            return ack.get(5, TimeUnit.MINUTES)
        } catch (e: Exception) {
            return false
        } finally {
            accepts.remove(id)
            acks.remove(id)
        }
    }

    // ---- receiving -----------------------------------------------------

    private fun readLoop() {
        var reason = "closed"
        try {
            while (!isClosed) {
                val rec = channel.receive()
                lastRx = System.nanoTime()
                when (rec.type) {
                    Wire.REC_JSON -> handleJson(JSONObject(String(rec.body, Charsets.UTF_8)))
                    Wire.REC_CHUNK -> handleChunk(rec.body)
                    else -> throw ProtocolException("unknown record type")
                }
            }
        } catch (e: Exception) {
            reason = e.message ?: e.javaClass.simpleName
        } finally {
            shutdown(reason)
        }
    }

    private fun handleJson(m: JSONObject) {
        when (m.optString("t")) {
            "hello" -> listener.onHello(m.optString("name"), m.optString("platform"))
            "pair_confirm" -> listener.onPairConfirm(m.optBoolean("ok"))
            "ping" -> sendJson(JSONObject().put("t", "pong"))
            "clipboard" -> listener.onClipboard(m.getString("text"))
            "file_offer" -> {
                val id = m.getInt("id")
                val size = m.getLong("size")
                val sink = if (size < 0) null else listener.openIncoming(id, m.optString("name"), size)
                if (sink == null) {
                    sendJson(JSONObject().put("t", "file_reject").put("id", id).put("reason", "declined"))
                } else {
                    incoming.put(id, Incoming(sink, size))?.file?.abort()
                    sendJson(JSONObject().put("t", "file_accept").put("id", id))
                }
            }
            "file_accept" -> accepts[m.getInt("id")]?.complete(true)
            "file_reject" -> accepts[m.getInt("id")]?.complete(false)
            "file_ack" -> acks[m.getInt("id")]?.complete(m.optBoolean("ok"))
            "file_cancel" -> incoming.remove(m.getInt("id"))?.file?.abort()
            "file_done" -> {
                val id = m.getInt("id")
                val f = incoming.remove(id)
                val ok = f != null && f.received == f.size && f.digest.digest().toHex() == m.optString("sha256")
                f?.file?.complete(ok)
                sendJson(JSONObject().put("t", "file_ack").put("id", id).put("ok", ok))
            }
            "" -> Unit
            else -> listener.onMessage(m.optString("t"), m) // features handled by the app; unknown ones are ignored there
        }
    }

    private fun handleChunk(body: ByteArray) {
        if (body.size < 4) throw ProtocolException("short chunk")
        val id = ByteBuffer.wrap(body).int
        val f = incoming[id] ?: return
        val n = body.size - 4
        f.received += n
        if (f.received > f.size) {
            incoming.remove(id)
            f.file.abort()
            return
        }
        f.digest.update(body, 4, n)
        f.file.write(body, 4, n)
    }

    private fun keepAlive() {
        try {
            while (!isClosed) {
                Thread.sleep(15_000)
                if (isClosed) break
                if (System.nanoTime() - lastRx > TimeUnit.SECONDS.toNanos(45)) {
                    shutdown("timeout")
                    break
                }
                sendJson(JSONObject().put("t", "ping"))
            }
        } catch (e: Exception) {
            shutdown(e.message ?: "keepalive failed")
        }
    }

    private fun shutdown(reason: String) {
        if (!closedFlag.compareAndSet(false, true)) return
        accepts.values.forEach { it.complete(false) }
        acks.values.forEach { it.complete(false) }
        incoming.values.forEach { runCatching { it.file.abort() } }
        incoming.clear()
        runCatching { closeTransport() }
        listener.onClosed(reason)
    }
}

object FileNames {
    private val bad = Regex("[\\u0000-\\u001f<>:\"|?*]")

    /** Mirrors reference/bridge_proto.safe_name: last path segment, no control/reserved chars. */
    fun safe(name: String): String {
        var n = name.replace('\\', '/').substringAfterLast('/')
        n = bad.replace(n, "_").trim(' ', '.')
        if (n.length > 200) n = n.substring(0, 200)
        return n.ifEmpty { "file" }
    }
}
