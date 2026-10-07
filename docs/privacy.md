# Privacy and moderation

Ordinary Android participants can explicitly create signed public Help and
unverified text/photo/video reports. These disclose the chosen public name,
stable participant fingerprint, public landmark, narrative, creation time and
sanitized media to nearby recipients. When reports reach the server, they remain
outside the public feed until an administrator approves them. The stable
fingerprint makes posts linkable and supports personal blocking. Carriers retain
the original author's statement; being a carrier does not make them its author.
Participant publication does not disclose private chat or confer volunteer
verification. Conventional photo/video processing removes metadata without
altering factual scene content; visible faces, signs and narrative may still
identify people. Authors review these before sharing.

Help expires within two hours and can remain labeled expired locally for one
day. Public report relay eligibility lasts at most seven days. The QA service
retains signed event metadata and approved media for deduplication, review and
publication until an operator-defined retention policy removes them; expiry
alone is not a server erasure guarantee. Signed withdrawal and learned moderation
hide subsequent public views and forwarding, but cannot recall prior copies.
Forwarding another person's public objects online requires relay consent;
media forwarding has its own switch, battery/storage limits and conservative
daily byte reservations. See [community boundaries](swarm-community-architecture.md).

CJP Swarm development/QA chat encrypts private profiles, drafts, messages, policies, keys and attachment chunks on Android. Chosen names are not verified identities. DMs and invite-only content are encrypted at the operational server; participant public keys, membership, timestamps and attachment sizes remain metadata. Open channels are member/server-readable. Removal cannot recall earlier copies; offline policy expires within six hours and message/attachment retention is seven days. Discovery counts the confirmed current peer. There are batched in-app alerts, with mute, rather than a push/background-delivery guarantee. See [the detailed contract and limits](chat-channel-architecture.md).

Public responses include display names, organization names, designated receiving-point details, request quantities and status. They exclude private emails, phones, home addresses, device/session IDs and internal organization/request IDs. Exact coordinates are omitted unless `exactLocationApproved` is true; relief points created through the portal are approximate by default.

Donor email is optional and used only for requested transaction notifications. Public browsing requires no permanent account. A private tracking token is a capability, not an identity check. Staff delivery views omit donor email and tracking tokens. Audit snapshots for public requests contain public fields, not authentication secrets. Order IDs and delivery notes are operational private data; define a retention period and restricted staff access before deployment.

EXIF/GPS/device metadata is removed from image derivatives. Video files are re-encoded with metadata and chapter removal. Originals and sanitized derivatives remain private until a coordinator or admin approves the attached field update. Approval publishes the sanitized derivative; rejection or hiding removes public objects. No static media can guarantee anonymity if people, documents, landmarks, or addresses are visible in its pixels. Contributors must review the visual content. Automatic face blur remains an explicit future feature.

Verified volunteer posts remain attributed to approved volunteers; ordinary participant and guest reports are separately labeled unverified. Every field post begins pending and its media stays private until a coordinator or admin approves it. Coordinators can moderate their organization's posts; admins can moderate any post, cancel requests, and suspend volunteers. Admins can deactivate organizations, review abuse reports and read the audit trail. Every moderation state change is audited. Hiding attempts to remove public files; prior downloads, screenshots and uncontrolled caches cannot be recalled. Production must use no-store or short caching and support cache invalidation.

The initial implementation records a public location string rather than harvesting device GPS. It has no advertising, third-party analytics, SMS or payment processing. Local/demo records are prominently identified. Prior to a live deployment, publish an operator-specific privacy notice with contact, jurisdiction, retention, access/deletion procedures and incident response. Historical relief/audit records require a defensible retention policy; do not silently erase their verification history.

Local messages, calls and attachments are private to the selected nearby session and local device. Pairing uses temporary session identifiers; it does not advertise a name, email, phone, GPS position or stable hardware identifier. Call capture starts only after acceptance, ends on hang-up/disconnect and is neither recorded nor uploaded by Saathi. Invitations contain local network candidates and should be exchanged privately with the intended person; compare the matching code before sharing.

An approved author explicitly prepares and signs a public relief event. These events contain author/organization identifiers and public relief content so the server can authenticate the original author. They are eligible for store-and-forward independently of private conversations. Each internet carrier separately opts in to sending public events; it never becomes their author and does not proxy arbitrary traffic or calls. Nearby receipt does not mean public publication.

IndexedDB stores private work and nonextractable signing keys on the current browser profile. It is not protection against a compromised device or scripts executing on Saathi's origin. Export important text/events and download original media before clearing storage; the text export excludes private keys and media bytes. Clear/logout removes signing keys and private local work, with device revocation attempted while connected. Field originals wait for direct authenticated internet upload, sanitization and moderation. Browser storage can be evicted; installation alone does not guarantee retention.
