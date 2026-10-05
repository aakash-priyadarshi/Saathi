package org.saathi.android

import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.time.Instant
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.interfaces.ECPublicKey

class ProtocolTest {
    private val fixture = JSONObject(javaClass.getResourceAsStream("/protocol-vectors.json")!!.bufferedReader().readText())
    private val now = Instant.parse(fixture.getString("now")).plusSeconds(2)
    private fun copy(name: String) = JSONObject(fixture.getJSONObject(name).toString())
    private fun config(value: JSONObject = copy("configuration"), minimum: Long = 1, previous: JSONObject? = null, time: Instant = now) = ServiceConfiguration.verify(value, fixture.getJSONObject("root"), "staging", 1, previous, minimum, time)
    @Test fun verifiesActualTypeScriptSignaturesAndCanonicalStringHashes() {
        assertEquals(fixture.getString("stringHash"), Protocol.hash("canonical string ✓"))
        assertEquals(7, config().getJSONObject("body").getInt("version"))
        assertTrue(Protocol.validEnvelope(copy("envelope"), 1, now))
        assertTrue(Protocol.validReceipt(copy("receipt"), copy("envelope"), copy("configuration").getJSONObject("body").getJSONArray("receiptKeys"), now))
    }
    @Test fun rejectsTamperedEventsReceiptsAndExcessHops() {
        val event = copy("envelope"); event.getJSONObject("body").getJSONObject("payload").put("requestedQuantity", 900)
        assertFalse(Protocol.validEnvelope(event, 1, now)); assertFalse(Protocol.validEnvelope(copy("envelope"), 7, now)); assertFalse(Protocol.validEnvelope(copy("envelope"), 1, now.plusSeconds(86400)))
        val receipt = copy("receipt"); receipt.getJSONObject("body").put("status", "PUBLISHED").put("publicId", "SAA-FORGED11")
        assertFalse(Protocol.validReceipt(receipt, copy("envelope"), copy("configuration").getJSONObject("body").getJSONArray("receiptKeys"), now))
    }
    @Test fun rejectsForgedConfigurationRollbackAndExpiry() {
        val tampered = copy("configuration"); tampered.getJSONObject("body").getJSONArray("apiEndpoints").put(0, "https://attacker.example")
        assertThrows(IllegalArgumentException::class.java) { config(tampered) }
        assertThrows(IllegalArgumentException::class.java) { config(minimum = 8) }
        assertThrows(IllegalArgumentException::class.java) { config(time = now.plusSeconds(31 * 86400)) }
        assertThrows(IllegalArgumentException::class.java) { ServiceConfiguration.verify(copy("configuration"), fixture.getJSONObject("root"), "production", 1, now = now) }
    }
    @Test fun revokedKeysNeverVerifyReceiptsButRetiredKeysDoWithinPolicy() {
        val keys = copy("configuration").getJSONObject("body").getJSONArray("receiptKeys")
        keys.getJSONObject(0).put("status", "RETIRED")
        assertTrue(Protocol.validReceipt(copy("receipt"), copy("envelope"), keys, now))
        keys.getJSONObject(0).put("status", "REVOKED")
        assertFalse(Protocol.validReceipt(copy("receipt"), copy("envelope"), keys, now))
    }
    @Test fun nativeJcaSignatureRoundTripsThroughP1363() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val data = obj("unicode" to "एक साथ ✓", "a" to 12.5, "z" to -0.0)
        val signature = Protocol.sign(data, keys.private)
        assertEquals(86, signature.length); assertTrue(Protocol.verify(data, signature, Protocol.publicJwk(keys.public as ECPublicKey)))
        assertFalse(Protocol.verify(obj("unicode" to "changed"), signature, Protocol.publicJwk(keys.public as ECPublicKey)))
    }
}
