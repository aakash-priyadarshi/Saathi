package org.saathi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opt-in browser bridge exchanges only fixture signaling on USB loopback; media uses direct ICE. */
@RunWith(AndroidJUnit4::class)
class NativeBrowserTest {
    @Test fun manualPairingMessagesFileHashesOriginalEventAndReconnect(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.ENVIRONMENT == "development" && args.getString("browserBridge") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageScope = "test-browser-${UUID.randomUUID()}"
        val repository = Repository(context, storageScope)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val client = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
        suspend fun bridge(path: String, value: JSONObject = obj()): JSONObject = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url("http://127.0.0.1:4009/$path").header("Authorization", "Bearer ${args.getString("bridgeToken")}").post(value.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { response ->
                check(response.isSuccessful) { "Browser fixture step failed: $path" }; JSONObject(response.body!!.string())
            }
        }
        val activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val wifi = withContext(Dispatchers.Main) { LocalWifiTransport(context, scope) }
        val session = PeerSession(context, repository, scope)
        var code = ""; val errors = mutableListOf<String>(); val createdFiles = mutableSetOf<String>()
        wifi.onCode = { code = it }; wifi.onFrame = { session.incoming(it) }; session.transport = wifi
        wifi.onState = { state -> scope.launch { bridge("diagnostic", obj("state" to state)) } }
        session.onError = { errors.add(it); scope.launch { bridge("diagnostic", obj("sessionError" to it)) } }
        session.onFile = { offer -> if (offer.getString("name") == "browser-fixture.txt") { createdFiles.add(offer.getString("id")); scope.launch { session.acceptFile(offer) } } }
        suspend fun waitFor(condition: () -> Boolean) { withTimeout(60000) { while (!condition()) delay(100) } }
        suspend fun pair() {
            withContext(Dispatchers.Main) {
                session.reset(); val offer = wifi.offer(); val answer = bridge("offer", obj("invitation" to offer)); wifi.accept(answer.getString("reply"))
                val peerField = LocalWifiTransport::class.java.getDeclaredField("pc").apply { isAccessible = true }
                val diagnostics = scope.launch { delay(15000); (peerField.get(wifi) as? org.webrtc.PeerConnection)?.getStats { report -> scope.launch { val pairs = report.statsMap.values.filter { it.type == "candidate-pair" }; bridge("diagnostic", obj("nativePairs" to org.json.JSONArray(pairs.map { stats -> obj("state" to stats.members["state"], "requestsSent" to stats.members["requestsSent"], "responsesReceived" to stats.members["responsesReceived"]) }), "nativeLocalCandidates" to report.statsMap.values.count { it.type == "local-candidate" }, "nativeRemoteCandidates" to report.statsMap.values.count { it.type == "remote-candidate" })) } } }
                waitFor { wifi.connected && code.isNotBlank() }; diagnostics.cancel(); bridge("confirm", obj("code" to code)); session.confirm(); waitFor { session.remoteMedia }
            }
        }
        try {
            repository.refresh(); repository.login("android-volunteer@saathi.test", args.getString("fixturePassword")!!, "")
            pair()
            withContext(Dispatchers.Main) { session.message("Native fixture message ✓") }
            bridge("message")
            waitFor { repository.store.all("messages").any { it.optString("direction") == "IN" && it.optString("text") == "Browser fixture reply ✓" } }
            assertNotNull(repository.store.all("messages").first { it.getString("direction") == "OUT" }.optString("deliveredAt").takeIf { it.isNotBlank() })
            bridge("file-to-native")
            waitFor { repository.store.all("attachments").any { it.optString("direction") == "IN" && it.optBoolean("complete") } }
            val received = repository.store.all("attachments").first { it.getString("direction") == "IN" }
            assertEquals(1048576, received.getInt("size"))
            val bytes = ByteArray(1048576) { 65 }; val fileId = UUID.randomUUID().toString(); createdFiles.add(fileId)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(hash, received.getString("hash"))
            val file = obj("id" to fileId, "name" to "native-fixture.txt", "mime" to "text/plain", "size" to bytes.size, "hash" to hash, "direction" to "OUT", "complete" to true)
            for (index in 0 until 128) File(context.filesDir, "attachments/$fileId-$index.bin").writeBytes(repository.store.encrypt(bytes.copyOfRange(index * 8192, (index + 1) * 8192), "attachment/$fileId/$index"))
            repository.store.put("attachments", fileId, file)
            withContext(Dispatchers.Main) { session.offerSaved(file) }; bridge("file-to-browser", obj("hash" to hash))
            val point = repository.preparation!!.getJSONArray("points").getJSONObject(0)
            val payload = obj("title" to "Native browser relay fixture", "description" to "Fictional relay interoperability; no real relief request.", "requestedQuantity" to 1, "unit" to "packs", "category" to "OTHER", "priority" to "URGENT", "deadline" to Instant.now().plusSeconds(3600).toString(), "reliefPointId" to point.getString("id"))
            val event = repository.author("REQUEST_CREATED", payload, point.getString("organizationId"))
            withContext(Dispatchers.Main) { session.shareEvents() }
            bridge("event", obj("id" to event.getString("id"), "hash" to Protocol.hash(event.getJSONObject("envelope"))))
            bridge("disconnect"); waitFor { !wifi.connected }
            assertEquals(2, repository.store.all("messages").size); assertEquals(1, repository.events().size)
            pair(); withContext(Dispatchers.Main) { session.message("Native reconnect fixture") }; bridge("reconnect")
            assertTrue(errors.joinToString("; "), errors.isEmpty())
            bridge("complete", obj("messages" to 2, "fileBytesEachWay" to 1048576, "originalEvent" to true, "reconnected" to true))
        } finally {
            withContext(Dispatchers.Main) { wifi.disconnect(); session.reset(); createdFiles.forEach { session.removeFile(it) }; wifi.release() }
            scope.cancel(); runCatching { repository.logout(true) }; repository.store.clearPrivate(); repository.store.close(); context.deleteDatabase("saathi-$storageScope.db"); activity.close()
        }
    }
}
