package org.saathi.android

import org.junit.Assert.*
import org.junit.Test

class CommunityRelayPolicyTest {
    @Test fun consentRequiresActualAllowedNetworkAndSeparateMediaSwitch(){
        assertFalse(CommunityRelayPolicy.mayForward("OFF",true,false,true,true))
        assertFalse(CommunityRelayPolicy.mayForward("WIFI",true,false,true,false))
        assertFalse(CommunityRelayPolicy.mayForward("ANY",true,false,false,true))
        assertTrue(CommunityRelayPolicy.mayForward("WIFI",false,false,true,true))
        assertFalse(CommunityRelayPolicy.mayForward("WIFI",false,true,true,true))
        assertTrue(CommunityRelayPolicy.mayForward("ANY",true,true,true,false))
    }
    @Test fun lowBatteryAndStoragePauseMediaBeforeRequests(){
        assertFalse(CommunityRelayPolicy.resourcesReady(false,19,20,false,0))
        assertTrue(CommunityRelayPolicy.resourcesReady(true,19,20,false,0))
        assertFalse(CommunityRelayPolicy.resourcesReady(true,90,20,true,64L*1048576))
        assertTrue(CommunityRelayPolicy.resourcesReady(false,20,20,true,64L*1048576+1))
    }
    @Test fun abandonedRequestsAndRetriesStillConsumeDailyAllowance(){
        var reserved=0L
        repeat(3){reserved=CommunityRelayPolicy.nextReservation(reserved,500,16384+262144)}
        assertEquals(860160L,reserved)
        assertEquals(286720L,CommunityRelayPolicy.nextReservation(0,500,16384+262144))
    }
    @Test fun dataAllowanceUsesDecimalMegabytesAndOffersHalfGigabyteSteps(){
        assertEquals(500_000_000L,CommunityRelayPolicy.nextReservation(499_000_000L,500,991_808))
        assertTrue(runCatching{CommunityRelayPolicy.nextReservation(499_000_000L,500,991_809)}.isFailure)
        assertEquals(listOf(500,1000,1500,2000,2500,3000,3500,4000,4500,5000),CommunityRelayPolicy.dailyLimitPresetsMB)
        assertEquals("500 MB",CommunityRelayPolicy.dailyLimitLabel(500))
        assertEquals("1.5 GB",CommunityRelayPolicy.dailyLimitLabel(1500))
        assertEquals("5 GB",CommunityRelayPolicy.dailyLimitLabel(5000))
    }
    @Test fun invalidSettingsFailClosed(){
        assertFalse(CommunityRelayPolicy.mayForward("UNKNOWN",true,false,true,true))
        assertTrue(CommunityRelayPolicy.resourcesReady(false,0,0,false,Long.MAX_VALUE))
        assertFalse(CommunityRelayPolicy.resourcesReady(true,100,-1,false,Long.MAX_VALUE))
        assertTrue(runCatching{CommunityRelayPolicy.nextReservation(0,5001,1)}.isFailure)
        assertTrue(runCatching{CommunityRelayPolicy.nextReservation(-1,500,1)}.isFailure)
    }
}
