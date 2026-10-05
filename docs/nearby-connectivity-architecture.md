# Nearby connectivity architecture

Decision recorded before application transport implementation, 5 October 2026. Read the [research and capability matrix](nearby-connectivity-research.md) and [measured Chromium spike](benchmarks/chromium-nearby.json).

## Chosen architecture

Separate **durable humanitarian events** from **live peer sessions**. Use the secure-origin PWA and IndexedDB for saved public information, drafts, non-extractable signing keys, outbound events, received events, messages and receipts. Keep the canonical NestJS domain services and database locks. The same signed event is carried unchanged through any supported path; a carrier never becomes its author.

Use WebRTC for foreground browser peer sessions over an already reachable local IP network. No STUN/TURN is configured for nearby mode, no arbitrary internet proxy exists, and no cellular media relay is automatic. Offline pairing exchanges complete descriptions out of band. A compressed pairing file/text is the reliable baseline; QR is a convenience only when its payload fits, not an assumed one-code solution for arbitrarily large SDP. Native discovery can later supply the exchange automatically. Use temporary session identifiers and explicit pairing/call consent.

For Android native, benchmark **Nearby Connections against Wi-Fi Aware/Direct and local-only hotspot**. Nearby is the leading GMS convenience candidate; Aware plus a standard IP session is the promising modern interoperable candidate. Keep LAN/manual pairing as a non-GMS/browser path. BLE is a possible bounded event/bootstrap fallback, not the media path. iOS26 Aware must be considered alongside older shared-LAN/Nearby paths. Neither native choice is promoted to production until its physical-device gate passes.

## Evidence and scope

The workstation proof achieved 3/3 independent Chromium-process pairings without a cloud signaling/STUN/TURN service, verified 1 MiB transfers and negotiated synthetic audio/video. Exact timings and throughput are in the JSON; they describe a single Windows host, not Android Wi-Fi performance. Range and battery are unmeasured. These results justify implementing and testing the browser primitives, not claiming Android/iOS compatibility or automatic radio discovery.

Application changes ship offline reading/drafts and original-author server ingestion with automated software validation. Nearby browser UI is an explicit preview pending phone/TLS/network tests. Native radio adapters and iOS release remain gated, rather than filling the repository with unverified production claims.

An isolated Android instrument now exists in `spikes/android`, using the installed Studio JBR/SDK and pinned official Gradle/SDK dependencies. Its debug APK build, lint and signature verification pass locally. The owner's S24 (SM-S921B) and tablet (SM-X510), both Android 16, now have physical foreground Nearby pairing, generated round-trip and checksum-transfer evidence with no active default network. Both reported Direct/Aware features and Aware availability; only the S24's Direct discovery was sampled, with no Direct/Aware data path established. See [the measured record](benchmarks/samsung-s24-tablet-nearby.json). The production native adapter remains undecided pending range/battery/background tests and comparisons against Aware/Direct/local-only hotspot; the current stop-and-wait application throughput does not establish peak radio capacity.

## Events, trust and acknowledgements

Use established Web Crypto ECDSA P-256/SHA-256 and RFC8785-compatible canonical JSON. Browser private keys are non-extractable CryptoKeys stored in IndexedDB; device public keys are registered while online by an authenticated approved volunteer. Signed body binds protocol version, event ID, author, organization, device, type, timestamps, publication intent, payload hash and maximum hops. Relay hop count is outside the author's signature. It is a cooperative forwarding bound, not cryptographic proof of the number of relays.

Carriers retain the original envelope. The server loads the original registered public key, rechecks current author/device revocation and membership, verifies hash/signature/expiry/schema, and executes the existing domain mutation plus event/receipt/audit writes inside one transaction. Repeated event IDs return the original result; different content with the same ID is rejected. Versioned edits conflict visibly; stale changes never reopen closed requests. Donor allocation/order placement remains online-only. Private chat and call media are never gateway payloads.

An offline recipient can check internal signature consistency using the included public key, but **must not infer verified volunteer authority from that self-asserted key**. Only an authenticated canonical response or a separately pinned server-signed receipt can establish acceptance. Server receipts are verified against a server public key pinned during an online visit. Production requires a stable operator-managed receipt signing key; local development may use a clearly ephemeral test key. Invalidated events are retained and reported, not silently deleted.

Visible states stay separate: **Saved on this phone**, **Reached another phone**, **Reached Saathi**, **Published**, **Needs your attention**. An event shared with a peer is still absent from the public website until server acceptance and publication. Relayed event metadata is not a fresh canonical inventory; offline donors must reconnect before purchasing/reserving.

## Transport and bandwidth policy

Every adapter reports detected capabilities and measured session health. Policy chooses the canonical API for authoritative writes; an established local peer for eligible events/messages; local durable storage otherwise. Radio-specific/native discovery stays outside domain logic. Never advertise a peer based solely on the browser supporting RTCPeerConnection. A peer is available after an actual authenticated pairing handshake and data channel opens.

Prioritize control/receipts and urgent events ahead of chat and file chunks. Use bounded frames, backpressure, SHA-256 attachment verification, durable chunk records, resume inventory and cancellation. Incoming attachments require recipient consent; no automatic opening/execution. Large field originals stay on the origin device for the existing authenticated sanitization/moderation pipeline. No private original becomes public merely by arriving nearby.

Calls require explicit acceptance before microphone/camera capture. Pre-negotiate media transceivers, then replace tracks without requiring an online signaling server. Poll WebRTC stats while foregrounded. Reduce video bitrate/resolution/frame rate, pause video when quality worsens, and preserve audio/messages where possible. Disconnection stops capture and leaves durable messages/events intact. Calls are not recorded/uploaded by default. Re-pairing is supported; sustained automatic browser reconnection after process suspension is not promised.

## PWA boundaries

Keep HTTPS in production and the existing secure origin offline. Cache a bounded public shell and static assets. Save public API snapshots separately with their fetch time; do not cache authentication responses, private donor links, emails, session secrets or team delivery records. Store only the logged-in volunteer identity/approved point metadata needed for a locally drafted event; clear private device information on logout, retaining unsent work only with an explicit export/clear choice. Storage failure must surface before a save is described as safe.

Request persistence when the user enables offline preparation. Explain eviction/uninstall risks and provide export. Service workers do not replay arbitrary mutations, keep calls alive or bypass authentication. Foreground reconnect/visibility events and bounded retries drive synchronization. API reachability, not navigator.onLine alone, determines freshness. A successful read is fresh at its recorded time, not an eternal “Up to date” claim.

## Implementation and release sequence

1. Research and independent browser proof — recorded before transport implementation.
2. Secure PWA shell, saved public snapshots, private draft storage, plain-language connection details and donor safety.
3. Shared signed envelope protocol, registered devices, transactional ingestion, server-signed receipts and deduplicated forward/reverse propagation.
4. Browser nearby preview: manual pairing, durable messaging, consent-based calls, bounded attachments and adaptive media; automated Chromium tests.
5. Physical Android testing with the owner's phones, operator HTTPS deployment, then native radio spikes; compare measurements before selecting the production native adapter.
6. iPhone hardware testing and cross-platform/native bridges before enabling those capability rows.

## Remaining physical gates

The test worksheet in `nearby-connectivity-device-tests.md` is required for same Wi-Fi/hotspot/local-only hotspot, permission denial, internet disappearance/return, out-of-range movement, background/lock/battery saver, large files and voice/video. No localhost or emulator result substitutes for these checks. Native hub support is a transport/storage role with multiple peers and original signatures; it is not an authoritative relief server or unrestricted tethering service.
