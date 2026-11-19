package app.bridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent

/** "Find my phone": loud alarm sound until stopped (max 2 minutes). Works even in silent mode (alarm stream). */
object FindPhone {
    private const val ID = 77
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var oldVolume = -1
    private val autoStop = Runnable { Bridge.current?.let { stop(it.context) } }

    @Synchronized
    fun start(ctx: Context) {
        if (player != null) return
        val am = ctx.getSystemService(AudioManager::class.java)
        oldVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        player = try {
            // Own "ping" sound (three rising pings, repeating).
            ctx.resources.openRawResourceFd(R.raw.find_ping).use { afd ->
                MediaPlayer().apply {
                    setAudioAttributes(attrs)
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    isLooping = true
                    prepare()
                    start()
                }
            }
        } catch (e: Exception) {
            // Fall back to the system alarm tone.
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        }
        runCatching {
            vibrator(ctx)?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }

        val stop = PendingIntent.getService(
            ctx, 3, Intent(ctx, BridgeService::class.java).setAction(Notifs.ACTION_STOP_FIND), PendingIntent.FLAG_IMMUTABLE,
        )
        Notifs.ensureChannels(ctx)
        val n = Notification.Builder(ctx, Notifs.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Mac ищет этот телефон")
            .setContentText("Нажмите, чтобы выключить сигнал")
            .setContentIntent(stop)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Выключить", stop).build())
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID, n)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, 120_000)
    }

    @Synchronized
    fun stop(ctx: Context) {
        handler.removeCallbacks(autoStop)
        // Every step is independent: one failing (e.g. volume change refused under Do Not Disturb)
        // must not leave the vibration running or the notification hanging.
        stopVibration(ctx)
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        if (oldVolume >= 0) {
            val v = oldVolume
            oldVolume = -1
            runCatching { ctx.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
        }
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(ID) }
        // Some vendors' vibrator services ignore a cancel that races with the last pulse: repeat it.
        handler.postDelayed({ stopVibration(ctx) }, 400)
        handler.postDelayed({ stopVibration(ctx) }, 1500)
    }

    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    private fun stopVibration(ctx: Context) {
        runCatching { vibrator(ctx)?.cancel() }
    }
}

/** Media keys and volume, as if pressed on the phone. */
object MediaControl {
    fun handle(ctx: Context, action: String) {
        val am = ctx.getSystemService(AudioManager::class.java)
        when (action) {
            "play_pause" -> key(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> key(am, KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> key(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume_up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "volume_down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    private fun key(am: AudioManager, code: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
