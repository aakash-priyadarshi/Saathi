# Android product verification

5 October 2026. Product evidence comes from `apps/android`, not the earlier `spikes/android` transport instrument. Both Samsung SM-S921B (S24) and SM-X510 (tablet), Android 16, are authorized for this run. The development app uses fictional local data and its own configuration root.

| Scenario | Evidence | Status |
|---|---|---|
| Canonical JSON, TypeScript-authored events/config/receipts, forged/expired/rollback/revoked checks | Five native JVM protocol tests | Verified automated |
| AES-GCM persistence, record substitution, missing AES key, non-exportable Keystore identity, scoped cleanup | Three SecureStore instrumentation tests on both physical devices and API 37 emulator | Physically verified; also emulated |
| Public reads, preparation/registration, original native signing, reopening storage, signed upload/receipt, replay and revoked device | NativeSyncTest against local PostgreSQL/API through USB reverse, on both physical devices and emulator | Physically verified API/storage behavior |
| Lost reservation response, repository reopening, retry with the original idempotency key, changed-body refusal | NativeSyncTest creates one real reservation, discards its local response and verifies the retry creates no second commitment | Physically verified on both devices; also emulated |
| Public isolation, signer rotation, conflicts/idempotency/carrier observations, media leases and visibility repair | 32 real PostgreSQL integration tests | Verified automated |
| Existing web flows, offline cold open/cache exclusions, nearby data/synthetic calls/resume/three-browser receipt relay | 14 desktop/mobile-width Playwright cases; 15 shared unit tests | Verified automated; viewport emulation is not Android hardware |
| Product Gradle assemble, Android lint and protocol tests | Debug build | Verified build |
| Saved field draft, actual force-stop/relaunch, original draft reopened | Fictional emulator UI | Verified emulated process death; not reboot |
| Native Nearby pairing, durable bidirectional messages/ACK, interrupted 4 MiB file resume/hash, urgent event before file completion | NativeDualTest, physical S24 author and tablet carrier | Passed with both devices reporting no validated internet |
| Author absent, carrier uploads original signature, signed PUBLISHED receipt returns after re-pairing | Same physical Nearby test, local API reached through USB reverse | Passed; two physical peers plus server, not three physical nodes |
| Phone/tablet layouts, light/dark/2x text, rotation, keyboard/error recovery | Product captures and finish review | Review in progress |
| Native local Wi-Fi: message ACK, interrupted 4 MiB resume, urgent event and receipt return; real hardware voice/video | NativeDualTest on shared Wi-Fi, S24/tablet | Passed; 8-second voice and video checks, received audio and decoded video on both |
| Native-to-browser pairing, bidirectional 1 MiB files, message/event/reconnect | NativeBrowserTest on physical tablet and Windows Chromium product | Passed after shared LAN, scoped firewall and file pacing correction |
| Range, sustained battery use, degraded calls, screen lock/reboot, battery saver, permission revocation | Physical scenarios required | Not verified |
| Physical Android browser-to-browser and three-device multi-hop | Additional approved peers/environments required | Not verified |
| iOS / direct BLE / Wi-Fi Direct / Aware adapters | No product implementation selected | Unsupported |

## Physical Nearby product result

Completed at **21:23 IST on 5 October 2026**. Instrumentation elapsed 76.459 seconds on the S24 and 76.145 seconds on the tablet. The original author disconnected before the carrier synchronized. The returned receipt verified the original envelope hash; relay metadata recorded one peer hop without replacing its author signature.

A 4 MiB generated text fixture used 512 encrypted 8 KiB chunks. The receiver persisted at least 32 chunks before deliberate disconnect. Re-pairing and reoffering resumed the bitmap, verified the complete SHA-256 and returned a durable file acknowledgement. The urgent signed event arrived before resumed attachment completion.

USB supplied fixture barriers and access to the local API. All peer messages, chunks, events and receipt-return bytes traveled through the product's actual Nearby adapter. The test verified `internetValidated=false` on both devices at preparation and all three connections; USB API access is not an independent internet proof. Barrier timing metadata measures the difference between peers arriving at a fixture barrier, not radio latency or throughput. No range or battery claim follows from this short run.

## Physical local Wi-Fi and browser results

The S24/tablet local-IP WebRTC test completed at **21:41 IST**, with both devices reporting validated internet. No STUN/TURN or cloud signaling server was used; this verifies local transport on that LAN, not an internet-loss call. Instrumentation elapsed 76.180 / 75.871 seconds. In the eight-second voice check, inbound audio counters were 99,359 bytes on the author and 92,865 on the carrier. After the separate eight-second video check they increased to 127,687 / 120,784 bytes, with 98 / 91 decoded remote camera frames. These are received-media counters, not recordings or sustained quality measurements. Deliberately weak networks and production screen-lock call cleanup remain unverified.

The physical tablet and a fresh Windows Chromium profile passed the complete browser interoperability fixture at **21:51 IST**, elapsed 21.738 seconds: matching-code pairing, durable messages in both directions, consented SHA-256-verified 1 MiB files each way, unchanged original native event at one hop, disconnect, retained records and re-pairing. The browser used the actual Saathi product; this was not Chrome on Android. The host rule allowed only test-browser UDP from the authorized tablet. No voice/video browser interoperability was executed in this fixture.

Two defects were repaired from measured failures: checking a remote channel already open when its observer attaches, and pacing browser file chunks so a bounded Android receiver can save them before its queue fills. All 14 existing browser regression cases passed again after pacing changed.

## Reproduce approved two-device tests

Build using the development trust directory and run the local fictional API before testing. Select the two explicitly approved model names:

```powershell
node scripts/android-dual-test.mjs SM-S921B SM-X510 nearby
node scripts/android-dual-test.mjs SM-S921B SM-X510 wifi
node scripts/android-browser-test.mjs SM-X510
```

The second command needs a reachable shared Wi-Fi/hotspot and uses actual microphones/cameras briefly. It checks received audio bytes and decoded video frames without recording streams. The helper grants only app runtime permissions used by the selected scenario and keeps the fixture activity awake. It does not change the owner's global screen timeout. Do not run another installer or instrumentation runner concurrently on either selected device.

Optional fixture tests require a development build, locally supplied arguments and the fictional `android-volunteer@saathi.test` account. No credential is embedded in an APK. Unique fixture storage scopes preserve ordinary product records; registered fixture identities are revoked during cleanup. Local raw serials, networking descriptions, credentials and UI dumps remain ignored. Public documentation contains only sanitized scenario results.

Repository reopening proves persisted bytes/identity, not process death. The separate emulator force-stop test proves its draft's actual process recovery. Standalone CI secure-storage tests skip API/browser/dual fixtures without their explicit arguments.

