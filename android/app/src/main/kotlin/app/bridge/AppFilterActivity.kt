package app.bridge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import kotlin.concurrent.thread

/** Choose which apps' notifications are forwarded to the Mac. */
class AppFilterActivity : Activity() {
    private lateinit var d: Design
    private lateinit var store: Store
    private lateinit var list: LinearLayout

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = Design(this)
        store = Store(this)
        window.statusBarColor = d.p.bg
        window.navigationBarColor = d.p.bg
        if (!d.p.dark) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d.dp(16), d.dp(8), d.dp(16), d.dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(d.p.bg)
            isVerticalScrollBarEnabled = false
            addView(col)
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        col.addView(d.text("Приложения", 34f, d.p.label, bold = true).apply { setPadding(d.dp(4), d.dp(14), 0, d.dp(6)) })
        col.addView(d.text("Выключенные приложения не присылают уведомления на Mac.", 15f, d.p.secondary)
            .apply { setPadding(d.dp(4), 0, d.dp(4), d.dp(12)) })
        list = d.card()
        col.addView(list)

        // Xiaomi/HyperOS hides the app list until this (custom) permission is granted.
        if (!Perms.has(this, "com.android.permission.GET_INSTALLED_APPS")) {
            runCatching { requestPermissions(arrayOf("com.android.permission.GET_INSTALLED_APPS"), 91) }
        }
        load()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 91) load()
    }

    private fun load() {
        thread(isDaemon = true) {
            val pm = packageManager
            val byPkg = LinkedHashMap<String, Triple<String, String, android.graphics.drawable.Drawable>>()
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName }
                .forEach { ri ->
                    byPkg.putIfAbsent(ri.activityInfo.packageName,
                        Triple(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), ri.loadIcon(pm)))
                }
            // Apps that already sent notifications are always listed.
            for (e in store.seenApps) {
                val parts = e.split("\t")
                val pkg = parts[0]
                val label = parts.getOrElse(1) { pkg }
                if (pkg == packageName || byPkg.containsKey(pkg)) continue
                val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: continue
                byPkg[pkg] = Triple(pkg, label, icon)
            }
            val rows = byPkg.values.sortedBy { it.second.lowercase() }
            runOnUiThread { list.removeAllViews(); fill(rows) }
        }
    }

    private fun fill(rows: List<Triple<String, String, android.graphics.drawable.Drawable>>) {
        val muted = store.mutedApps.toMutableSet()
        rows.forEachIndexed { i, (pkg, label, icon) ->
            if (i > 0) list.addView(d.separator())
            val sw: Switch = d.iosSwitch().apply {
                isChecked = pkg !in muted
                setOnCheckedChangeListener { _, on ->
                    if (on) muted.remove(pkg) else muted.add(pkg)
                    store.mutedApps = muted
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(d.dp(16), d.dp(8), d.dp(16), d.dp(8))
                minimumHeight = d.dp(56)
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(d.dp(34), d.dp(34))
            })
            row.addView(d.text(label, 17f, d.p.label).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = d.dp(14); marginEnd = d.dp(10)
                }
            })
            row.addView(sw)
            row.setOnClickListener { sw.toggle() }
            list.addView(row)
        }
    }
}
