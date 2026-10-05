# Saathi

**Here for each other.** Saathi coordinates verified humanitarian supply needs, guest contributions, incoming deliveries, and public field updates. A permanent request page tells people whether a shared request still needs help.

The web/API, browser offline foundation and native Android product use real PostgreSQL persistence. This is a demonstration installation, not a live relief operation. The native product has passed offline Nearby messages, interrupted file transfer and original-author event/receipt relay on a Samsung S24 and tablet. Browser communication remains a phone-testing preview. See [implementation status](docs/IMPLEMENTATION_STATUS.md) for the tested scope and remaining gates.

Source: [public GitHub repository](https://github.com/aakash-priyadarshi/Saathi). [Web/API CI](https://github.com/aakash-priyadarshi/Saathi/actions/workflows/ci.yml) runs database/browser checks and the isolated probe build. [Android product CI](https://github.com/aakash-priyadarshi/Saathi/actions/workflows/android-product.yml) builds/lints/tests the native product and publishes a seven-day staging artifact after its public trust/bootstrap variables and persistent QA signer are provisioned. The [transport probe](spikes/android/README.md) and its earlier [physical measurements](docs/benchmarks/samsung-s24-tablet-nearby.json) remain separate from product evidence.

## Local setup

The native Android product lives in [`apps/android`](apps/android), using Kotlin/Compose, Android Keystore, encrypted work and signed service discovery. Physical S24/tablet tests cover offline Nearby, local Wi-Fi hardware calls, browser files/messages and S24 → tablet → Windows-browser relay with reverse receipts. See [architecture](docs/android-architecture.md), [setup/signing](docs/android-release.md), [physical and automated evidence](docs/android-testing.md) and [deployment gates](docs/deployment-resilience.md). Hosted QA and broader connectivity/lifecycle verification remain in progress. The public `/download` page gains a direct APK action only when an approved HTTPS artifact is configured.

Requires Node.js 22+, pnpm 10, and Docker Desktop with its daemon running.

```powershell
Copy-Item .env.example .env
pnpm install
pnpm db:generate
pnpm build:packages
docker compose up -d
pnpm db:migrate
pnpm db:seed
pnpm dev
```

Open `http://localhost:3000`. API documentation: `http://localhost:4000/api/docs`. MinIO console: `http://localhost:9001`.

If Docker cannot start, run `pnpm db:local` in another terminal. This launches a real PostgreSQL 17 server on loopback, stores its database in `.data/postgres`, and leaves existing data intact. Set `STORAGE_PROVIDER=local` in `.env`; media is stored in `.data/media`. Continue with generate, build packages, migrate, seed and dev. Redis outages are supported through the database maintenance fallback; expect availability warnings. The embedded server and local storage are development conveniences and are disallowed for production.

Demo accounts: `volunteer@saathi.test`, `coordinator@saathi.test`, `admin@saathi.test`, and `medical@saathi.test`. Their initial password comes from `SEED_PASSWORD` (the example is `Saathi-demo-2026!`). Seeding is additive and refuses production; it does not reset existing passwords or quantities. The initial deadlines are relative to the first seed time. Expired demo requests stay archived; create new needs through the portal when returning later.

## Working flow

1. Admin creates a verified organization and appoints a coordinator.
2. Coordinator invites and approves volunteers after verifying identity and email, and designates public relief points.
3. Volunteer publishes a need. Guests see its remaining quantity and permanent `SAA-…` URL.
4. Guest reserves supplies without an account and receives a private tracking link. Reservations default to 20 minutes.
5. Guest arranges an external delivery and records its reference and ETA. Saathi neither orders nor takes payments, and has no SMS integration.
6. Volunteer marks the order seen, optionally in transit, then records actual quantities received. Partial deliveries are supported.
7. Completed needs leave the active feed. Their canonical pages and verification results explicitly say not to send more supplies.
8. Optional donor notifications are recorded in the same database transaction. Resend delivery retries independently.

Volunteers can publish text updates or upload photos/videos. Originals stay private. Images are re-encoded with Sharp; videos with FFmpeg. Sanitized media stays private until a coordinator approves the post; hiding removes its public derivatives. Automatic face detection/blur is not implemented: publishing guidance asks volunteers to check faces and personal information.

## Architecture and repository

```text
apps/web                  Next.js App Router, Tailwind, responsive public and team UI
apps/api                  NestJS REST, SSE, authorization, domain services, jobs
packages/database         Prisma schema, migrations, constraints, demo seed
packages/auth             Scrypt passwords, token hashing, TOTP verification
packages/config           Validated configuration and production startup checks
packages/types            Public response contracts
packages/validation       Strict shared Zod schemas
packages/protocol         Signed durable public relief envelopes and receipts
packages/ui               Accessible common UI feedback and verification
apps/android              Native Kotlin/Compose Saathi product
spikes/android            Separate synthetic transport measurement instrument
tests/unit                Domain and authentication tests
tests/integration         Real PostgreSQL workflow, authorization, media tests
tests/e2e                 Desktop/mobile browser workflows and captures
docs                      Architecture, deployment, security and privacy
```

The API owns business logic and authorization. Next.js proxies `/api` to NestJS, keeping browser sessions on the same origin. Quantities are protected by request-row locks and database bounds. A transaction-scoped advisory lock serializes each idempotency key; a replay returns the original response. The donor token and CSRF token are hashed in their main tables. Idempotency responses containing a private tracking token require restricted database access and retention policies.

SSE signals quantity changes; clients reload current server data. A 30-second polling fallback handles disrupted streams. Redis/BullMQ schedules maintenance; database-backed expiry and notification processing keep running if Redis is down. Database state remains primary.

## Configuration

Every setting is listed in [.env.example](.env.example). Never commit `.env`.

| Variable                                                 | Purpose                                                                    |
| -------------------------------------------------------- | -------------------------------------------------------------------------- |
| `NODE_ENV`                                               | development, test, or production; production enables strict startup checks |
| `DATABASE_URL`                                           | PostgreSQL connection URL including schema                                 |
| `REDIS_URL`                                              | BullMQ connection URL                                                      |
| `API_PORT`, `API_INTERNAL_URL`                           | API listen port and Next.js proxy destination                              |
| `WEB_ORIGIN`, `PUBLIC_URL`                               | Exact allowed browser origin and canonical public website URL              |
| `PLATFORM_NAME`, `NEXT_PUBLIC_PLATFORM_NAME`             | Runtime name and frontend metadata branding (default Saathi)               |
| `REQUEST_ID_PREFIX`                                      | Prefix for public request identifiers (default SAA)                        |
| `RESERVATION_MINUTES`, `SESSION_DAYS`                    | Reservation and session lifetimes                                          |
| `DEMO_MODE`, `SEED_PASSWORD`                             | Explicit demo disclosure and seed credentials; disabled in production      |
| `EMAIL_PROVIDER`                                         | `log` for local development, `resend` for real delivery                    |
| `RESEND_API_KEY`, `EMAIL_FROM`                           | Resend API credential and verified sender                                  |
| `STORAGE_PROVIDER`, `LOCAL_MEDIA_DIR`                    | `s3` or development-only `local`, and local media directory                |
| `S3_ENDPOINT`, `S3_REGION`                               | S3-compatible service configuration                                        |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY`                         | Storage credentials                                                        |
| `S3_PRIVATE_BUCKET`, `S3_PUBLIC_BUCKET`, `S3_PUBLIC_URL` | Separate private/original and public/approved derivative storage           |
| `MEDIA_SCAN_PROVIDER`                                    | `clamav` in production; `disabled` is local-only                           |
| `CLAMAV_HOST`, `CLAMAV_PORT`                             | ClamAV INSTREAM service                                                    |
| `FFMPEG_PATH`                                            | Override executable; `ffmpeg` selects the bundled binary                   |

`EMAIL_PROVIDER=log` records local notification attempts as `LOGGED`; it does **not** deliver an email. Resend requires a real key and verified sender. Production requires HTTPS, non-demo configuration, S3 credentials, Resend, and malware scanning. Local defaults are never production credentials.

## Verification

```powershell
pnpm test
pnpm test:integration
pnpm lint
pnpm typecheck
pnpm build
pnpm exec playwright install chromium
# With API and web running:
pnpm test:e2e
```

Integration tests migrate and truncate only the isolated `saathi_test` schema on a loopback database. The runner refuses non-local databases. E2E tests create clearly named demo needs in the development database and complete them; canonical history is intentionally retained. They must not run against live relief data. Tests cover simultaneous final-quantity claims, twenty parallel partial claims, concurrent idempotent retries, organization authorization, CSRF, session revocation, reservation expiry, partial deliveries, canonical archival, append-only audit protection, metadata removal, approved-media publication and withdrawal, and complete desktop/mobile contribution workflows.

Migrations are checked in. `pnpm db:migrate` applies them without prompting. Generate new migrations with Prisma during development; review the SQL, preserve quantity constraints and history triggers, and deploy with the migrator role. Never use `db push` against production.

## Offline and nearby

`SYNC_SIGNING_PRIVATE_JWK` is required in production for server receipt signatures. Keep it in the operator secret manager and preserve it through restarts/backups; see [deployment](docs/deployment.md) for exclusive key generation and rotation boundaries.

Open `/connectivity` while connected to save the public app shell and prepare an approved volunteer's signing device. `/offline` keeps timestamped public information, private drafts/media and signed relief events. `/nearby` provides manual invitation/reply pairing over a reachable local network, saved messages, accepted voice/video calls, consented small attachments and signed public-relief relay. Nearby delivery and canonical server publication have separate status indicators. Reservations require current server quantities.

Foreground automatic gateway upload is opt-in and carries public relief text only. Field media waits for an authenticated direct upload and the existing sanitization/moderation flow. No call recording, unrestricted internet gateway or automatic browser radio discovery is provided. Use operator HTTPS on phones; a plain LAN URL cannot substitute for the retained secure origin. The [Samsung S24 / Android 16 guide](docs/android-field-test-guide.md) and [device worksheet](docs/nearby-connectivity-device-tests.md) keep physical results separate from browser emulation.

Reproduce the independent desktop proof with `pnpm spike:nearby`. It uses two Chromium processes on one Windows host, no STUN/TURN/cloud signaling, verified bytes and synthetic camera/microphone. Results are in `docs/benchmarks/chromium-nearby.json`; they are not Android range or battery measurements. See the [official-source capability matrix](docs/nearby-connectivity-research.md) and [architecture decision](docs/adr/0001-durable-events-and-local-peer-sessions.md).

## Deployment and boundaries

The [Android transport instrument](spikes/android/README.md) opens directly in Android Studio. Its signed debug APK build and lint pass with the installed SDK/JBR. The owner's S24 (SM-S921B) and tablet (SM-X510), both Android 16, completed 20/20 offline pairing requests and exchanged generated messages and checksum-verified bytes without an active default network. Installed `0.3-probe` also passes rejection-prompt and later accepted-pair regression checks. Direct discovery and Aware availability are probes, not validated production data paths. No MCP is needed for Gradle or ADB. Physical browser/bridge, range, battery and lifecycle measurements remain required; see [the measured record](docs/benchmarks/samsung-s24-tablet-nearby.json).

GitHub Actions runs web/database/browser verification and a separate Android probe build/lint job on standard Ubuntu runners. The repository stays public, workflow permissions are read-only, runs are bounded and APK artifacts expire after seven days. Local credentials, raw device reports, signing keys and build caches are excluded from Git; public benchmarks contain selected measurements with personal identifiers omitted. `node scripts/check-publish.mjs` checks tracked files before publication.

See [deployment](docs/deployment.md), [architecture](docs/architecture.md), [security](docs/security.md), [privacy](docs/privacy.md), [threat model](docs/threat-model.md), and [offline protocol](docs/offline-protocol.md).

The native Android product, OS-backed identity, foreground Nearby discovery and local-Wi-Fi/browser adapter are implemented and tested at the scope in [Android verification](docs/android-testing.md). iOS, direct BLE/Wi-Fi Direct/Aware adapters and background hub policy remain unimplemented. Range, sustained battery and screen-lock behavior remain unverified. Magic links, passkeys, automatic face blur, email-forwarding parsers, distributed abuse throttling, pagination at larger scale, and independent load/security review are not part of the current tested milestone. See the [Android engineering handoff](docs/android-handoff.md) for the installed build and morning follow-up.
