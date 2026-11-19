import Foundation
import CoreBluetooth
import CryptoKit
import BridgeCore

/// Bluetooth LE discovery and transport, modelled on Apple Continuity: the phone advertises a rotating token,
/// the Mac recognises it (it knows the pairing secret), connects, and the normal encrypted protocol runs over a
/// GATT byte pipe. Works with no shared network; heavy features move to Wi-Fi when it is available.
enum BleIds {
    static let service = CBUUID(string: "B21D0001-5E1A-4C7B-9A3F-0D6F62726964")
    /// Mac → phone (write without response).
    static let rx = CBUUID(string: "B21D0002-5E1A-4C7B-9A3F-0D6F62726964")
    /// Phone → Mac (notify).
    static let tx = CBUUID(string: "B21D0003-5E1A-4C7B-9A3F-0D6F62726964")
    static let tokenData = CBUUID(string: "B21D")
    static let slotSeconds: Int64 = 900

    /// Must match BleIds.token on Android: HMAC-SHA256(secret, "bridge-ble-v1" || slot as big-endian Int64)[0..<6].
    static func token(secret: Data, slot: Int64) -> Data {
        var slotBytes = Data()
        withUnsafeBytes(of: slot.bigEndian) { slotBytes.append(contentsOf: $0) }
        var mac = HMAC<SHA256>(key: SymmetricKey(data: secret))
        mac.update(data: Data("bridge-ble-v1".utf8))
        mac.update(data: slotBytes)
        return Data(Data(mac.finalize()).prefix(6))
    }

    static func currentSlot() -> Int64 { Int64(Date().timeIntervalSince1970) / slotSeconds }
}

/// GATT characteristic pair presented as a byte pipe.
final class BleByteLink: ByteLink {
    var onData: ((Data) -> Void)?
    var onEnd: ((String) -> Void)?
    private let peripheral: CBPeripheral
    private let rx: CBCharacteristic
    private weak var central: CBCentralManager?
    private let bleQueue: DispatchQueue
    private var outQueue: DispatchQueue?
    private var pending: [(Data, ((Error?) -> Void)?)] = []   // bleQueue only
    private var ended = false

    init(peripheral: CBPeripheral, rx: CBCharacteristic, central: CBCentralManager, bleQueue: DispatchQueue) {
        self.peripheral = peripheral
        self.rx = rx
        self.central = central
        self.bleQueue = bleQueue
    }

    func start(queue: DispatchQueue) { outQueue = queue }

    /// bleQueue
    func deliver(_ data: Data) {
        guard !ended else { return }
        outQueue?.async { [weak self] in self?.onData?(data) }
    }

    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        bleQueue.async { [weak self] in
            guard let self = self, !self.ended else { completion(ProtocolError("Bluetooth link closed")); return }
            let size = max(20, self.peripheral.maximumWriteValueLength(for: .withoutResponse))
            var offset = 0
            while offset < data.count {
                let end = min(offset + size, data.count)
                let last = end == data.count
                self.pending.append((data.subdata(in: offset..<end), last ? completion : nil))
                offset = end
            }
            if data.isEmpty { completion(nil) }
            self.drain()
        }
    }

    /// bleQueue; also called from peripheralIsReady(toSendWriteWithoutResponse:).
    func drain() {
        while !ended, let first = pending.first, peripheral.canSendWriteWithoutResponse {
            peripheral.writeValue(first.0, for: rx, type: .withoutResponse)
            pending.removeFirst()
            first.1?(nil)
        }
    }

    func cancel() {
        bleQueue.async { [weak self] in
            guard let self = self else { return }
            self.central?.cancelPeripheralConnection(self.peripheral)
            self.end("closed")
        }
    }

    /// bleQueue
    func end(_ reason: String) {
        guard !ended else { return }
        ended = true
        let waiting = pending
        pending = []
        for item in waiting { item.1?(ProtocolError("Bluetooth link closed")) }
        outQueue?.async { [weak self] in self?.onEnd?(reason) }
    }
}

final class BleCentral: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private let queue = DispatchQueue(label: "bridge.ble")
    private var manager: CBCentralManager!
    private let server: BridgeServer
    private let config: Config
    private let pairingEnabled: () -> Bool
    private let hasSession: (String) -> Bool
    private var current: CBPeripheral?
    private var rx: CBCharacteristic?
    private var link: BleByteLink?
    private var lastAttempt: [UUID: Date] = [:]
    private var connectTimeout: DispatchWorkItem?

    /// `hasSession` is called on the Bluetooth queue and must be thread-safe.
    init(server: BridgeServer, config: Config, pairingEnabled: @escaping () -> Bool, hasSession: @escaping (String) -> Bool) {
        self.server = server
        self.config = config
        self.pairingEnabled = pairingEnabled
        self.hasSession = hasSession
        super.init()
        manager = CBCentralManager(delegate: self, queue: queue)
    }

    // ---- scanning -----------------------------------------------------------------------------------

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn { startScan() }
    }

    private func startScan() {
        guard manager.state == .poweredOn, current == nil else { return }
        manager.scanForPeripherals(withServices: [BleIds.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    /// Which paired phone does this token belong to? (Tokens rotate every 15 minutes; neighbouring slots are accepted.)
    private func deviceId(for token: Data) -> String? {
        let slot = BleIds.currentSlot()
        for dev in config.devices {
            guard let secret = Data(hex: dev.secret) else { continue }
            for s in (slot - 1)...(slot + 1) where BleIds.token(secret: secret, slot: s) == token { return dev.id }
        }
        return nil
    }

    func centralManager(
        _ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any], rssi RSSI: NSNumber
    ) {
        guard current == nil else { return }
        guard let data = (advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data])?[BleIds.tokenData],
              data.count == 6 else { return }
        if let last = lastAttempt[peripheral.identifier], Date().timeIntervalSince(last) < 3 { return }
        if data == Data(count: 6) {
            guard pairingEnabled() else { return }           // an unpaired phone: only while «Сопрячь телефон…» is open
        } else {
            guard let id = deviceId(for: data), !hasSession(id) else { return }
        }
        lastAttempt[peripheral.identifier] = Date()
        current = peripheral
        peripheral.delegate = self
        central.stopScan()
        central.connect(peripheral, options: nil)
        let t = DispatchWorkItem { [weak self] in self?.abandon("connect timeout") }
        connectTimeout = t
        queue.asyncAfter(deadline: .now() + 12, execute: t)
    }

    private func abandon(_ reason: String) {
        guard let p = current else { return }
        if link == nil {
            manager.cancelPeripheralConnection(p)
            current = nil
            rx = nil
            startScan()
        }
    }

    // ---- connection ---------------------------------------------------------------------------------

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([BleIds.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        current = nil
        startScan()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectTimeout?.cancel()
        link?.end(error.map { "\($0)" } ?? "disconnected")
        link = nil
        current = nil
        rx = nil
        queue.asyncAfter(deadline: .now() + 1) { [weak self] in self?.startScan() }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let svc = peripheral.services?.first(where: { $0.uuid == BleIds.service }) else { abandon("no service"); return }
        peripheral.discoverCharacteristics([BleIds.rx, BleIds.tx], for: svc)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics,
              let rx = chars.first(where: { $0.uuid == BleIds.rx }),
              let tx = chars.first(where: { $0.uuid == BleIds.tx }) else { abandon("no characteristics"); return }
        self.rx = rx
        peripheral.setNotifyValue(true, for: tx)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == BleIds.tx, characteristic.isNotifying, link == nil, let rx = rx else { return }
        connectTimeout?.cancel()
        let l = BleByteLink(peripheral: peripheral, rx: rx, central: manager, bleQueue: queue)
        link = l
        server.accept(link: l, handshakeTimeout: 25)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == BleIds.tx, let v = characteristic.value else { return }
        link?.deliver(v)
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        link?.drain()
    }
}
