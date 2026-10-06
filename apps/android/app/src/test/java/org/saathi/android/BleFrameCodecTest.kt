package org.saathi.android

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BleFrameCodecTest {
    private fun packets(bytes: ByteArray, mtu: Int = 185, frameId: UUID = UUID.randomUUID()): List<BlePacket> =
        BleFrameCodec.fragment(bytes, "stable-object-42", mtu, frameId)
            .map { BleFrameCodec.decode(it, mtu) }

    @Test fun reassemblesBoundedFramesOutOfOrderAndDeduplicatesCompletedFrames() {
        val expected = ByteArray(5_200) { (it % 239).toByte() }
        val fragments = packets(expected)
        assertTrue(fragments.size > 1)
        val receiver = BleReassembler()
        var completed: ByteArray? = null
        fragments.reversed().forEach { packet ->
            val result = receiver.accept(packet)
            if (result.complete != null) completed = result.complete
        }
        assertArrayEquals(expected, completed)
        assertTrue(receiver.accept(fragments.first()).duplicateComplete)
        assertEquals(0, receiver.activeCount())
    }

    @Test fun missingFragmentsRemainPendingAndRetransmittedDuplicatesAreIdempotent() {
        val expected = ByteArray(1_400) { (it % 173).toByte() }
        val fragments = packets(expected)
        val receiver = BleReassembler()
        fragments.dropLast(1).forEach { receiver.accept(it) }
        assertNull(receiver.accept(fragments.first()).complete)
        assertArrayEquals(expected, receiver.accept(fragments.last()).complete)
    }

    @Test fun rejectsCorruptPacketsMalformedIndexesAndOversizedFrames() {
        val encoded = BleFrameCodec.fragment(byteArrayOf(1, 2, 3), "fixture", 185).single()
        val corrupt = encoded.copyOf().apply { this[lastIndex - 4] = (this[lastIndex - 4].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { BleFrameCodec.decode(corrupt, 185) }
        assertThrows(IllegalArgumentException::class.java) { BleFrameCodec.fragment(ByteArray(BleFrameCodec.MAX_FRAME_BYTES + 1), "fixture", 185) }
        assertThrows(IllegalArgumentException::class.java) { BleFrameCodec.fragment(byteArrayOf(1), "fixture", 23) }
    }

    @Test fun enforcesAssemblyBoundsTimeoutAndCancellation() {
        var time = 10L
        val receiver = BleReassembler { time }
        val frame = ByteArray(400) { it.toByte() }
        val incomplete = (0 until 4).map { packets(frame + byteArrayOf(it.toByte())) }
        incomplete.forEach { receiver.accept(it.first()) }
        assertEquals(4, receiver.activeCount())
        assertThrows(IllegalArgumentException::class.java) { receiver.accept(packets(frame + byteArrayOf(9))[0]) }
        time += BleFrameCodec.ASSEMBLY_TIMEOUT_MS + 1
        receiver.expire()
        assertEquals(0, receiver.activeCount())
        val cancelledId = UUID.randomUUID()
        val cancelled = packets(frame, frameId = cancelledId)
        receiver.accept(cancelled.first())
        receiver.cancel(cancelledId)
        assertEquals(0, receiver.activeCount())
        receiver.accept(cancelled.first())
        assertEquals(1, receiver.activeCount())
        receiver.clear()
        assertEquals(0, receiver.activeCount())
    }

    @Test fun acknowledgementAndControlPacketsAreVersionedAndIntegrityChecked() {
        val frameId = UUID.randomUUID()
        val objectId = BleFrameCodec.objectIdHash("chat-message-id")
        val ack = BleFrameCodec.decode(BleFrameCodec.ack(frameId, objectId), 185)
        assertEquals(BlePacketKind.ACK, ack.kind)
        assertEquals(frameId, ack.frameId)
        assertArrayEquals(objectId, ack.objectId)
        val hello = BleFrameCodec.decode(BleFrameCodec.control(BlePacketKind.HELLO, byteArrayOf(1)), 185)
        assertEquals(BlePacketKind.HELLO, hello.kind)
        assertArrayEquals(byteArrayOf(1), hello.payload)
        val cancel = BleFrameCodec.decode(BleFrameCodec.control(BlePacketKind.CANCEL, frameId = frameId), 185)
        assertEquals(BlePacketKind.CANCEL, cancel.kind)
        assertEquals(frameId, cancel.frameId)
    }
}
