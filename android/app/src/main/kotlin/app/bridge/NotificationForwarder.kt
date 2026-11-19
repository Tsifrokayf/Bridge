package app.bridge

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/** Forwards phone notifications to the Mac while a session is connected, and executes reply/dismiss from the Mac. */
class NotificationForwarder : NotificationListenerService() {
    private val store by lazy { Store(this) }

    override fun onListenerConnected() {
        instance = this
        // Remember apps that currently have notifications, so the app filter has something to list.
        runCatching {
            val known = store.seenApps.map { it.substringBefore('\t') }.toSet()
            val add = activeNotifications.map { it.packageName }.distinct()
                .filter { it != packageName && it !in known }
                .map { pkg ->
                    val label = runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    pkg + "\t" + label
                }
            if (add.isNotEmpty()) store.seenApps = store.seenApps + add
        }
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        Bridge.current?.dndChanged()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!store.forwardNotifications) return
        val m = Bridge.current ?: return
        if (!m.isConnected) return
        if (sbn.packageName == packageName) return
        if (sbn.packageName in store.mutedApps) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if (n.visibility == Notification.VISIBILITY_SECRET) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val app = try {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        if (store.seenApps.none { it.startsWith(sbn.packageName + "\t") }) {
            store.seenApps = store.seenApps + (sbn.packageName + "\t" + app)
        }
        val action = replyAction(n)
        if (action != null) {
            if (live.size > 200) live.clear()
            live[sbn.key] = action
        }
        m.sendNotification(
            sbn.key, app, sbn.packageName, title.take(200), text.take(1000), sbn.postTime,
            canReply = action != null, clearable = sbn.isClearable,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        live.remove(sbn.key)
        val m = Bridge.current ?: return
        if (!m.isConnected) return
        m.sendNotificationRemoved(sbn.key)
    }

    companion object {
        @Volatile
        var instance: NotificationForwarder? = null

        private val live = ConcurrentHashMap<String, Notification.Action>()

        private fun replyAction(n: Notification): Notification.Action? =
            n.actions?.firstOrNull { a -> a.actionIntent != null && a.remoteInputs?.any { it.allowFreeFormInput } == true }

        fun reply(key: String, text: String) {
            val a = live[key] ?: return
            val ctx = Bridge.current?.context ?: return
            val inputs = a.remoteInputs ?: return
            val intent = Intent()
            val results = Bundle()
            for (ri in inputs) results.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            try {
                a.actionIntent.send(ctx, 0, intent)
            } catch (e: Exception) {
                Log.w("BridgeNotif", "reply failed", e)
            }
        }

        fun dismiss(key: String) {
            instance?.cancelNotification(key)
            live.remove(key)
        }
    }
}
