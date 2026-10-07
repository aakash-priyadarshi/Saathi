import Foundation
import SwarmCore

/// Long-press message actions and person options, matching Android `ChatRepository` (same buckets and payload fields).
extension ChatEngine {
    func author(_ r: JSON) -> String { ChatRules.participant(envelopeBody(r)["author"] as? JSON ?? [:]) }

    /// What a conversation shows: no delete markers, nothing deleted on this phone.
    func shown(in id: String) -> [JSON] {
        messages(in: id).filter { ($0["payload"] as? JSON)?["deletes"] == nil && store.get("chat-deleted", $0["id"] as? String ?? "") == nil }
    }
    /// Ids deleted for everyone: a delete marker from the message's own author (or one whose original has not arrived).
    func deletedForEveryone(in id: String) -> Set<String> {
        let all = messages(in: id)
        let byID = Dictionary(all.map { ($0["id"] as? String ?? "", $0) }, uniquingKeysWith: { a, _ in a })
        return Set(all.compactMap { m -> String? in
            guard let target = (m["payload"] as? JSON)?["deletes"] as? String else { return nil }
            return byID[target].map { author($0) == author(m) } ?? true ? target : nil
        })
    }
    /// A group admin's HIDE_MESSAGE (latest action wins, else the owner's folded-in moderation list).
    func hidden(_ channelID: String, _ messageID: String) -> Bool {
        if let latest = actions(channelID).last(where: { let b = body($0["envelope"] as? JSON ?? [:]); return b["targetId"] as? String == messageID && ["HIDE_MESSAGE", "RESTORE_MESSAGE"].contains(b["action"] as? String ?? "") }) {
            return body(latest["envelope"] as? JSON ?? [:])["action"] as? String == "HIDE_MESSAGE"
        }
        return ((current(channelID).map(body)?["moderation"] as? JSON)?["hiddenMessages"] as? [String] ?? []).contains(messageID)
    }

    func send(_ conversationID: String, text: String, replyTo: String?, threadRootID: String? = nil) async throws {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        try J.req((1...4000).contains(trimmed.utf16.count), "Write 1 to 4,000 characters.")
        var payload: JSON = ["text": trimmed]
        if let replyTo { payload["replyTo"] = replyTo }
        try await sendPayload(conversationID, payload: payload, format: "TEXT", threadRootID: threadRootID)
    }
    /// Text and photos forward as new messages marked "Forwarded".
    func forward(_ messageID: String, to targets: [String]) async throws {
        guard let record = store.get("chat-messages", messageID), let payload = record["payload"] as? JSON else { throw ChatRuleError("Message is unavailable.") }
        for target in targets {
            if let text = payload["text"] as? String { try await sendPayload(target, payload: ["text": text, "forwarded": true], format: "TEXT") }
            else if let id = attachment(of: record)?["id"] as? String, let image = photo(id) { try await sendPhoto(target, image: image, forwarded: true) }
            else { throw ChatRuleError("Only messages and photos can be forwarded from iPhone.") }
        }
    }
    /// Delete for me: hidden on this phone only (re-synced copies stay hidden); its photo is removed.
    func deleteForMe(_ messageID: String) throws {
        guard let record = store.get("chat-messages", messageID) else { return }
        try save("chat-deleted", messageID, ["id": messageID])
        if let id = attachment(of: record)?["id"] as? String { media.remove(id) }
        changed()
    }
    /// Delete for everyone: a signed SYSTEM message naming the author's own message; every phone hides it.
    func deleteForEveryone(_ messageID: String) async throws {
        guard let record = store.get("chat-messages", messageID), record["owned"] as? Bool == true else { throw ChatRuleError("Only the sender can delete a message for everyone.") }
        try await sendPayload(envelopeBody(record)["conversationId"] as? String ?? "", payload: ["deletes": messageID], format: "SYSTEM",
                              threadRootID: envelopeBody(record)["threadRootId"] as? String)
        if let id = attachment(of: record)?["id"] as? String { media.remove(id) }
        changed()
    }
    /// Delete-for-everyone arrived: drop the deleted message's media when its own author asked.
    func applyDelete(_ payload: JSON, author: String) {
        guard let target = payload["deletes"] as? String, let original = store.get("chat-messages", target), self.author(original) == author,
              let id = attachment(of: original)?["id"] as? String else { return }
        media.remove(id)
    }

    /// Reports wait on this phone and reach the team when Swarm syncs online (Android `chat-reports`).
    func report(message messageID: String? = nil, person personID: String? = nil, reason: String) throws {
        try J.req(["ABUSE", "SPAM", "SAFETY", "OTHER"].contains(reason) && store.count("chat-reports") < 100, "Report queue is full. Connect to send pending reports.")
        let id = UUID().uuidString.lowercased()
        var r: JSON = ["id": id, "reason": reason]
        if let messageID { r["messageId"] = messageID }
        if let personID { r["personId"] = personID }
        try save("chat-reports", id, r); notice = "Report saved. It reaches the team when Swarm is online."
    }
    func toggleBlock(_ personID: String) throws {
        if blocked(personID) { store.remove("chat-blocks", personID) } else { try save("chat-blocks", personID, ["id": personID]) }
        changed()
    }
    func toggleMute(_ conversationID: String) throws {
        guard var c = conversation(conversationID) else { return }
        c["muted"] = !(c["muted"] as? Bool ?? false); try save("chat-conversations", conversationID, c); changed()
    }
    /// Removes a direct conversation's messages from this phone only.
    func clear(_ conversationID: String) {
        for m in messages(in: conversationID) {
            if let id = attachment(of: m)?["id"] as? String { media.remove(id) }
            store.remove("chat-messages", m["id"] as? String ?? "")
        }
        changed()
    }
}
