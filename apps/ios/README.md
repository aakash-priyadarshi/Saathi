# CJP Swarm iOS handoff for Rohan

This directory contains a buildable Swift core package, Android-compatible BLE packet decoder, synthetic packet fixture, capability routing tests and the implementation architecture below. The native iOS application, transport adapters, signing/storage integration and microphone UI still need implementation on a Mac. No iPhone radio, audio or App Store release qualification has been performed.

## Implementation boundary and Mac handoff

Swarm's purpose is protest-site and field coordination when internet is absent, unreliable or disrupted. Preserve the offline-first product: do not replace nearby communication or durable local queues with cloud-only chat, and do not require a server connection for nearby pairing and local message exchange. The online service supports selected account/configuration and public help/update workflows; it is not a general private-message relay. Use the transport, relay-consent, delivery and test limits documented below; do not promise instant reach across a crowd. This requirement does not override security gates: Android private chat is currently a development/QA preview and remains disabled in release pending independent security review. Keep iOS chat similarly gated until its implementation is reviewed.

This project is currently being worked on from Windows, without Xcode, an iOS Simulator, Apple signing access or an attached iPhone. Do not try to build the native iOS app here, and do not describe this Swift core package as a completed iOS app or as proof of iOS transport support. Windows work may maintain shared protocol documentation and fixtures; the iOS application and all Apple-platform integration must be implemented and qualified on Rohan's Mac.

**Instructions for an AI working on Rohan's Mac:** If macOS, Xcode and an iPhone or simulator are available, implement the actual native iOS app described below. Start by reading this handoff, `docs/chat-channel-architecture.md`, `docs/offline-protocol.md`, `docs/field-connectivity.md`, `docs/android-architecture.md` and ADR 0011. Treat the existing Android implementation, shared protocol vectors and signed service configuration as the interoperability authority. Build a usable SwiftUI app target with secure identity/storage, chats, Nearby, Updates, Needs and More; implement the adapters, durable outbox, signed identity verification, receipts, feature configuration, transfer resume and lifecycle handling rather than stopping at another library or design document. Preserve stable participant IDs and wire formats; do not introduce a second backend or invent iOS-only message semantics.

Implement and report the actual capability boundaries: integrate and qualify Google's cross-platform Nearby SDK for direct offline discovery and transfer; BLE may carry bounded text/control/receipts, while saved media waits for a file-capable path; local WebRTC requires a reachable shared LAN and does not itself create a mesh. Direct-chat hold-to-talk is foreground-only and Wi-Fi-only. **Group walkie-talkie is out of product scope; do not build it as a follow-on.** Exchange signed, expiring gateway reachability hints and eligible public help/update events under the existing relay opt-in and byte-budget rules. Events move opportunistically over successive peer encounters until an opted-in phone with validated internet can upload them; this is one peer session at a time, not a live broadcast or source-routed path. Private chat is not uploaded through an internet gateway.

Run the Swift package and protocol-vector tests, add and build the native app target, and use Simulator plus real iPhone↔S24/Tab tests for each claimed transport and lifecycle behavior. Exercise offline reopen, pairing-code confirmation, text and media integrity, reconnect/inventory/receipts, BLE fallback limits, PTT release/interruption, and stale gateway-route handling. Test three-device forwarding only when three devices are available; otherwise record it as unverified. Do not claim 1,000-device scale from unit tests or two-device tests. Add macOS app-build CI after it builds reliably, keep signing credentials outside this public repository, and submit the implementation as a PR. If the Mac or iPhone is unavailable, leave the app explicitly unimplemented and report the blocker instead of claiming completion.

## iPhone app status and device results (7 October 2026)

`apps/ios/App` (XcodeGen, `xcodegen generate`) builds **CJP Swarm** (`org.cjp.swarm.staging`), which speaks the Android QA protocol (`org.saathi.nearby.v1.staging`) and matches the Android app screen for screen (Chats, Nearby, More; Group info; contact info).

Implemented and interoperating with Android:

- **Identity and pairing:** signed identity in the Keychain; the code is compared on the first pairing only, known people reconnect automatically (identity checked against the saved contact), auto-connect retries every 35 seconds.
- **Chats:** end-to-end encrypted DMs, receipts, inventory/need store-and-forward; long-press Reply, Copy, Forward, Save, Report, Delete for me / for everyone; Telegram-style reply threads with jump-to-quote; contact info (mute, block, report, clear).
- **Groups:** create (free chat, admins post with member replies, view only), make/remove admins, roles, remove/ban with confirmation, approval of join requests (removed members need approval), personal invitations (QR, link, send nearby) and one reusable 7-day join link per group (automatic admission when "Approve new members" is off), reports to review and recent admin changes.
- **Media:** photos (1280 px), videos (720p, metadata removed), files (text, audio, MP4), hold-to-talk voice clips that play automatically in the open chat; items up to 100 MB; attachments a peer missed are re-offered after reconnecting.
- **Online:** signed CHAT_SYNC every 20 seconds while open, encrypted attachment upload/download, endpoints from the verified signed service configuration (`ServiceConfig` in SwarmCore).
- **Background:** Bluetooth background modes keep the link and search running; local notifications for new messages (no server push).

Build: `cd apps/ios/App && xcodegen generate`, then build the `Swarm` scheme with your own Apple team (`DEVELOPMENT_TEAM=...`). Tests: `swift test --package-path apps/ios` (SwarmCore, protocol vectors, service config) and the TypeScript interop check `SWARM_INTEROP_OUT=/tmp/x.json swift test --package-path apps/ios --filter InteropExportTests && node apps/ios/scripts/ios-interop.mjs /tmp/x.json`. Simulator review: launch with `-SwarmDemo 1 [-SwarmTab chats|nearby|more] [-SwarmOpen dm|channel|owned|info]` (DEBUG only; the simulator cannot run Nearby because it has no Bluetooth).

TestFlight (paid team): `ASC_KEY_ID=... ASC_ISSUER_ID=... APPLE_TEAM_ID=... apps/ios/scripts/testflight.sh` archives with the Hotspot Configuration entitlement (`App/Release.entitlements`) under the bundle ID `org.cockroachjantaparty.swarm` (`org.cjp.swarm.staging` stays with free-team installs) and uploads it, signed through an App Store Connect Team API key at `~/.appstoreconnect/private_keys/AuthKey_<id>.p8`. The first run registers the bundle ID; if the upload then fails because there is no app record, create the app in App Store Connect with that bundle ID and run `testflight.sh upload`. Wi-Fi Aware is left out: in Nearby it is only a bandwidth-upgrade medium after connecting, needs a system pairing prompt per device, and does not help an Android phone discover an iPhone.

Still open: live walkie-talkie and calls (Android-only), server push.

Measured with an iPhone 17 Pro Max (iOS 27.2) and a Pixel 8 (Android 17 beta, Swarm QA 0.1.1):

- Same Wi-Fi network: discovery both ways; DMs and receipts both ways (WIFI_LAN medium).
- Airplane mode with Bluetooth on: **the iPhone discovers the Android phone; the Android phone does not discover an advertising iPhone.** DMs, receipts and inventory/need work over BLE.
- iPhone discovery reads the Android GATT advertisement; after repeated advertise/stop cycles on the Android phone those reads timed out until Bluetooth was restarted on both phones.
- Bandwidth upgrade fails: Android offers WIFI_HOTSPOT and the iPhone cannot join it because `NEHotspotConfiguration` needs the Hotspot Configuration entitlement, which free (personal) teams cannot use. Without a paid Apple team the iPhone stays on BLE (probe: about 4.5 KB/s application throughput, 1 MiB in 3 m 49 s).
- Free-team installs must be opened once with internet after each install before they launch offline; they expire after 7 days.

## Fetch and run the foundation on a Mac

Install the Xcode version compatible with your macOS from [Apple's requirements](https://developer.apple.com/xcode/system-requirements), select its command-line tools, then:

```sh
git clone https://github.com/aakash-priyadarshi/Saathi.git
cd Saathi
git switch -c codex/ios-native
swift test --package-path apps/ios
open apps/ios/Package.swift
```

The GitHub `Swarm iOS foundation` workflow runs the same tests on macOS. It builds the core library; an IPA needs an iOS app target and Apple provisioning. Keep certificates, provisioning profiles, signing private keys and account tokens out of this public repository. The foundation workflow is not app-build or device-interoperability evidence. Xcode builds, Simulator checks and physical iPhone tests run on Rohan's Mac.

Create a SwiftUI iOS app target (iOS 16+ provisional minimum), official display name **CJP Swarm**, visual branding **SWARM by CJP**. Add this local `SwarmCore` package. Choose distinct staging/production bundle IDs and Keychain namespaces with Rohan's Apple team. The Android app uses Kotlin/Compose; rebuilding it with a JavaScript wrapper will not supply native iOS radio support.

## Modules to implement

| Module                      | Responsibility and Android reference                                                                                                                                         |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SwarmStore` actor          | SQLite durable outbox, encrypted records/media and atomic ACK-after-save; reference `SecureStore.kt`, `ChatRepository.kt`                                                    |
| `IdentityVault`             | Keychain/Secure Enclave signing custody, separate ECDH encryption key; stable participant ID, migration and recovery; never reset a lost identity silently                   |
| `ChatProtocol`              | RFC 8785 canonical JSON, P-256 raw 64-byte signatures, JOSE ECDH-ES/A256GCM and `dir`/A256GCM; implement against the existing shared/native vectors before accepting traffic |
| `ServiceConfiguration`      | Verify the offline configuration root and signed service documents before using dynamic API URLs; persist verified expiry, version and endpoint fallback                     |
| `PeerSession` actor         | HELLO/NATIVE_CAPS, confirmation, verified identity, frame budgets, ordered controls, bounded CHAT_CHUNK assembly and durable receipts                                        |
| `NearbyAdapter`             | Google's cross-platform Nearby Connections Swift SDK, same service ID and point-to-point strategy as Android                                                                 |
| `BleAdapter`                | CoreBluetooth central/peripheral, Android GATT UUIDs and `BleFrameCodec.kt` framing, MTU budgets, indications and pairing approval                                           |
| `LocalWifiAdapter`          | WebRTC data/audio/video with the existing SAATHI1 offer/answer format, confirmation and identity checks                                                                      |
| `DeliveryCoordinator` actor | Connection-triggered inventory checks, stable IDs, bounded retry/backoff, byte/battery budgets and file resume; never turn transport success into Delivered                  |
| `WalkieTalkie` actor        | Port the Android floor state machine, signed connection-scoped PTT controls and release-before-signaling guarantee; AVAudioSession interruptions mute immediately            |
| SwiftUI screens             | Chats, Nearby, Updates, Needs, More; profile display name, admin feature config, scoped hold-to-talk and system navigation/accessibility                                     |

Group capacity is 200 members and 16 joined channels. Copy bounds from `WireLimits` and ADR 0011. The JSON/crypto wire format remains the authority; this Swift package does not pretend to implement missing crypto. Use established JOSE/JCS implementations after qualification, rather than hand-writing cryptographic primitives. CryptoKit P-256 alone does not implement JOSE headers/KDF or RFC 8785 canonicalization. Pass `apps/android/app/src/test/resources/chat-vectors.json`, shared service-config vectors and Android BLE packet fixtures in both languages.

## Android–iPhone transport decisions

[Google Nearby supports Android and iOS communication](https://developers.google.com/nearby) and publishes a [Swift setup guide](https://developers.google.com/nearby/connections/swift/get-started). Start there for offline text and files. Pin the qualified package revision in the app and commit Package.resolved. Derive Bonjour services from the **same** configured Nearby service ID; do not invent a second iOS ID. Compare the displayed verification code and explicitly confirm the peer.

Google's setup requires appropriate Bluetooth/local-network usage descriptions, Bonjour types and location/hotspot capabilities when using bandwidth upgrades. Configure and test those on the actual iPhone; permission denial must leave saved chats usable. Local Wi-Fi WebRTC works on a shared router/hotspot without internet if devices can reach each other. Access-point client isolation can block it. MultipeerConnectivity may be evaluated for Apple-only peers but must not be assumed Android-compatible.

For BLE parity, use the UUIDs in Android `BleTransport.kt`; packets are big-endian `SWRM`, version, packet kind, 16-byte UUID, 16-byte object hash, UInt16 index/total, UInt32 total bytes, 16-byte SHA-256 prefix, UInt16 payload size, payload and CRC32. The current profile allows 8 KiB frames, 128 ATT fragments and four bounded assemblies. Central/peripheral packet lengths must reflect negotiated ATT capacity. iPhone limits may be too small for the Android minimum MTU; record that outcome and retain Nearby/local Wi-Fi rather than silently changing the shared codec.

The included decoder/fixture verifies this framing and corruption rejection. It is not a CoreBluetooth connection implementation or pairing proof. BLE fallback currently carries text/control/receipts. Saved audio, pictures and video wait for file-capable Wi-Fi; live audio stops. If BLE media is added later, negotiate small-media limits and use short compressed voice/photo previews with an explicit bandwidth/battery budget. Do not promise live video or 200-person live audio over BLE.

## Walkie-talkie and background behavior

Implement foreground direct-chat PTT on a confirmed local Wi-Fi WebRTC connection: both users opt in, one speaker, 30-second turns, microphone off while waiting/listening, stop on chat change/background/disconnect/interruption. Port the tests in `WalkieTalkieTest.kt`, including the delayed grant after release and simultaneous press cases. Never capture camera/audio merely because a peer sends READY. Group PTT is explicitly out of scope; do not implement a group coordinator or audio fanout.

[Apple Push to Talk](https://developer.apple.com/documentation/pushtotalk/creating-a-push-to-talk-app) can support system-managed background audio after a user joins a channel, with its own entitlement/session/APNs rules. It does not supply Swarm's audio transport or offline relay. Qualify it after foreground parity; avoid promising APNs-based wakeup when there is no internet. [CoreBluetooth background execution](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html) also has different scanning/advertising behavior. Test foreground, screen lock, suspension, force quit and permission revocation separately.

## Acceptance sequence on Rohan's hardware

1. Run the package CI and protocol vectors. Add real crypto/storage tests before enabling nearby UI.
2. Build the signed staging app on iPhone; verify cold offline reopen, identity persistence, display-name updates and feature hiding after online configuration refresh.
3. Pair iPhone↔S24 and iPhone↔Tab; confirm codes, reconnect to the same participant, reject wrong-identity retries, record actual transport/capabilities.
4. Remove internet while keeping shared Wi-Fi. Transfer text, recorded audio, photo/video and interrupted private files; verify hashes and recipient-signed receipts after durable saves.
5. Disable Wi-Fi and test supported BLE text/control; media stays queued. Restore Wi-Fi and resume without duplicate messages.
6. Test PTT both directions, simultaneous presses, immediate release/cancel, screen lock, phone-call interruption, navigation and transport loss. Measure latency/battery on devices.
7. When a third device is available, check asynchronous authorized store-and-forward and removal/key changes. Load-test the signed 200-member roster and record that this does not represent 200 simultaneous radio links or guarantee delivery time.
8. Add iOS app build/Simulator CI once the app target exists. App Store/TestFlight signing and release are a separate step owned by the Apple team.

Bring changes back as a PR from `codex/ios-native`; do not introduce another backend or change chat identities to fit iOS.
