// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "SwarmCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "SwarmCore", targets: ["SwarmCore"])],
    targets: [
        .target(name: "SwarmCore"),
        .testTarget(name: "SwarmCoreTests", dependencies: ["SwarmCore"], resources: [.copy("Fixtures")])
    ]
)
