# 200-member channels, direct walkie-talkie and iOS handoff

Implemented on 7 October 2026 in the current Android/API codebase. The native
staging app remains the full CJP Swarm QA application, with existing participant
storage and identities preserved. Private chat retains its independent release
and security qualification gate.

- Up to 200 signed channel members and recipient-wrapped private epoch keys;
  member 201 fails closed, removed members have no fresh epoch key.
- Route-specific larger chat-sync parser, bounded changed-roster/admission
  paging, and negotiated hash-checked radio fragmentation. Existing joined
  channel limit stays 16. Unchanged policies are suppressed during ordered
  connection replay and acknowledged API sync.
- Separate direct-chat live walkie-talkie control, both participants opt in,
  authenticated turn grants, recipient/connection/session binding, one speaker,
  4-second request timeout and 30-second turn limit. Release, cancellation,
  conversation change, backgrounding and peer reset stop microphone capture.
- Direct Android Nearby versus shared-network live-audio guidance, current
  local-address hint and system settings links; no new background radio sweep
  or automatic hotspot activation.
- Swift core package, Android-exact BLE packet fixture, capability routing,
  macOS CI and Rohan's native-app handoff in `apps/ios/README.md`. This is a
  foundation; native iOS screens, secure store, crypto and radio adapters still
  need implementation and iPhone qualification.

## Verification

TypeScript typecheck/lint, 41 unit tests and 58 PostgreSQL integration tests
passed. Android debug/staging builds, staging lint and JVM tests passed,
including a generated 200-reader private policy and Android-exact iOS fixture.
Fourteen encrypted-storage/governance/history/profile-refresh instrumentation
tests passed on the Android emulator. The full GitHub verification remains the
authoritative hosted build result after push.

On the physical Samsung SM-S921B and SM-X510 (both Android 16), actual UI holds
produced received Wi-Fi RTP audio in both directions. Release disposed capture,
did not trigger an extra click, switching the visible recipient stopped capture,
and foreground loss stopped capture. Both temporary plugged-in keep-awake
settings were restored to 0. See `benchmarks/android-walkie-wifi.json`.
USB coordinated temporary pairing/barriers only. Shared Wi-Fi was present;
this is not an internet-outage, battery, intelligibility, range or 200-radio test.

Both physical device classes passed the two-branch Nearby hint UI test. The
fresh component reviewer returned `ship` for six real walkie state captures
and four isolated network-hint captures. The review did not qualify full shell,
light/large text or executed keyboard/TalkBack behavior in this round.

The `chat-radio` dual fixture passed on the same two physical Samsung devices
with Wi-Fi enabled, no infrastructure Wi-Fi address and no validated internet.
It verified private DM delivered/read receipts, open/private channel messages,
and six saved photo/synthetic AAC/video transfers across a DM and private group
with content hashes checked. Signed admission, thread locking, ban/unban,
member removal and blocking also passed. It used no API or USB reverse to port
4000; USB port 4010 carried test barriers and metadata only. See
`benchmarks/android-chat-radio-nearby.json`. Three-device relay, 200 physical
participants, live group PTT, sustained battery/range and iPhone transport tests
remain open.

Hosted Android build/lint/JVM/instrumentation passed after correcting the
guidance test to scroll uncomposed lazy-list content. Browser outage testing
also exposed a delayed-success race: API results now respect the browser's
offline state. Desktop/mobile cold-offline tests explicitly inject a late
successful response and verify online reservation controls remain hidden.

The final browser relay check exposed a second preparation race: the background
connection check could be interrupted before receipt verification keys were
saved. Explicit offline preparation now awaits validated key storage before
showing its ready notice. The two-hop regression blocks the carrier's background
configuration check, then verifies that a signed publication receipt still
returns through that offline carrier. It failed before the fix and passed after
the fix on desktop and mobile, alongside both cold-offline checks.

## Deployment

The changed API must be updated alongside the web image for online 200-member
sync. No new Prisma migration is required for these changes. Once the published
`:staging` image is green, use the existing Lightsail browser SSH shell:

```sh
cd /opt/saathi-staging
sudo docker compose pull api web
sudo docker compose up -d --no-deps --wait api web
sudo docker compose ps api web
curl -fsS https://swarm.cockroachjantaparty.org/api/v1/public/config
```

On 7 October 2026, the published image was applied through the existing
Lightsail browser SSH session. Both API and web containers reported healthy,
and the image revision matched `80aa554c30b6daaa76aa38d727b0c9b3416c0565`.
The public configuration route returned HTTP 200 through both the web origin
and the separate API origin. Dynamic signed API configuration remains unchanged.

The delayed-response fix image `b28034b3f0e0e4f3e9e6bc4d79b1856353329174`
was subsequently applied; both containers were healthy and both API routes
returned HTTP 200. The receipt-preparation correction uses the same deployment
procedure after its image is published.
