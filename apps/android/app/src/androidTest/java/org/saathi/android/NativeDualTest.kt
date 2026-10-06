package org.saathi.android

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Two physical product adapters. USB carries fixture coordination only; all peer payloads use radio. */
@RunWith(AndroidJUnit4::class)
class NativeDualTest {
    @Test fun physicalPairMessagesResumableFileOriginalEventAndCarrierReceipt(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.ENVIRONMENT == "development" && args.getString("dualFixture") == "true")
        val role = args.getString("role")!!; require(role in listOf("author", "carrier"))
        val mode = args.getString("transport")!!; require(mode in listOf("nearby", "wifi"))
        val browserRelay = args.getString("browserRelay") == "true"; require(!browserRelay || mode == "wifi")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageScope = "test-dual-${UUID.randomUUID()}"; val repository = Repository(context, storageScope)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val client = OkHttpClient.Builder().readTimeout(150, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
        suspend fun meet(step: String, value: JSONObject = obj()): JSONObject = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url("http://127.0.0.1:4010/$step").header("Authorization", "Bearer ${args.getString("bridgeToken")}").post(obj("role" to role, "value" to value).toString().toRequestBody("application/json".toMediaType())).build()).execute().use {
                check(it.isSuccessful) { "Dual-device fixture step failed: $step" }; JSONObject(it.body!!.string())
            }
        }
        fun internet() = context.getSystemService(ConnectivityManager::class.java).let { it.getNetworkCapabilities(it.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }
        suspend fun waitFor(condition: () -> Boolean) = withContext(Dispatchers.Main) { withTimeout(90000) { while (!condition()) delay(100) } }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val session = PeerSession(context, repository, scope)
        val nearby = withContext(Dispatchers.Main) { NearbyTransport(context, scope) }
        val wifi = if (mode == "wifi") withContext(Dispatchers.Main) { LocalWifiTransport(context, scope) } else null
        val transport: PeerTransport = wifi ?: nearby; session.transport = transport
        val files = mutableSetOf<String>(); var code = ""; var round = 0; var connecting = false
        val errors = mutableListOf<String>(); session.onError = { errors.add(it) }
        session.onFile = { file -> if (role == "carrier" && file.getString("name") == "native-dual-qa.txt") { files.add(file.getString("id")); scope.launch { session.acceptFile(file) } } }
        nearby.onFrame = { session.incoming(it) }; wifi?.onFrame = { session.incoming(it) }; wifi?.onCode = { code = it }
        nearby.onPair = { digits -> if (digits != null) scope.launch { val other = meet("code-$round", obj("code" to digits)); nearby.confirm(other.getString("code") == digits) } }
        nearby.onPeers = { peers -> if (role == "author" && !connecting && peers.isNotEmpty()) { connecting = true; scope.launch { nearby.connect(peers.keys.first()) } } }
        suspend fun pair() {
            round++; connecting = false; code = ""
            meet("pair-ready-$round")
            withContext(Dispatchers.Main) {
                session.reset()
                if (wifi == null) nearby.scan(role == "carrier")
                else {
                    val offer = if (role == "author") wifi.offer() else ""
                    val other = meet("offer-$round", obj("description" to offer))
                    val reply = if (role == "carrier") wifi.accept(other.getString("description")) else ""
                    val answer = meet("reply-$round", obj("description" to reply))
                    if (role == "author") wifi.accept(answer.getString("description"))
                }
            }
            waitFor { transport.connected && (wifi == null || code.isNotBlank()) }
            if (wifi != null) { val other = meet("code-$round", obj("code" to code)); assertEquals(code, other.getString("code")) }
            withContext(Dispatchers.Main) { session.confirm() }
            meet("connected-$round", obj("internetValidated" to internet()))
            waitFor { session.maximumFileBytes > 1048576 }
        }
        suspend fun statistics(): JSONObject {
            val connection = LocalWifiTransport::class.java.getDeclaredField("pc").apply { isAccessible = true }.get(wifi) as org.webrtc.PeerConnection
            return suspendCancellableCoroutine { continuation -> connection.getStats { report ->
                val streams = report.statsMap.values.filter { it.type == "inbound-rtp" }
                continuation.resume(obj("audioBytes" to streams.filter { (it.members["kind"] ?: it.members["mediaType"]) == "audio" }.sumOf { (it.members["bytesReceived"] as? Number)?.toLong() ?: 0 }, "videoFrames" to streams.filter { (it.members["kind"] ?: it.members["mediaType"]) == "video" }.sumOf { (it.members["framesDecoded"] as? Number)?.toLong() ?: 0 }))
            } }
        }
        try {
            repository.refresh(); if (role == "author") repository.login("android-volunteer@saathi.test", args.getString("fixturePassword")!!, "")
            meet("prepared", obj("internetValidated" to internet()))
            pair()
            withContext(Dispatchers.Main) { session.message("Physical $role message ✓") }
            waitFor { repository.store.all("messages").any { it.getString("direction") == "IN" } && repository.store.all("messages").any { it.getString("direction") == "OUT" && it.has("deliveredAt") } }
            meet("messages-acknowledged")
            var event: JSONObject? = null; var file: JSONObject? = null
            if (role == "author") {
                val point = repository.preparation!!.getJSONArray("points").getJSONObject(0)
                event = repository.author("REQUEST_CREATED", obj("title" to "Physical native relay ${UUID.randomUUID().toString().take(8)}", "description" to "Fictional two-device product test, no real relief activity.", "requestedQuantity" to 1, "unit" to "packs", "category" to "OTHER", "priority" to "URGENT", "deadline" to Instant.now().plusSeconds(3600).toString(), "reliefPointId" to point.getString("id")), point.getString("organizationId"))
                val id = UUID.randomUUID().toString(); files.add(id); val bytes = ByteArray(4 * 1048576) { (it % 251).toByte() }
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                file = obj("id" to id, "name" to "native-dual-qa.txt", "mime" to "text/plain", "size" to bytes.size, "hash" to hash, "direction" to "OUT", "complete" to true)
                File(context.filesDir, "attachments").mkdirs()
                for (index in 0 until 512) File(context.filesDir, "attachments/$id-$index.bin").writeBytes(repository.store.encrypt(bytes.copyOfRange(index * 8192, (index + 1) * 8192), "attachment/$id/$index"))
                repository.store.put("attachments", id, file)
            }
            val metadata = meet("payload-ready", if (role == "author") obj("eventId" to event!!.getString("id"), "eventHash" to Protocol.hash(event.getJSONObject("envelope")), "fileHash" to file!!.getString("hash")) else obj())
            if (role == "author") withContext(Dispatchers.Main) { session.offerSaved(file!!) }
            if (role == "carrier") waitFor { repository.store.all("attachments").any { file -> (0 until 32).all { session.hasPart(file.getString("id"), it) } } }
            meet("partial-file-persisted")
            if (role == "carrier") withContext(Dispatchers.Main) { transport.disconnect(); session.reset() }
            waitFor { !transport.connected }; meet("interrupted")
            delay(11000); pair()
            if (role == "author") withContext(Dispatchers.Main) { session.offerSaved(file!!); session.shareEvents() }
            if (role == "carrier") {
                waitFor { repository.events().isNotEmpty() }
                val carried = repository.events().single(); assertEquals(metadata.getString("eventId"), carried.getString("id")); assertEquals(metadata.getString("eventHash"), Protocol.hash(carried.getJSONObject("envelope"))); assertEquals(1, carried.getInt("hops"))
                assertFalse("Urgent event should arrive before the resumed large file completes", repository.store.all("attachments").single().optBoolean("complete"))
            }
            meet("urgent-event-before-file")
            waitFor { if (role == "author") repository.store.all("attachments").single().has("deliveredAt") else repository.store.all("attachments").single().optBoolean("complete") }
            if (role == "carrier") assertEquals(metadata.getString("fileHash"), repository.store.all("attachments").single().getString("hash"))
            meet("resumed-file-hash-verified", obj("bytes" to 4 * 1048576))
            if (wifi != null) {
                waitFor { session.remoteMedia }
                session.onCall = { video -> scope.launch { session.pauseTransfersForCall(); wifi.capture(video); session.send("CALL_ACCEPT", obj()) } }
                var answered = false; session.onAccepted = { answered = true }; session.onEnded = { wifi.stopMedia() }
                for (video in listOf(false, true)) {
                    answered = false; meet("call-ready-$video")
                    if (role == "author") withContext(Dispatchers.Main) { session.pauseTransfersForCall(); wifi.capture(video); session.send("CALL", obj("video" to video)) }
                    if (role == "author") waitFor { answered }
                    delay(8000)
                    val stats = statistics(); assertTrue("Remote audio must actually arrive", stats.getLong("audioBytes") > 0); if (video) assertTrue("Remote camera frames must actually decode", stats.getLong("videoFrames") > 0)
                    meet("call-media-$video", stats)
                    withContext(Dispatchers.Main) { session.send("CALL_END", obj()); wifi.stopMedia(); session.callInProgress = false }
                    meet("call-ended-$video")
                }
            }
            if (role == "author") withContext(Dispatchers.Main) { transport.disconnect(); session.reset() }
            waitFor { !transport.connected }; meet("author-disconnected")
            if (browserRelay) {
                suspend fun browserPair(label: String) {
                    code = ""
                    val offer = if (role == "carrier") withContext(Dispatchers.Main) { session.reset(); wifi!!.offer() } else ""
                    val answer = meet("browser-offer-$label", obj("description" to offer))
                    if (role == "carrier") { withContext(Dispatchers.Main) { wifi!!.accept(answer.getString("reply")) }; waitFor { wifi!!.connected && code.isNotBlank() } }
                    meet("browser-code-$label", obj("code" to code))
                    if (role == "carrier") withContext(Dispatchers.Main) { session.confirm() }
                }
                browserPair("event")
                if (role == "carrier") withContext(Dispatchers.Main) { session.shareEvents() }
                meet("browser-original-event", if (role == "carrier") obj("eventId" to repository.events().single().getString("id"), "eventHash" to Protocol.hash(repository.events().single().getJSONObject("envelope"))) else obj())
                if (role == "carrier") waitFor { !wifi!!.connected }
                browserPair("receipt")
                meet("browser-share-receipt")
                if (role == "carrier") waitFor { repository.events().single().has("receipt") }
                meet("browser-receipt-returned")
                if (role == "carrier") waitFor { !wifi!!.connected }
            } else if (role == "carrier") repository.sync(true)
            if (role == "carrier") assertEquals("PUBLISHED", repository.events().single().getJSONObject("receipt").getJSONObject("body").getString("status"))
            meet("carrier-published-original-signature")
            delay(11000); pair()
            if (role == "carrier") withContext(Dispatchers.Main) { session.shareEvents() }
            waitFor { repository.events().single().has("receipt") }
            assertEquals("PUBLISHED", repository.events().single().getJSONObject("receipt").getJSONObject("body").getString("status"))
            meet("receipt-returned-to-author")
            assertTrue(errors.joinToString("; "), errors.filterNot { it.contains("paused", ignoreCase = true) }.isEmpty())
            meet("complete", obj("messages" to 2, "resumedBytes" to 4 * 1048576, "mediaTested" to (wifi != null)))
        } finally {
            withContext(Dispatchers.Main) { transport.disconnect(); session.reset(); files.forEach { session.removeFile(it) }; wifi?.release() }
            scope.cancel(); if (role == "author") runCatching { repository.logout(true) }; repository.store.clearPrivate(); repository.store.close(); context.deleteDatabase("saathi-$storageScope.db"); activity.close()
        }
    }
}
