package app.bridge

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.sin

/** Colours of the "grouped" iOS look, light and dark. */
class Palette(val dark: Boolean) {
    val bg = if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    val card = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    val label = if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val secondary = if (dark) 0xFF8E8E93.toInt() else 0xFF6C6C70.toInt()
    val separator = if (dark) 0xFF38383A.toInt() else 0xFFD1D1D6.toInt()
    val fill = if (dark) 0xFF3A3A3C.toInt() else 0xFFE9E9EA.toInt()
    val accent = if (dark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    val green = if (dark) 0xFF30D158.toInt() else 0xFF34C759.toInt()
    val orange = if (dark) 0xFFFF9F0A.toInt() else 0xFFFF9500.toInt()
    val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()

    companion object {
        fun of(ctx: Context) =
            Palette(ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }
}

/** Small view factory with Apple-like proportions (continuous corners, SF-ish type scale, spring presses). */
class Design(val ctx: Context) {
    val p = Palette.of(ctx)
    val density = ctx.resources.displayMetrics.density
    /** HIG: motion must be optional. Android's "Remove animations" sets the animator scale to 0. */
    val reduceMotion: Boolean = try {
        android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) { false }
    fun dp(v: Int) = (v * density).toInt()
    fun dpf(v: Float) = v * density

    fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radiusDp)
    }

    fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false, medium: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = sizeSp; setTextColor(color)
        typeface = when {
            bold -> Typeface.create("sans-serif-medium", Typeface.BOLD)
            medium -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif", Typeface.NORMAL)
        }
        includeFontPadding = false
    }

    /** Spring-like press feedback: shrink on touch, overshoot back on release. */
    fun pressable(v: View, onClick: () -> Unit) {
        v.isClickable = true
        v.setOnTouchListener { view, e ->
            if (reduceMotion) {
                view.alpha = if (e.actionMasked == MotionEvent.ACTION_DOWN) 0.7f else 1f
                return@setOnTouchListener false
            }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.85f).setDuration(90)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380)
                        .setInterpolator(OvershootInterpolator(3.2f)).start()
            }
            false
        }
        v.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        }
    }

    fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(p.card, 14f)
        clipToOutline = true
        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun sectionHeader(s: String) = text(s.uppercase(), 13f, p.secondary).apply {
        setPadding(dp(32), dp(24), dp(20), dp(7))
    }

    fun footer(s: String) = text(s, 13f, p.secondary).apply {
        setLineSpacing(0f, 1.15f)
        setPadding(dp(32), dp(8), dp(32), 0)
    }

    fun iconTile(res: Int, tint: Int, sizeDp: Int = 30): View = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        val pad = dp(sizeDp / 5 + 1)
        setPadding(pad, pad, pad, pad)
        background = rounded(tint, sizeDp * 0.23f)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun iosSwitch(): Switch = Switch(ctx).apply {
        showText = false
        val w = dp(51); val h = dp(31)
        val on = GradientDrawable().apply { setColor(p.green); cornerRadius = h / 2f; setSize(w, h) }
        val off = GradientDrawable().apply { setColor(p.fill); cornerRadius = h / 2f; setSize(w, h) }
        trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), on)
            addState(intArrayOf(), off)
        }
        val knob = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(27), dp(27)) }
        thumbDrawable = InsetDrawable(knob, dp(2))
        splitTrack = false
    }

    /** Settings row: coloured icon, title, optional subtitle, trailing view. Returns the row. */
    fun row(icon: Int, tint: Int, title: String, subtitle: String?, trailing: View?, onClick: (() -> Unit)? = null): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minimumHeight = dp(56)
        }
        r.addView(iconTile(icon, tint))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14); marginEnd = dp(10)
            }
        }
        col.addView(text(title, 17f, p.label))
        if (subtitle != null) col.addView(text(subtitle, 15f, p.secondary).apply { setPadding(0, dp(2), 0, 0) })
        r.addView(col)
        if (trailing != null) r.addView(trailing)
        if (onClick != null) {
            r.isClickable = true
            r.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.setBackgroundColor(p.fill)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.background = null
                }
                false
            }
            r.setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onClick() }
        }
        return r
    }

    fun chevron(): View = text("›", 24f, p.separator).apply { setPadding(0, 0, dp(2), dp(2)) }

    fun separator(): View = View(ctx).apply {
        setBackgroundColor(p.separator)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(60) }
    }

    /** Fills a card with rows, adding inset hairlines between them. */
    fun group(vararg rows: View): LinearLayout = card().apply {
        rows.forEachIndexed { i, r -> if (i > 0) addView(separator()); addView(r) }
    }

    /** Big rounded button. filled = accent background, else tinted. */
    fun bigButton(label: String, icon: Int?, filled: Boolean, onClick: () -> Unit): LinearLayout {
        val b = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(if (filled) p.accent else p.card, 14f)
            setPadding(dp(16), dp(15), dp(16), dp(15))
        }
        if (icon != null) b.addView(ImageView(ctx).apply {
            setImageResource(icon); setColorFilter(if (filled) Color.WHITE else p.accent)
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }
        })
        b.addView(text(label, 17f, if (filled) Color.WHITE else p.accent, medium = true))
        pressable(b, onClick)
        return b
    }

    /** Staggered "rise and fade in" used when a screen appears. */
    fun enter(views: List<View>) {
        if (reduceMotion) return
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dpf(22f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 55L).setDuration(520)
                .setInterpolator(DecelerateInterpolator(2.2f)).start()
        }
    }

    /** Cross-fades a label when its text changes. */
    fun setTextAnimated(tv: TextView, s: CharSequence) {
        if (tv.text.toString() == s.toString()) return
        if (tv.text.isNullOrEmpty() || reduceMotion) { tv.text = s; return }
        tv.animate().alpha(0f).setDuration(110).withEndAction {
            tv.text = s
            tv.animate().alpha(1f).setDuration(180).start()
        }.start()
    }
}

/** Phone ⇄ Mac illustration: pulsing ring and travelling dashes while connected. */
class LinkView(ctx: Context, private val d: Design) : View(ctx) {
    enum class Mode { OFF, BUSY, ON }

    private val phone = ctx.getDrawable(R.drawable.ic_phone)!!.mutate()
    private val laptop = ctx.getDrawable(R.drawable.ic_laptop)!!.mutate()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d.dpf(2f) }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = d.dpf(3f); strokeCap = Paint.Cap.ROUND
    }
    private var tone = d.p.secondary
    private var mode = Mode.OFF
    private var t = 0f

    private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        val target = when (m) { Mode.ON -> d.p.green; Mode.BUSY -> d.p.orange; Mode.OFF -> d.p.secondary }
        ValueAnimator.ofObject(ArgbEvaluator(), tone, target).apply {
            duration = 450
            addUpdateListener { tone = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!d.reduceMotion) clock.start() }
    override fun onDetachedFromWindow() { clock.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cy = h / 2f
        val r = d.dpf(34f)
        val lx = w * 0.22f; val rx = w * 0.78f

        // connection line
        line.color = tone
        line.alpha = if (mode == Mode.OFF) 90 else 255
        val dash = d.dpf(9f); val gap = d.dpf(8f)
        val phase = if (mode == Mode.OFF) 0f else -(t * (dash + gap) * 2f)
        line.pathEffect = if (mode == Mode.ON || mode == Mode.BUSY) DashPathEffect(floatArrayOf(dash, gap), phase)
        else DashPathEffect(floatArrayOf(dash, gap * 2f), 0f)
        c.drawLine(lx + r + d.dpf(10f), cy, rx - r - d.dpf(10f), cy, line)

        for ((cx, icon) in listOf(lx to phone, rx to laptop)) {
            // soft pulse
            if (mode != Mode.OFF) {
                val k = (t + if (cx == lx) 0f else 0.5f) % 1f
                ring.color = tone
                ring.alpha = ((1f - k) * 110).toInt()
                c.drawCircle(cx, cy, r + k * d.dpf(18f), ring)
            }
            disc.color = d.p.card
            c.drawCircle(cx, cy, r, disc)
            ring.color = tone; ring.alpha = 255
            c.drawCircle(cx, cy, r, ring)
            icon.setTint(tone)
            val s = d.dp(30)
            icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            icon.draw(c)
        }
        // status dot "breathing" between the devices
        if (mode == Mode.ON) {
            val k = 0.5f + 0.5f * sin(t * 2f * Math.PI.toFloat())
            disc.color = tone; disc.alpha = (160 + 95 * k).toInt()
            c.drawCircle(w / 2f, cy, d.dpf(5f) + k * d.dpf(1.5f), disc)
            disc.alpha = 255
        }
    }
}
