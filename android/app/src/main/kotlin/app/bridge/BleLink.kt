package app.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import app.bridge.core.Crypto
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Bluetooth LE side of Bridge, modelled on Apple Continuity: the phone keeps advertising a small rotating
 * token, the Mac recognises it, connects, and the same encrypted protocol runs over a GATT byte pipe.
 * It works with no shared network at all. Heavy features (mirroring, photo sync) wait for Wi-Fi.
 */
object BleIds {
    val SERVICE: UUID = UUID.fromString("b21d0001-5e1a-4c7b-9a3f-0d6f62726964")
    /** Mac → phone (write). */
    val RX: UUID = UUID.fromString("b21d0002-5e1a-4c7b-9a3f-0d6f62726964")
    /** Phone → Mac (notify). */
    val TX: UUID = UUID.fromString("b21d0003-5e1a-4c7b-9a3f-0d6f62726964")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    /** 16-bit UUID (0xB21D) the rotating token is published under. */
    val TOKEN_DATA: UUID = UUID.fromString("0000b21d-0000-1000-8000-00805f9b34fb")
    const val SLOT_SECONDS = 900L

    /** 6 bytes that only a Mac holding the pairing secret can predict; all zeros while pairing. */
    fun token(secret: ByteArray?, slot: Long): ByteArray {
        if (secret == null) return ByteArray(6)
        val slotBytes = ByteBuffer.allocate(8).putLong(slot).array()
        return Crypto.hmac(secret, "bridge-ble-v1".toByteArray(), slotBytes).copyOf(6)
    }

    fun currentSlot(): Long = System.currentTimeMillis() / 1000 / SLOT_SECONDS
}

/** Blocking byte stream over a GATT connection: reads what the Mac wrote, writes as notifications. */
class BleStream(
    private val server: BluetoothGattServer,
    private val device: BluetoothDevice,
    private val tx: BluetoothGattCharacteristic,
) {
    private val inbox = LinkedBlockingQueue<ByteArray>()
    private val closedMarker = ByteArray(0)
    private val sent = Semaphore(0)
    @Volatile private var lastStatus = BluetoothGatt.GATT_SUCCESS
    @Volatile var mtu = 23
    @Volatile var closed = false
        private set

    fun feed(data: ByteArray) { if (data.isNotEmpty() && !closed) inbox.offer(data) }

    fun notificationSent(status: Int) {
        lastStatus = status
        sent.release()
    }

    fun close() {
        if (closed) return
        closed = true
        inbox.offer(closedMarker)
        sent.release()
    }

    val input: InputStream = object : InputStream() {
        private var cur = closedMarker
        private var pos = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= cur.size) {
                val n = inbox.take()
                if (n === closedMarker) { inbox.offer(closedMarker); return -1 }
                cur = n
                pos = 0
            }
            val c = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, c)
            pos += c
            return c
        }
    }

    @SuppressLint("MissingPermission")
    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            val end = off + len
            val max = (mtu - 3).coerceIn(20, 509)
            while (o < end) {
                if (closed) throw IOException("BLE link closed")
                val n = minOf(max, end - o)
                val chunk = b.copyOfRange(o, o + n)
                sent.drainPermits()
                val ok = if (Build.VERSION.SDK_INT >= 33) {
                    server.notifyCharacteristicChanged(device, tx, false, chunk) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run { tx.value = chunk; server.notifyCharacteristicChanged(device, tx, false) }
                }
                if (!ok) throw IOException("BLE notify failed")
                if (!sent.tryAcquire(5, TimeUnit.SECONDS)) throw IOException("BLE notify timeout")
                if (closed) throw IOException("BLE link closed")
                if (lastStatus != BluetoothGatt.GATT_SUCCESS) throw IOException("BLE notify status $lastStatus")
                o += n
            }
        }
    }
}

@SuppressLint("MissingPermission")
class BleLink(private val ctx: Context, private val manager: ConnectionManager, private val store: Store) {
    private val tag = "BridgeBle"
    private val main = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var advCallback: AdvertiseCallback? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var started = false
    private var central: BluetoothDevice? = null
    private var stream: BleStream? = null
    private var stateReceiver: BroadcastReceiver? = null

    /** For the status line: whether the phone is currently visible to the Mac over Bluetooth. */
    @Volatile var advertising = false
        private set

    /** While true the phone advertises the all-zero token so a Mac in pairing mode can pick it up. */
    @Volatile var pairingWindow = false
        set(v) {
            field = v
            main.post { if (started) restartAdvertising() }
        }

    private val refresh = object : Runnable {
        override fun run() {
            if (started) restartAdvertising()
            main.postDelayed(this, 5 * 60_000L)
        }
    }

    companion object {
        fun hasPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 31 ||
                (Perms.has(ctx, android.Manifest.permission.BLUETOOTH_ADVERTISE) &&
                    Perms.has(ctx, android.Manifest.permission.BLUETOOTH_CONNECT))

        val permissions: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 31)
                arrayOf(android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT)
            else emptyArray()
    }

    fun start() {
        main.post {
            if (started) return@post
            if (!hasPermission(ctx)) return@post
            val bm = ctx.getSystemService(BluetoothManager::class.java) ?: return@post
            val adapter = bm.adapter ?: return@post
            if (!adapter.isEnabled) {
                watchAdapter()
                return@post
            }
            val adv = adapter.bluetoothLeAdvertiser ?: return@post
            advertiser = adv
            val srv = try { bm.openGattServer(ctx, callback) } catch (e: Exception) { Log.w(tag, "gatt server", e); null } ?: return@post
            server = srv
            val svc = BluetoothGattService(BleIds.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val rx = BluetoothGattCharacteristic(
                BleIds.RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            val t = BluetoothGattCharacteristic(BleIds.TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
            t.addDescriptor(
                BluetoothGattDescriptor(
                    BleIds.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
            tx = t
            svc.addCharacteristic(rx)
            svc.addCharacteristic(t)
            started = true
            watchAdapter()
            srv.addService(svc)   // advertising starts in onServiceAdded
            main.postDelayed(refresh, 5 * 60_000L)
        }
    }

    fun stop() {
        main.post {
            main.removeCallbacks(refresh)
            stopAdvertising()
            stream?.close()
            stream = null
            central = null
            runCatching { server?.close() }
            server = null
            started = false
            stateReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
            stateReceiver = null
        }
    }

    private fun watchAdapter() {
        if (stateReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (st == BluetoothAdapter.STATE_ON) {
                    if (!started) start()
                } else if (st == BluetoothAdapter.STATE_TURNING_OFF && started) {
                    stopInternalForAdapterOff()
                }
            }
        }
        stateReceiver = r
        ctx.registerReceiver(r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private fun stopInternalForAdapterOff() {
        main.removeCallbacks(refresh)
        advCallback = null
        stream?.close()
        stream = null
        central = null
        runCatching { server?.close() }
        server = null
        started = false
    }

    // ---- advertising --------------------------------------------------------------------------------

    private fun restartAdvertising() {
        stopAdvertising()
        if (stream != null) return   // connected: advertising pauses by itself
        val secret = if (pairingWindow || !store.isPaired) null else store.secret
        if (secret == null && !pairingWindow) return   // nothing to announce
        val slot = BleIds.currentSlot()
        val adv = advertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(if (pairingWindow) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleIds.SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(BleIds.TOKEN_DATA), BleIds.token(secret, slot))
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { advertising = false; Log.w(tag, "advertise failed: $errorCode") }
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { advertising = true; Log.i(tag, "advertising") }
        }
        advCallback = cb
        try { adv.startAdvertising(settings, data, scanResponse, cb) } catch (e: Exception) { Log.w(tag, "startAdvertising", e) }
    }

    private fun stopAdvertising() {
        val cb = advCallback ?: return
        advCallback = null
        advertising = false
        runCatching { advertiser?.stopAdvertising(cb) }
    }

    // ---- GATT server --------------------------------------------------------------------------------

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post { if (started) restartAdvertising() }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    if (central != null && central != device) {
                        runCatching { server?.cancelConnection(device) }
                        return@post
                    }
                    central = device
                    stopAdvertising()
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED && device == central) {
                    central = null
                    stream?.close()
                    stream = null
                    restartAdvertising()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            stream?.mtu = mtu
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (descriptor.uuid != BleIds.CCCD) return
            val enabled = value.isNotEmpty() && value[0].toInt() != 0
            main.post {
                if (!enabled || device != central || stream != null) return@post
                val srv = server ?: return@post
                val t = tx ?: return@post
                val s = BleStream(srv, device, t)
                stream = s
                manager.attachBle(s) {
                    main.post {
                        if (stream === s) {
                            stream = null
                            runCatching { srv.cancelConnection(device) }
                        }
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (characteristic.uuid == BleIds.RX) stream?.feed(value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            stream?.notificationSent(status)
        }
    }
}
