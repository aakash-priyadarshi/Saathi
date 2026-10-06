package org.saathi.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStatusTest {
    @Test fun transportAcceptanceIsNotPresentedAsRecipientDelivery() {
        assertEquals("Sent nearby · waiting for receipt", chatStatus(JSONObject("""{"sentNearby":"now"}""")))
        assertEquals("Uploaded · waiting for receipt", chatStatus(JSONObject("""{"serverSaved":true}""")))
        assertEquals("Delivered", chatStatus(JSONObject("""{"sentNearby":"now","deliveredAt":"now"}""")))
        assertEquals("Read", chatStatus(JSONObject("""{"deliveredAt":"now","readAt":"now"}""")))
    }
}
