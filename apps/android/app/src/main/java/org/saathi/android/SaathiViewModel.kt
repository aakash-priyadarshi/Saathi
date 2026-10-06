package org.saathi.android

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.webrtc.VideoTrack
import android.media.MediaRecorder
import android.os.Build
import java.io.File

data class AppState(
    val requests: List<JSONObject> = emptyList(), val completed: List<JSONObject> = emptyList(), val posts: List<JSONObject> = emptyList(),
    val events: List<JSONObject> = emptyList(), val messages: List<JSONObject> = emptyList(), val files: List<JSONObject> = emptyList(), val drafts: List<JSONObject> = emptyList(), val donations: List<JSONObject> = emptyList(), val operations: List<JSONObject> = emptyList(),
    val preparation: JSONObject? = null, val account: JSONObject? = null, val dashboard: JSONObject? = null, val devices: List<JSONObject> = emptyList(), val savedAt: String? = null,
    val reachable: Boolean = false, val needsEnabled: Boolean = true, val authenticated: Boolean = false, val busy: Boolean = false, val notice: String? = null,
    val nearbyStatus: String = "Ready to connect nearby", val connected: Boolean = false, val confirmed: Boolean = false, val media: Boolean = false,
    val peers: Map<String, String> = emptyMap(), val pairCode: String? = null, val localCode: String = "", val invitation: String = "",
    val fileOffer: JSONObject? = null, val incomingCall: Boolean? = null, val calling: Boolean = false, val callActive: Boolean = false,
    val video: Boolean = false, val remoteVideo: VideoTrack? = null, val quality: String = ""
    ,val chatProfile: JSONObject? = null, val chatContacts: List<JSONObject> = emptyList(), val conversations: List<JSONObject> = emptyList(), val chatMessages: List<JSONObject> = emptyList(),
    val chatPeer: JSONObject? = null, val nearbyChannels: List<JSONObject> = emptyList(), val recording: Boolean = false, val incomingInvite:String?=null,
    val chatActions: List<JSONObject> = emptyList(), val preferences:JSONObject = JSONObject(),
    val localHelp:List<JSONObject> = emptyList(),val participantReports:List<JSONObject> = emptyList(),
    val chatPolicies:List<JSONObject> = emptyList(),val chatBlocks:Set<String> = emptySet(),
    val chatJoinInbox:List<JSONObject> = emptyList(),val chatReportInbox:List<JSONObject> = emptyList(),
    val relayReservedBytes:Long = 0,
    val gatewayStatus:String = "No recent Swarm internet gateway is known.",
    val walkieConversation: String? = null, val walkieStatus: String = "OFF", val walkieAvailable: Boolean = false,
    val localWifiAddress: Boolean = false
)

class SaathiViewModel @JvmOverloads constructor(application: Application, storageScope:String=BuildConfig.ENVIRONMENT, startServices:Boolean=true) : AndroidViewModel(application) {
    val repository = Repository(application,storageScope)
    private val mutable = MutableStateFlow(AppState(preferences=preferences(), needsEnabled=repository.needsEnabled))
    private val preferenceVersions=mutableMapOf<String,Long>()
    private val localRefreshes=Channel<Unit>(Channel.CONFLATED)
    val state = mutable.asStateFlow()
    val nearby = NearbyTransport(application, viewModelScope)
    val ble = BleTransport(application, viewModelScope)
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
    val community=CommunityRepository(application,repository,chat,session)
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null
    private var voiceTimeout: Job? = null
    private var healthCheck: Job? = null
    private var reconnectCheck: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var deliveryRetry: Job? = null
    private var backgroundRefresh: Job? = null
    private var lastChatAnnounceAt = 0L
    private var ringTimeout: Job? = null
    private var visibleConversation: String? = null
    private var appInForeground = false
    private var walkieDeadline: Job? = null
    private data class WalkieSignal(val kind: String, val packet: JSONObject, val generation: Long, val connection: String?)
    private val walkieSignals = Channel<WalkieSignal>(32)
    private val walkie = WalkieTalkie(send = { kind, packet ->
        if (!walkieSignals.trySend(WalkieSignal(kind, packet, session.connectionGeneration, session.transport?.session)).isSuccess) walkieFailure()
    }, changed = { snapshot -> applyWalkieSnapshot(snapshot) })
    private fun walkieFailure() { stopWalkie(false); notice("Walkie-talkie connection lost. Reconnect to this person; saved messages remain available.") }
    private fun applyWalkieSnapshot(snapshot: WalkieTalkie.Snapshot) {
        if ((snapshot.conversationId != null || state.value.walkieConversation != null) && wifiDelegate.isInitialized() && wifi.connected) {
            runCatching { wifi.pushToTalk(snapshot.transmitting, snapshot.receiving) }.onFailure {
                wifi.stopMedia(); viewModelScope.launch { walkieFailure() }
            }
        }
        session.callInProgress = snapshot.conversationId != null || state.value.calling
        mutable.update { it.copy(walkieConversation = snapshot.conversationId, walkieStatus = snapshot.status) }
        walkieDeadline?.cancel()
        snapshot.deadline?.let { deadline ->
            walkieDeadline = viewModelScope.launch { delay((deadline - System.nanoTime() / 1_000_000).coerceAtLeast(1)); walkie.expire() }
        }
    }
    fun enterConversation(id: String) { if (visibleConversation != id) stopWalkie(); visibleConversation = id }
    fun leaveConversation(id: String) { if (visibleConversation == id) { visibleConversation = null; stopWalkie() } }
    fun enableWalkie(id: String) = viewModelScope.launch {
        val generation = session.connectionGeneration
        runCatching {
            require(appInForeground && BuildConfig.CHAT_ENABLED && visibleConversation == id && session.transport === wifi && session.confirmed && session.remoteMedia && session.remoteWalkieTalkie)
            require(!state.value.calling && state.value.incomingCall == null && !state.value.recording)
            val conversation = state.value.conversations.first { it.getString("id") == id }
            val peer = chat.peer ?: error("Connect to this person first.")
            val person = ChatProtocol.participant(peer)
            require(conversation.getString("type") == "DIRECT" && conversation.optBoolean("joined") && conversation.getString("peerId") == person && !chat.blocked(person))
            session.pauseTransfersForCall()
            require(appInForeground && visibleConversation == id && session.connectionGeneration == generation && session.confirmed)
            walkie.enable(ChatProtocol.participant(chat.profile()), person, id)
        }.onFailure { stopWalkie(false); notice(it.message ?: "Connect to this person with local Wi-Fi before enabling walkie-talkie.") }
    }
    fun pressWalkie(id: String) {
        if (visibleConversation != id || state.value.walkieConversation != id) return
        runCatching { walkie.press() }.onFailure { notice(it.message ?: "The other person is not ready to listen.") }
    }
    fun releaseWalkie() = walkie.release()
    fun stopWalkie(signal: Boolean = true) = walkie.stop(signal)
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
        // Signing and signaling share one ordered reader. A quick release must follow
        // its request even if a Keystore operation completes slowly.
        viewModelScope.launch {
            for (signal in walkieSignals) {
                if (signal.generation != session.connectionGeneration || !session.confirmed || signal.connection == null) continue
                runCatching {
                    val signed = withContext(Dispatchers.IO) { chat.signLiveControl(signal.kind, signal.connection, signal.packet) }
                    check(signal.generation == session.connectionGeneration && session.confirmed)
                    session.send(signal.kind, signed, priority = 0)
                }.onFailure { if (signal.generation == session.connectionGeneration) walkieFailure() }
            }
        }
        // One reader snapshots encrypted storage off the UI thread. A burst of frame callbacks
        // must not start competing readers or repeat Keystore work inside StateFlow CAS retries.
        viewModelScope.launch(Dispatchers.IO) { for (ignored in localRefreshes) loadLocalSnapshot() }
        session.currentPeerIdentity = { chat.peer?.let { ChatProtocol.participant(it) } }
        session.onPeerIdentityVerified = { identity -> session.retryPendingMessagesFor(identity); if(BuildConfig.CHAT_ENABLED) community.announce() }
        session.onChatConnected = {
            if(BuildConfig.CHAT_ENABLED) { chat.announce(); lastChatAnnounceAt=System.currentTimeMillis(); scheduleNearbyDeliveryRetries(); if(chat.peer!=null)community.announce() }
            else session.send("CHAT_PROFILE", chat.profile())
        }
        session.onChatFrame = { frame, generation ->
            if (BuildConfig.CHAT_ENABLED) chat.receive(frame,generation)
            else if (frame.optString("kind") == "CHAT_PROFILE") chat.verifyTransportPeer(frame.getJSONObject("value")).also { session.onPeerIdentityVerified(it) }
        }
        session.onChatReset = { deliveryRetry?.cancel(); stopWalkie(false); chat.reset() }; chat.onChange = { refreshLocal(); scheduleNearbyDeliveryRetries() }; chat.onInvite = { receiveInvite(it) }
        session.onCommunityFrame={frame,generation->if(BuildConfig.CHAT_ENABLED)community.frame(frame,generation)};community.onChange={refreshLocal()};session.publicFileAllowed={id,hash->community.fileAllowed(id,hash)};session.onPublicFileComplete={community.completeFile(it)}
        session.chatFileAllowed = { id,hash -> chat.fileAllowed(id,hash) }; session.onChatFileComplete = { chat.completeFile(it) }
        chat.onIncoming={batchChatNotice(it)}
        File(application.cacheDir,"voice").apply { mkdirs();listFiles()?.forEach { it.delete() } }
        session.onChange = { refreshLocal() }; session.onError = { notice(it) }
        session.onFile = { offer -> mutable.update { it.copy(fileOffer = offer) } }
        session.onCall = { video -> if (!state.value.calling && state.value.incomingCall == null) { stopWalkie(); mutable.update { it.copy(incomingCall = video) }; startRingTimeout() } }
        session.onWalkieFrame = { kind, packet ->
            val profile = chat.peer
            val person = profile?.let { ChatProtocol.participant(it) }
            if (person != null && !chat.blocked(person) && !state.value.calling && visibleConversation == state.value.walkieConversation) {
                packet.exact("body", "signature"); val body = packet.getJSONObject("body"); body.exact("kind", "connection", "control")
                require(body.getString("kind") == kind && body.getString("connection") == session.transport?.session && Protocol.verify(body, packet.getString("signature"), profile.getJSONObject("body").getJSONObject("publicKey"))) { "Walkie-talkie identity could not be verified." }
                walkie.receive(kind, body.getJSONObject("control"), person)
            }
        }
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
        nearby.onConnectionLost = { advertise, automatic ->
            chat.peer?.let { session.requireSamePeer(ChatProtocol.participant(it)) }
            viewModelScope.launch {
                var waited = 0
                while (state.value.busy && waited < 5000) { delay(100); waited += 100 }
                if (state.value.busy || !preferences().optBoolean("nearbyVisible", true) || !ble.available || session.transport !== nearby) {
                    if (!ble.available) notice("Nearby connection changed. Messages are saved; Bluetooth is unavailable on this phone.")
                    return@launch
                }
                action {
                    activate(ble, preservePeerIdentity = true)
                    notice("Nearby connection changed. Looking for the same person by Bluetooth…")
                    ble.start(advertise = if (automatic) false else advertise, auto = automatic)
                }
            }
        }
        ble.onState = { status ->
            mutable.update { it.copy(nearbyStatus = status, connected = ble.connected) }
            if (ble.connected) action { session.confirm() } else session.reset()
            refreshLocal()
        }
        ble.onPeers = { peers -> mutable.update { it.copy(peers = peers) } }
        ble.onPair = { code -> mutable.update { it.copy(pairCode = code) } }
        ble.onFrame = { session.incoming(it) }; ble.onError = { notice(it) }
        refreshLocal(); if(startServices){refreshInBackground(); foregroundActive()}
    }
    fun notice(text: String?) { mutable.update { it.copy(notice = text) } }
    private fun scheduleNearbyDeliveryRetries() {
        if (!BuildConfig.CHAT_ENABLED || !session.confirmed || chat.peer == null || deliveryRetry?.isActive == true) return
        deliveryRetry = viewModelScope.launch {
            for (waitMillis in longArrayOf(3_000, 7_000, 15_000, 30_000, 60_000, 120_000)) {
                delay(waitMillis)
                if (!session.confirmed || !chat.retryPendingNearbyDelivery()) break
            }
        }
    }
    fun preferences():JSONObject {
        val saved=repository.store.get("preferences","local")?:obj("appearance" to "SYSTEM","relay" to "OFF","mediaRelay" to false,"dailyLimitMB" to 500,"batteryMinimum" to 20,"nearbyVisible" to true)
        if(!saved.has("dailyLimitMB")){
            val previous=saved.optInt("dailyLimitMiB",50)
            saved.put("dailyLimitMB",if(previous<500)500 else previous.coerceAtMost(5000)).remove("dailyLimitMiB")
            repository.store.put("preferences","local",saved)
        }
        return saved
    }
    fun preference(key:String,value:Any){
        require(when(key){"appearance"->value in listOf("SYSTEM","LIGHT","DARK");"relay"->value in listOf("OFF","WIFI","ANY");"mediaRelay","nearbyVisible"->value is Boolean;"dailyLimitMB"->value is Int && value in 500..5000;"batteryMinimum"->value is Int && value in 10..80;else->false})
        val next=JSONObject(state.value.preferences.toString()).put(key,value);mutable.update{it.copy(preferences=next)}
        val version=synchronized(preferenceVersions){((preferenceVersions[key]?:0)+1).also{preferenceVersions[key]=it}}
        viewModelScope.launch(Dispatchers.IO){synchronized(preferenceVersions){if(preferenceVersions[key]==version)synchronized(repository.store){repository.store.put("preferences","local",preferences().put(key,value))}}}
        if(key=="nearbyVisible" && value==false){nearby.disconnect();session.reset();endMedia()}
        if(key in setOf("relay","mediaRelay","dailyLimitMB"))mutable.update{it.copy(gatewayStatus=community.gatewayStatus())}
    }
    fun action(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try { block() } catch (e: Exception) { if (e !is CancellationException) notice(e.message ?: "This action could not finish. Saved work is safe.") }
            finally { mutable.update { it.copy(busy = false, reachable = repository.reachable) }; refreshLocal() }
        }
    }
    fun refreshLocal() {
        localRefreshes.trySend(Unit)
    }
    private fun loadLocalSnapshot() {
            try {
                val store = repository.store
                val requests=repository.requests();val completed=repository.requests(true);val posts=repository.snapshot("feed")
                val savedAt=store.get("public","freshness")?.optString("savedAt");val authenticated=store.get("credentials","session")!=null
                val profile=chat.profile();val contacts=chat.contacts();val conversations=chat.conversations();val chatMessages=chat.messages()
                val peer=if(session.confirmed)chat.peer else null;val channels=if(session.confirmed)chat.discovery else emptyList()
                val actions=store.all("chat-actions");val help=community.helps();val reports=community.reports()
                val policies=chat.policies();val blocks=store.all("chat-blocks").map{it.getString("id")}.toSet()
                val joins=store.all("chat-join-inbox");val reportInbox=store.all("chat-report-inbox")
                val reserved=store.get("relay-usage",java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString())?.optLong("bytes")?:0
                val events=repository.events();val messages=store.all("messages");val files=store.all("attachments");val drafts=store.all("drafts")
                val donations=store.all("donations");val operations=store.all("operations");val preparation=repository.preparation;val account=store.get("account","user")
                val confirmed=session.confirmed;val media=confirmed&&session.remoteMedia&&session.transport?.mediaAvailable==true&&repository.featureFlags?.optBoolean("localCalls")==true
                val localWifiAddress=LocalNetworkAdvice.hasWifiAddress()
                mutable.update { it.copy(requests=requests,completed=completed,posts=posts,savedAt=savedAt,authenticated=authenticated,
                    chatProfile=profile,chatContacts=contacts,conversations=conversations,chatMessages=chatMessages,chatPeer=peer,nearbyChannels=channels,
                    chatActions=actions,localHelp=help,participantReports=reports,chatPolicies=policies,chatBlocks=blocks,chatJoinInbox=joins,chatReportInbox=reportInbox,relayReservedBytes=reserved,
                    events=events,messages=messages,files=files,drafts=drafts,donations=donations,operations=operations,preparation=preparation,account=account,confirmed=confirmed,media=media,walkieAvailable=media&&session.remoteWalkieTalkie,needsEnabled=repository.needsEnabled,localWifiAddress=localWifiAddress,gatewayStatus=community.gatewayStatus()) }
            } catch (_: Exception) { notice("Saved information could not be unlocked. Do not clear app storage if you need to recover work.") }
    }
    private suspend fun refreshRemote() {
        repository.refresh()
        if (repository.preparation != null) loadDashboard()
        if (BuildConfig.CHAT_ENABLED) { runCatching { chat.sync() }; runCatching { community.sync() } }
    }
    private fun refreshInBackground() {
        if (backgroundRefresh?.isActive == true) return
        backgroundRefresh = viewModelScope.launch {
            try { refreshRemote() }
            catch (e: Exception) { if (e !is CancellationException) mutable.update { it.copy(reachable = repository.reachable, needsEnabled = repository.needsEnabled) } }
            finally { mutable.update { it.copy(reachable = repository.reachable, needsEnabled = repository.needsEnabled) }; refreshLocal() }
        }
    }
    fun refresh() = action { backgroundRefresh?.join(); refreshRemote() }
    fun helpRequest(help:JSONObject,id:String?=null,saved:()->Unit={})=chatAction{community.saveHelp(help,id);saved();notice("Help saved on your phone. Sharing nearby when connected.");runCatching{community.sync()}}
    fun offerHelp(id:String)=chatAction{community.offer(id);runCatching{community.sync()}}
    fun checkHelpArea(area:String)=chatAction{require(CommunityProtocol.publicText(area,80)){"Choose an approximate public area without contact details."};repository.store.put("community-meta","area",obj("area" to area));community.sync()}
    fun helpStatus(id:String,status:String,responder:String?=null)=chatAction{community.status(id,status,responder);runCatching{community.sync()}}
    fun participantReport(caption:String,area:String,warning:Boolean,uri:Uri?,video:Boolean,audio:Boolean,saved:()->Unit)=chatAction{val derivative=uri?.let{notice(if(video)"Optimising video…"else if(audio)"Preparing private-metadata-free audio…"else"Preparing a private-metadata-free photo…");FieldMedia.prepare(getApplication(),it,video,audio)};community.report(caption,area,warning,derivative);saved();notice("Report saved. It publishes as soon as it reaches Swarm.");runCatching{community.sync()}.onFailure{notice(it.message)} }
    fun withdrawReport(id:String)=chatAction{community.withdraw(id);runCatching{community.sync()}}
    fun shareReportMedia(id:String)=chatAction{community.announce();community.shareMedia(id)}
    fun flagStatement(id:String,reason:String)=chatAction{community.flag(id,reason);notice("Report saved for review. Offline review waits for a connection.")}
    fun hideHelp(id:String)=action{repository.api("/community/moderation/$id/hide",JSONObject(),true);community.sync()}
    fun aggregateHelp(category:String,area:String,rows:List<JSONObject>,open:(String)->Unit)=action{
        require(repository.preparation!=null){"Prepare a verified relief account before creating an official need."};val id=java.util.UUID.randomUUID().toString();val quantity=rows.sumOf{it.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").getJSONObject("help").getInt("quantity")}
        repository.store.put("drafts",id,obj("id" to id,"draftType" to "request","title" to "${category.replace('_',' ')} near $area".take(100),"description" to "Review ${rows.size} temporary participant requests near $area. Confirm quantity, area and current conditions before publishing.","quantity" to quantity.toString(),"unit" to if(category=="WATER")"bottles"else"items","category" to when(category){"FIRST_AID"->"MEDICAL";"CHARGING"->"POWER";"ACCESSIBILITY"->"OTHER";else->category},"priority" to "NORMAL","hours" to "24","fromHelp" to true));open("draft:"+id)
    }
    fun clearSafeMedia()=action{withContext(Dispatchers.IO){repository.store.all("attachments").filter{f->f.optBoolean("complete") && !f.optBoolean("chatOnly") && repository.store.all("community").none{r->r.getJSONObject("envelope").getJSONObject("body").getJSONObject("payload").optJSONObject("media")?.optString("id")==f.getString("id") && !r.optBoolean("mediaOnline")}}.forEach{session.removeFile(it.getString("id"))};getApplication<Application>().cacheDir.resolve("field-processing").listFiles()?.forEach{it.delete()}};notice("Reviewed public media caches cleared. Pending media and private chat files were kept.")}
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
    fun createChannel(name:String,visibility:String,mode:String="DISCUSSION",admission:String=if(visibility=="OPEN")"OPEN" else "INVITE_PLUS_APPROVAL",open:(String)->Unit)=chatAction { open(chat.create(name,visibility,mode,admission));runCatching {chat.sync()} }
    fun joinChannel(id:String)=chatAction { chat.join(id);runCatching {chat.sync()} }
    fun joinInvite(link:String,open:(String)->Unit)=chatAction { open(chat.acceptInvite(link));runCatching {chat.sync()} }
    fun invitePerson(id:String,person:JSONObject,show:(String)->Unit)=chatAction { show(chat.invite(id,person));runCatching {chat.sync()} }
    fun createChannelJoinLink(id:String,show:(String)->Unit)=chatAction { show(chat.createJoinLink(id)) }
    fun chatSend(id:String,text:String,mentions:List<String> = emptyList(),threadRootId:String?=null,saved:()->Unit={})=chatAction { val payload=obj("text" to text);if(mentions.isNotEmpty())payload.put("mentions",org.json.JSONArray(mentions));chat.send(id,payload,threadRootId=threadRootId);saved();runCatching {chat.sync()} }
    fun moderateChannel(id:String,action:String,target:String,role:String?=null,reaction:String?=null)=chatAction {chat.moderate(id,action,target,role,reaction);runCatching{chat.sync()};notice("Channel action saved. Check its confirmation in channel settings.")}
    fun configureChannel(id:String,mode:String,admission:String)=chatAction {chat.configure(id,mode,admission);runCatching{chat.sync()}}
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
    fun blockChat(id:String)=chatAction { stopWalkie(); chat.block(id);runCatching {chat.sync()} }
    fun leaveChat(id:String)=chatAction { chat.membership(id,null);runCatching {chat.sync()} }
    fun removeChatMember(id:String,person:String)=chatAction { chat.membership(id,person);runCatching {chat.sync()} }
    fun deleteChannel(id:String)=chatAction { chat.membership(id,null,true);runCatching {chat.sync()} }
    fun reportChat(id:String,reason:String="ABUSE")=chatAction { chat.report(id,reason);runCatching {chat.sync()};notice("Report saved. It will reach the team when connected.") }
    fun reportPerson(id:String,reason:String)=chatAction{chat.reportPerson(id,reason);runCatching{chat.sync()};notice("Report saved for review. Sending waits for an online connection.")}
    fun syncChats()=chatAction { chat.sync();notice("Chats checked. Recipient confirmations determine delivery.") }
    fun attachChat(id:String,uri:Uri,threadRootId:String?=null)=chatAction { chat.attach(id,uri,threadRootId=threadRootId);runCatching {chat.sync()} }
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
        require(state.value.walkieConversation == null && !state.value.calling) { "Turn off walkie-talkie before recording a voice note." }
        cancelVoice();val file=File(getApplication<Application>().cacheDir,"voice/note.m4a");voiceFile=file
        val recorder=if(Build.VERSION.SDK_INT>=31)MediaRecorder(getApplication()) else MediaRecorder()
        this.recorder=recorder
        try { recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioEncodingBitRate(64000);recorder.setAudioSamplingRate(16000);recorder.setMaxDuration(120000);recorder.setOutputFile(file.path);recorder.prepare();recorder.start();mutable.update {it.copy(recording=true)};voiceTimeout=viewModelScope.launch {delay(120000);cancelVoice();notice("Voice note reached its two-minute limit and was discarded. Record a shorter note.")} }catch(e:Exception){cancelVoice();throw e}
    }
    fun sendVoice(id:String,threadRootId:String?=null)=chatAction {
        val file=voiceFile?:error("Record a voice note first.");voiceTimeout?.cancel();recorder?.stop();recorder?.release();recorder=null;mutable.update {it.copy(recording=false)}
        try {chat.attach(id,Uri.fromFile(file),"audio/mp4","Voice note.m4a",threadRootId);runCatching {chat.sync()} }finally { file.delete();voiceFile=null }
    }
    fun cancelVoice() { voiceTimeout?.cancel();runCatching {recorder?.stop()};runCatching {recorder?.release()};recorder=null;voiceFile?.delete();voiceFile=null;mutable.update {it.copy(recording=false)} }
    suspend fun loadDashboard() { mutable.update { it.copy(dashboard = JSONObject(repository.api("/volunteer/dashboard", authenticated = true))) } }
    fun login(email: String, password: String, totp: String) = action { repository.login(email, password, totp); loadDashboard(); notice("This phone is ready for offline publishing.") }
    fun saveDraft(id: String, draft: JSONObject) { viewModelScope.launch(Dispatchers.IO) { repository.store.put("drafts", id, JSONObject(draft.toString()).put("id", id)); refreshLocal() } }
    fun publish(id: String, draft: JSONObject, type: String, payload: JSONObject, organization: String, done: () -> Unit) = action {
        withContext(Dispatchers.IO) { repository.store.put("drafts", id, draft) }
        val event = repository.author(type, payload, organization)
        draft.optJSONArray("mediaUris")?.takeIf { it.length() > 0 }?.let { event.put("mediaUris", it); repository.store.put("events", event.getString("id"), event) }
        repository.store.remove("drafts", id); notice("Saved on this phone. Synchronize or share nearby to send it."); done()
    }
    fun sync(carried: Boolean) = action { repository.sync(carried); notice("Saved updates checked with Swarm."); if (repository.reachable) repository.refresh() }
    fun logout(revoke: Boolean) = action { disconnect(); repository.logout(revoke); session.clearFiles(); mutable.update { it.copy(dashboard = null) }; notice("Signed out. Private saved work and this phone’s signing identity were cleared.") }
    fun listDevices() = action { mutable.update { it.copy(devices = org.json.JSONArray(repository.api("/sync/devices", authenticated = true)).objects()) } }
    fun revokeDevice(id: String) = action { repository.api("/sync/devices/$id/revoke", obj(), true); mutable.update { it.copy(devices = org.json.JSONArray(repository.api("/sync/devices", authenticated = true)).objects()) }; notice("Phone revoked by Swarm. Unsent events signed by that phone may be rejected.") }
    private fun activate(transport: PeerTransport, preservePeerIdentity: Boolean = false) { if (!preservePeerIdentity) session.clearPeerRequirement(); session.transport?.disconnect(); session.reset(); session.transport = transport; mutable.update { it.copy(invitation = "", localCode = "", pairCode = null, peers = emptyMap(), media = false, connected = false, callActive = false, calling = false) } }
    fun scan(advertise: Boolean,automatic:Boolean=false) = action {
        require(preferences().optBoolean("nearbyVisible",true)){"Enable Nearby visibility in More before searching."}
        if (repository.featureFlags?.optBoolean("nearby") != true) {
            runCatching { repository.refreshConfiguration(); repository.api("/public/config") }
        }
        require(repository.featureFlags?.optBoolean("nearby") == true) {
            if (repository.configuration == null)
                "Connect to the internet once to verify Nearby services. Bluetooth pairing remains available."
            else
                "Nearby discovery is not enabled in the verified service configuration. Local pairing remains available when configured."
        }
        activate(nearby); nearby.scan(advertise,automatic,chat.profile().getJSONObject("body").getString("name"))
    }
    fun scanBle(advertise: Boolean) = action { require(preferences().optBoolean("nearbyVisible",true)){"Enable Nearby visibility in More before searching."}; activate(ble); ble.start(advertise) }
    fun connect(id: String) = action { if (session.transport === ble) ble.connect(id) else nearby.connect(id) }
    fun confirmPair(match: Boolean) { if (session.transport === ble) ble.confirm(match) else nearby.confirm(match) }
    fun offer() = action { activate(wifi); mutable.update { it.copy(invitation = wifi.offer()) } }
    fun accept(text: String) = action { if (session.transport !== wifi) activate(wifi); val reply = wifi.accept(text); mutable.update { it.copy(invitation = reply) } }
    fun confirmLocal() = action { session.confirm(); mutable.update { it.copy(localCode = "") } }
    fun disconnect() { session.clearPeerRequirement(); session.transport?.disconnect(); session.reset(); endMedia(); mutable.update { it.copy(connected = false, confirmed = false, media = false, invitation = "", localCode = "") } }
    fun sendMessage(text: String) = action { session.message(text) }
    fun share() = action { session.shareEvents(); notice("Sharing eligible signed updates. They remain pending until Swarm confirms them.") }
    fun offerFile(uri: Uri) = action { session.offerFile(uri) }
    fun acceptFile() = action { state.value.fileOffer?.let { session.acceptFile(it) }; mutable.update { it.copy(fileOffer = null) } }
    fun declineFile() = action { state.value.fileOffer?.let { session.send("FILE_CANCEL", obj("id" to it.getString("id"))) }; mutable.update { it.copy(fileOffer = null) } }
    fun call(video: Boolean, incoming: Boolean) = action {
        stopWalkie()
        require(state.value.media && (!state.value.calling || incoming))
        session.pauseTransfersForCall(); ringTimeout?.cancel()
        wifi.capture(video); mutable.update { it.copy(calling = true, callActive = incoming, video = video, incomingCall = null, quality = "") }
        if (incoming) session.send("CALL_ACCEPT", obj()) else { session.send("CALL", obj("video" to video)); startRingTimeout() }
    }
    fun hangup() = action { session.send("CALL_END", obj()); endMedia() }
    private fun startRingTimeout() { ringTimeout?.cancel(); ringTimeout = viewModelScope.launch { delay(60000); runCatching { session.send("CALL_END", obj()) }; endMedia(); notice("Call was not answered. Nearby messages still work.") } }
    private fun endMedia() { ringTimeout?.cancel(); session.callInProgress = false; if (wifiDelegate.isInitialized()) wifi.stopMedia(); mutable.update { it.copy(calling = false, callActive = false, incomingCall = null, video = false, quality = "") } }
    fun foregroundActive() {
        appInForeground = true
        refreshLocal()
        scheduleNearbyDeliveryRetries()
        if (networkCallback == null && Build.VERSION.SDK_INT >= 24) {
            val manager = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = checkRemoteFeatures()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) checkRemoteFeatures()
                }
            }
            runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
        }
        healthCheck?.cancel()
        healthCheck = viewModelScope.launch { while (isActive) { delay(30000); refreshConnectionState() } }
    }
    private fun checkRemoteFeatures() {
        if (reconnectCheck?.isActive == true) return
        reconnectCheck = viewModelScope.launch { refreshConnectionState() }
    }
    private suspend fun refreshConnectionState() {
        repository.checkReachability()
        if (BuildConfig.CHAT_ENABLED) {
            if (repository.reachable) { runCatching { chat.sync(); chat.autoMedia() }; runCatching { community.sync() } }
            if (session.confirmed) {
                val now = System.currentTimeMillis()
                if (now - lastChatAnnounceAt >= 120_000) { runCatching { chat.announce() }; lastChatAnnounceAt = now }
                runCatching { community.announce() }
            }
        }
        mutable.update { it.copy(reachable = repository.reachable, needsEnabled = repository.needsEnabled) }
    }
    fun foregroundLost() {
        appInForeground = false
        attachmentSync?.cancel();healthCheck?.cancel();reconnectCheck?.cancel();deliveryRetry?.cancel()
        networkCallback?.let { callback -> runCatching { getApplication<Application>().getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) } }
        networkCallback = null
        nearby.stopScan();ble.stopScan();cancelVoice();stopWalkie(); if (state.value.calling) hangup()
    }
    override fun onCleared() { disconnect(); if (wifiDelegate.isInitialized()) wifi.release(); repository.store.close() }
}
