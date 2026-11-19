package app.bridge.core

import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two Kotlin sessions over a real socket pair: covers the v1.1 messages without needing Python. */
class LoopbackTest {
    private class Rec : SessionListener {
        val messages = CopyOnWriteArrayList<JSONObject>()
        val clipboard = CompletableFuture<String>()
        val closed = CompletableFuture<String>()
        val got = java.util.concurrent.Semaphore(0)
        override fun onClipboard(text: String) { clipboard.complete(text) }
        override fun onMessage(type: String, message: JSONObject) { messages.add(message); got.release() }
        override fun onClosed(reason: String) { closed.complete(reason) }
    }

    private fun pair(): Triple<Session, Session, Pair<Rec, Rec>> {
        val server = ServerSocket(0)
        val a = Socket("127.0.0.1", server.localPort)
        val b = server.accept()
        server.close()
        val k1 = Crypto.randomBytes(32)
        val k2 = Crypto.randomBytes(32)
        val ra = Rec()
        val rb = Rec()
        val sa = Session(SecureChannel(FrameIO(a.getInputStream(), a.getOutputStream()), SessionKeys(k1, k2)), ra, "A", "android") { a.close() }
        val sb = Session(SecureChannel(FrameIO(b.getInputStream(), b.getOutputStream()), SessionKeys(k2, k1)), rb, "B", "mac") { b.close() }
        sa.start()
        sb.start()
        return Triple(sa, sb, ra to rb)
    }

    @Test
    fun appLevelMessagesReachTheListener() {
        val (phone, mac, recs) = pair()
        val (phoneRec, macRec) = recs
        phone.sendJson(JSONObject().put("t", "status").put("battery", 77).put("charging", true))
        phone.sendNotification("k", "Telegram", "org.telegram", "T", "B", 1L, canReply = true, clearable = false)
        mac.sendJson(JSONObject().put("t", "find_phone").put("on", true))
        mac.sendJson(JSONObject().put("t", "notification_reply").put("key", "k").put("text", "ок"))

        assertTrue(macRec.got.tryAcquire(2, 5, TimeUnit.SECONDS))
        assertTrue(phoneRec.got.tryAcquire(2, 5, TimeUnit.SECONDS))
        val atMac = macRec.messages.associateBy { it.getString("t") }
        assertEquals(77, atMac.getValue("status").getInt("battery"))
        assertTrue(atMac.getValue("notification").getBoolean("reply"))
        assertEquals(false, atMac.getValue("notification").getBoolean("clear"))
        val atPhone = phoneRec.messages.associateBy { it.getString("t") }
        assertEquals("ок", atPhone.getValue("notification_reply").getString("text"))
        assertEquals(true, atPhone.getValue("find_phone").getBoolean("on"))
        phone.close(); mac.close()
    }
}
