// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SwarmCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "SwarmCore", targets: ["SwarmCore"])],
    dependencies: [
        // JOSE (RFC 7516/7518) for chat JWE, qualified against apps/android chat-vectors.json.
        .package(url: "https://github.com/airsidemobile/JOSESwift.git", from: "3.0.0")
    ],
    targets: [
        .target(name: "SwarmCore", dependencies: ["JOSESwift"]),
        .testTarget(name: "SwarmCoreTests", dependencies: ["SwarmCore"], resources: [.copy("Fixtures")])
    ]
)
