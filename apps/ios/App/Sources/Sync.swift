import Foundation
import SwarmCore

/// Online chat sync, ported from Android `ChatRepository.sync`: one signed CHAT_SYNC exchange with the Swarm server
/// carries this phone's unsent messages, receipts, joins, actions and reports, and brings back what it missed.
extension ChatEngine {
    // ponytail: staging endpoint is fixed here; Android also verifies the signed service config before using it.
    static let api = URL(string: "https://api.swarm.cockroachjantaparty.org/api/v1/chat/sync")!
    static let webOrigin = "https://swarm.cockroachjantaparty.org"

    func sync() async throws {
        guard hasProfile else { return }
        prune(); renewOwned()
        let t = now(), held = messages()
        var req: JSON = ["v": 1, "kind": "CHAT_SYNC", "id": UUID().uuidString.lowercased(), "profile": profile, "issuedAt": Instant.string(t),
                          "channelIds": Array(conversations().filter { $0["type"] as? String == "CHANNEL" }
                            .sorted { ($0["joined"] as? Bool == true || $0["pendingJoin"] as? Bool == true ? 0 : 1) < ($1["joined"] as? Bool == true || $1["pendingJoin"] as? Bool == true ? 0 : 1) }
                            .prefix(16).compactMap { $0["id"] as? String }),
                          "knownMessages": Array(held.sorted { ($0["receivedAt"] as? String ?? "") > ($1["receivedAt"] as? String ?? "") }.prefix(500).compactMap { $0["id"] as? String }),
                          "receiptMessageIds": Array(held.filter { $0["owned"] as? Bool == true && $0["readAt"] == nil }
                            .sorted { ($0["receivedAt"] as? String ?? "") > ($1["receivedAt"] as? String ?? "") }.prefix(50).compactMap { $0["id"] as? String }),
                          "peers": [JSON](), "policies": [JSON](), "messages": [JSON](), "receipts": [JSON](), "joins": [JSON](), "actions": [JSON](),
                          "blocks": Array(store.all("chat-blocks").prefix(100).compactMap { $0["id"] as? String }),
                          "reports": Array(store.all("chat-reports").prefix(8))]
        func add(_ field: String, _ value: JSON, _ limit: Int) {
            var list = req[field] as? [JSON] ?? []
            guard list.count < limit else { return }
            list.append(value); let previous = req[field]; req[field] = list
            if ((try? Canonical.data(req).count) ?? .max) > 82000 { req[field] = previous }
        }
        let pending = held.filter { $0["serverSaved"] as? Bool != true }
            .sorted { (attachment(of: $0) == nil ? 0 : 1, $0["receivedAt"] as? String ?? "") < (attachment(of: $1) == nil ? 0 : 1, $1["receivedAt"] as? String ?? "") }
        let needed = Set(pending.compactMap { envelopeBody($0)["recipientId"] as? String })
        for contact in contacts().sorted(by: { (needed.contains($0["id"] as? String ?? "") ? 0 : 1) < (needed.contains($1["id"] as? String ?? "") ? 0 : 1) }) {
            guard let p = contact["profile"] as? JSON, let id = contact["id"] as? String else { continue }
            if store.get("chat-server-contacts", id)?["hash"] as? String != hash(p) { add("peers", p, 16) }
        }
        let unsavedActions = store.all("chat-actions").filter { $0["serverSaved"] as? Bool != true && $0["rejected"] as? Bool != true }
        let actionPolicies = unsavedActions.compactMap { store.get("chat-policy-history", body($0["envelope"] as? JSON ?? [:])["policyHash"] as? String ?? "")?["policy"] as? JSON }
        var seen = Set<String>()
        for p in (actionPolicies + policies()).filter({ seen.insert(hash($0)).inserted }).sorted(by: { (body($0)["version"] as? Int ?? 0) < (body($1)["version"] as? Int ?? 0) }) {
            let b = body(p)
            if time(b["expiresAt"]) > t && (ChatRules.member(p, selfID) || isOwner(p)) && store.get("chat-server-policies", hash(p)) == nil { add("policies", p, 8) }
        }
        for record in pending {
            guard let envelope = record["envelope"] as? JSON else { continue }
            let b = body(envelope)
            if let recipient = b["recipientId"] as? String {
                if store.get("chat-server-contacts", recipient) == nil && !((req["peers"] as? [JSON]) ?? []).contains(where: { ChatRules.participant($0) == recipient }) { continue }
            } else if let policyHash = b["policyHash"] as? String, store.get("chat-server-policies", policyHash) == nil
                        && !((req["policies"] as? [JSON]) ?? []).contains(where: { hash($0) == policyHash }) { continue }
            add("messages", envelope, 20)
        }
        for r in store.all("chat-receipts") where r["serverSaved"] as? Bool != true {
            if let receipt = r["receipt"] as? JSON, ChatRules.participant(body(receipt)["recipient"] as? JSON ?? [:]) == selfID { add("receipts", receipt, 30) }
        }
        for j in store.all("chat-joins") { if let request = j["request"] as? JSON, time(body(request)["expiresAt"]) > t { add("joins", request, 8) } }
        for p in policies() where ChatRules.capabilities(p, selfID)["canManageMembers"] == true {
            for j in joinRequests(body(p)["id"] as? String ?? "") { if let request = j["request"] as? JSON { add("joins", request, 8) } }
        }
        for a in unsavedActions { if let envelope = a["envelope"] as? JSON, time(body(envelope)["expiresAt"]) > t { add("actions", envelope, 8) } }

        var request = URLRequest(url: Self.api, timeoutInterval: 30)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(Self.webOrigin, forHTTPHeaderField: "Origin") // the server only accepts writes from its own origin
        request.setValue(UUID().uuidString.lowercased(), forHTTPHeaderField: "Idempotency-Key")
        request.setValue(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0", forHTTPHeaderField: "X-Swarm-App-Version")
        request.httpBody = try JSONSerialization.data(withJSONObject: try me.sign(req))
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 || (response as? HTTPURLResponse)?.statusCode == 201,
              data.count <= 2 * 1024 * 1024, let r = try JSONSerialization.jsonObject(with: data) as? JSON, r["v"] as? Int == 1 else {
            NSLog("Swarm: sync failed status=%d %@", (response as? HTTPURLResponse)?.statusCode ?? -1, String(decoding: data.prefix(300), as: UTF8.self))
            throw ChatRuleError("Swarm could not be reached. Messages stay saved and travel nearby.")
        }
        NSLog("Swarm: sync ok accepted=%d messages=%d policies=%d", (r["accepted"] as? [Any])?.count ?? 0, (r["messages"] as? [Any])?.count ?? 0, (r["policies"] as? [Any])?.count ?? 0)
        let strs = { (k: String) in (r[k] as? [String]) ?? [] }, objs = { (k: String) in (r[k] as? [JSON]) ?? [] }
        for p in (req["peers"] as? [JSON]) ?? [] { let id = ChatRules.participant(p); try? save("chat-server-contacts", id, ["id": id, "hash": hash(p)]) }
        for id in strs("acceptedPolicies") { try? save("chat-server-policies", id, ["id": id]) }
        for id in strs("acceptedReceipts") { if var x = store.get("chat-receipts", id) { x["serverSaved"] = true; try? save("chat-receipts", id, x) } }
        for id in strs("acceptedActions") { if var x = store.get("chat-actions", id) { x["serverSaved"] = true; try? save("chat-actions", id, x) } }
        for x in objs("rejectedActions") {
            if let id = x["id"] as? String, var a = store.get("chat-actions", id) { a["rejected"] = true; a["rejection"] = x["reason"]; try? save("chat-actions", id, a) }
        }
        for p in objs("policies") where store.get("chat-conversations", body(p)["id"] as? String ?? "") != nil { try? applyPolicy(p) }
        for p in objs("historyPolicies") {
            let b = body(p)
            guard let cp = current(b["id"] as? String ?? ""), live(cp),
                  ChatRules.participant(b["owner"] as? JSON ?? [:]) == ChatRules.participant(body(cp)["owner"] as? JSON ?? [:]),
                  (try? ChatRules.policy(p, now: try Instant.parse(b["issuedAt"] as? String ?? ""))) != nil,
                  b["visibility"] as? String == "OPEN" || ChatRules.member(p, selfID) else { continue }
            try? archive(p)
        }
        for id in strs("removed") { if var c = conversation(id) { c["joined"] = false; try? save("chat-conversations", id, c) } }
        for d in objs("joinStates") {
            guard let id = d["channelId"] as? String, var c = conversation(id), c["joined"] as? Bool != true else { continue }
            c["joinStatus"] = d["status"]
            if d["status"] as? String == "REJECTED" { c["pendingJoin"] = false; store.remove("chat-joins", id) }
            try? save("chat-conversations", id, c)
        }
        for id in strs("accepted") { if var m = store.get("chat-messages", id) { m["serverSaved"] = true; try? save("chat-messages", id, m) } }
        for id in strs("acceptedReports") { store.remove("chat-reports", id) }
        for x in objs("rejected") {
            if let id = x["id"] as? String, var m = store.get("chat-messages", id) { m["attention"] = true; m["serverSaved"] = true; try? save("chat-messages", id, m) }
        }
        for j in objs("joins") { try? await handleJoin(j) }
        for m in objs("messages") { try? await receiveMessage(m, hops: 0, server: true) }
        for a in objs("actions") { try? await receiveAction(a, server: true) }
        for x in objs("receipts") { try? receiveReceipt(x) }
        prune(); changed()
        await announce()
    }
}
