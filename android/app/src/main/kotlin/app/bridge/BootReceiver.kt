package app.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        if (store.isPaired && store.enabled) {
            try {
                Bridge.startService(context)
            } catch (e: Exception) {
                // Background start can be refused by the system; the app will connect when opened.
            }
        }
    }
}
