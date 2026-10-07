# Android communication surface

Mode: Operate. Extend the incumbent paper/forest native Material 3 product into familiar communication. Kotlin/Compose, API 26+, phones and tablets; light/dark follow Android. The public relief website keeps its incumbent layout.

## Direction contract

THESIS: Talk to people and coordinate in named conversations while saved work survives changing connections.

OWN-WORLD: Warm paper, forest ink, local Lora headings and Manrope controls. Familiar conversation rows, readable bubbles, explicit delivery states; no decorative dashboard chrome or fictional reachability.

STORY: Open Chats, find a person in Nearby, compare the connection code, open the same DM or join a named channel. Updates and authoritative relief Needs stay separate; chat membership never grants relief authority.

FIRST VIEWPORT: SWARM by CJP on the conversation list, an explicit test-environment disclosure, truthful connection status, separate Direct messages and Channels. In a conversation, contextual Back and a compact SWARM bar leave the transcript/composer primary; search is an explicit action. Phones use five bottom destinations outside conversations; tablets use a rail.

FORM: Code-led extension, no approved image comp or replacement visual world. Signature interaction: two visible nodes connect once over 420ms when a confirmed person becomes reachable, then settle. Android Remove animations takes the instant path. Quiet message entry has no placement animation or permanent scanning loop.

QUALITY BAR: Native task clarity within seconds, all primary actions accessible, readable light/dark and 2x text, composer above keyboard, no clipped controls, preserved signed-event transport, truthful Saved/Sent/Delivered/Read, no invented verified identity. Fictional layout fixtures are labeled test data and separate from owner storage. Private chat remains a gated development/QA preview pending independent security review.

FINISH: Fresh native/web brand review over the exact captured device classes; fix material findings in one batch, obtain the reviewer verdict, then document built source. Visual evidence does not certify radio, crypto or production readiness.

## Built walkie-talkie and connection guidance

This component extends the incumbent native Material 3 Paper/Forest identity in Operate mode; it introduces no replacement visual world.

- Direct chats place walkie-talkie above the composer. Both people opt in with microphone permission on a confirmed, compatible local Wi-Fi connection. Calls and voice-note recording exclude it. The full-width talk control has a minimum height (48dp), written state copy and a polite live region; transmitting uses the existing error-container role. Waiting and listening explicitly say “Microphone off.” Turns last up to 30 seconds and release stops speaking. Source includes Space/Enter handling and semantic speak/stop actions. See [WalkieTalkieControl.kt](../../apps/android/app/src/main/java/org/saathi/android/WalkieTalkieControl.kt) and [ChatScreens.kt](../../apps/android/app/src/main/java/org/saathi/android/ChatScreens.kt).
- The microphone track and source exist only during an acknowledged transmitting turn; release, disabling, leaving/switching conversations, connection reset and backgrounding stop PTT. Channel creation says “Up to 200 members” and retains the pending independent private-group security review disclosure; channels have no walkie-talkie control. See [SaathiViewModel.kt](../../apps/android/app/src/main/java/org/saathi/android/SaathiViewModel.kt) and [LocalWifiTransport.kt](../../apps/android/app/src/main/java/org/saathi/android/LocalWifiTransport.kt).
- Nearby's direct Android hint states that messages and saved media can connect with Wi-Fi and Bluetooth on without internet, a router or a manual hotspot. The separate local Wi-Fi pairing section explains shared LAN/hotspot WebRTC calls and PTT. Bluetooth-only fallback carries text and small control updates; saved media waits for a file-capable connection. A local Wi-Fi address is a hint, not proof that the peer is reachable. Missing-address copy suggests a hotspot for live audio while preserving the direct Nearby messaging distinction. System Wi-Fi and network-settings actions open Android settings. [NearbyScreens.kt](../../apps/android/app/src/main/java/org/saathi/android/NearbyScreens.kt) and [LocalNetworkAdvice.kt](../../apps/android/app/src/main/java/org/saathi/android/LocalNetworkAdvice.kt) are authoritative; address detection only reads existing interfaces.

## Component review boundary — 2026-10-07

Reviewer disposition: **ship at component scope**. All ten physical dark-theme captures passed: ready, talking and listening on phone/tablet (`../review/{phone,tablet}-walkie-{ready,talking,listening}.png`), plus local-address and missing-address guidance (`../review/{phone,tablet}-network-{wifi-address,needs-hotspot}.png`). Physical PTT UI/audio, release, switch-off and background-stop checks passed on Galaxy S24 (SM-S921B) and tablet (SM-X510).

This round did not verify the full application shell, light theme, large text, or executed TalkBack/keyboard operation. Implemented semantics and key handling are source evidence only. These checks do not certify battery use, range, outage behavior, security or production readiness. The documented scope is the built Android component; group PTT and a full iOS app are not implemented here.
