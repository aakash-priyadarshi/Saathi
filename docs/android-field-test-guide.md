# Samsung S24 / Android 16 field-test guide

The owner's Samsung Galaxy S24 (SM-S921B, Android 16/API 36, security patch 2026-08-05) passed single-device native probe checks on 5 October 2026: installation, permission denial/settings recovery, Nearby advertising/discovery, Wi-Fi Direct discovery, foreground stop and local JSON export. Wi-Fi Direct and Wi-Fi Aware features were present, and Aware was available at the sample. These observations do not establish a connection to another device. See [the measured smoke record](benchmarks/samsung-s24-smoke.json).

The current `0.3-probe` debug APK is installed without root and is available at `spikes/android/app/build/outputs/apk/debug/app-debug.apk`. Repeated denial on this phone stopped Android showing the permission prompt; **Open app permission settings → Permissions → Nearby devices → Allow** recovered it. The test screen stays awake while foregrounded and stops when it leaves the foreground. No system lock, radio or battery policy is changed. Physical Chrome/PWA tests remain NOT RUN: automatic approval review blocked the remote-debugging setup without a detailed reason. The earlier [Android 17/API 37 emulator record](benchmarks/android-emulator-smoke.json) remains separate.

The owner then connected a Samsung tablet (SM-X510, Android 16/API 36, security patch 2026-08-05). The two devices paired through Nearby and exchanged generated message round trips and checksum-verified 1 MiB transfers in both directions after their Wi-Fi networks, mobile data and tethering were disconnected, with both radio switches still on. Android reported no active default network. Counts, timings, raw samples and test-automation delays are in [the two-device record](benchmarks/samsung-s24-tablet-nearby.json). Opening the report picker stopped the live connection; later pairing restored it. This probe does not implement native relief events or calls.

The `0.2-probe` dataset contains 20/20 successful offline pairing requests, median/p95 setup 15.0/39.1 seconds including automation and code confirmation, median round trips 24.4–33.8 ms and checksum-verified 1 MiB transfers in 7.2–7.9 seconds. A separate rejection test found a stale tablet prompt. `0.3-probe` fixes that prompt: one S24 rejection now dismisses both prompts and disables both measurement controls. A later accepted pair, 20 phone round trips and another verified 1 MiB transfer passed. Those follow-up samples remain separate from the twenty-request dataset. Both reports were exported and both test apps closed; internet access can be restored.

Range/battery/lifecycle tests, physical browser communication and Direct/Aware data-path comparisons remain required. A third device is useful for relay testing. Record each model, Android/browser version and security patch in [the device worksheet](nearby-connectivity-device-tests.md). No additional emulator can supply physical radio measurements.

## Prepare on the same secure origin

1. Serve the built web/API behind operator HTTPS with a certificate trusted by both phones. Keep `WEB_ORIGIN` and `PUBLIC_URL` exact. A plain LAN URL such as `http://192.168.1.10:3000` does not provide the secure context required by production service workers, camera and microphone.
2. Open Saathi while internet is available. In Connection details, choose **Save app for offline opening**. Visit Needs and Live to save public snapshots. Use the browser's install/Add to Home screen action if available. Installation does not grant permissions or prove background execution.
3. For an approved volunteer, sign in and choose **Prepare volunteer publishing**. Keep the original browser profile; clearing site data or uninstalling can remove drafts and keys. Export important work and save original media separately.
4. Connect both phones to the same reachable Wi-Fi or a user-created phone hotspot. Turn off WAN/cellular for the test while retaining the local network. Changing radio/hotspot settings is the user's normal OS action. Some access points/hotspots isolate clients; record that failure rather than assuming a connection.

## Nearby preview

Create an invitation on A. On B, scan it when a QR fits, or import/copy the pairing text/file through an available out-of-band method. B returns a reply; A imports it. Compare both short codes in person before choosing **The codes match**. Invitations expire in two minutes. No cloud signaling, STUN or TURN is used in this mode.

Send a message in each direction. Check the sender's “Reached another phone” acknowledgement. Offer a small image/text file and confirm that B receives nothing until choosing Receive. Interrupt the connection, reopen the app, pair again, and use **Offer saved attachment to this person** / **Receive or resume attachment**. Verify the resulting bytes/checksum.

Invite a voice/video call. Check that neither remote invitation nor an unanswered call starts the recipient's microphone/camera. Accept through the browser's normal permission prompt. Deny once, grant through the normal browser/app permission settings, and try again. End the call and verify capture stops. Move out of range, lock the screen, use battery saver, and background the app. Browser calls are foreground sessions; uninterrupted screen-lock operation is unverified and must not be promised.

## Durable relief relay

A creates a signed urgent request while Saathi is unreachable. Share it with B. Close A's app. B retains it and shares with C. C reconnects and explicitly sends saved relief updates (or has enabled the public-text gateway preference). Check that one canonical request exists with A as author. Pair C with B and share confirmations; then pair B with A and repeat. A should see Published only after a server-signed receipt is checked.

Private chat/calls and media originals do not enter the gateway. Saved field media uses the original author's authenticated direct upload and existing sanitization/moderation before publication. Donor reservations remain unavailable while quantities are stale.

## Record evidence

Run at least 20 pairing attempts per combination. Record success/attempts, median/p95 setup and message latency, verified transfer bytes/time, range and one-hour battery consumption against an idle baseline. Include permission denial, Samsung battery/app sleep settings, background/lock, AP isolation, interrupted transfers, revoked authors/devices, expiry and competing gateways. Mark unrun rows NOT RUN. The [desktop benchmark](benchmarks/chromium-nearby.json) is software evidence on one Windows host with synthetic media, not an S24 measurement.

The native synthetic-data instrument is in [spikes/android](../spikes/android/README.md). It measures authenticated Nearby pairing, round trips and a consented checksum-verified 1 MiB transfer, and probes Direct discovery/Aware availability. The product also has a bounded foreground Android BLE/GATT path; the S24 and Tab S9 passed code-confirmed, Wi-Fi-off bidirectional message checks ([evidence and exact APK hash](android-testing.md)). This does not establish automatic fallback, range, battery, background behavior or broad device compatibility. Native Direct/Aware data paths and local-only hotspot remain unimplemented. Android-only availability does not validate iOS or cross-platform pairings; see [current official research](nearby-connectivity-research.md).
