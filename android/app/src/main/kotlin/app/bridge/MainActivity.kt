package app.bridge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class MainActivity : Activity(), ConnectionManager.Listener {
    private lateinit var manager: ConnectionManager
    private lateinit var store: Store
    private lateinit var d: Design
    private lateinit var link: LinkView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var pairBtn: View
    private lateinit var forgetCard: View
    private lateinit var wakeBox: LinearLayout
    private lateinit var quickRow: View
    private lateinit var macRow: View
    private lateinit var padRow: View
    private lateinit var powerRow: View
    private lateinit var whereRow: View
    private lateinit var shotRow: View
    private var findingMac = false
    private lateinit var sendFileBtn: View
    private lateinit var sendClipBtn: View

    private val reqPickFiles = 10
    private val reqNotifPerm = 11
    private val reqPhotoPerm = 12
    private val reqSmsPerm = 13
    private lateinit var photoSwitch: Switch
    private lateinit var smsSwitch: Switch
    private lateinit var filesSwitch: Switch
    private lateinit var dndSwitch: Switch
    private var dndSub: TextView? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** A rounded "quick action" tile (icon above a label). */
    private fun tile(icon: Int, tint: Int, label: String, onClick: () -> Unit): LinearLayout {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            background = d.rounded(d.p.card, 14f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        t.addView(d.iconTile(icon, tint, 34))
        t.addView(d.text(label, 15f, d.p.label, medium = true).apply { setPadding(0, dp(14), 0, 0) })
        d.pressable(t, onClick)
        return t
    }

    /** Opens a link (prefilled from the clipboard when it holds one) in the browser on the Mac. */
    private fun sendLinkToMac() {
        val clip = try {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        } catch (_: Exception) { "" }
        val input = EditText(this).apply {
            hint = "https://…"
            setSingleLine()
            if (clip.startsWith("http://") || clip.startsWith("https://")) setText(clip)
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Открыть на Mac")
            .setView(box)
            .setPositiveButton("Открыть") { _, _ ->
                var u = input.text.toString().trim()
                if (u.isNotEmpty()) {
                    if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                    manager.sendJson(org.json.JSONObject().put("t", "open_url").put("url", u))
                    Toast.makeText(this, "Отправлено на Mac", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        window.statusBarColor = d.p.bg
        window.navigationBarColor = d.p.bg
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val m = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (d.p.dark) 0 else m, m)
        } else if (!d.p.dark) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        manager = Bridge.get(this)
        d = Design(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
            layoutTransition = android.animation.LayoutTransition().apply {
                enableTransitionType(android.animation.LayoutTransition.CHANGING)
            }
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(d.p.bg)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            addView(col)
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
            insets
        }
        setContentView(scroll)
        styleSystemBars()
        val animated = mutableListOf<View>()
        fun add(v: View, top: Int = 0): View {
            v.layoutParams = (v.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                .apply { topMargin = dp(top) }
            col.addView(v)
            animated.add(v)
            return v
        }

        // Large title
        add(d.text("Bridge", 34f, d.p.label, bold = true).apply { setPadding(dp(4), dp(14), 0, dp(10)) })

        // Hero: link illustration + state
        val hero = d.card().apply { setPadding(dp(16), dp(18), dp(16), dp(20)) }
        link = LinkView(this, d)
        hero.addView(link, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        status = d.text("", 22f, d.p.label, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) }
        detail = d.text("", 14f, d.p.secondary).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), 0) }
        hero.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hero.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        add(hero)

        // Mac asleep / unreachable: wake it or search harder
        wakeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        wakeBox.addView(d.bigButton("Разбудить Mac", R.drawable.ic_laptop, true) { manager.wakeMac() })
        wakeBox.addView(d.bigButton("Искать везде (другие подсети)", R.drawable.ic_laptop, false) {
            Toast.makeText(this, "Ищу Mac в соседних подсетях…", Toast.LENGTH_SHORT).show()
            manager.wakeNow(deep = true)
        })
        add(wakeBox, 12)

        // Pair button (unpaired state)
        pairBtn = add(d.bigButton("Сопрячь с Mac", R.drawable.ic_laptop, true) { choosePairTarget() }, 12)

        // Quick actions (paired state)
        quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sendFileBtn = tile(R.drawable.ic_upload, d.p.accent, "Отправить файлы") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                reqPickFiles,
            )
        }
        sendClipBtn = tile(R.drawable.ic_clip, d.p.orange, "Отправить буфер") {
            startActivity(Intent(this, SendClipboardActivity::class.java))
        }
        (quickRow as LinearLayout).addView(sendFileBtn)
        (quickRow as LinearLayout).addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        (quickRow as LinearLayout).addView(sendClipBtn)
        add(quickRow, 12)

        macRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val findMacTile = tile(R.drawable.ic_bell, d.p.red, "Найти Mac") {
            findingMac = !findingMac
            manager.sendJson(org.json.JSONObject().put("t", "find_mac").put("on", findingMac))
            Toast.makeText(this, if (findingMac) "Mac подаёт сигнал. Нажмите ещё раз, чтобы выключить" else "Сигнал на Mac выключен",
                Toast.LENGTH_SHORT).show()
        }
        val lockMacTile = tile(R.drawable.ic_laptop, 0xFF8E8E93.toInt(), "Заблокировать Mac") {
            manager.sendJson(org.json.JSONObject().put("t", "lock_mac"))
        }
        (macRow as LinearLayout).addView(findMacTile)
        (macRow as LinearLayout).addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        (macRow as LinearLayout).addView(lockMacTile)
        add(macRow, 12)

        powerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (powerRow as LinearLayout).addView(tile(R.drawable.ic_moon, 0xFF5856D6.toInt(), "Усыпить Mac") {
            AlertDialog.Builder(this)
                .setTitle("Усыпить Mac?")
                .setMessage("Соединение прервётся, пока Mac спит. Чтобы разбудить, откройте Bridge: телефон отправит сигнал пробуждения по сети.")
                .setPositiveButton("Усыпить") { _, _ -> manager.sendJson(org.json.JSONObject().put("t", "mac_power").put("action", "sleep")) }
                .setNegativeButton("Отмена", null)
                .show()
        })
        (powerRow as LinearLayout).addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        (powerRow as LinearLayout).addView(tile(R.drawable.ic_bell, 0xFF8E8E93.toInt(), "Звук Mac вкл/выкл") {
            manager.sendJson(org.json.JSONObject().put("t", "mac_volume").put("action", "mute_toggle"))
        })
        add(powerRow, 12)

        whereRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (whereRow as LinearLayout).addView(tile(R.drawable.ic_laptop, d.p.red, "Где Mac") {
            manager.sendJson(org.json.JSONObject().put("t", "locate_mac"))
            Toast.makeText(this, "Запрашиваю положение Mac…", Toast.LENGTH_SHORT).show()
        })
        (whereRow as LinearLayout).addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        (whereRow as LinearLayout).addView(tile(R.drawable.ic_upload, d.p.accent, "Ссылка на Mac") { sendLinkToMac() })
        add(whereRow, 12)

        shotRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (shotRow as LinearLayout).addView(tile(R.drawable.ic_photo, d.p.orange, "Скриншот Mac") {
            manager.sendJson(org.json.JSONObject().put("t", "mac_screenshot"))
            Toast.makeText(this, "Снимок придёт как файл", Toast.LENGTH_SHORT).show()
        })
        (shotRow as LinearLayout).addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        (shotRow as LinearLayout).addView(tile(R.drawable.ic_bell, d.p.accent, "Громкость Mac") {
            val acts = arrayOf("up", "down", "mute_toggle")
            AlertDialog.Builder(this)
                .setTitle("Громкость Mac")
                .setItems(arrayOf("Громче", "Тише", "Вкл/выкл звук")) { _, i ->
                    manager.sendJson(org.json.JSONObject().put("t", "mac_volume").put("action", acts[i]))
                }
                .show()
        })
        add(shotRow, 12)

        padRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (padRow as LinearLayout).addView(tile(R.drawable.ic_phone, 0xFF5856D6.toInt(), "Трекпад и пульт") {
            startActivity(Intent(this, TouchpadActivity::class.java))
        })
        (padRow as LinearLayout).addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        (padRow as LinearLayout).addView(tile(R.drawable.ic_bell, d.p.orange, "Приложения") {
            startActivity(Intent(this, AppFilterActivity::class.java))
        })
        add(padRow, 12)

        // Sync
        add(d.sectionHeader("Синхронизация"))
        val notif = d.iosSwitch().apply {
            isChecked = store.forwardNotifications
            setOnCheckedChangeListener { _, v -> store.forwardNotifications = v }
        }
        val clip = d.iosSwitch().apply {
            isChecked = store.applyClipboard
            setOnCheckedChangeListener { _, v -> store.applyClipboard = v }
        }
        photoSwitch = d.iosSwitch().apply {
            isChecked = store.syncPhotos && Perms.hasAll(this@MainActivity, Perms.photos)
            setOnCheckedChangeListener { _, v ->
                if (v && !Perms.hasAll(this@MainActivity, Perms.photos)) {
                    requestPermissions(Perms.photos, reqPhotoPerm)
                } else {
                    setPhotoSync(v)
                }
            }
        }
        smsSwitch = d.iosSwitch().apply {
            isChecked = store.smsCalls && Perms.hasAll(this@MainActivity, Perms.smsCalls)
            setOnCheckedChangeListener { _, v ->
                if (v && !Perms.hasAll(this@MainActivity, Perms.smsCalls)) {
                    requestPermissions(Perms.smsCalls, reqSmsPerm)
                } else {
                    store.smsCalls = v
                }
            }
        }
        filesSwitch = d.iosSwitch().apply {
            isChecked = store.fileAccess && hasAllFiles()
            setOnCheckedChangeListener { _, v ->
                if (v && !hasAllFiles()) {
                    // Needs the special "All files access" permission; the user grants it on a system screen.
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Доступ ко всем файлам")
                        .setMessage("Mac сможет просматривать и скачивать файлы из памяти телефона (только чтение). " +
                            "Включите «Разрешить доступ к управлению всеми файлами» для Bridge на следующем экране.")
                        .setPositiveButton("Открыть настройки") { _, _ ->
                            store.fileAccess = true
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(Uri.parse("package:$packageName")),
                            )
                        }
                        .setNegativeButton("Отмена") { _, _ -> isChecked = false }
                        .setOnCancelListener { isChecked = false }
                        .show()
                } else {
                    store.fileAccess = v
                }
            }
        }
        dndSwitch = d.iosSwitch().apply {
            isChecked = dndAllowed()
            setOnCheckedChangeListener { _, _ ->
                if (isChecked != dndAllowed()) startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            }
        }
        fun swRow(icon: Int, tint: Int, title: String, sub: String, sw: Switch) =
            d.row(icon, tint, title, sub, sw) { sw.toggle() }
        add(d.group(
            swRow(R.drawable.ic_bell, d.p.red, "Уведомления", "Показывать на Mac, отвечать прямо из них", notif),
            swRow(R.drawable.ic_clip, d.p.accent, "Буфер обмена", "Принимать текст и картинки с Mac", clip),
            swRow(R.drawable.ic_photo, d.p.orange, "Фото и скриншоты", "Новые снимки сразу попадают на Mac", photoSwitch),
        ))

        add(d.sectionHeader("Звонки и сообщения"))
        add(d.group(
            swRow(R.drawable.ic_chat, d.p.green, "SMS и звонки", "Читать, отвечать, звонить и принимать с Mac", smsSwitch),
            swRow(R.drawable.ic_moon, 0xFF5856D6.toInt(), "Не беспокоить", "Синхронизируется с Mac в обе стороны", dndSwitch)
                .also { dndSub = ((it.getChildAt(1) as LinearLayout).getChildAt(1)) as TextView },
        ))

        add(d.sectionHeader("Файлы"))
        add(d.group(
            swRow(R.drawable.ic_folder, 0xFF30B0C7.toInt(), "Память телефона", "Просмотр и скачивание с Mac", filesSwitch),
        ))

        add(d.sectionHeader("Разрешения Android"))
        add(d.group(
            d.row(R.drawable.ic_bell, 0xFF8E8E93.toInt(), "Доступ к уведомлениям", "Нужен для пересылки на Mac", d.chevron()) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            d.row(R.drawable.ic_phone, 0xFF8E8E93.toInt(), "Управление с Mac", "Служба «Bridge» в спец. возможностях: нажатия и ввод при зеркале экрана", d.chevron()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            d.row(R.drawable.ic_bolt, 0xFF8E8E93.toInt(), "Работа в фоне", "Не даёт системе усыплять Bridge", d.chevron()) {
                requestIgnoreBattery()
            },
        ))

        forgetCard = add(d.card().apply {
            addView(d.row(R.drawable.ic_laptop, d.p.red, "Забыть этот Mac", null, null) {
                AlertDialog.Builder(this@MainActivity).setMessage("Забыть сопряжение с ${store.macName}?")
                    .setPositiveButton("Забыть") { _, _ -> manager.forget(); refresh() }
                    .setNegativeButton("Отмена", null).show()
            })
        }, 24)

        add(d.footer(
            "Android не даёт читать буфер обмена в фоне, поэтому из телефона текст отправляется кнопкой «Отправить буфер» " +
                "или из уведомления. Обратное направление работает автоматически.",
        ))

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), reqNotifPerm)
        }

        store.enabled = true
        Bridge.startService(this)
        manager.start()
        d.enter(animated)
    }

    private fun hasAllFiles(): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    override fun onResume() {
        super.onResume()
        if (::dndSwitch.isInitialized) {
            val on = dndAllowed()
            if (dndSwitch.isChecked != on) dndSwitch.isChecked = on
            dndSub?.text = if (on) "Синхронизируется с Mac в обе стороны"
            else "Нужен доступ к режимам — нажмите, чтобы разрешить"
        }
        if (::filesSwitch.isInitialized) {
            // The user may have just come back from the system permission screen.
            val on = store.fileAccess && hasAllFiles()
            if (!on && store.fileAccess) store.fileAccess = false
            if (filesSwitch.isChecked != on) filesSwitch.isChecked = on
        }
        Bridge.onMacStatus = { runOnUiThread { refresh() } }
        if (store.isPaired && !locationAsked && (!Locator.has(this) || !PhoneInfo.hasCalendar(this))) {
            // "Where is my phone" on the Mac needs the location permission; asked once.
            locationAsked = true
            requestPermissions(Locator.allPermissions, reqLocPerm)
        }
        if (store.isPaired && !bleAsked && !BleLink.hasPermission(this)) {
            bleAsked = true
            requestPermissions(BleLink.permissions, reqBlePerm)
        } else {
            manager.ble.start()
        }
        manager.addListener(this)
        manager.acquireDiscovery()
        if (store.isPaired && !manager.isConnected) manager.wakeNow()
        store.enabled = true
        refresh()
    }

    override fun onPause() {
        Bridge.onMacStatus = null
        manager.removeListener(this)
        manager.releaseDiscovery()
        super.onPause()
    }

    override fun onStateChanged() = refresh()

    private fun refresh() {
        d.setTextAnimated(status, when (manager.state) {
            ConnectionManager.State.CONNECTED -> "Подключено"
            else -> Ui.statusLine(manager)
        })
        val paired = store.isPaired
        d.setTextAnimated(detail, when {
            !paired -> "Откройте на Mac меню Bridge → «Сопрячь телефон…», затем нажмите кнопку ниже."
            manager.activityText != null -> (manager.activityText ?: "")
            manager.state == ConnectionManager.State.CONNECTED && Bridge.macBattery >= 0 ->
                "${store.macName} · ${Bridge.macBattery}%" + if (Bridge.macCharging) " ⚡" else ""
            manager.state == ConnectionManager.State.CONNECTED -> store.macName
            manager.state == ConnectionManager.State.ASLEEP ->
                (if (manager.macAsleep) "Mac ушёл в сон" else "Mac не отвечает") +
                    ". Не ищу его постоянно: подключусь сам, когда он проснётся." +
                    if (manager.searchNote.isNotEmpty()) "\n${manager.searchNote}" else ""
            manager.state == ConnectionManager.State.SEARCHING && manager.searchNote.isNotEmpty() ->
                "Mac: ${store.macName}\n${manager.searchNote}"
            else -> "Mac: ${store.macName}"
        })
        link.setMode(when (manager.state) {
            ConnectionManager.State.CONNECTED -> LinkView.Mode.ON
            ConnectionManager.State.SEARCHING, ConnectionManager.State.CONNECTING,
            ConnectionManager.State.PAIRING -> LinkView.Mode.BUSY
            else -> LinkView.Mode.OFF
        })
        wakeBox.visibility = if (paired && manager.state == ConnectionManager.State.ASLEEP) View.VISIBLE else View.GONE
        pairBtn.visibility = if (paired) View.GONE else View.VISIBLE
        quickRow.visibility = if (paired) View.VISIBLE else View.GONE
        macRow.visibility = if (paired) View.VISIBLE else View.GONE
        padRow.visibility = if (paired) View.VISIBLE else View.GONE
        powerRow.visibility = if (paired) View.VISIBLE else View.GONE
        whereRow.visibility = if (paired) View.VISIBLE else View.GONE
        shotRow.visibility = if (paired) View.VISIBLE else View.GONE
        forgetCard.visibility = if (paired) View.VISIBLE else View.GONE
        for (v in listOf(sendFileBtn, sendClipBtn, macRow)) {
            v.isEnabled = manager.isConnected
            v.animate().alpha(if (manager.isConnected) 1f else 0.4f).setDuration(200).start()
        }
    }

    // ---- pairing UI ---------------------------------------------------------------------------------------

    /** Looks for the Mac by mDNS and by sweeping the local network; pairs automatically when there is exactly one. */
    private fun choosePairTarget() {
        val wait = AlertDialog.Builder(this)
            .setTitle("Ищу Mac…")
            .setMessage("Проверяю сеть Wi‑Fi (${LanScan.describe(this)})")
            .setNegativeButton("Отмена", null)
            .show()
        thread(isDaemon = true) {
            val scanned = runCatching { LanScan.scan(applicationContext) }.getOrDefault(emptyList())
            runOnUiThread {
                if (!wait.isShowing) return@runOnUiThread   // cancelled
                wait.dismiss()
                val items = LinkedHashMap<String, InetSocketAddress>()
                val seen = HashSet<String>()
                for (f in manager.discovered()) {
                    val ip = f.address.hostAddress?.substringBefore('%') ?: continue
                    if (!seen.add(ip)) continue   // the same Mac advertised twice ("MacBook Air" / "MacBook Air (2)")
                    items["${f.serviceName}  ($ip)"] = InetSocketAddress(f.address, f.port)
                }
                for (a in scanned) {
                    val ip = a.address?.hostAddress ?: continue
                    if (seen.add(ip)) items["Mac  ($ip)"] = a
                }
                when (items.size) {
                    0 -> notFoundDialog()
                    1 -> startPair(items.values.first())
                    else -> {
                        val labels = items.keys.toList()
                        AlertDialog.Builder(this)
                            .setTitle("Выберите Mac")
                            .setItems((labels + "Ввести адрес вручную…").toTypedArray()) { _, which ->
                                if (which < labels.size) startPair(items.getValue(labels[which])) else askManual()
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun notFoundDialog() {
        AlertDialog.Builder(this)
            .setTitle("Mac не найден")
            .setMessage(
                "Телефон в сети: ${LanScan.describe(this)}.\n\n" +
                    "Проверьте, что Bridge запущен на Mac и в его меню открыто «Сопрячь телефон…». " +
                    "Mac и телефон должны быть за одним роутером: не гостевая сеть и без «изоляции клиентов». " +
                    "Адрес Mac показан в меню Bridge — его можно ввести вручную."
            )
            .setPositiveButton("Повторить") { _, _ -> choosePairTarget() }
            .setNeutralButton("По Bluetooth") { _, _ -> pairBluetooth() }
            .setNegativeButton("Ввести адрес") { _, _ -> askManual() }
            .show()
    }

    private val reqBlePerm = 77
    private var bleAsked = false
    private var locationAsked = false
    private val reqLocPerm = 78
    private var bleThenPair = false

    /** Pairing without any shared network: the Mac finds the phone over Bluetooth LE. */
    private fun pairBluetooth() {
        if (!BleLink.hasPermission(this)) {
            bleThenPair = true
            requestPermissions(BleLink.permissions, reqBlePerm)
            return
        }
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Toast.makeText(this, "На телефоне нет Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            // The BLE link restarts by itself once Bluetooth is on.
            runCatching { startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        }
        AlertDialog.Builder(this)
            .setTitle("Сопряжение по Bluetooth")
            .setMessage("На Mac откройте меню Bridge → «Сопрячь телефон…» и держите телефон рядом. Bluetooth должен быть включён на обоих устройствах.")
            .setPositiveButton("Ок", null)
            .show()
        manager.pairViaBle(pairingUi())
    }

    private fun askManual() {
        val input = EditText(this).apply {
            hint = "192.168.1.5"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Адрес Mac (показан в меню Bridge)")
            .setView(input)
            .setPositiveButton("Сопрячь") { _, _ ->
                val s = input.text.toString().trim()
                val hasPort = s.count { it == ':' } == 1
                val host = if (hasPort) s.substringBefore(':') else s
                val port = if (hasPort) s.substringAfter(':').toIntOrNull() else app.bridge.core.Wire.DEFAULT_PORT
                if (host.isEmpty() || port == null || port !in 1..65535) {
                    Toast.makeText(this, "Нужен адрес вида 192.168.1.5 или 192.168.1.5:50123", Toast.LENGTH_LONG).show()
                } else {
                    // Name resolution must not run on the main thread.
                    thread(isDaemon = true) {
                        try {
                            val a = InetSocketAddress(host, port)
                            runOnUiThread { startPair(a) }
                        } catch (e: Exception) {
                            runOnUiThread { Toast.makeText(this, "Неверный адрес", Toast.LENGTH_LONG).show() }
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun startPair(address: InetSocketAddress) = manager.pair(address, pairingUi())

    private fun pairingUi(): PairingUi {
        return (object : PairingUi {
            override fun confirmSas(sas: String, macName: String, decide: (Boolean) -> Unit) {
                val shown = sas.chunked(3).joinToString(" ")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Сопряжение с $macName")
                    .setMessage("Код: $shown\n\nУбедитесь, что тот же код показан на Mac, и подтвердите на обоих устройствах.")
                    .setCancelable(false)
                    .setPositiveButton("Совпадает") { _, _ -> decide(true) }
                    .setNegativeButton("Не совпадает") { _, _ -> decide(false) }
                    .show()
            }

            override fun result(success: Boolean, message: String) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                refresh()
            }
        })
    }

    private fun setPhotoSync(on: Boolean) {
        if (on) {
            // Only photos taken from now on are sent; the existing library is left alone.
            thread(isDaemon = true) {
                store.photoLastId = Features(applicationContext, store, manager).currentMaxPhotoId()
                store.syncPhotos = true
                manager.refreshFeatures()
            }
        } else {
            store.syncPhotos = false
            manager.refreshFeatures()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            reqBlePerm -> {
                if (ok) {
                    manager.ble.start()
                    if (bleThenPair) pairBluetooth()
                }
                bleThenPair = false
            }
            reqLocPerm -> manager.runBackground { PhoneInfo.report(applicationContext, manager) }
            reqPhotoPerm -> if (ok) setPhotoSync(true) else photoSwitch.isChecked = false
            reqSmsPerm -> {
                // Calls can work even if the user declined some optional parts (e.g. call log).
                val core = Perms.has(this, Manifest.permission.RECEIVE_SMS)
                store.smsCalls = core
                if (!core) smsSwitch.isChecked = false
            }
        }
    }

    // ---- misc ---------------------------------------------------------------------------------------------

    private fun requestIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Уже разрешено", Toast.LENGTH_SHORT).show()
            return
        }
        // The list screen needs no special permission; pick Bridge → "Не оптимизировать".
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqPickFiles || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        val files = uris.mapNotNull { manager.openOutgoing(it) }
        if (files.isNotEmpty()) manager.sendFiles(files)
    }

    private fun dndAllowed(): Boolean =
        getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted
}
