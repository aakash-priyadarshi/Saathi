# Field connectivity and battery plan

Internet loss and radio interference are different failures. If cellular service
is unavailable but local radios work, Swarm can exchange data offline. If
interference blocks Wi-Fi or Bluetooth itself, that path cannot deliver; the app
retains its durable outbox and retries when a usable confirmed link returns. The
app cannot reliably diagnose a jammer from a failed connection and must never
label itself jammer-proof or mark queued work delivered.

## Current routes

| Route                      | Shared router/manual hotspot required?                 | Current capabilities                                                            |
| -------------------------- | ------------------------------------------------------ | ------------------------------------------------------------------------------- |
| Android Nearby Connections | No; Wi-Fi/Bluetooth remain enabled                     | Direct offline pairing, signed text/control and resumable encrypted saved media |
| Local WebRTC Wi-Fi pairing | Yes, a usable shared LAN/hotspot; internet is optional | Data/files plus direct live voice/video and walkie-talkie                       |
| Direct BLE/GATT fallback   | No                                                     | Bounded text/control/receipts; saved media waits for a file-capable connection  |
| Signed API sync            | Internet required                                      | Authorized durable message/attachment sync; does not carry local live audio     |

[Google Nearby Connections](https://developers.google.com/nearby/connections/overview)
manages offline Bluetooth/Wi-Fi peer connections. Its current Android adapter
uses `P2P_POINT_TO_POINT`, with one active peer, rather than 200 simultaneous
connections. It can form a direct path without an infrastructure Wi-Fi address.
Do not mistake the absence of `wlan0` internet/network attachment for the absence
of Android Nearby capability.

[Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifi-direct)
forms device-to-device groups without a preexisting hotspot. One participant
acts as group owner, selected by the system; this differs from asking someone
to manually enable a mobile hotspot. A dedicated Wi-Fi Direct or Wi-Fi Aware
WebRTC adapter could remove the shared-LAN requirement for live audio. It is a
future, hardware-qualified Android adapter, not part of the current live-audio
implementation. Google's Nearby Swift route is the cross-platform starting
point; do not assume Apple's system Wi-Fi Direct interoperability.

## Assistance implemented in the app

Nearby explains that direct Android text/media pairing needs neither internet
nor a manual hotspot. Connection options separately explain live-audio/browser
pairing. An existing Wi-Fi interface/address check, refreshed with app state and
on foreground return, reports whether local Wi-Fi pairing has an address.
USB tethering does not count. This is a hint, not a connectivity guarantee:
client isolation and incompatible networks can still prevent pairing.

Prepare each app online before field use so its signed feature configuration
is cached. Browser users should also select “Save app for offline opening”; this
waits for receipt verification keys as well as the app shell. The isolated
native radio fixture enabled transport features locally, so it qualifies the
radio path rather than first-run HTTPS configuration provisioning.

If no local Wi-Fi address exists, the app suggests one consenting participant
enable a hotspot and others join, with system settings buttons. It never silently
enables a hotspot, changes saved networks or asks users to host merely to send a
text. Choose a well-charged participant for manual hosting; internet tethering
is unnecessary. A hotspot supplies network access, not Swarm application-level
message relay or group-audio fanout.

## Power and delivery rules

- Search is explicit, foreground-only and bounded to one minute. No continuous
  background sweep of every member is introduced.
- Reuse an established connection. Exchange inventories on connection/recovery;
  request missing IDs, deduplicate and acknowledge only after durable save.
- Existing retry backoff and send/receive/relay budgets bound work. Pause bulk
  files during live audio. BLE sends small messages first and leaves media queued.
- A display-name change remains part of the signed profile exchange and rare
  refresh, not a separate polling loop.
- Walkie-talkie allocates microphone capture only during an acknowledged turn,
  disposes it on release, stops on navigation/background/disconnect and has a
  30-second limit. Keeping a live Wi-Fi connection still consumes power.
- Group forwarding is asynchronous between admitted peers the carrier meets.
  A→B→C requires B to subsequently connect to C in the current one-peer client.
  Membership size does not imply immediate delivery to all 200 people.

## Internet gateway status and eventual delivery

When a confirmed encounter starts, Android peers exchange signed gateway
leases. A lease says that a particular device recently reached the service and
whether it accepts text or opted-in media relay; it expires after 120 seconds.
Nodes gossip at most 16 gateway hints and stop advertisements after 16 hops.
These hints are not a source-routed end-to-end path. The displayed gateway and
hop count are recent observations, not a live global map. A status update
spreads only as devices meet, and can be stale or missing.

An offline event can reach the service through relay only if it is carried over
successive encounters to a phone with validated internet and relay enabled,
before the event expires and within the hop limit. If no such contact occurs,
Swarm keeps the event queued and retries on a later encounter. This is
eventual, opportunistic delivery when a path becomes available; it does not
send to 1,000 phones at once or guarantee that every phone or server receives an
event. Queues, deduplication, signatures, expiration, relay opt-in, battery
guards and daily data budgets bound the work. Internet relaying is limited to
the approved public help/update event and media flows; it is not a general
internet proxy for private chat.

On 7 October 2026, SM-S921B and SM-X510 passed direct Nearby DM/channel text and
saved photo/synthetic AAC/video transfers with no infrastructure Wi-Fi address
and no validated internet. Wi-Fi remained enabled; no manual hotspot or API was
used. Payload hashes, DM delivered/read receipts and membership/blocking checks
passed. See `benchmarks/android-chat-radio-nearby.json`. This does not establish
radio-interference resilience, sustained throughput or battery drain.

Next qualification: Wi-Fi-off BLE text; outage and radio-restoration retries in
DMs and channels; hotspot host/client roles; client
isolation; three actual relay devices; and measured latency, drain and thermal
behavior. No jammer experiment is required or claimed. Record transport,
internet validation, payload hashes and signed receipts for each result.
