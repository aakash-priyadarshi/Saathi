# Android product milestone work record

Started 5 October 2026 from `main` commit `f0bd84e`; pull was up to date. This record describes completed engineering and remaining gates; it is not a production certification.

## Decisions

The Android product is Kotlin/Compose in `apps/android`. Native radio, lifecycle, media and Keystore ownership remain in one runtime, while the existing TypeScript API owns all domain rules. `spikes/android` is preserved as a separate research instrument. Android SDK 26 is the compatibility floor; physical evidence currently covers Android 16 only.

Retain Saathi's paper/forest identity, Lora headings and Manrope controls through native Material 3 roles and adaptive navigation. No WebView shell. Public text flows, contribution/verification, volunteer work and saved/nearby flows use the existing API and signed event protocol. Coordinator/admin tools and field-photo authoring remain in the web portal.

Use standard P-256 canonical signatures, Android Keystore and AES-GCM records. Signed configuration permits ordinary backend/receipt-key rotation while the offline root remains separate from hosted receipt signing. Root compromise can require an APK update. Endpoint secrecy is never a security boundary.

Optional GMS Nearby is the native byte transport. Manual WebRTC on an existing reachable local network supplies the non-GMS adapter and call/browser path. No direct BLE/Direct/Aware/iOS implementation is advertised. Explicit foreground discovery, consent, bounded queues/storage and call ownership limit resource/privacy exposure.

Use a separate staging environment and configuration root. Railway initial PostgreSQL/empty-API resources are reviewed and staged, awaiting explicit deployment approval. No hosted staging domain/TLS/provider/restore result is claimed. Production keys are not generated in this task. Automatic face blur is deferred; it would not guarantee anonymity.

## Implemented

- Shared strict signed service configuration, monotonic versions, expiry/clock/equivocation checks, receipt active/retired/revoked keyring, and independently fail-closed hosted signer.
- Sanitized public API module omitting operational handlers; migration-safe publication boundary and independent web proxy destination. A separate materialized read database remains a deployment evolution.
- Event-scoped pseudonymous carrier observations without duplicate domain effects or public peer identifiers.
- Non-root API/worker images, PostgreSQL leased media claims, bounded FFmpeg decoding, moderation readiness locks and paginated visibility repair. Explicit orphan maintenance defaults to dry-run, protects referenced/active/recent objects and requires an operator prune command. Real provider policy, version retention and restore remain open.
- Native encrypted records and attachment chunks, non-exportable account-scoped identities, backup exclusion, registration/rotation/revocation and explicit private cleanup.
- Original signed events saved before transmission, atomic public snapshots, signed receipt checks, drafts and offline message composition, consented resend to the currently paired person.
- Durable ordinary API operations save original body/idempotency key before a write, preserving retry identity across uncertain responses and restart. No automatic new donation intent follows a lost response.
- Nearby/local-Wi-Fi adapters, bounded priority event/message/file scheduling, resumable 16 MiB native / 1 MiB browser attachments, consent-based media and foreground cleanup. Verified files export through Android's document picker; private removals require explicit confirmation.
- Environment-specific IDs, external production signing guard, optional persistent QA signer, HTTPS staging-build guard, download entry and Android build/lint/test/artifact CI. Public CI distribution requires the persistent QA signer and never exposes it to pull requests.

## Verification and corrections

17 shared/API unit tests, 33 PostgreSQL integration tests, 14 complete Playwright cases, lint/type checks and the full web/API build pass. Orphan pruning runs only in a dedicated temporary filesystem fixture; tests preserve references, active attempts, legacy derivatives, fresh uploads and unrelated objects. S3 pagination tests simulate provider responses and do not verify live credentials/policy. Initial browser failures came from expired development fixtures and accumulated prepared-device limits. A fresh isolated development schema resolved them; no destructive reset of ordinary data was needed.

Five native JVM protocol tests pass. Three SecureStore tests and NativeSyncTest pass on the physical S24, tablet and API 37 emulator. NativeSyncTest includes original-signature upload, trusted receipt/replay, revocation and a real committed reservation whose local response is lost before retry. A fictional emulator draft survived force-stop/relaunch with its original text.

A timezone-sensitive expired media lease test exposed a real UTC comparison bug, corrected before all 32 integration tests passed. Native resumed-file testing exposed a capability negotiation race, corrected with bounded capability waiting. The physical offline Nearby product test then passed on both Android devices, including messages, interrupted 4 MiB resume, urgent original event priority, carrier publication with author absent and receipt return. See [the precise evidence and limits](android-testing.md).

Shared Wi-Fi testing found that a remote data channel may already be open before its observer is installed. The adapter now checks its current state immediately after observer installation. The full physical local-Wi-Fi fixture then passed, including actual received audio and decoded camera frames on both devices. The tablet/browser fixture also passed pairing, messages, verified 1 MiB files each way, original signed event and reconnect. Browser file flooding initially exceeded the bounded native receiver; 20 ms pacing corrected it. All 14 browser regression cases passed again afterward. See the testing document for exact timestamps/counters and unverified scenarios.

The three-peer physical local-Wi-Fi fixture completed at 22:19 IST: S24 authored, tablet carried to Windows Chromium with the author disconnected, browser published the unchanged envelope at two peer hops, and the signed receipt returned browser → tablet → S24. Earlier consent timing failures were in the driver; waiting for persisted browser state fixed them. Exact device/transport limitations and sanitized measurements are in the testing document.

Session-expiry recovery now offers sign-in to the same account while preserving prepared offline work and signing identity. A replacement account ID using the same email is rejected without rebinding saved work. The expanded account/storage regression passed on both physical devices and emulator. Saving/retrying/removing actions remains explicit. The independent native reviewer scored all four listed material fixes resolved after two correction batches: truthful public states, completed-card meaning, S24 first-viewport usefulness and contextual Back. This verdict covers those four findings, not whole-surface or production certification. Ten captures cover physical phone/tablet plus emulator light/dark/2x text, keyboard, rotation and account recovery.

Source commit `264d4050857740d4cbac6168805ee4cc71106bc3` was pushed to the public repository; its [web/API workflow](https://github.com/aakash-priyadarshi/Saathi/actions/runs/37341532531) and [native Android workflow](https://github.com/aakash-priyadarshi/Saathi/actions/runs/37341532530) both passed, including CI Keystore instrumentation. Later review/release/evidence corrections require their own final CI run. No staging artifact was distributed because hosted trust/bootstrap and a persistent QA signer remain unprovisioned.

At the owner's request, a temporary stay-awake-while-charging setting kept both devices available during unattended testing. Original settings were restored after final tests, preserving the owner's ten-minute timeout. The fixture stopped camera/microphone capture and closed all peer sessions. Attended lifecycle/range/battery work is deferred until the owner returns.

## Remaining handoff gates

Hosted staging approval/configuration, TLS and real provider checks; operator provision of the persistent QA certificate; broader native/browser call/lifecycle/range/battery evidence; API 26 physical compatibility; external security/load review; real email/storage/scanner/Redis verification, operator media retention/version policy and backup restore. The local environment has log email, filesystem media and disabled scanning; it contains no configured real Resend/storage credentials. No live production readiness is claimed. The tablet-scoped temporary Windows UDP rule requires Administrator PowerShell to disable after testing; the owner is unavailable until morning. [Engineering handoff](android-handoff.md) records the exact artifact, installer and morning steps.
