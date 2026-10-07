import Foundation

/// Signed service configuration, ported from Android `ServiceConfiguration.verify` (and @saathi/protocol `verifyServiceConfig`).
/// `root` is the environment's root public JWK; `previous` is the last trusted signed config and `minimumVersion` the
/// last trusted version (rollback protection). Returns `value` unchanged once every rule holds; throws `ChatRuleError` otherwise.
public enum ServiceConfig {
    @discardableResult
    public static func verify(_ value: JSON, root: JSON, environment: String, versionCode: Int, previous: JSON? = nil,
                              minimumVersion: Int = 1, now: Date = Date()) throws -> JSON {
        try J.req(try Canonical.data(value).count <= 32768, "Service information is too large.")
        try J.exact(value, ["body", "rootKeyId", "signature"])
        let body = try J.obj(value, "body")
        let signed = (try? ChatCrypto.verify(["body": body, "signature": try J.str(value, "signature")], publicKey: root)) == true
        try J.req(try J.str(value, "rootKeyId") == (try? ChatRules.hash(root)) && signed, "Service information could not be verified.")
        try J.exact(body, ["format", "environment", "version", "issuedAt", "expiresAt", "protocolVersions", "minimumAndroidVersionCode",
                           "apiEndpoints", "publicUrl", "webOrigin", "receiptKeys", "features"])
        try J.req(try J.str(body, "format") == "saathi-service-config-v1" && J.str(body, "environment") == environment,
                  "This service information belongs to another environment.")
        let version = try J.int(body, "version")
        try J.req(version >= minimumVersion && version <= 9_007_199_254_740_991, "Older service information was refused.")
        if let previous {
            try J.req(version > (try J.int(try J.obj(previous, "body"), "version")) || (try ChatRules.hash(value)) == (try ChatRules.hash(previous)),
                      "Conflicting service information was refused.")
        }
        let issued = try Instant.parse(try J.str(body, "issuedAt")), expiry = try Instant.parse(try J.str(body, "expiresAt"))
        try J.req(issued <= now.addingTimeInterval(300) && expiry > now && expiry > issued && expiry <= issued.addingTimeInterval(31 * 86400),
                  "Service information has expired or the phone clock needs attention. Saved work is safe.")
        let protocols = try J.arr(body, "protocolVersions"), minimumCode = try J.int(body, "minimumAndroidVersionCode")
        try J.req(protocols.count == 1 && (protocols[0] as? NSNumber).map { !J.isBool($0) && $0 == 1 } == true
                  && minimumCode >= 1 && minimumCode <= versionCode, "Update Swarm to use this service.")
        let endpoints = try J.strs(body, "apiEndpoints")
        try J.req((1...3).contains(endpoints.count) && Set(endpoints).count == endpoints.count)
        for s in endpoints + [try J.str(body, "publicUrl"), try J.str(body, "webOrigin")] {
            let u = URLComponents(string: s)
            try J.req(s.count <= 300 && !(u?.host ?? "").isEmpty && u?.user == nil && u?.password == nil && u?.query == nil && u?.fragment == nil)
            try J.req(u?.scheme == "https" || (environment == "development" && u?.scheme == "http" && ["localhost", "127.0.0.1", "::1"].contains(u?.host)),
                      "An unsafe service address was refused.")
        }
        let keys = try J.objs(body, "receiptKeys")
        try J.req((1...20).contains(keys.count) && keys.filter { $0["status"] as? String == "ACTIVE" }.count == 1
                  && Set(try keys.map { try J.str($0, "keyId") }).count == keys.count)
        for k in keys {
            try J.exact(k, ["keyId", "publicKey", "status", "signingFrom", "signingUntil", "verifyUntil"])
            let jwk = try J.obj(k, "publicKey"); try ChatRules.publicKey(jwk)
            try J.req(try J.str(k, "keyId") == ChatRules.hash(jwk) && ["ACTIVE", "RETIRED", "REVOKED"].contains(try J.str(k, "status")))
            let from = try Instant.parse(try J.str(k, "signingFrom")), until = try Instant.parse(try J.str(k, "signingUntil"))
            try J.req(from < until && until <= (try Instant.parse(try J.str(k, "verifyUntil"))))
        }
        let flags = try J.obj(body, "features"); try J.exact(flags, ["nearby", "localCalls", "largeFiles"])
        for f in flags.keys { _ = try J.bool(flags, f) }
        return value
    }

    /// API base URLs of a verified config, in preference order (Android appends "/api/v1" + path after trimming "/").
    public static func apiEndpoints(_ verified: JSON) -> [String] { J.optStrs((verified["body"] as? JSON) ?? [:], "apiEndpoints") }
    /// Feature flags of a verified config: nearby, localCalls, largeFiles.
    public static func features(_ verified: JSON) -> [String: Bool] { (((verified["body"] as? JSON)?["features"] as? JSON) ?? [:]).compactMapValues { $0 as? Bool } }
    public static func version(_ verified: JSON) -> Int? { (try? J.int((verified["body"] as? JSON) ?? [:], "version")) }
}
