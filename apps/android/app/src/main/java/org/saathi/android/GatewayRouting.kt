package org.saathi.android

import org.json.JSONObject
import java.time.Instant

/** Small, signed reachability leases gossip over already confirmed nearby sessions. */
internal data class GatewayRoute(
    val lease: JSONObject,
    val gatewayId: String,
    val displayName: String,
    val sequence: Long,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val hops: Int,
    val viaPeer: String?,
    val mediaPath: Boolean,
    val remainingBytes: Long,
)

internal object GatewayRouting {
    const val LEASE_SECONDS = 120L
    const val MAX_HOPS = 16
    const val MAX_ROUTES = 16
    const val MAX_REMAINING_BYTES = 5_000_000_000L

    fun lease(
        profile: JSONObject,
        sequence: Long,
        issuedAt: Instant,
        acceptsMedia: Boolean,
        remainingBytes: Long,
        signer: java.security.PrivateKey,
    ): JSONObject {
        require(sequence > 0 && remainingBytes in 0..MAX_REMAINING_BYTES)
        val body = obj(
            "v" to 1,
            "kind" to "SWARM_GATEWAY_LEASE",
            "gatewayId" to ChatProtocol.participant(profile),
            "profile" to profile,
            "sequence" to sequence,
            "issuedAt" to issuedAt.toString(),
            "expiresAt" to issuedAt.plusSeconds(LEASE_SECONDS).toString(),
            "serverReachable" to true,
            "acceptsText" to true,
            "acceptsMedia" to acceptsMedia,
            "remainingBytes" to remainingBytes,
        )
        return obj("body" to body, "signature" to Protocol.sign(body, signer))
    }

    /** The hop count and media path describe only this advertisement, not the signed gateway claim. */
    fun advertisement(route: GatewayRoute, outgoingMediaPath: Boolean = route.mediaPath) =
        obj("lease" to route.lease, "hops" to route.hops, "mediaPath" to outgoingMediaPath)

    fun receive(advertisement: JSONObject, viaPeer: String?, now: Instant): GatewayRoute? = runCatching {
        advertisement.exact("lease", "hops", "mediaPath")
        val advertisedHops = advertisement.getInt("hops")
        require(advertisedHops in 0 until MAX_HOPS)
        val route = parseLease(advertisement.getJSONObject("lease"), advertisedHops + 1, viaPeer, now)
        route.copy(mediaPath = route.mediaPath && advertisement.getBoolean("mediaPath"))
    }.getOrNull()

    fun fromStored(value: JSONObject, now: Instant): GatewayRoute? = runCatching {
        value.exact("lease", "hops", "viaPeer", "mediaPath")
        val via = value.optString("viaPeer").takeIf { it.isNotBlank() }
        parseLease(value.getJSONObject("lease"), value.getInt("hops"), via, now)
            .copy(mediaPath = value.getBoolean("mediaPath"))
    }.getOrNull()

    fun stored(route: GatewayRoute) = obj(
        "lease" to route.lease,
        "hops" to route.hops,
        "viaPeer" to route.viaPeer,
        "mediaPath" to route.mediaPath,
    )

    private fun parseLease(lease: JSONObject, hops: Int, viaPeer: String?, now: Instant): GatewayRoute {
        lease.exact("body", "signature")
        val body = lease.getJSONObject("body")
        body.exact(
            "v", "kind", "gatewayId", "profile", "sequence", "issuedAt", "expiresAt",
            "serverReachable", "acceptsText", "acceptsMedia", "remainingBytes",
        )
        require(body.getInt("v") == 1 && body.getString("kind") == "SWARM_GATEWAY_LEASE")
        require(hops in 0..MAX_HOPS)
        val profile = ChatProtocol.profile(body.getJSONObject("profile"), now)
        val gatewayId = ChatProtocol.participant(profile)
        require(body.getString("gatewayId") == gatewayId)
        val sequenceValue = body.get("sequence") as Number
        val sequence = sequenceValue.toLong()
        require(sequence > 0 && sequenceValue.toDouble() == sequence.toDouble())
        val issuedAt = Instant.parse(body.getString("issuedAt"))
        val expiresAt = Instant.parse(body.getString("expiresAt"))
        require(issuedAt <= now.plusSeconds(30) && expiresAt > now)
        require(expiresAt <= issuedAt.plusSeconds(LEASE_SECONDS))
        require(body.getBoolean("serverReachable") && body.getBoolean("acceptsText"))
        val acceptsMedia = body.getBoolean("acceptsMedia")
        val remainingBytes = body.getLong("remainingBytes")
        require(remainingBytes in 0..MAX_REMAINING_BYTES)
        require(Protocol.verify(body, lease.getString("signature"), profile.getJSONObject("body").getJSONObject("publicKey")))
        return GatewayRoute(
            lease = lease,
            gatewayId = gatewayId,
            displayName = profile.getJSONObject("body").getString("name"),
            sequence = sequence,
            issuedAt = issuedAt,
            expiresAt = expiresAt,
            hops = hops,
            viaPeer = viaPeer,
            mediaPath = acceptsMedia,
            remainingBytes = remainingBytes,
        )
    }
}
