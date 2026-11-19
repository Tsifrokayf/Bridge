package app.bridge

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import org.json.JSONObject

/**
 * Optional features on top of the basic session: phone status, photo sync and
 * commands that arrive from the Mac (reply/dismiss, find phone, media keys, SMS, reject call).
 */
class Features(private val ctx: Context, private val store: Store, private val manager: ConnectionManager) {
    private val tag = "BridgeFeatures"
    private val remote = Remote(ctx, store, manager)
    private val nowPlaying = NowPlaying(ctx, manager)
    private val main = Handler(Looper.getMainLooper())

    private var batteryReceiver: BroadcastReceiver? = null
    private var dndReceiver: BroadcastReceiver? = null
    private var ringerReceiver: BroadcastReceiver? = null
    private var zenObserver: ContentObserver? = null
    @Volatile private var lastDnd: Boolean? = null
    private var photoObserver: ContentObserver? = null
    private var lastStatusKey = ""
    private val photoLock = Any()
    private val scanRunnable = Runnable { manager.runBackground { scanPhotos() } }

    // ---- lifecycle ----------------------------------------------------------------------------------

    fun onConnected() {
        main.post {
            registerStatus()
            registerDnd()
            registerRinger()
            startAgenda()
            nowPlaying.start()
            registerPhotos()
        }
    }

    fun onDisconnected() {
        Mirror.stop(ctx)
        main.post {
            nowPlaying.stop()
            ringerReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
            ringerReceiver = null
            main.removeCallbacks(agendaTick)
            batteryReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
            batteryReceiver = null
            dndReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
            dndReceiver = null
            zenObserver?.let { runCatching { ctx.contentResolver.unregisterContentObserver(it) } }
            zenObserver = null
            lastDnd = null
            photoObserver?.let { runCatching { ctx.contentResolver.unregisterContentObserver(it) } }
            photoObserver = null
            main.removeCallbacks(scanRunnable)
            lastStatusKey = ""
        }
    }

    // ---- status -------------------------------------------------------------------------------------

    private fun registerStatus() {
        if (batteryReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = sendStatus(i)
        }
        val f = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(r, f)
        batteryReceiver = r
        sendStatus(sticky)
    }

    private fun networkType(): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    }

    private fun sendStatus(i: Intent?) {
        if (i == null) return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val net = networkType()
        val key = "$pct/$charging/$net"
        if (key == lastStatusKey) return
        lastStatusKey = key
        manager.sendJson(
            JSONObject().put("t", "status").put("battery", pct).put("charging", charging).put("net", net),
        )
    }

    // ---- agenda (calendar + next alarm) ---------------------------------------------------------------

    private val agendaTick = object : Runnable {
        override fun run() {
            manager.runBackground { PhoneInfo.report(ctx, manager) }
            main.postDelayed(this, 15 * 60_000L)
        }
    }

    private fun startAgenda() {
        main.removeCallbacks(agendaTick)
        main.post(agendaTick)
    }

    // ---- ringer mode, camera ------------------------------------------------------------------------

    private fun ringerName(): String = when (ctx.getSystemService(android.media.AudioManager::class.java).ringerMode) {
        android.media.AudioManager.RINGER_MODE_SILENT -> "silent"
        android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        else -> "normal"
    }

    private fun sendRinger() = manager.sendJson(JSONObject().put("t", "ringer").put("mode", ringerName()))

    private fun registerRinger() {
        if (ringerReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = sendRinger()
        }
        val f = IntentFilter(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(r, f)
        ringerReceiver = r
        sendRinger()
    }

    /** Ringer / vibrate / silent from the Mac. Silent needs the policy access that notification access already includes. */
    private fun setRinger(mode: String) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        am.ringerMode = when (mode) {
            "silent" -> if (nm.isNotificationPolicyAccessGranted) android.media.AudioManager.RINGER_MODE_SILENT
                        else android.media.AudioManager.RINGER_MODE_VIBRATE
            "vibrate" -> android.media.AudioManager.RINGER_MODE_VIBRATE
            else -> android.media.AudioManager.RINGER_MODE_NORMAL
        }
    }

    /** The camera can only open in front of the user: a heads-up notification (and, where allowed, the camera itself). */
    private fun requestCamera() {
        val intent = Intent(ctx, CameraRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = android.app.PendingIntent.getActivity(ctx, 5, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
        Notifs.ensureChannels(ctx)
        val n = android.app.Notification.Builder(ctx, Notifs.CH_LINKS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Mac просит снимок")
            .setContentText("Нажмите, чтобы открыть камеру — фото сразу уйдёт на Mac")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(android.app.NotificationManager::class.java).notify(5005, n)
        runCatching { ctx.startActivity(intent) }   // works when Bridge is in the foreground
    }

    // ---- Do Not Disturb -----------------------------------------------------------------------------

    private fun dndOn(): Boolean {
        val f = ctx.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter
        return f == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
            f == android.app.NotificationManager.INTERRUPTION_FILTER_NONE ||
            f == android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
    }

    private fun registerDnd() {
        if (dndReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = sendDnd(force = false)
        }
        val f = IntentFilter(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(r, f)
        dndReceiver = r
        // Xiaomi/HyperOS and some other skins do not always send the broadcast: also watch the system setting itself
        // (and NotificationForwarder.onInterruptionFilterChanged calls dndChanged()).
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = sendDnd(force = false)
        }
        runCatching { ctx.contentResolver.registerContentObserver(android.provider.Settings.Global.getUriFor("zen_mode"), false, obs) }
        zenObserver = obs
        sendDnd(force = true)
    }

    /** Called by the notification listener when the interruption filter changes. */
    fun dndChanged() { main.post { sendDnd(force = false) } }

    private fun sendDnd(force: Boolean) {
        val on = dndOn()
        if (!force && lastDnd == on) return
        lastDnd = on
        manager.sendJson(
            JSONObject().put("t", "dnd").put("on", on)
                .put("canSet", ctx.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted),
        )
    }

    private fun setDnd(on: Boolean) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        nm.setInterruptionFilter(
            if (on) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else android.app.NotificationManager.INTERRUPTION_FILTER_ALL,
        )
    }

    // ---- photo sync ---------------------------------------------------------------------------------

    private val imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun registerPhotos() {
        if (Bridge.slowLink || !store.syncPhotos || !Perms.hasAll(ctx, Perms.photos) || photoObserver != null) return
        val obs = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                main.removeCallbacks(scanRunnable)
                main.postDelayed(scanRunnable, 2500) // let the camera finish writing
            }
        }
        ctx.contentResolver.registerContentObserver(imagesUri, true, obs)
        photoObserver = obs
        main.postDelayed(scanRunnable, 1500) // catch up on what was taken while disconnected
    }

    /** Newest image id, used as the starting point when sync is switched on (old photos are not sent). */
    fun currentMaxPhotoId(): Long = try {
        ctx.contentResolver.query(
            imagesUri, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media._ID} DESC LIMIT 1",
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) {
        0L
    }

    private fun scanPhotos() {
        synchronized(photoLock) {
            if (!manager.isConnected || !store.syncPhotos || !Perms.hasAll(ctx, Perms.photos)) return
            val last = store.photoLastId
            if (last < 0) {
                store.photoLastId = currentMaxPhotoId()
                return
            }
            val ids = ArrayList<Long>()
            try {
                ctx.contentResolver.query(
                    imagesUri, arrayOf(MediaStore.Images.Media._ID),
                    "${MediaStore.Images.Media._ID} > ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                    arrayOf(last.toString()), "${MediaStore.Images.Media._ID} ASC",
                )?.use { c -> while (c.moveToNext() && ids.size < 25) ids.add(c.getLong(0)) }
            } catch (e: Exception) {
                Log.w(tag, "photo query failed", e)
                return
            }
            for (id in ids) {
                if (!manager.isConnected) return
                val out = manager.openOutgoing(ContentUris.withAppendedId(imagesUri, id))
                if (out != null && !manager.sendOutgoingBlocking(out, kind = "photo", showProgress = false)) return
                store.photoLastId = id // also skips images that cannot be opened
            }
            if (ids.size >= 25) main.postDelayed(scanRunnable, 500)
        }
    }

    // ---- commands from the Mac ----------------------------------------------------------------------

    fun handle(type: String, m: JSONObject) {
        try {
            if (remote.handle(type, m)) return
            when (type) {
                "notification_reply" -> NotificationForwarder.reply(m.getString("key"), m.getString("text"))
                "notification_dismiss" -> NotificationForwarder.dismiss(m.getString("key"))
                "find_phone" -> if (m.optBoolean("on")) FindPhone.start(ctx) else FindPhone.stop(ctx)
                "media" -> MediaControl.handle(ctx, m.optString("action"))
                "sms_send" -> sendSms(m)
                "dnd_set" -> setDnd(m.optBoolean("on"))
                "agenda_request" -> manager.runBackground { PhoneInfo.report(ctx, manager) }
                "ringer_set" -> setRinger(m.optString("mode"))
                "camera_request" -> main.post { requestCamera() }
                "locate_phone" -> manager.runBackground { Locator.report(ctx, manager) }
                "now_playing_request" -> main.post { nowPlaying.stop(); nowPlaying.start() }
                "mac_status" -> {
                    Bridge.macBattery = m.optInt("battery", -1)
                    Bridge.macCharging = m.optBoolean("charging")
                    m.optJSONArray("ips")?.let { a -> store.macIps = (0 until a.length()).map { a.getString(it) } }
                    m.optJSONArray("macs")?.let { a -> store.macAddrs = (0 until a.length()).map { a.getString(it) } }
                    Bridge.onMacStatus?.invoke()
                    checkMacBattery()
                }
                "mac_state" -> manager.onMacState(m.optString("state"))
                "call_action" -> callAction(m.optString("action"), m.optBoolean("on", true))
            }
        } catch (e: Exception) {
            Log.w(tag, "command $type failed", e)
        }
    }

    private var macLowWarned = false

    /** Like the Apple ecosystem: tells you when the Mac is about to run out of battery. */
    private fun checkMacBattery() {
        val b = Bridge.macBattery
        if (b < 0) return
        if (Bridge.macCharging || b > 25) { macLowWarned = false; return }
        if (b > 15 || macLowWarned) return
        macLowWarned = true
        Notifs.ensureChannels(ctx)
        val n = android.app.Notification.Builder(ctx, Notifs.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Mac скоро разрядится")
            .setContentText("Заряд $b% — подключите зарядку")
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(android.app.NotificationManager::class.java).notify(5006, n)
    }

    @Suppress("DEPRECATION")
    private fun sendSms(m: JSONObject) {
        val req = m.optString("req")
        val ok = try {
            if (!store.smsCalls || !Perms.has(ctx, android.Manifest.permission.SEND_SMS)) {
                false
            } else {
                val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
                else SmsManager.getDefault()
                val text = m.getString("text")
                sm.sendMultipartTextMessage(m.getString("to"), null, sm.divideMessage(text), null, null)
                true
            }
        } catch (e: Exception) {
            Log.w(tag, "sms failed", e)
            false
        }
        manager.sendJson(JSONObject().put("t", "sms_result").put("req", req).put("ok", ok))
    }

    @Suppress("DEPRECATION")
    /** Call control from the Mac: accept / reject (hang up) / speaker / mute. Audio itself stays on the phone. */
    private fun callAction(action: String, on: Boolean) {
        if (!store.smsCalls) return
        runCatching {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            val canAnswer = Perms.has(ctx, android.Manifest.permission.ANSWER_PHONE_CALLS)
            when (action) {
                "reject", "hangup" -> if (canAnswer) tm.endCall()
                "accept" -> if (canAnswer) tm.acceptRingingCall()
                "speaker" -> @Suppress("DEPRECATION") { am.isSpeakerphoneOn = on }
                "mute" -> am.isMicrophoneMute = on
            }
        }
    }
}
