package app.bridge.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** Runs this Kotlin client against the Python reference peer ("fake Mac") over a real TCP socket. */
class InteropTest {
    private class Ref(stateDir: File, pair: Boolean) {
        private val history = CopyOnWriteArrayList<String>()
        val proc: Process

        init {
            val script = File("../../reference/bridge_ref.py").absoluteFile
            val cmd = mutableListOf("python3", script.path, "serve", "--state-dir", stateDir.absolutePath, "--once")
            if (pair) cmd += "--pair"
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["PYTHONIOENCODING"] = "utf-8"
            proc = pb.start()
            thread(isDaemon = true) {
                proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { history.add(it) }
            }
        }

        fun waitFor(prefix: String, seconds: Long = 20): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (System.nanoTime() < deadline) {
                history.firstOrNull { it.startsWith(prefix) }?.let { return it }
                Thread.sleep(20)
            }
            fail("timeout waiting for '$prefix'; got: $history")
        }

        fun port(): Int = waitFor("PORT").split(" ")[1].toInt()
        fun finish() {
            if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }

    private class Probe : SessionListener {
        val hello = CompletableFuture<String>()
        val pairConfirm = CompletableFuture<Boolean>()
        val clipboard = CompletableFuture<String>()
        val file = CompletableFuture<ByteArray>()
        val closed = CompletableFuture<String>()

        override fun onHello(name: String, platform: String) { hello.complete("$name/$platform") }
        override fun onPairConfirm(ok: Boolean) { pairConfirm.complete(ok) }
        override fun onClipboard(text: String) { clipboard.complete(text) }
        override fun onClosed(reason: String) { closed.complete(reason) }
        override fun openIncoming(id: Int, name: String, size: Long): IncomingFile {
            val buf = ByteArrayOutputStream()
            return object : IncomingFile {
                override fun write(buf2: ByteArray, off: Int, len: Int) = buf.write(buf2, off, len)
                override fun complete(ok: Boolean) {
                    if (ok) file.complete(buf.toByteArray()) else file.completeExceptionally(IOException("bad file"))
                }
                override fun abort() { file.completeExceptionally(IOException("aborted")) }
            }
        }
    }

    private fun <T> CompletableFuture<T>.await(): T = get(20, TimeUnit.SECONDS)
    private fun hex(b: ByteArray) = b.toHex()
    private fun sha(b: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(b))

    private fun pythonAvailable(): Boolean = try {
        ProcessBuilder("python3", "-c", "import cryptography").start().waitFor() == 0
    } catch (e: Exception) { false }

    private fun demoBytes() = ByteArray(300_000) { ((it * 7 + 3) and 0xFF).toByte() }

    private fun connect(port: Int): Pair<Socket, FrameIO> {
        val s = Socket("127.0.0.1", port)
        s.tcpNoDelay = true
        return s to FrameIO(s.getInputStream(), s.getOutputStream())
    }

    @Test
    fun pairReconnectAndTransfersAgainstReference() {
        assumeTrue(pythonAvailable(), "python3 with 'cryptography' is required for the interop test")
        val dir = Files.createTempDirectory("bridge-ref").toFile()
        val deviceId = Crypto.randomBytes(16)
        val secret: ByteArray

        // ---- 1. pairing (SAS must match what the reference peer computed) -----------------------------
        run {
            val ref = Ref(dir, pair = true)
            try {
                val (sock, io) = connect(ref.port())
                sock.use {
                    val res = Handshake.pair(io, deviceId, "Test Phone ✓")
                    secret = res.pairingSecret
                    assertEquals(ref.waitFor("SAS").split(" ")[1], res.sas)
                    val probe = Probe()
                    val s = Session(SecureChannel(io, res.keys), probe, "Test Phone", "android") { sock.close() }
                    s.start()
                    s.sendPairConfirm(true)
                    assertTrue(probe.pairConfirm.await())
                    assertEquals("RefMac/mac", probe.hello.await())
                    assertEquals("from-mac ✓", probe.clipboard.await())
                    assertContentEquals(demoBytes(), probe.file.await())

                    s.sendClipboard("привет")
                    val payload = ByteArray(700_000).also { Random(1).nextBytes(it) }
                    assertTrue(s.sendFile("photo.bin", payload.size.toLong(), payload.inputStream()))
                    val fileLine = ref.waitFor("FILE").split(" ")
                    assertEquals("photo.bin", fileLine[1])
                    assertEquals(payload.size.toString(), fileLine[2])
                    assertEquals(sha(payload), fileLine[3])
                    assertEquals("CLIPBOARD \"привет\"", ref.waitFor("CLIPBOARD"))
                    s.sendNotification("k1", "Telegram", "org.telegram", "Title", "Body ✓", 1L)
                    s.sendNotificationRemoved("k1")
                    ref.waitFor("NOTIFICATION_REMOVED k1")
                    s.close()
                }
                ref.waitFor("PAIRED")
                ref.waitFor("CLOSED")
            } finally { ref.finish() }
        }

        // ---- 2. reconnect with the stored secret --------------------------------------------------------
        run {
            val ref = Ref(dir, pair = false)
            try {
                val (sock, io) = connect(ref.port())
                sock.use {
                    val keys = Handshake.reconnect(io, deviceId, secret)
                    val probe = Probe()
                    val s = Session(SecureChannel(io, keys), probe, "Test Phone", "android") { sock.close() }
                    s.start()
                    assertEquals("from-mac ✓", probe.clipboard.await())
                    assertContentEquals(demoBytes(), probe.file.await())
                    s.sendClipboard("again")
                    ref.waitFor("CLIPBOARD \"again\"")
                    s.close()
                }
                assertEquals("MODE reconnect", ref.waitFor("MODE"))
                ref.waitFor("CLOSED")
            } finally { ref.finish() }
        }

        // ---- 3. wrong secret: the client refuses the server, nothing is exchanged -----------------------
        run {
            val ref = Ref(dir, pair = false)
            try {
                val (sock, io) = connect(ref.port())
                sock.use {
                    val e = assertFailsWith<ProtocolException> { Handshake.reconnect(io, deviceId, ByteArray(32)) }
                    assertEquals("server authentication failed", e.message)
                }
                ref.waitFor("REJECT")
            } finally { ref.finish() }
        }

        // ---- 4. pairing is refused while the Mac is not in pairing mode ----------------------------------
        run {
            val ref = Ref(dir, pair = false)
            try {
                val (sock, io) = connect(ref.port())
                sock.use {
                    assertFailsWith<IOException> { Handshake.pair(io, Crypto.randomBytes(16), "Intruder") }
                }
                ref.waitFor("REJECT")
            } finally { ref.finish() }
        }
    }
}
