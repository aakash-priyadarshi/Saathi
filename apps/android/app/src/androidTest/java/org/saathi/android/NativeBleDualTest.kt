package org.saathi.android

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Real BLE/GATT and Swarm peer-session test. USB is used only for synthetic test barriers. */
@RunWith(AndroidJUnit4::class)
class NativeBleDualTest {
    @Test fun bluetoothOnlyPairAndAcknowledgedMessagesInBothDirections(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.ENVIRONMENT == "development" && args.getString("dualFixture") == "true")
        val role = args.getString("role")!!
        require(role in setOf("author", "carrier"))
        val bridgeToken = args.getString("bridgeToken")!!
        require(bridgeToken.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("Turn Wi-Fi off for the BLE-only proof", context.getSystemService(WifiManager::class.java).isWifiEnabled)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertFalse("BLE proof must not have validated internet", connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)

        val activity = ActivityScenario.launch(MainActivity::class.java)
        activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val storageScope = "test-ble-${UUID.randomUUID()}"
        val repository = Repository(context, storageScope)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = PeerSession(context, repository, scope)
        val transport = withContext(Dispatchers.Main) { BleTransport(context, scope) }
        val client = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
        val errors = CopyOnWriteArrayList<String>()

        suspend fun meet(step: String, value: JSONObject = obj()): JSONObject = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("http://127.0.0.1:4012/$step")
                .header("Authorization", "Bearer $bridgeToken")
                .post(obj("role" to role, "value" to value).toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "BLE fixture barrier failed: $step" }
                JSONObject(response.body!!.string())
            }
        }

        suspend fun waitFor(condition: () -> Boolean) = withTimeout(90_000) {
            while (!condition()) delay(100)
        }

        val pairCode = CompletableDeferred<String>()
        var connectionStarted = false
        transport.onError = errors::add
        transport.onPair = { code -> if (code != null) pairCode.complete(code) }
        transport.onFrame = session::incoming
        session.transport = transport
        session.onError = errors::add
        if (role == "author") {
            transport.onPeers = { peers ->
                if (!connectionStarted && peers.isNotEmpty()) {
                    connectionStarted = true
                    scope.launch { runCatching { transport.connect(peers.keys.first()) }.onFailure { errors.add(it.message ?: "BLE connect failed") } }
                }
            }
        }

        try {
            withContext(Dispatchers.Main) { transport.start(advertise = role == "carrier") }
            val localCode = withTimeout(75_000) { pairCode.await() }
            val compared = meet("pair-code", obj("code" to localCode))
            assertTrue("Both devices must show the same six-digit code", compared.getBoolean("matching"))
            withContext(Dispatchers.Main) { transport.confirm(true) }
            meet("pair-confirmed")
            waitFor { transport.connected }

            assertTrue("GATT MTU must support bounded Swarm messages", transport.maximumFrameBytes > 0)
            assertFalse("BLE must not advertise file transfer", transport.supportsFiles)
            assertFalse("BLE must not advertise calls", transport.mediaAvailable)
            withContext(Dispatchers.Main) { session.confirm() }

            val firstText = "Physical offline BLE proof A→B"
            if (role == "author") {
                withContext(Dispatchers.Main) { session.message(firstText) }
                waitFor { repository.store.all("messages").any { it.optString("text") == firstText && it.optString("direction") == "OUT" && it.has("deliveredAt") } }
            } else {
                waitFor { repository.store.all("messages").any { it.optString("text") == firstText && it.optString("direction") == "IN" } }
            }
            val firstBarrier = meet("message-a-to-b", obj("text" to firstText))
            val firstPeerValue = firstBarrier.getJSONObject("values").getJSONObject(if (role == "author") "carrier" else "author")
            assertEquals(firstText, firstPeerValue.getString("text"))

            val secondText = "Physical offline BLE proof B→A"
            if (role == "carrier") {
                withContext(Dispatchers.Main) { session.message(secondText) }
                waitFor { repository.store.all("messages").any { it.optString("text") == secondText && it.optString("direction") == "OUT" && it.has("deliveredAt") } }
            } else {
                waitFor { repository.store.all("messages").any { it.optString("text") == secondText && it.optString("direction") == "IN" } }
            }
            val secondBarrier = meet("message-b-to-a", obj("text" to secondText))
            val secondPeerValue = secondBarrier.getJSONObject("values").getJSONObject(if (role == "author") "carrier" else "author")
            assertEquals(secondText, secondPeerValue.getString("text"))
            assertTrue("Peer-session messages must retain UUID identities", repository.store.all("messages").filter { it.optString("direction") == "OUT" }.all { runCatching { UUID.fromString(it.getString("id")) }.isSuccess })
            assertTrue("No transport errors should be reported: ${errors.joinToString("; ")}", errors.isEmpty())
            meet("complete", obj("messages" to 2, "wifiEnabled" to false, "internetValidated" to false))
        } finally {
            withContext(Dispatchers.Main) {
                session.reset()
                transport.disconnect()
            }
            scope.cancel()
            repository.store.clearPrivate()
            repository.store.close()
            context.deleteDatabase("saathi-$storageScope.db")
            activity.close()
        }
    }
}
