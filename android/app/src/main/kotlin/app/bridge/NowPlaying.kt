package app.bridge

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import org.json.JSONObject

/**
 * What the phone is playing (title, artist, app, play state), reported to the Mac so it can show it and control it.
 * Uses the media sessions of other apps, which needs the same notification access Bridge already asks for.
 */
class NowPlaying(private val ctx: Context, private val manager: ConnectionManager) {
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(ctx, NotificationForwarder::class.java)
    private val controllers = ArrayList<MediaController>()
    private var lastKey = ""
    private var started = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list ?: emptyList()) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = report()
        override fun onPlaybackStateChanged(state: PlaybackState?) = report()
        override fun onSessionDestroyed() = report()
    }

    fun start() {
        if (started) return
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            started = true
            attach(msm.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            Log.i("BridgeNowPlaying", "no notification access yet")
        } catch (e: Exception) {
            Log.w("BridgeNowPlaying", "start failed", e)
        }
    }

    fun stop() {
        if (started) runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        started = false
        detach()
        lastKey = ""
    }

    private fun detach() {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers.clear()
    }

    private fun attach(list: List<MediaController>) {
        detach()
        for (c in list) { runCatching { c.registerCallback(callback) }; controllers.add(c) }
        report()
    }

    private fun report() {
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val c = playing ?: controllers.firstOrNull { it.metadata != null }
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (c == null || title.isEmpty()) {
            send("", "", "", false)
            return
        }
        val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val app = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        send(title, artist, app, c.playbackState?.state == PlaybackState.STATE_PLAYING)
    }

    private fun send(title: String, artist: String, app: String, playing: Boolean) {
        val key = "$title|$artist|$app|$playing"
        if (key == lastKey) return
        lastKey = key
        manager.sendJson(
            JSONObject().put("t", "now_playing").put("title", title).put("artist", artist)
                .put("app", app).put("playing", playing),
        )
    }
}
