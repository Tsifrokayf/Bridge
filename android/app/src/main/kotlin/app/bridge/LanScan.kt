package app.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.bridge.core.Wire
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the Mac without mDNS: probes every host of the phone's local subnet(s) on Bridge's well-known port.
 * Works whenever phone and Mac can reach each other by IP (Wi-Fi ⇄ Ethernet behind one router),
 * even when the router does not forward multicast between its wireless and wired segments.
 */
object LanScan {
    private class Lan(val network: Network, val ip: Int, val prefix: Int)

    private fun lans(ctx: Context): List<Lan> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val out = ArrayList<Lan>()
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            for (la in lp.linkAddresses) {
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                    out.add(Lan(n, toInt(a.address), la.prefixLength))
                }
            }
        }
        return out
    }

    private fun toInt(b: ByteArray): Int =
        ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)

    private fun toAddr(v: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))

    private fun text(v: Int) = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    /** "192.168.0.23 (Wi-Fi)" — for messages when nothing was found. */
    fun describe(ctx: Context): String {
        val l = lans(ctx)
        return if (l.isEmpty()) "нет Wi-Fi" else l.joinToString(", ") { text(it.ip) }
    }

    /** Addresses (with the well-known port) of hosts that accept a connection. Blocking, ~1–4 s. */
    fun scan(ctx: Context, port: Int = Wire.DEFAULT_PORT): List<InetSocketAddress> {
        val targets = ArrayList<Pair<Network, Int>>()
        for (lan in lans(ctx)) {
            // Never sweep more than a /22 (1022 hosts); wider networks are scanned around our own address.
            val prefix = if (lan.prefix < 22) 24 else lan.prefix.coerceAtMost(30)
            val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
            val base = lan.ip and mask
            val count = 1 shl (32 - prefix)
            for (i in 1 until count - 1) {
                val host = base or i
                if (host != lan.ip) targets.add(lan.network to host)
            }
        }
        if (targets.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(96) { r -> Thread(r, "bridge-scan").also { it.isDaemon = true } }
        try {
            val tasks = targets.map { (net, host) ->
                Callable<InetSocketAddress?> {
                    try {
                        net.socketFactory.createSocket().use { s ->
                            s.connect(InetSocketAddress(toAddr(host), port), 350)
                            InetSocketAddress(toAddr(host), port)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            return pool.invokeAll(tasks, 6, TimeUnit.SECONDS)
                .mapNotNull { f -> if (f.isCancelled) null else runCatching { f.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** Connects to every host in [hosts] through [network]; returns those with [port] open. */
    private fun openHosts(network: Network, hosts: List<Int>, port: Int, timeoutMs: Int): List<InetSocketAddress> {
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(96) { r -> Thread(r, "bridge-scan").also { it.isDaemon = true } }
        try {
            val tasks = hosts.map { host ->
                Callable<InetSocketAddress?> {
                    try {
                        network.socketFactory.createSocket().use { s ->
                            s.connect(InetSocketAddress(toAddr(host), port), timeoutMs)
                            InetSocketAddress(toAddr(host), port)
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            return pool.invokeAll(tasks, 8, TimeUnit.SECONDS)
                .mapNotNull { f -> if (f.isCancelled) null else runCatching { f.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** A host answers on [port] with either an accept or a reset: it exists. A timeout means nobody is there. */
    private fun alive(network: Network, host: Int, port: Int): Boolean = try {
        network.socketFactory.createSocket().use { it.connect(InetSocketAddress(toAddr(host), port), 250); true }
    } catch (e: java.net.ConnectException) {
        e.message?.contains("refused", ignoreCase = true) == true
    } catch (e: Exception) {
        false
    }

    private val commonSubnets = listOf(
        "192.168.0", "192.168.1", "192.168.2", "192.168.10", "192.168.100", "10.0.0", "10.0.1", "10.1.1", "172.16.0",
    )

    /**
     * Router and wired LAN on different routes: the Mac may sit in another /24. Looks at the neighbouring subnets of the same
     * private range (and a few very common ones), finds those with a live gateway (.1/.254), and probes only those. Rare and slow
     * on purpose; the caller rate-limits it.
     */
    fun sweepNeighbors(ctx: Context, port: Int = Wire.DEFAULT_PORT): List<InetSocketAddress> {
        val subnets = LinkedHashMap<Int, Network>()   // /24 base → network to reach it through
        for (lan in lans(ctx)) {
            val first = (lan.ip ushr 24) and 255
            val second = (lan.ip ushr 16) and 255
            val own = lan.ip and 0xFFFFFF00.toInt()
            val bases = ArrayList<Int>()
            when {
                first == 10 -> for (t in 0..255) bases.add((10 shl 24) or (second shl 16) or (t shl 8))
                first == 192 && second == 168 -> for (t in 0..255) bases.add((192 shl 24) or (168 shl 16) or (t shl 8))
                first == 172 && second in 16..31 -> for (t in 0..255) bases.add((172 shl 24) or (second shl 16) or (t shl 8))
            }
            for (c in commonSubnets) {
                val o = c.split('.').map { it.toInt() }
                bases.add((o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8))
            }
            for (b in bases) if (b != own) subnets.putIfAbsent(b, lan.network)
        }
        if (subnets.isEmpty()) return emptyList()
        // Which subnets have a live gateway?
        val probes = ArrayList<Pair<Int, Network>>()
        for ((base, net) in subnets) { probes.add((base or 1) to net); probes.add((base or 254) to net) }
        val pool = Executors.newFixedThreadPool(96) { r -> Thread(r, "bridge-sweep").also { it.isDaemon = true } }
        val aliveBases = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        try {
            val tasks = probes.map { (host, net) ->
                Callable<Unit> { if (alive(net, host, 80) || alive(net, host, 443)) aliveBases.add(host and 0xFFFFFF00.toInt()) }
            }
            pool.invokeAll(tasks, 10, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        val found = ArrayList<InetSocketAddress>()
        for (base in aliveBases.take(8)) {
            val net = subnets[base] ?: continue
            found += openHosts(net, (1..254).map { base or it }, port, 300)
        }
        return found
    }

    /** Wake-on-LAN magic packets, as broadcasts on every local network and as unicast to the Mac's known addresses. */
    fun wake(ctx: Context, macs: List<String>, unicast: List<String>) {
        val packets = macs.mapNotNull { m ->
            val b = m.split(':', '-').mapNotNull { it.toIntOrNull(16)?.toByte() }
            if (b.size != 6) null else ByteArray(6) { 0xFF.toByte() } + ByteArray(96) { b[it % 6] }
        }
        if (packets.isEmpty()) return
        val targets = ArrayList<Pair<Network?, InetAddress>>()
        for (lan in lans(ctx)) {
            val mask = if (lan.prefix == 0) 0 else -1 shl (32 - lan.prefix)
            targets.add(lan.network to toAddr(lan.ip or mask.inv()))
            targets.add(lan.network to toAddr(-1))
        }
        for (ip in unicast) runCatching { targets.add(null to InetAddress.getByName(ip)) }
        for ((net, addr) in targets) {
            for (pkt in packets) for (port in intArrayOf(9, 7)) {
                try {
                    java.net.DatagramSocket().use { sock ->
                        sock.broadcast = true
                        net?.bindSocket(sock)
                        sock.send(java.net.DatagramPacket(pkt, pkt.size, addr, port))
                    }
                } catch (e: Exception) { /* best effort */ }
            }
        }
    }
}
