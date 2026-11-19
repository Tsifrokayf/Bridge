package app.bridge

import android.content.Context
import android.content.Intent

/** Process-wide access to the single [ConnectionManager]. */
object Bridge {
    @Volatile
    private var manager: ConnectionManager? = null

    val current: ConnectionManager? get() = manager

    /** True while the only link to the Mac is Bluetooth LE (no shared network): heavy features wait for Wi-Fi. */
    @Volatile var slowLink = false

    /** Battery of the Mac (percent, charging); -1 until the Mac reports it. */
    @Volatile var macBattery = -1
    @Volatile var macCharging = false
    @Volatile var onMacStatus: (() -> Unit)? = null

    @Synchronized
    fun get(ctx: Context): ConnectionManager {
        val app = ctx.applicationContext
        return manager ?: ConnectionManager(app, Store(app)).also { manager = it }
    }

    /** Only call from the foreground (activity) or from a boot/package-replaced receiver. */
    fun startService(ctx: Context) {
        ctx.startForegroundService(Intent(ctx, BridgeService::class.java))
    }
}
