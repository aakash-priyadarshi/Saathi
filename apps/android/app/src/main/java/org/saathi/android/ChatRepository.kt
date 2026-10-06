package org.saathi.android

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.util.UUID
import java.security.SecureRandom
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.GZIPInputStream
import android.provider.OpenableColumns
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec

/** Stable conversations over confirmed peers and signed HTTP. All local chat records are encrypted. */
class ChatRepository(private val context: Context, private val repository: Repository, private val session: PeerSession) {
    private val store get()=repository.store
    private val lock=Mutex()
    private val mediaLock=Mutex()
    private val account="swarm-chat"
    var onChange: ()->Unit = {}
    @Volatile private var heldPeer: JSONObject? = null
    @Volatile private var peerGeneration = -1L
    var peer: JSONObject?
        get() = if(peerGeneration==session.connectionGeneration && session.confirmed)heldPeer else null
        private set(value) { heldPeer=value }
    private var heldDiscovery: List<JSONObject> = emptyList()
    var discovery: List<JSONObject>
        get() = if(peer!=null)heldDiscovery else emptyList()
        private set(value) { heldDiscovery=value }
    var onInvite: (String)->Unit = {}
    var onIncoming: (String)->Unit = {}
    private fun now()=repository.clock()
    private fun save(bucket:String,id:String,value:JSONObject) {
        if(bucket=="chat-receipts" && store.get(bucket,id)==null && store.all(bucket).size>=500){
            store.all(bucket).filter{it.optBoolean("serverSaved") || ChatProtocol.participant(it.getJSONObject("receipt").getJSONObject("body").getJSONObject("recipient"))!=self()}.minByOrNull{it.getJSONObject("receipt").getJSONObject("body").getString("recordedAt")}?.let{store.remove(bucket,it.getString("id"))}
        }
        require(store.get(bucket,id)!=null || store.all(bucket).size<500) { "Chat storage is full. Review and clear an old conversation first." }
        store.put(bucket,id,value)
    }
    fun profile(): JSONObject = synchronized(store) {
        val saved=store.get("chat","profile")
        if(saved!=null) { require(store.hasIdentity(account)) { "Chat identity is unavailable. Saved chats were not reset." }; return@synchronized saved }
        store.ensureIdentity(account)
        val encryption=ChatProtocol.newEncryptionKey(); store.put("chat","encryption",encryption)
        val key=Protocol.publicJwk(store.publicKey(account))
        val body=obj("v" to 1,"kind" to "CHAT_PROFILE","id" to Protocol.hash(key),"name" to "Nearby participant","publicKey" to key,"encryptionKey" to ChatProtocol.encryptionPublic(encryption),"updatedAt" to now().toString())
        signed(body).also { store.put("chat","profile",it) }
    }
    private fun signed(body: JSONObject)=obj("body" to body,"signature" to Protocol.sign(body,store.privateKey(account)))
    private fun self()=ChatProtocol.participant(profile())
    fun conversations()=store.all("chat-conversations")
    fun messages()=store.all("chat-messages").filter { Instant.parse(it.getJSONObject("envelope").getJSONObject("body").getString("expiresAt"))>now() }
    fun contacts()=store.all("chat-contacts").filter { store.get("chat-blocks",it.getString("id"))==null }
    fun policies()=store.all("chat-policies").map { it.getJSONObject("policy") }
    fun blocked(id:String)=store.get("chat-blocks",id)!=null
    fun reset(){ peer=null; discovery=emptyList(); onChange() }
    private fun remember(profile:JSONObject) {
        ChatProtocol.profile(profile,now()); val id=ChatProtocol.participant(profile)
        val old=store.get("chat-contacts",id)?.getJSONObject("profile")
        require(old==null || Protocol.hash(old.getJSONObject("body").getJSONObject("encryptionKey"))==Protocol.hash(profile.getJSONObject("body").getJSONObject("encryptionKey"))) { "This person's chat identity changed. Compare identities again." }
        if(old==null || Instant.parse(profile.getJSONObject("body").getString("updatedAt"))>=Instant.parse(old.getJSONObject("body").getString("updatedAt"))) save("chat-contacts",id,obj("id" to id,"profile" to profile))
    }
    suspend fun rename(name:String)=withContext(Dispatchers.IO) { lock.withLock {
        require(name.trim().length in 1..32); val body=JSONObject(profile().getJSONObject("body").toString()).put("name",name.trim()).put("updatedAt",now().toString())
        store.put("chat","profile",signed(body)); announceUnlocked(); onChange()
    } }
    suspend fun direct(person:JSONObject): String=withContext(Dispatchers.IO) { lock.withLock {
        remember(person); val id=ChatProtocol.dm(self(),ChatProtocol.participant(person))
        if(store.get("chat-conversations",id)==null) save("chat-conversations",id,obj("id" to id,"type" to "DIRECT","peerId" to ChatProtocol.participant(person),"title" to person.getJSONObject("body").getString("name"),"muted" to false,"joined" to true,"lastRead" to Instant.EPOCH.toString()))
        onChange(); id
    } }
    private fun current(id:String)=store.get("chat-policies",id)?.getJSONObject("policy")
    fun fileAllowed(id:String,hash:String):Boolean {
        val person=peer?.let { ChatProtocol.participant(it) }?:return false
        if(!session.confirmed || blocked(person))return false
        return messages().any { record->
            val a=record.getJSONObject("payload").optJSONObject("attachment");val b=record.getJSONObject("envelope").getJSONObject("body")
            a?.optString("id")==id && a.optString("cipherHash")==hash && if(!b.isNull("recipientId")) {
                val other=if(record.optBoolean("owned"))b.getString("recipientId") else ChatProtocol.participant(b.getJSONObject("author"));other==person
            }else{val p=current(b.getString("conversationId"));p!=null && live(p) && ChatProtocol.member(p,person)}
        }
    }
    suspend fun attach(conversationId:String,uri:Uri,mimeOverride:String?=null,nameOverride:String?=null)=withContext(Dispatchers.IO){lock.withLock{
        val mime=mimeOverride?:context.contentResolver.getType(uri)?:error("Choose a photo, audio, video or text file.")
        require(mime in listOf("image/jpeg","image/png","image/webp","audio/mp4","audio/mpeg","video/mp4","video/webm","text/plain"))
        val name=nameOverride?:context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { require(it.moveToFirst());it.getString(0) }?: "Attachment"
        val output=ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { input->val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=16777188){"Choose a file smaller than 16 MB."};output.write(buffer,0,n)}}?:error("Cannot read this attachment.")
        val plain=output.toByteArray();require(plain.isNotEmpty()); val id=UUID.randomUUID().toString();val key=ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"));cipher.updateAAD(("SWARM_ATTACHMENT_V1/"+id).toByteArray());val encrypted=cipher.iv+cipher.doFinal(plain)
        val file=session.saveChatBytes(id,encrypted)
        val attachment=obj("id" to id,"name" to name.take(100),"mime" to mime,"size" to plain.size,"hash" to Protocol.digest(plain),"cipherHash" to Protocol.digest(encrypted),"key" to Protocol.b64(key))
        val format=if(mime.startsWith("image/"))"PHOTO" else if(mime.startsWith("video/"))"VIDEO" else if(mime.startsWith("audio/"))"VOICE" else "FILE"
        try {
            val messageId=sendUnlocked(conversationId,obj("attachment" to attachment),format)
            val envelope=store.get("chat-messages",messageId)!!.getJSONObject("envelope")
            val manifest=signed(obj("v" to 1,"kind" to "CHAT_ATTACHMENT","id" to id,"messageId" to messageId,"messageHash" to Protocol.hash(envelope),"author" to profile(),"size" to encrypted.size,"cipherHash" to Protocol.digest(encrypted),"expiresAt" to envelope.getJSONObject("body").getString("expiresAt")))
            save("chat-manifests",messageId,obj("id" to messageId,"manifest" to manifest))
            if(fileAllowed(id,file.getString("hash")))runCatching {session.send("CHAT_ATTACHMENT_META",manifest);session.offerSaved(file)}
        }catch(e:Exception){session.removeFile(id);throw e}
        onChange()
    }}
    suspend fun attachmentBytes(messageId:String)=withContext(Dispatchers.IO){
        val record=store.get("chat-messages",messageId)?:error("Message is unavailable.")
        val a=record.getJSONObject("payload").getJSONObject("attachment");val encrypted=session.readSavedBytes(a.getString("id"))
        require(Protocol.digest(encrypted)==a.getString("cipherHash") && encrypted.size==a.getInt("size")+28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(Protocol.decode(a.getString("key")),"AES"),GCMParameterSpec(128,encrypted.copyOfRange(0,12)));cipher.updateAAD(("SWARM_ATTACHMENT_V1/"+a.getString("id")).toByteArray())
        cipher.doFinal(encrypted.copyOfRange(12,encrypted.size)).also {require(it.size==a.getInt("size") && Protocol.digest(it)==a.getString("hash")){"Private attachment verification failed."}}
    }
    suspend fun completeFile(id:String){
        val record=messages().firstOrNull { it.getJSONObject("payload").optJSONObject("attachment")?.optString("id")==id }?:return
        attachmentBytes(record.getString("id"));val file=store.get("attachments",id)!!;file.put("chatOnly",true);store.put("attachments",id,file);onChange()
    }
    suspend fun exportAttachment(messageId:String,destination:Uri)=withContext(Dispatchers.IO){
        val bytes=attachmentBytes(messageId);context.contentResolver.openOutputStream(destination,"wt")?.use {it.write(bytes)}?:error("The selected destination cannot be written.")
    }
    suspend fun offerAttachment(messageId:String)=withContext(Dispatchers.IO){
        val record=store.get("chat-messages",messageId)?:error("Message is unavailable.");val a=record.getJSONObject("payload").getJSONObject("attachment")
        val file=store.get("attachments",a.getString("id"))?:error("Wait for a nearby member with the attachment.");require(fileAllowed(a.getString("id"),a.getString("cipherHash")))
        session.offerSaved(file)
    }
    private fun verifyManifest(manifest:JSONObject,record:JSONObject):JSONObject {
        manifest.exact("body","signature");val b=manifest.getJSONObject("body");b.exact("v","kind","id","messageId","messageHash","author","size","cipherHash","expiresAt")
        val envelope=record.getJSONObject("envelope");val a=record.getJSONObject("payload").getJSONObject("attachment")
        ChatProtocol.profile(b.getJSONObject("author"),now())
        require(b.get("v")==1 && b.getString("kind")=="CHAT_ATTACHMENT" && b.getString("id")==a.getString("id") && b.getString("messageId")==record.getString("id") && b.getString("messageHash")==Protocol.hash(envelope)
            && ChatProtocol.participant(b.getJSONObject("author"))==ChatProtocol.participant(envelope.getJSONObject("body").getJSONObject("author"))
            && b.getInt("size")==a.getInt("size")+28 && b.getString("cipherHash")==a.getString("cipherHash")
            && Instant.parse(b.getString("expiresAt"))<=Instant.parse(envelope.getJSONObject("body").getString("expiresAt")) && Instant.parse(b.getString("expiresAt"))>now()
            && Protocol.verify(b,manifest.getString("signature"),b.getJSONObject("author").getJSONObject("body").getJSONObject("publicKey"))) {"Private attachment manifest verification failed."}
        return manifest
    }
    /** One bounded batch per automatic check; explicit media sync can continue resumably. */
    suspend fun synchronizeAttachment(messageId:String,complete:Boolean=false)=withContext(Dispatchers.IO){mediaLock.withLock{
        var uploadBytes:ByteArray?=null
        while(true){
            val record=store.get("chat-messages",messageId)?:error("Message is unavailable.");require(record.optBoolean("serverSaved")&&!record.optBoolean("attention")){"Check chat delivery before synchronizing this attachment."}
            val a=record.getJSONObject("payload").getJSONObject("attachment");val file=store.get("attachments",a.getString("id"))
            val count=(a.getInt("size")+28+8191)/8192
            val manifest=store.get("chat-manifests",messageId)?.getJSONObject("manifest")
            val upload=file?.optBoolean("complete")==true && manifest!=null
            val progress=store.get("chat-media-progress",messageId)?.optJSONArray("received")?.let{it.strings().map(String::toInt).toSet()}?:emptySet()
            val missing=(0 until count).filter{if(upload)it !in progress else file?.optJSONArray("received")?.optBoolean(it)!=true}.take(6)
            if(upload&&missing.isEmpty() || !upload&&file?.optBoolean("complete")==true)return@withLock
            val chunks=JSONArray()
            if(upload){if(uploadBytes==null)uploadBytes=session.readSavedBytes(a.getString("id"));val bytes=uploadBytes;for(part in missing)chunks.put(obj("part" to part,"data" to Protocol.b64(bytes.copyOfRange(part*8192,minOf(bytes.size,(part+1)*8192)))))}
            val body=obj("v" to 1,"kind" to "CHAT_ATTACHMENT_REQUEST","profile" to profile(),"issuedAt" to now().toString(),"messageId" to messageId,"manifest" to if(upload)manifest else null,"parts" to JSONArray(if(upload)emptyList<Int>() else missing),"chunks" to chunks)
            val response=JSONObject(repository.api("/chat/attachment",signed(body),false));require(response.getInt("v")==1)
            val verified=verifyManifest(response.getJSONObject("manifest"),record);save("chat-manifests",messageId,obj("id" to messageId,"manifest" to verified))
            val received=response.getJSONArray("received");require(received.length()<=count);val indices=(0 until received.length()).map{received.getInt(it)};require(indices.distinct().size==indices.size&&indices.all{it in 0 until count})
            save("chat-media-progress",messageId,obj("id" to messageId,"received" to JSONArray(indices.map{it.toString()})))
            if(!upload){
                val parts=response.getJSONArray("chunks").objects();require(parts.size<=6)
                session.saveServerChatChunks(a.getString("id"),a.getInt("size")+28,a.getString("cipherHash"),parts.map{it.exact("part","data");require(it.getInt("part") in missing);it.getInt("part") to Protocol.decode(it.getString("data"))})
                if(store.get("attachments",a.getString("id"))?.optBoolean("complete")==true){completeFile(a.getString("id"));return@withLock}
                if(parts.isEmpty())return@withLock
            }
            onChange();if(!complete || (upload&&indices.size==count))return@withLock
            // Fits the operational request limit; text/event work runs on separate coroutines.
            kotlinx.coroutines.delay(750)
        }
    }}
    suspend fun autoMedia(){
        if(mediaLock.isLocked)return
        val next=messages().firstOrNull{m->val a=m.getJSONObject("payload").optJSONObject("attachment");a!=null&&m.optBoolean("serverSaved")&&!m.optBoolean("attention")&&
            (store.get("attachments",a.getString("id"))?.optBoolean("complete")!=true || (store.get("chat-manifests",m.getString("id"))!=null && (store.get("chat-media-progress",m.getString("id"))?.optJSONArray("received")?.length()?:0)<(a.getInt("size")+28+8191)/8192))}
        if(next!=null)runCatching{synchronizeAttachment(next.getString("id"))}
    }
    private fun live(policy:JSONObject)=Instant.parse(policy.getJSONObject("body").getString("expiresAt"))>now() && ChatProtocol.member(policy,self()) && store.get("chat-conversations",policy.getJSONObject("body").getString("id"))?.optBoolean("joined")==true
    private fun archive(policy:JSONObject) {
        val hash=Protocol.hash(policy); save("chat-policy-history",hash,obj("id" to hash,"policy" to policy))
        val b=policy.getJSONObject("body"); b.getJSONArray("keys").objects().firstOrNull { it.getString("participantId")==self() }?.let {
            val kid="channel:"+b.getString("id")+":"+b.getString("epoch")+":"+self()
            val value=ChatProtocol.decrypt(it.getString("jwe"),store.get("chat","encryption")!!,null,kid)
            value.exact("key"); require(Protocol.decode(value.getString("key")).size==32)
            save("chat-keys",hash,obj("id" to hash,"key" to value.getString("key")))
        }
    }
    private fun applyPolicy(policy:JSONObject, consent:Boolean=false) {
        val b=policy.getJSONObject("body")
        require(Instant.parse(b.getString("issuedAt"))<=now().plusSeconds(300))
        ChatProtocol.policy(policy,Instant.parse(b.getString("issuedAt")))
        val id=b.getString("id"); val old=current(id); val conversation=store.get("chat-conversations",id)
        if(old!=null) {
            val previous=old.getJSONObject("body")
            require(ChatProtocol.participant(previous.getJSONObject("owner"))==ChatProtocol.participant(b.getJSONObject("owner")) && previous.getString("visibility")==b.getString("visibility"))
            if(b.getInt("version")<previous.getInt("version") || previous.getBoolean("deleted")&&!b.getBoolean("deleted")) return
            require(b.getInt("version")!=previous.getInt("version") || Protocol.hash(old)==Protocol.hash(policy)) { "Conflicting channel membership. Sending is paused." }
        }
        require(conversation!=null || consent) { "Join this channel before accepting its history." }
        archive(policy); save("chat-policies",id,obj("id" to id,"policy" to policy))
        b.getJSONArray("members").objects().forEach { remember(it.getJSONObject("profile")) }
        val joined=ChatProtocol.member(policy,self()) && (consent || conversation?.optBoolean("joined")==true || conversation?.optBoolean("pendingJoin")==true)
        val next=conversation?:obj("id" to id,"type" to "CHANNEL","muted" to false,"lastRead" to Instant.EPOCH.toString())
        next.put("title",b.getString("name")).put("joined",joined).put("pendingJoin",false).put("deleted",b.getBoolean("deleted")).put("visibility",b.getString("visibility")).put("ownerId",ChatProtocol.participant(b.getJSONObject("owner")))
        save("chat-conversations",id,next)
        if(!joined) store.remove("chat-keys",Protocol.hash(policy))
    }
    private fun revised(previous:JSONObject?,name:String,visibility:String,members:JSONArray,deleted:Boolean=false):JSONObject {
        val id=previous?.getJSONObject("body")?.getString("id")?:UUID.randomUUID().toString()
        val epoch=UUID.randomUUID().toString(); val key=ByteArray(32).also { SecureRandom().nextBytes(it) }
        val keys=JSONArray()
        if(visibility=="INVITE") members.objects().filter { it.isNull("removedAt") }.forEach {
            val person=it.getJSONObject("profile"); val personId=ChatProtocol.participant(person)
            keys.put(obj("participantId" to personId,"jwe" to ChatProtocol.encrypt(obj("key" to Protocol.b64(key)),person.getJSONObject("body").getJSONObject("encryptionKey"),null,"channel:$id:$epoch:$personId")))
        }
        val issued=now(); val body=obj("v" to 1,"kind" to "CHAT_CHANNEL","id" to id,"name" to name,"visibility" to visibility,"owner" to profile(),"version" to ((previous?.getJSONObject("body")?.getInt("version")?:0)+1),"epoch" to epoch,"issuedAt" to issued.toString(),"expiresAt" to issued.plusSeconds(21600).toString(),"deleted" to deleted,"members" to members,"keys" to keys)
        val policy=signed(body); ChatProtocol.policy(policy,issued); return policy
    }
    suspend fun create(name:String,visibility:String):String=withContext(Dispatchers.IO) { lock.withLock {
        require(name.trim().length in 1..48 && visibility in listOf("OPEN","INVITE") && conversations().count { it.getString("type")=="CHANNEL"&&it.optBoolean("joined") }<16)
        val members=JSONArray().put(obj("profile" to profile(),"role" to "OWNER","joinedAt" to now().toString(),"removedAt" to null))
        val policy=revised(null,name.trim(),visibility,members); applyPolicy(policy,true); announceUnlocked(); onChange(); policy.getJSONObject("body").getString("id")
    } }
    private fun makeJoin(id:String,action:String):JSONObject {
        val time=now(); return signed(obj("v" to 1,"kind" to "CHAT_JOIN","id" to UUID.randomUUID().toString(),"channelId" to id,"participant" to profile(),"action" to action,"issuedAt" to time.toString(),"expiresAt" to time.plusSeconds(21600).toString()))
    }
    suspend fun join(id:String)=withContext(Dispatchers.IO) { lock.withLock {
        UUID.fromString(id); val descriptor=discovery.firstOrNull { it.getString("id")==id }?:error("Find the channel nearby again.")
        val request=makeJoin(id,"JOIN"); save("chat-joins",id,obj("id" to id,"request" to request))
        save("chat-conversations",id,obj("id" to id,"type" to "CHANNEL","title" to descriptor.getString("name"),"muted" to false,"joined" to false,"pendingJoin" to true,"lastRead" to Instant.EPOCH.toString()))
        if(session.confirmed)session.send("CHAT_JOIN",request); onChange()
    } }
    private suspend fun handleJoin(request:JSONObject) {
        ChatProtocol.join(request,now()); val b=request.getJSONObject("body"); val p=current(b.getString("channelId"))?:return; val pb=p.getJSONObject("body")
        if(ChatProtocol.participant(pb.getJSONObject("owner"))!=self() || pb.getBoolean("deleted")) return
        val person=b.getJSONObject("participant"); val personId=ChatProtocol.participant(person); if(blocked(personId))return
        val members=JSONArray(pb.getJSONArray("members").toString()); val existing=members.objects().firstOrNull { ChatProtocol.participant(it.getJSONObject("profile"))==personId }
        if(b.getString("action")=="JOIN") {
            if(pb.getString("visibility")!="OPEN" || existing?.isNull("removedAt")==false) return
            if(existing==null) { require(members.length()<16); members.put(obj("profile" to person,"role" to "MEMBER","joinedAt" to now().toString(),"removedAt" to null)) } else { if(live(p)) { if(session.confirmed && peer?.let { ChatProtocol.participant(it) }==personId)session.send("CHAT_POLICY",p); return } }
        } else { if(personId==self() || existing==null || !existing.isNull("removedAt"))return; existing.put("removedAt",now().toString()) }
        val revised=revised(p,pb.getString("name"),pb.getString("visibility"),members); applyPolicy(revised,true)
        if(session.confirmed && peer!=null)session.send("CHAT_POLICY",revised)
    }
    suspend fun invite(id:String,person:JSONObject):String=withContext(Dispatchers.IO) { lock.withLock {
        remember(person); val old=current(id)?:error("Channel is unavailable."); val b=old.getJSONObject("body"); require(ChatProtocol.participant(b.getJSONObject("owner"))==self() && !b.getBoolean("deleted"))
        val members=JSONArray(b.getJSONArray("members").toString()); val recipient=ChatProtocol.participant(person)
        val existing=members.objects().firstOrNull { ChatProtocol.participant(it.getJSONObject("profile"))==recipient }
        if(existing==null){ require(members.length()<16); members.put(obj("profile" to person,"role" to "MEMBER","joinedAt" to now().toString(),"removedAt" to null)) } else { existing.put("removedAt",JSONObject.NULL).put("profile",person) }
        val policy=revised(old,b.getString("name"),b.getString("visibility"),members); applyPolicy(policy,true)
        val time=now(); val invite=signed(obj("v" to 1,"kind" to "CHAT_INVITE","id" to UUID.randomUUID().toString(),"policy" to policy,"recipientId" to recipient,"issuedAt" to time.toString(),"expiresAt" to policy.getJSONObject("body").getString("expiresAt")))
        val bytes=ByteArrayOutputStream(); GZIPOutputStream(bytes).use { it.write(invite.toString().toByteArray()) }
        onChange(); "cjpswarm://invite/"+Protocol.b64(bytes.toByteArray())
    } }
    fun decodeInvite(link:String):JSONObject {
        val uri=Uri.parse(link.trim()); require(uri.scheme=="cjpswarm" && uri.host=="invite" && uri.query==null && uri.fragment==null && link.length<=44000)
        val token=uri.path?.removePrefix("/")?:error("Invitation is incomplete."); require(token.length in 1..42000 && !token.contains('/'))
        val output=ByteArrayOutputStream(); GZIPInputStream(Protocol.decode(token).inputStream()).use { input -> val buffer=ByteArray(1024); while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=26000);output.write(buffer,0,n)} }
        val invite=JSONObject(output.toString(Charsets.UTF_8.name())); ChatProtocol.invite(invite,self(),now()); return invite
    }
    suspend fun acceptInvite(link:String):String=withContext(Dispatchers.IO) { lock.withLock {
        val invite=decodeInvite(link); val b=invite.getJSONObject("body"); val id=b.getJSONObject("policy").getJSONObject("body").getString("id")
        val existing=store.get("chat-invites",b.getString("id"))
        require(existing==null || existing.getString("hash")==Protocol.hash(invite))
        val p=b.getJSONObject("policy"); val old=current(id); require(old==null || old.getJSONObject("body").getInt("version")<=p.getJSONObject("body").getInt("version")) { "This invitation has been replaced. Ask for a new one." }
        applyPolicy(p,true); save("chat-invites",b.getString("id"),obj("id" to b.getString("id"),"hash" to Protocol.hash(invite),"expiresAt" to b.getString("expiresAt")))
        announceUnlocked(); onChange(); id
    } }
    suspend fun membership(id:String,personId:String?,delete:Boolean=false)=withContext(Dispatchers.IO) { lock.withLock {
        val p=current(id)?:error("Channel is unavailable."); val b=p.getJSONObject("body")
        if(ChatProtocol.participant(b.getJSONObject("owner"))==self()) {
            val members=JSONArray(b.getJSONArray("members").toString())
            if(personId!=null){ require(personId!=self()); members.objects().first { ChatProtocol.participant(it.getJSONObject("profile"))==personId }.put("removedAt",now().toString()) }
            val next=revised(p,b.getString("name"),b.getString("visibility"),members,delete)
            applyPolicy(next,true)
            // A removed member must receive the signed revocation, even though future history is denied.
            if(session.confirmed && peer?.let{ChatProtocol.member(p,ChatProtocol.participant(it))}==true)session.send("CHAT_POLICY",next)
        }else{
            require(personId==null && !delete); val request=makeJoin(id,"LEAVE"); save("chat-joins",id,obj("id" to id,"request" to request))
            val c=store.get("chat-conversations",id)!!; c.put("joined",false); save("chat-conversations",id,c)
            if(session.confirmed) session.send("CHAT_JOIN",request)
        }
        announceUnlocked(); onChange()
    } }
    suspend fun send(id:String,payload:JSONObject,format:String="TEXT"):String=withContext(Dispatchers.IO) { lock.withLock { sendUnlocked(id,payload,format) } }
    private suspend fun sendUnlocked(id:String,payload:JSONObject,format:String):String {
        ChatProtocol.payload(payload,format); val conversation=store.get("chat-conversations",id)?:error("Open a conversation first.")
        require(conversation.optBoolean("joined")) { "Join this channel before sending." }
        val direct=conversation.getString("type")=="DIRECT"; val peerId=if(direct)conversation.getString("peerId") else null
        require(peerId==null || !blocked(peerId)) { "Unblock this person before sending." }
        val p=if(direct)null else current(id)?:error("Channel membership is unavailable.")
        if(p!=null) require(live(p)) { "Waiting for the channel owner to refresh membership. Your conversation is safe." }
        val messageId=UUID.randomUUID().toString(); val pb=p?.getJSONObject("body"); val hash=p?.let { Protocol.hash(it) }
        val encrypted=direct || pb?.getString("visibility")=="INVITE"
        val kid=if(direct)"dm:$id:$messageId:$peerId" else "channel:$id:${pb!!.getString("epoch")}:$messageId"
        val recipient=if(direct) store.get("chat-contacts",peerId!!)?.getJSONObject("profile")?:error("This person's identity is unavailable.") else null
        val content=if(!encrypted) payload.toString() else ChatProtocol.encrypt(payload,recipient?.getJSONObject("body")?.getJSONObject("encryptionKey"),if(direct)null else Protocol.decode(store.get("chat-keys",hash!!)!!.getString("key")),kid)
        val time=now(); val sequence=(store.get("chat-sequences",id)?.optInt("value")?:0)+1
        val body=obj("v" to 1,"kind" to "CHAT_MESSAGE","id" to messageId,"conversationId" to id,"author" to profile(),"recipientId" to peerId,"policyHash" to hash,"channelVersion" to (pb?.getInt("version")?:0),"epoch" to pb?.getString("epoch"),"sequence" to sequence,"createdAt" to time.toString(),"expiresAt" to time.plusSeconds(604800).toString(),"format" to format,"encrypted" to encrypted,"content" to content)
        val envelope=signed(body); ChatProtocol.message(envelope,p,time)
        save("chat-messages",messageId,obj("id" to messageId,"envelope" to envelope,"payload" to payload,"owned" to true,"hops" to 0,"receivedAt" to time.toString(),"serverSaved" to false))
        store.put("chat-sequences",id,obj("value" to sequence)); onChange()
        runCatching { sendRecord(store.get("chat-messages",messageId)!!) }; return messageId
    }
    private fun eligible(message:JSONObject,personId:String):Boolean {
        val b=message.getJSONObject("envelope").getJSONObject("body")
        if(blocked(personId) || blocked(ChatProtocol.participant(b.getJSONObject("author"))) || Instant.parse(b.getString("expiresAt"))<=now() || message.getInt("hops")>=6) return false
        if(!b.isNull("recipientId"))return b.getString("recipientId")==personId && message.getBoolean("owned")
        val current=current(b.getString("conversationId"))?:return false
        val historic=store.get("chat-policy-history",b.getString("policyHash"))?.getJSONObject("policy")?:return false
        return live(current) && ChatProtocol.member(current,personId) && (historic.getJSONObject("body").getString("visibility")=="OPEN" || ChatProtocol.member(historic,personId))
    }
    private suspend fun sendRecord(record:JSONObject) {
        val person=peer?:return; if(!session.confirmed || !eligible(record,ChatProtocol.participant(person)))return
        val b=record.getJSONObject("envelope").getJSONObject("body")
        if(!b.isNull("policyHash")) {
            val p=store.get("chat-policy-history",b.getString("policyHash"))!!.getJSONObject("policy")
            session.send(if(Protocol.hash(p)==current(b.getString("conversationId"))?.let { Protocol.hash(it) })"CHAT_POLICY" else "CHAT_HISTORY_POLICY",p)
        }
        session.send("CHAT_MESSAGE",obj("envelope" to record.getJSONObject("envelope"),"hops" to record.getInt("hops")+1))
        store.get("chat-manifests",record.getString("id"))?.let{session.send("CHAT_ATTACHMENT_META",it.getJSONObject("manifest"))}
        record.put("sentNearby",now().toString()); save("chat-messages",record.getString("id"),record)
    }
    private fun receipt(record:JSONObject,status:String):JSONObject {
        val envelope=record.getJSONObject("envelope"); val b=envelope.getJSONObject("body")
        return signed(obj("v" to 1,"kind" to "CHAT_RECEIPT","messageId" to b.getString("id"),"conversationId" to b.getString("conversationId"),"recipient" to profile(),"status" to status,"recordedAt" to now().toString(),"messageHash" to Protocol.hash(envelope)))
    }
    private suspend fun receiveMessage(envelope:JSONObject,hops:Int,server:Boolean=false) {
        require(hops in 0..6); val b=envelope.getJSONObject("body"); val id=b.getString("id"); val author=ChatProtocol.participant(b.getJSONObject("author"))
        if(blocked(author))return
        val p=if(b.isNull("policyHash"))null else store.get("chat-policy-history",b.getString("policyHash"))?.getJSONObject("policy")?:error("Channel history is unavailable.")
        val cp=if(p==null)null else current(b.getString("conversationId"))
        if(p!=null) require(cp!=null && live(cp)) { "Channel membership needs a refresh." }
        ChatProtocol.message(envelope,p,now(),history=true)
        val old=store.get("chat-messages",id)
        require(old==null || Protocol.hash(old.getJSONObject("envelope"))==Protocol.hash(envelope)) { "Conflicting message identifier." }
        if(old!=null){ if(!old.optBoolean("owned"))sendReceipt(old,"DELIVERED");return }
        if(p==null)require(b.getString("recipientId")==self()) { "This private message belongs to another person." }
        val kid=if(p==null)"dm:${b.getString("conversationId")}:$id:${b.getString("recipientId")}" else "channel:${b.getString("conversationId")}:${b.getString("epoch")}:$id"
        val payload=if(!b.getBoolean("encrypted"))JSONObject(b.getString("content")) else ChatProtocol.decrypt(b.getString("content"),if(p==null)store.get("chat","encryption")!! else null,if(p==null)null else Protocol.decode(store.get("chat-keys",b.getString("policyHash"))?.getString("key")?:error("Older private history is not shared with new members.")),kid)
        ChatProtocol.payload(payload,b.getString("format")); remember(b.getJSONObject("author"))
        if(p==null && store.get("chat-conversations",b.getString("conversationId"))==null)save("chat-conversations",b.getString("conversationId"),obj("id" to b.getString("conversationId"),"type" to "DIRECT","peerId" to author,"title" to b.getJSONObject("author").getJSONObject("body").getString("name"),"muted" to false,"joined" to true,"lastRead" to Instant.EPOCH.toString()))
        val record=obj("id" to id,"envelope" to envelope,"payload" to payload,"owned" to false,"hops" to hops,"receivedAt" to now().toString(),"serverSaved" to server)
        save("chat-messages",id,record); sendReceipt(record,"DELIVERED"); onIncoming(id);onChange()
    }
    private suspend fun sendReceipt(record:JSONObject,status:String) {
        val r=receipt(record,status); val b=r.getJSONObject("body"); val id=b.getString("messageId")+":"+self()+":"+status
        save("chat-receipts",id,obj("id" to id,"receipt" to r))
        if(session.confirmed && peer!=null)session.send("CHAT_RECEIPT",r)
    }
    private fun receiveReceipt(receipt:JSONObject) {
        val b=receipt.getJSONObject("body"); val message=store.get("chat-messages",b.getString("messageId"))?:return
        val envelope=message.getJSONObject("envelope")
        ChatProtocol.receipt(receipt,envelope,current(b.getString("conversationId")),now())
        val id=b.getString("messageId")+":"+ChatProtocol.participant(b.getJSONObject("recipient"))+":"+b.getString("status")
        save("chat-receipts",id,obj("id" to id,"receipt" to receipt))
        if(message.getBoolean("owned")){message.put(if(b.getString("status")=="READ")"readAt" else "deliveredAt",b.getString("recordedAt"));save("chat-messages",message.getString("id"),message)}
    }
    suspend fun read(id:String,visibleIds:List<String>)=withContext(Dispatchers.IO) { lock.withLock {
        if(store.get("chat-conversations",id)==null)return@withLock
        val visible=visibleIds.distinct().take(30).mapNotNull{store.get("chat-messages",it)}.filter{!it.optBoolean("owned")&&!it.optBoolean("readLocally")&&it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==id}
        var changed=false
        try{for(record in visible){currentCoroutineContext().ensureActive();sendReceipt(record,"READ");record.put("readLocally",true);save("chat-messages",record.getString("id"),record);changed=true}}
        finally{if(changed)onChange()}
    } }
    suspend fun mute(id:String)=withContext(Dispatchers.IO) { lock.withLock { val c=store.get("chat-conversations",id)!!;c.put("muted",!c.optBoolean("muted"));save("chat-conversations",id,c);onChange() } }
    suspend fun block(id:String)=withContext(Dispatchers.IO) { lock.withLock { if(blocked(id))store.remove("chat-blocks",id) else save("chat-blocks",id,obj("id" to id));onChange() } }
    suspend fun report(messageId:String,reason:String)=withContext(Dispatchers.IO) { lock.withLock { require(reason in listOf("ABUSE","SPAM","SAFETY") && store.get("chat-messages",messageId)!=null);val id=UUID.randomUUID().toString();save("chat-reports",id,obj("id" to id,"messageId" to messageId,"reason" to reason));onChange() } }
    suspend fun clearConversation(id:String)=withContext(Dispatchers.IO) { lock.withLock { require(store.get("chat-conversations",id)?.optBoolean("joined")==false || id.startsWith("dm:")); messages().filter { it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==id }.forEach { store.remove("chat-messages",it.getString("id")) }; onChange() } }
    suspend fun announce()=withContext(Dispatchers.IO) { lock.withLock { announceUnlocked() } }
    private suspend fun announceUnlocked() {
        if(!session.confirmed)return
        renewOwned()
        session.send("CHAT_PROFILE",profile())
        val channels=policies().filter { live(it) && it.getJSONObject("body").getString("visibility")=="OPEN" }.take(16).map { val b=it.getJSONObject("body");obj("id" to b.getString("id"),"name" to b.getString("name"),"ownerId" to ChatProtocol.participant(b.getJSONObject("owner")),"members" to b.getJSONArray("members").objects().count { it.isNull("removedAt") },"version" to b.getInt("version"),"visibility" to "OPEN") }
        session.send("CHAT_DISCOVERY",signed(obj("v" to 1,"kind" to "CHAT_DISCOVERY","profile" to profile(),"channels" to JSONArray(channels))))
        val person=peer?.let { ChatProtocol.participant(it) }?:return
        if(blocked(person))return
        for(p in policies()) if(ChatProtocol.member(p,person))session.send("CHAT_POLICY",p)
        for(join in store.all("chat-joins")) if(Instant.parse(join.getJSONObject("request").getJSONObject("body").getString("expiresAt"))>now())session.send("CHAT_JOIN",join.getJSONObject("request"))
        val inventory=messages().filter { eligible(it,person) }.map { obj("id" to it.getString("id"),"conversationId" to it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")) }
        session.send("CHAT_INVENTORY",JSONArray(inventory.take(500)))
    }
    suspend fun receive(frame:JSONObject, generation:Long=session.connectionGeneration)=withContext(Dispatchers.IO) { lock.withLock {
        if(generation!=session.connectionGeneration || !session.confirmed)return@withLock
        val kind=frame.getString("kind")
        if(kind=="CHAT_PROFILE"){
            val profile=frame.getJSONObject("value");remember(profile);val first=peer==null;require(first || ChatProtocol.participant(peer!!)==ChatProtocol.participant(profile)) { "Nearby identity changed. Reconnect and compare the codes." };peerGeneration=generation;peer=profile
            if(first)announceUnlocked(); onChange();return@withLock
        }
        val person=peer?.let { ChatProtocol.participant(it) }?:return@withLock
        if(blocked(person))return@withLock
        when(kind){
            "CHAT_ATTACHMENT_META" -> {val manifest=frame.getJSONObject("value");val id=manifest.getJSONObject("body").getString("messageId");val record=store.get("chat-messages",id)?:return@withLock;verifyManifest(manifest,record);save("chat-manifests",id,obj("id" to id,"manifest" to manifest))}
            "CHAT_INVITE" -> { val link=frame.getString("value"); decodeInvite(link); onInvite(link) }
            "CHAT_DISCOVERY" -> {
                val value=frame.getJSONObject("value");value.exact("body","signature");val b=value.getJSONObject("body");b.exact("v","kind","profile","channels")
                require(b.get("v")==1 && b.getString("kind")=="CHAT_DISCOVERY" && ChatProtocol.participant(ChatProtocol.profile(b.getJSONObject("profile"),now()))==person && Protocol.verify(b,value.getString("signature"),peer!!.getJSONObject("body").getJSONObject("publicKey")))
                val descriptors=b.getJSONArray("channels").objects();require(descriptors.size<=16)
                descriptors.forEach { it.exact("id","name","ownerId","members","version","visibility");UUID.fromString(it.getString("id"));require(it.getString("name").trim().length in 1..48 && it.getString("ownerId").matches(Regex("[a-f0-9]{64}")) && it.getInt("members") in 1..16 && it.getInt("version")>0 && it.getString("visibility")=="OPEN") }
                discovery=descriptors;onChange()
            }
            "CHAT_POLICY" -> { val p=frame.getJSONObject("value"); val c=store.get("chat-conversations",p.getJSONObject("body").getString("id")); if(c!=null) {applyPolicy(p);onChange()} }
            "CHAT_HISTORY_POLICY" -> {
                val p=frame.getJSONObject("value");val b=p.getJSONObject("body"); val cp=current(b.getString("id"))?:return@withLock
                require(live(cp) && ChatProtocol.member(cp,person) && b.getInt("version")<cp.getJSONObject("body").getInt("version") && ChatProtocol.participant(b.getJSONObject("owner"))==ChatProtocol.participant(cp.getJSONObject("body").getJSONObject("owner")))
                ChatProtocol.policy(p,Instant.parse(b.getString("issuedAt")));require(b.getString("visibility")=="OPEN" || ChatProtocol.member(p,self())&&ChatProtocol.member(p,person));archive(p)
            }
            "CHAT_JOIN" -> {handleJoin(frame.getJSONObject("value"));onChange()}
            "CHAT_INVENTORY" -> {
                val values=frame.getJSONArray("value").objects(); require(values.size<=500)
                val needed=values.filter { it.exact("id","conversationId");UUID.fromString(it.getString("id")); val c=store.get("chat-conversations",it.getString("conversationId"));c?.optBoolean("joined")==true && store.get("chat-messages",it.getString("id"))==null }.map { it.getString("id") }
                for(batch in needed.chunked(50))session.send("CHAT_NEED",JSONArray(batch))
            }
            "CHAT_NEED" -> {val ids=frame.getJSONArray("value").strings();require(ids.size<=50);for(id in ids){UUID.fromString(id);store.get("chat-messages",id)?.let { sendRecord(it) }}}
            "CHAT_MESSAGE" -> {val value=frame.getJSONObject("value");value.exact("envelope","hops");receiveMessage(value.getJSONObject("envelope"),value.getInt("hops"))}
            "CHAT_RECEIPT" -> {receiveReceipt(frame.getJSONObject("value"));onChange()}
        }
    } }
    suspend fun sync()=withContext(Dispatchers.IO) { lock.withLock {
        prune();renewOwned()
        val time=now();val held=messages()
        val body=obj("v" to 1,"kind" to "CHAT_SYNC","id" to UUID.randomUUID().toString(),"profile" to profile(),"issuedAt" to time.toString(),"channelIds" to JSONArray(conversations().filter{it.getString("type")=="CHANNEL"}.sortedBy{if(it.optBoolean("joined")||it.optBoolean("pendingJoin"))0 else 1}.take(16).map{it.getString("id")}),"knownMessages" to JSONArray(held.map{it.getString("id")}),"receiptMessageIds" to JSONArray(held.filter{it.optBoolean("owned")&&!it.has("readAt")}.sortedByDescending{it.getString("receivedAt")}.take(50).map{it.getString("id")}),"peers" to JSONArray(),"policies" to JSONArray(),"messages" to JSONArray(),"receipts" to JSONArray(),"joins" to JSONArray(),"blocks" to JSONArray(store.all("chat-blocks").take(100).map { it.getString("id") }),"reports" to JSONArray(store.all("chat-reports").take(8)))
        fun add(field:String,value:JSONObject,limit:Int):Boolean{
            val array=body.getJSONArray(field);if(array.length()>=limit)return false
            array.put(value);if(Protocol.canonical(body).size>82000){array.remove(array.length()-1);return false};return true
        }
        val pending=held.filter{!it.optBoolean("serverSaved")}.sortedWith(compareBy<JSONObject>{if(it.getJSONObject("payload").has("attachment"))1 else 0}.thenBy{it.getString("receivedAt")})
        val needed=pending.mapNotNull {val b=it.getJSONObject("envelope").getJSONObject("body");if(b.isNull("recipientId"))null else b.getString("recipientId")}.distinct()
        for(contact in contacts().sortedBy{if(it.getString("id") in needed)0 else 1}){
            val p=contact.getJSONObject("profile");if(store.get("chat-server-contacts",contact.getString("id"))?.optString("hash")!=Protocol.hash(p))add("peers",p,16)
        }
        for(p in policies()) {
            val b=p.getJSONObject("body");val hash=Protocol.hash(p)
            if(Instant.parse(b.getString("expiresAt"))>time && (ChatProtocol.member(p,self()) || ChatProtocol.participant(b.getJSONObject("owner"))==self()) && store.get("chat-server-policies",hash)==null)add("policies",p,8)
        }
        for(record in pending){
            val envelope=record.getJSONObject("envelope");val b=envelope.getJSONObject("body")
            val recipient=if(b.isNull("recipientId"))null else b.getString("recipientId")
            if(recipient!=null && store.get("chat-server-contacts",recipient)==null && body.getJSONArray("peers").objects().none {ChatProtocol.participant(it)==recipient})continue
            add("messages",envelope,20)
        }
        for(r in store.all("chat-receipts"))if(!r.optBoolean("serverSaved") && ChatProtocol.participant(r.getJSONObject("receipt").getJSONObject("body").getJSONObject("recipient"))==self())add("receipts",r.getJSONObject("receipt"),30)
        for(join in store.all("chat-joins"))if(Instant.parse(join.getJSONObject("request").getJSONObject("body").getString("expiresAt"))>time)add("joins",join.getJSONObject("request"),8)
        require(Protocol.canonical(body).size<88000) { "Too much chat work for one check. Share nearby or review an old conversation." }
        val response=JSONObject(repository.api("/chat/sync",signed(body),false)); require(response.getInt("v")==1)
        body.getJSONArray("peers").objects().forEach {save("chat-server-contacts",ChatProtocol.participant(it),obj("id" to ChatProtocol.participant(it),"hash" to Protocol.hash(it)))}
        response.getJSONArray("acceptedPolicies").strings().forEach{save("chat-server-policies",it,obj("id" to it))}
        response.getJSONArray("acceptedReceipts").strings().forEach{id->store.get("chat-receipts",id)?.let{it.put("serverSaved",true);save("chat-receipts",id,it)}}
        response.getJSONArray("policies").objects().forEach { p-> if(store.get("chat-conversations",p.getJSONObject("body").getString("id"))!=null)applyPolicy(p) }
        response.getJSONArray("historyPolicies").objects().forEach { p-> val b=p.getJSONObject("body");val cp=current(b.getString("id"));if(cp!=null && live(cp) && ChatProtocol.participant(b.getJSONObject("owner"))==ChatProtocol.participant(cp.getJSONObject("body").getJSONObject("owner"))) {ChatProtocol.policy(p,Instant.parse(b.getString("issuedAt")));if(b.getString("visibility")=="OPEN"||ChatProtocol.member(p,self()))archive(p)} }
        response.getJSONArray("removed").strings().forEach { id-> store.get("chat-conversations",id)?.let { it.put("joined",false);save("chat-conversations",id,it) } }
        response.getJSONArray("accepted").strings().forEach { id-> store.get("chat-messages",id)?.let { it.put("serverSaved",true);save("chat-messages",id,it) } }
        response.getJSONArray("rejected").objects().forEach { rejected->store.get("chat-messages",rejected.getString("id"))?.let { it.put("attention",true).put("serverSaved",true);save("chat-messages",it.getString("id"),it) } }
        response.getJSONArray("messages").objects().forEach { message->runCatching {receiveMessage(message,0,true)} }
        response.getJSONArray("receipts").objects().forEach { receipt->runCatching {receiveReceipt(receipt)} }
        response.getJSONArray("joins").objects().forEach { join->runCatching {handleJoin(join)} }
        announceUnlocked(); prune();onChange()
    } }
    private suspend fun prune() {
        val time=now();val expired=store.all("chat-messages").filter { Instant.parse(it.getJSONObject("envelope").getJSONObject("body").getString("expiresAt"))<=time }
        expired.forEach { store.remove("chat-messages",it.getString("id"));store.remove("chat-manifests",it.getString("id"));store.remove("chat-media-progress",it.getString("id")) }
        store.all("chat-invites").filter { Instant.parse(it.getString("expiresAt"))<=time }.forEach { store.remove("chat-invites",it.getString("id")) }
        val messages=store.all("chat-messages");val ids=messages.map{it.getString("id")}.toSet()
        val attachmentIds=messages.mapNotNull{it.getJSONObject("payload").optJSONObject("attachment")?.optString("id")}.toSet()
        expired.mapNotNull{it.getJSONObject("payload").optJSONObject("attachment")?.optString("id")}.filter{it !in attachmentIds}.forEach{session.removeFile(it)}
        store.all("chat-receipts").filter{it.getJSONObject("receipt").getJSONObject("body").getString("messageId") !in ids}.forEach{store.remove("chat-receipts",it.getString("id"))}
        val retained=messages.mapNotNull{val b=it.getJSONObject("envelope").getJSONObject("body");if(b.isNull("policyHash"))null else b.getString("policyHash")}.toSet()+policies().map{Protocol.hash(it)}
        store.all("chat-policy-history").filter{it.getString("id") !in retained}.forEach{store.remove("chat-policy-history",it.getString("id"));store.remove("chat-keys",it.getString("id"));store.remove("chat-server-policies",it.getString("id"))}
    }
    private fun renewOwned(){
        for(p in policies()){val b=p.getJSONObject("body");if(ChatProtocol.participant(b.getJSONObject("owner"))==self()&&!b.getBoolean("deleted")&&Instant.parse(b.getString("expiresAt"))<now().plusSeconds(1800))applyPolicy(revised(p,b.getString("name"),b.getString("visibility"),b.getJSONArray("members")),true)}
    }
}
