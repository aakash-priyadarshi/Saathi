# ADR 0003: Signed service configuration and replaceable infrastructure

Accepted 5 October 2026 for native Android; provider validation and hosted staging remain deployment gates.

## Decision

An APK contains only its environment, protocol/app version, a configuration-root **public** P-256 JWK and bootstrap configuration URLs. The offline operator root signs a bounded RFC 8785 canonical JSON document with established ECDSA/SHA-256. The API receives the signed document and root public key; it does not receive the root private key. Endpoint secrecy provides no authorization or trust boundary.

The strict document includes environment/version, issue/expiry times, supported protocols, minimum app version, one to three API origins, public/browser origins, signed feature flags and a receipt verification keyring. Exactly one active receipt key is required. Configurations expire within 31 days, reject excessive future issue time, bound download size to 32 KiB, require HTTPS outside loopback development and reject credentials/query/fragment in endpoint URLs. Unsupported protocols/apps, wrong environments, forged signatures, version rollback and same-version equivocation fail closed.

Android keeps the last valid signed configuration separately from encrypted version/clock high-water marks. It verifies cached configuration again before endpoint use. Temporary bootstrap outage can fall back to an unexpired cached configuration; expired configuration preserves local drafts/messages/events but stops trusting remote writes until refreshed. Wall-clock rollback beyond five minutes is rejected against persisted and launch-monotonic bounds. A device with a wholly manipulated clock/storage or compromised OS is outside this protection. Native origin-bound session cookies are not forwarded to a newly signed origin: authenticate/preparation at the new endpoint when needed.

Operational migration publishes an incremented configuration through existing bootstrap sources and old/new APIs, with an overlap longer than the expected offline interval. Verify health/TLS/signing continuity and restore the authoritative database before directing clients to another host. GETs can try configured endpoints; ordinary writes do not blindly fail over. Signed events retain durable IDs for explicit replay, and the server returns the one logical result. Operators must not point independent databases at the same active workload. Alternate bootstrap hosts must be independent enough to survive the outage being planned; one bootstrap plus a cached endpoint is not high availability.

Routine receipt-key rotation is root-authorized configuration, not an APK change. Root compromise is a distinct emergency: issue a newly trusted APK, revoke compromised distribution credentials as necessary and communicate recovery. Root rotation through a chain is intentionally not implemented. A privileged attacker who replaces the entire app or uses an unlocked device can still use its keys.

This borrows signed metadata, version/expiry and rollback principles from [TUF](https://theupdateframework.github.io/specification/v1.0.36/); it is **not** a full TUF implementation with delegated roles/thresholds. Canonicalization uses shared protocol code and the maintained [Java JCS implementation](https://github.com/erdtman/java-json-canonicalization); no custom signature algorithm is introduced.

## Receipt lifecycle and ownership

The key ID is SHA-256 of the canonical public JWK. Receipt signatures bind event ID, payload hash and original envelope signature hash. Keyring entries are ACTIVE, RETIRED or REVOKED with validity/signing windows. Retired keys verify old receipts only within their signed policy window; revoked keys verify none, including old cached receipts after updated policy is known. Offline clients cannot learn a revocation they have not received. A stored event receipt cannot be replaced by an older recorded receipt. The server reissues stored logical results under the active signer without reapplying the domain action; moderation/withdrawal can still invalidate publication.

1. Keep the configuration root offline under a named operator custodian and encrypted backup. Test recovery separately. The signing CLI refuses to generate production keys.
2. Keep the operational receipt private key in managed secrets, shared across all replicas/redeployments. Hosted staging/production refuse missing keys, missing keyring/configuration, invalid JWKs, mismatched private/public pairs and mismatched active key IDs. Development's exclusively created persistent fallback is development-only.
3. Generate the next receipt pair outside Git, add its ACTIVE entry and retire the prior key with a deliberate overlap window. Increment/re-sign configuration, install the new private signer and matching metadata as one deployment. Verify interoperability before completing rotation.
4. Emergency compromise: publish a higher-version configuration marking the old key REVOKED, deploy the new signer and re-fetch/reissue affected receipts. Audit logical outcomes rather than treating forged receipts as domain actions.

Railway initially provides operator-managed secrets. It does not change the trust model. Moving to another provider copies approved signed configuration and durable signer secrets through a secure operator channel. A managed asymmetric KMS can replace private-JWK signing through the same public P-256 identity/key IDs and raw P1363 wire signature; provider DER responses need standards-based conversion. KMS/HSM prevents ordinary private-key export but does not prevent an authorized compromised workload from requesting signatures. This milestone does not claim a tested KMS integration. See [AWS key selection](https://docs.aws.amazon.com/kms/latest/developerguide/symm-asymm-choose-key-spec.html).

## Public/operational boundary

`API_PLANE=public` starts a separate read-only Nest module with sanitized projections and health endpoints. Auth, device/sync, jobs, moderation, metrics and write routes are absent. `PUBLIC_API_INTERNAL_URL` lets the web proxy route reads there while existing authenticated/donation writes retain the operational API. Public-only DB credentials and cache/availability isolation must be configured by the operator; the combined developer deployment is not a security-isolated public infrastructure.

An asynchronously published separate database is deferred: current load does not justify replication/state machinery. `PublicReadService` is the migration-safe projection interface. Donations still require authoritative transactional checks. Independent public snapshots/CDN may later preserve reads during an operational outage. Browsers retain their existing same-origin/TLS code trust boundary and keyring cache; they do not gain the native APK's independently pinned root simply by adding a keyring.

## Verification

Shared adversarial tests reject modified/expired/rollback/wrong-environment configuration and revoked/out-of-window receipts. Native tests verify TypeScript signatures. Real PostgreSQL tests verify signer rotation/reissue, public-module isolation, duplicate carriers and canonical domain conflicts. Hosted endpoint migration, independent DNS/region outage, backup restoration and root-incident drills remain operational release gates.
