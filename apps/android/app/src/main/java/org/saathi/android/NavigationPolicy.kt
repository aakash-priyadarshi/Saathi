package org.saathi.android

/** Primary destinations remain stable; Needs follows the signed server feature switch. */
internal object NavigationPolicy {
    private val primaryDestinations = listOf("Live", "Chats", "Nearby", "Needs", "More")

    fun visibleDestinations(needsEnabled: Boolean): List<String> =
        primaryDestinations.filter { it != "Needs" || needsEnabled }
}
