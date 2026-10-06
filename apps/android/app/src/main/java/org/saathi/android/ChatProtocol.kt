package org.saathi.android

import com.nimbusds.jose.*
import com.nimbusds.jose.crypto.*
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID

/** JOSE RFC 7516/7518. Static identity ECDH; no Signal/MLS/forward-secrecy claim. */
object ChatProtocol {
    fun newEncryptionKey() = JSONObject(ECKeyGenerator(Curve.P_256).generate().toJSONString())
    fun encryptionPublic(key: JSONObject): JSONObject {
        val k = ECKey.parse(key.toString()).toPublicJWK()
        return obj("kty" to "EC", "crv" to "P-256", "x" to k.x.toString(), "y" to k.y.toString())
    }
    fun encrypt(value: JSONObject, key: JSONObject?, symmetric: ByteArray?, kid: String): String {
        val alg = if (symmetric != null) JWEAlgorithm.DIR else JWEAlgorithm.ECDH_ES
        val message = JWEObject(JWEHeader.Builder(alg, EncryptionMethod.A256GCM).type(JOSEObjectType("SWARM_CHAT_V1")).keyID(kid).build(), Payload(String(Protocol.canonical(value))))
        if (symmetric != null) { require(symmetric.size == 32); message.encrypt(DirectEncrypter(symmetric)) }
        else message.encrypt(ECDHEncrypter(ECKey.parse(key!!.toString()).toECPublicKey()))
        return message.serialize()
    }
    fun decrypt(value: String, key: JSONObject?, symmetric: ByteArray?, kid: String): JSONObject {
        require(value.length <= 22000)
        val message = JWEObject.parse(value); val header = message.header
        require(header.algorithm == (if (symmetric != null) JWEAlgorithm.DIR else JWEAlgorithm.ECDH_ES) && header.encryptionMethod == EncryptionMethod.A256GCM)
        require(header.type?.toString() == "SWARM_CHAT_V1" && header.keyID == kid && header.compressionAlgorithm == null && header.criticalParams.isNullOrEmpty())
        if (symmetric != null) { require(symmetric.size == 32); message.decrypt(DirectDecrypter(symmetric)) }
        else message.decrypt(ECDHDecrypter(ECKey.parse(key!!.toString()).toECPrivateKey()))
        require(message.payload.toBytes().size <= 16000)
        return JSONObject(message.payload.toString())
    }
    private fun header(value:String,algorithm:String,kid:String){
        require(value.length<=22000 && value.split('.').size==5)
        val h=JSONObject(String(Protocol.decode(value.substringBefore('.'))))
        val fields=setOf("alg","enc","typ","kid")+if(algorithm=="ECDH-ES")setOf("epk")else emptySet()
        require(h.keys().asSequence().toSet()==fields && h.getString("alg")==algorithm && h.getString("enc")=="A256GCM" && h.getString("typ")=="SWARM_CHAT_V1" && h.getString("kid")==kid)
        if(algorithm=="ECDH-ES")Protocol.publicKey(h.getJSONObject("epk"))
    }
    private fun text(o: JSONObject, field: String, max: Int): String {
        require(o.get(field) is String); return o.getString(field).also { require(it.length in 1..max) }
    }
    private fun id(value: String) { require(value.matches(Regex("[a-f0-9]{64}"))) }
    private fun plainName(value:String) { require(!Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]").containsMatchIn(value)) }
    private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
    private fun signed(value: JSONObject): JSONObject {
        value.exact("body", "signature"); require(text(value, "signature", 86).length == 86)
        return value.getJSONObject("body")
    }
    private fun time(start: String, end: String, seconds: Long, now: Instant) {
        val a = Instant.parse(start); val b = Instant.parse(end)
        require(a <= now.plusSeconds(300) && b > now && b > a && b <= a.plusSeconds(seconds))
    }
    fun profile(profile: JSONObject, now: Instant = Instant.now()): JSONObject {
        val b = signed(profile); b.exact("v","kind","id","name","publicKey","encryptionKey","updatedAt")
        require(b.get("v") == 1 && b.getString("kind") == "CHAT_PROFILE")
        val name = text(b,"name",32); require(name.trim() == name);plainName(name)
        id(b.getString("id")); require(b.getString("id") == Protocol.hash(b.getJSONObject("publicKey")))
        require(Instant.parse(b.getString("updatedAt")) <= now.plusSeconds(300))
        Protocol.publicKey(b.getJSONObject("encryptionKey"))
        ECKey.parse(b.getJSONObject("encryptionKey").toString()).toECPublicKey()
        require(Protocol.verify(b, profile.getString("signature"), b.getJSONObject("publicKey")))
        return profile
    }
    fun participant(profile: JSONObject) = profile.getJSONObject("body").getString("id")
    fun dm(a: String, b: String): String { id(a); id(b); require(a != b); return "dm:" + Protocol.hash(JSONArray(listOf("SWARM_DM_V1") + listOf(a,b).sorted())) }
    fun member(policy: JSONObject, person: String): Boolean = ChannelGovernance.capabilities(policy,person).getBoolean("canRead")
    fun policy(policy: JSONObject, now: Instant = Instant.now()): JSONObject {
        require(Protocol.canonical(policy).size <= 22000)
        val b = signed(policy); b.exactOptional(listOf("v","kind","id","name","visibility","owner","version","epoch","issuedAt","expiresAt","deleted","members","keys"),listOf("settings","bannedIds","appliedActions","moderation"))
        require(b.get("v") == 1 && b.getString("kind") == "CHAT_CHANNEL"); uuid(b.getString("id")); uuid(b.getString("epoch"))
        val name=text(b,"name",48); require(name.trim()==name && b.getString("visibility") in listOf("OPEN","INVITE") && b.get("deleted") is Boolean && b.get("version") is Number && b.getLong("version") in 1..Int.MAX_VALUE && b.getDouble("version")==b.getLong("version").toDouble())
        plainName(name)
        time(b.getString("issuedAt"),b.getString("expiresAt"),21600,now)
        val owner=profile(b.getJSONObject("owner"),now); require(Protocol.verify(b,policy.getString("signature"),owner.getJSONObject("body").getJSONObject("publicKey")))
        val members=b.getJSONArray("members").objects(); require(members.size in 1..16)
        members.forEach { it.exact("profile","role","joinedAt","removedAt"); profile(it.getJSONObject("profile"),now); require(it.getString("role") in ChannelGovernance.roles); require(Instant.parse(it.getString("joinedAt")) <= now.plusSeconds(300)); if(!it.isNull("removedAt")) require(Instant.parse(it.getString("removedAt")) >= Instant.parse(it.getString("joinedAt")) && Instant.parse(it.getString("removedAt"))<=now.plusSeconds(300)) }
        require(members.map { participant(it.getJSONObject("profile")) }.distinct().size==members.size)
        require(members.count { it.getString("role")=="OWNER" }==1 && members.any { it.getString("role")=="OWNER" && participant(it.getJSONObject("profile"))==participant(owner) && it.isNull("removedAt") })
        val active=members.filter { it.isNull("removedAt") }.map { participant(it.getJSONObject("profile")) }.sorted()
        ChannelGovernance.settings(b,active)
        val keys=b.getJSONArray("keys").objects(); require(keys.size<=16)
        keys.forEach { it.exact("participantId","jwe"); id(it.getString("participantId")); require(text(it,"jwe",2048).length>=100) }
        keys.forEach{header(it.getString("jwe"),"ECDH-ES","channel:${b.getString("id")}:${b.getString("epoch")}:${it.getString("participantId")}")}
        val readers=active.filter{ChannelGovernance.capabilities(obj("body" to JSONObject(b.toString()).put("deleted",false)),it).getBoolean("canRead")}
        require(if(b.getString("visibility")=="OPEN") keys.isEmpty() else keys.map { it.getString("participantId") }.sorted()==readers)
        return policy
    }
    fun invite(invite: JSONObject, recipient: String, now: Instant=Instant.now()): JSONObject {
        if(invite.getJSONObject("body").optString("kind")=="CHAT_ADMISSION") return ChannelGovernance.admission(invite,recipient,now)
        val b=signed(invite); b.exact("v","kind","id","policy","recipientId","issuedAt","expiresAt")
        require(b.get("v")==1 && b.getString("kind")=="CHAT_INVITE"); uuid(b.getString("id"))
        time(b.getString("issuedAt"),b.getString("expiresAt"),21600,now)
        val p=policy(b.getJSONObject("policy"),now)
        require(b.getString("recipientId")==recipient && member(p,recipient) && Instant.parse(b.getString("expiresAt"))<=Instant.parse(p.getJSONObject("body").getString("expiresAt")))
        require(Protocol.verify(b,invite.getString("signature"),p.getJSONObject("body").getJSONObject("owner").getJSONObject("body").getJSONObject("publicKey")))
        return invite
    }
    fun message(message: JSONObject, policy: JSONObject?, now: Instant=Instant.now(), history: Boolean=false): JSONObject {
        require(Protocol.canonical(message).size<=22000)
        val b=signed(message); b.exactOptional(listOf("v","kind","id","conversationId","author","recipientId","policyHash","channelVersion","epoch","sequence","createdAt","expiresAt","format","encrypted","content"),listOf("threadRootId"))
        if(b.has("threadRootId"))uuid(b.getString("threadRootId"))
        require(b.get("v")==1 && b.getString("kind")=="CHAT_MESSAGE"); uuid(b.getString("id"))
        text(b,"conversationId",80); text(b,"content",14000); require(b.get("encrypted") is Boolean && b.get("sequence") is Number && b.getDouble("sequence")==b.getLong("sequence").toDouble() && b.getLong("sequence") in 1..Int.MAX_VALUE)
        time(b.getString("createdAt"),b.getString("expiresAt"),604800,now)
        require(b.getString("format") in listOf("TEXT","PHOTO","VIDEO","VOICE","FILE","SYSTEM","RELIEF"))
        val author=profile(b.getJSONObject("author"),now); require(Protocol.verify(b,message.getString("signature"),author.getJSONObject("body").getJSONObject("publicKey")))
        if(!b.isNull("recipientId")) {
            require(b.getString("conversationId")==dm(participant(author),b.getString("recipientId")) && b.getBoolean("encrypted") && b.isNull("policyHash") && b.get("channelVersion")==0 && b.isNull("epoch") && !b.has("threadRootId"))
        } else {
            val p=policy ?: error("Channel membership is unavailable.")
            policy(p,if(history) Instant.parse(b.getString("createdAt")) else now); val pb=p.getJSONObject("body")
            require(member(p,participant(author)) && b.getString("conversationId")==pb.getString("id") && b.getString("policyHash")==Protocol.hash(p) && b.getInt("channelVersion")==pb.getInt("version") && b.getString("epoch")==pb.getString("epoch") && b.getBoolean("encrypted")==(pb.getString("visibility")=="INVITE"))
            require(Instant.parse(b.getString("createdAt"))>=Instant.parse(pb.getString("issuedAt")) && Instant.parse(b.getString("createdAt"))<Instant.parse(pb.getString("expiresAt")))
            val caps=ChannelGovernance.capabilities(p,participant(author))
            require(caps.getBoolean(if(b.has("threadRootId"))"canReplyInThreads" else "canPostTopLevel") && (b.getString("format") !in listOf("PHOTO","VIDEO","VOICE","FILE") || caps.getBoolean("canAttachMedia"))) {"Your channel role does not permit this post."}
        }
        if(b.getBoolean("encrypted"))header(b.getString("content"),if(b.isNull("recipientId"))"dir" else "ECDH-ES",if(b.isNull("recipientId"))"channel:${b.getString("conversationId")}:${b.getString("epoch")}:${b.getString("id")}" else "dm:${b.getString("conversationId")}:${b.getString("id")}:${b.getString("recipientId")}")
        else payload(JSONObject(b.getString("content")),b.getString("format"))
        return message
    }
    fun receipt(receipt: JSONObject, message: JSONObject, policy: JSONObject?, now: Instant=Instant.now()): JSONObject {
        val b=signed(receipt); b.exact("v","kind","messageId","conversationId","recipient","status","recordedAt","messageHash"); val m=message.getJSONObject("body")
        require(b.get("v")==1 && b.getString("kind")=="CHAT_RECEIPT" && b.getString("status") in listOf("DELIVERED","READ") && Instant.parse(b.getString("recordedAt"))<=now.plusSeconds(300))
        val person=profile(b.getJSONObject("recipient"),now)
        require(b.getString("messageId")==m.getString("id") && b.getString("conversationId")==m.getString("conversationId") && b.getString("messageHash")==Protocol.hash(message))
        require(if(!m.isNull("recipientId")) participant(person)==m.getString("recipientId") else policy!=null && member(policy,participant(person)))
        require(Protocol.verify(b,receipt.getString("signature"),person.getJSONObject("body").getJSONObject("publicKey")))
        return receipt
    }
    fun join(join: JSONObject, now: Instant=Instant.now()): JSONObject {
        val b=signed(join); b.exactOptional(listOf("v","kind","id","channelId","participant","action","issuedAt","expiresAt"),listOf("invitation"))
        require(b.get("v")==1 && b.getString("kind")=="CHAT_JOIN" && b.getString("action") in listOf("JOIN","LEAVE")); uuid(b.getString("id")); uuid(b.getString("channelId"))
        time(b.getString("issuedAt"),b.getString("expiresAt"),21600,now); val p=profile(b.getJSONObject("participant"),now)
        b.optJSONObject("invitation")?.let {ChannelGovernance.admission(it,participant(p),now);require(b.getString("action")=="JOIN" && it.getJSONObject("body").getString("channelId")==b.getString("channelId"))}
        require(Protocol.verify(b,join.getString("signature"),p.getJSONObject("body").getJSONObject("publicKey")))
        return join
    }
    fun payload(payload: JSONObject,format:String?=null): JSONObject {
        require(Protocol.canonical(payload).size<=12000)
        require(payload.keys().asSequence().all { it in listOf("text","attachment","reference","mentions") })
        if(payload.has("text")) text(payload,"text",4000)
        if(payload.has("mentions")) { val ids=payload.getJSONArray("mentions").strings(); require(ids.size<=8 && ids.distinct().size==ids.size); ids.forEach { id(it) } }
        if(payload.has("reference")) { val r=payload.getJSONObject("reference"); r.exact("type","id","title"); require(r.getString("type") in listOf("NEED","UPDATE")); text(r,"id",80); text(r,"title",120) }
        if(payload.has("attachment")) { val a=payload.getJSONObject("attachment"); a.exact("id","name","mime","size","hash","cipherHash","key"); uuid(a.getString("id")); text(a,"name",100); require(a.getString("mime") in listOf("image/jpeg","image/png","image/webp","text/plain","audio/mp4","audio/mpeg","video/mp4","video/webm")); require(a.get("size") is Number && a.getLong("size") in 1..16777188 && a.getDouble("size")==a.getLong("size").toDouble()); id(a.getString("hash")); id(a.getString("cipherHash")); require(Protocol.decode(a.getString("key")).size==32) }
        require(payload.has("text") || payload.has("attachment") || payload.has("reference"))
        if(format!=null){
            require(when(format){"TEXT","SYSTEM"->payload.has("text");"RELIEF"->payload.has("reference");"PHOTO","VIDEO","VOICE","FILE"->payload.has("attachment");else->false})
            val mime=payload.optJSONObject("attachment")?.optString("mime")?:""
            require(when(format){"PHOTO"->mime.startsWith("image/");"VIDEO"->mime.startsWith("video/");"VOICE"->mime.startsWith("audio/");else->true})
        }
        return payload
    }
}
