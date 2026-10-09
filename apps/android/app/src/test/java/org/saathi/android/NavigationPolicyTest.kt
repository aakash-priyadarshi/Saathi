package org.saathi.android

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationPolicyTest {
    @Test fun liveAndNeedsAreAvailableWhenEnabled() {
        assertEquals(listOf("Live", "Chats", "Nearby", "Needs", "More"), NavigationPolicy.visibleDestinations(true, true))
    }

    @Test fun bothPublicFeaturesAreHiddenByDefault() {
        assertEquals(listOf("Chats", "Nearby", "More"), NavigationPolicy.visibleDestinations(false, false))
    }

    @Test fun liveAndNeedsCanBeControlledIndependently() {
        assertEquals(listOf("Live", "Chats", "Nearby", "More"), NavigationPolicy.visibleDestinations(true, false))
        assertEquals(listOf("Chats", "Nearby", "Needs", "More"), NavigationPolicy.visibleDestinations(false, true))
    }
}
