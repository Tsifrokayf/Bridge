import Foundation
import BridgeCore

struct PairedDevice: Codable {
    var id: String      // hex
    var name: String
    var secret: String  // hex
}

private struct ConfigFile: Codable {
    var deviceId: String
    var devices: [PairedDevice] = []
    var applyClipboard: Bool = true
    var showNotifications: Bool = true
    // Added later: optional so that older config files still decode.
    var syncImages: Bool?
    var syncDND: Bool?
    var quickReply: String?
    var focusSetupAsked: Bool?
    var autoLock: Bool?
}

/// Settings and pairing secrets in ~/Library/Application Support/Bridge/config.json (mode 0600).
final class Config {
    private let lock = NSLock()
    private var file: ConfigFile
    private let url: URL

    let deviceName: String
    let saveDirectory: URL
    let photoDirectory: URL
    let outboxDirectory: URL

    init() {
        let fm = FileManager.default
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Bridge", isDirectory: true)
        try? fm.createDirectory(at: support, withIntermediateDirectories: true,
                                attributes: [.posixPermissions: 0o700])
        url = support.appendingPathComponent("config.json")
        if let data = try? Data(contentsOf: url), let f = try? JSONDecoder().decode(ConfigFile.self, from: data) {
            file = f
        } else {
            file = ConfigFile(deviceId: randomBytes(16).hex)
        }
        deviceName = Host.current().localizedName ?? "Mac"
        saveDirectory = (fm.urls(for: .downloadsDirectory, in: .userDomainMask).first
            ?? fm.homeDirectoryForCurrentUser.appendingPathComponent("Downloads"))
            .appendingPathComponent("Bridge", isDirectory: true)
        photoDirectory = (fm.urls(for: .picturesDirectory, in: .userDomainMask).first
            ?? fm.homeDirectoryForCurrentUser.appendingPathComponent("Pictures"))
            .appendingPathComponent("Bridge", isDirectory: true)
        outboxDirectory = support.appendingPathComponent("outbox", isDirectory: true)
        try? fm.createDirectory(at: outboxDirectory, withIntermediateDirectories: true,
                                attributes: [.posixPermissions: 0o700])
        save()
    }

    private func save() {
        guard let data = try? JSONEncoder().encode(file) else { return }
        // Write via a temp file so the secret is never briefly world-readable.
        let tmp = url.deletingLastPathComponent().appendingPathComponent("config.json.tmp")
        FileManager.default.createFile(atPath: tmp.path, contents: data, attributes: [.posixPermissions: 0o600])
        _ = try? FileManager.default.replaceItemAt(url, withItemAt: tmp)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    var deviceId: Data {
        lock.lock(); defer { lock.unlock() }
        return Data(hex: file.deviceId) ?? Data(count: 16)
    }

    var devices: [PairedDevice] {
        lock.lock(); defer { lock.unlock() }
        return file.devices
    }

    func secret(for id: Data) -> Data? {
        lock.lock(); defer { lock.unlock() }
        guard let d = file.devices.first(where: { $0.id == id.hex }) else { return nil }
        return Data(hex: d.secret)
    }

    func name(for id: Data) -> String? {
        lock.lock(); defer { lock.unlock() }
        return file.devices.first(where: { $0.id == id.hex })?.name
    }

    func addDevice(id: Data, name: String, secret: Data) {
        lock.lock()
        file.devices.removeAll { $0.id == id.hex }
        file.devices.append(PairedDevice(id: id.hex, name: name, secret: secret.hex))
        lock.unlock()
        save()
    }

    func removeDevice(id: String) {
        lock.lock()
        file.devices.removeAll { $0.id == id }
        lock.unlock()
        save()
    }

    var applyClipboard: Bool {
        get { lock.lock(); defer { lock.unlock() }; return file.applyClipboard }
        set { lock.lock(); file.applyClipboard = newValue; lock.unlock(); save() }
    }

    var showNotifications: Bool {
        get { lock.lock(); defer { lock.unlock() }; return file.showNotifications }
        set { lock.lock(); file.showNotifications = newValue; lock.unlock(); save() }
    }

    var quickReply: String {
        get { lock.lock(); defer { lock.unlock() }; return file.quickReply ?? "Не могу говорить, перезвоню позже." }
        set { lock.lock(); file.quickReply = newValue; lock.unlock(); save() }
    }
    var focusSetupAsked: Bool {
        get { lock.lock(); defer { lock.unlock() }; return file.focusSetupAsked ?? false }
        set { lock.lock(); file.focusSetupAsked = newValue; lock.unlock(); save() }
    }

    /// Lock the Mac when the phone has been gone for a while (opt-in).
    var autoLock: Bool {
        get { lock.lock(); defer { lock.unlock() }; return file.autoLock ?? false }
        set { lock.lock(); file.autoLock = newValue; lock.unlock(); save() }
    }

    var syncDND: Bool {
        get { lock.lock(); defer { lock.unlock() }; return file.syncDND ?? true }
        set { lock.lock(); file.syncDND = newValue; lock.unlock(); save() }
    }
    var syncImages: Bool {
        get { lock.lock(); defer { lock.unlock() }; return file.syncImages ?? true }
        set { lock.lock(); file.syncImages = newValue; lock.unlock(); save() }
    }
}
