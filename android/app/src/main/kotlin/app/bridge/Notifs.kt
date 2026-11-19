package app.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notifs {
    const val CH_SERVICE = "service"
    const val CH_FILES = "files"
    const val CH_ALERT = "alert"
    const val CH_LINKS = "links"
    const val ID_SERVICE = 1
    const val ACTION_STOP = "app.bridge.STOP"
    const val ACTION_STOP_FIND = "app.bridge.STOP_FIND"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Соединение с Mac", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Поиск телефона", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LINKS, "Ссылки с Mac", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_FILES, "Полученные файлы", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun service(ctx: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val clip = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, SendClipboardActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            ctx, 2, Intent(ctx, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Bridge")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Буфер → Mac", clip).build())
            .addAction(Notification.Action.Builder(null, "Стоп", stop).build())
            .build()
    }
}
