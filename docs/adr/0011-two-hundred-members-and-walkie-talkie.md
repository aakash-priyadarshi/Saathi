# 200-member channels and foreground walkie-talkie

Status: implemented in the current gated Android/API chat builds; physical scale and live-audio checks remain separate acceptance tests.

The requested 200-member ceiling supersedes the 16-member ceiling in ADR 0010. It does not change that ADR's security/release qualification requirements or select an MLS library. Sixteen remains the limit on joined channels per identity, rather than people in a channel.

## Membership and message architecture

Keep the existing owner-signed, versioned roster and private channel epoch keys. Admit up to 200 roster entries and wrap a fresh epoch key for each authorized reader. On removal, rotate keys and preserve old signed policies for history verification; removed roster slots can be recycled when full. The owner must be reachable for admission and fresh private epochs. Existing six-hour membership expiry and seven-day message expiry still apply.

The roster is bounded at 384 KiB. Actual generated 200-recipient private rosters exceed the previous 90 KB sync limit, so signed sync accepts at most 900,000 bytes. Only this HTTP route has the larger parser budget; attachment and other routes retain their original limits. Replies stay below 1,500,000 bytes. `knownPolicyHashes` and `knownJoinIds` let the server page changed policies and pending admission requests. API authorization and signature checks still precede writes.

The Android client starts with the old request shape/budget and enables paging
only after a verified-endpoint sync response advertises `capabilities.policyPaging`.
The capability is bound to the signed service configuration hash and cleared on
an unsuccessful extended sync. Existing small chats can therefore sync during
an API rollout; large pending rosters wait for a capable API rather than sending
new strict-schema fields to an older deployment.

Native peers negotiate `chatChunks` through `NATIVE_CAPS`. Oversized CHAT frames are split into `CHAT_CHUNK` controls that each fit the negotiated frame size. Assemblies are limited to two per connection, 512 KiB each, 4,096 pieces and 120 seconds. Length and SHA-256 integrity are checked before normal signed chat validation. Connection reset discards fragments. Older peers receive an upgrade-required error for oversized rosters; their existing small chats remain compatible. This is bounded replication, not compression that skips verification.

Unchanged current/history policies sent before inventory/message replay are cached
by hash and connection generation over the ordered native transports. They are
resent on a new connection or policy change, instead of repeating a 200-reader
roster before every chat message. This transmission cache is not a recipient
receipt and does not mark a message delivered.

Message IDs, receipts, peer inventories and retry backoff remain the delivery mechanism. A 200-person group does **not** create 19,900 direct links. The current native client has one active peer connection. Members exchange eligible group history during encounters and can carry it to other admitted members. Thus A→B→C is asynchronous store-and-forward: B needs a later connection to C. Online API synchronization reaches other admitted members when they have internet. Delivery time cannot be promised for out-of-range or suspended devices.

For a later multi-peer implementation, use a small set of active neighbor links with bounded inventories, per-peer receipt progress and radio budgets. Do not scan 200 names in a tight loop. Wi-Fi supports large private attachments using the existing resumable, hash-verified chunks. Current BLE fallback carries text, receipts and control; pictures, clips and recorded audio remain saved until a file-capable connection returns. Live media is never silently converted into a saved recording.

## Direct walkie-talkie

The separate Walkie-talkie switch is scoped to the currently visible DM. Both people open that DM and enable it over a confirmed local WebRTC Wi-Fi connection. This works without internet on a usable shared local network. Nearby Connections and the current BLE transport do not expose a WebRTC audio link; discovering a peer alone does not enable live audio. Keep the existing voice-note and full-duplex call controls.

`NATIVE_CAPS.walkieTalkie` gates this extension. Each PTT control has a P-256 signature over its kind, local Wi-Fi connection session ID and control body. Verify it against the known chat participant's public key. Control bodies bind both identities, the DM, a new enable-session UUID at each endpoint, and a turn UUID. A fresh request/grant binds both enable sessions and proves possession of the participant signing key before enabling the sender microphone.

Controls are `PTT_READY`, `PTT_REQUEST`, `PTT_GRANT`, `PTT_BUSY`, `PTT_STOP`, `PTT_END`. The microphone starts only after a valid grant while the button remains held. Release/cancel, keyboard release, chat navigation, app backgrounding, peer reset and a 30-second deadline mute locally before signaling. A four-second unanswered request expires. Stable participant ID ordering resolves simultaneous requests to one speaker. An old grant cannot reopen capture after release or in another chat. Signals use an ordered signing/sending queue; floor state is neither recorded nor persisted. Receive audio is enabled only while the accepted peer holds the floor. No background scan or polling loop is added. File transfers pause while walkie-talkie is enabled; turn it off to resume files.

Touch uses hold/release. Keyboard uses held Space/Enter; accessibility activation starts a bounded turn and a second activation stops it. Waiting/receiving states keep the microphone off and expose readable status. The controller releases the WebRTC microphone source after each turn. Idle mode retains the existing local connection rather than repeatedly opening microphones.

## Group walkie-talkie: out of scope

Group PTT is explicitly excluded from the current product scope. Do not treat a coordinator, SFU, audio fanout or partition-floor design as a planned implementation milestone. A 200-member asynchronous message roster is not a live-audio service, and recorded group voice notes can use the existing delayed message-delivery model.

## Verification boundary

Unit tests exercise actual 200-person signed private rosters, key unwrap, member 201 rejection, removal/key rotation, bounded fragmented transfers, simultaneous PTT requests, release races, wrong identities, stale sessions and timeouts. PostgreSQL integration verifies 200-person sync, unchanged policy suppression and outsider exclusion. Physical audio quality, real touch release, Wi-Fi loss and battery impact require paired Android devices; 200-radio range/throughput is not established by these fixtures. iOS radio interoperability requires Rohan's Mac and iPhone qualification described in `apps/ios/README.md`.

On 7 October 2026, S24/SM-S921B and tablet/SM-X510 passed actual hold-to-talk
controls and received Wi-Fi RTP audio in both directions. Release disposed the
microphone track; changing the visible DM and foreground loss stopped capture.
The fixture used isolated signed identities and USB only for temporary pairing
and test barriers. Shared Wi-Fi was available; this was not a verified internet
outage, radio range, voice intelligibility or battery test. The recorded result
is `docs/benchmarks/android-walkie-wifi.json`.
