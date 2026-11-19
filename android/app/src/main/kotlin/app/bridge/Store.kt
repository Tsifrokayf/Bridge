package app.bridge

import android.content.Context
import android.os.Build
import app.bridge.core.Crypto
import app.bridge.core.hexToBytes
import app.bridge.core.toHex

/**
 * Persistent settings and the pairing secret.
 * The secret lives in the app's private storage (allowBackup is off, so it is not copied to cloud backups).
 */
class Store(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    val deviceId: ByteArray
        get() {
            var h = p.getString("device_id", null)
            if (h == null) {
                h = Crypto.randomBytes(16).toHex()
                p.edit().putString("device_id", h).apply()
            }
            return h.hexToBytes()
        }

    val deviceName: String get() = Build.MODEL ?: "Android"

    val isPaired: Boolean get() = macIdHex != null && secret != null

    val macIdHex: String? get() = p.getString("mac_id", null)
    val macName: String get() = p.getString("mac_name", null) ?: "Mac"
    val secret: ByteArray? get() = p.getString("secret", null)?.hexToBytes()

    var lastHost: String?
        get() = p.getString("last_host", null)
        set(v) { p.edit().putString("last_host", v).apply() }

    var lastPort: Int
        get() = p.getInt("last_port", 0)
        set(v) { p.edit().putInt("last_port", v).apply() }

    /** Recently used Mac addresses ("ip:port", newest first): reconnect without scanning when the Mac is on another route. */
    val knownHosts: List<String>
        get() = (p.getString("known_hosts", "") ?: "").split(',').filter { it.isNotEmpty() }

    fun rememberHost(host: String, port: Int) {
        val k = "$host:$port"
        val list = (listOf(k) + knownHosts.filter { it != k }).take(6)
        p.edit().putString("known_hosts", list.joinToString(",")).apply()
    }

    /** IPv4 addresses the Mac reported for itself (Wi-Fi, Ethernet…). */
    var macIps: List<String>
        get() = (p.getString("mac_ips", "") ?: "").split(',').filter { it.isNotEmpty() }
        set(v) { p.edit().putString("mac_ips", v.take(8).joinToString(",")).apply() }

    /** Hardware addresses of the Mac's network interfaces, for Wake-on-LAN. */
    var macAddrs: List<String>
        get() = (p.getString("mac_addrs", "") ?: "").split(',').filter { it.isNotEmpty() }
        set(v) { p.edit().putString("mac_addrs", v.take(8).joinToString(",")).apply() }

    var forwardNotifications: Boolean
        get() = p.getBoolean("fwd_notifications", true)
        set(v) { p.edit().putBoolean("fwd_notifications", v).apply() }

    /** Packages whose notifications are not forwarded to the Mac. */
    var mutedApps: Set<String>
        get() = p.getStringSet("muted_apps", emptySet()) ?: emptySet()
        set(v) { p.edit().putStringSet("muted_apps", HashSet(v)).apply() }

    /** Apps that have posted notifications ("package\tlabel"); fallback list when the system hides installed apps. */
    var seenApps: Set<String>
        get() = p.getStringSet("seen_apps", emptySet()) ?: emptySet()
        set(v) { p.edit().putStringSet("seen_apps", HashSet(v)).apply() }

    /** False after the user pressed "Стоп"; stops the boot receiver from restarting the service. */
    var enabled: Boolean
        get() = p.getBoolean("enabled", true)
        set(v) { p.edit().putBoolean("enabled", v).apply() }

    /** Send new photos/screenshots to the Mac. */
    var syncPhotos: Boolean
        get() = p.getBoolean("sync_photos", false)
        set(v) { p.edit().putBoolean("sync_photos", v).apply() }

    /** MediaStore _ID of the newest image already handled; -1 = not initialised yet. */
    var photoLastId: Long
        get() = p.getLong("photo_last_id", -1L)
        set(v) { p.edit().putLong("photo_last_id", v).apply() }

    /** Forward SMS and call events to the Mac. */
    var smsCalls: Boolean
        get() = p.getBoolean("sms_calls", false)
        set(v) { p.edit().putBoolean("sms_calls", v).apply() }

    /** Let the Mac browse and download files from shared storage (needs "All files access"). */
    var fileAccess: Boolean
        get() = p.getBoolean("file_access", false)
        set(v) { p.edit().putBoolean("file_access", v).apply() }

    var applyClipboard: Boolean
        get() = p.getBoolean("apply_clipboard", true)
        set(v) { p.edit().putBoolean("apply_clipboard", v).apply() }

    fun savePairing(macId: ByteArray, macName: String, secret: ByteArray) {
        p.edit().putString("mac_id", macId.toHex()).putString("mac_name", macName)
            .putString("secret", secret.toHex()).apply()
    }

    fun forget() {
        p.edit().remove("mac_id").remove("mac_name").remove("secret").remove("last_host").remove("last_port").remove("known_hosts").remove("mac_ips").remove("mac_addrs").apply()
    }
}
