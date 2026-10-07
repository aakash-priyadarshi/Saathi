import AVFoundation
import SwarmCore

/// Hold-to-talk: records while the mic is held and returns the clip on release. AAC at 16 kHz mono, 24 kbit/s keeps
/// a 10-second clip near 30 KB, so it crosses even the slow iPhone↔Android Bluetooth link in seconds.
@MainActor final class VoiceRecorder: ObservableObject {
    @Published private(set) var recording = false
    private var recorder: AVAudioRecorder?
    private var started = Date()
    private let url = FileManager.default.temporaryDirectory.appendingPathComponent("hold-to-talk.m4a")

    func start() async -> Bool {
        let allowed = await withCheckedContinuation { done in AVAudioSession.sharedInstance().requestRecordPermission { done.resume(returning: $0) } }
        guard allowed else { return false }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker, .allowBluetooth])
            try AVAudioSession.sharedInstance().setActive(true)
            let r = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 16000, AVNumberOfChannelsKey: 1,
                                                             AVEncoderBitRateKey: 24000])
            guard r.record(forDuration: 120) else { return false }
            recorder = r; started = Date(); recording = true
            return true
        } catch { return false }
    }
    /// The finished clip, or nil for a tap shorter than half a second.
    func stop() -> Data? {
        guard let r = recorder else { return nil }
        r.stop(); recorder = nil; recording = false
        defer { try? FileManager.default.removeItem(at: url) }
        guard Date().timeIntervalSince(started) >= 0.5 else { return nil }
        return try? Data(contentsOf: url)
    }
}

/// Plays one voice message at a time (tapped, or automatically when it arrives in the open conversation).
@MainActor final class VoicePlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = VoicePlayer()
    @Published private(set) var playing: String?
    private var player: AVAudioPlayer?

    func toggle(_ id: String, _ data: Data?) { playing == id ? stop() : play(id, data) }
    func play(_ id: String, _ data: Data?) {
        guard let data, let p = try? AVAudioPlayer(data: data) else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker, .allowBluetooth])
        try? AVAudioSession.sharedInstance().setActive(true)
        player?.stop(); p.delegate = self; player = p; playing = id; p.play()
    }
    func stop() { player?.stop(); player = nil; playing = nil }
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) { Task { @MainActor in self.stop() } }
}

extension ChatEngine {
    func sendVoice(_ conversationID: String, clip: Data) async throws {
        try await sendMedia(conversationID, plain: clip, name: "Voice message.m4a", mime: "audio/mp4", format: "VOICE")
    }
    /// The decrypted bytes of a held voice message, if they have arrived.
    func voice(_ attachmentID: String) -> Data? { media.plain(attachmentID) }
}
