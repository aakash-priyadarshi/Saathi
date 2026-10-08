package org.saathi.android

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationPolicyTest {
    @Test fun liveAndNeedsAreAvailableWhenEnabled() {
        assertEquals(listOf("Live", "Chats", "Nearby", "Needs", "More"), NavigationPolicy.visibleDestinations(true))
    }

    @Test fun onlyNeedsHidesWhenTheVerifiedFeatureSwitchIsOff() {
        assertEquals(listOf("Live", "Chats", "Nearby", "More"), NavigationPolicy.visibleDestinations(false))
    }
}
