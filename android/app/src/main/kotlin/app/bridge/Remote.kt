package app.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only requests from the Mac: dial, contacts, call log, phone file browser. */
class Remote(private val ctx: Context, private val store: Store, private val manager: ConnectionManager) {
    private val tag = "BridgeRemote"

    fun handle(type: String, m: JSONObject): Boolean {
        when (type) {
            "dial" -> dial(m.optString("number"))
            "open_url" -> openUrl(m.optString("url"))
            "mac_location" -> macLocation(m)
            "sms_search" -> manager.runBackground { searchSms(m.optString("q")) }
            "threads_request" -> manager.runBackground { sendThreads() }
            "thread_request" -> manager.runBackground { sendThread(m.optLong("tid", -1)) }
            "mirror_start", "mirror_stop", "mirror_ack", "mirror_input" -> Mirror.handle(ctx, type, m)
            "contacts_request" -> manager.runBackground { sendContacts() }
            "calls_request" -> manager.runBackground { sendCalls(m.optInt("limit", 30).coerceIn(1, 100)) }
            "fs_list" -> manager.runBackground { fsList(m.optString("req"), m.optString("path")) }
            "fs_get" -> manager.runBackground { fsGet(m.optString("req"), m.optString("path")) }
            else -> return false
        }
        return true
    }

    // ---- SMS conversations -----------------------------------------------------------------------------

    private fun sendThreads() {
        val out = JSONObject().put("t", "sms_threads")
        if (!Perms.has(ctx, android.Manifest.permission.READ_SMS)) {
            manager.sendJson(out.put("error", "perm")); return
        }
        val seen = LinkedHashMap<Long, JSONObject>()
        ctx.contentResolver.query(
            android.provider.Telephony.Sms.CONTENT_URI,
            arrayOf("thread_id", "address", "body", "date", "type", "read"), null, null, "date DESC",
        )?.use { c ->
            var rows = 0
            while (c.moveToNext() && seen.size < 40 && rows++ < 6000) {
                val tid = c.getLong(0)
                if (seen.containsKey(tid)) continue
                val addr = c.getString(1) ?: ""
                seen[tid] = JSONObject().put("tid", tid).put("address", addr)
                    .put("name", contactName(ctx, addr) ?: JSONObject.NULL)
                    .put("snippet", (c.getString(2) ?: "").take(120)).put("ts", c.getLong(3))
                    .put("out", c.getInt(4) != 1).put("unread", c.getInt(4) == 1 && c.getInt(5) == 0)
            }
        }
        manager.sendJson(out.put("items", org.json.JSONArray(seen.values.toList())))
    }

    private fun searchSms(q: String) {
        val out = JSONObject().put("t", "sms_threads").put("search", true)
        if (!Perms.has(ctx, android.Manifest.permission.READ_SMS)) {
            manager.sendJson(out.put("error", "perm")); return
        }
        val items = ArrayList<JSONObject>()
        if (q.length >= 2) {
            val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            ctx.contentResolver.query(
                android.provider.Telephony.Sms.CONTENT_URI, arrayOf("thread_id", "address", "body", "date", "type"),
                "body LIKE ? ESCAPE '\\'", arrayOf("%$esc%"), "date DESC",
            )?.use { c ->
                while (c.moveToNext() && items.size < 40) {
                    val addr = c.getString(1) ?: ""
                    items.add(
                        JSONObject().put("tid", c.getLong(0)).put("address", addr)
                            .put("name", contactName(ctx, addr) ?: JSONObject.NULL)
                            .put("snippet", (c.getString(2) ?: "").take(160)).put("ts", c.getLong(3))
                            .put("out", c.getInt(4) != 1).put("unread", false),
                    )
                }
            }
        }
        manager.sendJson(out.put("items", JSONArray(items)))
    }

    private fun sendThread(tid: Long) {
        val out = JSONObject().put("t", "sms_thread").put("tid", tid)
        if (tid < 0 || !Perms.has(ctx, android.Manifest.permission.READ_SMS)) {
            manager.sendJson(out.put("items", org.json.JSONArray())); return
        }
        val list = ArrayList<JSONObject>()
        ctx.contentResolver.query(
            android.provider.Telephony.Sms.CONTENT_URI, arrayOf("body", "date", "type"),
            "thread_id=?", arrayOf(tid.toString()), "date DESC",
        )?.use { c ->
            while (c.moveToNext() && list.size < 80) {
                list.add(JSONObject().put("text", c.getString(0) ?: "").put("ts", c.getLong(1)).put("out", c.getInt(2) != 1))
            }
        }
        list.reverse()
        manager.sendJson(out.put("items", org.json.JSONArray(list)))
    }

    // ---- Handoff: link from the Mac -----------------------------------------------------------------

    /** Where the Mac is (Find My style): a tappable notification that opens the map. */
    private fun macLocation(m: JSONObject) {
        val err = m.optString("error")
        if (err.isNotEmpty()) {
            android.widget.Toast.makeText(
                ctx, if (err == "permission") "Разрешите Bridge геопозицию на Mac" else "Не удалось определить положение Mac",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        val lat = m.optDouble("lat"); val lon = m.optDouble("lon")
        if (lat.isNaN() || lon.isNaN()) return
        openUrl("https://maps.google.com/?q=$lat,$lon", "Mac найден", "Точность ≈ ${m.optInt("acc")} м — нажмите, чтобы открыть карту")
    }

    /** Android blocks starting activities from the background, so the link arrives as a tappable notification. */
    private fun openUrl(url: String, title: String = "Ссылка с Mac", text: String = url) {
        val uri = android.net.Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https") return
        Notifs.ensureChannels(ctx)
        val open = android.app.PendingIntent.getActivity(
            ctx, url.hashCode(), android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = android.app.Notification.Builder(ctx, Notifs.CH_LINKS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(android.app.NotificationManager::class.java).notify(3000 + (url.hashCode() and 0xFF), n)
    }

    // ---- dial ---------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun dial(number: String) {
        if (!store.smsCalls || !Perms.has(ctx, Manifest.permission.CALL_PHONE)) return
        if (!NUMBER.matches(number)) return
        try {
            ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (e: Exception) {
            Log.w(tag, "dial failed", e)
        }
    }

    // ---- contacts and call log ----------------------------------------------------------------------

    private fun sendContacts() {
        if (!store.smsCalls || !Perms.has(ctx, Manifest.permission.READ_CONTACTS)) {
            manager.sendJson(JSONObject().put("t", "contacts").put("items", JSONArray()).put("ok", false))
            return
        }
        val items = JSONArray()
        val seen = HashSet<String>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
            )?.use { c ->
                while (c.moveToNext() && items.length() < 2000) {
                    val name = c.getString(0)?.take(80) ?: continue
                    val num = c.getString(1)?.take(40) ?: continue
                    if (!seen.add(name + "|" + num.filter { it.isDigit() || it == '+' })) continue
                    items.put(JSONObject().put("n", name).put("p", num))
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "contacts failed", e)
        }
        manager.sendJson(JSONObject().put("t", "contacts").put("ok", true).put("items", items))
    }

    private fun sendCalls(limit: Int) {
        if (!store.smsCalls || !Perms.has(ctx, Manifest.permission.READ_CALL_LOG)) {
            manager.sendJson(JSONObject().put("t", "calls").put("items", JSONArray()).put("ok", false))
            return
        }
        val items = JSONArray()
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                    CallLog.Calls.DATE, CallLog.Calls.DURATION,
                ),
                null, null, CallLog.Calls.DATE + " DESC",
            )?.use { c ->
                while (c.moveToNext() && items.length() < limit) {
                    val kind = when (c.getInt(2)) {
                        CallLog.Calls.OUTGOING_TYPE -> "out"
                        CallLog.Calls.INCOMING_TYPE -> "in"
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> "missed"
                        else -> "other"
                    }
                    val o = JSONObject().put("number", c.getString(0) ?: "").put("type", kind)
                        .put("ts", c.getLong(3)).put("dur", c.getLong(4))
                    c.getString(1)?.takeIf { it.isNotEmpty() }?.let { o.put("name", it.take(80)) }
                    items.put(o)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "call log failed", e)
        }
        manager.sendJson(JSONObject().put("t", "calls").put("ok", true).put("items", items))
    }

    // ---- file browser -------------------------------------------------------------------------------

    private fun hasFileAccess(): Boolean =
        store.fileAccess && (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager())

    /** Maps a Mac-supplied relative path to a file inside shared storage, or null if it escapes the root. */
    private fun resolve(rel: String): File? {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val f = try {
            File(root, rel).canonicalFile
        } catch (e: Exception) {
            return null
        }
        val inside = f.path == root.path || f.path.startsWith(root.path + File.separator)
        return if (inside) f else null
    }

    private fun fsList(req: String, path: String) {
        fun fail(err: String) = manager.sendJson(
            JSONObject().put("t", "fs_list_result").put("req", req).put("path", path).put("ok", false).put("error", err),
        )
        if (!hasFileAccess()) return fail("disabled")
        val dir = resolve(path) ?: return fail("path")
        val files = dir.listFiles() ?: return fail("unreadable")
        val sorted = files.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).take(3000)
        val arr = JSONArray()
        for (f in sorted) {
            arr.put(
                JSONObject().put("name", f.name.take(200)).put("dir", f.isDirectory)
                    .put("size", if (f.isFile) f.length() else 0L).put("mtime", f.lastModified()),
            )
        }
        manager.sendJson(
            JSONObject().put("t", "fs_list_result").put("req", req).put("path", path).put("ok", true).put("entries", arr),
        )
    }

    private fun fsGet(req: String, path: String) {
        fun fail(err: String) = manager.sendJson(
            JSONObject().put("t", "fs_get_result").put("req", req).put("ok", false).put("error", err),
        )
        if (!hasFileAccess()) return fail("disabled")
        val f = resolve(path) ?: return fail("path")
        if (!f.isFile || !f.canRead()) return fail("unreadable")
        val pfd = try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            return fail("unreadable")
        }
        manager.sendJson(JSONObject().put("t", "fs_get_result").put("req", req).put("ok", true))
        manager.sendOutgoingBlocking(Outgoing(f.name, f.length(), pfd), kind = "pull")
    }

    companion object {
        private val NUMBER = Regex("[+0-9*#()\\- ]{1,32}")
    }
}
