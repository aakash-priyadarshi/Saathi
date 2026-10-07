import CryptoKit
import Foundation
import Security
import SwarmCore

/// Bucketed JSON records, one file each, mirroring Android `SecureStore` buckets.
/// Files use iOS Data Protection (encrypted until first unlock), so nearby work continues while locked.
@MainActor final class Store {
    private let root: URL
    private var cache: [String: [String: JSON]] = [:]

    init(name: String = "swarm-staging") {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = base.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var r = root; try? r.setResourceValues(values)
    }
    private func folder(_ bucket: String) -> URL { root.appendingPathComponent(bucket, isDirectory: true) }
    private func file(_ bucket: String, _ id: String) -> URL { folder(bucket).appendingPathComponent(ChatCrypto.base64url(Data(id.utf8)) + ".json") }

    private func load(_ bucket: String) -> [String: JSON] {
        if let hit = cache[bucket] { return hit }
        var rows: [String: JSON] = [:]
        let files = (try? FileManager.default.contentsOfDirectory(at: folder(bucket), includingPropertiesForKeys: nil)) ?? []
        for url in files where url.pathExtension == "json" {
            guard let data = try? Data(contentsOf: url), let row = try? JSONSerialization.jsonObject(with: data) as? JSON,
                  let id = try? String(decoding: ChatCrypto.unbase64url(url.deletingPathExtension().lastPathComponent), as: UTF8.self) else { continue }
            rows[id] = row
        }
        cache[bucket] = rows
        return rows
    }
    func get(_ bucket: String, _ id: String) -> JSON? { load(bucket)[id] }
    func all(_ bucket: String) -> [JSON] { Array(load(bucket).values) }
    func count(_ bucket: String) -> Int { load(bucket).count }
    func put(_ bucket: String, _ id: String, _ value: JSON) throws {
        _ = load(bucket)
        try FileManager.default.createDirectory(at: folder(bucket), withIntermediateDirectories: true)
        let data = try JSONSerialization.data(withJSONObject: value)
        try data.write(to: file(bucket, id), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        cache[bucket]![id] = value
    }
    func remove(_ bucket: String, _ id: String) {
        _ = load(bucket)
        try? FileManager.default.removeItem(at: file(bucket, id))
        cache[bucket]![id] = nil
    }
}

/// The two identity keys live in the Keychain, this device only, readable after first unlock.
enum Keychain {
    private static let service = "org.cjp.swarm.identity"
    private static func read(_ account: String) -> Data? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                    kSecAttrAccount as String: account, kSecReturnData as String: true]
        var out: AnyObject?
        return SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess ? out as? Data : nil
    }
    private static func write(_ account: String, _ data: Data) throws {
        let item: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                                   kSecAttrAccount as String: account, kSecValueData as String: data,
                                   kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw ChatRuleError("Could not save this phone's identity (\(status)).") }
    }
    /// Loads this phone's identity, creating it once. A missing half is never silently replaced.
    static func identity() throws -> ChatIdentity {
        switch (read("signing"), read("encryption")) {
        case let (s?, e?):
            return ChatIdentity(signing: try P256.Signing.PrivateKey(rawRepresentation: s),
                                encryption: try P256.KeyAgreement.PrivateKey(rawRepresentation: e))
        case (nil, nil):
            let created = ChatIdentity()
            try write("signing", created.signing.rawRepresentation)
            try write("encryption", created.encryption.rawRepresentation)
            return created
        default:
            throw ChatRuleError("This phone's chat identity is incomplete. Saved chats were not reset.")
        }
    }
}
