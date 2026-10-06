package org.saathi.android

import org.json.JSONArray
import org.json.JSONObject
import org.erdtman.jcs.JsonCanonicalizer
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings() = (0 until length()).map { getString(it) }
fun JSONObject.exact(vararg fields: String) { require(keys().asSequence().toSet() == fields.toSet()) { "Unrecognized or incomplete information." } }
fun JSONObject.exactOptional(required: List<String>, optional: List<String>) { exact(*(required + optional.filter { has(it) }).toTypedArray()) }

/** RFC 8785 JSON, P-256/SHA-256, IEEE P1363 signatures: identical to @saathi/protocol. */
object Protocol {
    // The JCS Java parser accepts an object root. A fixed wrapper lets that same
    // canonicalizer encode primitives/arrays, then removes only the wrapper.
    fun canonical(value: Any): ByteArray {
        val wrapped = JsonCanonicalizer(obj("v" to value).toString()).encodedString
        return wrapped.substring(5, wrapped.length - 1).toByteArray(Charsets.UTF_8)
    }
    fun hash(value: Any) = digest(canonical(value))
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    private fun fixed(n: BigInteger): ByteArray { require(n.signum() >= 0 && n.bitLength() <= 256); return ByteArray(32).also { val raw = n.toByteArray().takeLast(32).toByteArray(); raw.copyInto(it, 32 - raw.size) } }
    fun publicJwk(key: ECPublicKey) = obj("kty" to "EC", "crv" to "P-256", "x" to b64(fixed(key.w.affineX)), "y" to b64(fixed(key.w.affineY)))
    fun publicKey(jwk: JSONObject): ECPublicKey {
        jwk.exact("kty", "crv", "x", "y")
        require(jwk.getString("kty") == "EC" && jwk.getString("crv") == "P-256")
        val x = decode(jwk.getString("x")); val y = decode(jwk.getString("y")); require(x.size == 32 && y.size == 32)
        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), parameters)) as ECPublicKey
    }
    fun sign(value: JSONObject, key: PrivateKey): String {
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(canonical(value)); sign() }
        val sequence = ASN1Sequence.getInstance(der)
        require(sequence.size() == 2)
        return b64(fixed(ASN1Integer.getInstance(sequence.getObjectAt(0)).positiveValue) + fixed(ASN1Integer.getInstance(sequence.getObjectAt(1)).positiveValue))
    }
    fun verify(value: JSONObject, signature: String, jwk: JSONObject): Boolean = runCatching {
        require(signature.matches(Regex("[A-Za-z0-9_-]{86}")))
        val raw = decode(signature); require(raw.size == 64)
        val der = DERSequence(arrayOf(ASN1Integer(BigInteger(1, raw.copyOfRange(0, 32))), ASN1Integer(BigInteger(1, raw.copyOfRange(32, 64))))).encoded
        Signature.getInstance("SHA256withECDSA").run { initVerify(publicKey(jwk)); update(canonical(value)); verify(der) }
    }.getOrDefault(false)
    fun envelope(type: String, payload: JSONObject, author: String, organization: String, device: String, key: PrivateKey, publicKey: ECPublicKey): JSONObject {
        val now = Instant.now()
        val body = obj("id" to UUID.randomUUID().toString(), "protocolVersion" to 1, "authorId" to author, "organizationId" to organization, "deviceId" to device,
            "createdAt" to now.toString(), "expiresAt" to now.plusSeconds(86400).toString(), "intent" to "PUBLIC_RELIEF", "maxHops" to 6,
            "type" to type, "payload" to payload, "payloadHash" to hash(payload))
        return obj("body" to body, "publicKey" to publicJwk(publicKey), "signature" to sign(body, key)).also { require(canonical(it).size <= 65536) }
    }
    fun validEnvelope(envelope: JSONObject, hops: Int, now: Instant = Instant.now()): Boolean = runCatching {
        envelope.exact("body", "publicKey", "signature")
        require(canonical(envelope).size <= 65536)
        val body = envelope.getJSONObject("body")
        body.exact("id", "protocolVersion", "authorId", "organizationId", "deviceId", "createdAt", "expiresAt", "intent", "maxHops", "type", "payload", "payloadHash")
        listOf("id", "authorId", "organizationId", "deviceId").forEach { require(UUID.fromString(body.getString(it)).toString() == body.getString(it)) }
        require(body.getInt("protocolVersion") == 1 && body.getString("intent") == "PUBLIC_RELIEF")
        require(body.getString("type") in listOf("REQUEST_CREATED", "REQUEST_UPDATED", "FIELD_PUBLISHED"))
        require(body.getInt("maxHops") in 1..8 && hops in 0..body.getInt("maxHops"))
        require(Instant.parse(body.getString("expiresAt")) > now)
        require(Instant.parse(body.getString("createdAt")) <= now.plusSeconds(300))
        require(hash(body.getJSONObject("payload")) == body.getString("payloadHash"))
        verify(body, envelope.getString("signature"), envelope.getJSONObject("publicKey"))
    }.getOrDefault(false)
    fun validReceipt(receipt: JSONObject, envelope: JSONObject, keys: JSONArray, now: Instant = Instant.now()): Boolean = runCatching {
        receipt.exact("body", "signature")
        val body = receipt.getJSONObject("body"); val event = envelope.getJSONObject("body")
        val key = keys.objects().first { it.getString("keyId") == body.getString("keyId") }
        require(key.getString("status") != "REVOKED" && now <= Instant.parse(key.getString("verifyUntil")))
        val recorded = Instant.parse(body.getString("recordedAt"))
        require(recorded >= Instant.parse(key.getString("signingFrom")) && recorded <= Instant.parse(key.getString("signingUntil")) && recorded <= now.plusSeconds(300))
        require(body.getString("eventId") == event.getString("id") && body.getString("payloadHash") == event.getString("payloadHash"))
        require(body.getString("signatureHash") == hash(envelope.getString("signature")))
        require(body.getString("status") in listOf("ACCEPTED", "PUBLISHED", "REJECTED", "CONFLICT", "INVALIDATED"))
        verify(body, receipt.getString("signature"), key.getJSONObject("publicKey"))
    }.getOrDefault(false)
}
