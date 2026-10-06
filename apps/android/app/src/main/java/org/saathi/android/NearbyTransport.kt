package org.saathi.android

import android.content.Context
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

interface PeerTransport {
    val mediaAvailable: Boolean
    val connected: Boolean
    val session: String
    suspend fun send(frame: JSONObject)
    fun disconnect()
}

class NearbyTransport(context: Context, private val scope: CoroutineScope) : PeerTransport {
    private val client = Nearby.getConnectionsClient(context)
    val available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    override val mediaAvailable = false
    override var connected = false; private set
    override var session = UUID.randomUUID().toString(); private set
    private var endpoint: String? = null
    private var pending: String? = null
    private var window: Job? = null
    private var lastScan = 0L
    private var temporaryName = "Swarm " + UUID.randomUUID().toString().take(4).uppercase()
    var onState: (String) -> Unit = {}
    var onPeers: (Map<String, String>) -> Unit = {}
    var onPair: (String?) -> Unit = {}
    var onFrame: (JSONObject) -> Unit = {}
    var onError: (String) -> Unit = {}
    private val peers = linkedMapOf<String, String>()
    private val service = "org.saathi.nearby.v1.${BuildConfig.ENVIRONMENT}"
    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(from: String, payload: Payload) {
            if (!connected || from != endpoint || payload.type != Payload.Type.BYTES) return
            val raw = payload.asBytes() ?: return
            if (raw.size > 24000) return
            runCatching { onFrame(JSONObject(String(raw))) }.onFailure { onError("A nearby message could not be read.") }
        }
        override fun onPayloadTransferUpdate(from: String, update: PayloadTransferUpdate) {}
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            if (pending != null || connected) { client.rejectConnection(id); return }
            pending = id; onPair(info.authenticationDigits); onState("Compare the code on both devices")
            scope.launch { delay(60000); if (pending == id) confirm(false) }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (id != pending) return
            onPair(null); pending = null
            if (result.status.isSuccess) {
                endpoint = id; connected = true; stopScan(); onState("Connected nearby")
            } else { connected = false; endpoint = null; onState("Pairing was declined. Try again when you are ready.") }
        }
        override fun onDisconnected(id: String) {
            if (pending == id) { pending = null; onPair(null) }
            if (endpoint == id) { endpoint = null; connected = false; onState("Nearby connection lost. Saved work is safe. Move closer or reconnect.") }
        }
    }
    suspend fun scan(advertise: Boolean, automatic:Boolean=false) {
        require(available) { "Use local Wi-Fi pairing on this phone." }
        require(System.currentTimeMillis() - lastScan > 10000) { "Wait a moment before searching again to save battery." }
        disconnect(); lastScan = System.currentTimeMillis(); peers.clear(); onPeers(peers.toMap()); temporaryName = "Swarm " + UUID.randomUUID().toString().take(4).uppercase()
        if (advertise || automatic) client.startAdvertising(temporaryName, service, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).setConnectionType(ConnectionType.BALANCED).build()).await()
        if (!advertise || automatic) client.startDiscovery(service, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) { if (peers.size < 20) { peers[id] = info.endpointName.take(40); onPeers(peers.toMap()) } }
            override fun onEndpointLost(id: String) { peers.remove(id); onPeers(peers.toMap()) }
        }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()).await()
        onState(if (automatic) "Visible and looking nearby for one minute" else if (advertise) "Visible nearby as $temporaryName for one minute" else "Looking for nearby Swarm for one minute")
        window = scope.launch { delay(60000); stopScan(); if (!connected && pending == null) onState("Search finished. Search again when another person is ready.") }
    }
    suspend fun connect(id: String) { require(peers.containsKey(id)); client.requestConnection(temporaryName, id, lifecycle).await() }
    fun confirm(match: Boolean) { val id = pending ?: return; if (match) client.acceptConnection(id, payloads).addOnFailureListener { onError("Could not accept this connection.") } else { client.rejectConnection(id); pending = null; onPair(null) } }
    fun stopScan() { window?.cancel(); client.stopDiscovery(); client.stopAdvertising() }
    override suspend fun send(frame: JSONObject) { require(connected); val raw = frame.toString().toByteArray(); require(raw.size <= 24000); client.sendPayload(endpoint!!, Payload.fromBytes(raw)).await() }
    override fun disconnect() { stopScan(); client.stopAllEndpoints(); connected = false; endpoint = null; pending = null; onPair(null); session = UUID.randomUUID().toString(); onState("Ready to connect nearby") }
}
