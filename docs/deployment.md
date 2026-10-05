# Deployment

The source includes a Docker build and CI workflow. They are provided for operator deployment, not a claim that a live deployment has occurred. The Docker daemon on the development host was unavailable; the image build has not been executed here. PostgreSQL integration tests ran with the native local PostgreSQL fallback.

The [public repository's CI](https://github.com/aakash-priyadarshi/Saathi/actions/runs/37272841824) passed lint, type checks, unit/database/browser tests, production workspace builds and Android probe build/lint for code commit `405c58c`. It runs on standard Ubuntu runners with PostgreSQL 17 and Redis 7 and retains the synthetic Android debug APK for seven days. Production image startup and actual S3/R2, Resend, ClamAV and physical-phone checks remain release gates.

## Required services

Use managed PostgreSQL with backups and point-in-time recovery, Redis for maintenance scheduling, separate S3/R2 private and public buckets, a verified Resend sender, and a reachable ClamAV service. Serve the web app over TLS behind one canonical origin. Route `/api` through Next.js to the internal API. Do not expose the private API port, PostgreSQL, Redis or storage admin consoles to the internet. Provide a trusted ingress with per-client distributed throttling and upload/request-body limits.

Set production environment variables through a secret manager. Do not bake `.env` into the image. `DEMO_MODE=false`, `EMAIL_PROVIDER=resend`, `STORAGE_PROVIDER=s3` and `MEDIA_SCAN_PROVIDER=clamav` are mandatory. Configure `PUBLIC_URL` and `WEB_ORIGIN` identically to the canonical HTTPS origin. `API_INTERNAL_URL` points to the API's internal address and is baked into Next.js rewrites at build time. The Docker build defaults it to `http://api:4000`; override it with `--build-arg API_INTERNAL_URL=http://your-api-service:4000` when needed. `NEXT_PUBLIC_PLATFORM_NAME` is also a Docker build argument; `PLATFORM_NAME` is runtime branding. Set them together.

Production refuses known development secrets and insecure settings. The config layer requires explicit credentials; it does not create cloud accounts, DNS records, buckets, verified senders or scanner services. Configure the public bucket only for approved derivative access. Originals must have no public policy. Give the processor create/read access to private objects and limited public derivative access. Use cache-control policies that let moderation withdraw content.

## Build and release

```sh
pnpm install --frozen-lockfile
pnpm db:generate
pnpm build:packages
pnpm lint
pnpm typecheck
pnpm test
pnpm test:integration # local isolated test database, not production
pnpm build
docker build -t saathi:release .
```

Run schema migration with a dedicated migrator role before deploying the runtime. Run separate web and API containers from the image, overriding the web command to `pnpm --filter @saathi/web start`. The API defaults to `pnpm --filter @saathi/api start`. Allow graceful shutdown and sufficient health-check startup time. Restrict `/api/docs` at ingress if internal API discovery is undesirable. `/health` is process liveness; `/ready` checks the database. `/metrics` requires an authenticated admin and should be scraped through a restricted credential/session integration.

The production application DB role must not own schemas or tables, disable triggers, truncate history, or run migrations. Grant it SELECT/INSERT/UPDATE on operational tables, SELECT/INSERT on AuditEvent, and no DELETE on ReliefRequest. Test its permissions explicitly after provisioning. Audit triggers supplement least privilege; they cannot protect history from a superuser.

Bootstrap the first production admin using an operator-controlled database provisioning procedure: create a scrypt password with packages/auth, a verified email and ADMIN role. Production seeding intentionally refuses to run. Self-service MFA secret encryption/enrollment, recovery and password-reset flows are still incomplete; resolve those before onboarding live teams. There is no default production admin password.

## Failure recovery

Database transactions commit receipt records and outbox notifications together. Monitor pending notification counts and lastError; retries use provider idempotency keys. An email outage must never roll back a receipt. Redis failure falls back to database maintenance and lazy reservation expiry. All expiry writes take request-row locks. Restore the authoritative database before allowing writes after disaster recovery, and reconcile storage assets against MediaAsset moderation state.

Media originals survive a processing error; the authenticated retry endpoint can process them again. Failed processing is visible in admin metrics. Current media processing happens in the API process with bounded files/time/threads; production should isolate it in a worker/container with strict CPU/memory/network budgets and add a durable reconciliation queue for storage/database partial failures. The notification job holds a database claim lock during provider delivery, so provider timeouts and connection-pool sizing must be monitored under load.

## Release gates still owed

- Independent security and abuse review, secret/encrypted-backup practices and incident response.
- MFA enrollment/encrypted provisioning/recovery, password reset and production operator bootstrap UI.
- Distributed edge rate limiting with correctly trusted proxy identity; never blindly trust arbitrary forwarded headers.
- S3/R2 and ClamAV integration against the actual operator configuration; real Resend delivery verification.
- Load/soak tests and process isolation for FFmpeg; queue/reconciliation and orphan cleanup.
- Cursor pagination, SSE polling consolidation and domain metrics for larger installations.
- Backup restore drill, alerting, data retention policy, organization verification procedure and moderator staffing.
- Docker image execution on the deployment host and deployment-specific TLS/CSP testing.

## Offline signing and Android testing

Set `SYNC_SIGNING_PRIVATE_JWK` to an operator-owned P-256 private JWK in the production secret manager. `node scripts/generate-sync-key.mjs .data/production-sync-key.json` creates one exclusively without printing it; do not commit the file. Windows operators must restrict its ACL (POSIX mode bits alone do not provide Windows access control). Back up this stable authority key securely with the database. Losing or changing it breaks offline receipt trust; production rotation needs a versioned trust/keyring migration before rollout. Development alone can create a persistent key beside local media storage.

Apply migration `202610050003_offline_events` with the normal migrator role. Preserve offline event, receipt and original-author audit records. Configure secure exact origins, cache-control and service-worker scope through the actual reverse proxy. Verify installed PWA offline cold opening and stale donor safeguards against that deployment.

The owner's first test phone is Samsung S24 / Android 16. Follow [its test guide](android-field-test-guide.md) and record measured results in [the worksheet](nearby-connectivity-device-tests.md). Native mobile/radio adapters, cross-platform bridges, automatic discovery and background execution have separate implementation and physical rollout gates. No hardware support is inferred from localhost or a mobile browser viewport.
