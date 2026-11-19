package app.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.TelephonyManager
import org.json.JSONObject

internal fun contactName(ctx: Context, number: String?): String? {
    if (number.isNullOrEmpty() || !Perms.has(ctx, android.Manifest.permission.READ_CONTACTS)) return null
    return try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }
}

/** Incoming SMS → Mac. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Store(context).smsCalls) return
        val m = Bridge.current ?: return
        if (!m.isConnected) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Long messages arrive as several parts from the same sender.
        for ((from, texts) in parts.groupBy { it.originatingAddress ?: "" }) {
            val o = JSONObject().put("t", "sms").put("id", System.currentTimeMillis())
                .put("from", from).put("text", texts.joinToString("") { it.messageBody ?: "" })
                .put("ts", texts.first().timestampMillis)
            contactName(context, from)?.let { o.put("name", it) }
            m.sendJson(o)
        }
    }
}

/** Call state → Mac (ringing shows a notification there, idle removes it). */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Store(context).smsCalls) return
        val m = Bridge.current ?: return
        if (!m.isConnected) return
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
            TelephonyManager.EXTRA_STATE_IDLE -> "idle"
            else -> return
        }
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val o = JSONObject().put("t", "call").put("state", state)
        if (!number.isNullOrEmpty()) {
            o.put("number", number)
            contactName(context, number)?.let { o.put("name", it) }
        }
        m.sendJson(o)
    }
}
