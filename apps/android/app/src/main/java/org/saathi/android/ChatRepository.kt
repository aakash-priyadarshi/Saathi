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

/** 8 KiB parts per signed media request (1 MiB); matches the server's limit. */
internal const val PARTS_PER_REQUEST = 128
/** Stable conversations over confirmed peers and signed HTTP. All local chat records are encrypted. */
class ChatRepository(private val context: Context, private val repository: Repository, private val session: PeerSession) {
    private val store get()=repository.store
    private val lock=Mutex()
    private val mediaLock=Mutex()
    private val account="swarm-chat"
    var onChange: ()->Unit = {}
    @Volatile private var heldPeer: JSONObject? = null
    @Volatile private var peerGeneration = -1L
    private var policySendGeneration = -1L
    private val sentPolicyFrames = mutableSetOf<String>()
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
    private fun requireRosterSpace(members: JSONArray) {
        // Earlier signed policies retain removal history; recycle only inactive roster slots.
        for (index in members.length()-1 downTo 0) {
            if (members.length() < ChatProtocol.MAX_CHANNEL_MEMBERS) break
            if (!members.getJSONObject(index).isNull("removedAt")) members.remove(index)
        }
        require(members.length() < ChatProtocol.MAX_CHANNEL_MEMBERS) { "This channel has 200 members. Remove someone before admitting another person." }
    }
    private fun save(bucket:String,id:String,value:JSONObject) {
        if(bucket=="chat-receipts" && store.get(bucket,id)==null && store.all(bucket).size>=500){
            store.all(bucket).filter{it.optBoolean("serverSaved") || ChatProtocol.participant(it.getJSONObject("receipt").getJSONObject("body").getJSONObject("recipient"))!=self()}.minByOrNull{it.getJSONObject("receipt").getJSONObject("body").getString("recordedAt")}?.let{store.remove(bucket,it.getString("id"))}
        }
        require(store.get(bucket,id)!=null || store.count(bucket)<500) { "Chat storage is full. Review and clear an old conversation first." }
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
    internal fun signLiveControl(kind: String, connection: String, control: JSONObject) = signed(obj("kind" to kind,"connection" to connection,"control" to control))
    private fun self()=ChatProtocol.participant(profile())
    fun conversations()=store.all("chat-conversations")
    fun messages()=store.all("chat-messages").filter { Instant.parse(it.getJSONObject("envelope").getJSONObject("body").getString("expiresAt"))>now() }
    fun contacts()=store.all("chat-contacts").filter { store.get("chat-blocks",it.getString("id"))==null }
    fun policies()=store.all("chat-policies").map { it.getJSONObject("policy") }
    fun blocked(id:String)=store.get("chat-blocks",id)!=null
    fun reset(){ peer=null; discovery=emptyList(); onChange() }
    /** A reconnect that skipped the code turned out not to be anyone already met. */
    var onUnknownPeer: (String) -> Unit = {}
    fun verifyTransportPeer(profile: JSONObject): String {
        val verified = ChatProtocol.profile(profile,now())
        val participant = ChatProtocol.participant(verified)
        if (session.transport?.codeSkipped == true && store.get("chat-contacts",participant) == null) {
            session.transport?.disconnect(); onUnknownPeer(verified.getJSONObject("body").getString("name"))
            error("Compare the code to pair with this person.")
        }
        val previous = peer
        require(previous == null || ChatProtocol.participant(previous) == participant) { "Nearby identity changed. Reconnect and compare the codes." }
        session.verifyPeer(participant)
        remember(verified)
        peerGeneration=session.connectionGeneration;peer=verified
        onChange()
        return participant
    }
    internal fun remember(profile:JSONObject) {
        ChatProtocol.profile(profile,now()); val id=ChatProtocol.participant(profile)
        val old=store.get("chat-contacts",id)?.getJSONObject("profile")
        require(old==null || Protocol.hash(old.getJSONObject("body").getJSONObject("encryptionKey"))==Protocol.hash(profile.getJSONObject("body").getJSONObject("encryptionKey"))) { "This person's chat identity changed. Compare identities again." }
        val current=old==null || Instant.parse(profile.getJSONObject("body").getString("updatedAt"))>=Instant.parse(old.getJSONObject("body").getString("updatedAt"))
        val latest=if(current)profile else old
        if(current && (old==null || Protocol.hash(old)!=Protocol.hash(latest)))save("chat-contacts",id,obj("id" to id,"profile" to latest))
        val name=latest.getJSONObject("body").getString("name")
        store.all("chat-conversations").filter { it.optString("type")=="DIRECT" && it.optString("peerId")==id && it.optString("title")!=name }.forEach { conversation ->
            conversation.put("title",name); save("chat-conversations",conversation.getString("id"),conversation)
        }
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
    fun current(id:String)=store.get("chat-policies",id)?.getJSONObject("policy")
    fun actions(id:String)=store.all("chat-actions").filter{it.getJSONObject("envelope").getJSONObject("body").getString("channelId")==id && !it.optBoolean("rejected")}.sortedWith(compareBy<JSONObject>{it.getJSONObject("envelope").getJSONObject("body").getString("issuedAt")}.thenBy{it.getString("id")})
    fun joinRequests(id:String)=store.all("chat-join-inbox").filter{it.getJSONObject("request").getJSONObject("body").getString("channelId")==id && !it.optBoolean("resolved") && Instant.parse(it.getJSONObject("request").getJSONObject("body").getString("expiresAt"))>now()}
    private fun pendingMembership(policy:JSONObject)=actions(policy.getJSONObject("body").getString("id")).any{r->val a=r.getJSONObject("envelope").getJSONObject("body");a.getString("action") in listOf("REMOVE","BAN","SET_ROLE","APPROVE_JOIN") && a.getInt("version")==policy.getJSONObject("body").getInt("version") && policy.getJSONObject("body").optJSONArray("appliedActions")?.strings()?.contains(a.getString("id"))!=true}
    fun capabilities(id:String)=current(id)?.let{ChannelGovernance.capabilities(it,self())}
    fun threadLocked(id:String,root:String):Boolean {
        val latest=actions(id).lastOrNull {it.getJSONObject("envelope").getJSONObject("body").let{a->a.getString("targetId")==root && a.getString("action") in listOf("LOCK_THREAD","UNLOCK_THREAD")}}
        return latest?.getJSONObject("envelope")?.getJSONObject("body")?.getString("action")?.let{it=="LOCK_THREAD"} ?: (current(id)?.getJSONObject("body")?.optJSONObject("moderation")?.getJSONArray("lockedThreads")?.strings()?.contains(root)==true)
    }
    fun messageHidden(id:String,message:String):Boolean {
        val latest=actions(id).lastOrNull {it.getJSONObject("envelope").getJSONObject("body").let{a->a.getString("targetId")==message && a.getString("action") in listOf("HIDE_MESSAGE","RESTORE_MESSAGE")}}
        return latest?.getJSONObject("envelope")?.getJSONObject("body")?.getString("action")?.let{it=="HIDE_MESSAGE"} ?: (current(id)?.getJSONObject("body")?.optJSONObject("moderation")?.getJSONArray("hiddenMessages")?.strings()?.contains(message)==true)
    }
    fun fileAllowed(id:String,hash:String):Boolean {
        val person=peer?.let { ChatProtocol.participant(it) }?:return false
        if(!session.confirmed || blocked(person))return false
        return messages().any { record->
            val a=record.getJSONObject("payload").optJSONObject("attachment");val b=record.getJSONObject("envelope").getJSONObject("body")
            a?.optString("id")==id && a.optString("cipherHash")==hash && if(!b.isNull("recipientId")) {
                val other=if(record.optBoolean("owned"))b.getString("recipientId") else ChatProtocol.participant(b.getJSONObject("author"));other==person
            }else{val p=current(b.getString("conversationId"));p!=null && live(p) && !pendingMembership(p) && ChatProtocol.member(p,person)}
        }
    }
    suspend fun attach(conversationId:String,source:Uri,mimeOverride:String?=null,nameOverride:String?=null,threadRootId:String?=null,forwarded:Boolean=false)=withContext(Dispatchers.IO){
        val sourceMime=mimeOverride?:context.contentResolver.getType(source)?:error("Choose a photo, audio, video or text file.")
        require(sourceMime in listOf("image/jpeg","image/png","image/webp","audio/mp4","audio/mpeg","video/mp4","video/webm","text/plain"))
        val name=nameOverride?:context.contentResolver.query(source,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { require(it.moveToFirst());it.getString(0) }?: "Attachment"
        // Videos travel compressed (and metadata-free) because every carrier phone stores and resends them.
        val compressed=if(sourceMime.startsWith("video/"))FieldMedia.prepare(context,source,video=true) else null
        val uri=compressed?.let{Uri.fromFile(it.file)}?:source;val mime=if(compressed!=null)"video/mp4" else sourceMime
        try{lock.withLock{
        val id=UUID.randomUUID().toString();val key=ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Streams through an encrypted temporary file so a 250 MB video never sits in memory.
        val temporary=java.io.File.createTempFile("chat-",".bin",java.io.File(context.cacheDir,"chat-processing").apply{mkdirs()})
        val (plainSize,plainHash)=try{
            val result=context.contentResolver.openInputStream(uri)?.use{input->temporary.outputStream().buffered().use{AttachmentCipher.encrypt(input,it,key,id,FieldMedia.MAX_BYTES)}}?:error("Cannot read this attachment.")
            session.saveChatFile(id,temporary);result
        }finally{temporary.delete()}
        val file=store.get("attachments",id)!!
        file.put("contentMime",mime);store.put("attachments",id,file)
        val attachment=obj("id" to id,"name" to (if(compressed!=null)name.substringBeforeLast('.')+".mp4" else name).take(100),"mime" to mime,"size" to plainSize,"hash" to plainHash,"cipherHash" to file.getString("hash"),"key" to Protocol.b64(key))
        val format=if(mime.startsWith("image/"))"PHOTO" else if(mime.startsWith("video/"))"VIDEO" else if(mime.startsWith("audio/"))"VOICE" else "FILE"
        try {
            val messageId=sendUnlocked(conversationId,obj("attachment" to attachment).apply{if(forwarded)put("forwarded",true)},format,threadRootId)
            val envelope=store.get("chat-messages",messageId)!!.getJSONObject("envelope")
            val manifest=signed(obj("v" to 1,"kind" to "CHAT_ATTACHMENT","id" to id,"messageId" to messageId,"messageHash" to Protocol.hash(envelope),"author" to profile(),"size" to file.getInt("size"),"cipherHash" to file.getString("hash"),"expiresAt" to envelope.getJSONObject("body").getString("expiresAt")))
            save("chat-manifests",messageId,obj("id" to messageId,"manifest" to manifest))
            if(fileAllowed(id,file.getString("hash")))runCatching {session.send("CHAT_ATTACHMENT_META",manifest);session.offerSaved(file)}
        }catch(e:Exception){session.removeFile(id);throw e}
        onChange()
    }}finally{compressed?.file?.delete()}}
    /** Decrypts and verifies a saved attachment into [output] without holding it in memory. */
    private suspend fun decryptTo(messageId:String,output:java.io.OutputStream)=withContext(Dispatchers.IO){
        val record=store.get("chat-messages",messageId)?:error("Message is unavailable.")
        val a=record.getJSONObject("payload").getJSONObject("attachment");val id=a.getString("id")
        val file=store.get("attachments",id)?:error("Wait for a nearby member with the attachment.")
        require(file.optBoolean("complete") && file.getString("hash")==a.getString("cipherHash")){"Private attachment verification failed."}
        val hash=session.openSaved(id).use{AttachmentCipher.decrypt(it,output,Protocol.decode(a.getString("key")),id,a.getLong("size"),file.getLong("size"))}
        require(hash==a.getString("hash")){"Private attachment verification failed."}
    }
    /** Whole attachment in memory, for photo and voice previews only. */
    suspend fun attachmentBytes(messageId:String):ByteArray{
        val a=store.get("chat-messages",messageId)?.getJSONObject("payload")?.getJSONObject("attachment")?:error("Message is unavailable.")
        require(a.getInt("size")<=PeerSession.PREVIEW_BYTES){"This attachment is too large to preview. Export it instead."}
        return java.io.ByteArrayOutputStream(a.getInt("size")).also{decryptTo(messageId,it)}.toByteArray()
    }
    suspend fun completeFile(id:String){
        val record=messages().firstOrNull { it.getJSONObject("payload").optJSONObject("attachment")?.optString("id")==id }?:return
        decryptTo(record.getString("id"),object:java.io.OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){}})
        val file=store.get("attachments",id)!!;file.put("chatOnly",true);store.put("attachments",id,file);onChange()
    }
    suspend fun exportAttachment(messageId:String,destination:Uri)=withContext(Dispatchers.IO){
        context.contentResolver.openOutputStream(destination,"wt")?.use {decryptTo(messageId,it)}?:error("The selected destination cannot be written.")
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
            && AttachmentCipher.validSize(a.getLong("size"),b.getLong("size")) && b.getString("cipherHash")==a.getString("cipherHash")
            && Instant.parse(b.getString("expiresAt"))<=Instant.parse(envelope.getJSONObject("body").getString("expiresAt")) && Instant.parse(b.getString("expiresAt"))>now()
            && Protocol.verify(b,manifest.getString("signature"),b.getJSONObject("author").getJSONObject("body").getJSONObject("publicKey"))) {"Private attachment manifest verification failed."}
        return manifest
    }
    /** One bounded batch per automatic check; explicit media sync can continue resumably. */
    suspend fun synchronizeAttachment(messageId:String,complete:Boolean=false)=withContext(Dispatchers.IO){mediaLock.withLock{
        while(true){
            val record=store.get("chat-messages",messageId)?:error("Message is unavailable.");require(record.optBoolean("serverSaved")&&!record.optBoolean("attention")){"Check chat delivery before synchronizing this attachment."}
            val a=record.getJSONObject("payload").getJSONObject("attachment");val file=store.get("attachments",a.getString("id"))
            val manifest=store.get("chat-manifests",messageId)?.getJSONObject("manifest")
            // The signed manifest fixes the ciphertext size; without it, the first request only fetches it.
            val count=manifest?.getJSONObject("body")?.getInt("size")?.let{(it+8191)/8192}
            val upload=file?.optBoolean("complete")==true && manifest!=null
            val progress=store.get("chat-media-progress",messageId)?.optJSONArray("received")?.let{it.strings().map(String::toInt).toSet()}?:emptySet()
            val missing=if(count==null)emptyList()else (0 until count).filter{if(upload)it !in progress else !session.hasPart(a.getString("id"),it)}.take(PARTS_PER_REQUEST)
            if(count!=null && (upload&&missing.isEmpty() || !upload&&file?.optBoolean("complete")==true))return@withLock
            val chunks=JSONArray()
            if(upload)for(part in missing)chunks.put(obj("part" to part,"data" to Protocol.b64(session.readPart(a.getString("id"),part))))
            val body=obj("v" to 1,"kind" to "CHAT_ATTACHMENT_REQUEST","profile" to profile(),"issuedAt" to now().toString(),"messageId" to messageId,"manifest" to if(upload)manifest else null,"parts" to JSONArray(if(upload)emptyList<Int>() else missing),"chunks" to chunks)
            val response=JSONObject(repository.api("/chat/attachment",signed(body),false,bulk=true));require(response.getInt("v")==1)
            val verified=verifyManifest(response.getJSONObject("manifest"),record);save("chat-manifests",messageId,obj("id" to messageId,"manifest" to verified))
            val cipherSize=verified.getJSONObject("body").getInt("size");val parts=(cipherSize+8191)/8192
            val received=response.getJSONArray("received");require(received.length()<=parts);val indices=(0 until received.length()).map{received.getInt(it)};require(indices.distinct().size==indices.size&&indices.all{it in 0 until parts})
            save("chat-media-progress",messageId,obj("id" to messageId,"received" to JSONArray(indices.map{it.toString()})))
            if(count==null)continue
            if(!upload){
                val chunks=response.getJSONArray("chunks").objects();require(chunks.size<=PARTS_PER_REQUEST)
                session.saveServerChatChunks(a.getString("id"),cipherSize,a.getString("cipherHash"),chunks.map{it.exact("part","data");require(it.getInt("part") in missing);it.getInt("part") to Protocol.decode(it.getString("data"))})
                if(store.get("attachments",a.getString("id"))?.optBoolean("complete")==true){completeFile(a.getString("id"));return@withLock}
                if(chunks.isEmpty())return@withLock
            }
            onChange();if(!complete || (upload&&indices.size==parts))return@withLock
            // Fits the operational request limit; text/event work runs on separate coroutines.
            kotlinx.coroutines.delay(750)
        }
    }}
    suspend fun autoMedia(){
        if(mediaLock.isLocked)return
        val next=messages().firstOrNull{m->val a=m.getJSONObject("payload").optJSONObject("attachment");a!=null&&m.optBoolean("serverSaved")&&!m.optBoolean("attention")&&
            (store.get("attachments",a.getString("id"))?.optBoolean("complete")!=true || (store.get("chat-manifests",m.getString("id"))?.getJSONObject("manifest")?.getJSONObject("body")?.getInt("size")?.let{size->(store.get("chat-media-progress",m.getString("id"))?.optJSONArray("received")?.length()?:0)<(size+8191)/8192}==true))}
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
            if(b.getInt("version")>previous.getInt("version")){
                for(row in actions(id).filter{it.getJSONObject("envelope").getJSONObject("body").getInt("version")==previous.getInt("version")}){
                    val action=row.getJSONObject("envelope").getJSONObject("body");val kind=action.getString("action");if(kind in listOf("REACT","UNREACT","REVIEW_REPORT"))continue
                    val target=action.getString("targetId");val member=b.getJSONArray("members").objects().firstOrNull{ChatProtocol.participant(it.getJSONObject("profile"))==target}
                    require(b.optJSONArray("appliedActions")?.strings()?.contains(action.getString("id"))==true &&
                        (kind !in listOf("REMOVE","BAN","REJECT_JOIN") || member==null||!member.isNull("removedAt")) &&
                        (kind!="BAN"||b.optJSONArray("bannedIds")?.strings()?.contains(target)==true) &&
                        (kind!="UNBAN"||b.optJSONArray("bannedIds")?.strings()?.contains(target)!=true) &&
                        (kind!="APPROVE_JOIN"||member?.isNull("removedAt")==true) &&
                        (kind!="SET_ROLE"||member?.optString("role")==action.getString("role")) &&
                        (kind!="LOCK_THREAD"||b.optJSONObject("moderation")?.getJSONArray("lockedThreads")?.strings()?.contains(target)==true) &&
                        (kind!="UNLOCK_THREAD"||b.optJSONObject("moderation")?.getJSONArray("lockedThreads")?.strings()?.contains(target)!=true) &&
                        (kind!="HIDE_MESSAGE"||b.optJSONObject("moderation")?.getJSONArray("hiddenMessages")?.strings()?.contains(target)==true) &&
                        (kind!="RESTORE_MESSAGE"||b.optJSONObject("moderation")?.getJSONArray("hiddenMessages")?.strings()?.contains(target)!=true)) {"This policy omitted a known moderation action. Check permissions again."}
                }
            }
        }
        require(conversation!=null || consent) { "Join this channel before accepting its history." }
        archive(policy); save("chat-policies",id,obj("id" to id,"policy" to policy))
        b.getJSONArray("members").objects().forEach { remember(it.getJSONObject("profile")) }
        val joined=ChatProtocol.member(policy,self()) && (consent || conversation?.optBoolean("joined")==true || conversation?.optBoolean("pendingJoin")==true)
        if(joined || store.get("chat-joins",id)?.getJSONObject("request")?.getJSONObject("body")?.getString("action")=="LEAVE"&&!ChatProtocol.member(policy,self()))store.remove("chat-joins",id)
        val next=conversation?:obj("id" to id,"type" to "CHANNEL","muted" to false,"lastRead" to Instant.EPOCH.toString())
        next.put("title",b.getString("name")).put("joined",joined).put("pendingJoin",false).put("deleted",b.getBoolean("deleted")).put("visibility",b.getString("visibility")).put("ownerId",ChatProtocol.participant(b.getJSONObject("owner")))
        save("chat-conversations",id,next)
        if(!joined) store.remove("chat-keys",Protocol.hash(policy))
    }
    private fun revised(previous:JSONObject?,name:String,visibility:String,members:JSONArray,deleted:Boolean=false,settings:JSONObject?=null):JSONObject {
        val id=previous?.getJSONObject("body")?.getString("id")?:UUID.randomUUID().toString()
        val epoch=UUID.randomUUID().toString(); val key=ByteArray(32).also { SecureRandom().nextBytes(it) }
        val keys=JSONArray()
        val issued=now(); val body=obj("v" to 1,"kind" to "CHAT_CHANNEL","id" to id,"name" to name,"visibility" to visibility,"owner" to profile(),"version" to ((previous?.getJSONObject("body")?.getInt("version")?:0)+1),"epoch" to epoch,"issuedAt" to issued.toString(),"expiresAt" to issued.plusSeconds(21600).toString(),"deleted" to deleted,"members" to members,"keys" to keys)
        previous?.getJSONObject("body")?.let{p->listOf("settings","bannedIds","appliedActions","moderation").forEach{if(p.has(it))body.put(it,p.get(it))}}
        if(settings!=null)body.put("settings",settings)
        if(visibility=="INVITE")members.objects().filter{ChannelGovernance.capabilities(obj("body" to JSONObject(body.toString()).put("deleted",false)),ChatProtocol.participant(it.getJSONObject("profile"))).getBoolean("canRead")}.forEach{
            val person=it.getJSONObject("profile");val personId=ChatProtocol.participant(person);keys.put(obj("participantId" to personId,"jwe" to ChatProtocol.encrypt(obj("key" to Protocol.b64(key)),person.getJSONObject("body").getJSONObject("encryptionKey"),null,"channel:$id:$epoch:$personId")))
        }
        val policy=signed(body); ChatProtocol.policy(policy,issued); return policy
    }
    suspend fun create(name:String,visibility:String,mode:String="DISCUSSION",admission:String=if(visibility=="OPEN")"OPEN" else "INVITE_AUTO",type:String=if(mode=="ANNOUNCEMENT")"ANNOUNCE" else "FREE"):String=withContext(Dispatchers.IO) { lock.withLock {
        require(name.trim().length in 1..48 && visibility in listOf("OPEN","INVITE") && conversations().count { it.getString("type")=="CHANNEL"&&it.optBoolean("joined") }<16)
        val members=JSONArray().put(obj("profile" to profile(),"role" to "OWNER","joinedAt" to now().toString(),"removedAt" to null))
        val policy=revised(null,name.trim(),visibility,members,settings=GroupType.settings(type,admission)); applyPolicy(policy,true); announceUnlocked(); onChange(); policy.getJSONObject("body").getString("id")
    } }
    private fun makeJoin(id:String,action:String,invitation:JSONObject?=null):JSONObject {
        val time=now();val body=obj("v" to 1,"kind" to "CHAT_JOIN","id" to UUID.randomUUID().toString(),"channelId" to id,"participant" to profile(),"action" to action,"issuedAt" to time.toString(),"expiresAt" to time.plusSeconds(21600).toString());if(invitation!=null)body.put("invitation",invitation);return signed(body)
    }
    suspend fun join(id:String)=withContext(Dispatchers.IO) { lock.withLock {
        UUID.fromString(id); val descriptor=discovery.firstOrNull { it.getString("id")==id }?:error("Find the channel nearby again.")
        val request=makeJoin(id,"JOIN"); save("chat-joins",id,obj("id" to id,"request" to request))
        save("chat-conversations",id,obj("id" to id,"type" to "CHANNEL","title" to descriptor.getString("name"),"muted" to false,"joined" to false,"pendingJoin" to true,"lastRead" to Instant.EPOCH.toString()))
        if(session.confirmed)session.send("CHAT_JOIN",request); onChange()
    } }
    private suspend fun handleJoin(request:JSONObject) {
        ChatProtocol.join(request,now()); val b=request.getJSONObject("body"); val p=current(b.getString("channelId"))?:return; val pb=p.getJSONObject("body")
        if(!ChannelGovernance.capabilities(p,self()).getBoolean("canManageMembers") || pb.getBoolean("deleted")) return
        val person=b.getJSONObject("participant"); val personId=ChatProtocol.participant(person); if(blocked(personId))return
        if(pb.optJSONArray("bannedIds")?.strings()?.contains(personId)==true)return
        // Like WhatsApp: a removed member cannot rejoin on their own; their request waits for an admin's approval.
        val previous=pb.getJSONArray("members").objects().firstOrNull{ChatProtocol.participant(it.getJSONObject("profile"))==personId}
        if(b.getString("action")=="JOIN" && previous!=null && !previous.isNull("removedAt")){remember(person);save("chat-join-inbox",b.getString("id"),obj("id" to b.getString("id"),"request" to request,"resolved" to false));return}
        if(b.getString("action")=="JOIN") {
            val admission=pb.optJSONObject("settings")?.optString("admission")?:if(pb.getString("visibility")=="OPEN")"OPEN" else "INVITE_AUTO"
            val invitation=b.optJSONObject("invitation")
            val inviteBody=invitation?.optJSONObject("body")
            val privateInvite=pb.getString("visibility")=="INVITE" && inviteBody?.optString("kind")=="CHAT_ADMISSION"
            if(admission in listOf("APPROVAL_ONLY","INVITE_PLUS_APPROVAL") || privateInvite) {
                if(pb.getString("visibility")=="INVITE") {
                    val invite=invitation?:return;val ib=invite.getJSONObject("body")
                    ChatProtocol.invite(invite,personId,now())
                    require(ib.getString("policyHash")==Protocol.hash(p) && ChatProtocol.participant(ib.getJSONObject("owner"))==ChatProtocol.participant(pb.getJSONObject("owner")) && ChannelGovernance.capabilities(p,ChatProtocol.participant(ib.optJSONObject("issuer")?:ib.getJSONObject("owner"))).getBoolean("canInvite")) {"Ask for a current invitation."}
                    if(ib.getString("recipientId")=="*") {
                        val linkId=ib.getString("id");val ownerId=ChatProtocol.participant(pb.getJSONObject("owner"));val prior=store.get("chat-link-uses",linkId)
                        if(prior!=null && prior.getString("participantId")!=personId)return
                        if(prior==null && ownerId==self())save("chat-link-uses",linkId,obj("id" to linkId,"participantId" to personId,"requestId" to b.getString("id"),"expiresAt" to ib.getString("expiresAt")))
                    }
                }
                remember(person);save("chat-join-inbox",b.getString("id"),obj("id" to b.getString("id"),"request" to request,"resolved" to false));return
            }
        }
        if(ChatProtocol.participant(pb.getJSONObject("owner"))!=self())return
        val members=JSONArray(pb.getJSONArray("members").toString()); val existing=members.objects().firstOrNull { ChatProtocol.participant(it.getJSONObject("profile"))==personId }
        if(b.getString("action")=="JOIN") {
            if(pb.getString("visibility")!="OPEN") return
            if(existing==null) { requireRosterSpace(members); members.put(obj("profile" to person,"role" to "MEMBER","joinedAt" to now().toString(),"removedAt" to null)) } else if(!existing.isNull("removedAt")){existing.put("removedAt",JSONObject.NULL).put("joinedAt",now().toString()).put("profile",person)}else { if(live(p)) { if(session.confirmed && peer?.let { ChatProtocol.participant(it) }==personId)session.send("CHAT_POLICY",p); return } }
        } else { if(personId==self() || existing==null || !existing.isNull("removedAt"))return; existing.put("removedAt",now().toString()) }
        val revised=revised(p,pb.getString("name"),pb.getString("visibility"),members); applyPolicy(revised,true)
        if(session.confirmed && peer!=null)session.send("CHAT_POLICY",revised)
    }
    suspend fun invite(id:String,person:JSONObject):String=withContext(Dispatchers.IO) { lock.withLock {
        remember(person); val old=current(id)?:error("Channel is unavailable."); val b=old.getJSONObject("body"); require(ChannelGovernance.capabilities(old,self()).getBoolean("canInvite") && !b.getBoolean("deleted"))
        val recipient=ChatProtocol.participant(person);require(b.optJSONArray("bannedIds")?.strings()?.contains(recipient)!=true) {"Unban this identity before inviting them."}
        val admission=b.optJSONObject("settings")?.optString("admission")?:"INVITE_AUTO"
        if(admission in listOf("INVITE_PLUS_APPROVAL","APPROVAL_ONLY")) {
            val time=now();val invite=signed(obj("v" to 1,"kind" to "CHAT_ADMISSION","id" to UUID.randomUUID().toString(),"channelId" to id,"name" to b.getString("name"),"owner" to b.getJSONObject("owner"),"issuer" to profile(),"recipientId" to recipient,"policyHash" to Protocol.hash(old),"admission" to admission,"issuedAt" to time.toString(),"expiresAt" to b.getString("expiresAt")))
            onChange();return@withLock encodeInvite(invite)
        }
        require(ChatProtocol.participant(b.getJSONObject("owner"))==self()) {"Ask the owner to activate automatic admission, or use an approval invitation."}
        val members=JSONArray(b.getJSONArray("members").toString())
        val existing=members.objects().firstOrNull { ChatProtocol.participant(it.getJSONObject("profile"))==recipient }
        if(existing==null){ requireRosterSpace(members); members.put(obj("profile" to person,"role" to "MEMBER","joinedAt" to now().toString(),"removedAt" to null)) } else { existing.put("removedAt",JSONObject.NULL).put("profile",person) }
        val policy=revised(old,b.getString("name"),b.getString("visibility"),members); applyPolicy(policy,true)
        val time=now(); val invite=signed(obj("v" to 1,"kind" to "CHAT_INVITE","id" to UUID.randomUUID().toString(),"policy" to policy,"recipientId" to recipient,"issuedAt" to time.toString(),"expiresAt" to policy.getJSONObject("body").getString("expiresAt")))
        onChange(); encodeInvite(invite)
    } }
    suspend fun createJoinLink(id:String):String=withContext(Dispatchers.IO) { lock.withLock {
        val policy=current(id)?:error("Channel is unavailable.");val b=policy.getJSONObject("body")
        require(b.getString("visibility")=="INVITE" && !b.getBoolean("deleted")) {"Join links are only available for active private channels."}
        require(ChannelGovernance.capabilities(policy,self()).getBoolean("canInvite")) {"You do not have permission to create an invitation."}
        val issued=now();val admission=b.optJSONObject("settings")?.optString("admission")?:"INVITE_AUTO"
        val invite=signed(obj("v" to 1,"kind" to "CHAT_ADMISSION","id" to UUID.randomUUID().toString(),"channelId" to id,"name" to b.getString("name"),"owner" to b.getJSONObject("owner"),"issuer" to profile(),"recipientId" to "*","policyHash" to Protocol.hash(policy),"admission" to if(admission=="INVITE_AUTO")"INVITE_PLUS_APPROVAL" else admission,"issuedAt" to issued.toString(),"expiresAt" to b.getString("expiresAt")))
        onChange();encodeInvite(invite)
    } }
    private fun encodeInvite(invite:JSONObject):String {val bytes=ByteArrayOutputStream(); GZIPOutputStream(bytes).use { it.write(invite.toString().toByteArray()) };return "cjpswarm://invite/"+Protocol.b64(bytes.toByteArray())}
    fun decodeInvite(link:String):JSONObject {
        val uri=Uri.parse(link.trim()); require(uri.scheme=="cjpswarm" && uri.host=="invite" && uri.query==null && uri.fragment==null && link.length<=700000)
        val token=uri.path?.removePrefix("/")?:error("Invitation is incomplete."); require(token.length in 1..699980 && !token.contains('/'))
        val output=ByteArrayOutputStream(); GZIPInputStream(Protocol.decode(token).inputStream()).use { input -> val buffer=ByteArray(1024); while(true){val n=input.read(buffer);if(n<0)break;require(output.size()+n<=524288);output.write(buffer,0,n)} }
        val invite=JSONObject(output.toString(Charsets.UTF_8.name())); ChatProtocol.invite(invite,self(),now()); return invite
    }
    suspend fun acceptInvite(link:String):String=withContext(Dispatchers.IO) { lock.withLock {
        val invite=decodeInvite(link); val b=invite.getJSONObject("body"); val id=if(b.getString("kind")=="CHAT_ADMISSION")b.getString("channelId") else b.getJSONObject("policy").getJSONObject("body").getString("id")
        val existing=store.get("chat-invites",b.getString("id"))
        require(existing==null || existing.getString("hash")==Protocol.hash(invite))
        if(b.getString("kind")=="CHAT_ADMISSION") {
            require(existing==null){"This invitation has already been used. Ask for a new one."}
            val request=makeJoin(id,"JOIN",invite);save("chat-joins",id,obj("id" to id,"request" to request))
            save("chat-conversations",id,obj("id" to id,"type" to "CHANNEL","title" to b.getString("name"),"muted" to false,"joined" to false,"pendingJoin" to true,"lastRead" to Instant.EPOCH.toString()))
            save("chat-invites",b.getString("id"),obj("id" to b.getString("id"),"hash" to Protocol.hash(invite),"expiresAt" to b.getString("expiresAt")))
            if(session.confirmed)session.send("CHAT_JOIN",request);onChange();return@withLock id
        }
        val p=b.getJSONObject("policy"); val old=current(id); require(old==null || old.getJSONObject("body").getInt("version")<=p.getJSONObject("body").getInt("version")) { "This invitation has been replaced. Ask for a new one." }
        applyPolicy(p,true); save("chat-invites",b.getString("id"),obj("id" to b.getString("id"),"hash" to Protocol.hash(invite),"expiresAt" to b.getString("expiresAt")))
        announceUnlocked(); onChange(); id
    } }
    suspend fun moderate(id:String,action:String,target:String,role:String?=null,reaction:String?=null)=withContext(Dispatchers.IO){lock.withLock{moderateUnlocked(id,action,target,role,reaction)}}
    private suspend fun moderateUnlocked(id:String,action:String,target:String,role:String?=null,reaction:String?=null){
        val policy=current(id)?:error("Channel is unavailable.");val time=now()
        val body=obj("v" to 1,"kind" to "CHAT_ACTION","id" to UUID.randomUUID().toString(),"channelId" to id,"actor" to profile(),"policyHash" to Protocol.hash(policy),"version" to policy.getJSONObject("body").getInt("version"),"action" to action,"targetId" to target,"issuedAt" to time.toString(),"expiresAt" to time.plusSeconds(21600).toString())
        if(role!=null)body.put("role",role);if(reaction!=null)body.put("reaction",reaction)
        val envelope=signed(body);receiveAction(envelope,false)
        if(action=="REJECT_JOIN" && ChatProtocol.participant(policy.getJSONObject("body").getJSONObject("owner"))==self() && peer?.let{ChatProtocol.participant(it)}==target)session.send("CHAT_ADMISSION_REJECTION",envelope)
        if(session.confirmed && peer?.let{ChatProtocol.member(policy,ChatProtocol.participant(it))}==true)session.send("CHAT_ACTION",envelope)
        onChange()
    }
    suspend fun configure(id:String,mode:String,admission:String,type:String=if(mode=="ANNOUNCEMENT")"ANNOUNCE" else "FREE")=withContext(Dispatchers.IO){lock.withLock{
        val p=current(id)?:error("Channel is unavailable.");val b=p.getJSONObject("body");require(ChatProtocol.participant(b.getJSONObject("owner"))==self()&&!pendingMembership(p))
        val settings=GroupType.settings(type,admission)
        val next=revised(p,b.getString("name"),b.getString("visibility"),b.getJSONArray("members"),settings=settings);applyPolicy(next,true);announceUnlocked();onChange()
    }}
    private suspend fun receiveAction(envelope:JSONObject,server:Boolean){
        val b=envelope.getJSONObject("body");val id=b.getString("id");val policy=current(b.getString("channelId"))?:error("Channel is unavailable.")
        val old=store.get("chat-actions",id)
        if(old!=null){require(Protocol.hash(old.getJSONObject("envelope"))==Protocol.hash(envelope));if(server){old.put("serverSaved",true);save("chat-actions",id,old)};return}
        val consumed=policy.getJSONObject("body").optJSONArray("appliedActions")?.strings()?.contains(id)==true
        val authorization=if(Protocol.hash(policy)==b.getString("policyHash"))policy else if(consumed)store.get("chat-policy-history",b.getString("policyHash"))?.getJSONObject("policy")?:error("Moderation history is unavailable.") else error("Permissions changed. This action needs review.")
        ChannelGovernance.action(envelope,authorization,if(consumed)Instant.parse(b.getString("issuedAt"))else now())
        val a=b.getString("action");val actor=ChatProtocol.participant(b.getJSONObject("actor"))
        if(!consumed){
            if(a !in listOf("REACT","UNREACT","REVIEW_REPORT")){
                val family=if(a in ChannelGovernance.membershipActions)ChannelGovernance.membershipActions else if(a in listOf("LOCK_THREAD","UNLOCK_THREAD"))listOf("LOCK_THREAD","UNLOCK_THREAD") else listOf("HIDE_MESSAGE","RESTORE_MESSAGE")
                require(actions(b.getString("channelId")).none{r->val prior=r.getJSONObject("envelope").getJSONObject("body");prior.getInt("version")==b.getInt("version") && prior.getString("targetId")==b.getString("targetId") && prior.getString("action") in family && policy.getJSONObject("body").optJSONArray("appliedActions")?.strings()?.contains(prior.getString("id"))!=true}){"A conflicting action needs a fresh owner policy before retrying."}
            }
            val denied=actions(b.getString("channelId")).any{r->val prior=r.getJSONObject("envelope").getJSONObject("body");prior.getInt("version")==b.getInt("version") && prior.getString("targetId")==actor && prior.getString("action") in listOf("REMOVE","BAN","SET_ROLE")}
            require(!denied){"Moderator authority has changed."}
            if(a in ChannelGovernance.membershipActions){
                if(a in listOf("APPROVE_JOIN","REJECT_JOIN"))require(joinRequests(b.getString("channelId")).any{ChatProtocol.participant(it.getJSONObject("request").getJSONObject("body").getJSONObject("participant"))==b.getString("targetId")}) {"Pending request is unavailable."}
            }else{
                val message=store.get("chat-messages",b.getString("targetId"))?.getJSONObject("envelope")?.getJSONObject("body")?:error("Message is unavailable.")
                require(message.getString("conversationId")==b.getString("channelId") && Instant.parse(message.getString("expiresAt"))>now())
                if(a in listOf("LOCK_THREAD","UNLOCK_THREAD"))require(!message.has("threadRootId"))
            }
        }
        save("chat-actions",id,obj("id" to id,"envelope" to envelope,"serverSaved" to server,"receivedAt" to now().toString(),"owned" to (actor==self())))
        if(!consumed && ChatProtocol.participant(policy.getJSONObject("body").getJSONObject("owner"))==self() && a !in listOf("REACT","UNREACT","REVIEW_REPORT")) incorporateAction(envelope,policy)
        onChange()
    }
    private suspend fun incorporateAction(envelope:JSONObject,policy:JSONObject){
        val a=envelope.getJSONObject("body");val b=JSONObject(policy.getJSONObject("body").toString());val members=b.getJSONArray("members");val target=a.getString("targetId");val kind=a.getString("action")
        val member=members.objects().firstOrNull{ChatProtocol.participant(it.getJSONObject("profile"))==target}
        val bans=b.optJSONArray("bannedIds")?.strings()?.toMutableSet()?:mutableSetOf()
        when(kind){
            "APPROVE_JOIN"->{require(target !in bans);val request=joinRequests(b.getString("id")).first{ChatProtocol.participant(it.getJSONObject("request").getJSONObject("body").getJSONObject("participant"))==target};val person=request.getJSONObject("request").getJSONObject("body").getJSONObject("participant");if(member==null){requireRosterSpace(members);members.put(obj("profile" to person,"role" to "MEMBER","joinedAt" to now().toString(),"removedAt" to null))}else member.put("removedAt",JSONObject.NULL).put("profile",person).put("joinedAt",now().toString());request.put("resolved",true);save("chat-join-inbox",request.getString("id"),request)}
            "REJECT_JOIN"->joinRequests(b.getString("id")).filter{ChatProtocol.participant(it.getJSONObject("request").getJSONObject("body").getJSONObject("participant"))==target}.forEach{it.put("resolved",true);save("chat-join-inbox",it.getString("id"),it)}
            "REMOVE","BAN"->{require(member!=null);member.put("removedAt",now().toString());if(kind=="BAN")bans.add(target)}
            "UNBAN"->bans.remove(target)
            "SET_ROLE"->{require(member!=null && member.isNull("removedAt"));member.put("role",a.getString("role"))}
        }
        val moderation=b.optJSONObject("moderation")?:obj("lockedThreads" to JSONArray(),"hiddenMessages" to JSONArray())
        if(kind in listOf("LOCK_THREAD","UNLOCK_THREAD","HIDE_MESSAGE","RESTORE_MESSAGE")){
            val field=if(kind in listOf("LOCK_THREAD","UNLOCK_THREAD"))"lockedThreads"else"hiddenMessages";val values=moderation.getJSONArray(field).strings().toMutableSet();if(kind in listOf("LOCK_THREAD","HIDE_MESSAGE"))values.add(target)else values.remove(target);require(values.size<=100);moderation.put(field,JSONArray(values.toList()))
        }
        b.put("bannedIds",JSONArray(bans.toList())).put("moderation",moderation).put("appliedActions",JSONArray(((b.optJSONArray("appliedActions")?.strings()?:emptyList())+a.getString("id")).distinct().takeLast(100)))
        val next=revised(obj("body" to b,"signature" to policy.getString("signature")),b.getString("name"),b.getString("visibility"),members);applyPolicy(next,true)
        if(session.confirmed && peer?.let{ChatProtocol.member(policy,ChatProtocol.participant(it)) || ChatProtocol.member(next,ChatProtocol.participant(it))}==true){session.send("CHAT_POLICY",next);if(peer?.let{!ChatProtocol.member(policy,ChatProtocol.participant(it))}==true)session.send("CHAT_ACTION_PROOF",obj("action" to envelope,"policy" to policy))}
    }
    suspend fun membership(id:String,personId:String?,delete:Boolean=false)=withContext(Dispatchers.IO) { lock.withLock {
        if(personId!=null){moderateUnlocked(id,"REMOVE",personId);return@withLock}
        val p=current(id)?:error("Channel is unavailable."); val b=p.getJSONObject("body")
        if(ChatProtocol.participant(b.getJSONObject("owner"))==self()) {
            val members=JSONArray(b.getJSONArray("members").toString())
            val next=revised(p,b.getString("name"),b.getString("visibility"),members,delete)
            applyPolicy(next,true)
            // A removed member must receive the signed revocation, even though future history is denied.
            if(session.confirmed && peer?.let{ChatProtocol.member(p,ChatProtocol.participant(it))}==true)session.send("CHAT_POLICY",next)
        }else{
            require(!delete); val request=makeJoin(id,"LEAVE"); save("chat-joins",id,obj("id" to id,"request" to request))
            val c=store.get("chat-conversations",id)!!; c.put("joined",false); save("chat-conversations",id,c)
            if(session.confirmed) session.send("CHAT_JOIN",request)
        }
        announceUnlocked(); onChange()
    } }
    suspend fun send(id:String,payload:JSONObject,format:String="TEXT",threadRootId:String?=null):String=withContext(Dispatchers.IO) { lock.withLock { sendUnlocked(id,payload,format,threadRootId) } }
    private suspend fun sendUnlocked(id:String,payload:JSONObject,format:String,threadRootId:String?=null):String {
        ChatProtocol.payload(payload,format); val conversation=store.get("chat-conversations",id)?:error("Open a conversation first.")
        require(conversation.optBoolean("joined")) { "Join this channel before sending." }
        val direct=conversation.getString("type")=="DIRECT"; val peerId=if(direct)conversation.getString("peerId") else null
        require(peerId==null || !blocked(peerId)) { "Unblock this person before sending." }
        val p=if(direct)null else current(id)?:error("Channel membership is unavailable.")
        if(p!=null) require(live(p)) { "Waiting for the channel owner to refresh membership. Your conversation is safe." }
        if(p!=null)require(!pendingMembership(p)) {"Membership is changing. Waiting for fresh channel keys."}
        if(threadRootId!=null){require(p!=null && !threadLocked(id,threadRootId));val root=store.get("chat-messages",threadRootId)?.getJSONObject("envelope")?.getJSONObject("body")?:error("Open the original post before replying.");require(root.getString("conversationId")==id && !root.has("threadRootId") && Instant.parse(root.getString("expiresAt"))>now());require(ChannelGovernance.capabilities(p,self()).getBoolean("canCreateThreads") || messages().filter{it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==id}.any{it.getJSONObject("envelope").getJSONObject("body").optString("threadRootId")==threadRootId}){"Your role can reply to existing threads, but cannot start one."}}
        val messageId=UUID.randomUUID().toString(); val pb=p?.getJSONObject("body"); val hash=p?.let { Protocol.hash(it) }
        val encrypted=direct || pb?.getString("visibility")=="INVITE"
        val kid=if(direct)"dm:$id:$messageId:$peerId" else "channel:$id:${pb!!.getString("epoch")}:$messageId"
        val recipient=if(direct) store.get("chat-contacts",peerId!!)?.getJSONObject("profile")?:error("This person's identity is unavailable.") else null
        val content=if(!encrypted) payload.toString() else ChatProtocol.encrypt(payload,recipient?.getJSONObject("body")?.getJSONObject("encryptionKey"),if(direct)null else Protocol.decode(store.get("chat-keys",hash!!)!!.getString("key")),kid)
        // A recently received policy can be slightly ahead of this phone's clock.
        // Keep the existing five-minute validator bound and never date a post before its authority.
        val observed=now();val time=if(pb==null)observed else maxOf(observed,Instant.parse(pb.getString("issuedAt")))
        val sequence=(store.get("chat-sequences",id)?.optInt("value")?:0)+1
        val body=obj("v" to 1,"kind" to "CHAT_MESSAGE","id" to messageId,"conversationId" to id,"author" to profile(),"recipientId" to peerId,"policyHash" to hash,"channelVersion" to (pb?.getInt("version")?:0),"epoch" to pb?.getString("epoch"),"sequence" to sequence,"createdAt" to time.toString(),"expiresAt" to time.plusSeconds(604800).toString(),"format" to format,"encrypted" to encrypted,"content" to content)
        if(threadRootId!=null)body.put("threadRootId",threadRootId)
        val envelope=signed(body); ChatProtocol.message(envelope,p,observed)
        save("chat-messages",messageId,obj("id" to messageId,"envelope" to envelope,"payload" to payload,"owned" to true,"hops" to 0,"receivedAt" to observed.toString(),"serverSaved" to false))
        store.put("chat-sequences",id,obj("value" to sequence)); onChange()
        runCatching { sendRecord(store.get("chat-messages",messageId)!!) }; return messageId
    }
    private fun eligible(message:JSONObject,personId:String):Boolean {
        val b=message.getJSONObject("envelope").getJSONObject("body")
        val author=ChatProtocol.participant(b.getJSONObject("author"))
        if(author==personId || blocked(personId) || blocked(author) || Instant.parse(b.getString("expiresAt"))<=now() || message.getInt("hops")>=6) return false
        if(!b.isNull("recipientId"))return b.getString("recipientId")==personId && message.getBoolean("owned")
        val current=current(b.getString("conversationId"))?:return false
        val historic=store.get("chat-policy-history",b.getString("policyHash"))?.getJSONObject("policy")?:return false
        return live(current) && !pendingMembership(current) && ChatProtocol.member(current,personId) && (historic.getJSONObject("body").getString("visibility")=="OPEN" || ChatProtocol.member(historic,personId))
    }
    private suspend fun sendRecord(record:JSONObject) {
        val person=peer?:return; if(!session.confirmed || !eligible(record,ChatProtocol.participant(person)))return
        val b=record.getJSONObject("envelope").getJSONObject("body")
        if(!b.isNull("policyHash")) {
            val p=store.get("chat-policy-history",b.getString("policyHash"))!!.getJSONObject("policy")
            sendPolicy(p,if(Protocol.hash(p)==current(b.getString("conversationId"))?.let { Protocol.hash(it) })"CHAT_POLICY" else "CHAT_HISTORY_POLICY")
        }
        session.send("CHAT_MESSAGE",obj("envelope" to record.getJSONObject("envelope"),"hops" to record.getInt("hops")+1))
        store.get("chat-manifests",record.getString("id"))?.let{session.send("CHAT_ATTACHMENT_META",it.getJSONObject("manifest"))}
        record.put("sentNearby",now().toString()); save("chat-messages",record.getString("id"),record)
    }
    private suspend fun sendPolicy(policy:JSONObject,kind:String="CHAT_POLICY") {
        val generation=session.connectionGeneration
        if(policySendGeneration!=generation){sentPolicyFrames.clear();policySendGeneration=generation}
        val key=kind+":"+Protocol.hash(policy)
        if(key in sentPolicyFrames)return
        session.send(kind,policy)
        // Both native radio transports are ordered/reliable. Reset resends before any message.
        // Transport completion does not acknowledge message delivery or policy acceptance.
        if(generation==session.connectionGeneration){if(sentPolicyFrames.size>=1024)sentPolicyFrames.clear();sentPolicyFrames.add(key)}
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
        if(!server && cp!=null)require(ChatProtocol.member(cp,author)){"This author's membership has been removed. Held earlier history remains available."}
        if(cp!=null && Protocol.hash(cp)==b.getString("policyHash")){
            require(!pendingMembership(cp)) {"Waiting for fresh membership."}
            if(b.has("threadRootId"))require(!threadLocked(b.getString("conversationId"),b.getString("threadRootId"))) {"Thread is locked."}
        }
        ChatProtocol.message(envelope,p,now(),history=true)
        val old=store.get("chat-messages",id)
        require(old==null || Protocol.hash(old.getJSONObject("envelope"))==Protocol.hash(envelope)) { "Conflicting message identifier." }
        if(old!=null){ if(!old.optBoolean("owned"))sendReceipt(old,"DELIVERED");return }
        if(p!=null && b.has("threadRootId") && !ChannelGovernance.capabilities(p,author).getBoolean("canCreateThreads"))require(messages().any{it.getJSONObject("envelope").getJSONObject("body").let{held->held.getString("conversationId")==b.getString("conversationId") && held.optString("threadRootId")==b.getString("threadRootId")}}){"Receive the existing thread before this reply."}
        if(p==null)require(b.getString("recipientId")==self()) { "This private message belongs to another person." }
        val kid=if(p==null)"dm:${b.getString("conversationId")}:$id:${b.getString("recipientId")}" else "channel:${b.getString("conversationId")}:${b.getString("epoch")}:$id"
        val payload=if(!b.getBoolean("encrypted"))JSONObject(b.getString("content")) else ChatProtocol.decrypt(b.getString("content"),if(p==null)store.get("chat","encryption")!! else null,if(p==null)null else Protocol.decode(store.get("chat-keys",b.getString("policyHash"))?.getString("key")?:error("Older private history is not shared with new members.")),kid)
        ChatProtocol.payload(payload,b.getString("format")); remember(b.getJSONObject("author"))
        if(p==null && store.get("chat-conversations",b.getString("conversationId"))==null)save("chat-conversations",b.getString("conversationId"),obj("id" to b.getString("conversationId"),"type" to "DIRECT","peerId" to author,"title" to b.getJSONObject("author").getJSONObject("body").getString("name"),"muted" to false,"joined" to true,"lastRead" to Instant.EPOCH.toString()))
        val record=obj("id" to id,"envelope" to envelope,"payload" to payload,"owned" to false,"hops" to hops,"receivedAt" to now().toString(),"serverSaved" to server)
        save("chat-messages",id,record); sendReceipt(record,"DELIVERED")
        // Delete for everyone: drop the deleted message's media when its author asked.
        payload.optString("deletes").ifEmpty{null}?.let{target->store.get("chat-messages",target)?.takeIf{ChatProtocol.participant(it.getJSONObject("envelope").getJSONObject("body").getJSONObject("author"))==author}?.getJSONObject("payload")?.optJSONObject("attachment")?.let{session.removeFile(it.getString("id"))}}
        onIncoming(id);onChange()
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
    /** Delete for me: hidden on this phone only (re-synced copies stay hidden); its media file is removed. */
    suspend fun deleteForMe(messageId:String)=withContext(Dispatchers.IO){lock.withLock{
        val record=store.get("chat-messages",messageId)?:return@withLock
        save("chat-deleted",messageId,obj("id" to messageId))
        record.getJSONObject("payload").optJSONObject("attachment")?.let{session.removeFile(it.getString("id"))}
        onChange()
    }}
    /** Delete for everyone: a signed SYSTEM message naming the author's own message; every phone hides it. */
    suspend fun deleteForEveryone(messageId:String)=withContext(Dispatchers.IO){lock.withLock{
        val record=store.get("chat-messages",messageId)?:error("Message is unavailable.")
        require(record.optBoolean("owned")){"Only the sender can delete a message for everyone."}
        val b=record.getJSONObject("envelope").getJSONObject("body")
        sendUnlocked(b.getString("conversationId"),obj("deletes" to messageId),"SYSTEM",b.optString("threadRootId").ifEmpty{null})
        record.getJSONObject("payload").optJSONObject("attachment")?.let{session.removeFile(it.getString("id"))}
        onChange()
    }}
    /** Forward text or a verified attachment to other chats, marked as forwarded. */
    suspend fun forward(messageId:String,targets:List<String>)=withContext(Dispatchers.IO){
        val record=store.get("chat-messages",messageId)?:error("Message is unavailable.")
        val payload=record.getJSONObject("payload");val attachment=payload.optJSONObject("attachment")
        if(attachment==null){targets.forEach{send(it,obj("text" to payload.getString("text"),"forwarded" to true))};return@withContext}
        val copy=java.io.File(java.io.File(context.cacheDir,"chat-processing").apply{mkdirs()},UUID.randomUUID().toString())
        try{
            copy.outputStream().use{decryptTo(messageId,it)}
            targets.forEach{attach(it,Uri.fromFile(copy),attachment.getString("mime"),attachment.getString("name"),forwarded=true)}
        }finally{copy.delete()}
    }
    suspend fun mute(id:String)=withContext(Dispatchers.IO) { lock.withLock { val c=store.get("chat-conversations",id)!!;c.put("muted",!c.optBoolean("muted"));save("chat-conversations",id,c);onChange() } }
    suspend fun block(id:String)=withContext(Dispatchers.IO) { lock.withLock { if(blocked(id))store.remove("chat-blocks",id) else save("chat-blocks",id,obj("id" to id));onChange() } }
    suspend fun report(messageId:String,reason:String)=withContext(Dispatchers.IO) { lock.withLock { require(reason in listOf("ABUSE","SPAM","SAFETY","OTHER") && store.get("chat-messages",messageId)!=null);require(store.all("chat-reports").size<100){"Report queue is full. Connect to send pending reports."};val id=UUID.randomUUID().toString();save("chat-reports",id,obj("id" to id,"messageId" to messageId,"reason" to reason));onChange() } }
    suspend fun reportPerson(personId:String,reason:String)=withContext(Dispatchers.IO){lock.withLock{require(reason in listOf("ABUSE","SPAM","SAFETY","OTHER") && personId!=self() && store.get("chat-contacts",personId)!=null);require(store.all("chat-reports").size<100){"Report queue is full. Connect to send pending reports."};val id=UUID.randomUUID().toString();save("chat-reports",id,obj("id" to id,"personId" to personId,"reason" to reason));onChange()}}
    fun reports(channelId:String)=store.all("chat-report-inbox").filter{report->report.getString("channelId")==channelId && !actions(channelId).any{a->val b=a.getJSONObject("envelope").getJSONObject("body");b.getString("action")=="REVIEW_REPORT" && b.getString("targetId")==report.getString("messageId") && b.getString("issuedAt")>=report.getString("createdAt")}}
    suspend fun clearConversation(id:String)=withContext(Dispatchers.IO) { lock.withLock { require(store.get("chat-conversations",id)?.optBoolean("joined")==false || id.startsWith("dm:")); messages().filter { it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")==id }.forEach { store.remove("chat-messages",it.getString("id")) }; onChange() } }
    suspend fun announce()=withContext(Dispatchers.IO) { lock.withLock { announceUnlocked() } }
    /** Retransmit recent messages missing a receipt from this peer, with stable IDs for deduplication. */
    suspend fun retryPendingNearbyDelivery(): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        val person = peer?.let { ChatProtocol.participant(it) } ?: return@withLock false
        if (!session.confirmed || blocked(person)) return@withLock false
        val cutoff = now().minusSeconds(10 * 60)
        val pending = messages().filter { record ->
            val body = record.getJSONObject("envelope").getJSONObject("body")
            val id = record.getString("id")
            val peerDelivered = store.get("chat-receipts", "$id:$person:DELIVERED") != null || store.get("chat-receipts", "$id:$person:READ") != null
            !record.optBoolean("attention") && Instant.parse(body.getString("createdAt")).isAfter(cutoff) &&
                !peerDelivered && eligible(record, person)
        }.take(50)
        pending.forEach { record -> runCatching { sendRecord(record) } }
        pending.any { record ->
            val id = record.getString("id")
            store.get("chat-receipts", "$id:$person:DELIVERED") == null && store.get("chat-receipts", "$id:$person:READ") == null
        }
    } }
    /** Offers this phone's attachments that the connected person has not received yet, one at a time, so a clip or
     *  photo sent while the link was down still arrives after reconnecting. */
    suspend fun offerUndelivered()=withContext(Dispatchers.IO){lock.withLock{offerUndeliveredUnlocked()}}
    private suspend fun offerUndeliveredUnlocked(){
        val person=peer?.let{ChatProtocol.participant(it)}?:return
        if(!session.confirmed || session.offerInFlight())return
        val next=messages().filter{it.optBoolean("owned")}.sortedBy{it.getString("receivedAt")}
            .mapNotNull{m->m.getJSONObject("payload").optJSONObject("attachment")?.let{a->store.get("attachments",a.getString("id"))}}
            .firstOrNull{f->f.optBoolean("complete") && person !in (f.optJSONArray("deliveredTo")?.strings()?:emptyList()) && fileAllowed(f.getString("id"),f.getString("hash"))}?:return
        runCatching{session.offerSaved(next)}
    }
    private suspend fun announceUnlocked() {
        if(!session.confirmed)return
        renewOwned()
        session.send("CHAT_PROFILE",profile())
        val channels=policies().filter { live(it) && it.getJSONObject("body").getString("visibility")=="OPEN" }.take(16).map { val b=it.getJSONObject("body");obj("id" to b.getString("id"),"name" to b.getString("name"),"ownerId" to ChatProtocol.participant(b.getJSONObject("owner")),"members" to b.getJSONArray("members").objects().count { it.isNull("removedAt") },"version" to b.getInt("version"),"visibility" to "OPEN") }
        session.send("CHAT_DISCOVERY",signed(obj("v" to 1,"kind" to "CHAT_DISCOVERY","profile" to profile(),"channels" to JSONArray(channels))))
        val person=peer?.let { ChatProtocol.participant(it) }?:return
        if(blocked(person))return
        for(p in policies()) if(ChatProtocol.member(p,person))sendPolicy(p)
        for(join in store.all("chat-joins")) if(Instant.parse(join.getJSONObject("request").getJSONObject("body").getString("expiresAt"))>now())session.send("CHAT_JOIN",join.getJSONObject("request"))
        for(p in policies())if(ChatProtocol.member(p,person)){
            if(ChannelGovernance.capabilities(p,person).getBoolean("canManageMembers"))for(j in joinRequests(p.getJSONObject("body").getString("id")))session.send("CHAT_JOIN",j.getJSONObject("request"))
            for(a in actions(p.getJSONObject("body").getString("id")).takeLast(100)){
                val e=a.getJSONObject("envelope");val proof=store.get("chat-policy-history",e.getJSONObject("body").getString("policyHash"))?.getJSONObject("policy")
                if(proof!=null && p.getJSONObject("body").optJSONArray("appliedActions")?.strings()?.contains(e.getJSONObject("body").getString("id"))==true)session.send("CHAT_ACTION_PROOF",obj("action" to e,"policy" to proof))else session.send("CHAT_ACTION",e)
            }
        }
        for(a in store.all("chat-actions")){val e=a.getJSONObject("envelope");val b=e.getJSONObject("body");if(b.getString("action")=="REJECT_JOIN" && b.getString("targetId")==person && ChatProtocol.participant(b.getJSONObject("actor"))==self() && Instant.parse(b.getString("expiresAt"))>now())session.send("CHAT_ADMISSION_REJECTION",e)}
        val inventory=messages().filter { eligible(it,person) }.map { obj("id" to it.getString("id"),"conversationId" to it.getJSONObject("envelope").getJSONObject("body").getString("conversationId")) }
        session.send("CHAT_INVENTORY",JSONArray(inventory.take(500)))
        offerUndeliveredUnlocked()
    }
    suspend fun receive(frame:JSONObject, generation:Long=session.connectionGeneration)=withContext(Dispatchers.IO) { lock.withLock {
        if(generation!=session.connectionGeneration || !session.confirmed)return@withLock
        val kind=frame.getString("kind")
        if(kind=="CHAT_PROFILE"){
            val profile=frame.getJSONObject("value");val first=peer==null
            require(generation==session.connectionGeneration)
            val participant=verifyTransportPeer(profile)
            if(first)announceUnlocked(); onChange();session.onPeerIdentityVerified(participant);return@withLock
        }
        val person=peer?.let { ChatProtocol.participant(it) }?:return@withLock
        if(blocked(person))return@withLock
        when(kind){
            "CHAT_ADMISSION_REJECTION"->{val e=frame.getJSONObject("value");val b=e.getJSONObject("body");val join=store.get("chat-joins",b.getString("channelId"))?.getJSONObject("request")?:return@withLock;val invite=join.getJSONObject("body").optJSONObject("invitation")?:return@withLock;val descriptor=invite.getJSONObject("body");val owner=descriptor.getJSONObject("owner");ChatProtocol.profile(owner,now());require(b.get("v")==1 && b.getString("kind")=="CHAT_ACTION" && b.getString("action")=="REJECT_JOIN" && b.getString("targetId")==self() && b.getString("policyHash")==descriptor.getString("policyHash") && ChatProtocol.participant(b.getJSONObject("actor"))==ChatProtocol.participant(owner) && Instant.parse(b.getString("expiresAt"))>now() && Instant.parse(b.getString("issuedAt"))<=now().plusSeconds(300) && Protocol.verify(b,e.getString("signature"),owner.getJSONObject("body").getJSONObject("publicKey"))){"Join decision could not be verified."};store.get("chat-conversations",b.getString("channelId"))?.let{it.put("pendingJoin",false).put("joinStatus","REJECTED");save("chat-conversations",it.getString("id"),it)};store.remove("chat-joins",b.getString("channelId"))}
            "CHAT_ACTION_PROOF"->{val value=frame.getJSONObject("value");value.exact("action","policy");val e=value.getJSONObject("action");val eb=e.getJSONObject("body");val p=current(eb.getString("channelId"))?:error("Channel is unavailable.");require(p.getJSONObject("body").optJSONArray("appliedActions")?.strings()?.contains(eb.getString("id"))==true);val proof=ChatProtocol.policy(value.getJSONObject("policy"),Instant.parse(eb.getString("issuedAt")));require(Protocol.hash(proof)==eb.getString("policyHash") && proof.getJSONObject("body").getString("id")==eb.getString("channelId") && ChatProtocol.participant(proof.getJSONObject("body").getJSONObject("owner"))==ChatProtocol.participant(p.getJSONObject("body").getJSONObject("owner")));save("chat-policy-history",Protocol.hash(proof),obj("id" to Protocol.hash(proof),"policy" to proof));receiveAction(e,false)}
            "CHAT_ATTACHMENT_META" -> {val manifest=frame.getJSONObject("value");val id=manifest.getJSONObject("body").getString("messageId");val record=store.get("chat-messages",id)?:return@withLock;verifyManifest(manifest,record);save("chat-manifests",id,obj("id" to id,"manifest" to manifest))}
            "CHAT_INVITE" -> { val link=frame.getString("value"); decodeInvite(link); onInvite(link) }
            "CHAT_DISCOVERY" -> {
                val value=frame.getJSONObject("value");value.exact("body","signature");val b=value.getJSONObject("body");b.exact("v","kind","profile","channels")
                require(b.get("v")==1 && b.getString("kind")=="CHAT_DISCOVERY" && ChatProtocol.participant(ChatProtocol.profile(b.getJSONObject("profile"),now()))==person && Protocol.verify(b,value.getString("signature"),peer!!.getJSONObject("body").getJSONObject("publicKey")))
                val descriptors=b.getJSONArray("channels").objects();require(descriptors.size<=16)
                descriptors.forEach { it.exact("id","name","ownerId","members","version","visibility");UUID.fromString(it.getString("id"));require(it.getString("name").trim().length in 1..48 && it.getString("ownerId").matches(Regex("[a-f0-9]{64}")) && it.getInt("members") in 1..ChatProtocol.MAX_CHANNEL_MEMBERS && it.getInt("version")>0 && it.getString("visibility")=="OPEN") }
                discovery=descriptors;onChange()
            }
            "CHAT_POLICY" -> { val p=frame.getJSONObject("value"); val c=store.get("chat-conversations",p.getJSONObject("body").getString("id")); if(c!=null) {applyPolicy(p);onChange()} }
            "CHAT_HISTORY_POLICY" -> {
                val p=frame.getJSONObject("value");val b=p.getJSONObject("body"); val cp=current(b.getString("id"))?:return@withLock
                require(live(cp) && ChatProtocol.member(cp,person) && b.getInt("version")<cp.getJSONObject("body").getInt("version") && ChatProtocol.participant(b.getJSONObject("owner"))==ChatProtocol.participant(cp.getJSONObject("body").getJSONObject("owner")))
                ChatProtocol.policy(p,Instant.parse(b.getString("issuedAt")));require(b.getString("visibility")=="OPEN" || ChatProtocol.member(p,self())&&ChatProtocol.member(p,person));archive(p)
            }
            "CHAT_JOIN" -> {handleJoin(frame.getJSONObject("value"));onChange()}
            "CHAT_ACTION" -> {receiveAction(frame.getJSONObject("value"),false)}
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
        val configurationHash=repository.configuration?.let{Protocol.hash(it)}?:""
        val capability=store.get("chat","sync-capability")
        val paging=configurationHash.isNotEmpty() && capability?.optString("configurationHash")==configurationHash && capability?.optBoolean("policyPaging")==true
        val body=obj("v" to 1,"kind" to "CHAT_SYNC","id" to UUID.randomUUID().toString(),"profile" to profile(),"issuedAt" to time.toString(),"channelIds" to JSONArray(conversations().filter{it.getString("type")=="CHANNEL"}.sortedBy{if(it.optBoolean("joined")||it.optBoolean("pendingJoin"))0 else 1}.take(16).map{it.getString("id")}),"knownMessages" to JSONArray(held.map{it.getString("id")}),"receiptMessageIds" to JSONArray(held.filter{it.optBoolean("owned")&&!it.has("readAt")}.sortedByDescending{it.getString("receivedAt")}.take(50).map{it.getString("id")}),"peers" to JSONArray(),"policies" to JSONArray(),"messages" to JSONArray(),"receipts" to JSONArray(),"joins" to JSONArray(),"blocks" to JSONArray(store.all("chat-blocks").take(100).map { it.getString("id") }),"reports" to JSONArray(store.all("chat-reports").take(8)))
        body.put("actions",JSONArray())
        if(paging){
            body.put("knownPolicyHashes",JSONArray((policies()+store.all("chat-policy-history").map{it.getJSONObject("policy")}).map{Protocol.hash(it)}.distinct().take(128)))
            body.put("knownJoinIds",JSONArray(store.all("chat-join-inbox").filter{!it.optBoolean("resolved")}.take(100).map{it.getString("id")}))
        }
        fun add(field:String,value:JSONObject,limit:Int):Boolean{
            val array=body.getJSONArray(field);if(array.length()>=limit)return false
            array.put(value);if(Protocol.canonical(body).size>(if(paging)880000 else 82000)){array.remove(array.length()-1);return false};return true
        }
        val pending=held.filter{!it.optBoolean("serverSaved")}.sortedWith(compareBy<JSONObject>{if(it.getJSONObject("payload").has("attachment"))1 else 0}.thenBy{it.getString("receivedAt")})
        val needed=pending.mapNotNull {val b=it.getJSONObject("envelope").getJSONObject("body");if(b.isNull("recipientId"))null else b.getString("recipientId")}.distinct()
        for(contact in contacts().sortedBy{if(it.getString("id") in needed)0 else 1}){
            val p=contact.getJSONObject("profile");if(store.get("chat-server-contacts",contact.getString("id"))?.optString("hash")!=Protocol.hash(p))add("peers",p,16)
        }
        val unsavedActions=store.all("chat-actions").filter{!it.optBoolean("serverSaved")&&!it.optBoolean("rejected")}
        val actionPolicies=unsavedActions.mapNotNull{store.get("chat-policy-history",it.getJSONObject("envelope").getJSONObject("body").getString("policyHash"))?.getJSONObject("policy")}.distinctBy{Protocol.hash(it)}
        for(p in (actionPolicies+policies()).distinctBy{Protocol.hash(it)}.sortedBy{it.getJSONObject("body").getInt("version")}) {
            val b=p.getJSONObject("body");val hash=Protocol.hash(p)
            if(Instant.parse(b.getString("expiresAt"))>time && (ChatProtocol.member(p,self()) || ChatProtocol.participant(b.getJSONObject("owner"))==self()) && store.get("chat-server-policies",hash)==null)add("policies",p,8)
        }
        for(record in pending){
            val envelope=record.getJSONObject("envelope");val b=envelope.getJSONObject("body")
            val recipient=if(b.isNull("recipientId"))null else b.getString("recipientId")
            if(recipient!=null && store.get("chat-server-contacts",recipient)==null && body.getJSONArray("peers").objects().none {ChatProtocol.participant(it)==recipient})continue
            if(recipient==null && store.get("chat-server-policies",b.getString("policyHash"))==null && body.getJSONArray("policies").objects().none{Protocol.hash(it)==b.getString("policyHash")})continue
            add("messages",envelope,20)
        }
        for(r in store.all("chat-receipts"))if(!r.optBoolean("serverSaved") && ChatProtocol.participant(r.getJSONObject("receipt").getJSONObject("body").getJSONObject("recipient"))==self())add("receipts",r.getJSONObject("receipt"),30)
        for(join in store.all("chat-joins"))if(Instant.parse(join.getJSONObject("request").getJSONObject("body").getString("expiresAt"))>time)add("joins",join.getJSONObject("request"),8)
        for(p in policies())if(ChannelGovernance.capabilities(p,self()).getBoolean("canManageMembers"))for(join in joinRequests(p.getJSONObject("body").getString("id")))add("joins",join.getJSONObject("request"),8)
        for(a in unsavedActions)if(Instant.parse(a.getJSONObject("envelope").getJSONObject("body").getString("expiresAt"))>time)add("actions",a.getJSONObject("envelope"),8)
        require(Protocol.canonical(body).size<(if(paging)900000 else 90000)) { "Too much chat work for one check. Share nearby or review an old conversation." }
        val response=try{JSONObject(repository.api("/chat/sync",signed(body),false))}catch(error:Exception){if(paging)store.remove("chat","sync-capability");throw error}; require(response.getInt("v")==1)
        store.put("chat","sync-capability",obj("configurationHash" to configurationHash,"policyPaging" to (response.optJSONObject("capabilities")?.optBoolean("policyPaging")==true)))
        body.getJSONArray("peers").objects().forEach {save("chat-server-contacts",ChatProtocol.participant(it),obj("id" to ChatProtocol.participant(it),"hash" to Protocol.hash(it)))}
        response.getJSONArray("acceptedPolicies").strings().forEach{save("chat-server-policies",it,obj("id" to it))}
        response.getJSONArray("acceptedReceipts").strings().forEach{id->store.get("chat-receipts",id)?.let{it.put("serverSaved",true);save("chat-receipts",id,it)}}
        response.optJSONArray("acceptedActions")?.strings()?.forEach{id->store.get("chat-actions",id)?.let{it.put("serverSaved",true);save("chat-actions",id,it)}}
        response.optJSONArray("rejectedActions")?.objects()?.forEach{r->store.get("chat-actions",r.getString("id"))?.let{it.put("rejected",true).put("rejection",r.getString("reason"));save("chat-actions",it.getString("id"),it)}}
        response.getJSONArray("policies").objects().forEach { p-> if(store.get("chat-conversations",p.getJSONObject("body").getString("id"))!=null)applyPolicy(p) }
        response.getJSONArray("historyPolicies").objects().forEach { p-> val b=p.getJSONObject("body");val cp=current(b.getString("id"));if(cp!=null && live(cp) && ChatProtocol.participant(b.getJSONObject("owner"))==ChatProtocol.participant(cp.getJSONObject("body").getJSONObject("owner"))) {ChatProtocol.policy(p,Instant.parse(b.getString("issuedAt")));if(b.getString("visibility")=="OPEN"||ChatProtocol.member(p,self()))archive(p)} }
        response.getJSONArray("removed").strings().forEach { id-> store.get("chat-conversations",id)?.let { it.put("joined",false);save("chat-conversations",id,it) } }
        response.optJSONArray("joinStates")?.objects()?.forEach{decision->store.get("chat-conversations",decision.getString("channelId"))?.let{c->if(!c.optBoolean("joined")){c.put("joinStatus",decision.getString("status"));if(decision.getString("status")=="REJECTED"){c.put("pendingJoin",false);store.remove("chat-joins",c.getString("id"))};save("chat-conversations",c.getString("id"),c)}}}
        response.getJSONArray("accepted").strings().forEach { id-> store.get("chat-messages",id)?.let { it.put("serverSaved",true);save("chat-messages",id,it) } }
        response.optJSONArray("acceptedReports")?.strings()?.forEach{store.remove("chat-reports",it)}
        response.optJSONArray("reports")?.objects()?.let{reports->store.all("chat-report-inbox").forEach{store.remove("chat-report-inbox",it.getString("id"))};reports.take(100).forEach{r->if(capabilities(r.getString("channelId"))?.optBoolean("canModerate")==true)save("chat-report-inbox",r.getString("id"),r)}}
        response.getJSONArray("rejected").objects().forEach { rejected->store.get("chat-messages",rejected.getString("id"))?.let { it.put("attention",true).put("serverSaved",true);save("chat-messages",it.getString("id"),it) } }
        response.getJSONArray("joins").objects().forEach { join->runCatching {handleJoin(join)} }
        response.getJSONArray("messages").objects().forEach { message->runCatching {receiveMessage(message,0,true)} }
        response.optJSONArray("actions")?.objects()?.forEach { a->runCatching {receiveAction(a,true)} }
        response.getJSONArray("receipts").objects().forEach { receipt->runCatching {receiveReceipt(receipt)} }
        response.getJSONArray("joins").objects().forEach { join->runCatching {handleJoin(join)} }
        announceUnlocked(); prune();onChange()
    } }
    private suspend fun prune() {
        val time=now();val expired=store.all("chat-messages").filter { Instant.parse(it.getJSONObject("envelope").getJSONObject("body").getString("expiresAt"))<=time }
        store.all("chat-actions").filter{Instant.parse(it.getJSONObject("envelope").getJSONObject("body").getString("issuedAt"))<time.minusSeconds(7*86400)}.forEach{store.remove("chat-actions",it.getString("id"))}
        store.all("chat-joins").filter{Instant.parse(it.getJSONObject("request").getJSONObject("body").getString("expiresAt"))<=time}.forEach{j->store.remove("chat-joins",j.getString("id"));store.get("chat-conversations",j.getString("id"))?.takeIf{!it.optBoolean("joined")}?.let{it.put("pendingJoin",false).put("joinStatus","EXPIRED");save("chat-conversations",it.getString("id"),it)}}
        expired.forEach { store.remove("chat-messages",it.getString("id"));store.remove("chat-manifests",it.getString("id"));store.remove("chat-media-progress",it.getString("id")) }
        store.all("chat-invites").filter { Instant.parse(it.getString("expiresAt"))<=time }.forEach { store.remove("chat-invites",it.getString("id")) }
        store.all("chat-link-uses").filter { Instant.parse(it.getString("expiresAt"))<=time }.forEach { store.remove("chat-link-uses",it.getString("id")) }
        val messages=store.all("chat-messages");val ids=messages.map{it.getString("id")}.toSet()
        val attachmentIds=messages.mapNotNull{it.getJSONObject("payload").optJSONObject("attachment")?.optString("id")}.toSet()
        expired.mapNotNull{it.getJSONObject("payload").optJSONObject("attachment")?.optString("id")}.filter{it !in attachmentIds}.forEach{session.removeFile(it)}
        store.all("chat-receipts").filter{it.getJSONObject("receipt").getJSONObject("body").getString("messageId") !in ids}.forEach{store.remove("chat-receipts",it.getString("id"))}
        val retained=messages.mapNotNull{val b=it.getJSONObject("envelope").getJSONObject("body");if(b.isNull("policyHash"))null else b.getString("policyHash")}.toSet()+policies().map{Protocol.hash(it)}+store.all("chat-actions").map{it.getJSONObject("envelope").getJSONObject("body").getString("policyHash")}
        store.all("chat-policy-history").filter{it.getString("id") !in retained}.forEach{store.remove("chat-policy-history",it.getString("id"));store.remove("chat-keys",it.getString("id"));store.remove("chat-server-policies",it.getString("id"))}
    }
    private fun renewOwned(){
        for(p in policies()){val b=p.getJSONObject("body");if(ChatProtocol.participant(b.getJSONObject("owner"))==self()&&!b.getBoolean("deleted")&&Instant.parse(b.getString("expiresAt"))<now().plusSeconds(1800))applyPolicy(revised(p,b.getString("name"),b.getString("visibility"),b.getJSONArray("members")),true)}
    }
}
