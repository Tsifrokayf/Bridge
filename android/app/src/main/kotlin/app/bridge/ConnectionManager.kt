package app.bridge

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import app.bridge.core.FileNames
import app.bridge.core.FrameIO
import app.bridge.core.Handshake
import app.bridge.core.IncomingFile
import app.bridge.core.SecureChannel
import app.bridge.core.Session
import app.bridge.core.SessionListener
import app.bridge.core.Wire
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class Found(val serviceName: String, val address: InetAddress, val port: Int, val macId: String?)

class Outgoing(val name: String, val size: Long, val pfd: ParcelFileDescriptor)

interface PairingUi {
    /** Called on the main thread. [decide] may be called from any thread, exactly once. */
    fun confirmSas(sas: String, macName: String, decide: (Boolean) -> Unit)

    /** Called on the main thread. */
    fun result(success: Boolean, message: String)
}

/**
 * Owns discovery, the connection loop and the active [Session].
 * All socket I/O happens on background threads.
 */
class ConnectionManager(private val ctx: Context, private val store: Store) {
    val context: Context get() = ctx

    enum class State { UNPAIRED, PAIRING, SEARCHING, CONNECTING, CONNECTED, ASLEEP }

    interface Listener {
        /** Always delivered on the main thread. */
        fun onStateChanged()
    }

    private val tag = "Bridge"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "bridge-sender").also { it.isDaemon = true } }
    private val transfers = Executors.newCachedThreadPool { r -> Thread(r, "bridge-transfer").also { it.isDaemon = true } }
    private val signal = Object()
    private val features by lazy { Features(ctx, store, this) }

    fun dndChanged() = features.dndChanged()

    @Volatile var state: State = State.UNPAIRED
        private set

    @Volatile var peerName: String? = null
        private set

    /** Human-readable progress of the current file transfer, or null. */
    @Volatile var activityText: String? = null
        private set

    @Volatile private var session: Session? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var pairing = false

    val isConnected: Boolean get() = state == State.CONNECTED

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun fire() = main.post { listeners.forEach { it.onStateChanged() } }

    private fun setState(s: State) {
        if (state != s) {
            state = s
            fire()
        }
    }

    private fun setActivity(text: String?) {
        activityText = text
        fire()
    }

    private fun toast(msg: String) = main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }

    private fun poke() = synchronized(signal) { signal.notifyAll() }

    private fun waitSignal(ms: Long) = synchronized(signal) { signal.wait(ms) }

    // ---- lifecycle ------------------------------------------------------------------------------------

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    val ble: BleLink by lazy { BleLink(ctx, this, store) }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        ble.start()
        acquireDiscovery()
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lastScan = 0L
                needScan = true
                wakeRequested = true
                restartDiscovery()
                poke()
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb)
        val gen = ++generation
        thread(name = "bridge-conn", isDaemon = true) { loop(gen) }
        if (!store.isPaired) setState(State.UNPAIRED)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        networkCallback?.let { runCatching { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        session?.close("stopped")
        ble.stop()
        releaseDiscovery()
        poke()
    }

    fun forget() {
        session?.close("forgotten")
        store.forget()
        peerName = null
        setState(State.UNPAIRED)
        poke()
    }

    // ---- discovery (NSD / mDNS) ---------------------------------------------------------------------------

    private val nsd: NsdManager by lazy { ctx.getSystemService(NsdManager::class.java) }
    private val found = ConcurrentHashMap<String, Found>()
    private var discoveryUsers = 0
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun discovered(): List<Found> = found.values.sortedBy { it.serviceName }

    @Synchronized
    fun acquireDiscovery() {
        if (discoveryUsers++ == 0) startDiscovery()
    }

    @Synchronized
    fun releaseDiscovery() {
        if (discoveryUsers > 0 && --discoveryUsers == 0) stopDiscovery()
    }

    @Synchronized
    private fun restartDiscovery() {
        if (discoveryUsers > 0) {
            stopDiscovery()
            startDiscovery()
        }
    }

    private fun startDiscovery() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType.contains("_bridgesync")) enqueueResolve(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                fire()
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(tag, "discovery start failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = l
        try {
            nsd.discoverServices(Wire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(tag, "discoverServices failed", e)
        }
    }

    private fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
        found.clear()
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        val start = synchronized(resolveQueue) {
            resolveQueue.add(info)
            if (!resolving) { resolving = true; true } else false
        }
        if (start) resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val info = synchronized(resolveQueue) {
            val n = resolveQueue.poll()
            if (n == null) resolving = false
            n
        } ?: return
        val l = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolveNext()
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host
                if (host != null) {
                    val id = serviceInfo.attributes["id"]?.let { String(it, Charsets.UTF_8) }
                    found[serviceInfo.serviceName] = Found(serviceInfo.serviceName, host, serviceInfo.port, id)
                    fire()
                    if (id != null && id == store.macIdHex) wakeRequested = true   // our Mac appeared: stop waiting
                    poke()
                }
                resolveNext()
            }
        }
        try {
            nsd.resolveService(info, l)
        } catch (e: Exception) {
            resolveNext()
        }
    }

    // ---- connection loop ----------------------------------------------------------------------------------

    /** The Mac as seen by mDNS right now (most trustworthy: fresh address and port). */
    private fun mdnsTarget(): InetSocketAddress? {
        val id = store.macIdHex
        return found.values.firstOrNull { it.macId != null && it.macId == id }?.let { InetSocketAddress(it.address, it.port) }
    }

    /** Addresses that are cheap to try: mDNS, the last good one, history, and every address the Mac itself reported. */
    private fun cheapCandidates(): List<InetSocketAddress> {
        val out = LinkedHashSet<InetSocketAddress>()
        mdnsTarget()?.let { out.add(it) }
        val h = store.lastHost
        if (h != null && store.lastPort > 0) out.add(InetSocketAddress(h, store.lastPort))
        for (k in store.knownHosts) {
            val host = k.substringBeforeLast(':', "")
            val port = k.substringAfterLast(':', "").toIntOrNull() ?: continue
            if (host.isNotEmpty()) out.add(InetSocketAddress(host, port))
        }
        for (ip in store.macIps) out.add(InetSocketAddress(ip, Wire.DEFAULT_PORT))
        return out.toList()
    }

    /** Why the Mac was not found, shown on the main screen while searching. */
    @Volatile var searchNote: String = ""
        private set

    /** True after the Mac announced sleep/shutdown: the phone stops searching and waits for it to come back. */
    @Volatile var macAsleep = false
        private set

    @Volatile private var needScan = false          // the network changed: sweep our own subnets once
    @Volatile private var lastScan = 0L
    @Volatile private var deepRequested = false     // the user asked to search harder (neighbouring subnets)
    @Volatile private var wakeRequested = false     // something suggests the Mac may be back: check right now
    private var failStreak = 0
    private var lastDeep = 0L
    private val idleWaits = longArrayOf(60_000, 120_000, 300_000, 600_000, 1_200_000, 1_800_000)

    /** The app was opened, the network changed or the Mac was seen: check immediately, even if the Mac was asleep. */
    fun wakeNow(deep: Boolean = false) {
        wakeRequested = true
        if (deep) deepRequested = true
        poke()
    }

    /** Wake-on-LAN for the Mac (works only if "Wake for network access" is enabled on it), then look for it. */
    fun wakeMac() {
        val macs = store.macAddrs
        runBackground {
            runCatching { LanScan.wake(ctx, macs, store.macIps + listOfNotNull(store.lastHost)) }
            wakeNow()
        }
        toast(if (macs.isEmpty()) "Адрес Mac ещё неизвестен: подключитесь к нему один раз" else "Пробуждаю Mac…")
    }

    /** The Mac told us it is going to sleep or power off (or that it woke up). */
    fun onMacState(state: String) {
        macAsleep = state == "sleep" || state == "shutdown"
        if (!macAsleep) wakeNow()
    }

    private fun probe(a: InetSocketAddress, timeoutMs: Int = 900): Boolean = try {
        Socket().use { it.connect(a, timeoutMs); true }
    } catch (e: Exception) { false }

    /** Reachable ones among [list], probed in parallel. */
    private fun probeAll(list: List<InetSocketAddress>): List<InetSocketAddress> {
        if (list.isEmpty()) return emptyList()
        val ok = java.util.concurrent.ConcurrentLinkedQueue<InetSocketAddress>()
        val threads = list.map { a -> thread(isDaemon = true, name = "bridge-probe") { if (probe(a)) ok.add(a) } }
        threads.forEach { runCatching { it.join(1500) } }
        return list.filter { it in ok }
    }

    private fun loop(gen: Int) {
        while (running && gen == generation) {
            if (pairing) { waitSignal(1_000); continue }
            if (!store.isPaired) {
                setState(State.UNPAIRED)
                waitSignal(5_000)
                continue
            }
            val force = wakeRequested
            wakeRequested = false

            if (bleActive) {
                // Linked over Bluetooth: move to the network only if the Mac is reachable there. No scanning of our own.
                val ip = probeAll(cheapCandidates()).firstOrNull()
                if (ip == null) { waitSignal(60_000); continue }
                session?.close("upgrade")
                val until = SystemClock.elapsedRealtime() + 3_000
                while (linkBusy.get() && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
                if (tryConnect(listOf(ip))) { afterSuccess(); continue }
            }

            // The Mac said it is asleep: stay quiet until it appears (mDNS / Bluetooth) or the user asks.
            if (macAsleep && !force && mdnsTarget() == null) {
                softState(State.ASLEEP)
                waitSignal(30 * 60_000L)
                continue
            }

            softState(if (failStreak > 0) State.SEARCHING else State.CONNECTING)
            // 1. cheap candidates (history, addresses the Mac reported): a handful of single connections.
            var live = probeAll(cheapCandidates())
            var connected = tryConnect(live)
            var note = ""
            val now = SystemClock.elapsedRealtime()

            // 2. our own subnets, only after a network change, on request, or during the first few failures.
            if (!connected && (force || needScan || failStreak < 3) && now - lastScan > 8_000) {
                lastScan = now
                softState(State.SEARCHING)
                val scanned = runCatching { LanScan.scan(ctx) }.getOrDefault(emptyList())
                note = "Сеть ${LanScan.describe(ctx)}: " +
                    if (scanned.isEmpty()) "Mac не отвечает на порту ${Wire.DEFAULT_PORT}" else "Mac найден"
                connected = tryConnect(scanned.filter { it !in live })
            }

            // 3. neighbouring subnets (router + LAN behind different routes): rare and rate-limited.
            if (!connected && (deepRequested || ((force || needScan) && failStreak >= 1)) &&
                now - lastDeep > (if (deepRequested) 0 else 10 * 60_000L)
            ) {
                deepRequested = false
                lastDeep = now
                softState(State.SEARCHING)
                val found = runCatching { LanScan.sweepNeighbors(ctx) }.getOrDefault(emptyList())
                note += (if (note.isEmpty()) "" else " · ") + "Соседние подсети: " +
                    if (found.isEmpty()) "ничего" else "найдено ${found.size}"
                connected = tryConnect(found)
            }

            needScan = false
            if (connected) { afterSuccess(); continue }

            failStreak++
            note += (if (note.isEmpty()) "" else " · ") + "Bluetooth: " +
                if (ble.advertising) "телефон виден Mac" else "выключен или нет разрешения"
            searchNote = note
            fire()
            if (!linkBusy.get()) {
                peerName = null
                if (failStreak <= 3) {
                    setState(State.SEARCHING)
                    waitSignal(3_000L shl (failStreak - 1))          // 3 s, 6 s, 12 s
                } else {
                    // Not found for a while: it is probably asleep or elsewhere. Stop searching; wait for a signal.
                    setState(State.ASLEEP)
                    waitSignal(idleWaits[minOf(failStreak - 4, idleWaits.size - 1)])
                }
            }
        }
    }

    private fun afterSuccess() {
        failStreak = 0
        macAsleep = false
        needScan = false
        deepRequested = false
        searchNote = ""
        peerName = null
        if (!linkBusy.get() && running && store.isPaired) setState(State.SEARCHING)
    }

    /** Tries each address in turn; true once a session has run (it returns when that session ends). */
    private fun tryConnect(list: List<InetSocketAddress>): Boolean {
        for (target in list) {
            if (!running) return false
            softState(State.CONNECTING)
            try {
                runConnection(target)
                return true
            } catch (e: Exception) {
                Log.i(tag, "connection to $target failed: ${e.message}")
            }
        }
        return false
    }

    private val linkBusy = AtomicBoolean(false)

    /** State changes from the reconnect loop must never overwrite a live link (e.g. a Bluetooth session). */
    private fun softState(st: State) { if (!linkBusy.get()) setState(st) }
    @Volatile private var bleActive = false

    private fun runConnection(target: InetSocketAddress) {
        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.keepAlive = true
            sock.soTimeout = 10_000
            sock.connect(target, 4_000)
            val io = FrameIO(sock.getInputStream(), sock.getOutputStream())
            runLink(
                io, { runCatching { sock.close() } }, sock.inetAddress?.hostAddress?.substringBefore('%'), sock.port,
                slow = false,
            ) { sock.soTimeout = 0 }
        } finally {
            runCatching { sock.close() }
        }
    }

    /** Reconnect handshake plus the session loop over any byte pipe (TCP socket or BLE). Blocks until it ends. */
    private fun runLink(
        io: FrameIO, closer: () -> Unit, host: String?, port: Int, slow: Boolean, afterHandshake: () -> Unit,
    ) {
        if (!linkBusy.compareAndSet(false, true)) throw IOException("link busy")
        try {
            val keys = Handshake.reconnect(io, store.deviceId, store.secret!!)
            afterHandshake()
            val closed = CountDownLatch(1)
            val s = Session(SecureChannel(io, keys), ConnListener(closed), store.deviceName, "android") { closer() }
            session = s
            Bridge.slowLink = slow
            bleActive = slow
            if (host != null) { store.lastHost = host; store.lastPort = port; store.rememberHost(host, port) }
            s.start()
            setState(State.CONNECTED)
            features.onConnected()
            try {
                while (running && !s.isClosed) closed.await(1, TimeUnit.SECONDS)
                s.close("done")
            } finally {
                features.onDisconnected()
                if (session === s) session = null
                Bridge.slowLink = false
                bleActive = false
            }
        } finally {
            runCatching { closer() }
            linkBusy.set(false)
        }
    }

    /** A Mac connected over Bluetooth LE: pair (if the user asked for it) or reconnect with the stored secret. */
    fun attachBle(stream: BleStream, release: () -> Unit) {
        val ui = blePairUi
        if (ui != null) {
            blePairUi = null
            pair(ui) { Link(FrameIO(stream.input, stream.output), { stream.close(); release() }, null, 0) }
            return
        }
        if (!store.isPaired || linkBusy.get() || pairing) {
            stream.close(); release()
            return
        }
        thread(name = "bridge-ble", isDaemon = true) {
            val watchdog = main
            val done = AtomicBoolean(false)
            watchdog.postDelayed({ if (!done.get()) stream.close() }, 20_000)
            try {
                setState(State.CONNECTING)
                runLink(FrameIO(stream.input, stream.output), { stream.close() }, null, 0, slow = true) { done.set(true) }
            } catch (e: Exception) {
                Log.i(tag, "BLE link failed: ${e.message}")
            } finally {
                done.set(true)
                stream.close()
                release()
                peerName = null
                if (running && store.isPaired && session == null) setState(State.SEARCHING)
                poke()
            }
        }
    }

    @Volatile private var blePairUi: PairingUi? = null

    /** Waits for the Mac (in pairing mode) to come over Bluetooth instead of a network address. */
    fun pairViaBle(ui: PairingUi) {
        blePairUi = ui
        ble.start()
        ble.pairingWindow = true
        main.postDelayed({
            if (blePairUi === ui) {
                blePairUi = null
                ble.pairingWindow = false
                ui.result(false, "Mac не нашёлся по Bluetooth. На Mac откройте «Сопрячь телефон…» и включите Bluetooth.")
            }
        }, 90_000)
    }

    // ---- pairing ------------------------------------------------------------------------------------------

    class Link(
        val io: FrameIO, val close: () -> Unit, val host: String?, val port: Int, val afterHandshake: () -> Unit = {},
    )

    fun pair(address: InetSocketAddress, ui: PairingUi) = pair(ui) {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 15_000
            socket.connect(address, 5_000)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        Link(
            FrameIO(socket.getInputStream(), socket.getOutputStream()), { runCatching { socket.close() } },
            address.address?.hostAddress?.substringBefore('%'), address.port,
        ) { socket.soTimeout = 0 }
    }

    private fun pair(ui: PairingUi, open: () -> Link) {
        if (pairing) { android.util.Log.w("BridgePair", "pair ignored: already pairing"); return }
        pairing = true
        android.util.Log.w("BridgePair", "start")
        setState(State.PAIRING)
        session?.close("pairing")
        thread(name = "bridge-pair", isDaemon = true) {
            var link: Link? = null
            var message = ""
            var ok = false
            try {
                val l = open()
                android.util.Log.w("BridgePair", "connected to ${l.host}:${l.port}")
                link = l
                val io = l.io
                val watchdog = AtomicBoolean(false)
                if (l.host == null) main.postDelayed({ if (!watchdog.get()) l.close() }, 20_000)
                val res = Handshake.pair(io, store.deviceId, store.deviceName)
                watchdog.set(true)
                android.util.Log.w("BridgePair", "handshake ok, sas=${res.sas}")
                l.afterHandshake()
                val peerConfirm = CompletableFuture<Boolean>()
                val s = Session(
                    SecureChannel(io, res.keys),
                    object : SessionListener {
                        override fun onPairConfirm(ok: Boolean) { peerConfirm.complete(ok) }
                        override fun onClosed(reason: String) { peerConfirm.complete(false) }
                    },
                    store.deviceName, "android",
                ) { runCatching { l.close() } }
                s.start()

                val decision = CompletableFuture<Boolean>()
                main.post { ui.confirmSas(res.sas, res.serverName) { decision.complete(it) } }
                android.util.Log.w("BridgePair", "waiting for user decision")
                val userOk = decision.get(120, TimeUnit.SECONDS)
                android.util.Log.w("BridgePair", "user decided $userOk")
                s.sendPairConfirm(userOk)
                if (!userOk) {
                    s.close("cancelled")
                    message = "Сопряжение отменено"
                } else if (!peerConfirm.get(120, TimeUnit.SECONDS)) {
                    s.close("rejected")
                    message = "Mac не подтвердил сопряжение"
                } else {
                    store.savePairing(res.serverId, res.serverName, res.pairingSecret)
                    if (l.host != null) {
                        store.lastHost = l.host
                        store.lastPort = l.port
                    }
                    s.close("paired")
                    ok = true
                    message = "Сопряжено с ${res.serverName}"
                }
            } catch (e: Exception) {
                android.util.Log.w("BridgePair", "failed", e)
                message = "Не удалось сопрячься: " + (e.message ?: e.javaClass.simpleName) +
                    "\nНа Mac должно быть открыто «Сопрячь телефон…»"
                runCatching { link?.close?.invoke() }
            } finally {
                pairing = false
                ble.pairingWindow = false
                setState(if (store.isPaired) State.SEARCHING else State.UNPAIRED)
                poke()
                main.post { ui.result(ok, message) }
            }
        }
    }

    // ---- outgoing -----------------------------------------------------------------------------------------

    fun sendClipboard(text: String) {
        val s = session ?: return
        sender.execute { runCatching { s.sendClipboard(text) } }
    }

    fun sendNotification(
        key: String, app: String, pkg: String, title: String, text: String, ts: Long,
        canReply: Boolean = false, clearable: Boolean = true,
    ) {
        val s = session ?: return
        sender.execute { runCatching { s.sendNotification(key, app, pkg, title, text, ts, canReply, clearable) } }
    }

    /** Fire-and-forget JSON message (status, sms, call, ...). Silently dropped when not connected. */
    fun sendJson(o: org.json.JSONObject) {
        val s = session ?: return
        sender.execute { runCatching { s.sendJson(o) } }
    }

    /** Runs [block] on the shared background worker used for feature work (photo scans, ...). */
    fun runBackground(block: () -> Unit) { transfers.execute { runCatching(block) } }

    fun refreshFeatures() {
        features.onDisconnected()
        if (isConnected) features.onConnected()
    }

    fun sendNotificationRemoved(key: String) {
        val s = session ?: return
        sender.execute { runCatching { s.sendNotificationRemoved(key) } }
    }

    /**
     * Opens the file descriptor immediately: the temporary URI permission of a share intent ends
     * with the activity, but an open descriptor stays valid.
     */
    fun openOutgoing(uri: Uri): Outgoing? {
        return try {
            var name = uri.lastPathSegment ?: "file"
            var size = -1L
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            if (size < 0) size = pfd.statSize
            if (size < 0) {
                pfd.close()
                return null
            }
            Outgoing(name, size, pfd)
        } catch (e: Exception) {
            Log.w(tag, "cannot open $uri", e)
            null
        }
    }

    /** Blocking send of one opened file; closes its descriptor. Call from a worker thread. */
    fun sendOutgoingBlocking(f: Outgoing, kind: String? = null, showProgress: Boolean = true): Boolean {
        val s = session
        if (s == null) {
            runCatching { f.pfd.close() }
            return false
        }
        var lastUi = 0L
        return try {
            FileInputStream(f.pfd.fileDescriptor).use { input ->
                s.sendFile(f.name, f.size, input, kind) { sent ->
                    if (showProgress) {
                        val now = System.nanoTime()
                        if (now - lastUi > 300_000_000L) {
                            lastUi = now
                            val pct = if (f.size > 0) (sent * 100 / f.size).toInt() else 100
                            setActivity("Отправка «${f.name}» — $pct%")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            false
        } finally {
            runCatching { f.pfd.close() }
            if (showProgress) setActivity(null)
        }
    }

    fun sendFiles(files: List<Outgoing>) {
        if (session == null) {
            files.forEach { runCatching { it.pfd.close() } }
            toast("Mac не подключён")
            return
        }
        for (f in files) {
            transfers.execute {
                val ok = sendOutgoingBlocking(f)
                toast(if (ok) "Отправлено: ${f.name}" else "Не удалось отправить: ${f.name}")
            }
        }
    }

    // ---- incoming -----------------------------------------------------------------------------------------

    private inner class ConnListener(private val closed: CountDownLatch) : SessionListener {
        override fun onHello(name: String, platform: String) {
            peerName = name
            fire()
        }

        override fun onClipboard(text: String) {
            if (!store.applyClipboard) return
            main.post {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Mac", text))
            }
        }

        override fun openIncoming(id: Int, name: String, size: Long): IncomingFile? =
            try { MediaSink(name) } catch (e: Exception) { Log.w(tag, "cannot store $name", e); null }

        override fun onMessage(type: String, message: org.json.JSONObject) {
            features.handle(type, message)
        }

        override fun onClosed(reason: String) {
            Log.i(tag, "session closed: $reason")
            closed.countDown()
        }
    }

    /** Writes a received file into Downloads/Bridge via MediaStore (no storage permission needed). */
    private inner class MediaSink(name: String) : IncomingFile {
        private val displayName = FileNames.safe(name)
        private val resolver = ctx.contentResolver
        private val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(displayName.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        private val uri: Uri
        private val out: OutputStream

        init {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bridge")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("insert failed")
            out = resolver.openOutputStream(uri) ?: throw IOException("open failed")
        }

        override fun write(buf: ByteArray, off: Int, len: Int) = out.write(buf, off, len)

        override fun complete(ok: Boolean) {
            runCatching { out.close() }
            if (ok) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                notifyReceived()
            } else {
                resolver.delete(uri, null, null)
            }
        }

        override fun abort() {
            runCatching { out.close() }
            runCatching { resolver.delete(uri, null, null) }
        }

        private fun notifyReceived() {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(ctx, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
            val n: Notification = Notification.Builder(ctx, Notifs.CH_FILES)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Файл с Mac")
                .setContentText(displayName)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(uri.hashCode(), n)
        }
    }
}
