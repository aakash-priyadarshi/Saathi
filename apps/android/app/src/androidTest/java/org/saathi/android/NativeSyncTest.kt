package org.saathi.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Opt-in fixture test against an isolated development server; no credentials enter an APK. */
@RunWith(AndroidJUnit4::class)
class NativeSyncTest {
    @Test fun preparedIdentityAuthorsDurablySynchronizesAndRejectsRevokedDevice() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(BuildConfig.ENVIRONMENT == "development" && arguments.containsKey("fixturePassword"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test-sync-${UUID.randomUUID()}"
        var repository = Repository(context, scope)
        try {
            repository.refresh()
            assertTrue(repository.requests().isNotEmpty())
            repository.login("android-volunteer@saathi.test", arguments.getString("fixturePassword")!!, "", "test-token")
            val prepared = repository.preparation!!
            val point = prepared.getJSONArray("points").getJSONObject(0)
            val title = "Native QA ${UUID.randomUUID().toString().take(8)}"
            val payload = obj("title" to title, "description" to "Fictional Android interoperability request; no real relief activity.", "requestedQuantity" to 2, "unit" to "packs", "category" to "OTHER", "priority" to "NORMAL", "deadline" to Instant.now().plusSeconds(3600).toString(), "reliefPointId" to point.getString("id"))
            repository.store.put("drafts", "fixture", obj("title" to title))
            val event = repository.author("REQUEST_CREATED", payload, point.getString("organizationId"))
            val id = event.getString("id")
            assertTrue(Protocol.validEnvelope(event.getJSONObject("envelope"), 0))
            val savedUser = repository.store.get("account", "user")!!
            val savedDevice = repository.store.get("account", "device")!!.getString("id")
            val identity = repository.store.publicKey(savedUser.getString("id")).encoded
            // The same email must not rebind existing private work to a replacement account ID.
            repository.store.put("account", "user", org.json.JSONObject(savedUser.toString()).put("id", UUID.randomUUID().toString()))
            assertThrows(Exception::class.java) { runBlocking { repository.login("android-volunteer@saathi.test", arguments.getString("fixturePassword")!!, "", "test-token") } }
            assertNull(repository.store.get("credentials", "session"))
            assertEquals(id, repository.events().single().getString("id"))
            repository.store.put("account", "user", savedUser)
            repository.login("android-volunteer@saathi.test", arguments.getString("fixturePassword")!!, "", "test-token")
            assertEquals(savedDevice, repository.store.get("account", "device")!!.getString("id"))
            assertArrayEquals(identity, repository.store.publicKey(savedUser.getString("id")).encoded)
            assertEquals(title, repository.store.get("drafts", "fixture")!!.getString("title"))
            repository.store.close()
            repository = Repository(context, scope)
            assertEquals(title, repository.store.get("drafts", "fixture")!!.getString("title"))
            assertEquals(id, repository.events().single().getString("id"))
            repository.sync(false)
            val receipt = repository.events().single().getJSONObject("receipt")
            assertEquals("PUBLISHED", receipt.getJSONObject("body").getString("status"))
            assertTrue(Protocol.validReceipt(receipt, event.getJSONObject("envelope"), repository.configuration!!.getJSONObject("body").getJSONArray("receiptKeys")))
            repository.sync(false) // Replay fetch returns the same logical request and verified receipt.
            repository.refresh()
            assertEquals(1, repository.requests().count { it.getString("title") == title })
            val publicId = repository.requests().single { it.getString("title") == title }.getString("publicId")
            val intent = "reserve/$publicId"; val retryKey = UUID.randomUUID().toString()
            val reservationBody = obj("publicId" to publicId, "quantity" to 1)
            // The server commits, then the process loses the response before its local response save.
            repository.store.put("operations", intent, obj("id" to intent, "path" to "/donations", "body" to reservationBody, "authenticated" to false, "key" to retryKey, "createdAt" to Instant.now().toString()))
            val firstReservation = org.json.JSONObject(repository.api("/donations", reservationBody, idempotencyKey = retryKey))
            repository.store.close(); repository = Repository(context, scope); repository.refreshConfiguration()
            val recovered = repository.retryWrite(repository.store.get("operations", intent)!!)
            assertEquals(firstReservation.getString("id"), recovered.getString("id"))
            assertNotNull(repository.store.get("donations", recovered.getString("trackingToken")))
            val current = org.json.JSONObject(repository.api("/public/verify/$publicId"))
            assertEquals(1, current.getInt("committedQuantity"))
            assertThrows(Exception::class.java) { runBlocking { repository.write(intent, "/donations", obj("publicId" to publicId, "quantity" to 2)) } }
            repository.write("cancel/${recovered.getString("trackingToken")}", "/donations/tracking/${recovered.getString("trackingToken")}/cancel", obj())
            val device = repository.store.get("account", "device")!!.getString("id")
            repository.api("/sync/devices/$device/revoke", obj(), true)
            val revoked = repository.author("REQUEST_CREATED", payload.put("title", "$title revoked"), point.getString("organizationId"))
            repository.sync(false)
            assertEquals("REJECTED", repository.store.get("events", revoked.getString("id"))!!.getJSONObject("receipt").getJSONObject("body").getString("status"))
        } finally {
            runCatching { repository.logout(true) }; repository.store.clearPrivate(); repository.store.close()
            context.deleteDatabase("saathi-$scope.db")
        }
    }
}
