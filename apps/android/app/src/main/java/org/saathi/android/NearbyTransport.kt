package org.saathi.android

import android.content.Context
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothManager
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

interface PeerTransport {
    val mediaAvailable: Boolean
    val connected: Boolean
    val session: String
    val maximumFrameBytes: Int get() = 24000
    val supportsFiles: Boolean get() = true
    val capabilities: Set<TransportCapability> get() = TransportCapabilities.of(mediaAvailable, supportsFiles)
    suspend fun send(frame: JSONObject)
    fun disconnect()
}

internal fun nearbyEndpointName(displayName: String): String = displayName
    .filterNot { Character.isISOControl(it) }
    .trim()
    .take(32)
    .ifBlank { "Swarm " + UUID.randomUUID().toString().take(4).uppercase() }

class NearbyTransport(context: Context, private val scope: CoroutineScope) : PeerTransport {
    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)
    private val lifecycleMutex = Mutex()
    @Volatile private var generation = 0L
    @Volatile private var cleanupJob: Job? = null
    private var lifecycle = Lifecycle.IDLE
    private var accepting = false
    private var activeAdvertise = false
    private var activeAutomatic = false
    val available = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
    override val mediaAvailable = false
    override var connected = false; private set
    override var session = UUID.randomUUID().toString(); private set
    private var endpoint: String? = null
    private var pending: String? = null
    private var window: Job? = null
    private var lastScan = 0L
    private var temporaryName = nearbyEndpointName("")
    var onState: (String) -> Unit = {}
    var onPeers: (Map<String, String>) -> Unit = {}
    var onPair: (String?) -> Unit = {}
    var onFrame: (JSONObject) -> Unit = {}
    var onError: (String) -> Unit = {}
    var onConnectionLost: ((advertise: Boolean, automatic: Boolean) -> Unit)? = null
    /** When this link last carried a frame either way; a quiet link has exchanged everything. */
    @Volatile var lastActivity = 0L; private set
    /** The connected (or last connected) phone's advertised name. */
    var peerName = ""; private set
    private val peers = linkedMapOf<String, String>()
    private val service = "org.saathi.nearby.v1.${BuildConfig.ENVIRONMENT}"
    /** Searching, pairing or connected: an automatic search must not replace any of these. */
    val active get() = lifecycle != Lifecycle.IDLE && lifecycle != Lifecycle.STOPPING
    private enum class Lifecycle { IDLE, STARTING, ADVERTISING, DISCOVERING, CONNECTING, PAIRING, CONNECTED, STOPPING }
    private fun log(event: String, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        val wifi = runCatching { appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled }.getOrNull()
        val bluetooth = runCatching { appContext.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled }.getOrNull()
        Log.d("SwarmNearby", "$event state=$lifecycle generation=$generation wifi=$wifi bluetooth=$bluetooth $detail")
    }
    private fun endpointTag(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    private fun statusCode(error: Throwable): Int? = generateSequence(error) { it.cause }.filterIsInstance<ApiException>().firstOrNull()?.statusCode
    private fun isCurrent(token: Long) = token == generation
    private fun setLifecycle(next: Lifecycle, detail: String = "") { lifecycle = next; log("state", "name=$next $detail") }

    private fun payloads(token: Long) = object : PayloadCallback() {
        override fun onPayloadReceived(from: String, payload: Payload) {
            if (!isCurrent(token) || !connected || from != endpoint || payload.type != Payload.Type.BYTES) return
            val raw = payload.asBytes() ?: return
            lastActivity = System.currentTimeMillis()
            if (raw.size > maximumFrameBytes) { onError("A nearby message exceeded the safe size limit."); return }
            runCatching { onFrame(JSONObject(String(raw, Charsets.UTF_8))) }.onFailure { onError("A nearby message could not be read.") }
        }
        override fun onPayloadTransferUpdate(from: String, update: PayloadTransferUpdate) { if (isCurrent(token)) log("payload-transfer", "peer=${endpointTag(from)} status=${update.status}") }
    }

    private fun lifecycleCallback(token: Long) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            if (!isCurrent(token)) return
            if (pending != null || connected || lifecycle == Lifecycle.PAIRING) { log("connection-rejected-busy", "peer=${endpointTag(id)}"); return }
            pending = id; accepting = false; setLifecycle(Lifecycle.PAIRING, "peer=${endpointTag(id)}")
            // Swarm is internal: every Swarm phone connects without a code. Its signed identity is checked when it arrives.
            peerName = info.endpointName.take(40); onState("Connecting to ${peerName.take(32)}…"); confirm(true)
            scope.launch { delay(60000); if (isCurrent(token) && pending == id && !accepting) confirm(false) }
        }
        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            if (!isCurrent(token) || id != pending) return
            log("connection-result", "peer=${endpointTag(id)} status=${result.status.statusCode} success=${result.status.isSuccess}")
            onPair(null); pending = null; accepting = false
            if (result.status.isSuccess) {
                endpoint = id; connected = true; lastActivity = System.currentTimeMillis(); setLifecycle(Lifecycle.CONNECTED); stopScan(); onState("Connected nearby")
            } else { connected = false; endpoint = null; setLifecycle(if (activeAdvertise || activeAutomatic) Lifecycle.ADVERTISING else Lifecycle.DISCOVERING); onState("Pairing was declined or interrupted. Choose the person again when ready.") }
        }
        override fun onDisconnected(id: String) {
            if (!isCurrent(token)) return
            log("endpoint-disconnected", "peer=${endpointTag(id)}")
            if (pending == id) { pending = null; accepting = false; onPair(null) }
            if (endpoint == id) {
                val wasAdvertising = activeAdvertise
                val wasAutomatic = activeAutomatic
                endpoint = null; connected = false; setLifecycle(Lifecycle.IDLE); session = UUID.randomUUID().toString()
                onConnectionLost?.invoke(wasAdvertising, wasAutomatic)
                onState("Nearby connection lost. Your saved work is safe. Reconnect when ready.")
            }
        }
    }
    suspend fun scan(advertise: Boolean, automatic:Boolean=false, displayName:String="") {
        require(available) { "Use local Wi-Fi pairing on this phone." }
        while (true) {
            val cleanup = cleanupJob
            cleanup?.join()
            var started = false
            lifecycleMutex.withLock {
                if (cleanupJob !== cleanup) return@withLock
                val token = ++generation
                require(System.currentTimeMillis() - lastScan > 10000) { "Wait a moment before searching again to save battery." }
                lastScan = System.currentTimeMillis(); peers.clear(); onPeers(emptyMap()); onPair(null)
                activeAdvertise = advertise; activeAutomatic = automatic; accepting = false
                temporaryName = nearbyEndpointName(displayName)
                stopOperations("replace-session")
                var attempt = 0
                while (true) {
                    if (!isCurrent(token)) return@withLock
                    try {
                        setLifecycle(Lifecycle.STARTING)
                        if (advertise || automatic) {
                            client.startAdvertising(temporaryName, service, lifecycleCallback(token), AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).setConnectionType(ConnectionType.BALANCED).build()).await()
                            if (!isCurrent(token)) return@withLock
                            setLifecycle(Lifecycle.ADVERTISING, "name=$temporaryName")
                            log("advertising-started")
                        }
                        if (!advertise || automatic) {
                            client.startDiscovery(service, discoveryCallback(token), DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()).await()
                            if (!isCurrent(token)) return@withLock
                            setLifecycle(Lifecycle.DISCOVERING)
                            log("discovery-started")
                        }
                        // No time limit: the view model stops searching when Swarm leaves the foreground.
                        onState(if (automatic) "Visible and looking nearby while Swarm is open" else if (advertise) "Visible nearby as $temporaryName while Swarm is open" else "Looking for nearby Swarm while Swarm is open")
                        window?.cancel()
                        started = true
                        return@withLock
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        log("start-failed", "status=${statusCode(error)} type=${error.javaClass.simpleName}")
                        stopOperations("start-recovery")
                        if (!isCurrent(token)) return@withLock
                        if (statusCode(error) != ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL || attempt++ >= 1) {
                            setLifecycle(Lifecycle.IDLE)
                            onError("Nearby could not start. Your saved work is safe; try again in a moment.")
                            throw error
                        }
                        log("out-of-order-recovery", "attempt=$attempt")
                        delay(500)
                    }
                }
            }
            if (started) return
        }
    }
    private fun discoveryCallback(token: Long) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            if (!isCurrent(token) || connected) return
            if (peers.size < 20) { peers[id] = info.endpointName.take(40); log("endpoint-found", "peer=${endpointTag(id)}"); onPeers(peers.toMap()) }
        }
        override fun onEndpointLost(id: String) {
            if (!isCurrent(token)) return
            peers.remove(id); log("endpoint-lost", "peer=${endpointTag(id)}"); onPeers(peers.toMap())
        }
    }

    suspend fun connect(id: String) = lifecycleMutex.withLock {
        require(peers.containsKey(id)) { "This nearby device is no longer available. Search again." }
        require(lifecycle == Lifecycle.DISCOVERING || lifecycle == Lifecycle.ADVERTISING) { "Start nearby discovery before connecting." }
        require(pending == null && endpoint == null && lifecycle != Lifecycle.CONNECTING) { "A nearby connection is already in progress." }
        val token = generation
        setLifecycle(Lifecycle.CONNECTING)
        log("request-connection", "peer=${endpointTag(id)}")
        try {
            client.requestConnection(temporaryName, id, lifecycleCallback(token)).await()
            // An unanswered request would block every later attempt; return to searching after 30 seconds.
            scope.launch { delay(30000); if (isCurrent(token) && lifecycle == Lifecycle.CONNECTING && pending == null) setLifecycle(if (activeAdvertise || activeAutomatic) Lifecycle.ADVERTISING else Lifecycle.DISCOVERING, "reason=request-timeout") }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            log("request-connection-failed", "peer=${endpointTag(id)} status=${statusCode(error)}")
            if (isCurrent(token) && statusCode(error) == ConnectionsStatusCodes.STATUS_OUT_OF_ORDER_API_CALL) {
                val advertise = activeAdvertise
                val automatic = activeAutomatic
                generation++
                stopOperations("request-recovery")
                // Re-establish discovery once. The peer list is refreshed and the user can choose again.
                lastScan = 0L
                onError("Nearby was safely reset after an out-of-order radio call. Choose the person again.")
                scope.launch {
                    delay(500)
                    runCatching { scan(advertise, automatic) }
                        .onFailure { if (it !is CancellationException) onError("Nearby recovery did not finish. Search again when ready.") }
                }
            } else {
                if (isCurrent(token)) setLifecycle(Lifecycle.DISCOVERING)
                throw error
            }
        }
    }

    fun confirm(match: Boolean) {
        val id = pending ?: return
        if (accepting || lifecycle != Lifecycle.PAIRING) return
        accepting = true
        val token = generation
        onPair(null)
        log(if (match) "pair-accepted" else "pair-rejected", "peer=${endpointTag(id)}")
        val task = if (match) client.acceptConnection(id, payloads(token)) else client.rejectConnection(id)
        task.addOnFailureListener { error ->
            if (!isCurrent(token)) return@addOnFailureListener
            log("pair-decision-failed", "peer=${endpointTag(id)} status=${statusCode(error)}")
            if (pending == id) { pending = null; accepting = false }
            setLifecycle(Lifecycle.IDLE)
            onError("The nearby connection could not be accepted. Your saved work is safe.")
        }
    }

    private suspend fun stopOperations(reason: String) {
        window?.cancel(); window = null
        setLifecycle(Lifecycle.STOPPING, "reason=$reason")
        log("stop-discovery", "reason=$reason"); client.stopDiscovery()
        log("stop-advertising", "reason=$reason"); client.stopAdvertising()
        log("stop-all-endpoints", "reason=$reason"); client.stopAllEndpoints()
        connected = false; endpoint = null; pending = null; accepting = false
        delay(350)
    }

    fun stopScan() {
        window?.cancel(); window = null; log("stop-scan")
        val previous = cleanupJob
        cleanupJob = scope.launch {
            previous?.join()
            lifecycleMutex.withLock {
                runCatching { client.stopDiscovery() }
                runCatching { client.stopAdvertising() }
            }
        }
    }

    override suspend fun send(frame: JSONObject) {
        require(connected) { "Connect nearby first." }
        val target = endpoint ?: error("Nearby connection ended. Your saved work is safe.")
        val raw = frame.toString().toByteArray(Charsets.UTF_8)
        require(raw.size <= maximumFrameBytes)
        log("payload-send", "peer=${endpointTag(target)} bytes=${raw.size}")
        lastActivity = System.currentTimeMillis()
        client.sendPayload(target, Payload.fromBytes(raw)).await()
    }

    override fun disconnect() {
        generation++
        window?.cancel(); window = null
        connected = false; endpoint = null; pending = null; accepting = false; peers.clear(); onPeers(emptyMap()); onPair(null)
        session = UUID.randomUUID().toString(); setLifecycle(Lifecycle.STOPPING, "reason=disconnect"); onState("Ready to connect nearby")
        val previous = cleanupJob
        cleanupJob = scope.launch {
            previous?.join()
            lifecycleMutex.withLock {
                stopOperations("disconnect")
                setLifecycle(Lifecycle.IDLE)
            }
        }
    }
}
