package org.saathi.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePeerRegistryTest {
    private val id = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    @Test fun rotatedHardwareAddressKeepsOneTemporarySwarmPeer() {
        val registry = BlePeerRegistry()
        val first = registry.observe("AA:00", id, 1_000, "SWARM")
        val second = registry.observe("BB:00", id, 2_000, "SWARM")

        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertFalse(registry.contains("AA:00"))
        assertTrue(registry.contains("BB:00"))
        assertEquals("SWARM · 010203", second.getValue("BB:00"))
    }

    @Test fun unseenPeersExpireAndUnknownLegacyPeersUseBrandName() {
        val registry = BlePeerRegistry(staleAfterMillis = 1_000)
        assertEquals("SWARM nearby", registry.observe("AA:00", null, 1_000, "SWARM").getValue("AA:00"))
        assertEquals(emptyMap<String, String>(), registry.prune(2_001))
    }

    @Test fun distinctEphemeralIdsRemainDistinct() {
        val registry = BlePeerRegistry()
        registry.observe("AA:00", id, 1_000, "SWARM")
        val other = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)
        val peers = registry.observe("BB:00", other, 2_000, "SWARM")

        assertEquals(2, peers.size)
        assertEquals(2, peers.values.toSet().size)
    }
}
