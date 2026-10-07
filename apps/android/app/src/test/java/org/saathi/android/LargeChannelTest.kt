package org.saathi.android

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class LargeChannelTest {
    @Test fun nativeValidatesTwoHundredPrivateRecipientsAndRejectsOverflowAndMissingKey() {
        val time = Instant.now(); val id = UUID.randomUUID().toString(); val epoch = UUID.randomUUID().toString()
        val signing = (0 until 201).map { ECKeyGenerator(Curve.P_256).generate() }
        val people = signing.mapIndexed { index, key ->
            val pub = ChatProtocol.encryptionPublic(JSONObject(key.toJSONString()))
            val b = obj("v" to 1,"kind" to "CHAT_PROFILE","id" to Protocol.hash(pub),"name" to "Fixture $index","publicKey" to pub,"encryptionKey" to pub,"updatedAt" to time.toString())
            obj("body" to b,"signature" to Protocol.sign(b,key.toECPrivateKey()))
        }
        val members = JSONArray(people.take(200).mapIndexed { i,p -> obj("profile" to p,"role" to if(i==0)"OWNER" else "MEMBER","joinedAt" to time.toString(),"removedAt" to null) })
        val symmetric = ByteArray(32){it.toByte()}
        val keys = JSONArray(people.take(200).map { p -> val person = ChatProtocol.participant(p)
            obj("participantId" to person,"jwe" to ChatProtocol.encrypt(obj("key" to Protocol.b64(symmetric)),p.getJSONObject("body").getJSONObject("encryptionKey"),null,"channel:$id:$epoch:$person"))
        })
        val body = obj("v" to 1,"kind" to "CHAT_CHANNEL","id" to id,"name" to "Large fixture","visibility" to "INVITE","owner" to people.first(),"version" to 1,"epoch" to epoch,"issuedAt" to time.toString(),"expiresAt" to time.plusSeconds(3600).toString(),"deleted" to false,"members" to members,"keys" to keys)
        fun signed() = obj("body" to body,"signature" to Protocol.sign(body,signing.first().toECPrivateKey()))
        assertTrue(Protocol.canonical(signed()).size>150000); ChatProtocol.policy(signed(),time)
        val last = people[199]; val kid = "channel:$id:$epoch:${ChatProtocol.participant(last)}"
        assertEquals(Protocol.b64(symmetric),ChatProtocol.decrypt(keys.getJSONObject(199).getString("jwe"),JSONObject(signing[199].toJSONString()),null,kid).getString("key"))
        keys.remove(199); assertThrows(Exception::class.java){ChatProtocol.policy(signed(),time)}
        members.put(obj("profile" to people.last(),"role" to "MEMBER","joinedAt" to time.toString(),"removedAt" to null))
        assertThrows(Exception::class.java){ChatProtocol.policy(signed(),time)}
    }
}
