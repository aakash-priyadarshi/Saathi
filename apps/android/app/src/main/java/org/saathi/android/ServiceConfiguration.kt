package org.saathi.android

import org.json.JSONObject
import java.net.URI
import java.time.Instant

object ServiceConfiguration {
    fun verify(value: JSONObject, root: JSONObject, environment: String, versionCode: Int, previous: JSONObject? = null, minimumVersion: Long = 1, now: Instant = Instant.now()): JSONObject {
        require(Protocol.canonical(value).size <= 32768) { "Service information is too large." }
        value.exact("body", "rootKeyId", "signature")
        require(value.getString("rootKeyId") == Protocol.hash(root) && Protocol.verify(value.getJSONObject("body"), value.getString("signature"), root)) { "Service information could not be verified." }
        val body = value.getJSONObject("body")
        body.exact("format", "environment", "version", "issuedAt", "expiresAt", "protocolVersions", "minimumAndroidVersionCode", "apiEndpoints", "publicUrl", "webOrigin", "receiptKeys", "features")
        require(body.getString("format") == "saathi-service-config-v1" && body.getString("environment") == environment) { "This service information belongs to another environment." }
        val version = body.getLong("version")
        require(version in minimumVersion..9007199254740991L) { "Older service information was refused." }
        require(previous == null || version > previous.getJSONObject("body").getLong("version") || Protocol.hash(value) == Protocol.hash(previous)) { "Conflicting service information was refused." }
        val issued = Instant.parse(body.getString("issuedAt")); val expiry = Instant.parse(body.getString("expiresAt"))
        require(issued <= now.plusSeconds(300) && expiry > now && expiry > issued && expiry <= issued.plusSeconds(31 * 86400)) { "Service information has expired or the phone clock needs attention. Saved work is safe." }
        require(body.getJSONArray("protocolVersions").length() == 1 && body.getJSONArray("protocolVersions").getInt(0) == 1 && body.getInt("minimumAndroidVersionCode") in 1..versionCode) { "Update Saathi to use this service." }
        val endpoints = body.getJSONArray("apiEndpoints").strings(); require(endpoints.size in 1..3 && endpoints.distinct().size == endpoints.size)
        (endpoints + body.getString("publicUrl") + body.getString("webOrigin")).forEach {
            val url = URI(it); require(it.length <= 300 && url.host != null && url.userInfo == null && url.query == null && url.fragment == null)
            require(url.scheme == "https" || (environment == "development" && url.scheme == "http" && url.host in listOf("localhost", "127.0.0.1", "::1"))) { "An unsafe service address was refused." }
        }
        val keys = body.getJSONArray("receiptKeys").objects(); require(keys.size in 1..20 && keys.count { it.getString("status") == "ACTIVE" } == 1 && keys.map { it.getString("keyId") }.distinct().size == keys.size)
        keys.forEach {
            it.exact("keyId", "publicKey", "status", "signingFrom", "signingUntil", "verifyUntil")
            require(it.getString("keyId") == Protocol.hash(it.getJSONObject("publicKey")) && it.getString("status") in listOf("ACTIVE", "RETIRED", "REVOKED"))
            val from = Instant.parse(it.getString("signingFrom")); val until = Instant.parse(it.getString("signingUntil")); require(from < until && until <= Instant.parse(it.getString("verifyUntil")))
        }
        val flags = body.getJSONObject("features"); flags.exact("nearby", "localCalls", "largeFiles"); flags.keys().forEach { flags.getBoolean(it) }
        return value
    }
}
