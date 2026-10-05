# Deployment, failover and release gates

Use portable Node/PostgreSQL/S3-compatible services. Railway staging is a separate private project/environment; it does not change the public GitHub repository. The reviewed initial patch creates one PostgreSQL 18 service/volume in Singapore and an empty API service. Provisioning requires explicit deployment approval. Staging a resource does not verify a domain, TLS, providers or backups.

Deploy `Dockerfile.api` for operations, a separate `API_PLANE=public` instance for sanitized reads, and `Dockerfile.media-worker` for media. Limit the worker container's CPU, memory and temporary storage, keep non-root execution and start with one replica/job. Production requires `MEDIA_PROCESSING_MODE=worker`; inline processing preserves local compatibility. PostgreSQL claims jobs with `FOR UPDATE SKIP LOCKED`, unique ten-minute leases and capped interrupted attempts. Lease comparisons explicitly use UTC, including when PostgreSQL's session timezone differs. FFmpeg has file/pipe-only protocols, no stdin, bounded allocations/threads/duration/output and a kill deadline; decoder diagnostics are excluded from API errors/logs. Lease-specific derivatives prevent an expired attempt overwriting newer output.

Originals/candidates remain private until human approval. Failed upload DB writes compensate by deleting the new original. Moderation locks posts/assets and checks readiness before publication. The worker repairs desired public visibility at startup and approximately every minute in bounded pages; exhausted interrupted jobs become visible failures. Database/object storage cannot commit atomically: a crash after a public copy can leave a temporary mismatch until repair. Bucket/CDN expiry, orphan inventory/retention/deletion and real-provider failure injection remain operational verification; this is not complete orphan reconciliation. Face blur is deferred and would not guarantee anonymity.

Staging uses `APP_ENV=staging` with separate config root, receipt signer, database, buckets and email. Fictional seeding requires `DEMO_MODE=true` and `STAGING_SEED_ALLOWED=true`; production seeding refuses. Keep the private receipt signer in managed secrets and the configuration root offline. Worker/public services need no private receipt key. Docker ignores and the publication scanner exclude environment files, private JWKs, keystores, builds and device dumps.

Production checks require HTTPS, durable signer/keyring/configuration, real Resend, ClamAV, private S3 credentials, non-demo data and worker mode. These are configuration checks, not verified provider connectivity. Redis failure preserves PostgreSQL allocation/expiry/outbox correctness but still needs operational alerts.

## Required live-release evidence

- Resend verified sender/domain and bounded fixture delivery, without tracking-token/email payload logs.
- Real private/public S3-compatible buckets: access policy, sanitized upload/download/hide, signed-original expiry, desired visibility repair and orphan retention.
- Real ClamAV: approved harmless scanner test, rejection/timeouts and fail-closed worker behavior. Fake protocol tests do not count.
- Redis authentication/TLS and outage recovery; queue health, SSE reconnect, outbox backlog, service health and load sizing.
- Hosted TLS on every signed endpoint; independent host/DNS failure, origin-scoped session behavior and authoritative restore before migration.
- Backups/PITR with an owner and retention policy; restore into a separate environment and verify quantities, audits, outbox and receipt continuity. Railway documents [backups](https://docs.railway.com/guides/postgres-backups-restores) and [PITR](https://docs.railway.com/volumes/point-in-time-recovery); no restore has been verified here.
- Physical native pairing/messages/calls/files/reconnect/range/battery, lifecycle execution, external security review and signing-key recovery.

## Failover

Restore one authoritative database and object state. Configure the approved active receipt identity on replacement operations. Check readiness, authorization/revocation and a fixture signed event/receipt before exposing it. Preserve idempotency, receipt and audit records. Publish an incremented root-signed configuration with old/new endpoint overlap through existing discovery sources. Monitor ingestion, rejection, duplicate paths and verification. Do not split writes across divergent databases.

Rollback deployment images without decreasing configuration versions; publish a new signed version when routing back. If discovery and cached configuration both expire, local drafts/messages/events remain useful but sync needs restored trusted discovery. Routine migration needs no APK update; root compromise may. No domain is promised unblockable.

HTTP logs use route templates/request IDs. Worker logs use asset IDs/durations/attempt counts. Carrier observations are pseudonymous per event, bounded and absent from public projections. Alert thresholds, app-adoption/sync-lag dashboards and incident/backup drills remain operator-owned release gates.
