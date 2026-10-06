package org.saathi.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.webrtc.VideoTrack
import android.media.MediaRecorder
import android.os.Build
import java.io.File

data class AppState(
    val requests: List<JSONObject> = emptyList(), val completed: List<JSONObject> = emptyList(), val posts: List<JSONObject> = emptyList(),
    val events: List<JSONObject> = emptyList(), val messages: List<JSONObject> = emptyList(), val files: List<JSONObject> = emptyList(), val drafts: List<JSONObject> = emptyList(), val donations: List<JSONObject> = emptyList(), val operations: List<JSONObject> = emptyList(),
    val preparation: JSONObject? = null, val account: JSONObject? = null, val dashboard: JSONObject? = null, val devices: List<JSONObject> = emptyList(), val savedAt: String? = null,
    val reachable: Boolean = false, val authenticated: Boolean = false, val busy: Boolean = false, val notice: String? = null,
    val nearbyStatus: String = "Ready to connect nearby", val connected: Boolean = false, val confirmed: Boolean = false, val media: Boolean = false,
    val peers: Map<String, String> = emptyMap(), val pairCode: String? = null, val localCode: String = "", val invitation: String = "",
    val fileOffer: JSONObject? = null, val incomingCall: Boolean? = null, val calling: Boolean = false, val callActive: Boolean = false,
    val video: Boolean = false, val remoteVideo: VideoTrack? = null, val quality: String = ""
    ,val chatProfile: JSONObject? = null, val chatContacts: List<JSONObject> = emptyList(), val conversations: List<JSONObject> = emptyList(), val chatMessages: List<JSONObject> = emptyList(),
    val chatPeer: JSONObject? = null, val nearbyChannels: List<JSONObject> = emptyList(), val recording: Boolean = false, val incomingInvite:String?=null
)

class SaathiViewModel @JvmOverloads constructor(application: Application, storageScope:String=BuildConfig.ENVIRONMENT, startServices:Boolean=true) : AndroidViewModel(application) {
    val repository = Repository(application,storageScope)
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    val nearby = NearbyTransport(application, viewModelScope)
    private val wifiDelegate = lazy { LocalWifiTransport(application, viewModelScope).apply {
        onState = { status -> mutable.update { it.copy(nearbyStatus = status, connected = connected) }; if (!connected) { this@SaathiViewModel.session.reset(); endMedia() }; refreshLocal() }
        onCode = { code -> mutable.update { it.copy(localCode = code) } }
        onFrame = { this@SaathiViewModel.session.incoming(it) }
        onVideo = { video -> mutable.update { it.copy(remoteVideo = video) } }
        onQuality = { quality -> mutable.update { it.copy(quality = quality) } }
    } }
    val wifi by wifiDelegate
    val session = PeerSession(application, repository, viewModelScope)
    val chat = ChatRepository(application,repository,session)
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var voiceTimeout: Job? = null
    private var healthCheck: Job? = null
    private var ringTimeout: Job? = null
    private val incomingChats=mutableSetOf<String>()
    private var chatNotice:Job?=null
    private fun chatAction(block:suspend()->Unit)=action {require(BuildConfig.CHAT_ENABLED){"Chat is available in development and QA builds while security review is pending."};block()}
    private fun batchChatNotice(id:String){
        synchronized(incomingChats){incomingChats.add(id)}
        if(chatNotice?.isActive==true)return
        chatNotice=viewModelScope.launch{delay(1200);val ids=synchronized(incomingChats){incomingChats.toList().also{incomingChats.clear()}};withContext(Dispatchers.IO){
            val fresh=ids.mapNotNull{repository.store.get("chat-messages",it)}.filter{m->val c=repository.store.get("chat-conversations",m.getJSONObject("envelope").getJSONObject("body").getString("conversationId"));c!=null&&!c.optBoolean("muted")&&!m.optBoolean("readLocally")}
            if(fresh.isNotEmpty())notice("${fresh.size} new message${if(fresh.size==1)"" else "s"} received${if(fresh.any{it.getJSONObject("payload").optJSONArray("mentions")?.strings()?.contains(ChatProtocol.participant(chat.profile()))==true})" · You were mentioned" else ""}.")
        }}
    }
    init {
        session.onChatConnected = { if(BuildConfig.CHAT_ENABLED)chat.announce() }; session.onChatFrame = { frame, generation -> if(BuildConfig.CHAT_ENABLED)chat.receive(frame,generation) }; session.onChatReset = { chat.reset() }; chat.onChange = { refreshLocal() }; chat.onInvite = { receiveInvite(it) }
        session.chatFileAllowed = { id,hash -> chat.fileAllowed(id,hash) }; session.onChatFileComplete = { chat.completeFile(it) }
        chat.onIncoming={batchChatNotice(it)}
        File(application.cacheDir,"voice").apply { mkdirs();listFiles()?.forEach { it.delete() } }
        session.onChange = { refreshLocal() }; session.onError = { notice(it) }
        session.onFile = { offer -> mutable.update { it.copy(fileOffer = offer) } }
        session.onCall = { video -> if (!state.value.calling && state.value.incomingCall == null) { mutable.update { it.copy(incomingCall = video) }; startRingTimeout() } }
        session.onAccepted = { if (state.value.calling) { ringTimeout?.cancel(); mutable.update { it.copy(callActive = true) } } }
        session.onEnded = { endMedia() }
        nearby.onState = { status ->
            mutable.update { it.copy(nearbyStatus = status, connected = nearby.connected) }
            if (nearby.connected) action { session.confirm() } else session.reset()
            refreshLocal()
        }
        nearby.onPeers = { peers -> mutable.update { it.copy(peers = peers) } }
        nearby.onPair = { code -> mutable.update { it.copy(pairCode = code) } }
        nearby.onFrame = { session.incoming(it) }; nearby.onError = { notice(it) }
        refreshLocal(); if(startServices){refresh(); foregroundActive()}
    }
    fun notice(text: String?) { mutable.update { it.copy(notice = text) } }
    fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try { block() } catch (e: Exception) { if (e !is CancellationException) notice(e.message ?: "This action could not finish. Saved work is safe.") }
            finally { mutable.update { it.copy(busy = false, reachable = repository.reachable) }; refreshLocal() }
        }
    }
    fun refreshLocal() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val store = repository.store
                mutable.update { it.copy(requests = repository.requests(), completed = repository.requests(true), posts = repository.snapshot("feed"), savedAt = store.get("public", "freshness")?.optString("savedAt"), authenticated = store.get("credentials", "session") != null) }
                mutable.update { it.copy(chatProfile = chat.profile(), chatContacts = chat.contacts(), conversations = chat.conversations(),chatMessages = chat.messages(),chatPeer = if(session.confirmed)chat.peer else null,nearbyChannels = if(session.confirmed)chat.discovery else emptyList()) }
                mutable.update { it.copy(requests = repository.requests(), completed = repository.requests(true), posts = repository.snapshot("feed"), events = repository.events(), messages = store.all("messages"), files = store.all("attachments"), drafts = store.all("drafts"), donations = store.all("donations"), operations = store.all("operations"), preparation = repository.preparation, account = store.get("account", "user"), savedAt = store.get("public", "freshness")?.optString("savedAt"), confirmed = session.confirmed, media = session.confirmed && session.remoteMedia && session.transport?.mediaAvailable == true && repository.featureFlags?.optBoolean("localCalls") == true) }
            } catch (_: Exception) { notice("Saved information could not be unlocked. Do not clear app storage if you need to recover work.") }
        }
    }
    fun refresh() = action { repository.refresh(); if (repository.preparation != null) loadDashboard();if(BuildConfig.CHAT_ENABLED)runCatching {chat.sync()} }
    fun chatName(name:String)=chatAction { chat.rename(name) }
    fun receiveInvite(link:String?){ if(BuildConfig.CHAT_ENABLED&&link?.startsWith("cjpswarm://invite/")==true && link.length<=44000)mutable.update{it.copy(incomingInvite=link)} }
    fun dismissInvite(){mutable.update{it.copy(incomingInvite=null)}}
    fun needFromChat(message:JSONObject,open:(String)->Unit)=chatAction {
        require(repository.preparation!=null){"Prepare your verified relief account before creating an official need."}
        val text=message.getJSONObject("payload").getString("text");val id=java.util.UUID.randomUUID().toString()
        repository.store.put("drafts",id,obj("id" to id,"draftType" to "request","title" to text.take(100),"description" to text.take(2000),"quantity" to "","unit" to "items","category" to "OTHER","priority" to "NORMAL","hours" to "24","fromChat" to true))
        open("draft:"+id)
    }
    fun discuss(reference:JSONObject,open:(String)->Unit)=chatAction {
        val key=reference.getString("type")+":"+reference.getString("id");val existing=repository.store.get("chat-discussions",key)?.optString("conversationId")
        if(existing!=null && chat.conversations().any {it.getString("id")==existing&&it.optBoolean("joined")})open(existing)
        else {val id=chat.create(reference.getString("title").take(48),"OPEN");repository.store.put("chat-discussions",key,obj("conversationId" to id));chat.send(id,obj("reference" to reference),"RELIEF");open(id);runCatching {chat.sync()}}
    }
    fun openChat(person:JSONObject,open:(String)->Unit)=chatAction { open(chat.direct(person)) }
    fun createChannel(name:String,visibility:String,open:(String)->Unit)=chatAction { open(chat.create(name,visibility));runCatching {chat.sync()} }
    fun joinChannel(id:String)=chatAction { chat.join(id);runCatching {chat.sync()} }
    fun joinInvite(link:String,open:(String)->Unit)=chatAction { open(chat.acceptInvite(link));runCatching {chat.sync()} }
    fun invitePerson(id:String,person:JSONObject,show:(String)->Unit)=chatAction { show(chat.invite(id,person));runCatching {chat.sync()} }
    fun chatSend(id:String,text:String,mentions:List<String> = emptyList(),saved:()->Unit={})=chatAction { val payload=obj("text" to text);if(mentions.isNotEmpty())payload.put("mentions",org.json.JSONArray(mentions));chat.send(id,payload);saved();runCatching {chat.sync()} }
    private val composerVersions=mutableMapOf<String,Long>()
    fun saveChatComposer(id:String,text:String) {
        val version=maxOf(composerVersions[id]?:0,repository.store.get("chat-drafts",id)?.optLong("version")?:0)+1;composerVersions[id]=version
        viewModelScope.launch(Dispatchers.IO){synchronized(repository.store){val old=repository.store.get("chat-drafts",id)?.optLong("version")?:0;if(version>=old)repository.store.put("chat-drafts",id,obj("text" to text,"version" to version))}}
    }
    fun chatReference(id:String,reference:JSONObject)=chatAction { chat.send(id,obj("reference" to reference),"RELIEF");runCatching {chat.sync()} }
    suspend fun readChat(id:String,visibleIds:List<String>) {
        try {chat.read(id,visibleIds)} catch(error:Exception){if(error is CancellationException)throw error;notice("Read confirmation could not be saved. Reopen this conversation to retry.")}
    }
    fun muteChat(id:String)=chatAction { chat.mute(id) }
    fun blockChat(id:String)=chatAction { chat.block(id);runCatching {chat.sync()} }
    fun leaveChat(id:String)=chatAction { chat.membership(id,null);runCatching {chat.sync()} }
    fun removeChatMember(id:String,person:String)=chatAction { chat.membership(id,person);runCatching {chat.sync()} }
    fun deleteChannel(id:String)=chatAction { chat.membership(id,null,true);runCatching {chat.sync()} }
    fun reportChat(id:String)=chatAction { chat.report(id,"ABUSE");runCatching {chat.sync()};notice("Report saved. It will reach the team when connected.") }
    fun syncChats()=chatAction { chat.sync();notice("Chats checked. Recipient confirmations determine delivery.") }
    fun attachChat(id:String,uri:Uri)=chatAction { chat.attach(id,uri);runCatching {chat.sync()} }
    fun shareChatAttachment(id:String)=chatAction { chat.offerAttachment(id) }
    fun clearChat(id:String)=chatAction {chat.clearConversation(id)}
    fun sendNearbyInvite(link:String)=chatAction {require(chat.peer!=null);session.send("CHAT_INVITE",link);notice("Invitation sent nearby. The recipient decides whether to join.")}
    private var attachmentSync:Job?=null
    fun syncChatAttachment(id:String){
        if(!BuildConfig.CHAT_ENABLED || attachmentSync?.isActive==true){notice("Another private attachment is already synchronizing.");return}
        attachmentSync=viewModelScope.launch{try{chat.sync();chat.synchronizeAttachment(id,true);notice("Private attachment checked and synchronized where available.")}catch(e:Exception){if(e !is CancellationException)notice(e.message?:"Attachment paused. Saved chunks will resume later.")}finally{refreshLocal()}}
    }
    fun exportChatAttachment(id:String,uri:Uri)=chatAction {chat.exportAttachment(id,uri)}
    fun startVoice()=chatAction {
        cancelVoice();val file=File(getApplication<Application>().cacheDir,"voice/note.m4a");voiceFile=file
        val recorder=if(Build.VERSION.SDK_INT>=31)MediaRecorder(getApplication()) else MediaRecorder()
        this.recorder=recorder
        try { recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioEncodingBitRate(64000);recorder.setAudioSamplingRate(16000);recorder.setMaxDuration(120000);recorder.setOutputFile(file.path);recorder.prepare();recorder.start();mutable.update {it.copy(recording=true)};voiceTimeout=viewModelScope.launch {delay(120000);cancelVoice();notice("Voice note reached its two-minute limit and was discarded. Record a shorter note.")} }catch(e:Exception){cancelVoice();throw e}
    }
    fun sendVoice(id:String)=chatAction {
        val file=voiceFile?:error("Record a voice note first.");voiceTimeout?.cancel();recorder?.stop();recorder?.release();recorder=null;mutable.update {it.copy(recording=false)}
        try {chat.attach(id,Uri.fromFile(file),"audio/mp4","Voice note.m4a");runCatching {chat.sync()} }finally { file.delete();voiceFile=null }
    }
    fun cancelVoice() { voiceTimeout?.cancel();runCatching {recorder?.stop()};runCatching {recorder?.release()};recorder=null;voiceFile?.delete();voiceFile=null;mutable.update {it.copy(recording=false)} }
    suspend fun loadDashboard() { mutable.update { it.copy(dashboard = JSONObject(repository.api("/volunteer/dashboard", authenticated = true))) } }
    fun login(email: String, password: String, totp: String) = action { repository.login(email, password, totp); loadDashboard(); notice("This phone is ready for offline publishing.") }
    fun saveDraft(id: String, draft: JSONObject) { viewModelScope.launch(Dispatchers.IO) { repository.store.put("drafts", id, JSONObject(draft.toString()).put("id", id)); refreshLocal() } }
    fun publish(id: String, draft: JSONObject, type: String, payload: JSONObject, organization: String, done: () -> Unit) = action {
        withContext(Dispatchers.IO) { repository.store.put("drafts", id, draft) }
        repository.author(type, payload, organization); repository.store.remove("drafts", id); notice("Saved on this phone. Synchronize or share nearby to send it."); done()
    }
    fun sync(carried: Boolean) = action { repository.sync(carried); notice("Saved updates checked with Swarm."); if (repository.reachable) repository.refresh() }
    fun logout(revoke: Boolean) = action { disconnect(); repository.logout(revoke); session.clearFiles(); mutable.update { it.copy(dashboard = null) }; notice("Signed out. Private saved work and this phone’s signing identity were cleared.") }
    fun listDevices() = action { mutable.update { it.copy(devices = org.json.JSONArray(repository.api("/sync/devices", authenticated = true)).objects()) } }
    fun revokeDevice(id: String) = action { repository.api("/sync/devices/$id/revoke", obj(), true); mutable.update { it.copy(devices = org.json.JSONArray(repository.api("/sync/devices", authenticated = true)).objects()) }; notice("Phone revoked by Swarm. Unsent events signed by that phone may be rejected.") }
    private fun activate(transport: PeerTransport) { session.transport?.disconnect(); session.reset(); session.transport = transport; mutable.update { it.copy(invitation = "", localCode = "", media = false, connected = false, callActive = false, calling = false) } }
    fun scan(advertise: Boolean,automatic:Boolean=false) = action { require(repository.featureFlags?.optBoolean("nearby") == true) { "Nearby discovery is not enabled for this environment. Local pairing remains available when configured." }; activate(nearby); nearby.scan(advertise,automatic) }
    fun connect(id: String) = action { nearby.connect(id) }
    fun offer() = action { activate(wifi); mutable.update { it.copy(invitation = wifi.offer()) } }
    fun accept(text: String) = action { if (session.transport !== wifi) activate(wifi); val reply = wifi.accept(text); mutable.update { it.copy(invitation = reply) } }
    fun confirmLocal() = action { session.confirm(); mutable.update { it.copy(localCode = "") } }
    fun disconnect() { session.transport?.disconnect(); session.reset(); endMedia(); mutable.update { it.copy(connected = false, confirmed = false, media = false, invitation = "", localCode = "") } }
    fun sendMessage(text: String) = action { session.message(text) }
    fun share() = action { session.shareEvents(); notice("Sharing eligible signed updates. They remain pending until Swarm confirms them.") }
    fun offerFile(uri: Uri) = action { session.offerFile(uri) }
    fun acceptFile() = action { state.value.fileOffer?.let { session.acceptFile(it) }; mutable.update { it.copy(fileOffer = null) } }
    fun declineFile() = action { state.value.fileOffer?.let { session.send("FILE_CANCEL", obj("id" to it.getString("id"))) }; mutable.update { it.copy(fileOffer = null) } }
    fun call(video: Boolean, incoming: Boolean) = action {
        require(state.value.media && (!state.value.calling || incoming))
        session.pauseTransfersForCall(); ringTimeout?.cancel()
        wifi.capture(video); mutable.update { it.copy(calling = true, callActive = incoming, video = video, incomingCall = null, quality = "") }
        if (incoming) session.send("CALL_ACCEPT", obj()) else { session.send("CALL", obj("video" to video)); startRingTimeout() }
    }
    fun hangup() = action { session.send("CALL_END", obj()); endMedia() }
    private fun startRingTimeout() { ringTimeout?.cancel(); ringTimeout = viewModelScope.launch { delay(60000); runCatching { session.send("CALL_END", obj()) }; endMedia(); notice("Call was not answered. Nearby messages still work.") } }
    private fun endMedia() { ringTimeout?.cancel(); session.callInProgress = false; if (wifiDelegate.isInitialized()) wifi.stopMedia(); mutable.update { it.copy(calling = false, callActive = false, incomingCall = null, video = false, quality = "") } }
    fun foregroundActive() {
        healthCheck?.cancel()
        healthCheck = viewModelScope.launch { while (isActive) { delay(30000); repository.checkReachability(); if(BuildConfig.CHAT_ENABLED){if(repository.reachable)runCatching {chat.sync();chat.autoMedia()}; if(session.confirmed)runCatching {chat.announce()}}; mutable.update { it.copy(reachable = repository.reachable) } } }
    }
    fun foregroundLost() { attachmentSync?.cancel();healthCheck?.cancel(); nearby.stopScan();cancelVoice(); if (state.value.calling) hangup() }
    override fun onCleared() { disconnect(); if (wifiDelegate.isInitialized()) wifi.release(); repository.store.close() }
}
