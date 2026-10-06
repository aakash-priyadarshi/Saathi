# Swarm community extension

This milestone extends the existing identity, encrypted store, signed receipt
trust, Nearby connection, resumable file transport and public media pipeline.
It does not create humanitarian accounts for chat participants. Chat authority,
ordinary community statements, verified relief Needs and verified volunteer
Updates remain separate domains.

## Channels, admission and moderation

OWNER, ADMIN, MODERATOR, MEMBER and READ_ONLY map to explicit capability ceilings.
Policy overrides can reduce a role's permissions; they cannot grant beyond its
ceiling. Removing read access removes all dependent permissions and excludes
that participant from fresh key wrapping. Group calls remain gated off.

Announcement members can reply and react where allowed. Top-level posts require
the corresponding capability on raw signed messages, locally and at the API.
Thread replies carry a root ID, have their own view/unread state, and do not
appear as top-level posts. Starting a thread and replying to an existing thread
are distinct permissions. Locked threads retain already authorized history.

Approval descriptors are recipient-bound signed invitations containing the
channel identity and owner/issuer proof, without keys or roster. Admission
stays pending until an authorized decision and a fresh owner policy. Admins can
approve/reject and manage permitted member roles; moderators receive only their
configured capabilities. Remove permits a later authorized rejoin; ban binds
to the stable cryptographic identity until an authorized unban; personal block
does not change channel membership. A display-name edit changes none of these
identities.

Moderation actions bind actor, target, policy hash/version, expiry and stable ID.
Conflicting high-consequence actions against one target/policy require a new
owner policy. Removals, bans, role changes and admissions pause new content
until fresh membership keys are known. A consumed action can carry its original
signed policy as historical proof; this supplies no older key to new members.
The audit stores action metadata, not private message plaintext.

Signed online report batches accept Spam, Harassment, Unsafe content or Other.
Channel moderators receive only eligible report IDs, reasons and message
references. Their signed review actions can travel nearby. Person reports enter
the existing global moderation queue and convey no relief role or global
moderator authority. Reports wait durably when online review is unavailable.

QR uses the same signed invitation as manual paste. Decode leads to verification,
review, then explicit join/request. Camera denial, unsupported camera, oversized
QR and invalid recipient/signature/expiry retain the manual path. No camera
frames are uploaded. QR decoding itself is not admission.

## Nearby Help

Ordinary participants sign temporary Help statements with approximate public
landmarks. Initial requests expire within two hours; revisions cannot extend
them. The requester owns edits, responder assignment, resolution and cancellation.
A responder signs an offer tied to the current request hash. Assigned responder
identity survives ordinary detail edits. Terminal states cannot be reopened.
Expiry is derived locally; an expired Help label can remain for one day without
being advertised or synchronized as an active request.

Limits include three active requests per identity, one per category, one minute
between initial requests, bounded quantity/text, 100 server statements per
author/day, 500 local statements, bounded inventories and six relay hops.
Duplicate IDs/hashes do not count as new submissions. Email, obvious phone-number
patterns and coordinate pairs are rejected in public text. This is a bounded
validation rule, not a comprehensive personal-data or home-address detector;
authors must review landmarks and narrative details before sharing.

Block and signed generic reports apply to Help. Existing global relief ADMIN
review can remove requests; a chat moderator does not automatically obtain that
authority. Category/area demand aggregation opens an existing Official Need draft
only for an authorized prepared relief account. Quantity and current conditions
require explicit review; Help never automatically becomes a donation request.

## Participant reports and media

Ordinary text/photo/video reports use the existing participant signature and a
stable report/media ID. They retain the original author and creation time across
carriers. First server receipt time and actual public publication time are
separate. The public feed includes only approved posts, visibly labels ordinary
reports unverified, preserves chronology, and warns about delayed/stale reports.
Moderation approval does not turn an ordinary participant into a verified
volunteer. Content-warning media requires a reveal action.

Approved participant posts expose a stable public participant fingerprint so a
personal block also applies to later posts. This creates cross-post linkability;
it is not an anonymous-report system. Public feed responses do not expose the
participant's keys, contact details or private group membership.

Photos are conventionally decoded, EXIF-oriented, bounded to 40 megapixels at
input, resized to 1600 pixels at output, JPEG re-encoded and thumbnailed. Videos
up to 60 seconds, 1080p and 16 MiB are asynchronously remuxed from supported
AVC/HEVC/AAC tracks; location/container metadata is not copied. Android does not
promise bitrate transcoding of arbitrary large videos: unsupported/oversized
inputs are rejected with a gallery fallback. The existing server FFmpeg pipeline
normalizes accepted public video again before publication. Selected originals
are never overwritten. No AI alters factual imagery.

Signed media metadata binds MIME, size and SHA-256. Nearby transfers reuse the
existing encrypted-at-rest resumable file store, explicit receive consent and
final hash verification. Received report previews are prepared locally. Public
media can be viewed on the phone; approved online media opens from the public
feed. A withdrawal hides local/online originals when learned and prevents fresh
publication; copies, exports and screenshots elsewhere cannot be recalled.

The gateway sends authenticated statements and bounded report chunks only to
the existing service. It is not an arbitrary network proxy. Stable IDs and hash
checks plus database locks make competing carriers one submission/publication.
Report metadata can arrive before media; the UI distinguishes local save, nearby
share, online receipt, pending media and public publication. Missing dependency
responses remain retryable. Inventories send dependencies before revisions, and
receipt checks rotate over the bounded retained set.

The service backfills signed parent revisions and responder offers before
dependent Help state, including across edited public areas. Known objects keep
receiving revisions after an area edit. Hidden rows do not occupy the public
selection window. Responses are budgeted below the native 256 KiB response
limit; bounded pagination can still require several foreground checks.

## Relay controls and resource bounds

Other people's online forwarding requires explicit Off / Wi-Fi only / Wi-Fi +
mobile consent and a validated allowed network. Public media has a separate
switch. Own explicit publication intent is independent of forwarding consent.
Battery minimum, charging exception, storage headroom and daily allowance apply
before requests, including retries. The allowance conservatively reserves request
bytes, maximum response bytes and 8 KiB overhead; it is not carrier-billed usage.
UTC day rollover preserves the preceding usage ledger for seven days.

Urgent relief and urgent Help frames precede ordinary public statements, chat,
voice, small photos and video/file chunks. Large transfers yield between bounded
chunks. This ordering is not a claim of bounded real-world radio latency.
Foreground synchronization runs periodically while the app is active; Android
background execution, range, battery endurance and unattended carrier behavior
remain separate qualification work.

Public server uploads reserve 512 MiB globally and 64 MiB per original author in
this QA deployment, including retained completed assets. Expired partial chunks
are removed by maintenance. Retained asset cleanup requires an explicit operator
policy; accepting unlimited abandoned media or silently deleting published media
would be inappropriate. Help/report metadata retention and public copy withdrawal
are disclosed in the privacy and release records.

## Settings and gates

More persists System/Light/Dark immediately using the existing theme, edits the
display name without rekeying, controls visibility/relay, and shows saved media
and reserved daily allowance. Safe cache cleanup preserves private chat files
and public media that has not reached the service. It does not clear identity,
pending work or memberships. Production credentials/signers remain outside APKs.

See [group crypto/scale ADR](adr/0010-group-crypto-and-announcement-scale.md),
[milestone evidence](swarm-unified-milestone.md) and the existing offline/private
chat threat models for release limits. Physical radio evidence and synthetic
layout captures must remain explicitly distinguishable.
