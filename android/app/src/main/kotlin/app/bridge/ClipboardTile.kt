package app.bridge

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: one tap sends the clipboard to the Mac. */
class ClipboardTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = if (Bridge.get(this@ClipboardTile).isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        val i = Intent(this, SendClipboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(i)
        }
    }
}
