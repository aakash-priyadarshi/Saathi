package org.saathi.android

/** Decisions use measured network/resource facts; USB fixture access is not internet consent. */
internal object CommunityRelayPolicy {
    val dailyLimitPresetsMB = listOf(500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000)

    fun dailyLimitLabel(megabytes: Int): String = when {
        megabytes < 1000 -> "$megabytes MB"
        megabytes % 1000 == 0 -> "${megabytes / 1000} GB"
        else -> "${megabytes / 1000}.${(megabytes % 1000) / 100} GB"
    }

    fun mayForward(mode:String,mediaEnabled:Boolean,media:Boolean,validated:Boolean,wifi:Boolean):Boolean =
        validated && (mode=="ANY" || mode=="WIFI" && wifi) && (!media || mediaEnabled)

    fun resourcesReady(charging:Boolean,battery:Int,minimum:Int,media:Boolean,freeBytes:Long):Boolean =
        minimum in 0..80 && (charging || battery in minimum..100) && (!media || freeBytes>64L*1048576)

    /** Automatic online media runs while charging or above the battery floor, and under the day's data limit. */
    fun mediaAllowed(charging:Boolean,battery:Int,minimum:Int,usedBytes:Long,limitMB:Int):Boolean =
        (charging || battery>=minimum) && usedBytes<limitMB.coerceIn(500,5000)*1_000_000L

    fun nextReservation(used:Long,limitMB:Int,wireBudget:Int):Long {
        require(used>=0 && limitMB in 500..5000 && wireBudget in 1..1048576){"Public relay data settings are invalid."}
        val next=used+wireBudget+8192L
        require(next<=limitMB*1_000_000L){"Daily public relay data limit reached. Saved work will retry tomorrow."}
        return next
    }
}
