package app.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Lets the Mac tap, swipe and type on this phone. Enabled by the user in Settings → Accessibility. */
class BridgeAccessibility : AccessibilityService() {
    companion object {
        @Volatile var instance: BridgeAccessibility? = null

        @Suppress("DEPRECATION")
        fun screenSize(ctx: Context): Pair<Int, Int> {
            val wm = ctx.getSystemService(WindowManager::class.java)
            return if (Build.VERSION.SDK_INT >= 30) {
                val b = wm.maximumWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val p = android.graphics.Point()
                wm.defaultDisplay.getRealSize(p)
                p.x to p.y
            }
        }
    }

    override fun onServiceConnected() { instance = this; Mirror.state(if (Mirror.active) "on" else "off") }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2) else lineTo(x1 + 0.1f, y1)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms.coerceIn(1, 3000)))
            .build()
        dispatchGesture(g, null, null)
    }

    /** Two-finger pinch around (cx, cy): zoomIn spreads the fingers, otherwise brings them together. */
    fun pinch(cx: Float, cy: Float, zoomIn: Boolean, base: Float) {
        val near = base * 0.06f
        val far = base * 0.22f
        val (from, to) = if (zoomIn) near to far else far to near
        fun stroke(sign: Float) = GestureDescription.StrokeDescription(
            Path().apply { moveTo(cx + sign * from, cy); lineTo(cx + sign * to, cy) }, 0, 350,
        )
        val g = GestureDescription.Builder().addStroke(stroke(-1f)).addStroke(stroke(1f)).build()
        dispatchGesture(g, null, null)
    }

    fun key(name: String) {
        when (name) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "lock" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            "backspace" -> edit { it.dropLast(1) }
            "enter" -> focused()?.let { if (Build.VERSION.SDK_INT >= 30) it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) }
        }
    }

    fun typeText(s: String) { if (s.isNotEmpty()) edit { it + s } }

    private fun focused(): AccessibilityNodeInfo? = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun edit(f: (String) -> String) {
        val n = focused() ?: return
        val cur = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, f(cur))
        }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
