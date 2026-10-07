import CryptoKit
import Foundation
import JOSESwift

public enum ChatCryptoError: Error { case invalidJSON, unsupportedNumber, invalidKey, invalidSignature, invalidCiphertext, contextMismatch }

/// RFC 8785 (JCS) canonical JSON, matching `canonicalize` (TypeScript) and `Protocol.canonical` (Kotlin).
/// Swarm's signed documents use strings, integers, booleans, null, arrays and objects only.
public enum Canonical {
    public static func data(_ value: Any) throws -> Data {
        var out = ""
        try write(value, into: &out)
        return Data(out.utf8)
    }
    private static func write(_ value: Any, into out: inout String) throws {
        switch value {
        case is NSNull: out += "null"
        case let s as String: string(s, into: &out)
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { out += n.boolValue ? "true" : "false"; return }
            let d = n.doubleValue
            guard d.isFinite, d == d.rounded(), abs(d) <= 9_007_199_254_740_991 else { throw ChatCryptoError.unsupportedNumber }
            out += String(Int64(d))
        case let a as [Any]:
            out += "["
            for (i, v) in a.enumerated() { if i > 0 { out += "," }; try write(v, into: &out) }
            out += "]"
        case let o as [String: Any]:
            out += "{"
            // JCS orders keys by their UTF-16 code units.
            let keys = o.keys.sorted { Array($0.utf16).lexicographicallyPrecedes(Array($1.utf16)) }
            for (i, k) in keys.enumerated() {
                if i > 0 { out += "," }
                string(k, into: &out); out += ":"
                try write(o[k]!, into: &out)
            }
            out += "}"
        default: throw ChatCryptoError.invalidJSON
        }
    }
    private static func string(_ s: String, into out: inout String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if u.value < 0x20 { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
            }
        }
        out += "\""
    }
}

/// Swarm chat signatures, identities and JWE, interoperable with the TypeScript and Android clients.
public enum ChatCrypto {
    public static func base64url(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
    public static func unbase64url(_ s: String) throws -> Data {
        guard s.range(of: "^[A-Za-z0-9_-]*$", options: .regularExpression) != nil else { throw ChatCryptoError.invalidJSON }
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        t += String(repeating: "=", count: (4 - t.count % 4) % 4)
        guard let d = Data(base64Encoded: t) else { throw ChatCryptoError.invalidJSON }
        return d
    }
    public static func sha256Hex(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }

    /// A public P-256 JWK ({kty, crv, x, y}) as a CryptoKit signing key.
    public static func signingKey(_ jwk: [String: Any]) throws -> P256.Signing.PublicKey {
        guard Set(jwk.keys) == ["kty", "crv", "x", "y"], jwk["kty"] as? String == "EC", jwk["crv"] as? String == "P-256",
              let x = jwk["x"] as? String, let y = jwk["y"] as? String else { throw ChatCryptoError.invalidKey }
        let xd = try unbase64url(x), yd = try unbase64url(y)
        guard xd.count == 32, yd.count == 32 else { throw ChatCryptoError.invalidKey }
        do { return try P256.Signing.PublicKey(x963Representation: Data([4]) + xd + yd) } catch { throw ChatCryptoError.invalidKey }
    }
    /// Participant ID: SHA-256 of the canonical public signing JWK.
    public static func participantID(_ publicKey: [String: Any]) throws -> String {
        _ = try signingKey(publicKey)
        return sha256Hex(try Canonical.data(publicKey))
    }
    /// Verifies `{body, signature}`: a raw 64-byte P-256 signature over canonical(body).
    public static func verify(_ signed: [String: Any], publicKey: [String: Any]) throws -> Bool {
        guard Set(signed.keys) == ["body", "signature"], let body = signed["body"] as? [String: Any],
              let sig = signed["signature"] as? String, sig.count == 86 else { throw ChatCryptoError.invalidSignature }
        guard let signature = try? P256.Signing.ECDSASignature(rawRepresentation: unbase64url(sig)) else { return false }
        return try signingKey(publicKey).isValidSignature(signature, for: Canonical.data(body))
    }
    /// Signs canonical(body) for a `{body, signature}` document.
    public static func sign(_ body: [String: Any], with key: P256.Signing.PrivateKey) throws -> [String: Any] {
        ["body": body, "signature": base64url(try key.signature(for: Canonical.data(body)).rawRepresentation)]
    }
    public static func directConversationID(_ a: String, _ b: String) throws -> String {
        let hex = "^[a-f0-9]{64}$"
        guard a != b, a.range(of: hex, options: .regularExpression) != nil, b.range(of: hex, options: .regularExpression) != nil
        else { throw ChatCryptoError.invalidKey }
        return "dm:" + sha256Hex(try Canonical.data(["SWARM_DM_V1"] + [a, b].sorted()))
    }

    /// Encrypts canonical(value) as a compact chat JWE: ECDH-ES (direct key agreement, empty apu/apv) to a public
    /// P-256 JWK, or `dir` with a 32-byte key. Header is exactly alg, enc, typ, kid (+ epk), as Android/TypeScript require.
    public static func encrypt(_ value: [String: Any], publicKey: [String: Any]? = nil, symmetricKey: Data? = nil, kid: String) throws -> String {
        var header: [String: Any] = ["enc": "A256GCM", "typ": "SWARM_CHAT_V1", "kid": kid]
        let cek: CryptoKit.SymmetricKey
        if let symmetricKey {
            guard symmetricKey.count == 32 else { throw ChatCryptoError.invalidKey }
            header["alg"] = "dir"; cek = CryptoKit.SymmetricKey(data: symmetricKey)
        } else {
            guard let jwk = publicKey else { throw ChatCryptoError.invalidKey }
            let recipient = try P256.KeyAgreement.PublicKey(x963Representation: signingKey(jwk).x963Representation)
            let ephemeral = P256.KeyAgreement.PrivateKey()
            let z = try ephemeral.sharedSecretFromKeyAgreement(with: recipient).withUnsafeBytes { Data($0) }
            // RFC 7518 §4.6.2 Concat KDF, one SHA-256 round: AlgorithmID = "A256GCM", empty PartyU/VInfo, keydatalen 256.
            func be32(_ n: Int) -> Data { withUnsafeBytes(of: UInt32(n).bigEndian) { Data($0) } }
            let alg = Data("A256GCM".utf8)
            let info = be32(1) + z + be32(alg.count) + alg + be32(0) + be32(0) + be32(256)
            cek = CryptoKit.SymmetricKey(data: Data(SHA256.hash(data: info)))
            let x963 = ephemeral.publicKey.x963Representation
            header["alg"] = "ECDH-ES"
            header["epk"] = ["kty": "EC", "crv": "P-256", "x": base64url(x963[1..<33]), "y": base64url(x963[33..<65])]
        }
        let protected = base64url(try Canonical.data(header))
        let sealed = try AES.GCM.seal(Canonical.data(value), using: cek, nonce: AES.GCM.Nonce(), authenticating: Data(protected.utf8))
        return [protected, "", base64url(Data(sealed.nonce)), base64url(sealed.ciphertext), base64url(sealed.tag)].joined(separator: ".")
    }

    /// Decrypts a chat JWE (ECDH-ES with a private JWK, or `dir` with a 32-byte key), enforcing the same
    /// header rules as Android/TypeScript: exact fields, A256GCM, typ SWARM_CHAT_V1 and the expected kid.
    public static func decrypt(_ compact: String, privateKey: [String: Any]? = nil, symmetricKey: Data? = nil, kid: String) throws -> [String: Any] {
        let parts = compact.split(separator: ".", omittingEmptySubsequences: false)
        guard compact.count <= 22000, parts.count == 5,
              let header = try JSONSerialization.jsonObject(with: unbase64url(String(parts[0]))) as? [String: Any]
        else { throw ChatCryptoError.invalidCiphertext }
        let algorithm = symmetricKey != nil ? "dir" : "ECDH-ES"
        let fields: Set<String> = algorithm == "ECDH-ES" ? ["alg", "enc", "typ", "kid", "epk"] : ["alg", "enc", "typ", "kid"]
        guard Set(header.keys) == fields, header["alg"] as? String == algorithm, header["enc"] as? String == "A256GCM",
              header["typ"] as? String == "SWARM_CHAT_V1", header["kid"] as? String == kid else { throw ChatCryptoError.contextMismatch }
        if algorithm == "ECDH-ES" {
            guard let epk = header["epk"] as? [String: Any] else { throw ChatCryptoError.invalidCiphertext }
            _ = try signingKey(epk) // a valid public P-256 point
        }
        let jwe = try JWE(compactSerialization: compact)
        let decrypter: Decrypter?
        if let symmetricKey {
            guard symmetricKey.count == 32 else { throw ChatCryptoError.invalidKey }
            decrypter = Decrypter(keyManagementAlgorithm: .direct, contentEncryptionAlgorithm: .A256GCM, decryptionKey: symmetricKey)
        } else {
            guard let jwk = privateKey, jwk["kty"] as? String == "EC", jwk["crv"] as? String == "P-256",
                  let x = jwk["x"] as? String, let y = jwk["y"] as? String, let d = jwk["d"] as? String
            else { throw ChatCryptoError.invalidKey }
            let key = try ECPrivateKey(crv: "P-256", x: x, y: y, privateKey: d)
            decrypter = Decrypter(keyManagementAlgorithm: .ECDH_ES, contentEncryptionAlgorithm: .A256GCM, decryptionKey: key)
        }
        guard let decrypter else { throw ChatCryptoError.invalidKey }
        let payload: Payload
        do { payload = try jwe.decrypt(using: decrypter) } catch { throw ChatCryptoError.invalidCiphertext }
        guard payload.data().count <= 16000, let value = try JSONSerialization.jsonObject(with: payload.data()) as? [String: Any]
        else { throw ChatCryptoError.invalidCiphertext }
        return value
    }
}
