package org.saathi.android

import org.junit.Assert.*
import org.junit.Test

class NearbyEndpointNameTest {
    @Test fun advertisesChosenDisplayNameWithinNearbyLimit() {
        assertEquals("S24 for Arjun", nearbyEndpointName("  S24 for Arjun  "))
        assertEquals("A".repeat(32), nearbyEndpointName("A".repeat(40)))
        assertFalse(nearbyEndpointName("A\nB").contains('\n'))
        assertTrue(nearbyEndpointName(" \n ").startsWith("Swarm "))
    }
}
