package org.saathi.android

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.time.Instant

class GatewayRoutingTest {
    private fun identity(now: Instant): Pair<JSONObject, java.security.PrivateKey> {
        val pair=KeyPairGenerator.getInstance("EC").apply{initialize(java.security.spec.ECGenParameterSpec("secp256r1"))}.generateKeyPair()
        val public=Protocol.publicJwk(pair.public as ECPublicKey)
        val body=obj("v" to 1,"kind" to "CHAT_PROFILE","id" to Protocol.hash(public),"name" to "Gateway phone","publicKey" to public,"encryptionKey" to ChatProtocol.encryptionPublic(ChatProtocol.newEncryptionKey()),"updatedAt" to now.toString())
        return obj("body" to body,"signature" to Protocol.sign(body,pair.private)) to pair.private
    }

    @Test fun signedGatewayRoutePropagatesWithIncreasingHopCountAndMediaPath() {
        val now=Instant.parse("2026-10-07T10:00:00Z")
        val (profile,key)=identity(now)
        val lease=GatewayRouting.lease(profile,1,now,true,4_000_000_000L,key)
        val first=GatewayRouting.receive(obj("lease" to lease,"hops" to 0,"mediaPath" to true),"relay-one",now.plusSeconds(1))!!
        assertEquals(1,first.hops)
        assertEquals("relay-one",first.viaPeer)
        assertTrue(first.mediaPath)
        val second=GatewayRouting.receive(GatewayRouting.advertisement(first,false),"relay-two",now.plusSeconds(2))!!
        assertEquals(2,second.hops)
        assertFalse(second.mediaPath)
        assertEquals(4_000_000_000L,second.remainingBytes)
    }

    @Test fun invalidSignatureExpiredLeaseAndExcessiveHopsAreRejected() {
        val now=Instant.parse("2026-10-07T10:00:00Z")
        val (profile,key)=identity(now)
        val lease=GatewayRouting.lease(profile,1,now,true,500_000_000L,key)
        val changed=JSONObject(lease.toString()).getJSONObject("body").put("serverReachable",false)
        assertNull(GatewayRouting.receive(obj("lease" to obj("body" to changed,"signature" to lease.getString("signature")),"hops" to 0,"mediaPath" to true),"peer",now))
        assertNull(GatewayRouting.receive(obj("lease" to lease,"hops" to GatewayRouting.MAX_HOPS,"mediaPath" to true),"peer",now))
        assertNull(GatewayRouting.receive(obj("lease" to lease,"hops" to 0,"mediaPath" to true),"peer",now.plusSeconds(GatewayRouting.LEASE_SECONDS+1)))
    }

    @Test fun signedCommunityReportAcceptsOnlyAudioSizedMetadata() {
        val now=Instant.parse("2026-10-07T10:00:00Z")
        val (profile,key)=identity(now);val id=java.util.UUID.randomUUID().toString()
        val payload=obj("caption" to "Short audio update from the public area","area" to "Fictional Gate 2","contentWarning" to false,"media" to obj("id" to java.util.UUID.randomUUID().toString(),"mime" to "audio/mp4","size" to 1024,"hash" to "a".repeat(64),"width" to 1,"height" to 1,"durationSeconds" to 30))
        val body=obj("v" to 1,"kind" to "COMMUNITY_EVENT","id" to id,"objectId" to id,"author" to profile,"createdAt" to now.toString(),"expiresAt" to now.plusSeconds(86400).toString(),"maxHops" to CommunityProtocol.MAX_HOPS,"payloadHash" to Protocol.hash(payload),"type" to "REPORT","payload" to payload)
        assertTrue(CommunityProtocol.valid(obj("body" to body,"signature" to Protocol.sign(body,key)),now))
        payload.getJSONObject("media").put("width",320);body.put("payloadHash",Protocol.hash(payload))
        assertFalse(CommunityProtocol.valid(obj("body" to body,"signature" to Protocol.sign(body,key)),now))
    }

    @Test fun communityEventsAllowSixteenRelayHopsButRejectMore() {
        val now=Instant.parse("2026-10-07T10:00:00Z")
        val (profile,key)=identity(now);val id=java.util.UUID.randomUUID().toString()
        val payload=obj("caption" to "Public update","area" to "Fictional Gate 2","contentWarning" to false,"media" to JSONObject.NULL)
        val body=obj("v" to 1,"kind" to "COMMUNITY_EVENT","id" to id,"objectId" to id,"author" to profile,"createdAt" to now.toString(),"expiresAt" to now.plusSeconds(86400).toString(),"maxHops" to CommunityProtocol.MAX_HOPS,"payloadHash" to Protocol.hash(payload),"type" to "REPORT","payload" to payload)
        assertTrue(CommunityProtocol.valid(obj("body" to body,"signature" to Protocol.sign(body,key)),now))
        body.put("maxHops",CommunityProtocol.MAX_HOPS+1)
        assertFalse(CommunityProtocol.valid(obj("body" to body,"signature" to Protocol.sign(body,key)),now))
    }
}
