package app.bridge

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Turns the phone into a trackpad, keyboard and media remote for the Mac. */
class TouchpadActivity : Activity() {
    private lateinit var d: Design
    private lateinit var pad: PadView
    private lateinit var keys: EditText

    private fun send(o: JSONObject) { Bridge.current?.sendJson(o) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = Design(this)
        window.statusBarColor = d.p.bg
        window.navigationBarColor = d.p.bg
        if (!d.p.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(d.p.bg)
            setPadding(d.dp(16), d.dp(8), d.dp(16), d.dp(16))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                insets.systemWindowInsetLeft + d.dp(16), insets.systemWindowInsetTop + d.dp(8),
                insets.systemWindowInsetRight + d.dp(16), insets.systemWindowInsetBottom + d.dp(16),
            )
            insets
        }
        root.addView(d.text("Трекпад", 34f, d.p.label, bold = true).apply { setPadding(d.dp(4), d.dp(14), 0, d.dp(10)) })

        pad = PadView(this)
        root.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Media row
        val media = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun mbtn(label: String, action: String) {
            val b = d.bigButton(label, null, false) { send(JSONObject().put("t", "mac_media").put("action", action)) }
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d.dp(8) }
            media.addView(b)
        }
        mbtn("⏮", "previous"); mbtn("⏯", "play_pause"); mbtn("⏭", "next"); mbtn("🔈", "volume_down"); mbtn("🔊", "volume_up")
        root.addView(media, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = d.dp(12) })

        // Keyboard: a hidden field that forwards typed characters.
        keys = EditText(this).apply {
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(1, 1)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val t = s?.toString().orEmpty()
                    if (t.isNotEmpty()) {
                        send(JSONObject().put("t", "mac_text").put("text", t))
                        s?.clear()
                    }
                }
            })
            setOnKeyListener { _, code, ev ->
                if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val k = when (code) {
                    KeyEvent.KEYCODE_DEL -> "backspace"
                    KeyEvent.KEYCODE_ENTER -> "enter"
                    KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                    KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                    KeyEvent.KEYCODE_DPAD_UP -> "up"
                    KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                    else -> null
                }
                if (k != null) send(JSONObject().put("t", "mac_key").put("key", k))
                k != null
            }
        }
        root.addView(keys)
        val kb = d.bigButton("Клавиатура", null, true) {
            keys.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(keys, InputMethodManager.SHOW_FORCED)
        }
        root.addView(kb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = d.dp(8) })
        setContentView(root)
    }

    /** One finger moves the pointer, tap = click, two fingers scroll, two-finger tap = right click. */
    private inner class PadView(ctx: Context) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var lastX = 0f; private var lastY = 0f
        private var downAt = 0L; private var moved = 0f; private var maxPointers = 0
        private var lastTapAt = 0L
        private var remX = 0f; private var remY = 0f

        init { background = d.rounded(d.p.card, 18f) }

        override fun onDraw(c: Canvas) {
            paint.color = d.p.secondary
            paint.textSize = d.dpf(15f)
            c.drawText("Водите пальцем, как по трекпаду", width / 2f, height / 2f - d.dpf(8f), paint)
            c.drawText("Касание — клик, два пальца — прокрутка", width / 2f, height / 2f + d.dpf(14f), paint)
        }

        private fun cx(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getX(i); return s / e.pointerCount }
        private fun cy(e: MotionEvent): Float { var s = 0f; for (i in 0 until e.pointerCount) s += e.getY(i); return s / e.pointerCount }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = e.eventTime; moved = 0f; maxPointers = 1
                    lastX = e.x; lastY = e.y; remX = 0f; remY = 0f
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxPointers = maxOf(maxPointers, e.pointerCount)
                    lastX = cx(e); lastY = cy(e)
                }
                MotionEvent.ACTION_POINTER_UP -> { lastX = cx(e); lastY = cy(e) }
                MotionEvent.ACTION_MOVE -> {
                    val x = cx(e); val y = cy(e)
                    val dx = x - lastX; val dy = y - lastY
                    lastX = x; lastY = y
                    moved += abs(dx) + abs(dy)
                    if (e.pointerCount >= 2) {
                        send(JSONObject().put("t", "scroll").put("dx", (dx * 1.5f).roundToInt()).put("dy", (dy * 1.5f).roundToInt()))
                    } else {
                        // gentle acceleration for fast swipes
                        val speed = abs(dx) + abs(dy)
                        val gain = if (speed > 24f) 2.6f else if (speed > 10f) 2.0f else 1.5f
                        remX += dx * gain; remY += dy * gain
                        val ix = remX.toInt(); val iy = remY.toInt()
                        if (ix != 0 || iy != 0) {
                            remX -= ix; remY -= iy
                            send(JSONObject().put("t", "pointer").put("dx", ix).put("dy", iy))
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val quick = e.eventTime - downAt < 260 && moved < d.dpf(14f)
                    if (quick) {
                        val right = maxPointers >= 2
                        val dbl = !right && e.eventTime - lastTapAt < 320
                        lastTapAt = if (dbl) 0 else e.eventTime
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        send(JSONObject().put("t", "click").put("button", if (right) "right" else "left").put("count", if (dbl) 2 else 1))
                    }
                }
            }
            return true
        }
    }
}
