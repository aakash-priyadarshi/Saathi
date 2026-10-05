# ADR 0001 — Durable events, separate local peer sessions

Date: 2026-10-05. Status: accepted for the PWA/event foundation; native adapters and phone compatibility gated by measurements.

## Context

Saathi needs secure offline reading/authoring, eventual public publication and nearby communication. Browser and OS APIs differ. Calling needs an active high-bandwidth path; relief requests must survive peer disappearance. A unified BLE mesh or universal WebRTC layer would conflate these needs.

## Decision

Persist original signed humanitarian events independently of any session. Keep canonical authorization and quantity allocation in NestJS/PostgreSQL. Use WebRTC as the browser live data/media primitive on a reachable IP network, with explicit offline pairing and no default STUN/TURN. Use established Web Crypto P-256 and canonical JSON rather than new cryptography. Pin a server receipt key while online and distinguish transport delivery, server acceptance and publication.

Benchmark Android Nearby Connections, Wi-Fi Aware/Direct and local-only hotspot instead of prematurely standardizing native transport. Include iOS26 Wi-Fi Aware in the interoperability spike. Do not expose experimental native/browser combinations as supported.

## Consequences

The PWA is useful with no internet and no nearby peers. Browser pairing can require manual exchange; browser installation does not grant native radios. Live calls stop with the network/process, while messages/events persist. Author revocation and stale edits can be rejected after transit. Donor reservations remain online-only. Real phone testing and production HTTPS are release gates. Google Play services is an optional native dependency, not the system's only path.

## Evidence

See `nearby-connectivity-research.md`, `nearby-connectivity-architecture.md` and the reproducible `pnpm spike:nearby` JSON. 3/3 desktop Chromium-process sessions, checksum-verified transfer and synthetic media negotiation passed. This is a software proof with no measured phone range/battery result.

Physical follow-up: the S24 (SM-S921B) and Samsung tablet (SM-X510), both Android 16, paired with the isolated Nearby SDK probe and exchanged generated messages and checksum-verified bytes with no active default network. See `benchmarks/samsung-s24-tablet-nearby.json` for counts, raw samples and test boundaries. This establishes a candidate path for these devices; it does not select a production native adapter before Direct/Aware/hotspot comparison and range/battery/lifecycle tests.
