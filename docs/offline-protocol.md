# Offline and nearby protocol v1

This document describes the implemented browser foundation and its boundaries. See [current research and capability matrix](nearby-connectivity-research.md), [architecture](nearby-connectivity-architecture.md), [ADR](adr/0001-durable-events-and-local-peer-sessions.md), and [physical-device worksheet](nearby-connectivity-device-tests.md). Native radio transports are not implemented or verified.

## Local persistence

The secure-origin PWA caches /offline, /nearby and /connectivity plus static assets. It never caches API responses, authentication pages, team delivery records or donor bearer URLs in CacheStorage. Public request/feed snapshots live in IndexedDB with their fetch timestamp. Private drafts, messages, bounded attachments and pending original media are stored locally before an attempted send.

IndexedDB stores a non-extractable P-256 signing key and approved-volunteer preparation metadata. This is browser-origin storage, not Android Keystore, SQLite, or a claim of hardware key isolation. An origin compromise or another person using the same browser profile can access local work; protect against XSS and clear shared-device data. Export excludes private keys/media bytes; save media originals separately. Storage exceptions are surfaced. Browser eviction, uninstall and manual site-data clearing can remove work; requesting persistence reduces but cannot eliminate that risk.

## Original-author envelope

`packages/protocol` uses established Web Crypto ECDSA P-256/SHA-256 and the RFC8785-compatible canonicalize library. The strict v1 envelope binds a UUID, author, organization, registered device, event type, creation/expiry times, public-relief intent, maximum hops and canonical payload hash. Default lifetime is 24 hours, default hops six; the server caps lifetime at seven days and maximum hops at eight. An envelope is at most 64 KiB. Signatures are base64url Web Crypto P1363 bytes. Supported events are request creation, versioned request changes and text field posts (optionally linked to a request). Media bytes and donor orders are excluded.

A relayed public key proves internal signature consistency. It does not establish volunteer approval offline. Saathi rechecks original key registration, author/device revocation, approved membership, active verified organization, expiry and domain authorization on ingestion. Carriers never become authors. Unregistered/invalid keys fail validation; authenticated, well-formed domain failures can produce signed rejection/conflict receipts.

PostgreSQL advisory event locks, domain row locks/versions, the canonical mutation, event record, audit and signed receipt are committed transactionally. Concurrent carriers of one event create one logical request and return the same public ID. Changed contents under an existing ID are rejected. Expired deadlines are checked at application time. Offline donor allocation is unavailable: reservations and purchases require current authoritative quantities.

## Delivery and publication

Keep four separate visible states: Saved on this phone, Reached another phone, Reached Saathi, Published. A nearby acknowledgement confirms a cooperative peer's durable receipt; it is not publication or a delivery guarantee. Server receipts bind event ID, payload/signature hashes, status, canonical public ID where available, optional field-post ID, timestamp and server key ID. The public receipt key is pinned from Saathi's secure origin while online. An unknown key or altered receipt cannot become a trusted publication status. Older receipts cannot overwrite later withdrawal.

Scheduled posts remain Accepted until their actual publication time. Media arriving later attaches to the original author's field post through an authenticated endpoint, goes through existing sanitization, and changes its receipt to Accepted while moderation is pending. Receipt reconciliation reflects approval/rejection. Admin invalidation cancels the associated canonical request or hides the field post and removes public media derivatives. Preserve the production receipt key in secure backups; key rotation requires an explicit offline trust/keyring migration design before rollout.

## Foreground nearby preview

Browser WebRTC uses an existing reachable local IP path with no STUN/TURN or cloud signaling. Compressed two-way invitations/replies are exchanged manually by file/text; QR is offered only when the payload fits. Descriptions are bounded and expire after two minutes. Users compare a session fingerprint code before exchanging work. No automatic browser radio discovery, radio control, internet forwarding or cellular media gateway is provided.

Encrypted ordered data channels carry frames capped at 24 KB. Exact-ID inventory is capped at 500 events, requests at 50 IDs per batch, and event fragments at 64 KiB/eight parts. Peers ask only for missing envelopes while receiving new receipts for known events; urgent structured requests go first. Sharing requires explicit user action. TTL/hops are cooperative relay bounds, not cryptographic proof of the path. Signed receipts can return for an already-known event after its relay lifetime expires.

Messages are saved before send and acknowledged after receiver persistence. Unsent messages are resent to a newly paired person only through an explicit action. Session identifiers are temporary. Calls require invitation and acceptance before microphone/camera capture, use the live reachable session, and are not recorded/uploaded. Quality checks reduce video rate/resolution and pause video on worsening conditions; file transfer can defer while that media connection is weak. Process suspension/screen lock may interrupt browser calls. Losing the connection stops capture and preserves durable work.

Attachments are opt-in image/text files at most 1 MiB, chunked with backpressure and SHA-256 verification. Accepted parts persist, cancellations stop sending, and a later pairing can explicitly re-offer/resume only missing parts. Up to twenty attachments, 500 relief events, 1,000 messages, 100 drafts and 120 public snapshots bound storage; original field media additionally faces per-file/form and browser quota limits. Original photos/videos wait for a direct authenticated upload, stay private and require sanitization/moderation before public use.

## Gateway policy and limits

Only signed public-relief text enters the gateway. Users can explicitly send/check their queue, or opt into foreground automatic upload on reconnect (including normal data charges). Private messages, recordings and media originals are excluded. Reachability is based on Saathi responses, not just navigator.onLine. A failing network/server/rate-limit path stops the batch; later foreground checks retry. There is no browser always-on background relay guarantee.

Automated browser checks cover offline cold opening, persisted drafts/messages, stale donor guards, local calls with synthetic media, consented/checksummed files, interrupted/resumed transfer, two-hop relay while the author is absent and reverse receipts. Database checks cover authorization, revocation, conflicts, duplicates, tampering, moderation and invalidation. Desktop/mobile-width Chromium is software evidence only. The owner's Samsung S24 / Android 16, physical range/battery/lock behavior, native adapters and every iPhone/cross-platform combination remain NOT RUN until measured.
