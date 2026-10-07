package org.saathi.android

import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Capability ceilings mirror the shared protocol. Channel roles never grant relief authority. */
object ChannelGovernance {
    val roles=listOf("OWNER","ADMIN","MODERATOR","MEMBER","READ_ONLY")
    val capabilityNames=listOf("canRead","canPostTopLevel","canReplyInThreads","canCreateThreads","canAttachMedia","canReact","canInvite","canModerate","canStartCalls","canJoinCalls","canManageMembers")
    val membershipActions=listOf("APPROVE_JOIN","REJECT_JOIN","REMOVE","BAN","UNBAN","SET_ROLE")
    val actions=membershipActions+listOf("LOCK_THREAD","UNLOCK_THREAD","HIDE_MESSAGE","RESTORE_MESSAGE","REVIEW_REPORT","REACT","UNREACT")
    fun capabilities(policy:JSONObject,person:String):JSONObject {
        val b=policy.getJSONObject("body")
        val role=b.getJSONArray("members").objects().firstOrNull {ChatProtocol.participant(it.getJSONObject("profile"))==person && it.isNull("removedAt")}?.getString("role")
        val admitted=role!=null && !b.getBoolean("deleted") && b.optJSONArray("bannedIds")?.strings()?.contains(person)!=true
        val manager=role in listOf("OWNER","ADMIN");val moderator=manager || role=="MODERATOR";val writer=admitted && role!="READ_ONLY"
        val ceiling=obj("canRead" to admitted,"canPostTopLevel" to (writer && (b.optJSONObject("settings")?.optString("mode")!="ANNOUNCEMENT" || moderator)),"canReplyInThreads" to writer,"canCreateThreads" to writer,"canAttachMedia" to writer,"canReact" to admitted,"canInvite" to (admitted && manager),"canModerate" to (admitted && moderator),"canStartCalls" to false,"canJoinCalls" to false,"canManageMembers" to (admitted && moderator))
        val configured=if(role=="OWNER")null else b.optJSONObject("settings")?.optJSONObject("capabilities")?.optJSONObject(role?:"")
        capabilityNames.forEach {key->ceiling.put(key,ceiling.getBoolean(key) && (configured?.optBoolean(key) ?: if(key=="canManageMembers")manager else true))}
        if(!ceiling.getBoolean("canRead"))capabilityNames.forEach{ceiling.put(it,false)}
        return ceiling
    }
    private fun ids(b:JSONObject,key:String,max:Int,pattern:Regex) {
        b.optJSONArray(key)?.let {a->val values=a.strings();require(values.size<=max && values.distinct().size==values.size && values.all {it.matches(pattern)})}
    }
    private val identity=Regex("[a-f0-9]{64}")
    private val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    fun settings(body:JSONObject,active:List<String>) {
        ids(body,"bannedIds",100,identity);ids(body,"appliedActions",100,uuid)
        require(active.none {body.optJSONArray("bannedIds")?.strings()?.contains(it)==true})
        body.optJSONObject("moderation")?.let {m->m.exact("lockedThreads","hiddenMessages");ids(m,"lockedThreads",100,uuid);ids(m,"hiddenMessages",100,uuid)}
        body.optJSONObject("settings")?.let {s->
            s.exactOptional(listOf("mode","admission"),listOf("capabilities"));require(s.getString("mode") in listOf("DISCUSSION","ANNOUNCEMENT"))
            require(s.getString("admission") in if(body.getString("visibility")=="OPEN")listOf("OPEN","APPROVAL_ONLY")else listOf("INVITE_AUTO","INVITE_PLUS_APPROVAL","APPROVAL_ONLY"))
            s.optJSONObject("capabilities")?.let {c->require(c.keys().asSequence().all {it in roles.drop(1)});c.keys().asSequence().forEach {r->val caps=c.getJSONObject(r);caps.exact(*capabilityNames.toTypedArray());require(capabilityNames.all {caps.get(it) is Boolean})}}
        }
    }
    /** A reusable join link ("*" admission) may last 7 days; every other invitation or action at most six hours. */
    const val JOIN_LINK_SECONDS=7*86400L
    private fun bounded(body:JSONObject,now:Instant,max:Long=21600) {
        val issued=Instant.parse(body.getString("issuedAt"));val expires=Instant.parse(body.getString("expiresAt"))
        require(issued<=now.plusSeconds(300) && expires>now && expires>issued && expires<=issued.plusSeconds(max)) {"This invitation or action has expired. Ask for a new one."}
        require(UUID.fromString(body.getString("id")).toString()==body.getString("id"))
    }
    fun admission(value:JSONObject,recipient:String,now:Instant):JSONObject {
        value.exact("body","signature");val b=value.getJSONObject("body")
        b.exactOptional(listOf("v","kind","id","channelId","name","owner","recipientId","policyHash","admission","issuedAt","expiresAt"),listOf("issuer"))
        val name=b.getString("name");require(name==name.trim() && name.length in 1..48 && !Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]").containsMatchIn(name))
        require(b.get("v")==1 && b.getString("kind")=="CHAT_ADMISSION" && b.getString("channelId").matches(uuid) && b.getString("policyHash").matches(identity))
        require(b.getString("admission") in listOf("INVITE_PLUS_APPROVAL","APPROVAL_ONLY"));bounded(b,now,if(b.getString("recipientId")=="*")JOIN_LINK_SECONDS else 21600)
        ChatProtocol.profile(b.getJSONObject("owner"),now);val issuer=ChatProtocol.profile(b.optJSONObject("issuer")?:b.getJSONObject("owner"),now)
        require(b.getString("recipientId")==recipient || b.getString("recipientId")=="*") {"This invitation is for a different participant."}
        require(Protocol.verify(b,value.getString("signature"),issuer.getJSONObject("body").getJSONObject("publicKey"))) {"This invitation could not be authenticated."}
        return value
    }
    fun action(value:JSONObject,policy:JSONObject,now:Instant):JSONObject {
        value.exact("body","signature");val b=value.getJSONObject("body")
        b.exactOptional(listOf("v","kind","id","channelId","actor","policyHash","version","action","targetId","issuedAt","expiresAt"),listOf("role","reaction"))
        require(b.get("v")==1 && b.getString("kind")=="CHAT_ACTION" && b.getString("action") in actions);bounded(b,now);ChatProtocol.policy(policy,now)
        require((b.get("version") is Int || b.get("version") is Long) && b.getLong("version") in 1..1000000)
        val actor=ChatProtocol.profile(b.getJSONObject("actor"),now);val person=ChatProtocol.participant(actor);val pb=policy.getJSONObject("body")
        require(b.getString("channelId")==pb.getString("id") && b.getInt("version")==pb.getInt("version") && b.getString("policyHash")==Protocol.hash(policy) && Protocol.verify(b,value.getString("signature"),actor.getJSONObject("body").getJSONObject("publicKey"))) {"Channel permissions have changed. Check again."}
        val caps=capabilities(policy,person);val a=b.getString("action");val membership=a in membershipActions;val reaction=a in listOf("REACT","UNREACT")
        require(caps.getBoolean(if(membership)"canManageMembers" else if(reaction)"canReact" else "canModerate")) {"Your channel role does not permit this action."}
        val role=pb.getJSONArray("members").objects().firstOrNull {ChatProtocol.participant(it.getJSONObject("profile"))==person}?.getString("role")
        val target=pb.getJSONArray("members").objects().firstOrNull {ChatProtocol.participant(it.getJSONObject("profile"))==b.getString("targetId")}?.getString("role")
        require(if(membership)b.getString("targetId").matches(identity) else b.getString("targetId").matches(uuid))
        require(!membership || (target!="OWNER" && (target!="ADMIN" || role=="OWNER")))
        require((a=="SET_ROLE")==b.has("role") && reaction==b.has("reaction"))
        if(a=="SET_ROLE")require(b.getString("role") in roles.drop(1) && role!="MODERATOR" && (b.getString("role")!="ADMIN" || role=="OWNER"))
        if(reaction)require(b.getString("reaction") in listOf("THANKS","SUPPORT"))
        return value
    }
}

/** Group types, built from existing channel settings: FREE (everyone posts), ANNOUNCE (admins post, members reply in
 *  threads) and VIEW (admins post; members read and react only). */
object GroupType {
    private val viewOnly=obj("canRead" to true,"canPostTopLevel" to false,"canReplyInThreads" to false,"canCreateThreads" to false,"canAttachMedia" to false,
        "canReact" to true,"canInvite" to false,"canModerate" to false,"canStartCalls" to false,"canJoinCalls" to false,"canManageMembers" to false)
    fun settings(type:String,admission:String)=obj("mode" to if(type=="FREE")"DISCUSSION" else "ANNOUNCEMENT","admission" to admission).apply{if(type=="VIEW")put("capabilities",obj("MEMBER" to JSONObject(viewOnly.toString())))}
    fun of(settings:JSONObject?)=when{
        settings?.optString("mode")!="ANNOUNCEMENT"->"FREE"
        settings.optJSONObject("capabilities")?.optJSONObject("MEMBER")?.optBoolean("canReplyInThreads",true)==false->"VIEW"
        else->"ANNOUNCE"
    }
    val labels=listOf("FREE" to "Free chat · everyone can post","ANNOUNCE" to "Admins post · members reply in threads","VIEW" to "View only · only admins post")
}
