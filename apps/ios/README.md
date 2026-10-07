# CJP Swarm iOS handoff for Rohan

This directory contains a buildable Swift core package, Android-compatible BLE packet decoder, synthetic packet fixture, capability routing tests and the implementation architecture below. The native iOS application, transport adapters, signing/storage integration and microphone UI still need implementation on a Mac. No iPhone radio, audio or App Store release qualification has been performed.

## Fetch and run the foundation on a Mac

Install the Xcode version compatible with your macOS from [Apple's requirements](https://developer.apple.com/xcode/system-requirements), select its command-line tools, then:

```sh
git clone https://github.com/aakash-priyadarshi/Saathi.git
cd Saathi
git switch -c codex/ios-native
swift test --package-path apps/ios
open apps/ios/Package.swift
```

The GitHub `Swarm iOS foundation` workflow runs the same tests on macOS. It builds the core library; an IPA needs an iOS app target and Apple provisioning. Keep certificates, provisioning profiles, signing private keys and account tokens out of this public repository. Windows can edit shared source and inspect CI; Xcode builds, Simulator checks and physical iPhone tests run on Rohan's Mac.

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

Begin with foreground direct-chat PTT on local WebRTC: both users opt in, one speaker, 30-second turns, microphone off while waiting/listening, stop on chat change/background/disconnect/interruption. Port the tests in `WalkieTalkieTest.kt`, including the delayed grant after release and simultaneous press cases. Never capture camera/audio merely because a peer sends READY. Group PTT is the separate coordinator/SFU design in ADR 0011.

[Apple Push to Talk](https://developer.apple.com/documentation/pushtotalk/creating-a-push-to-talk-app) can support system-managed background audio after a user joins a channel, with its own entitlement/session/APNs rules. It does not supply Swarm's audio transport or offline relay. Qualify it after foreground parity; avoid promising APNs-based wakeup when there is no internet. [CoreBluetooth background execution](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html) also has different scanning/advertising behavior. Test foreground, screen lock, suspension, force quit and permission revocation separately.

## Acceptance sequence on Rohan's hardware

1. Run the package CI and protocol vectors. Add real crypto/storage tests before enabling nearby UI.
2. Build the signed staging app on iPhone; verify cold offline reopen, identity persistence, display-name updates and feature hiding after online configuration refresh.
3. Pair iPhone↔S24 and iPhone↔Tab; confirm codes, reconnect to the same participant, reject wrong-identity retries, record actual transport/capabilities.
4. Remove internet while keeping shared Wi-Fi. Transfer text, recorded audio, photo/video and interrupted private files; verify hashes and recipient-signed receipts after durable saves.
5. Disable Wi-Fi and test supported BLE text/control; media stays queued. Restore Wi-Fi and resume without duplicate messages.
6. Test PTT both directions, simultaneous presses, immediate release/cancel, screen lock, phone-call interruption, navigation and transport loss. Measure latency/battery on devices.
7. Use three devices to check asynchronous authorized store-and-forward and removal/key changes, then load-test 200 signed members. Group live audio waits for the router/coordinator milestone.
8. Add iOS app build/Simulator CI once the app target exists. App Store/TestFlight signing and release are a separate step owned by the Apple team.

Bring changes back as a PR from `codex/ios-native`; do not introduce another backend or change chat identities to fit iOS.
