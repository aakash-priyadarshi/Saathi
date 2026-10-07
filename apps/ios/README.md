# CJP Swarm iOS app

The repository now includes a native SwiftUI iPhone communication preview in `apps/ios/App` and the shared Swift protocol/crypto package in `apps/ios/Sources/SwarmCore`. This work was merged into `main` in pull request #2. The app speaks the Android QA chat protocol (`org.saathi.nearby.v1.staging`) and uses the CJP Swarm identity.

Swarm is built for protest and field coordination where internet may be absent, unreliable, restricted or disrupted. Preserve offline nearby exchange and durable local queues; do not turn chat into a cloud-dependent service. A nearby connection still handles one confirmed peer at a time. Store-and-forward is asynchronous and does not guarantee reach or delivery time.

## Implemented scope

- Keychain-backed signing identity, encrypted direct and group chats, membership and join links.
- Nearby discovery and pairing, reconnect to known people, and messages/receipts with Android-compatible formats.
- Photos, videos, files and hold-to-talk voice clips; media missed during a connection can be offered again after reconnecting.
- Signed online chat synchronization and encrypted attachment transfer using the verified service configuration.
- Bluetooth background modes and local message notifications. There is no server push guarantee.

This is the communication preview, not a complete iOS port of the web operations portal. Admin management, public needs operations, and field moderation remain web/API functions. Live walkie-talkie and calls are currently Android-only; group walkie-talkie is out of product scope. Private chat remains a development/QA preview pending independent security review.

## Physical test record (7 October 2026)

Rohan tested an iPhone 17 Pro Max on iOS 27.2 with a Pixel 8 on Android 17 beta, using Swarm QA 0.1.1. These are scoped results from one phone pair, not independent release qualification.

| Scenario                               | Result                                                                                                                                                                                                                    |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Same Wi-Fi                             | Discovery both ways, direct messages and receipts passed.                                                                                                                                                                 |
| Airplane mode with Bluetooth enabled   | iPhone discovery of Android passed; Android discovery of the advertising iPhone did not. DMs, receipts and inventory/need exchange worked over Bluetooth.                                                                 |
| Repeated Android advertise/stop cycles | iPhone GATT reads sometimes timed out until Bluetooth was restarted on both phones.                                                                                                                                       |
| Wi-Fi bandwidth upgrade                | Android offered a hotspot path, but iPhone could not join because Apple's Hotspot Configuration entitlement was unavailable. A free-team build stayed on Bluetooth; the probe measured about 4.5 KB/s and 1 MiB in 3m49s. |

The offline behavior above does not prove broad device compatibility, sustained range, battery use, screen-lock/suspension reliability, or larger group delivery. Do not infer full mesh or 1,000-device reach from this test. The app’s iPhone-to-Android offline discovery is currently one-way; shared Wi-Fi provides two-way discovery in the tested setup.

## Build and test on Rohan's Mac

Use a Mac with Xcode installed. Windows can maintain shared protocol docs and fixtures, but cannot build or qualify the native iOS app or simulator.

```sh
brew install mint
swift test --package-path apps/ios
cd apps/ios/App
mint run yonaskolb/XcodeGen@2.46.0 xcodegen generate
xcodebuild -project Swarm.xcodeproj -scheme Swarm \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

To install on a physical iPhone, select your Apple team and provisioning profile in Xcode. Keep signing certificates, provisioning profiles, Apple tokens and private keys out of this public repository. The `Swarm iOS` GitHub Actions workflow runs Swift package tests and an unsigned iOS Simulator app build; it does not sign or distribute an IPA.

The TypeScript/Swift interop fixture can be checked with:

```sh
cd ../..
SWARM_INTEROP_OUT=/tmp/swarm-vectors.json \
  swift test --package-path apps/ios --filter InteropExportTests
node apps/ios/scripts/ios-interop.mjs /tmp/swarm-vectors.json
```

XcodeGen is pinned to version 2.46.0 in this guide and in GitHub Actions so local project generation matches CI.

For simulator UI review, launch the `Swarm` scheme with `-SwarmDemo 1` and optional `-SwarmTab chats|nearby|more` or `-SwarmOpen dm|channel|owned|info`. Demo data is DEBUG-only; the simulator has no Bluetooth radio and cannot prove Nearby behavior.

## Remaining gates

- Re-test iPhone↔Android BLE discovery and transfers on supported iPhone/Android releases; resolve the one-way discovery and repeated-advertising timeout before claiming radio parity.
- A paid Apple Developer team is needed for the Hotspot Configuration and Wi-Fi Aware entitlements, TestFlight and public distribution. The Google Nearby transport and its upgrades still need qualification under the team's actual entitlements.
- Measure range, sustained battery use, screen-lock/suspension, force-quit and permission-revocation behavior on physical devices.
- Build and review the signed staging app with the Apple team. No TestFlight or App Store release has been performed.

See [chat/channel architecture](../../docs/chat-channel-architecture.md), [offline protocol](../../docs/offline-protocol.md), [field connectivity](../../docs/field-connectivity.md), and [Android test evidence](../../docs/android-testing.md) for shared wire and verification limits.
