# CJP Swarm Android engineering handoff — 6 October 2026

The current CJP Swarm development/QA milestone extends current main baseline `8a9d99d` in the existing Kotlin/Compose application. Both the S24 and tablet are available for this milestone; their current store/UI and physical QR checks pass. The [unified milestone](swarm-unified-milestone.md) and [artifact evidence](benchmarks/android-unified-community.json) distinguish current verification from earlier call/transport proof builds. This remains a fictional local development preview. Private chat is disabled in release pending independent cryptographic review.

## Changes

Native needs/completed/field/verification, contributions, volunteer preparation, drafts, signed updates, deliveries, saved work, nearby messages, files and local calls reuse the existing API/domain protocol. Android Keystore identities and encrypted private records preserve prepared work. Same-account recovery preserves that identity; replacement account IDs cannot inherit saved work. Signed service discovery supports routine endpoint and receipt-key rotation without replacing the APK. Hosted signing fails closed; sanitized public handlers, carrier observations, an isolated leased media worker and explicit guarded orphan maintenance harden the backend. Persistent external QA signing and a model-selecting non-destructive installer are provided.

Updates, Chats, Nearby, Needs and More now separate conversation from authoritative relief publishing. Stable DMs, open/invite-only channels, signed membership/invites/receipts, durable encrypted attachments, authorized history/sync, references, foreground mentions/notices and mute/block/report extend the existing transports. Familiar native calls appear only on a compatible live DM path; huddles remain unavailable. The [chat architecture](chat-channel-architecture.md) explains the static-key encryption, bounded retention/quotas and revocation limits. Chat membership confers no relief RBAC authority.

Verified-volunteer coordinator/admin management remains in the web portal. Ordinary participant field-photo/video authoring, temporary Nearby Help and consented public-media forwarding are now native; reports stay unverified and pending approval. Direct BLE/Direct/Aware/iOS and background relaying/calls are unsupported. Physical range, sustained battery, degraded-network and lock/reboot behavior remain unverified.

## Installed artifact

| Field                | Value                                                                                                       |
| -------------------- | ----------------------------------------------------------------------------------------------------------- |
| Package/build        | `org.saathi.android.dev`, debug                                                                             |
| Version              | `1.0.0-dev`, versionCode 1                                                                                  |
| Android floor/target | min SDK 26 / target SDK 36; compile SDK 37.0                                                                |
| Architecture         | Kotlin/Compose, Material 3, Android Keystore, encrypted SQLite records, native transport adapters           |
| APK                  | `C:\project-bussiness\Saathi\apps\android\app\build\outputs\apk\debug\app-debug.apk`                        |
| Size                 | 77,215,868 bytes                                                                                            |
| APK SHA-256          | `cb98308fe909ba2d80329f3808ab90113e5fd9e8da1fa7a8cb7563beed1539c2`                                          |
| Certificate SHA-256  | `bfccf64b1323649f43c8dc2db8ff05aee9c71856ef1f0a60221768718a992310`                                          |
| Environment          | Separate fictional development schema; API at `127.0.0.1:4000` through USB reverse; web at `localhost:3000` |

From the repository root:

```powershell
.\scripts\android-install.ps1 -Model SM-S921B -BuildType debug -ReverseDevelopmentApi
.\scripts\android-install.ps1 -Model SM-X510 -BuildType debug -ReverseDevelopmentApi
# Exact direct ADB form; substitute the authorized serial without publishing it:
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <authorized-device> install -r 'C:\project-bussiness\Saathi\apps\android\app\build\outputs\apk\debug\app-debug.apk'
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <authorized-device> reverse tcp:4000 tcp:4000
```

Fixture volunteer: `android-volunteer@saathi.test`. The password comes from the local environment and is neither published nor embedded in the APK. Preserve the configuration-root directory and debug signing certificate for updates. Uninstalling or changing the certificate can lose private app work. [Release instructions](android-release.md) describe external QA/production signing and conditional seven-day CI distribution.

## Current unified evidence

Continued current main baseline `8a9d99d`. Five-role channel governance, key-free approval invitations, announcement threads, temporary Nearby Help, ordinary unverified photo/video reports, consented public-media relay, local QR scanning, durable theme/profile/data controls and the supplied animated brand lockup extend the incumbent native runtime. [Unified milestone](swarm-unified-milestone.md) records behavior and qualification limits; [current evidence](benchmarks/android-unified-community.json) binds verification to exact artifacts.

Local checks pass 33 shared/API unit, 54 PostgreSQL integration, 14 browser and 16 native JVM cases, plus native build/lint. The current APK passed 13 storage/UI/governance/community cases on each Samsung Android 16 device (S24 49.879 seconds; tablet 31.065 seconds). Physical camera-to-tablet QR scanning was confirmed on the preceding build and the recipient showed a pending approval request; its separate artifact hash is retained in the evidence record. An actual tablet ANR exposed competing encrypted-store refreshes; snapshots now load through one conflated background reader, with an interaction regression during 100 refresh callbacks. Nearby relay evidence is recorded separately; store/QR results alone do not establish gateway or radio success.

The final APK completed the two-device Nearby community fixture: tablet author 63.267 seconds, S24 carrier 63.908 seconds. It passed fresh-key approval, thread/lock authorization, ban after rename and future-key exclusion, Help offer/assignment/resolution, sanitized public-photo hash, relay-OFF refusal, consenting gateway text/media acceptance with original authorship, withdrawal and block. The tablet had no validated default internet and the S24 did; USB still supplied fixture configuration/API coordination. This does not prove author-absent relay, three-native-peer forwarding, full internet-loss transitions or video radio transfer. The [radio measurement](benchmarks/android-community-nearby.json) preserves the exact APK and barrier scope.

## Earlier communication/foundation evidence

Physical S24 (SM-S921B) and tablet (SM-X510), both Android 16/API 36:

- No validated internet: native Nearby matching-code pairing, durable messages/ACK both ways, interrupted 4 MiB resume/hash, urgent event priority, author-absent publication and signed receipt return.
- Shared LAN: the same data scenarios plus eight-second hardware voice/video receipt checks on both devices. This run had internet available; it is not an internet-loss call proof.
- Physical tablet ↔ Windows Chromium: messages and consented 1 MiB files both ways, original native event and reconnect. This is not Android browser coverage.
- S24 → tablet → Windows Chromium → API: unchanged author envelope at two peer hops with author disconnected, then signed receipt returned browser → tablet → S24.
- Encrypted-storage and signed API/idempotent lost-response recovery tests on both devices and an API 37 emulator. Same-account recovery and replacement-ID refusal also passed. Emulator draft survived actual force-stop; physical reboot is unverified.
- New physical two-device Nearby chat: encrypted stable DM and signed Delivered/Read, server/radio deduplication, owner-approved open join, recipient-only invitation/replay, private channels, generated photo/synthetic AAC, interrupted encrypted 4 MiB resume, ciphertext server upload/download, removal and block. Both devices had validated internet; complete chat internet-loss transition remains unverified.
- Later native refinement preserves the local-Wi-Fi hardware call/data and Windows-browser original-event/receipt regression. Exact proof-build hashes differ from the installed final UI correction build; no earlier result is represented as a full final-APK private-chat/radio certification.

Local checks: 26 unit tests, 45 real PostgreSQL integration tests, all 14 browser cases, typecheck/lint/build and nine native JVM cases. The final APK passed five Keystore/chat-storage/conversation-viewport instrumentation cases on the tablet (21.515 seconds) and emulator (14.595 seconds). Long history stays unread until visible, incoming messages preserve a deliberate history view, latest-message navigation sends one Read receipt, and own sends stay visible. The phone was unavailable for this final viewport check. The [testing record](android-testing.md), [chat measurements](benchmarks/android-chat-nearby.json) and [retained call/relay regression](benchmarks/android-swarm-wifi-regression.json) preserve exact scenario/artifact limitations. The new native finish verdict is separate from the earlier four-finding verdict; neither certifies production readiness.

This milestone extends `d60cdaf`; implementation source is [2a7f906](https://github.com/aakash-priyadarshi/Saathi/commit/2a7f906a37478e549ca580c5c428056c4bdaa6b1). Both [web/API verification](https://github.com/aakash-priyadarshi/Saathi/actions/runs/37396036178) and [Android product verification](https://github.com/aakash-priyadarshi/Saathi/actions/runs/37396035822) passed on that exact source commit, including 26 unit, 45 PostgreSQL integration, all 14 browser cases, native build/lint/protocol tests and emulator instrumentation. Final design/test/handoff documentation follows in a documentation-only commit; no implementation changes follow these green runs. Hosted QA distribution stays gated on trust/bootstrap and a persistent external signer.

## Morning and production gates

Both Samsung devices' original stay-awake-while-charging values were restored and verified after the final fixture. The owner's ten-minute screen timeout was unchanged. Fixture activity and peer/media sessions ended, ordinary development storage was reopened, and fixture-only USB reverse ports 4009/4010 were removed on both devices and the emulator. The development API reverse at 4000 remains available.

The tablet-scoped Windows UDP rule still needs removal in **Administrator PowerShell**:

```powershell
& 'C:\project-bussiness\Saathi\.data\tooling\Allow-SaathiFixture.ps1' -Action Disable
```

Attended tests remain: three-native-peer chat (only two Android devices available), full new-chat internet → offline nearby → internet transition, actual voice recording/video attachment transfer, automatic-discovery end-to-end behavior, Android browser interoperability, no-internet hotspot/LAN calls, permissions, lock/reboot/battery saver, degraded calls, range and sustained battery. API 26 physical compatibility and larger attachment limits also need validation. New private chat is not implemented in the browser bridge; its existing relief/message path is preserved.

Railway's reviewed staging patch is not deployed; explicit deployment approval remains pending. Hosted TLS/bootstrap/configuration and a persistent QA signer are not provisioned, so no hosted QA APK/download is claimed. Real Resend/S3/ClamAV/Redis, deployed decoder containment, backup/restore, operator retention/alerts, external security and load review remain production blockers. Filesystem orphan tests and simulated S3 pagination do not verify real bucket policy. [Deployment and failover](deployment-resilience.md) gives the precise release gates.
