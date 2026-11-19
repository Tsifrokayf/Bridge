package app.bridge

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.json.JSONObject

/** Base for one-tap Quick Settings tiles that send a single command to the Mac. */
abstract class MacCommandTile : TileService() {
    abstract fun command(active: Boolean): JSONObject
    open fun toggles(): Boolean = false

    private val on get() = toggles() && Companion.active.contains(this::class.java.name)

    override fun onStartListening() {
        val connected = Bridge.get(this).isConnected
        qsTile?.apply {
            state = when {
                !connected -> Tile.STATE_UNAVAILABLE
                on -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    override fun onClick() {
        val m = Bridge.get(this)
        if (!m.isConnected) { onStartListening(); return }
        val name = this::class.java.name
        val next = !on
        if (toggles()) { if (next) active.add(name) else active.remove(name) }
        m.sendJson(command(next))
        onStartListening()
    }

    companion object { val active = HashSet<String>() }
}

class LockMacTile : MacCommandTile() {
    override fun command(active: Boolean) = JSONObject().put("t", "lock_mac")
}

class LocateMacTile : MacCommandTile() {
    override fun command(active: Boolean) = JSONObject().put("t", "locate_mac")
}

class RingMacTile : MacCommandTile() {
    override fun toggles() = true
    override fun command(active: Boolean) = JSONObject().put("t", "find_mac").put("on", active)
}

class MuteMacTile : MacCommandTile() {
    override fun command(active: Boolean) = JSONObject().put("t", "mac_volume").put("action", "mute_toggle")
}
