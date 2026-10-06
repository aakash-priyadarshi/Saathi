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
        repeat(3){reserved=CommunityRelayPolicy.nextReservation(reserved,1,16384+262144)}
        assertEquals(860160L,reserved)
        assertTrue(runCatching{CommunityRelayPolicy.nextReservation(reserved,1,16384+262144)}.isFailure)
        assertEquals(286720L,CommunityRelayPolicy.nextReservation(0,1,16384+262144))
    }
    @Test fun invalidSettingsFailClosed(){
        assertFalse(CommunityRelayPolicy.mayForward("UNKNOWN",true,false,true,true))
        assertFalse(CommunityRelayPolicy.resourcesReady(true,100,0,false,Long.MAX_VALUE))
        assertTrue(runCatching{CommunityRelayPolicy.nextReservation(0,501,1)}.isFailure)
        assertTrue(runCatching{CommunityRelayPolicy.nextReservation(-1,50,1)}.isFailure)
    }
}
