package org.saathi.android

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.PriorityQueue

/** Durable saves precede ACK. Transport confirmation never implies volunteer authority/publication. */
class PeerSession(private val context: Context, private val repository: Repository, private val scope: CoroutineScope) {
    var transport: PeerTransport? = null
    var confirmed = false
    var remoteMedia = false; private set
    var remoteWalkieTalkie = false; private set
    @Volatile private var remoteChatChunks = false
    private val chatAssembler = ChatFrameAssembler()
    @Volatile private var remoteLarge = false
    val maximumFileBytes get() = if (remoteLarge && repository.featureFlags?.optBoolean("largeFiles") == true) MAX_FILE else 1048576
    private data class Received(val generation: Long, val frame: JSONObject)
    private val incoming = Channel<Received>(64)
    @Volatile private var generation = 0L
    @Volatile private var remoteMaximumFrameBytes = 24000
    val negotiatedFrameBytes get() = minOf(24000, transport?.maximumFrameBytes ?: 0, remoteMaximumFrameBytes)
    @Volatile private var expectedPeerIdentity: String? = null
    @Volatile private var peerIdentityVerified = true
    val connectionGeneration get() = generation
    var callInProgress = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private data class Queued(val priority: Int, val order: Long, val generation: Long, val transport: PeerTransport, val frame: JSONObject, val result: CompletableDeferred<Unit>)
    private val queue = PriorityQueue<Queued>(compareBy<Queued> { it.priority }.thenBy { it.order })
    private var order = 0L
    private val fragments = mutableMapOf<String, Pair<JSONObject, Array<String?>>>()
    private val shared = mutableSetOf<String>()
    private val transmitted = mutableSetOf<String>()
    private val requested = mutableSetOf<String>()
    private val offered = mutableSetOf<String>()
    private val accepted = mutableSetOf<String>()
    private val sendingFiles = mutableMapOf<String, Job>()
    private val requestedMissing = mutableMapOf<String, Int>()
    private val chunkDirectory = File(context.filesDir, "attachments").apply { mkdirs() }
    var onChange: () -> Unit = {}
    var onError: (String) -> Unit = {}
    var onFile: (JSONObject) -> Unit = {}
    var onCall: (Boolean) -> Unit = {}
    var onAccepted: () -> Unit = {}
    var onEnded: () -> Unit = {}
    var onWalkieFrame: (String, JSONObject) -> Unit = { _, _ -> }
    var onChatFrame: suspend (JSONObject, Long) -> Unit = { _, _ -> }
    var onChatConnected: suspend () -> Unit = {}
    var onChatReset: () -> Unit = {}
    var chatFileAllowed: (String,String) -> Boolean = { _,_->false }
    var onChatFileComplete: suspend (String) -> Unit = {}
    var onCommunityFrame: suspend (JSONObject,Long)->Unit={_,_->}
    var currentPeerIdentity: () -> String? = { null }
    var onPeerIdentityVerified: suspend (String) -> Unit = {}
    var publicFileAllowed:(String,String)->Boolean={_,_->false}
    var onPublicFileComplete:suspend(String)->Unit={}
    init {
        scope.launch { for (item in incoming) if (item.generation == generation) runCatching { receive(item.frame, item.generation) }.onFailure { onError(it.message ?: "This nearby update could not be saved.") } }
        scope.launch {
            for (signal in wake) while (true) {
                val item = synchronized(queue) { queue.poll() } ?: break
                try { check(item.generation == generation) { "Connection changed. Saved work is safe." }; item.transport.send(item.frame); item.result.complete(Unit) } catch (e: Exception) { item.result.completeExceptionally(e) }
            }
        }
    }
    fun incoming(frame: JSONObject) { if (!incoming.trySend(Received(generation, frame)).isSuccess) onError("Too much nearby information. Reconnect to retry saved work.") }
    fun requireSamePeer(identity: String) { expectedPeerIdentity = identity; peerIdentityVerified = false }
    fun clearPeerRequirement() { expectedPeerIdentity = null; peerIdentityVerified = true }
    fun verifyPeer(identity: String) {
        val expected = expectedPeerIdentity
        if (expected != null && expected != identity) {
            transport?.disconnect()
            error("This is a different nearby participant. The saved conversation and messages were left untouched.")
        }
        expectedPeerIdentity = null
        peerIdentityVerified = true
    }
    fun reset() {
        onChatReset()
        chatAssembler.clear(); remoteChatChunks = false; remoteWalkieTalkie = false
        generation++; callInProgress = false; confirmed = false; remoteMedia = false; remoteLarge = false; remoteMaximumFrameBytes = 24000; shared.clear(); transmitted.clear(); requested.clear(); offered.clear(); accepted.clear(); fragments.clear()
        sendingFiles.values.forEach { it.cancel() }; sendingFiles.clear()
        synchronized(queue) { while (queue.isNotEmpty()) queue.poll()?.result?.completeExceptionally(IllegalStateException("Connection changed. Saved work is safe.")) }
        onChange()
    }
    suspend fun send(kind: String, value: Any, id: String = UUID.randomUUID().toString(), priority: Int = if (kind in listOf("EVENT", "RECEIPT", "INVENTORY", "NEED")) 0 else if (kind == "FILE_CHUNK") 5 else if(kind.startsWith("CHAT_"))2 else 1): String {
        val frame = obj("v" to 1, "kind" to kind, "id" to id, "value" to value)
        val completion = CompletableDeferred<Unit>()
        val currentTransport = transport ?: error("Connect nearby first.")
        val needed = TransportCapabilities.required(kind, value)
        require(needed in currentTransport.capabilities) { "This connection cannot carry that kind of update. Your saved work is safe." }
        require(peerIdentityVerified || kind in setOf("HELLO", "NATIVE_CAPS", "CHAT_PROFILE")) {
            "Verifying the nearby participant. Messages stay on this phone until identity is confirmed."
        }
        val encodedSize = frame.toString().toByteArray().size
        val budget = minOf(24000, currentTransport.maximumFrameBytes, remoteMaximumFrameBytes)
        if (kind.startsWith("CHAT_") && kind != "CHAT_CHUNK" && encodedSize > budget) {
            require(remoteChatChunks) { "Update the other device to share this larger channel. Your work is saved." }
            val sentGeneration = generation
            for (part in ChatFrameAssembler.parts(frame, budget)) {
                check(generation == sentGeneration)
                send("CHAT_CHUNK", part, priority = priority)
            }
            return id
        }
        require(currentTransport.maximumFrameBytes > 0 && encodedSize <= minOf(24000, currentTransport.maximumFrameBytes, remoteMaximumFrameBytes)) {
            "This update is too large for the current nearby connection. It remains saved on this phone."
        }
        synchronized(queue) { require(queue.size < 256) { "Nearby queue is full. Saved work is safe." }; queue.add(Queued(priority, order++, generation, currentTransport, frame, completion)) }
        wake.trySend(Unit); completion.await(); return id
    }
    suspend fun announce() {
        val current = transport
        send("HELLO", obj("protocol" to 1, "maxFrame" to (current?.maximumFrameBytes ?: 0), "media" to (current?.mediaAvailable == true && repository.featureFlags?.optBoolean("localCalls") == true), "files" to (current?.supportsFiles == true)))
        // Protocol extension is ignored by version-1 browsers; their 1 MiB limit remains unchanged.
        send("NATIVE_CAPS", obj("largeFiles" to (current?.supportsFiles == true && repository.featureFlags?.optBoolean("largeFiles") == true), "chatChunks" to true, "walkieTalkie" to (current?.mediaAvailable == true)))
        onChatConnected()
    }
    suspend fun confirm() { require(transport?.connected == true); confirmed = true; announce(); onChange() }
    suspend fun message(text: String) {
        require(text.isNotBlank() && text.length <= 4000)
        require(repository.store.all("messages").size < 500) { "Message storage is full. Clear reviewed messages first." }
        val id = UUID.randomUUID().toString(); val created = Instant.now().toString()
        repository.store.put("messages", id, obj("id" to id, "text" to text, "createdAt" to created, "direction" to "OUT", "peer" to (if (confirmed) transport?.session else null), "recipientParticipantId" to (currentPeerIdentity() ?: "")))
        onChange(); if (confirmed) retryMessage(id)
    }
    suspend fun retryMessage(id: String) {
        require(confirmed && transport?.connected == true) { "Connect and confirm the nearby person before sending this saved message." }
        val message = repository.store.get("messages", id) ?: error("Saved message is unavailable.")
        require(message.getString("direction") == "OUT" && !message.has("deliveredAt"))
        val recipient = message.optString("recipientParticipantId").takeIf { it.isNotBlank() }
        require(recipient == null || (peerIdentityVerified && currentPeerIdentity() == recipient)) {
            "This saved message is for a different verified participant. Its original ID was not sent to anyone else."
        }
        message.put("peer", transport!!.session); repository.store.put("messages", id, message)
        send("MESSAGE", obj("text" to message.getString("text"), "createdAt" to message.getString("createdAt")), id); onChange()
    }
    suspend fun retryPendingMessagesFor(identity: String) {
        if (!confirmed || !peerIdentityVerified || currentPeerIdentity() != identity) return
        repository.store.all("messages")
            .filter { it.optString("direction") == "OUT" && !it.has("deliveredAt") && it.optString("recipientParticipantId") == identity }
            .take(50)
            .forEach { record -> runCatching { retryMessage(record.getString("id")) }.onFailure { onError("A saved message is waiting for delivery. Reconnect to retry it.") } }
    }
    suspend fun shareEvents() {
        require(confirmed)
        val events = repository.events().filter { it.getInt("hops") < it.getJSONObject("envelope").getJSONObject("body").getInt("maxHops") && Instant.parse(it.getJSONObject("envelope").getJSONObject("body").getString("expiresAt")) > Instant.now() }.take(500)
        shared.clear(); shared.addAll(events.map { it.getString("id") }); requested.clear()
        val batchSize = ((minOf(24000, transport?.maximumFrameBytes ?: 0, remoteMaximumFrameBytes) - 128) / 40).coerceIn(1, 500)
        shared.toList().chunked(batchSize).forEach { send("INVENTORY", JSONArray(it)) }
    }
    private suspend fun receive(frame: JSONObject, receivedGeneration: Long) {
        frame.exact("v", "kind", "id", "value"); require(frame.getInt("v") == 1); UUID.fromString(frame.getString("id"))
        val kind = frame.getString("kind")
        if (kind == "HELLO") {
            val value = frame.getJSONObject("value"); value.exact("protocol", "maxFrame", "media", "files")
            require(value.getInt("protocol") == 1 && value.getInt("maxFrame") in 512..24000)
            remoteMaximumFrameBytes = value.getInt("maxFrame")
            remoteMedia = value.getBoolean("media") && transport?.mediaAvailable == true
            remoteLarge = value.getBoolean("files") && repository.featureFlags?.optBoolean("largeFiles") == true
            onChange(); return
        }
        if (kind == "NATIVE_CAPS") {
            val caps = frame.getJSONObject("value")
            remoteLarge = caps.optBoolean("largeFiles"); remoteChatChunks = caps.optBoolean("chatChunks")
            remoteWalkieTalkie = caps.optBoolean("walkieTalkie") && transport?.mediaAvailable == true
            onChange(); return
        }
        if (!peerIdentityVerified && kind != "CHAT_PROFILE") return
        if (!confirmed || transport?.connected != true) return
        if (kind.startsWith("PTT_")) {
            require(remoteWalkieTalkie && remoteMedia && transport?.mediaAvailable == true && repository.featureFlags?.optBoolean("localCalls") == true)
            onWalkieFrame(kind, frame.getJSONObject("value")); return
        }
        if (kind.startsWith("CHAT_")) {
            val assembled = if (kind == "CHAT_CHUNK") { require(remoteChatChunks); chatAssembler.accept(frame.getJSONObject("value")) ?: return } else frame
            onChatFrame(assembled, receivedGeneration); return
        }
        if (kind.startsWith("COMMUNITY_")) { onCommunityFrame(frame, receivedGeneration); return }
        when (kind) {
            "MESSAGE" -> {
                val value = frame.getJSONObject("value"); value.exact("text", "createdAt"); require(value.getString("text").length in 1..4000); Instant.parse(value.getString("createdAt"))
                val id = frame.getString("id"); val old = repository.store.get("messages", id)
                require(old == null || (old.getString("direction") == "IN" && old.getString("text") == value.getString("text") && old.getString("createdAt") == value.getString("createdAt")))
                require(old != null || repository.store.all("messages").size < 500) { "Message storage is full. Clear reviewed messages first." }
                repository.store.put("messages", id, obj("id" to id, "text" to value.getString("text"), "createdAt" to value.getString("createdAt"), "direction" to "IN", "peer" to transport!!.session, "deliveredAt" to Instant.now().toString()))
                send("ACK", obj("id" to id)); onChange()
            }
            "ACK" -> {
                val id = frame.getJSONObject("value").getString("id"); UUID.fromString(id)
                repository.store.get("messages", id)?.takeIf { it.optString("direction") == "OUT" && it.optString("peer") == transport!!.session }?.let { it.put("deliveredAt", Instant.now().toString()); repository.store.put("messages", id, it) }
                if (id in transmitted) repository.store.get("events", id)?.let { it.put("sharedAt", Instant.now().toString()); repository.store.put("events", id, it) }
                if (id in offered) repository.store.get("attachments", id)?.let { it.put("deliveredAt", Instant.now().toString()); repository.store.put("attachments", id, it) }
                onChange()
            }
            "INVENTORY" -> {
                val ids = frame.getJSONArray("value").strings(); require(ids.size <= 500); ids.forEach { UUID.fromString(it) }
                val batchSize = ((maximumFrameBytes() - 128) / 64).coerceIn(1, 50)
                for (batch in ids.chunked(batchSize)) send("NEED", JSONArray(batch.map { obj("id" to it, "hasEvent" to (repository.store.get("events", it) != null)) }))
            }
            "NEED" -> {
                val values = frame.getJSONArray("value").objects(); require(values.size <= 50)
                for (value in values) {
                    value.exact("id", "hasEvent"); val id = value.getString("id")
                    if (id !in shared || id in requested) continue
                    requested.add(id); val event = repository.store.get("events", id) ?: continue
                    val body = event.getJSONObject("envelope").getJSONObject("body")
                    if (!value.getBoolean("hasEvent") && event.getInt("hops") < body.getInt("maxHops") && Instant.parse(body.getString("expiresAt")) > Instant.now()) {
                        val raw = event.getJSONObject("envelope").toString().toByteArray()
                        val chunkSize = eventChunkBytes()
                        val total = (raw.size + chunkSize - 1) / chunkSize
                        require(total in 1..16) { "This signed update is too large for the current nearby connection. It remains saved on this phone." }
                        for (part in 0 until total) send("EVENT", obj("id" to id, "part" to part, "total" to total, "hops" to event.getInt("hops") + 1, "data" to Protocol.b64(raw.copyOfRange(part * chunkSize, minOf(raw.size, (part + 1) * chunkSize)))))
                        transmitted.add(id)
                    }
                    event.optJSONObject("receipt")?.let { send("RECEIPT", it) }
                }
            }
            "EVENT" -> {
                val value = frame.getJSONObject("value"); value.exact("id", "part", "total", "hops", "data")
                val id = value.getString("id"); UUID.fromString(id); val total = value.getInt("total"); val part = value.getInt("part"); val hops = value.getInt("hops")
                require(total in 1..16 && part in 0 until total && hops in 1..8 && value.getString("data").length <= maximumFrameBytes())
                require(id in fragments || fragments.size < 8)
                val pending = fragments.getOrPut(id) { value to arrayOfNulls(total) }; require(pending.second.size == total && pending.first.getInt("hops") == hops)
                val data = value.getString("data"); require(pending.second[part] == null || pending.second[part] == data); pending.second[part] = data
                if (pending.second.all { it != null }) {
                    fragments.remove(id); val raw = pending.second.flatMap { Protocol.decode(it!!).toList() }.toByteArray(); require(raw.size <= 65536)
                    val envelope = JSONObject(String(raw)); require(envelope.getJSONObject("body").getString("id") == id)
                    repository.receiveEvent(envelope, hops); send("ACK", obj("id" to id)); onChange()
                }
            }
            "RECEIPT" -> { repository.acceptReceipt(frame.getJSONObject("value")); onChange() }
            "CALL" -> { require(transport?.mediaAvailable == true && remoteMedia && repository.featureFlags?.optBoolean("localCalls") == true); onCall(frame.getJSONObject("value").getBoolean("video")) }
            "CALL_ACCEPT" -> onAccepted()
            "CALL_END" -> onEnded()
            "FILE_OFFER" -> {
                if (transport?.supportsFiles != true) return
                require(!callInProgress) { "End the call before receiving a file. Messages and requests still work." }
                val value = frame.getJSONObject("value"); value.exact("id", "name", "mime", "size", "hash"); UUID.fromString(value.getString("id"))
                require(value.getString("name").length in 1..100 && value.getString("hash").matches(Regex("[a-f0-9]{64}")))
                require(value.getLong("size") in 1..maximumFile().toLong() && (value.getString("mime") in allowedMime || value.getString("mime")=="application/octet-stream" && chatFileAllowed(value.getString("id"),value.getString("hash"))))
                onFile(value)
            }
            "FILE_ACCEPT" -> {
                if (transport?.supportsFiles != true) return
                val value = frame.getJSONObject("value"); val id = value.getString("id"); if (id !in offered || sendingFiles.containsKey(id)) return
                val file = repository.store.get("attachments", id) ?: return; val missing = value.getJSONArray("missing"); require(missing.length() <= ACCEPT_BATCH)
                val indices = (0 until missing.length()).map { missing.getInt(it) }; require(indices.distinct().size == indices.size && indices.all { it in 0 until chunks(file) })
                require(!callInProgress)
                sendingFiles[id] = scope.launch {
                    val self = coroutineContext[Job]
                    try {
                        val mime=file.optString("contentMime",file.getString("mime"));val priority=if(mime.startsWith("audio/"))3 else if(mime.startsWith("video/"))6 else if(mime.startsWith("image/") && file.getInt("size")<=1048576)4 else 5
                        for (index in indices) { val raw = withContext(Dispatchers.IO) { readChunk(id, index) }; send("FILE_CHUNK", obj("id" to id, "index" to index, "data" to Protocol.b64(raw)),priority=priority); delay(20) }
                        // Free the slot before FILE_DONE: the receiver may answer at once with its next round.
                        if (sendingFiles[id] === self) sendingFiles.remove(id)
                        send("FILE_DONE", obj("id" to id)); file.put("sentAt", Instant.now().toString()); repository.store.put("attachments", id, file); onChange()
                    } catch (e: Exception) { if (e !is CancellationException) onError("File paused. Reconnect and offer it again to resume.") }
                    finally { if (sendingFiles[id] === self) sendingFiles.remove(id) }
                }
            }
            "FILE_CHUNK" -> {
                if (transport?.supportsFiles != true) return
                val value = frame.getJSONObject("value"); val id = value.getString("id"); if (id !in accepted) return
                val file = repository.store.get("attachments", id) ?: return; val index = value.getInt("index"); require(index in 0 until chunks(file) && value.getString("data").length <= 11000)
                val raw = Protocol.decode(value.getString("data")); require(raw.size == minOf(8192, file.getInt("size") - index * 8192))
                // Saved parts are their own files; rewriting a 32,000-entry record per part would be quadratic.
                writeChunk(id, index, raw); if (index % 64 == 0) onChange()
            }
            "FILE_DONE" -> {
                if (transport?.supportsFiles != true) return
                val id = frame.getJSONObject("value").getString("id"); if (id !in accepted) return
                val file = repository.store.get("attachments", id) ?: return
                // Large files arrive in rounds: each accept names at most ACCEPT_BATCH parts to fit one frame.
                val missing = missingParts(id, file)
                if (missing.isNotEmpty()) {
                    require(missing.size < (requestedMissing[id] ?: Int.MAX_VALUE)) { "File is incomplete. Reconnect and receive it again to resume." }
                    requestedMissing[id] = missing.size; send("FILE_ACCEPT", obj("id" to id, "missing" to JSONArray(missing.take(ACCEPT_BATCH)))); onChange(); return
                }
                requestedMissing.remove(id)
                val hash = MessageDigest.getInstance("SHA-256"); for (index in 0 until chunks(file)) hash.update(readChunk(id, index))
                require(hash.digest().joinToString("") { "%02x".format(it) } == file.getString("hash")) { "This file could not be verified." }
                file.put("complete", true); repository.store.put("attachments", id, file); accepted.remove(id); send("ACK", obj("id" to id)); onChange()
                if(file.getString("mime")=="application/octet-stream")onChatFileComplete(id)
                else onPublicFileComplete(id)
            }
            "FILE_CANCEL" -> { val id = frame.getJSONObject("value").getString("id"); accepted.remove(id); sendingFiles.remove(id)?.cancel(); onChange() }
        }
    }
    private val allowedMime = listOf("image/jpeg", "image/png", "image/webp", "text/plain", "audio/mp4", "audio/mpeg", "video/mp4", "video/webm")
    private fun maximumFrameBytes() = minOf(24000, transport?.maximumFrameBytes ?: 0, remoteMaximumFrameBytes)
    private fun eventChunkBytes(): Int {
        val encodedBudget = (maximumFrameBytes() - 1024).coerceAtLeast(512)
        return minOf(12288, (encodedBudget * 3 / 4) - 32).coerceAtLeast(256)
    }
    private fun maximumFile() = maximumFileBytes
    private suspend fun fileReady(size: Long) {
        require(confirmed && !callInProgress) { "Confirm the nearby person and end any call before sharing a file." }
        if (size > 1048576 && repository.featureFlags?.optBoolean("largeFiles") == true) withTimeoutOrNull(3000) { while (!remoteLarge && transport?.connected == true) delay(50) }
        require(size <= maximumFile()) { "This connection supports files up to ${maximumFile() / 1048576} MB. Choose a smaller file or reconnect to another native Swarm app." }
    }
    private fun checkSpace(size: Int) {
        require(StatFs(context.filesDir.path).availableBytes > size.toLong() * 2 + 64 * 1024 * 1024) { "Free more phone storage before receiving this file." }
        require((chunkDirectory.listFiles()?.sumOf { it.length() } ?: 0L) + size < 1024L * 1024 * 1024) { "Attachment storage is full. Remove reviewed files first." }
        val battery = context.getSystemService(BatteryManager::class.java)
        require(size <= 1048576 || battery.isCharging || battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) >= 20) { "Charge this phone before transferring a large file." }
    }
    private fun chunks(file: JSONObject) = (file.getInt("size") + 8191) / 8192
    private fun chunkFile(id: String, index: Int): File { UUID.fromString(id); require(index in 0..MAX_PART); return File(chunkDirectory, "$id-$index.bin") }
    fun hasPart(id: String, index: Int) = chunkFile(id, index).exists()
    private fun missingParts(id: String, file: JSONObject) = (0 until chunks(file)).filter { !hasPart(id, it) }
    /** One decrypted 8 KiB part; callers stream large files part by part. */
    fun readPart(id: String, index: Int) = readChunk(id, index)
    private fun writeChunk(id: String, index: Int, bytes: ByteArray) { val file = chunkFile(id, index); val temp = File(file.path + ".pending"); temp.writeBytes(repository.store.encrypt(bytes, "attachment/$id/$index")); check(temp.renameTo(file)) }
    private fun readChunk(id: String, index: Int) = repository.store.decrypt(chunkFile(id, index).readBytes(), "attachment/$id/$index")
    suspend fun offerFile(source: Uri) = withContext(Dispatchers.IO) {
        require(confirmed && !callInProgress) { "Confirm the nearby person and end any call before sharing a file." }
        val sourceMime = context.contentResolver.getType(source) ?: "application/octet-stream"; require(sourceMime in allowedMime) { "Choose a photo, text, audio or video file." }
        val original = context.contentResolver.query(source, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst(); it.getLong(0) to it.getString(1) } ?: error("Cannot read this file.")
        // Videos go out compressed and metadata-free; the peer may carry them on to others.
        val compressed = if (sourceMime.startsWith("video/")) FieldMedia.prepare(context, source, video = true) else null
        val uri = compressed?.let { Uri.fromFile(it.file) } ?: source; val mime = if (compressed != null) "video/mp4" else sourceMime
        val sizeAndName = compressed?.let { it.size to original.second.substringBeforeLast('.') + ".mp4" } ?: original
        try {
        require(sizeAndName.first > 0); fileReady(sizeAndName.first)
        checkSpace(sizeAndName.first.toInt()); require(repository.store.all("attachments").size < 50)
        val id = UUID.randomUUID().toString(); val hash = MessageDigest.getInstance("SHA-256"); var size = 0; var index = 0
        try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                while (true) { val raw = ByteArray(8192); var n = 0; while (n < raw.size) { val read = input.read(raw, n, raw.size - n); if (read < 0) break; n += read }; if (n == 0) break; size += n; require(size <= maximumFile()); val chunk = raw.copyOf(n); hash.update(chunk); writeChunk(id, index++, chunk) }
            }
            require(size == sizeAndName.first.toInt())
            val file = obj("id" to id, "name" to sizeAndName.second.take(100), "mime" to mime, "size" to size, "hash" to hash.digest().joinToString("") { "%02x".format(it) }, "direction" to "OUT", "complete" to true)
            repository.store.put("attachments", id, file); withContext(Dispatchers.Main.immediate) { offerSaved(file); onChange() }
        } catch (e: Exception) { (0 until index).forEach { chunkFile(id, it).delete() }; throw e }
        } finally { compressed?.file?.delete() }
    }
    suspend fun offerSaved(file: JSONObject) {
        if (transport?.supportsFiles != true) {
            file.put("waitingForStrongerTransport", true)
            repository.store.put("attachments", file.getString("id"), file)
            onChange()
            onError("File saved on this phone. Reconnect using local Wi-Fi or a higher-bandwidth connection to send it.")
            return
        }
        fileReady(file.getLong("size"))
        if(file.optBoolean("chatOnly")) require(chatFileAllowed(file.getString("id"),file.getString("hash"))) { "Connect to an authorized chat member to share this attachment." }
        if(file.optBoolean("publicOnly"))require(publicFileAllowed(file.getString("id"),file.getString("hash"))) { "This public report is unavailable." }
        require(file.optBoolean("complete")) { "Receive the whole file before sharing it." }
        file.remove("waitingForStrongerTransport")
        repository.store.put("attachments", file.getString("id"), file)
        offered.add(file.getString("id"))
        send("FILE_OFFER", obj("id" to file.getString("id"), "name" to file.getString("name"), "mime" to file.getString("mime"), "size" to file.getInt("size"), "hash" to file.getString("hash")))
    }
    suspend fun acceptFile(offer: JSONObject) {
        require(confirmed && transport?.supportsFiles == true && !callInProgress && offer.getInt("size") <= maximumFile() && accepted.size < 2)
        checkSpace(offer.getInt("size")); val id = offer.getString("id"); val previous = repository.store.get("attachments", id)
        require(previous == null || (previous.optString("direction") == "IN" && previous.getString("hash") == offer.getString("hash") && previous.getInt("size") == offer.getInt("size")))
        val file = previous ?: JSONObject(offer.toString()).put("direction", "IN").put("complete", false)
        repository.store.put("attachments", id, file); accepted.add(id); requestedMissing.remove(id)
        val missing = missingParts(id, file); send("FILE_ACCEPT", obj("id" to id, "missing" to JSONArray(missing.take(ACCEPT_BATCH)))); onChange()
    }
    suspend fun cancelFile(id: String) { accepted.remove(id); sendingFiles.remove(id)?.cancel(); send("FILE_CANCEL", obj("id" to id)); onChange() }
    /** Splits an encrypted chat file into saved parts while hashing, without loading it into memory. */
    suspend fun saveChatFile(id:String,source:File)=withContext(Dispatchers.IO) {
        UUID.fromString(id); val size=source.length(); require(size in 29..MAX_FILE.toLong() && repository.store.all("attachments").size<50);checkSpace(size.toInt())
        var index=0;val digest=MessageDigest.getInstance("SHA-256")
        try {
            source.inputStream().buffered().use{input->val buffer=ByteArray(8192);while(true){val n=AttachmentCipher.readFully(input,buffer);if(n==0)break;val raw=buffer.copyOf(n);digest.update(raw);writeChunk(id,index++,raw)}}
            obj("id" to id,"name" to "Encrypted attachment","mime" to "application/octet-stream","size" to size.toInt(),"hash" to digest.digest().joinToString(""){"%02x".format(it)},"direction" to "OUT","complete" to true,"chatOnly" to true).also { repository.store.put("attachments",id,it) }
        }catch(e:Exception){(0 until index).forEach { chunkFile(id,it).delete() };throw e}
    }
    suspend fun savePublicFile(id:String,source:File,mime:String):JSONObject {
        require(mime in listOf("image/jpeg","video/mp4"));require(source.length() in 29..FieldMedia.MAX_BYTES)
        return saveChatFile(id,source).apply{put("name",if(mime=="image/jpeg")"Public report photo.jpg"else"Public report video.mp4");put("mime",mime);remove("chatOnly");put("publicOnly",true);repository.store.put("attachments",id,this)}
    }
    /** Whole-file read for small previews only (photos, voice notes); larger files stream via [exportTo]. */
    suspend fun readSavedBytes(id:String)=withContext(Dispatchers.IO) {
        val file=repository.store.get("attachments",id)?:error("Attachment is unavailable.");require(file.getBoolean("complete"));require(file.getInt("size")<=PREVIEW_BYTES){"This file is too large to preview. Export it instead."}
        val bytes=ByteArray(file.getInt("size"));for(index in 0 until chunks(file))readChunk(id,index).copyInto(bytes,index*8192)
        require(Protocol.digest(bytes)==file.getString("hash")) { "Attachment verification failed." };bytes
    }
    /** The saved file as a stream of its decrypted parts, read one part at a time. */
    fun openSaved(id:String):java.io.InputStream {
        val file=repository.store.get("attachments",id)?:error("Attachment is unavailable.");require(file.getBoolean("complete"));val count=chunks(file)
        return java.io.SequenceInputStream(object:java.util.Enumeration<java.io.InputStream>{
            var index=0
            override fun hasMoreElements()=index<count
            override fun nextElement()=java.io.ByteArrayInputStream(readChunk(id,index++))
        })
    }
    /** Writes the verified saved file to [target], part by part. */
    suspend fun exportTo(id:String,target:File)=withContext(Dispatchers.IO) {
        val file=repository.store.get("attachments",id)?:error("Attachment is unavailable.");require(file.getBoolean("complete"))
        val digest=MessageDigest.getInstance("SHA-256")
        target.outputStream().buffered().use{out->for(index in 0 until chunks(file)){val raw=readChunk(id,index);digest.update(raw);out.write(raw)}}
        if(digest.digest().joinToString(""){"%02x".format(it)}!=file.getString("hash")){target.delete();error("Attachment verification failed.")}
    }
    suspend fun saveServerChatChunks(id:String,size:Int,hash:String,parts:List<Pair<Int,ByteArray>>) = withContext(Dispatchers.IO) {
        UUID.fromString(id); require(size in 29..MAX_FILE && hash.matches(Regex("[a-f0-9]{64}")))
        val old=repository.store.get("attachments",id)
        require(old==null || old.getInt("size")==size && old.getString("hash")==hash)
        if(old?.optBoolean("complete")==true)return@withContext
        if(old==null){require(repository.store.all("attachments").size<50);checkSpace(size)}
        val count=(size+8191)/8192
        val file=old?:obj("id" to id,"name" to "Encrypted attachment","mime" to "application/octet-stream","size" to size,"hash" to hash,"direction" to "IN","complete" to false,"chatOnly" to true)
        if(old==null)repository.store.put("attachments",id,file)
        for((part,bytes) in parts){require(part in 0 until count && bytes.size==minOf(8192,size-part*8192));writeChunk(id,part,bytes)}
        if(missingParts(id,file).isEmpty()){
            val digest=MessageDigest.getInstance("SHA-256");for(part in 0 until count)digest.update(readChunk(id,part))
            if(digest.digest().joinToString(""){"%02x".format(it)}!=hash){
                (0 until count).forEach{chunkFile(id,it).delete()}
                error("Encrypted attachment verification failed. Retry to replace the invalid chunks.")
            }
            file.put("complete",true);repository.store.put("attachments",id,file)
        }
        onChange()
    }
    suspend fun pauseTransfersForCall() { callInProgress = true; (accepted.toList() + sendingFiles.keys.toList()).distinct().forEach { cancelFile(it) } }
    suspend fun exportFile(id: String, destination: Uri) = withContext(Dispatchers.IO) {
        val file = repository.store.get("attachments", id) ?: error("Saved file is unavailable.")
        require(file.getBoolean("complete")) { "Receive and verify the whole file before exporting it." }
        val hash = MessageDigest.getInstance("SHA-256")
        for (index in 0 until chunks(file)) hash.update(readChunk(id, index))
        require(hash.digest().joinToString("") { "%02x".format(it) } == file.getString("hash")) { "This saved file could not be verified. No file was exported." }
        context.contentResolver.openOutputStream(destination, "wt")?.use { output -> for (index in 0 until chunks(file)) output.write(readChunk(id, index)) } ?: error("The selected destination cannot be written.")
    }
    suspend fun removeFile(id: String) { if (confirmed) runCatching { cancelFile(id) }; repository.store.get("attachments", id)?.let { file -> (0 until chunks(file)).forEach { index -> chunkFile(id, index).delete(); File(chunkFile(id, index).path + ".pending").delete() }; repository.store.remove("attachments", id) }; onChange() }
    fun clearFiles() { chunkDirectory.listFiles()?.forEach { it.delete() } }
    companion object {
        /** 250 MB media plus room for chat encryption overhead. */
        const val MAX_FILE = FieldMedia.MAX_BYTES.toInt() + 1048576
        const val MAX_PART = 32767
        /** Missing-part indices per FILE_ACCEPT; 2,048 indices fit one 24 KB frame. */
        const val ACCEPT_BATCH = 2048
        const val PREVIEW_BYTES = 32 * 1024 * 1024
    }
}
