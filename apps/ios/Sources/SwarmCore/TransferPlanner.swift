import Foundation

public enum TransferContent: Sendable { case text, control, savedMedia, liveAudio, liveVideo }
public enum TransferDecision: Equatable, Sendable { case send, waitForPeer, waitForWifi, verifyPeer, unsupportedSize }

public struct PeerCapabilities: Sendable {
    public let participantID: String?
    public let confirmed: Bool
    public let maxFrameBytes: Int
    public let files: Bool
    public let audio: Bool
    public let video: Bool
    public init(participantID: String?, confirmed: Bool, maxFrameBytes: Int, files: Bool, audio: Bool, video: Bool) {
        self.participantID = participantID; self.confirmed = confirmed; self.maxFrameBytes = maxFrameBytes
        self.files = files; self.audio = audio; self.video = video
    }
}

public enum WireLimits {
    public static let channelMembers = 200
    public static let joinedChannels = 16
    public static let policyBytes = 384 * 1024
    public static let assembledChatBytes = 524288
    public static let chatSyncBytes = 900000
    public static let chatReplyBytes = 1500000
    public static let liveTurnSeconds = 30
}

/// Routing has no delivery side effects. A successful write is never a signed recipient receipt.
public enum TransferPlanner {
    public static func decide(_ content: TransferContent, encodedFrameBytes: Int, expectedPeer: String, peer: PeerCapabilities?) -> TransferDecision {
        guard let peer else { return .waitForPeer }
        guard peer.confirmed, peer.participantID == expectedPeer else { return .verifyPeer }
        guard encodedFrameBytes > 0 else { return .unsupportedSize }
        switch content {
        case .savedMedia: return peer.files ? .send : .waitForWifi
        case .liveAudio: return peer.audio ? .send : .waitForWifi
        case .liveVideo: return peer.video ? .send : .waitForWifi
        case .text, .control:
            return encodedFrameBytes <= peer.maxFrameBytes ? .send : .unsupportedSize
        }
    }
}
