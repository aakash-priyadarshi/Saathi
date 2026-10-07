package org.saathi.android

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SecureStoreTest {
    @Test fun concurrentTransactionsAndStoreWritesUseOneLockOrder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test-${UUID.randomUUID()}"; val store = SecureStore(context, scope)
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val transaction = pool.submit {
                start.await()
                store.transaction {
                    repeat(20) { store.put("public", "transaction-$it", obj("n" to it), false) }
                }
            }
            val writer = pool.submit {
                start.await()
                repeat(20) { store.put("public", "writer-$it", obj("n" to it), false) }
            }
            start.countDown()
            transaction.get(10, TimeUnit.SECONDS)
            writer.get(10, TimeUnit.SECONDS)
            assertEquals(40, store.all("public").size)
        } finally {
            pool.shutdownNow(); store.close(); context.deleteDatabase("saathi-$scope.db")
        }
    }

    @Test fun missingEncryptionKeyPreservesRecordsAndNeverRegeneratesOverPrivateWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test-${UUID.randomUUID()}"; val first = SecureStore(context, scope)
        first.put("drafts", "fixture", obj("title" to "Recoverable private fixture")); first.put("public", "fixture", obj("title" to "Public fixture"), false); first.close()
        val keys = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keys.deleteEntry("saathi.$scope.storage")
        val reopened = SecureStore(context, scope)
        assertFalse(keys.containsAlias("saathi.$scope.storage")); assertNotNull(reopened.get("public", "fixture"))
        assertThrows(Exception::class.java) { reopened.get("drafts", "fixture") }
        val rows = reopened.readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE bucket='drafts'", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(1, rows); reopened.close(); context.deleteDatabase("saathi-$scope.db")
    }
    @Test fun encryptedDraftsSurviveReopenAndRejectCiphertextOrRecordSubstitution() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test-${UUID.randomUUID()}"
        val first = SecureStore(context, scope)
        val original = obj("title" to "Private fixture draft ✓")
        first.put("drafts", "fixture", original)
        first.put("public", "fixture", obj("title" to "Public fixture"), false)
        val raw = first.readableDatabase.rawQuery("SELECT data FROM records WHERE bucket='drafts'", null).use { it.moveToFirst(); it.getBlob(0) }
        assertFalse(String(raw).contains("Private fixture"))
        first.close()
        val reopened = SecureStore(context, scope)
        assertEquals(original.toString(), reopened.get("drafts", "fixture").toString())
        assertThrows(Exception::class.java) { reopened.decrypt(raw, "drafts/different-record") }
        val changed = raw.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { reopened.decrypt(changed, "drafts/fixture") }
        reopened.clearPrivate()
        assertNull(reopened.get("drafts", "fixture")); assertNotNull(reopened.get("public", "fixture"))
        reopened.close(); context.deleteDatabase("saathi-$scope.db")
    }
    @Test fun signingIdentityIsNonExportableAndDeletedOnLogoutWithoutTouchingOtherAccounts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = "test-${UUID.randomUUID()}"; val store = SecureStore(context, scope)
        val account = UUID.randomUUID().toString(); store.ensureIdentity(account)
        assertNull(store.privateKey(account).encoded)
        val data = obj("original" to "Native Keystore fixture", "amount" to 15)
        val signature = Protocol.sign(data, store.privateKey(account))
        assertTrue(Protocol.verify(data, signature, Protocol.publicJwk(store.publicKey(account))))
        assertTrue(store.securityDescription().startsWith("Protected by Android"))
        store.clearPrivate()
        assertThrows(Exception::class.java) { store.privateKey(account) }
        store.close(); context.deleteDatabase("saathi-$scope.db")
    }
}
