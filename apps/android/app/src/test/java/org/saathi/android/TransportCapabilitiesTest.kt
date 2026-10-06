package org.saathi.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportCapabilitiesTest {
    @Test fun bluetoothFallbackCarriesTextControlAndSmallEventsOnly() {
        val bluetoothOnly = TransportCapabilities.of(media = false, files = false)
        assertEquals(
            setOf(TransportCapability.TEXT, TransportCapability.SMALL_CONTROL, TransportCapability.SMALL_STRUCTURED_EVENT),
            bluetoothOnly,
        )
        assertTrue(TransportCapabilities.required("MESSAGE", JSONObject()) in bluetoothOnly)
        assertTrue(TransportCapabilities.required("COMMUNITY_HELP", JSONObject()) in bluetoothOnly)
        assertFalse(TransportCapabilities.required("FILE_CHUNK", JSONObject()) in bluetoothOnly)
        assertFalse(TransportCapabilities.required("CALL", JSONObject().put("video", false)) in bluetoothOnly)
        assertFalse(TransportCapabilities.required("CALL", JSONObject().put("video", true)) in bluetoothOnly)
    }

    @Test fun strongerDirectTransportsExposeCapabilitiesByFeatures() {
        val nearby = TransportCapabilities.of(media = false, files = true)
        assertTrue(TransportCapability.LARGE_ATTACHMENT in nearby)
        assertFalse(TransportCapability.VOICE in nearby)
        val localWifi = TransportCapabilities.of(media = true, files = true)
        assertTrue(TransportCapability.VOICE in localWifi)
        assertTrue(TransportCapability.VIDEO in localWifi)
        assertTrue(TransportCapability.HIGH_BANDWIDTH in localWifi)
    }
}
