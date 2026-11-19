package app.bridge

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Foreground service that keeps the connection to the Mac alive. */
class BridgeService : Service(), ConnectionManager.Listener {
    private lateinit var manager: ConnectionManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.ensureChannels(this)
        manager = Bridge.get(this)
        manager.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Notifs.ACTION_STOP_FIND) {
            FindPhone.stop(this)
            return START_STICKY
        }
        if (intent?.action == Notifs.ACTION_STOP) {
            Store(this).enabled = false
            manager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(Notifs.ID_SERVICE, Notifs.service(this, statusText()), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        manager.start()
        return START_STICKY
    }

    override fun onStateChanged() {
        getSystemService(NotificationManager::class.java).notify(Notifs.ID_SERVICE, Notifs.service(this, statusText()))
    }

    private fun statusText(): String = Ui.statusLine(manager)

    override fun onDestroy() {
        manager.removeListener(this)
        super.onDestroy()
    }
}
