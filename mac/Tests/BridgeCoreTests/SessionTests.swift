import XCTest
@testable import BridgeCore

private final class Loopback: Transport {
    weak var peer: Session?
    var peerQueue: DispatchQueue = DispatchQueue(label: "peer")
    private(set) var closedCount = 0

    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        let body = data.sub(4, data.count)
        let q = peerQueue
        q.async { [weak self] in self?.peer?.handle(frame: body) }
        completion(nil)
    }

    func close() { closedCount += 1 }
}

private final class Recorder: SessionDelegate {
    let clipboard = Recorder.expectation("clipboard")
    let file = Recorder.expectation("file")
    let hello = Recorder.expectation("hello")

    private static func expectation(_ name: String) -> XCTestExpectation {
        let e = XCTestExpectation(description: name)
        e.assertForOverFulfill = false
        return e
    }

    var texts: [String] = []
    var files: [URL] = []
    var kinds: [String?] = []
    var notifications: [IncomingNotification] = []
    var messages: [(String, [String: Any])] = []
    let message = Recorder.expectation("message")
    var removed: [String] = []
    var helloName = ""

    func session(_ s: Session, didReceiveHello name: String, platform: String) { helloName = "\(name)/\(platform)"; hello.fulfill() }
    func session(_ s: Session, didReceiveClipboard text: String) { texts.append(text); clipboard.fulfill() }
    func session(_ s: Session, didReceiveFile url: URL, kind: String?) { files.append(url); kinds.append(kind); file.fulfill() }
    func session(_ s: Session, didReceiveNotification n: IncomingNotification) { notifications.append(n) }
    func session(_ s: Session, didRemoveNotification key: String) { removed.append(key) }
    func session(_ s: Session, didReceiveMessage type: String, _ m: [String: Any]) { messages.append((type, m)); message.fulfill() }
}

/// Two Sessions wired back to back, with keys as a real handshake would produce them.
final class SessionTests: XCTestCase {
    private var dirA: URL!
    private var dirB: URL!
    private var dirPhotos: URL!

    override func setUp() {
        let base = FileManager.default.temporaryDirectory.appendingPathComponent("bridge-test-\(UUID().uuidString)")
        dirA = base.appendingPathComponent("a")
        dirB = base.appendingPathComponent("b")
        dirPhotos = base.appendingPathComponent("photos")
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dirA.deletingLastPathComponent())
    }

    private func makePair() -> (mac: Session, phone: Session, macRec: Recorder, phoneRec: Recorder) {
        let c2s = randomBytes(32), s2c = randomBytes(32)
        let macQ = DispatchQueue(label: "mac"), phoneQ = DispatchQueue(label: "phone")
        let tMac = Loopback(), tPhone = Loopback()
        let mac = Session(codec: RecordCodec(sendKey: s2c, recvKey: c2s), transport: tMac,
                          peerId: Data(), saveDirectory: dirA, photoDirectory: dirPhotos,
                          deviceName: "Test Mac", queue: macQ)
        let phone = Session(codec: RecordCodec(sendKey: c2s, recvKey: s2c), transport: tPhone,
                            peerId: Data(), saveDirectory: dirB, deviceName: "Test Phone", queue: phoneQ)
        tMac.peer = phone; tMac.peerQueue = phoneQ
        tPhone.peer = mac; tPhone.peerQueue = macQ
        let macRec = Recorder(), phoneRec = Recorder()
        mac.delegate = macRec
        phone.delegate = phoneRec
        return (mac, phone, macRec, phoneRec)
    }

    func testHelloClipboardAndNotifications() {
        let (mac, phone, macRec, phoneRec) = makePair()
        mac.start()
        phone.start()
        wait(for: [macRec.hello, phoneRec.hello], timeout: 5)
        XCTAssertEqual(macRec.helloName, "Test Phone/mac")

        mac.sendClipboard("привет ✓")
        wait(for: [phoneRec.clipboard], timeout: 5)
        XCTAssertEqual(phoneRec.texts, ["привет ✓"])

        phone.sendJSON(["t": "notification", "key": "k1", "app": "Telegram", "pkg": "org.telegram",
                        "title": "T", "text": "Body", "ts": 5])
        phone.sendJSON(["t": "notification_removed", "key": "k1"])
        phone.sendClipboard("flush")
        wait(for: [macRec.clipboard], timeout: 5)
        XCTAssertEqual(macRec.notifications.first?.app, "Telegram")
        XCTAssertEqual(macRec.notifications.first?.text, "Body")
        XCTAssertEqual(macRec.removed, ["k1"])
        mac.close(); phone.close()
    }

    func testFileBothWaysWithChecksumAndSanitizedName() throws {
        let (mac, phone, macRec, phoneRec) = makePair()
        mac.start(); phone.start()

        let src = FileManager.default.temporaryDirectory.appendingPathComponent("bridge-src-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: src, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: src) }
        let payload = Data((0..<700_000).map { UInt8(truncatingIfNeeded: $0 &* 7 &+ 3) })
        let file = src.appendingPathComponent("photo.bin")
        try payload.write(to: file)

        let done = expectation(description: "sent")
        var progressCalled = false
        mac.sendFile(at: file, progress: { _, _ in progressCalled = true }, completion: { ok in
            XCTAssertTrue(ok)
            done.fulfill()
        })
        wait(for: [done, phoneRec.file], timeout: 20)
        XCTAssertTrue(progressCalled)
        let got = try Data(contentsOf: try XCTUnwrap(phoneRec.files.first))
        XCTAssertEqual(got, payload)
        XCTAssertEqual(phoneRec.files.first?.lastPathComponent, "photo.bin")

        // Same name again → "photo (1).bin", nothing overwritten.
        let done2 = expectation(description: "sent2")
        mac.sendFile(at: file, completion: { ok in XCTAssertTrue(ok); done2.fulfill() })
        wait(for: [done2], timeout: 20)
        XCTAssertEqual(phoneRec.files.count, 2)
        XCTAssertEqual(phoneRec.files.last?.lastPathComponent, "photo (1).bin")

        // Phone → Mac.
        let evil = expectation(description: "evil")
        let tiny = src.appendingPathComponent("x.txt")
        try Data("hello".utf8).write(to: tiny)
        phone.sendFile(at: tiny, completion: { ok in XCTAssertTrue(ok); evil.fulfill() })
        wait(for: [evil, macRec.file], timeout: 10)
        XCTAssertEqual(macRec.files.first?.deletingLastPathComponent().standardizedFileURL.path, dirA.standardizedFileURL.path)

        // No temp files are left behind.
        let leftovers = try FileManager.default.contentsOfDirectory(atPath: dirB.path).filter { $0.hasPrefix(".bridge-partial") }
        XCTAssertTrue(leftovers.isEmpty)
        mac.close(); phone.close()
    }

    func testTamperedRecordClosesSession() {
        let (mac, phone, _, _) = makePair()
        let closed = expectation(description: "closed")
        final class D: SessionDelegate {
            let e: XCTestExpectation
            init(_ e: XCTestExpectation) { self.e = e }
            func session(_ s: Session, didCloseWithReason reason: String) { e.fulfill() }
        }
        let d = D(closed)
        phone.delegate = d
        phone.queue.async { phone.handle(frame: randomBytes(64)) }
        wait(for: [closed], timeout: 5)
        XCTAssertTrue(phone.isClosed)
        mac.close()
    }

    func testContentIsDroppedUntilEnabled() throws {
        let (mac, phone, macRec, phoneRec) = makePair()
        mac.start(); phone.start()
        wait(for: [macRec.hello, phoneRec.hello], timeout: 5)
        mac.contentEnabled = false

        phone.sendClipboard("secret")
        phone.sendJSON(["t": "notification", "key": "k", "app": "A", "pkg": "p", "title": "t", "text": "x", "ts": 1])
        let src = FileManager.default.temporaryDirectory.appendingPathComponent("bridge-gate-\(UUID().uuidString).txt")
        try Data("data".utf8).write(to: src)
        defer { try? FileManager.default.removeItem(at: src) }
        let rejected = expectation(description: "file refused")
        phone.sendFile(at: src, completion: { ok in
            XCTAssertFalse(ok)
            rejected.fulfill()
        })
        wait(for: [rejected], timeout: 10)
        XCTAssertTrue(macRec.texts.isEmpty)
        XCTAssertTrue(macRec.notifications.isEmpty)
        XCTAssertTrue(macRec.files.isEmpty)

        mac.contentEnabled = true
        phone.sendClipboard("ok")
        wait(for: [macRec.clipboard], timeout: 5)
        XCTAssertEqual(macRec.texts, ["ok"])
        mac.close(); phone.close()
    }

    func testPhotosGoToPhotoDirectoryAndAppMessagesAreForwarded() throws {
        let (mac, phone, macRec, _) = makePair()
        mac.start(); phone.start()

        let src = FileManager.default.temporaryDirectory.appendingPathComponent("bridge-photo-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: src, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: src) }
        let img = src.appendingPathComponent("IMG_1.jpg")
        try Data(repeating: 7, count: 5000).write(to: img)

        let sent = expectation(description: "sent")
        phone.sendFile(at: img, kind: "photo", completion: { ok in XCTAssertTrue(ok); sent.fulfill() })
        wait(for: [sent, macRec.file], timeout: 10)
        let saved = try XCTUnwrap(macRec.files.first)
        XCTAssertEqual(macRec.kinds.first ?? nil, "photo")
        XCTAssertEqual(saved.deletingLastPathComponent().standardizedFileURL.path, dirPhotos.standardizedFileURL.path)

        phone.sendJSON(["t": "status", "battery": 55, "charging": true])
        phone.sendJSON(["t": "notification", "key": "k", "app": "A", "pkg": "p", "title": "t", "text": "x",
                        "ts": 1, "reply": true, "clear": false])
        phone.sendJSON(["t": "sms", "id": 1, "from": "+100", "text": "hi", "ts": 2])
        phone.sendClipboard("flush")
        wait(for: [macRec.clipboard], timeout: 5)
        XCTAssertEqual(macRec.messages.map { $0.0 }, ["status", "sms"])
        XCTAssertEqual(jsonInt(macRec.messages[0].1["battery"]), 55)
        XCTAssertEqual(macRec.notifications.first?.canReply, true)
        XCTAssertEqual(macRec.notifications.first?.clearable, false)
        mac.close(); phone.close()
    }
}
