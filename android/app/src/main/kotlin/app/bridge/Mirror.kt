package app.bridge

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Screen mirroring + remote control from the Mac.
 * Frames are JPEG snapshots of the screen (MediaProjection) sent over the encrypted session;
 * input comes back as normalised coordinates and is injected by [BridgeAccessibility].
 */
object Mirror {
    @Volatile var active = false
    @Volatile var seq = 0
    @Volatile var acked = 0
    @Volatile var width = 720
    @Volatile var fps = 8
    @Volatile var quality = 55
    private const val ID_ASK = 4100

    fun handle(ctx: Context, type: String, m: JSONObject) {
        when (type) {
            "mirror_start" -> {
                width = m.optInt("w", 720).coerceIn(360, 1280)
                fps = m.optInt("fps", 8).coerceIn(2, 15)
                quality = m.optInt("q", 55).coerceIn(30, 90)
                if (active) { state("on"); return }
                if (Bridge.slowLink) { state("nonet"); return }
                state("asking")
                askPermission(ctx)
            }
            "mirror_stop" -> stop(ctx)
            "mirror_ack" -> acked = m.optInt("seq", acked)
            "mirror_input" -> input(ctx, m)
        }
    }

    fun state(s: String) {
        Bridge.current?.sendJson(
            JSONObject().put("t", "mirror_state").put("state", s)
                .put("control", BridgeAccessibility.instance != null),
        )
    }

    /** Android shows the capture consent dialog only from an activity; from the background it comes via a notification. */
    private fun askPermission(ctx: Context) {
        Notifs.ensureChannels(ctx)
        val i = Intent(ctx, MirrorPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 41, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, Notifs.CH_LINKS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Mac просит показать экран")
            .setContentText("Нажмите, чтобы разрешить трансляцию")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(android.app.NotificationManager::class.java).notify(ID_ASK, n)
        runCatching { ctx.startActivity(i) }
    }

    fun stop(ctx: Context) {
        if (!active) return
        active = false
        ctx.stopService(Intent(ctx, MirrorService::class.java))
        state("off")
    }

    fun onStopped() {
        if (active) { active = false; state("off") }
    }

    fun sendFrame(w: Int, h: Int, jpeg: ByteArray) {
        val m = Bridge.current ?: return
        seq += 1
        m.sendJson(
            JSONObject().put("t", "mirror_frame").put("w", w).put("h", h).put("seq", seq)
                .put("d", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
        )
    }

    fun sendAudio(pcm: ByteArray) {
        Bridge.current?.sendJson(
            JSONObject().put("t", "mirror_audio").put("rate", 24000).put("d", Base64.encodeToString(pcm, Base64.NO_WRAP)),
        )
    }

    private fun input(ctx: Context, m: JSONObject) {
        val a = BridgeAccessibility.instance
        if (a == null) { state("on"); return }
        val (sw, sh) = BridgeAccessibility.screenSize(ctx)
        fun px(k: String) = (m.optDouble(k, 0.5).coerceIn(0.0, 1.0) * sw).toFloat()
        fun py(k: String) = (m.optDouble(k, 0.5).coerceIn(0.0, 1.0) * sh).toFloat()
        when (m.optString("kind")) {
            "tap" -> a.gesture(px("x"), py("y"), px("x"), py("y"), m.optLong("ms", 60))
            "swipe" -> a.gesture(px("x1"), py("y1"), px("x2"), py("y2"), m.optLong("ms", 250))
            "pinch" -> a.pinch(px("x"), py("y"), m.optBoolean("in", true), minOf(sw, sh).toFloat())
            "key" -> a.key(m.optString("key"))
            "text" -> a.typeText(m.optString("text"))
        }
    }
}

/** Foreground service that owns the MediaProjection (required by Android 14+). */
class MirrorService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("bridge-mirror").apply { start() }
    private val handler = Handler(thread.looper)
    private var last = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        Notifs.ensureChannels(this)
        val stop = PendingIntent.getService(
            this, 5, Intent(this, MirrorService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, Notifs.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Экран транслируется на Mac")
            .setContentText("Нажмите, чтобы остановить")
            .setContentIntent(stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(42, n)
        if (i.action == "stop") { Mirror.onStopped(); stopSelf(); return START_NOT_STICKY }

        val code = i.getIntExtra("code", Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data = i.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Mirror.onStopped(); stopSelf() }
        }, handler)

        val (rw, rh) = BridgeAccessibility.screenSize(this)
        val w = (minOf(Mirror.width, rw) / 2) * 2
        val h = ((rh.toLong() * w / rw).toInt() / 2) * 2
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        display = p.createVirtualDisplay(
            "bridge-mirror", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        startAudio(p)
        Mirror.acked = Mirror.seq
        Mirror.active = true
        Mirror.state("on")
        return START_NOT_STICKY
    }

    @Volatile private var audioOn = false

    /** Captures what apps play (media/games; not phone calls) and streams it as 24 kHz mono PCM. */
    private fun startAudio(p: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29 || !Perms.has(this, android.Manifest.permission.RECORD_AUDIO)) return
        try {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val rec = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(24000 * 2)
                .setAudioPlaybackCaptureConfig(cfg).build()
            rec.startRecording()
            audioOn = true
            Thread {
                val buf = ByteArray(4800) // 100 ms
                while (audioOn) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    var loud = false
                    var i = 0
                    while (i < n - 1) { if (buf[i].toInt() != 0 || buf[i + 1].toInt() != 0) { loud = true; break }; i += 2 }
                    if (loud && Mirror.seq - Mirror.acked <= 3) Mirror.sendAudio(buf.copyOf(n))
                }
                runCatching { rec.stop() }
                rec.release()
            }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            audioOn = false
        }
    }

    private fun onImage(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - last < 1000L / Mirror.fps || Mirror.seq - Mirror.acked > 3) return
            last = now
            val plane = img.planes[0]
            val pad = plane.rowStride - plane.pixelStride * img.width
            val wide = Bitmap.createBitmap(img.width + pad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            wide.copyPixelsFromBuffer(plane.buffer)
            val bmp = if (pad > 0) Bitmap.createBitmap(wide, 0, 0, img.width, img.height) else wide
            val out = ByteArrayOutputStream(64 * 1024)
            bmp.compress(Bitmap.CompressFormat.JPEG, Mirror.quality, out)
            Mirror.sendFrame(img.width, img.height, out.toByteArray())
            if (bmp !== wide) bmp.recycle()
            wide.recycle()
        } finally {
            img.close()
        }
    }

    override fun onDestroy() {
        audioOn = false
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        thread.quitSafely()
        super.onDestroy()
    }
}

/** Invisible activity that shows the system "start recording / casting?" dialog. */
class MirrorPermissionActivity : Activity() {
    private val req = 77

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sound capture needs RECORD_AUDIO; the picture works without it.
        if (!Perms.has(this, android.Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 78)
        } else {
            launchCapture()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 78) launchCapture()
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), req)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == req) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, MirrorService::class.java).putExtra("code", resultCode).putExtra("data", data),
                )
            } else {
                Mirror.state("denied")
            }
        }
        finish()
    }
}
