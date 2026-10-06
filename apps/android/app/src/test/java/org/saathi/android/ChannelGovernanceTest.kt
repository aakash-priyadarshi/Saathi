package org.saathi.android

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import java.security.KeyPairGenerator
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID

class ChannelGovernanceTest {
    private val now=Instant.now()
    private fun keys()=KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()
    private fun signed(b:JSONObject,key:KeyPair)=obj("body" to b,"signature" to Protocol.sign(b,key.private))
    private fun person(key:KeyPair,name:String):JSONObject{val pub=Protocol.publicJwk(key.public as ECPublicKey);return signed(obj("v" to 1,"kind" to "CHAT_PROFILE","id" to Protocol.hash(pub),"name" to name,"publicKey" to pub,"encryptionKey" to ChatProtocol.encryptionPublic(ChatProtocol.newEncryptionKey()),"updatedAt" to now.toString()),key)}
    private fun policy(owner:JSONObject,member:JSONObject,key:KeyPair,role:String="MEMBER"):JSONObject {
        val members=JSONArray(listOf(obj("profile" to owner,"role" to "OWNER","joinedAt" to now.minusSeconds(1).toString(),"removedAt" to null),obj("profile" to member,"role" to role,"joinedAt" to now.minusSeconds(1).toString(),"removedAt" to null)))
        return signed(obj("v" to 1,"kind" to "CHAT_CHANNEL","id" to UUID.randomUUID().toString(),"name" to "Announcements","visibility" to "OPEN","owner" to owner,"version" to 1,"epoch" to UUID.randomUUID().toString(),"issuedAt" to now.minusSeconds(1).toString(),"expiresAt" to now.plusSeconds(3600).toString(),"deleted" to false,"members" to members,"keys" to JSONArray(),"settings" to obj("mode" to "ANNOUNCEMENT","admission" to "APPROVAL_ONLY")),key)
    }
    @Test fun nativeSignedAnnouncementPostingAndReadOnlyThreadBoundaries(){
        val ownerKey=keys();val memberKey=keys();val owner=person(ownerKey,"Owner");val member=person(memberKey,"Member");val p=policy(owner,member,ownerKey);val pb=p.getJSONObject("body")
        val b=obj("v" to 1,"kind" to "CHAT_MESSAGE","id" to UUID.randomUUID().toString(),"conversationId" to pb.getString("id"),"author" to member,"recipientId" to null,"policyHash" to Protocol.hash(p),"channelVersion" to 1,"epoch" to pb.getString("epoch"),"sequence" to 1,"createdAt" to now.toString(),"expiresAt" to now.plusSeconds(3600).toString(),"format" to "TEXT","encrypted" to false,"content" to obj("text" to "A signed reply").toString())
        assertThrows(Exception::class.java){ChatProtocol.message(signed(b,memberKey),p,now)}
        b.put("threadRootId",UUID.randomUUID().toString());ChatProtocol.message(signed(b,memberKey),p,now)
        pb.getJSONArray("members").getJSONObject(1).put("role","READ_ONLY");val readOnly=signed(pb,ownerKey);b.put("policyHash",Protocol.hash(readOnly))
        assertThrows(Exception::class.java){ChatProtocol.message(signed(b,memberKey),readOnly,now)}
    }
    @Test fun delegatedLockIsAuthenticatedAndRoleDemotionRejectsStaleActions(){
        val ownerKey=keys();val modKey=keys();val owner=person(ownerKey,"Owner");val moderator=person(modKey,"Moderator");val p=policy(owner,moderator,ownerKey,"MODERATOR");val pb=p.getJSONObject("body")
        val b=obj("v" to 1,"kind" to "CHAT_ACTION","id" to UUID.randomUUID().toString(),"channelId" to pb.getString("id"),"actor" to moderator,"policyHash" to Protocol.hash(p),"version" to 1,"action" to "LOCK_THREAD","targetId" to UUID.randomUUID().toString(),"issuedAt" to now.toString(),"expiresAt" to now.plusSeconds(3600).toString())
        ChannelGovernance.action(signed(b,modKey),p,now)
        assertThrows(Exception::class.java){ChannelGovernance.action(signed(b,ownerKey),p,now)}
        pb.getJSONArray("members").getJSONObject(1).put("role","MEMBER");pb.put("version",2)
        assertThrows(Exception::class.java){ChannelGovernance.action(signed(b,modKey),signed(pb,ownerKey),now)}
    }
    @Test fun admissionHasNoKeysAndRetainsRecipientAndExpiryBinding(){
        val key=keys();val owner=person(key,"Owner");val recipient=person(keys(),"Recipient");val p=policy(owner,recipient,key)
        val b=obj("v" to 1,"kind" to "CHAT_ADMISSION","id" to UUID.randomUUID().toString(),"channelId" to p.getJSONObject("body").getString("id"),"name" to "Organizers","owner" to owner,"recipientId" to ChatProtocol.participant(recipient),"policyHash" to Protocol.hash(p),"admission" to "INVITE_PLUS_APPROVAL","issuedAt" to now.toString(),"expiresAt" to now.plusSeconds(3600).toString())
        val invite=signed(b,key);ChannelGovernance.admission(invite,ChatProtocol.participant(recipient),now)
        assertFalse(invite.toString().contains("\"keys\""));assertFalse(invite.toString().contains("\"members\""))
        assertThrows(Exception::class.java){ChannelGovernance.admission(invite,ChatProtocol.participant(owner),now)}
        assertThrows(Exception::class.java){ChannelGovernance.admission(invite,ChatProtocol.participant(recipient),now.plusSeconds(7200))}
    }
}
