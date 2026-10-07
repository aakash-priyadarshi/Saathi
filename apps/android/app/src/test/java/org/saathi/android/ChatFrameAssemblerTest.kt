package org.saathi.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ChatFrameAssemblerTest {
    private fun frame(size: Int = 230000) = obj("v" to 1, "kind" to "CHAT_POLICY", "id" to UUID.randomUUID().toString(), "value" to obj("fixture" to "x".repeat(size)))
    @Test fun assemblesLargeRosterOutOfOrderWithinSmallFrameBudget() {
        val original = frame(); val parts = ChatFrameAssembler.parts(original, 1800)
        assertTrue(parts.size > 100)
        assertTrue(parts.all { obj("v" to 1,"id" to UUID.randomUUID().toString(),"kind" to "CHAT_CHUNK","value" to it).toString().toByteArray().size <= 1800 })
        val receiver = ChatFrameAssembler(); var completed: JSONObject? = null
        for (part in parts.reversed()) receiver.accept(part)?.let { completed = it }
        assertEquals(original.toString(), completed!!.toString())
    }
    @Test fun rejectsTamperingConflictingPartsAndOversizeBeforeAllocating() {
        val parts = ChatFrameAssembler.parts(frame(),24000); val receiver = ChatFrameAssembler()
        receiver.accept(parts.first())
        val conflicting = JSONObject(parts.first().toString()).put("data",Protocol.b64(byteArrayOf(1)))
        assertThrows(Exception::class.java) { receiver.accept(conflicting) }
        val huge = JSONObject(parts.first().toString()).put("bytes", ChatFrameAssembler.MAX_BYTES+1)
        assertThrows(Exception::class.java) { receiver.accept(huge) }
        val damaged = parts.map{JSONObject(it.toString())}; damaged.last().put("data",Protocol.b64(ByteArray(Protocol.decode(damaged.last().getString("data")).size)))
        val second = ChatFrameAssembler()
        assertThrows(Exception::class.java) { damaged.forEach{second.accept(it)} }
    }
    @Test fun connectionResetAndExpiryDiscardIncompleteFrames() {
        var now = 0L; val receiver = ChatFrameAssembler { now }; val parts = ChatFrameAssembler.parts(frame(),24000)
        receiver.accept(parts.first()); receiver.clear()
        assertTrue(parts.drop(1).all{receiver.accept(it)==null})
        now += ChatFrameAssembler.EXPIRY_MS; assertNull(receiver.accept(parts.first()))
    }
}
