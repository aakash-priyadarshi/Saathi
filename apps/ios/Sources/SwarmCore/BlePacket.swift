import Foundation
import CryptoKit

public enum BlePacketError: Error { case invalidFrame }

/// Android BleFrameCodec v1 packet decoder. CoreBluetooth discovery/connection is a separate adapter.
public struct BlePacket: Equatable {
    public let kind: UInt8
    public let frameID: UUID
    public let objectIDHash: Data
    public let index: Int
    public let total: Int
    public let totalBytes: Int
    public let digest: Data
    public let payload: Data

    public static func decode(_ data: Data, maximumPacketBytes: Int) throws -> BlePacket {
        let bytes = [UInt8](data)
        guard bytes.count >= 68, bytes.count <= maximumPacketBytes else { throw BlePacketError.invalidFrame }
        func integer(_ offset: Int, _ count: Int) -> UInt32 {
            bytes[offset..<(offset+count)].reduce(0) { ($0 << 8) | UInt32($1) }
        }
        guard integer(0, 4) == 0x5357524d, bytes[4] == 1, (1...7).contains(bytes[5]),
              integer(bytes.count-4, 4) == crc32(Array(bytes.dropLast(4))) else { throw BlePacketError.invalidFrame }
        let length = Int(integer(62, 2)), index = Int(integer(38, 2)), total = Int(integer(40, 2)), size = Int(integer(42, 4))
        guard length == bytes.count-68 else { throw BlePacketError.invalidFrame }
        switch bytes[5] {
        case 1:
            guard (1...8192).contains(size), (1...128).contains(total), (0..<total).contains(index), length > 0, length <= size else { throw BlePacketError.invalidFrame }
        case 2:
            guard index == 0, total == 1, size == 0, length == 0 else { throw BlePacketError.invalidFrame }
        default:
            guard index == 0, total == 1, size == length, length <= 128 else { throw BlePacketError.invalidFrame }
        }
        let id = UUID(uuid: (bytes[6],bytes[7],bytes[8],bytes[9],bytes[10],bytes[11],bytes[12],bytes[13],bytes[14],bytes[15],bytes[16],bytes[17],bytes[18],bytes[19],bytes[20],bytes[21]))
        return BlePacket(kind: bytes[5], frameID: id, objectIDHash: Data(bytes[22..<38]), index: index, total: total, totalBytes: size, digest: Data(bytes[46..<62]), payload: Data(bytes[64..<(64+length)]))
    }

    public func verifiesCompleteFrame(_ data: Data, objectID: String) -> Bool {
        data.count == totalBytes && Data(SHA256.hash(data: data).prefix(16)) == digest &&
            Data(SHA256.hash(data: Data(objectID.utf8)).prefix(16)) == objectIDHash
    }

    private static func crc32(_ bytes: [UInt8]) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in bytes {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = (crc >> 1) ^ ((crc & 1) == 1 ? 0xedb88320 : 0) }
        }
        return crc ^ 0xffffffff
    }
}
