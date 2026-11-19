package app.bridge

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject

/** Calendar events and the next alarm of the phone, for the Mac menu (like Calendar / Clock sharing in the Apple ecosystem). */
object PhoneInfo {
    fun hasCalendar(ctx: Context) = Perms.has(ctx, Manifest.permission.READ_CALENDAR)

    /** Events from now until the end of tomorrow, soonest first (at most 12). */
    fun calendar(ctx: Context): JSONArray {
        val out = JSONArray()
        if (!hasCalendar(ctx)) return out
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 2)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val end = cal.timeInMillis
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(now.toString()).appendPath(end.toString()).build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        runCatching {
            ctx.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext() && out.length() < 12) {
                    out.put(
                        JSONObject().put("title", c.getString(0).orEmpty()).put("start", c.getLong(1)).put("end", c.getLong(2))
                            .put("allDay", c.getInt(3) == 1).put("place", c.getString(4).orEmpty()),
                    )
                }
            }
        }
        return out
    }

    /** Epoch millis of the next alarm, or 0. */
    fun nextAlarm(ctx: Context): Long =
        runCatching { ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime ?: 0L }.getOrDefault(0L)

    fun report(ctx: Context, manager: ConnectionManager) {
        manager.sendJson(
            JSONObject().put("t", "phone_agenda").put("events", calendar(ctx)).put("alarm", nextAlarm(ctx))
                .put("calendar", hasCalendar(ctx)),
        )
    }
}
