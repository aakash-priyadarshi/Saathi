package org.saathi.android

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Actual foreground UI, identity signatures and Wi-Fi RTP. USB carries pairing/barriers only. */
@RunWith(AndroidJUnit4::class)
class NativeWalkieDualTest {
    @get:Rule val ui = createComposeRule()

    @Test fun holdReleaseRecipientSwitchAndBackgroundStopCapture(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.ENVIRONMENT == "development" && args.getString("dualFixture") == "true")
        val role = args.getString("role")!!; val author = role == "author"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageScope = "test-walkie-${UUID.randomUUID()}"
        val models = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            SaathiViewModel(context.applicationContext as Application, storageScope, startServices = false).also { models.put("fixture", it) }
        }
        // This isolated test exercises the local media feature. Signed-config acceptance has its own tests.
        Repository::class.java.getDeclaredField("configuration").apply { isAccessible = true }
            .set(vm.repository, obj("body" to obj("features" to obj("localCalls" to true, "largeFiles" to true))))
        val wifi = withContext(Dispatchers.Main) { vm.wifi }
        val client = OkHttpClient.Builder().readTimeout(95, TimeUnit.SECONDS).callTimeout(95, TimeUnit.SECONDS).build()
        suspend fun meet(step: String, value: JSONObject = obj()): JSONObject = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url("http://127.0.0.1:4014/$step")
                .header("Authorization", "Bearer ${args.getString("bridgeToken")}")
                .post(obj("role" to role, "value" to value).toString().toRequestBody("application/json".toMediaType()))
                .build()).execute().use { check(it.isSuccessful) { "Barrier failed: $step" }; JSONObject(it.body!!.string()) }
        }
        suspend fun waitFor(condition: () -> Boolean) = withContext(Dispatchers.Main) {
            withTimeout(80_000) { while (!condition()) delay(50) }
        }
        fun microphone() = LocalWifiTransport::class.java.getDeclaredField("audioTrack").apply { isAccessible = true }.get(wifi)
        suspend fun audioBytes(): Long = suspendCancellableCoroutine { continuation ->
            val connection = LocalWifiTransport::class.java.getDeclaredField("pc").apply { isAccessible = true }.get(wifi) as org.webrtc.PeerConnection
            connection.getStats { report ->
                val count = report.statsMap.values.filter { it.type == "inbound-rtp" && (it.members["kind"] ?: it.members["mediaType"]) == "audio" }
                    .sumOf { (it.members["bytesReceived"] as? Number)?.toLong() ?: 0 }
                if (continuation.isActive) continuation.resume(count)
            }
        }
        fun capture(name: String) {
            ui.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.cacheDir, "swarm-walkie-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        try {
            vm.chat.rename(if (author) "Test Arjun" else "Test Priya")
            withContext(Dispatchers.Main) { vm.session.transport = wifi; vm.foregroundActive() }
            meet("prepared")
            val offer = if (author) withContext(Dispatchers.Main) { wifi.offer() } else ""
            val otherOffer = meet("offer", obj("description" to offer))
            val reply = if (!author) withContext(Dispatchers.Main) { wifi.accept(otherOffer.getString("description")) } else ""
            val otherReply = meet("reply", obj("description" to reply))
            if (author) withContext(Dispatchers.Main) { wifi.accept(otherReply.getString("description")) }
            waitFor { wifi.connected && vm.state.value.localCode.isNotBlank() }
            assertEquals(vm.state.value.localCode, meet("code", obj("code" to vm.state.value.localCode)).getString("code"))
            withContext(Dispatchers.Main) { vm.session.confirm() }
            waitFor { vm.chat.peer != null && vm.state.value.walkieAvailable }
            val dm = vm.chat.direct(vm.chat.peer!!); vm.refreshLocal()
            waitFor { vm.state.value.conversations.any { it.getString("id") == dm } }
            ui.setContent { val state by vm.state.collectAsState(); SaathiTheme { androidx.compose.material3.Surface(Modifier.fillMaxSize()) { ConversationScreen(vm, state, dm, { _, _ -> }, {}, {}, {}, Modifier.fillMaxSize()) } } }
            ui.onNodeWithContentDescription("Enable walkie-talkie with ${if (author) "Test Priya" else "Test Arjun"}").performClick()
            waitFor { vm.state.value.walkieStatus == "READY" }
            assertNull("Idle walkie-talkie must have no microphone track", microphone())
            capture("ready"); meet("ready")
            suspend fun turn(speaker: Boolean, step: String) {
                val before = audioBytes()
                if (speaker) ui.onNodeWithText("Hold to talk").performTouchInput { down(center) }
                waitFor { vm.state.value.walkieStatus == if (speaker) "TALKING" else "LISTENING" }
                if (speaker) assertNotNull(microphone()) else assertNull(microphone())
                delay(1200); capture(if (speaker) "talking" else "listening")
                if (!speaker) withTimeout(8000) { while (audioBytes() <= before) delay(100) }
                val received = audioBytes() - before
                meet("$step-audio", obj("audioBytes" to received))
                if (speaker) ui.onNodeWithText("Release to stop").performTouchInput { up() }
                waitFor { vm.state.value.walkieStatus == "READY" }
                delay(300)
                assertNull("Release must dispose capture and must not trigger a second click", microphone())
                assertEquals("READY", vm.state.value.walkieStatus)
                meet("$step-released")
            }
            turn(author, "author"); turn(!author, "carrier")
            if (author) ui.onNodeWithText("Hold to talk").performTouchInput { down(center) }
            waitFor { vm.state.value.walkieStatus == if (author) "TALKING" else "LISTENING" }
            meet("switch-talking")
            if (author) withContext(Dispatchers.Main) { vm.enterConversation("another-conversation") }
            waitFor { vm.state.value.walkieStatus == if (author) "OFF" else "WAITING" }
            assertNull("Changing recipient must dispose capture", microphone())
            if (author) ui.onRoot().performTouchInput { up() }
            withContext(Dispatchers.Main) { vm.stopWalkie(); vm.enterConversation(dm) }
            meet("recipient-switch-stopped")
            withContext(Dispatchers.Main) { vm.enableWalkie(dm) }
            waitFor { vm.state.value.walkieStatus == "READY" }; meet("ready-again")
            if (author) ui.onNodeWithText("Hold to talk").performTouchInput { down(center) }
            waitFor { vm.state.value.walkieStatus == if (author) "TALKING" else "LISTENING" }
            meet("background-talking")
            withContext(Dispatchers.Main) { vm.foregroundLost() }
            waitFor { vm.state.value.walkieStatus == "OFF" }
            assertNull("Foreground loss must dispose capture", microphone())
            meet("complete", obj("microphoneStopped" to true))
        } finally {
            withContext(Dispatchers.Main) { vm.foregroundLost(); vm.disconnect() }
            vm.repository.store.clearPrivate()
            withContext(Dispatchers.Main) { models.clear() }
            context.deleteDatabase("saathi-$storageScope.db")
        }
    }
}
