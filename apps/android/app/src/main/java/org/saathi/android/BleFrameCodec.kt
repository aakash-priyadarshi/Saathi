package org.saathi.android

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.UUID
import java.util.zip.CRC32

internal enum class BlePacketKind(val wire: Int) { DATA(1), ACK(2), HELLO(3), PAIR_CODE(4), APPROVE(5), DENY(6), CANCEL(7) }

internal data class BlePacket(
    val kind: BlePacketKind,
    val frameId: UUID,
    val objectId: ByteArray,
    val index: Int,
    val total: Int,
    val totalBytes: Int,
    val digest: ByteArray,
    val payload: ByteArray,
)

/** Bounded GATT framing. The JSON frame ID remains authoritative in the higher-level payload. */
internal object BleFrameCodec {
    const val VERSION = 1
    const val MAX_FRAME_BYTES = 8192
    const val MAX_CHUNKS = 128
    const val MAX_ACTIVE_ASSEMBLIES = 4
    const val ASSEMBLY_TIMEOUT_MS = 20_000L
    const val COMPLETED_RETENTION_MS = 10 * 60_000L
    const val HEADER_AND_CRC_BYTES = 68
    // At least 64 payload bytes/ATT packet are needed to carry an 8 KiB frame
    // within the bounded 128-fragment limit.
    const val MIN_MTU = HEADER_AND_CRC_BYTES + 3 + 64
    private const val MAGIC = 0x5357524d // SWRM
    private const val SHA_BYTES = 16
    private const val OBJECT_ID_BYTES = 16

    data class AssemblyResult(val complete: ByteArray? = null, val duplicateComplete: Boolean = false)

    fun objectIdHash(objectId: String): ByteArray = digest(objectId.toByteArray(Charsets.UTF_8), OBJECT_ID_BYTES)

    fun fragment(raw: ByteArray, objectId: String, mtu: Int, frameId: UUID = UUID.randomUUID()): List<ByteArray> {
        require(raw.size in 1..MAX_FRAME_BYTES) { "BLE frame exceeds the bounded message size" }
        val payloadLimit = mtu - 3 - HEADER_AND_CRC_BYTES
        require(payloadLimit > 0) { "Bluetooth link MTU is too small for Swarm frames" }
        val count = (raw.size + payloadLimit - 1) / payloadLimit
        require(count in 1..MAX_CHUNKS) { "BLE frame needs too many fragments" }
        val idHash = objectIdHash(objectId)
        val checksum = digest(raw, SHA_BYTES)
        return (0 until count).map { index ->
            val start = index * payloadLimit
            encode(BlePacket(BlePacketKind.DATA, frameId, idHash, index, count, raw.size, checksum,
                raw.copyOfRange(start, minOf(raw.size, start + payloadLimit))))
        }
    }

    fun control(kind: BlePacketKind, payload: ByteArray = byteArrayOf(), frameId: UUID = UUID.randomUUID()): ByteArray {
        require(kind != BlePacketKind.DATA && kind != BlePacketKind.ACK)
        require(payload.size <= 128)
        return encode(BlePacket(kind, frameId, ByteArray(OBJECT_ID_BYTES), 0, 1, payload.size,
            digest(payload, SHA_BYTES), payload))
    }

    fun ack(frameId: UUID, objectId: ByteArray): ByteArray = encode(
        BlePacket(BlePacketKind.ACK, frameId, objectId.copyOf(), 0, 1, 0, ByteArray(SHA_BYTES), byteArrayOf()),
    )

    fun decode(raw: ByteArray, mtu: Int): BlePacket {
        require(raw.size in HEADER_AND_CRC_BYTES..(mtu - 3)) { "BLE packet size is invalid" }
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        require(buffer.int == MAGIC) { "BLE packet magic is invalid" }
        require(buffer.get().toInt() and 0xff == VERSION) { "BLE protocol version is unsupported" }
        val kindValue = buffer.get().toInt() and 0xff
        val kind = BlePacketKind.entries.firstOrNull { it.wire == kindValue }
            ?: error("BLE packet kind is unsupported")
        val frameId = UUID(buffer.long, buffer.long)
        val objectId = ByteArray(OBJECT_ID_BYTES).also(buffer::get)
        val index = buffer.short.toInt() and 0xffff
        val total = buffer.short.toInt() and 0xffff
        val totalBytes = buffer.int
        val digest = ByteArray(SHA_BYTES).also(buffer::get)
        val payloadSize = buffer.short.toInt() and 0xffff
        require(payloadSize == raw.size - HEADER_AND_CRC_BYTES) { "BLE payload length does not match" }
        val payload = ByteArray(payloadSize).also(buffer::get)
        val suppliedCrc = buffer.int.toLong() and 0xffffffffL
        val crc = CRC32().apply { update(raw, 0, raw.size - 4) }.value
        require(suppliedCrc == crc) { "BLE packet integrity check failed" }
        when (kind) {
            BlePacketKind.DATA -> {
                require(totalBytes in 1..MAX_FRAME_BYTES && total in 1..MAX_CHUNKS && index in 0 until total)
                require(payload.isNotEmpty() && payload.size <= totalBytes)
            }
            BlePacketKind.ACK -> require(index == 0 && total == 1 && totalBytes == 0 && payload.isEmpty())
            else -> require(index == 0 && total == 1 && totalBytes == payload.size && payload.size <= 128)
        }
        return BlePacket(kind, frameId, objectId, index, total, totalBytes, digest, payload)
    }

    private fun encode(packet: BlePacket): ByteArray {
        require(packet.objectId.size == OBJECT_ID_BYTES && packet.digest.size == SHA_BYTES)
        require(packet.payload.size <= 0xffff)
        val raw = ByteBuffer.allocate(HEADER_AND_CRC_BYTES + packet.payload.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).put(VERSION.toByte()).put(packet.kind.wire.toByte())
            .putLong(packet.frameId.mostSignificantBits).putLong(packet.frameId.leastSignificantBits)
            .put(packet.objectId).putShort(packet.index.toShort()).putShort(packet.total.toShort())
            .putInt(packet.totalBytes).put(packet.digest).putShort(packet.payload.size.toShort()).put(packet.payload).array()
        val crc = CRC32().apply { update(raw, 0, raw.size - 4) }.value.toInt()
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).putInt(raw.size - 4, crc)
        return raw
    }

    private fun digest(bytes: ByteArray, count: Int) = MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(count)
}

/** At most four 8 KiB objects are retained and incomplete transfers expire without ACK. */
internal class BleReassembler(private val now: () -> Long = System::currentTimeMillis) {
    private data class Assembly(
        val objectId: ByteArray,
        val total: Int,
        val totalBytes: Int,
        val digest: ByteArray,
        val createdAt: Long,
        val parts: Array<ByteArray?>,
        var receivedBytes: Int = 0,
    )
    private val active = LinkedHashMap<UUID, Assembly>()
    private val completed = LinkedHashMap<UUID, Long>()

    @Synchronized fun accept(packet: BlePacket): BleFrameCodec.AssemblyResult {
        require(packet.kind == BlePacketKind.DATA)
        expire()
        if (packet.frameId in completed) return BleFrameCodec.AssemblyResult(duplicateComplete = true)
        var assembly = active[packet.frameId]
        if (assembly == null) {
            require(active.size < BleFrameCodec.MAX_ACTIVE_ASSEMBLIES) { "Too many BLE transfers are active" }
            assembly = Assembly(packet.objectId.copyOf(), packet.total, packet.totalBytes, packet.digest.copyOf(), now(), arrayOfNulls(packet.total))
            active[packet.frameId] = assembly
        }
        require(assembly.objectId.contentEquals(packet.objectId) && assembly.total == packet.total &&
            assembly.totalBytes == packet.totalBytes && assembly.digest.contentEquals(packet.digest)) { "BLE fragment metadata changed" }
        val existing = assembly.parts[packet.index]
        if (existing != null) {
            require(existing.contentEquals(packet.payload)) { "Duplicate BLE fragment content changed" }
            return BleFrameCodec.AssemblyResult()
        }
        require(assembly.receivedBytes + packet.payload.size <= assembly.totalBytes) { "BLE transfer exceeds its declared size" }
        assembly.parts[packet.index] = packet.payload.copyOf()
        assembly.receivedBytes += packet.payload.size
        if (assembly.parts.any { it == null }) return BleFrameCodec.AssemblyResult()
        require(assembly.receivedBytes == assembly.totalBytes) { "BLE transfer size does not match" }
        val result = ByteArray(assembly.totalBytes)
        var offset = 0
        assembly.parts.forEach { part -> part!!.copyInto(result, offset).also { offset += part.size } }
        require(MessageDigest.getInstance("SHA-256").digest(result).copyOf(16).contentEquals(assembly.digest)) { "BLE frame hash does not match" }
        active.remove(packet.frameId)
        completed[packet.frameId] = now()
        while (completed.size > 128) completed.remove(completed.keys.first())
        return BleFrameCodec.AssemblyResult(complete = result)
    }

    @Synchronized fun expire() {
        val time = now()
        active.entries.removeAll { time - it.value.createdAt > BleFrameCodec.ASSEMBLY_TIMEOUT_MS }
        completed.entries.removeAll { time - it.value > BleFrameCodec.COMPLETED_RETENTION_MS }
    }

    @Synchronized fun clear() { active.clear(); completed.clear() }
    @Synchronized fun cancel(frameId: UUID) { active.remove(frameId) }
    @Synchronized fun activeCount() = active.size
}
