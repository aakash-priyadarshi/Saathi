package org.saathi.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WalkieTalkieTest {
    private class Pair {
        var now = 1000L
        val aId = "a".repeat(64); val bId = "b".repeat(64)
        val id = ChatProtocol.dm(aId, bId)
        val packets = mutableListOf<Triple<Boolean, String, JSONObject>>()
        val a = WalkieTalkie({ now }, { k, v -> packets.add(Triple(true, k, v)) }, {})
        val b = WalkieTalkie({ now }, { k, v -> packets.add(Triple(false, k, v)) }, {})
        fun pump() {
            var count = 0
            while (packets.isNotEmpty()) {
                check(++count < 100)
                val (fromA, kind, packet) = packets.removeAt(0)
                if (fromA) b.receive(kind, packet, aId) else a.receive(kind, packet, bId)
                assertFalse(a.snapshot.transmitting && b.snapshot.transmitting)
            }
        }
        fun ready() { a.enable(aId, bId, id); b.enable(bId, aId, id); pump() }
    }
    @Test fun handshakeKeepsMicrophonesOffAndReleaseImmediatelyMutes() {
        val p = Pair(); p.ready()
        assertEquals("READY", p.a.snapshot.status); assertEquals("READY", p.b.snapshot.status)
        p.a.press(); assertFalse(p.a.snapshot.transmitting); p.pump()
        assertTrue(p.a.snapshot.transmitting); assertTrue(p.b.snapshot.receiving)
        p.a.release(); assertFalse(p.a.snapshot.transmitting); p.pump()
        assertEquals("READY", p.b.snapshot.status)
        p.b.press(); p.pump(); assertTrue(p.b.snapshot.transmitting)
    }
    @Test fun delayedGrantCannotOpenMicrophoneAfterReleaseOrChatSwitch() {
        val p = Pair(); p.ready(); p.a.press()
        val (_, kind, request) = p.packets.removeAt(0); p.b.receive(kind, request, p.aId)
        val grant = p.packets.single(); p.packets.clear()
        p.a.release(); p.a.receive(grant.second, grant.third, p.bId)
        assertFalse(p.a.snapshot.transmitting)
        p.a.enable(p.aId, "c".repeat(64), ChatProtocol.dm(p.aId, "c".repeat(64)))
        p.a.receive(grant.second, grant.third, p.bId)
        assertFalse(p.a.snapshot.transmitting); assertEquals("WAITING", p.a.snapshot.status)
    }
    @Test fun simultaneousPressHasExactlyOneSpeakerAndTimeoutClosesBothEnds() {
        val p = Pair(); p.ready(); p.a.press(); p.b.press(); p.pump()
        assertTrue(p.a.snapshot.transmitting); assertTrue(p.b.snapshot.receiving)
        p.now += 30001; p.a.expire(); p.b.expire(); p.pump()
        assertFalse(p.a.snapshot.transmitting); assertFalse(p.b.snapshot.receiving)
    }
    @Test fun forgedRecipientAndOldScopeAreIgnoredAndDisconnectIsImmediate() {
        val p = Pair(); p.ready(); p.a.press()
        val request = p.packets.removeAt(0)
        val forged = JSONObject(request.third.toString()).put("recipientId", "c".repeat(64))
        p.b.receive(request.second, forged, p.aId); assertEquals("READY", p.b.snapshot.status)
        p.b.receive(request.second, request.third, "d".repeat(64)); assertEquals("READY", p.b.snapshot.status)
        p.b.receive(request.second, request.third, p.aId); p.pump(); assertTrue(p.a.snapshot.transmitting)
        p.a.stop(false); assertFalse(p.a.snapshot.transmitting); assertNull(p.a.snapshot.conversationId)
        p.a.enable(p.aId,p.bId,p.id); p.b.stop(false); p.b.enable(p.bId,p.aId,p.id); p.pump()
        p.b.receive(request.second, request.third, p.aId); assertEquals("READY",p.b.snapshot.status)
    }
}
