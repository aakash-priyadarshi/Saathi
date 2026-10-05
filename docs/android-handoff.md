# Android engineering handoff — 5 October 2026

The actual Saathi product is built, signature-verified and installed on the authorized Samsung S24 and tablet. It is a native Kotlin/Compose application in `apps/android`; the isolated research probe remains in `spikes/android`. This is a fictional local development build, not a production or hosted staging deployment.

## Changes

Native needs/completed/field/verification, contributions, volunteer preparation, drafts, signed updates, deliveries, saved work, nearby messages, files and local calls reuse the existing API/domain protocol. Android Keystore identities and encrypted private records preserve prepared work. Same-account recovery preserves that identity; replacement account IDs cannot inherit saved work. Signed service discovery supports routine endpoint and receipt-key rotation without replacing the APK. Hosted signing fails closed; sanitized public handlers, carrier observations, an isolated leased media worker and explicit guarded orphan maintenance harden the backend. Persistent external QA signing and a model-selecting non-destructive installer are provided.

Coordinator/admin management and field-photo authoring remain in the web portal. Direct BLE/Direct/Aware/iOS and background relaying/calls are unsupported. Physical range, sustained battery, degraded-network and lock/reboot behavior remain unverified.

## Installed artifact

| Field                | Value                                                                                                       |
| -------------------- | ----------------------------------------------------------------------------------------------------------- |
| Package/build        | `org.saathi.android.dev`, debug                                                                             |
| Version              | `1.0.0-dev`, versionCode 1                                                                                  |
| Android floor/target | min SDK 26 / target SDK 36; compile SDK 37.0                                                                |
| Architecture         | Kotlin/Compose, Material 3, Android Keystore, encrypted SQLite records, native transport adapters           |
| APK                  | `C:\project-bussiness\Saathi\apps\android\app\build\outputs\apk\debug\app-debug.apk`                        |
| Size                 | 72,133,511 bytes                                                                                            |
| APK SHA-256          | `e33ec2f45237137802fcda4a377406c9b7ece0ea69bf794e58c7889648b22d06`                                          |
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

Local checks: 17 unit tests, 33 real PostgreSQL integration tests, 14 browser regression cases, typecheck/lint/build, five native JVM tests and Keystore instrumentation. The [testing record](android-testing.md) and [sanitized measurements](benchmarks/android-product-devices.json) preserve exact scenario limitations. The finish verdict resolved four named native UI findings; it is not whole-surface or production certification.

Core source commit: `264d4050857740d4cbac6168805ee4cc71106bc3`, with successful [web/API](https://github.com/aakash-priyadarshi/Saathi/actions/runs/37341532531) and [Android](https://github.com/aakash-priyadarshi/Saathi/actions/runs/37341532530) runs. The final follow-up commit/run is reported with the handoff message. [Current web/API runs](https://github.com/aakash-priyadarshi/Saathi/actions/workflows/ci.yml) and [current Android runs](https://github.com/aakash-priyadarshi/Saathi/actions/workflows/android-product.yml) show the public branch's latest validation.

## Morning and production gates

Device stay-awake-while-charging settings were restored after testing; the owner's ten-minute timeout was unchanged. Camera/microphone capture and peer sessions ended. Fixture-only USB forwards were removed. The tablet-scoped Windows UDP rule still needs removal in **Administrator PowerShell**:

```powershell
& 'C:\project-bussiness\Saathi\.data\tooling\Allow-SaathiFixture.ps1' -Action Disable
```

Attended tests remain: Android browser interoperability, no-internet hotspot/LAN calls, permissions, lock/reboot/battery saver, degraded calls, range and sustained battery. API 26 physical compatibility and larger attachment limits also need validation.

Railway's reviewed staging patch is not deployed; explicit deployment approval remains pending. Hosted TLS/bootstrap/configuration and a persistent QA signer are not provisioned, so no hosted QA APK/download is claimed. Real Resend/S3/ClamAV/Redis, deployed decoder containment, backup/restore, operator retention/alerts, external security and load review remain production blockers. Filesystem orphan tests and simulated S3 pagination do not verify real bucket policy. [Deployment and failover](deployment-resilience.md) gives the precise release gates.
