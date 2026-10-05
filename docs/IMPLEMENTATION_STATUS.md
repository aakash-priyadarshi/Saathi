# Implementation status

Updated 5 October 2026. Platform name: **Saathi**. The online web workflow, durable offline PWA and browser nearby preview are operational on this development host with real PostgreSQL persistence. Checkmarks mean implemented and locally verified; they do not imply a live deployment, physical-phone compatibility or completed production certification.

## Phase 1 — core web milestone

- [x] TypeScript pnpm/Turborepo monorepo, Prisma schema, versioned migrations, guarded additive demo seed
- [x] Password login, revocable hashed sessions, CSRF/origin checks, RBAC and organization boundaries
- [x] Admin organization creation, coordinator appointment, volunteer invitation/approval/suspension and public relief points
- [x] Request creation/editing, state derivation, optimistic versions, permanent canonical URL and ID verification
- [x] Transaction-locked partial/full guest reservations, concurrent idempotent retries, expiry and quantity constraints
- [x] External order references, ETA and private notes, incoming orders, confirmation, transit and partial receipt records
- [x] Completion removes needs from the active feed while preserving their canonical closed notices and audit history
- [x] Private guest tracking links with expiry, order status and cancellation of unplaced reservations
- [x] Transactional notification outbox, retries and Resend provider adapter; local attempts explicitly marked LOGGED
- [ ] Real donor email delivery verified with an operator-provided Resend key and verified sender
- [x] Public needs/completed/live feeds, search/category filters, volunteer dashboard and coordinator/admin tools
- [x] SSE updates with polling fallback and database maintenance when Redis is unavailable
- [x] Append-only audit triggers, important domain events, abuse reporting and moderation tools
- [x] Responsive light/dark web UI, keyboard labels, explicit loading/error/empty/closed states, fictional demo disclosure
- [x] Unit, real database integration, authorization, concurrency and desktop/mobile browser workflow checks
- [x] Workspace typecheck, lint and production builds

## Phase 2 — field reporting

- [x] Text updates and atomically linked structured requests
- [x] Authenticated photo/video uploads with file/decode bounds and discarded original filenames
- [x] Sharp image re-encoding and EXIF/device/GPS metadata removal
- [x] FFmpeg video re-encoding, metadata removal, duration/timeout/thread limits and thumbnails
- [x] Private originals and derivatives; coordinator approval before public media publication, withdrawal on hiding
- [x] Signed original review links, processing failure state and authenticated retry endpoint
- [x] S3/R2-compatible storage adapter and development filesystem fallback
- [x] ClamAV INSTREAM adapter and production refusal of disabled scanning
- [ ] Actual operator S3/R2, ClamAV and Resend integration tests
- [ ] Automatic face detection/blur, isolated media workers and durable storage/database reconciliation

## Phase 3 — resilient connectivity foundation and browser preview

- [x] Current official research, capability matrix, candidate comparison and architecture decision before browser transport implementation
- [x] Independent browser proof with offline signaling, direct data and synthetic audio/video; measured connection success, latency and throughput
- [x] Isolated Android transport instrument: authenticated Nearby pairing, RTT/checksum-transfer measurement, Direct discovery and Aware availability probes; debug APK build/lint/signature verification pass
- [x] Installable PWA shell, offline cold opening and bounded IndexedDB public snapshots, private drafts, messages, events and attachment parts
- [x] API reachability, saved-information age, dynamic capability explanations and distinct local / nearby / server / public delivery states
- [x] Approved volunteer preparation, nonextractable P-256 device keys, canonical signed envelopes and transport-independent public relief events
- [x] Original-author server authorization, durable deduplication, optimistic conflicts, signed receipts, device revocation and administrative invalidation
- [x] Manual browser pairing by invitation/reply QR, text or file on a reachable local network, with matching-code confirmation
- [x] Durable nearby text, consented resumable small attachments, accepted audio/video calls, bandwidth adaptation and stopped capture on disconnect
- [x] Multi-hop public relief store-and-forward with author absent, explicit internet-carrier consent and signed confirmation returning through later pairings
- [x] Private field originals stay on the author's device; direct authenticated upload attaches to the same published text update and restores moderation
- [x] Current-connection guards for donor reservations and contribution changes; private donor/auth responses excluded from the offline cache
- [ ] Physical Samsung S24 / Android 16 validation, second-phone compatibility, hotspot/range/battery/background/screen-lock measurements
- [ ] Native Android and iOS clients, automatic nearby discovery, native radio adapters and temporary native hub
- [ ] Browser-to-native and cross-platform transport proofs before selecting or advertising production native transports

The browser nearby feature is a **phone-testing preview**. It requires a previously prepared secure origin, a reachable local Wi-Fi/hotspot path and foreground use. It cannot enable system radios or discover arbitrary nearby phones. Native transport selection remains gated on physical proofs; no production BLE, Wi-Fi Direct, native Nearby or iOS adapter is claimed. The synthetic Android probe is separate from the production domain client.

## Validation recorded

- `pnpm test`: **11 unit tests passed**.
- `pnpm test:integration`: **29 integration tests passed**, using a separate `saathi_test` schema on PostgreSQL 17.
- Browser validation: **14 cases passed** across desktop and mobile-width projects, including the six original workflows and eight connectivity cases. The final full run passed 13; a premature cold-load assertion was corrected to wait for the saved message row, and both cold-load cases then passed on rerun.
- `pnpm lint`, `pnpm typecheck` and `pnpm build`: passed across all nine workspaces. The root TypeScript check also covers the test sources.
- Image/video sanitization, publication/withdrawal, concurrent final-quantity allocation, twenty competing partial contributions, duplicate idempotent retries, CSRF, cross-organization access, session revocation, volunteer approval/suspension and audit constraints have automated coverage.
- Offline cold opening, storage persistence, private-cache exclusion, local message/file/call exchange, interrupted attachment resumption and A-to-B-to-C publication with reverse signed receipts have browser coverage. Calls use synthetic camera/microphone sources; mobile-width checks run on Windows Chromium and are not Android hardware tests.
- The local peer proof ran three pairings between independent Chromium processes on one Windows host with HTTP blocked and no cloud signaling or STUN/TURN. Range and battery measurements are absent. See `benchmarks/chromium-nearby.json` and `nearby-connectivity-device-tests.md`.
- Desktop/mobile screenshots were inspected in light and dark themes. Physical-device results remain **NOT RUN** in `android-field-test-guide.md`.
- The pinned Android wrapper build, lint and debug signature verification passed. A single Android 17/API 37 emulator passed installation/launch, permission denial/retry, advertising, foreground-stop and local JSON-export smoke checks; unpaired measurement controls remained disabled. See `benchmarks/android-emulator-smoke.json`. The owner chose to connect the S24 later; native pairing, latency, throughput, range and battery are unmeasured.
- Docker/CI definitions are included. The local Docker daemon was unavailable, so the Docker image and remote CI execution are **unverified**. Native local PostgreSQL was used instead.
- Local Redis was unavailable; database maintenance and reservation fallback paths ran. Real Redis-backed worker execution remains a deployment check.

## Later phases and release gates

- [ ] Native Android/iOS app, OS-secured credentials, native transport proofs and platform background policy
- [ ] Physical range, battery, reconnection, screen-lock and battery-saver tests; validate the second Android phone before compatibility claims
- [ ] MFA enrollment, encrypted secret provisioning, recovery codes, password reset and production operator bootstrap UI
- [ ] Independent security/load review, least-privilege infrastructure, distributed rate limiting and media process isolation
- [ ] Cursor pagination and shared SSE polling for larger installations
- [ ] Production backup/restore drill, retention policy, alerting, real provider integration and deployment-specific TLS/CSP verification

The implemented browser protocol is documented in `offline-protocol.md`. Research and native release gates are in `nearby-connectivity-research.md`, `nearby-connectivity-architecture.md` and `android-field-test-guide.md`. See `deployment.md`, `security.md` and `threat-model.md` for the remaining live-service gates.
