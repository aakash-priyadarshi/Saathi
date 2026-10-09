package org.saathi.android

/** Public destinations follow the current server feature switches. */
internal object NavigationPolicy {
    private val primaryDestinations = listOf("Live", "Chats", "Nearby", "Needs", "More")

    fun visibleDestinations(liveEnabled: Boolean, needsEnabled: Boolean): List<String> =
        primaryDestinations.filter { (it != "Live" || liveEnabled) && (it != "Needs" || needsEnabled) }
}
