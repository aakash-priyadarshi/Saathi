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

data class AppState(
    val requests: List<JSONObject> = emptyList(), val completed: List<JSONObject> = emptyList(), val posts: List<JSONObject> = emptyList(),
    val events: List<JSONObject> = emptyList(), val messages: List<JSONObject> = emptyList(), val files: List<JSONObject> = emptyList(), val drafts: List<JSONObject> = emptyList(), val donations: List<JSONObject> = emptyList(), val operations: List<JSONObject> = emptyList(),
    val preparation: JSONObject? = null, val account: JSONObject? = null, val dashboard: JSONObject? = null, val devices: List<JSONObject> = emptyList(), val savedAt: String? = null,
    val reachable: Boolean = false, val authenticated: Boolean = false, val busy: Boolean = false, val notice: String? = null,
    val nearbyStatus: String = "Ready to connect nearby", val connected: Boolean = false, val confirmed: Boolean = false, val media: Boolean = false,
    val peers: Map<String, String> = emptyMap(), val pairCode: String? = null, val localCode: String = "", val invitation: String = "",
    val fileOffer: JSONObject? = null, val incomingCall: Boolean? = null, val calling: Boolean = false, val callActive: Boolean = false,
    val video: Boolean = false, val remoteVideo: VideoTrack? = null, val quality: String = ""
)

class SaathiViewModel(application: Application) : AndroidViewModel(application) {
    val repository = Repository(application)
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
    private var healthCheck: Job? = null
    private var ringTimeout: Job? = null
    init {
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
        refreshLocal(); refresh(); foregroundActive()
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
                mutable.update { it.copy(requests = repository.requests(), completed = repository.requests(true), posts = repository.snapshot("feed"), events = repository.events(), messages = store.all("messages"), files = store.all("attachments"), drafts = store.all("drafts"), donations = store.all("donations"), operations = store.all("operations"), preparation = repository.preparation, account = store.get("account", "user"), savedAt = store.get("public", "freshness")?.optString("savedAt"), confirmed = session.confirmed, media = session.confirmed && session.remoteMedia && session.transport?.mediaAvailable == true && repository.featureFlags?.optBoolean("localCalls") == true) }
            } catch (_: Exception) { notice("Saved information could not be unlocked. Do not clear app storage if you need to recover work.") }
        }
    }
    fun refresh() = action { repository.refresh(); if (repository.preparation != null) loadDashboard() }
    suspend fun loadDashboard() { mutable.update { it.copy(dashboard = JSONObject(repository.api("/volunteer/dashboard", authenticated = true))) } }
    fun login(email: String, password: String, totp: String) = action { repository.login(email, password, totp); loadDashboard(); notice("This phone is ready for offline publishing.") }
    fun saveDraft(id: String, draft: JSONObject) { viewModelScope.launch(Dispatchers.IO) { repository.store.put("drafts", id, JSONObject(draft.toString()).put("id", id)); refreshLocal() } }
    fun publish(id: String, draft: JSONObject, type: String, payload: JSONObject, organization: String, done: () -> Unit) = action {
        withContext(Dispatchers.IO) { repository.store.put("drafts", id, draft) }
        repository.author(type, payload, organization); repository.store.remove("drafts", id); notice("Saved on this phone. Synchronize or share nearby to send it."); done()
    }
    fun sync(carried: Boolean) = action { repository.sync(carried); notice("Saved updates checked with Saathi."); if (repository.reachable) repository.refresh() }
    fun logout(revoke: Boolean) = action { disconnect(); repository.logout(revoke); session.clearFiles(); mutable.update { it.copy(dashboard = null) }; notice("Signed out. Private saved work and this phone’s signing identity were cleared.") }
    fun listDevices() = action { mutable.update { it.copy(devices = org.json.JSONArray(repository.api("/sync/devices", authenticated = true)).objects()) } }
    fun revokeDevice(id: String) = action { repository.api("/sync/devices/$id/revoke", obj(), true); mutable.update { it.copy(devices = org.json.JSONArray(repository.api("/sync/devices", authenticated = true)).objects()) }; notice("Phone revoked by Saathi. Unsent events signed by that phone may be rejected.") }
    private fun activate(transport: PeerTransport) { session.transport?.disconnect(); session.reset(); session.transport = transport; mutable.update { it.copy(invitation = "", localCode = "", media = false, connected = false, callActive = false, calling = false) } }
    fun scan(advertise: Boolean) = action { require(repository.featureFlags?.optBoolean("nearby") == true) { "Nearby discovery is not enabled for this environment. Local pairing remains available when configured." }; activate(nearby); nearby.scan(advertise) }
    fun connect(id: String) = action { nearby.connect(id) }
    fun offer() = action { activate(wifi); mutable.update { it.copy(invitation = wifi.offer()) } }
    fun accept(text: String) = action { if (session.transport !== wifi) activate(wifi); val reply = wifi.accept(text); mutable.update { it.copy(invitation = reply) } }
    fun confirmLocal() = action { session.confirm(); mutable.update { it.copy(localCode = "") } }
    fun disconnect() { session.transport?.disconnect(); session.reset(); endMedia(); mutable.update { it.copy(connected = false, confirmed = false, media = false, invitation = "", localCode = "") } }
    fun sendMessage(text: String) = action { session.message(text) }
    fun share() = action { session.shareEvents(); notice("Sharing eligible signed updates. They remain pending until Saathi confirms them.") }
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
        healthCheck = viewModelScope.launch { while (isActive) { delay(30000); repository.checkReachability(); mutable.update { it.copy(reachable = repository.reachable) } } }
    }
    fun foregroundLost() { healthCheck?.cancel(); nearby.stopScan(); if (state.value.calling) hangup() }
    override fun onCleared() { disconnect(); if (wifiDelegate.isInitialized()) wifi.release(); repository.store.close() }
}
