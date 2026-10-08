package org.saathi.android

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicMediaCleanupPolicyTest {
    @Test fun onlyCompletedPublicMediaOutsidePendingReportsCanBeCleared() {
        val pending = setOf("pending-report")
        assertTrue(PublicMediaCleanupPolicy.shouldClear(JSONObject("""{"id":"published-report","publicOnly":true,"complete":true}"""), pending))
        assertFalse(PublicMediaCleanupPolicy.shouldClear(JSONObject("""{"id":"pending-report","publicOnly":true,"complete":true}"""), pending))
        assertFalse(PublicMediaCleanupPolicy.shouldClear(JSONObject("""{"id":"nearby-file","complete":true}"""), pending))
        assertFalse(PublicMediaCleanupPolicy.shouldClear(JSONObject("""{"id":"chat-file","publicOnly":true,"chatOnly":true,"complete":true}"""), pending))
        assertFalse(PublicMediaCleanupPolicy.shouldClear(JSONObject("""{"id":"partial-report","publicOnly":true,"complete":false}"""), pending))
    }
}
