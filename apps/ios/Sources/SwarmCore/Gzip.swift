import Compression
import Foundation

/// Minimal RFC 1952 reader for Android `GZIPOutputStream` invitation links (Apple's Compression does raw DEFLATE only).
enum Gzip {
    static func inflate(_ data: Data, limit: Int) throws -> Data {
        let b = [UInt8](data)
        try J.req(b.count >= 18 && b[0] == 0x1f && b[1] == 0x8b && b[2] == 8, "Invitation is incomplete.")
        let flags = b[3]; var i = 10
        if flags & 4 != 0 { try J.req(i + 2 <= b.count); i += 2 + Int(b[i]) | Int(b[i + 1]) << 8 }
        if flags & 8 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }
        if flags & 16 != 0 { while i < b.count && b[i] != 0 { i += 1 }; i += 1 }
        if flags & 2 != 0 { i += 2 }
        try J.req(i < b.count - 8, "Invitation is incomplete.")
        let size = Int(b[b.count - 4]) | Int(b[b.count - 3]) << 8 | Int(b[b.count - 2]) << 16 | Int(b[b.count - 1]) << 24
        try J.req(size > 0 && size <= limit, "Invitation is too large.")
        let deflated = Array(b[i..<(b.count - 8)])
        var output = [UInt8](repeating: 0, count: size + 1)
        let written = compression_decode_buffer(&output, output.count, deflated, deflated.count, nil, COMPRESSION_ZLIB)
        try J.req(written == size, "Invitation is incomplete.")
        return Data(output[0..<written])
    }
}
