package org.saathi.android

import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class ChatProtocolTest {
    private fun vectors()=JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText())
    @Test fun joseDirectAndSymmetricEncryptionRoundTripAndRejectWrongRecipientContextAndTampering(){
        val key=ChatProtocol.newEncryptionKey();val other=ChatProtocol.newEncryptionKey();val value=obj("text" to "Durable private fixture")
        val cipher=ChatProtocol.encrypt(value,ChatProtocol.encryptionPublic(key),null,"test-context")
        assertEquals(value.toString(),ChatProtocol.decrypt(cipher,key,null,"test-context").toString())
        assertThrows(Exception::class.java){ChatProtocol.decrypt(cipher,other,null,"test-context")}
        assertThrows(Exception::class.java){ChatProtocol.decrypt(cipher,key,null,"different")}
        val symmetric=ByteArray(32){it.toByte()};val group=ChatProtocol.encrypt(value,null,symmetric,"group")
        assertEquals(value.toString(),ChatProtocol.decrypt(group,null,symmetric,"group").toString())
        assertThrows(Exception::class.java){ChatProtocol.decrypt(group,null,ByteArray(32),"group")}
        val parts=cipher.split('.').toMutableList();parts[3]=(if(parts[3][0]=='A')"B" else "A")+parts[3].substring(1)
        assertThrows(Exception::class.java){ChatProtocol.decrypt(parts.joinToString("."),key,null,"test-context")}
    }
    @Test fun typescriptJweAndCanonicalSignaturesInteroperateWithNimbusAndNativeValidators(){
        val vector=vectors();val time=Instant.parse(vector.getString("now"));val message=vector.getJSONObject("dm");val body=message.getJSONObject("body")
        ChatProtocol.profile(body.getJSONObject("author"),time);ChatProtocol.message(message,null,time)
        val kid="dm:"+body.getString("conversationId")+":"+body.getString("id")+":"+body.getString("recipientId")
        assertEquals("Where is Gate 2?",ChatProtocol.decrypt(body.getString("content"),vector.getJSONObject("recipientPrivateJwk"),null,kid).getString("text"))
        val policy=vector.getJSONObject("privateChannel");ChatProtocol.policy(policy,time)
        val memberKey=policy.getJSONObject("body").getJSONArray("keys").objects().first {it.getString("participantId")==body.getString("recipientId")}
        val keyKid="channel:"+policy.getJSONObject("body").getString("id")+":"+policy.getJSONObject("body").getString("epoch")+":"+body.getString("recipientId")
        assertEquals(vector.getString("key"),ChatProtocol.decrypt(memberKey.getString("jwe"),vector.getJSONObject("recipientPrivateJwk"),null,keyKid).getString("key"))
        val channelMessage=vector.getJSONObject("privateMessage");ChatProtocol.message(channelMessage,policy,time)
        val cb=channelMessage.getJSONObject("body")
        assertEquals("Where is Gate 2?",ChatProtocol.decrypt(cb.getString("content"),null,Protocol.decode(vector.getString("key")),"channel:"+cb.getString("conversationId")+":"+cb.getString("epoch")+":"+cb.getString("id")).getString("text"))
        ChatProtocol.message(vector.getJSONObject("openMessage"),vector.getJSONObject("openChannel"),time)
    }
    @Test fun identitiesRemainStableAndForgedProfilesPlainDmsAndStaleMembershipFail(){
        val v=vectors();val time=Instant.parse(v.getString("now"));val message=v.getJSONObject("dm");val b=message.getJSONObject("body")
        assertEquals(b.getString("conversationId"),ChatProtocol.dm(b.getString("recipientId"),ChatProtocol.participant(b.getJSONObject("author"))))
        val forged=JSONObject(b.getJSONObject("author").toString());forged.getJSONObject("body").put("name","Impersonator")
        assertThrows(Exception::class.java){ChatProtocol.profile(forged,time)}
        message.getJSONObject("body").put("encrypted",false)
        assertThrows(Exception::class.java){ChatProtocol.message(message,null,time)}
        assertThrows(Exception::class.java){ChatProtocol.message(v.getJSONObject("privateMessage"),v.getJSONObject("openChannel"),time)}
    }
    @Test fun payloadValidationBoundsMediaMentionsAndReferences(){
        assertEquals("Hello",ChatProtocol.payload(obj("text" to "Hello")).getString("text"))
        assertThrows(Exception::class.java){ChatProtocol.payload(obj("text" to "x".repeat(4001)))}
        assertThrows(Exception::class.java){ChatProtocol.payload(obj("reference" to obj("type" to "ADMIN_ACTION","id" to "x","title" to "Fake authority")))}
        assertThrows(Exception::class.java){ChatProtocol.payload(obj("text" to "Test","unexpected" to true))}
    }
}
