# Samsung S24 / Android 16 field-test guide

The owner's confirmed first phone is a Samsung Galaxy S24 running Android 16. No physical phone tests have run from this workstation. A second Android phone is required for a nearby pairing test; a third is useful for relay testing. Record every model, Android/browser version and security patch in [the device worksheet](nearby-connectivity-device-tests.md).

The owner chose to prepare the build and connect the S24 later. The debug APK is ready at `spikes/android/app/build/outputs/apk/debug/app-debug.apk`. A single Android 17/API 37 emulator passed installation, permission denial/retry, advertising, foreground-stop and JSON-export smoke checks. [Those results](benchmarks/android-emulator-smoke.json) do not fill any physical-phone compatibility row.

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

The native synthetic-data instrument is in [spikes/android](../spikes/android/README.md). It measures authenticated Nearby pairing, round trips and a consented checksum-verified 1 MiB transfer, and probes Direct discovery/Aware availability. A successful build does not establish phone compatibility. Native Direct/Aware data paths, local-only hotspot and BLE remain separate implementation/device gates. Compare actual measurements before selecting a production adapter. Android-only availability does not validate iOS or cross-platform pairings; see [current official research](nearby-connectivity-research.md).
