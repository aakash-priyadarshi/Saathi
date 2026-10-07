import XCTest
@testable import SwarmCore

final class SwarmCoreTests: XCTestCase {
    func testBluetoothFallbackQueuesMediaAndNeverRoutesToAnotherChat() {
        let ble = PeerCapabilities(participantID: "person-a", confirmed: true, maxFrameBytes: 8192, files: false, audio: false, video: false)
        XCTAssertEqual(TransferPlanner.decide(.text, encodedFrameBytes: 1000, expectedPeer: "person-a", peer: ble), .send)
        XCTAssertEqual(TransferPlanner.decide(.savedMedia, encodedFrameBytes: 4000, expectedPeer: "person-a", peer: ble), .waitForWifi)
        XCTAssertEqual(TransferPlanner.decide(.liveAudio, encodedFrameBytes: 1000, expectedPeer: "person-a", peer: ble), .waitForWifi)
        XCTAssertEqual(TransferPlanner.decide(.text, encodedFrameBytes: 1000, expectedPeer: "person-b", peer: ble), .verifyPeer)
        XCTAssertEqual(TransferPlanner.decide(.text, encodedFrameBytes: 1000, expectedPeer: "person-a", peer: nil), .waitForPeer)
    }
    func testAndroidCompatiblePacketAndTampering() throws {
        let url = Bundle.module.url(forResource: "ble-packet", withExtension: "json", subdirectory: "Fixtures")!
        let fixture = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: String]
        let bytes = Data(base64Encoded: fixture["packet"]!)!
        let payload = Data(base64Encoded: fixture["payload"]!)!
        let packet = try BlePacket.decode(bytes, maximumPacketBytes: 512)
        XCTAssertEqual(packet.payload, payload)
        XCTAssertEqual(packet.frameID.uuidString.lowercased(), fixture["frameID"]!)
        XCTAssertTrue(packet.verifiesCompleteFrame(payload, objectID: fixture["objectID"]!))
        XCTAssertFalse(packet.verifiesCompleteFrame(payload, objectID: "other"))
        var changed = bytes; changed[64] ^= 1
        XCTAssertThrowsError(try BlePacket.decode(changed, maximumPacketBytes: 512))
        XCTAssertThrowsError(try BlePacket.decode(bytes, maximumPacketBytes: 20))
    }
}
