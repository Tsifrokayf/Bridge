package app.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import org.json.JSONObject
import java.util.concurrent.Executors

/** Answers "where is the phone?" from the Mac (also used by Find Phone). Needs the location permission. */
object Locator {
    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Everything the Mac features ask for at once: location (Find Phone) and calendar (agenda). */
    val allPermissions = permissions + Manifest.permission.READ_CALENDAR

    fun has(ctx: Context) = Perms.has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || Perms.has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Best recent position; asks for a fresh fix when the cached one is old. Reports through [manager]. */
    @SuppressLint("MissingPermission")
    fun report(ctx: Context, manager: ConnectionManager) {
        if (!has(ctx)) {
            manager.sendJson(JSONObject().put("t", "phone_location").put("error", "permission"))
            return
        }
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) || it == LocationManager.PASSIVE_PROVIDER }.getOrDefault(false) }
        val cached = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        val fresh = cached != null && System.currentTimeMillis() - cached.time < 2 * 60_000
        if (fresh) { send(manager, cached); return }
        if (Build.VERSION.SDK_INT >= 30 && providers.isNotEmpty()) {
            val provider = if (LocationManager.GPS_PROVIDER in providers) LocationManager.GPS_PROVIDER else providers.first()
            val exec = Executors.newSingleThreadExecutor()
            val cancel = CancellationSignal()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ cancel.cancel() }, 15_000)
            lm.getCurrentLocation(provider, cancel, exec) { loc ->
                if (loc != null) send(manager, loc) else if (cached != null) send(manager, cached)
                else manager.sendJson(JSONObject().put("t", "phone_location").put("error", "unavailable"))
                exec.shutdown()
            }
        } else if (cached != null) {
            send(manager, cached)
        } else {
            manager.sendJson(JSONObject().put("t", "phone_location").put("error", "unavailable"))
        }
    }

    private fun send(manager: ConnectionManager, l: Location) {
        manager.sendJson(
            JSONObject().put("t", "phone_location").put("lat", l.latitude).put("lon", l.longitude)
                .put("acc", l.accuracy.toDouble()).put("ts", l.time),
        )
    }
}
