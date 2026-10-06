package org.saathi.android

/** Decisions use measured network/resource facts; USB fixture access is not internet consent. */
internal object CommunityRelayPolicy {
    fun mayForward(mode:String,mediaEnabled:Boolean,media:Boolean,validated:Boolean,wifi:Boolean):Boolean =
        validated && (mode=="ANY" || mode=="WIFI" && wifi) && (!media || mediaEnabled)

    fun resourcesReady(charging:Boolean,battery:Int,minimum:Int,media:Boolean,freeBytes:Long):Boolean =
        minimum in 10..80 && (charging || battery in minimum..100) && (!media || freeBytes>64L*1048576)

    fun nextReservation(used:Long,limitMiB:Int,wireBudget:Int):Long {
        require(used>=0 && limitMiB in 1..500 && wireBudget in 1..1048576){"Public relay data settings are invalid."}
        val next=used+wireBudget+8192L
        require(next<=limitMiB*1048576L){"Daily public relay data limit reached. Saved work will retry tomorrow."}
        return next
    }
}
