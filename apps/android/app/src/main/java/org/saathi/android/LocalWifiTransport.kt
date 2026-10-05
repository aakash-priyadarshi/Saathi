package org.saathi.android

import android.content.Context
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Manual signaling uses the browser's bounded SAATHI1 format, with no internet signaling servers. */
class LocalWifiTransport(private val context: Context, private val scope: CoroutineScope) : PeerTransport {
    override val mediaAvailable = true
    override var connected = false; private set
    override var session = UUID.randomUUID().toString(); private set
    val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var quality: Job? = null
    private var pairingDeadline: Job? = null
    private var weakReadings = 0
    private var generation = 0L
    var onState: (String) -> Unit = {}
    var onCode: (String) -> Unit = {}
    var onFrame: (JSONObject) -> Unit = {}
    var onVideo: (VideoTrack?) -> Unit = {}
    var onQuality: (String) -> Unit = {}
    init {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        factory = PeerConnectionFactory.builder().setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)).setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext)).createPeerConnectionFactory()
    }
    private fun setup(): PeerConnection {
        disconnect()
        val currentGeneration = generation
        val config = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN; bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE }
        val peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) { scope.launch { if (currentGeneration == generation) checkOpen() } }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                scope.launch { if (currentGeneration == generation && state in listOf(PeerConnection.IceConnectionState.DISCONNECTED, PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED)) { connected = false; stopMedia(); onState("Nearby connection lost. Saved work is safe. Move closer or reconnect.") } }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(data: DataChannel) { scope.launch { if (currentGeneration == generation) bind(data) } }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) { scope.launch { if (currentGeneration == generation) { receiver.track()?.setEnabled(false); (receiver.track() as? VideoTrack)?.let { onVideo(it) } } } }
        }) ?: error("Local pairing is unavailable on this phone.")
        pc = peer
        pairingDeadline = scope.launch { delay(120000); if (!connected) { disconnect(); onState("Pairing expired. Make a new invitation.") } }
        onState("Preparing local Wi-Fi pairing")
        return peer
    }
    private fun bind(data: DataChannel) {
        channel = data
        val currentGeneration = generation
        data.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() { scope.launch { if (currentGeneration == generation) { if (data.state() == DataChannel.State.OPEN) checkOpen() else if (data.state() == DataChannel.State.CLOSED) { connected = false; stopMedia(); onState("Nearby connection lost. Saved work is safe.") } } } }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || buffer.data.remaining() > 24000) return
                val raw = ByteArray(buffer.data.remaining()); buffer.data.get(raw)
                scope.launch { if (currentGeneration == generation) runCatching { onFrame(JSONObject(String(raw))) } }
            }
        })
        // A remote channel can already be OPEN when its observer is attached.
        // Do not depend on receiving a state transition that has already happened.
        checkOpen()
    }
    private fun checkOpen() {
        val peer = pc ?: return
        if (channel?.state() != DataChannel.State.OPEN || peer.signalingState() != PeerConnection.SignalingState.STABLE || connected) return
        val fingerprints = listOf(peer.localDescription?.description, peer.remoteDescription?.description).map { Regex("a=fingerprint:([^\\r\\n]+)").find(it ?: "")?.groupValues?.get(1) ?: "" }.sorted()
        if (fingerprints.any { it.isBlank() }) return
        connected = true; pairingDeadline?.cancel()
        onCode(Protocol.hash(obj("fingerprints" to JSONArray(fingerprints), "session" to session)).take(8).uppercase())
        onState("Connected nearby. Compare the code before sharing.")
    }
    private suspend fun createDescription(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(value: SessionDescription) { if (cont.isActive) cont.resume(value) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException("Could not prepare pairing.")) }
            override fun onSetFailure(reason: String) {}
        }
        if (offer) pc!!.createOffer(observer, MediaConstraints()) else pc!!.createAnswer(observer, MediaConstraints())
    }
    private suspend fun setDescription(value: SessionDescription, remote: Boolean) = suspendCancellableCoroutine<Unit> { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(value: SessionDescription) {}
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onCreateFailure(reason: String) {}
            override fun onSetFailure(reason: String) { if (cont.isActive) cont.resumeWithException(IllegalStateException("This pairing invitation could not be applied.")) }
        }
        if (remote) pc!!.setRemoteDescription(observer, value) else pc!!.setLocalDescription(observer, value)
    }
    private suspend fun description(offer: Boolean): String {
        setDescription(createDescription(offer), false)
        withTimeout(12000) { while (pc?.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) delay(100) }
        checkOpen()
        val value = obj("v" to 1, "session" to session, "createdAt" to System.currentTimeMillis(), "type" to if (offer) "offer" else "answer", "sdp" to pc!!.localDescription.description)
        val out = ByteArrayOutputStream(); GZIPOutputStream(out).use { it.write(value.toString().toByteArray()) }
        return "SAATHI1:" + Protocol.b64(out.toByteArray())
    }
    suspend fun offer(): String {
        val peer = setup(); session = UUID.randomUUID().toString()
        peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
        peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
        bind(peer.createDataChannel("saathi-v1", DataChannel.Init()))
        return description(true)
    }
    suspend fun accept(text: String): String {
        require(text.startsWith("SAATHI1:") && text.length <= 20000) { "This is not a Saathi invitation." }
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(Protocol.decode(text.substring(8)))).use { input ->
            val chunk = ByteArray(2048)
            while (true) { val n = input.read(chunk); if (n < 0) break; require(output.size() + n <= 50000) { "This invitation is too large." }; output.write(chunk, 0, n) }
        }
        val value = JSONObject(output.toString("UTF-8")); value.exact("v", "session", "createdAt", "type", "sdp")
        require(value.getInt("v") == 1 && UUID.fromString(value.getString("session")).toString() == value.getString("session") && value.getString("sdp").length <= 40000)
        require(kotlin.math.abs(System.currentTimeMillis() - value.getLong("createdAt")) <= 120000) { "This invitation expired. Ask for a new one." }
        if (value.getString("type") == "offer") {
            setup(); session = value.getString("session")
            setDescription(SessionDescription(SessionDescription.Type.OFFER, value.getString("sdp")), true)
            pc!!.transceivers.forEach { it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV }
            return description(false)
        }
        require(value.getString("type") == "answer" && pc?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER && session == value.getString("session")) { "This reply belongs to another invitation." }
        setDescription(SessionDescription(SessionDescription.Type.ANSWER, value.getString("sdp")), true); checkOpen(); return ""
    }
    override suspend fun send(frame: JSONObject) {
        val raw = frame.toString().toByteArray(); require(raw.size <= 24000 && connected)
        val currentChannel = channel ?: error("Connection lost."); val currentGeneration = generation
        withTimeout(15000) { while (currentChannel.bufferedAmount() > 65536) { require(connected && generation == currentGeneration); delay(40) } }
        check(generation == currentGeneration && currentChannel.send(DataChannel.Buffer(ByteBuffer.wrap(raw), false))) { "Connection lost. Saved work is safe." }
    }
    fun capture(video: Boolean) {
        require(connected)
        stopMedia()
        pc!!.transceivers.forEach { it.receiver.track()?.setEnabled(true) }
        audioSource = factory.createAudioSource(MediaConstraints()); audioTrack = factory.createAudioTrack("saathi-audio", audioSource).apply { setEnabled(true) }
        pc!!.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO }?.sender?.setTrack(audioTrack, false)
        if (video) {
            val cameras = Camera2Enumerator(context); val name = cameras.deviceNames.firstOrNull { cameras.isFrontFacing(it) } ?: cameras.deviceNames.firstOrNull() ?: error("No camera is available.")
            videoSource = factory.createVideoSource(false); capturer = cameras.createCapturer(name, null)
            helper = SurfaceTextureHelper.create("SaathiCamera", egl.eglBaseContext)
            capturer!!.initialize(helper, context, videoSource!!.capturerObserver); capturer!!.startCapture(640, 360, 15)
            videoTrack = factory.createVideoTrack("saathi-video", videoSource).apply { setEnabled(true) }
            pc!!.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.sender?.setTrack(videoTrack, false)
            quality = scope.launch { while (isActive) { delay(4000); adapt() } }
        }
    }
    private fun adapt() {
        val currentGeneration = generation
        pc?.getStats { report -> scope.launch {
            if (generation != currentGeneration || !connected) return@launch
            val pair = report.statsMap.values.firstOrNull { it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true }
            val weak = (pair?.members?.get("currentRoundTripTime") as? Number)?.toDouble()?.let { it > 0.8 } == true || (pair?.members?.get("availableOutgoingBitrate") as? Number)?.toDouble()?.let { it < 150000 } == true || report.statsMap.values.filter { it.type == "remote-inbound-rtp" }.any { (it.members["fractionLost"] as? Number)?.toDouble()?.let { loss -> loss > .12 } == true }
            weakReadings = if (weak) weakReadings + 1 else 0
            val sender = pc?.transceivers?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.sender
            sender?.parameters?.let { parameters -> parameters.encodings.forEach { it.maxBitrateBps = if (weak) 100000 else 500000; it.maxFramerate = if (weak) 8 else 15; it.scaleResolutionDownBy = if (weak) 2.0 else 1.0 }; sender.parameters = parameters }
            if (weakReadings >= 2) { videoTrack?.setEnabled(false); onQuality("Video paused to keep audio clear. Messages still work.") }
            else if (weak) onQuality("Reducing video quality to keep audio clear.")
        } }
    }
    fun resumeVideo() { weakReadings = 0; videoTrack?.setEnabled(true); onQuality("Video resumed") }
    fun stopMedia() {
        quality?.cancel(); runCatching { capturer?.stopCapture() }; capturer?.dispose(); capturer = null
        pc?.transceivers?.forEach { it.sender.setTrack(null, false); it.receiver.track()?.setEnabled(false) }
        audioTrack?.dispose(); videoTrack?.dispose(); audioSource?.dispose(); videoSource?.dispose(); helper?.dispose()
        audioTrack = null; videoTrack = null; audioSource = null; videoSource = null; helper = null
    }
    override fun disconnect() { generation++; pairingDeadline?.cancel(); connected = false; stopMedia(); channel?.close(); channel?.dispose(); channel = null; pc?.close(); pc?.dispose(); pc = null; onCode(""); onVideo(null) }
    fun release() { disconnect(); factory.dispose(); egl.release() }
}
