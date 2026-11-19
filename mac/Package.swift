// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Bridge",
    platforms: [.macOS(.v13)],
    products: [
        .executable(name: "Bridge", targets: ["Bridge"]),
    ],
    targets: [
        // Protocol, crypto and session logic. No AppKit, so it can be unit-tested.
        .target(name: "BridgeCore"),
        // Menu-bar app (AppKit + Network.framework).
        .executableTarget(name: "Bridge", dependencies: ["BridgeCore"]),
        .testTarget(name: "BridgeCoreTests", dependencies: ["BridgeCore"]),
    ]
)
