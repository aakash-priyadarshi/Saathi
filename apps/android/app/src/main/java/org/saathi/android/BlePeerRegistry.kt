package org.saathi.android

/** Short-lived identities for filtered Swarm BLE advertisements. Never exposes a hardware name. */
internal class BlePeerRegistry(
    private val staleAfterMillis: Long = 15_000,
    private val maximumPeers: Int = 20,
) {
    private data class Entry(val label: String, val lastSeenAt: Long, val advertisementId: String?)

    private val byAddress = linkedMapOf<String, Entry>()
    private val addressByAdvertisementId = mutableMapOf<String, String>()

    fun observe(address: String, advertisementId: ByteArray?, nowMillis: Long, brand: String): Map<String, String> {
        pruneExpired(nowMillis)
        val id = advertisementId?.takeIf { it.size == 8 }?.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0').uppercase() }
        val previousAddress = id?.let(addressByAdvertisementId::get)
        if (previousAddress != null && previousAddress != address) {
            val old = byAddress.remove(previousAddress)
            old?.advertisementId?.let { if (addressByAdvertisementId[it] == previousAddress) addressByAdvertisementId.remove(it) }
            val replaced = byAddress.remove(address)
            replaced?.advertisementId?.let { if (addressByAdvertisementId[it] == address) addressByAdvertisementId.remove(it) }
            val label = old?.label ?: label(brand, id)
            byAddress[address] = Entry(label, nowMillis, id)
            addressByAdvertisementId[id] = address
        } else {
            val old = byAddress[address]
            if (old == null) {
                if (byAddress.size < maximumPeers) {
                    val name = id?.let { label(brand, it) } ?: "$brand nearby"
                    byAddress[address] = Entry(name, nowMillis, id)
                }
            } else {
                byAddress[address] = old.copy(lastSeenAt = nowMillis, advertisementId = id ?: old.advertisementId)
            }
            if (id != null && address in byAddress) addressByAdvertisementId[id] = address
        }
        return snapshot()
    }

    fun contains(address: String) = address in byAddress

    fun prune(nowMillis: Long): Map<String, String> {
        pruneExpired(nowMillis)
        return snapshot()
    }

    fun clear() {
        byAddress.clear()
        addressByAdvertisementId.clear()
    }

    private fun pruneExpired(nowMillis: Long) {
        val expired = byAddress.filterValues { nowMillis - it.lastSeenAt > staleAfterMillis }.keys
        expired.forEach { address ->
            byAddress.remove(address)?.advertisementId?.let { if (addressByAdvertisementId[it] == address) addressByAdvertisementId.remove(it) }
        }
    }

    private fun snapshot() = byAddress.mapValues { it.value.label }.toMap(LinkedHashMap())

    private fun label(brand: String, id: String) = "$brand · ${id.take(6)}"
}
