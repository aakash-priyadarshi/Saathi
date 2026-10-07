import XCTest
@testable import SwarmCore

/// The live staging service configuration (Fixtures/staging-service-config.json) against its root public JWK.
final class ServiceConfigTests: XCTestCase {
    private var root: JSON = [:], config: JSON = [:]
    private let inside = try! Instant.parse("2026-10-20T00:00:00Z") // issuedAt 2026-10-06, expiresAt 2026-11-05
    override func setUpWithError() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "staging-service-config", withExtension: "json", subdirectory: "Fixtures"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? JSON)
        root = try XCTUnwrap(fixture["root"] as? JSON); config = try XCTUnwrap(fixture["config"] as? JSON)
    }
    private func verify(_ c: JSON? = nil, environment: String = "staging", versionCode: Int = 1, previous: JSON? = nil,
                        minimumVersion: Int = 1, now: Date? = nil) throws {
        try ServiceConfig.verify(c ?? config, root: root, environment: environment, versionCode: versionCode,
                                 previous: previous, minimumVersion: minimumVersion, now: now ?? inside)
    }
    private func edited(_ change: (inout JSON) -> Void) -> JSON {
        var body = config["body"] as! JSON; change(&body); var c = config; c["body"] = body; return c
    }

    func testRealStagingConfigVerifies() throws {
        XCTAssertEqual(try ServiceConfig.verify(config, root: root, environment: "staging", versionCode: 1, now: inside)["rootKeyId"] as? String,
                       config["rootKeyId"] as? String)
        XCTAssertEqual(ServiceConfig.apiEndpoints(config), ["https://api.swarm.cockroachjantaparty.org"])
        XCTAssertEqual(ServiceConfig.features(config), ["nearby": true, "localCalls": true, "largeFiles": true])
        XCTAssertEqual(ServiceConfig.version(config), 2)
        try verify(previous: config, minimumVersion: 2) // re-verifying the same trusted config is fine
    }
    func testTamperedConfigFails() {
        XCTAssertThrowsError(try verify(edited { $0["apiEndpoints"] = ["https://evil.example"] }))
        XCTAssertThrowsError(try verify(edited { $0["features"] = ["nearby": false, "localCalls": true, "largeFiles": true] }))
        var c = config; c["rootKeyId"] = String(repeating: "0", count: 64)
        XCTAssertThrowsError(try verify(c))
        c = config; c["extra"] = true
        XCTAssertThrowsError(try verify(c))
    }
    func testWrongEnvironmentFails() {
        XCTAssertThrowsError(try verify(environment: "production"))
        XCTAssertThrowsError(try verify(environment: "development"))
    }
    func testRollbackFails() {
        XCTAssertThrowsError(try verify(minimumVersion: 3))
        // Same version as the trusted one but different contents (forged previous shares a version, differs in hash).
        XCTAssertThrowsError(try verify(previous: edited { $0["publicUrl"] = "https://other.example" }))
        XCTAssertThrowsError(try verify(previous: edited { $0["version"] = 5 }))
    }
    func testClockBoundsAndAppGate() {
        XCTAssertThrowsError(try verify(now: try! Instant.parse("2026-11-05T13:13:55.753Z"))) // expired
        XCTAssertThrowsError(try verify(now: try! Instant.parse("2026-10-06T13:00:00Z")))     // issued > now + 5 min
        XCTAssertNoThrow(try verify(now: try! Instant.parse("2026-10-06T13:10:00Z")))        // within 5 min skew
        XCTAssertThrowsError(try verify(versionCode: 0))
    }
}
