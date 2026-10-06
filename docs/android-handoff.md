# CJP Swarm Android engineering handoff — 6 October 2026

The CJP Swarm product is built and signature-verified. Earlier milestone builds were installed and tested on the authorized Samsung S24 and tablet; the final UI build is verified on the tablet and emulator because the phone was disconnected for the owner's use. It displays SWARM by CJP and uses the existing native Kotlin/Compose application in `apps/android`; the research probe remains separate in `spikes/android`. This is a fictional local development preview, not a production or hosted staging deployment. Private chat is disabled in release pending independent cryptographic review.

## Changes

Native needs/completed/field/verification, contributions, volunteer preparation, drafts, signed updates, deliveries, saved work, nearby messages, files and local calls reuse the existing API/domain protocol. Android Keystore identities and encrypted private records preserve prepared work. Same-account recovery preserves that identity; replacement account IDs cannot inherit saved work. Signed service discovery supports routine endpoint and receipt-key rotation without replacing the APK. Hosted signing fails closed; sanitized public handlers, carrier observations, an isolated leased media worker and explicit guarded orphan maintenance harden the backend. Persistent external QA signing and a model-selecting non-destructive installer are provided.

Updates, Chats, Nearby, Needs and More now separate conversation from authoritative relief publishing. Stable DMs, open/invite-only channels, signed membership/invites/receipts, durable encrypted attachments, authorized history/sync, references, foreground mentions/notices and mute/block/report extend the existing transports. Familiar native calls appear only on a compatible live DM path; huddles remain unavailable. The [chat architecture](chat-channel-architecture.md) explains the static-key encryption, bounded retention/quotas and revocation limits. Chat membership confers no relief RBAC authority.

Coordinator/admin management and field-photo authoring remain in the web portal. Direct BLE/Direct/Aware/iOS and background relaying/calls are unsupported. Physical range, sustained battery, degraded-network and lock/reboot behavior remain unverified.

## Installed artifact

| Field                | Value                                                                                                       |
| -------------------- | ----------------------------------------------------------------------------------------------------------- |
| Package/build        | `org.saathi.android.dev`, debug                                                                             |
| Version              | `1.0.0-dev`, versionCode 1                                                                                  |
| Android floor/target | min SDK 26 / target SDK 36; compile SDK 37.0                                                                |
| Architecture         | Kotlin/Compose, Material 3, Android Keystore, encrypted SQLite records, native transport adapters           |
| APK                  | `C:\project-bussiness\Saathi\apps\android\app\build\outputs\apk\debug\app-debug.apk`                        |
| Size                 | 76,301,488 bytes                                                                                            |
| APK SHA-256          | `ed7cc9933ed36fee5ebd1595039c37da586807c8899f95967e1a56ffd2b3d687`                                          |
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

## Verified evidence

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

The tablet's original stay-awake-while-charging value (`0`) was restored and verified. Fixture activity/peer/media sessions ended, ordinary development storage scope was reopened, and fixture-only USB reverse ports 4009/4010 were removed on tablet/emulator. The development API reverse at 4000 remains available. The phone was disconnected before its temporary charging-awake override could be restored; phone restoration is unverified. Reconnect it with USB debugging authorized, then run the locally saved restoration helper from the repository root. The owner's ten-minute timeout was unchanged:

```powershell
node .data/tooling/device-awake.mjs restore-phone
```

The tablet-scoped Windows UDP rule still needs removal in **Administrator PowerShell**:

```powershell
& 'C:\project-bussiness\Saathi\.data\tooling\Allow-SaathiFixture.ps1' -Action Disable
```

Attended tests remain: three-native-peer chat (only two Android devices available), full new-chat internet → offline nearby → internet transition, actual voice recording/video attachment transfer, automatic-discovery end-to-end behavior, Android browser interoperability, no-internet hotspot/LAN calls, permissions, lock/reboot/battery saver, degraded calls, range and sustained battery. API 26 physical compatibility and larger attachment limits also need validation. New private chat is not implemented in the browser bridge; its existing relief/message path is preserved.

Railway's reviewed staging patch is not deployed; explicit deployment approval remains pending. Hosted TLS/bootstrap/configuration and a persistent QA signer are not provisioned, so no hosted QA APK/download is claimed. Real Resend/S3/ClamAV/Redis, deployed decoder containment, backup/restore, operator retention/alerts, external security and load review remain production blockers. Filesystem orphan tests and simulated S3 pagination do not verify real bucket policy. [Deployment and failover](deployment-resilience.md) gives the precise release gates.
