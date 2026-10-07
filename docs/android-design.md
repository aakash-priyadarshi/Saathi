# Design System: CJP Swarm Android

## Overview

**Creative North Star: "The Community Noticeboard"**

Android extends the established community noticeboard into named conversations: warm paper, forest ink, short Lora headings, and Manrope operational text. Material 3 supplies familiar controls, dialogs, and adaptive navigation. The [root design system](../DESIGN.md) remains the web contract; this document records the actual Kotlin/Compose implementation without converting web `px` values into native measurements. This is a code-led Operate extension, with no approved image comp or replacement visual world.

The official app/store name is **CJP Swarm**. The main masthead displays **SWARM** with **by CJP**; the compact conversation bar keeps SWARM. More contains the About material, “Connect nearby. Coordinate together.”, and “Developed by Cockroach Janta Party.” Branding is configurable through the [Android build configuration](../apps/android/app/build.gradle); existing Saathi repository, package, protocol, and storage identifiers stay compatible.

**Key Characteristics:**

- Paper and forest roles with explicit System, Light, and Dark appearance choices and matching system-bar icon contrast.
- Native Material navigation bar on phones and rail at wider widths.
- Separate direct messages, discussion/announcement channels, unverified Nearby Help and participant reports, and authoritative relief needs and verified volunteer updates.
- Latest conversation context, deliberate history scrolling, and visible-message read boundaries.
- Title, quantity, organization, and next action near the top of a need card.
- Explicit freshness, nonproduction disclosure, and current connection capability.
- Separate local saving, nearby sharing, central acceptance, publication, and attention states.

Implementation authority is [SaathiTheme.kt](../apps/android/app/src/main/java/org/saathi/android/SaathiTheme.kt), [MainActivity.kt](../apps/android/app/src/main/java/org/saathi/android/MainActivity.kt), [ChatScreens.kt](../apps/android/app/src/main/java/org/saathi/android/ChatScreens.kt), [CommunityScreens.kt](../apps/android/app/src/main/java/org/saathi/android/CommunityScreens.kt), [SwarmStartup.kt](../apps/android/app/src/main/java/org/saathi/android/SwarmStartup.kt), [ChatRepository.kt](../apps/android/app/src/main/java/org/saathi/android/ChatRepository.kt), [TeamScreens.kt](../apps/android/app/src/main/java/org/saathi/android/TeamScreens.kt), [NearbyScreens.kt](../apps/android/app/src/main/java/org/saathi/android/NearbyScreens.kt), and [SaathiViewModel.kt](../apps/android/app/src/main/java/org/saathi/android/SaathiViewModel.kt). The [Android surface contract](../.impeccable/surfaces/android.md) records its Operate mode and direction. Measurements below are source observations, not a new token scale. The root `DESIGN.md` and `.impeccable/design.json` retain their incumbent web authority; this native extension introduces no duplicate token frontmatter or native sidecar.

### Finish evidence

The current unified community milestone adds signed temporary Help, ordinary unverified reports, local QR invitations and the supplied-logo startup. Runtime checks, CI status, physical store checks and two-device Nearby proof belong to the [unified evidence record](benchmarks/android-unified-community.json), with each check's own artifact and limitations. This design document does not carry test times or treat an earlier runtime artifact as the current visual build.

The 6 October 2026 finish review at local `.impeccable/review/community-native/community-finish-review.md` requested four corrections. The finding-by-finding `.impeccable/review/community-native/community-fix-verdict.md` records all four **resolved** and `disposition: ship`: full Announcements context with separate named search/settings actions, all five phone destination labels at 2× text, 8dp separation between Help choices, and the shipping raster inventory/evidence scope. The reviewer reread all 33 required native originals and both public-feed originals at their existing paths; all were valid, with no mandatory capture missing or material regression found in this correction batch. This ship disposition covers those four fixes only, rather than a new whole-surface audit.

The current visual verdict binds to `apps/android/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `cb98308fe909ba2d80329f3808ab90113e5fd9e8da1fa7a8cb7563beed1539c2` (77,215,868 bytes). The subsequent synthetic QR test-hint alignment changed no UI or app APK. Current native originals are local `.impeccable/review/community-native/{phone,tablet,emulator}-*.png`; public-feed originals are `.impeccable/review/community-web/desktop.png` and `mobile.png`. Physical phone captures are 1080×2340, physical tablet captures 2304×1440, and emulator captures 1080×2400. Fixtures are fictional and separate from owner storage. These local review files are ignored QA evidence, rather than shipping assets or public Git artifacts.

| Current evidence                                                                                                                    | Recorded surface and limit                                                                                                                                                  |
| ----------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `phone-announcement.png`, `phone-thread.png`, with tablet/emulator counterparts                                                     | Complete standard channel name and separate status/search/settings row; latest thread context remains above the composer, with earlier content available through scrolling. |
| `emulator-help-large-text.png`, `emulator-help-composer-large-text.png`                                                             | All five destination labels at 2× text; scrollable Help form continuation with separated choices and visible dialog actions.                                                |
| `phone-help-composer.png`, `tablet-help-composer.png`, `emulator-help-composer.png`                                                 | Category, audience and priority choices retain visible horizontal gaps and native Material control sizes.                                                                   |
| `*-chats.png`, `*-help.png`, `*-updates.png`, `*-report-composer.png`, `*-contact.png`, `*-settings.png`, plus light/dark originals | Native community states across the supplied phone, tablet and emulator matrix; synthetic layout evidence with truthful unverified, saved, received and consent meaning.     |
| Community web `desktop.png`, `mobile.png`                                                                                           | Public participant report remains explicitly Unverified, with separate authored/received times and delayed or stale meaning; fictional layout evidence.                     |
| [SwarmStartup.kt](../apps/android/app/src/main/java/org/saathi/android/SwarmStartup.kt)                                             | Finite supplied-logo reveal and immediate accessibility paths verified in source; runtime timing and hardware motion/reduced-motion remain unverified.                      |

Visual review does not certify transport, cryptography or production readiness. Earlier verdicts below retain their historical scope and artifact bindings.

The 6 October 2026 local-only QA verdict at `.impeccable/review/android/swarm-fix-verdict.md` records `disposition: ship` for exactly three listed findings: latest/history positioning with visible-message reads, all phone destination labels, and measured nearby-only persistence wording. All three are resolved at that fix-list scope. This is not whole-surface, transport, cryptographic, or production approval.

That earlier verdict and its captures are ignored local QA evidence, intentionally absent from public Git artifacts. The following table records that historical milestone, not the current community APK. Its paths identify local captures, not public hyperlinks. Public evidence and limits are recorded in the committed [Android testing report](android-testing.md) and [UI/storage benchmark record](benchmarks/android-swarm-ui-storage.json). Native captures reside under local `C:/project-bussiness/Saathi/.impeccable/review/android/`; web brand captures reside under local `C:/project-bussiness/Saathi/.impeccable/review/swarm-web/`.

| Evidence                                                                                                 | Recorded surface and limit                                                                                                                                                                                                               |
| -------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `emulator-swarm-chats.png`, `emulator-swarm-dm.png`                                                      | Final APK, phone-sized emulator; all five destination labels and compact DM composition. Chats includes an intentional public-refresh loading state with saved conversations already loaded.                                             |
| `emulator-swarm-keyboard.png`                                                                            | Final APK, native keyboard; composer remains above the IME.                                                                                                                                                                              |
| `emulator-swarm-channel.png`, `emulator-swarm-channel-dark.png`, `emulator-swarm-channel-large-text.png` | Final APK, channel in light/dark and 2× text; transcript continuation below the viewport is expected.                                                                                                                                    |
| `tablet-swarm-chats.png`, `tablet-swarm-channel.png`                                                     | Final APK, physical SM-X510; labeled rail and wide conversation layout. Taskbar/status chrome belongs to Android.                                                                                                                        |
| `phone-swarm-chats.png`, `phone-swarm-dm.png`                                                            | Physical S24 captures retained from the earlier post-label/layout correction build. They do not prove a final-APK phone recapture or final phone read test; the disconnected phone's unavailable hardware check was explicitly deferred. |
| `emulator-swarm-long-history-latest.png`, `emulator-swarm-long-history-scrolled.png`                     | Final APK, fictional long-history UI proofs: newest context opens visibly and deliberate history scrolling remains available.                                                                                                            |
| `emulator-swarm-nearby-status.png`                                                                       | Final APK, explicitly labeled fictional connection-state preview of “Connected nearby · Saved work remains on this phone”; component-state evidence, not a radio test.                                                                   |
| Web `desktop.png`, `mobile.png`                                                                          | Settled incumbent noticeboard with SWARM/by CJP and footer credit; branding context, not native evidence.                                                                                                                                |

The earlier five viewport/storage cases passed on the physical SM-X510 and API 37 emulator, including offscreen unread history, incoming messages while scrolled back, one Read receipt on returning to latest, and own sends remaining visible. The [historical benchmark record](benchmarks/android-swarm-ui-storage.json) binds those results to their tested artifact and records timings. Stores are isolated fictional UUID stores with startup services disabled; store reopen is not reboot/process-death coverage. The reviewer inspected supplied assertions and logs rather than rerunning them independently. Earlier radio/call proof hashes retain their scope in [Android testing](android-testing.md).

The earlier four-finding foundation verdict remains local at `.impeccable/review/android/finish-verdict-final.md`, with its prior need/Team captures and [device benchmark record](benchmarks/android-product-devices.json). It resolved public request state truth, completed meaning, phone task priority, and contextual Back at its own scope; it did not separately picture unavailable verification or exercise a Back gesture. The earlier three-fix continuation checked its own regressions, with no new broad defect hunt. No native HTML/CSS detector ran. The existing web detector result is `[]` at local `.data/tooling/swarm-design-detector.json`. Transport, browser file interoperability, and release limitations belong to [Android testing](android-testing.md) and [Android release](android-release.md).

Private chat remains a development/QA preview; release builds disable it pending independent cryptographic review. A visual ship disposition does not remove this gate.

## Colors

The native theme reuses the root palette through Material roles. These are aliases to existing root frontmatter primitives, not new colors. `SaathiTheme` applies the saved System, Light or Dark appearance choice; System uses `isSystemInDarkTheme()`. It also selects light/dark status-bar and navigation-bar icon contrast through `WindowCompat`. It does not enable wallpaper-derived Dynamic Color.

| Material role                        | Light root token | Dark root token | Use                                                          |
| ------------------------------------ | ---------------- | --------------- | ------------------------------------------------------------ |
| `primary`                            | `accent`         | `dark-accent`   | Actions, selected states, line icons, and quantity progress. |
| `onPrimary`                          | `surface`        | `dark-paper`    | Contrast text on filled primary controls.                    |
| `primaryContainer`, `surfaceVariant` | `soft`           | `dark-soft`     | Notices, connection strip, and selected tonal surfaces.      |
| `onPrimaryContainer`                 | `ink`            | `dark-ink`      | Text on primary containers.                                  |
| `background`                         | `paper`          | `dark-paper`    | Page canvas and top app bar.                                 |
| `onBackground`, `onSurface`          | `ink`            | `dark-ink`      | Main readable information.                                   |
| `surface`                            | `surface`        | `dark-surface`  | Need cards, navigation, and plain containers.                |
| `onSurfaceVariant`                   | `muted`          | `dark-muted`    | Supporting copy, metadata, freshness, and hints.             |
| `outline`, `outlineVariant`          | `line`           | `dark-line`     | Need-card borders, field outlines, and separators.           |
| `error`, `onErrorContainer`          | `red`            | `dark-red`      | Attention states and urgent labels.                          |
| `errorContainer`                     | `red-bg`         | `dark-red-bg`   | Nonproduction disclosure and priority badge backgrounds.     |

The theme aliases `secondary` and its on/container roles to the corresponding primary roles. `surfaceTint` uses primary; `surfaceContainer` and `surfaceContainerLowest` use surface; high/highest containers use surfaceVariant; low containers use background. Other scheme roles retain their Material 3 defaults. Android's shared `Notice` uses primaryContainer for closed and unavailable states as well as positive information; visible text supplies the meaning. The web's amber caution styling is not applied to this native component.

The supplied-logo startup has its own source-coded forest ground (`#003824`) and cream subtitle (`#fff2d5`), taken from the supplied identity treatment. These are local startup values, not additions to the application palette or duplicate theme tokens. The paper/forest Material scheme resumes after the finite reveal.

**The Semantic Color Rule.** Pair every status color with written state. Nearby connection is not volunteer verification, and local saving is not publication.

## Typography

**Display Font:** local Lora.  
**Body and Control Font:** local Manrope.

The bundled font files are [lora.ttf](../apps/android/app/src/main/res/font/lora.ttf) and [manrope.ttf](../apps/android/app/src/main/res/font/manrope.ttf). Manrope registers weight variations at 400, 500, 600, and 700; Lora is registered as one local family. Their SIL Open Font License 1.1 notices ship in [lora-license.txt](../apps/android/app/src/main/assets/lora-license.txt) and [manrope-license.txt](../apps/android/app/src/main/assets/manrope-license.txt).

Text uses `sp`, so Android's font setting changes its scale. The source customizes selected Material 3 roles; dimensions not overridden below remain the installed library's defaults.

| Role                                         | Implemented treatment                                                                                                   |
| -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `displaySmall`                               | Lora (32sp, 40sp line height).                                                                                          |
| `headlineMedium`                             | Lora (28sp, 36sp line height); shared page headings and prominent quantities. Need-card quantities request bold weight. |
| Needs invitation                             | Two Lora lines derived from headlineMedium (22sp, 28sp line height); second line italic and primary color.              |
| `headlineSmall`, `titleLarge`, `titleMedium` | Manrope, bold; remaining Material metrics inherited.                                                                    |
| `bodyLarge`                                  | Manrope (25sp line height); remaining metrics inherited.                                                                |
| `bodyMedium`                                 | Manrope (22sp line height); remaining metrics inherited.                                                                |
| `bodySmall`                                  | Manrope (18sp line height); freshness, notice copy, and metadata.                                                       |
| `labelLarge`                                 | Manrope, bold; written action labels.                                                                                   |
| `labelMedium`                                | Manrope; remaining metrics inherited.                                                                                   |

The full masthead uses `titleLarge` for SWARM and `bodySmall` for by CJP; the compact conversation masthead uses `titleMedium`. Chats/Nearby/More headings use the existing Lora `headlineMedium`. Conversation names use Manrope `titleLarge`; message bodies use `bodyLarge`; timestamps and delivery labels use inherited `labelSmall`. Conversation row names may ellipsize to one line and previews to two; the conversation header allows two name lines. These are observed role assignments, not a new branding type scale.

Unlisted roles retain Material 3 defaults, including the `titleSmall` used by shared notice headings. Do not describe every native text role as a custom Manrope token.

**The Heading First Rule.** Keep the human-readable need title ahead of quantity, category, and organization metadata.

## Layout

The edge-to-edge activity uses a Material `Scaffold`, its content padding, consumed window insets, status-bar padding on the rail, and `imePadding()` for keyboard clearance. Content is centered with a maximum width (1000dp). Compact width uses five always-labeled bottom destinations outside conversations; at width (600dp) and above, a rail (104dp) replaces the bottom bar and official needs use two columns. The rail stays available in a wide conversation. Other screens remain scrollable single-column lists. The compact navigation bar has a minimum height of 80dp; at `fontScale >= 1.5`, its minimum becomes 112dp and labels can wrap to two lines. The reviewed 2× state keeps all five names complete without reducing the user's font scale.

Chats opens by default. Its padded list (20dp) uses gaps (12dp), an explicit saved-chat search, then Direct messages and Channels groups. Conversation rows use tonal avatars (44dp), readable names/previews, timestamps, unread counts, mute state, and thin rules. Nearby keeps discovery actions before People and Channels nearby; More collects profile, relief-team access, saved work, connection options, and About material.

A compact SWARM app bar and contextual Back reserve the phone conversation for its header, transcript, and composer. The global connection strip and bottom navigation are absent in that conversation; connection meaning lives in its header. The avatar and conversation title have their own row, with the title allowed two lines; a separate row carries connection/membership status and the named search/settings actions. The reviewed standard Announcements title is complete on the phone, tablet and emulator. Search opens only through its labeled icon action. The transcript has a retained reverse lazy-list state, padding (20dp), and row gaps (12dp). Message surfaces occupy up to 88% of row width with a maximum (520dp). The composer uses a multiline field (up to four lines), attachment and voice/send actions, and container padding (12dp). The keyboard inset keeps it clear of the IME.

Message bubbles keep content and delivery status primary. A single overflow menu holds copy, thread, attachment, appreciation, safety and moderator actions; empty reply counts and unused reactions stay out of the transcript. Attachment export, nearby sharing and download/resume use plain action labels rather than transport jargon. Help/need shortcuts remain available from that menu for eligible messages.

Needs use a lazy grid with outer padding (20dp) and horizontal/vertical gaps (16dp). The full-width grid header contains the compact two-line invitation, section heading and refresh action, search, one horizontally scrolling row of state and category filters, then snapshot freshness. A vertical divider (24dp high) separates state filters from categories. The two state filters and all categories share this row to keep the first usable need close to the search.

Need cards place title first, remaining or received quantity second, and organization plus “View need” or “View impact” third. Category/priority, short description, progress, counts, and public location follow. Card padding (20dp) and internal gaps (12dp) preserve readable grouping. The description caps at three lines; operational state and action labels are not placed in that truncated copy.

Needs begins with the Official Needs/Nearby Help selector, whose chips have an 8dp gap. Nearby Help uses a single-column ruled list with public-area checking, temporary request meaning, and written offer/update/resolution actions. Its dialog body is scrollable with a maximum height of 480dp; category, audience and priority `FlowRow` choices each use 8dp horizontal separation and wrap naturally.

The chronological Updates hub puts Create Update and, for a prepared relief-team phone, Verified volunteer update above its list. Participant/volunteer labels, authored time, online receipt/publication time when available, media state and content-warning decisions stay in the row's reading order. Help, Updates, Nearby, Saved, Team, and detail screens use lazy lists with padding (20dp). Observed list gaps are 16dp for Help, the Updates hub, draft forms and Nearby people/channels, 18dp for Connection/Saved/Team/detail, and 20dp for More. Shared headings use gaps (8dp); notices use internal padding (16dp), icon/text gaps (12dp), and text gaps (6dp). These values are contextual measurements, not a universal spacing ladder.

**The Task First Rule.** Preserve the reviewed normal-phone path from compact invitation to search, filters, freshness, and the first need's quantity, organization, and complete action. Large text, landscape, and keyboard states may continue below the viewport through scrolling; the captures do not prove all device/font combinations.

**The Visible Read Rule.** Open the newest conversation context, preserve deliberate history scrolling when new incoming messages arrive, and expose “Latest messages” while away from the newest end. Read receipts follow visible inbound messages; loading saved history is not reading it.

## Elevation & Depth

Need cards are outlined, flat surfaces. Thin Material rules and tonal containers carry grouping; no custom shadow or hover lift is implemented. The compact `NavigationBar` explicitly uses tonal elevation (0dp). Other Material controls and dialogs retain their library defaults. Android therefore inherits Material depth behavior rather than the web's blanket CSS no-shadow rule.

Material controls, progress indicators, and interaction feedback keep their native behavior. The Nearby signature draws two already-visible nodes; the connecting line settles over 420ms with `FastOutSlowInEasing` when a confirmed, unblocked peer becomes reachable. The source checks Android's global animator-duration scale: Remove animations (`0`) takes a zero-duration path. It does not run a permanent scanning loop. Message entry uses a short fade (140ms), no placement animation, and no exit fade; transcript positioning uses immediate scrolls. Static screenshots do not verify motion in execution, and this verdict does not independently certify every reduced-motion setting or gesture.

## Shapes

Need cards use restrained corners (12dp) and a thin outline (1dp). The Nearby formation container uses corners (12dp), message bubbles (14dp), shared notices (8dp), and urgency badges (4dp). Avatars are circles. Buttons, chips, dialogs, fields, and other cards use Material 3 shape defaults unless their call site overrides them. There is no global custom `Shapes` scale in `SaathiTheme`.

## Components

### Buttons and fields

Filled, outlined, and text buttons are native Material 3 components with written actions. Team sign-in, draft-saving, and signed-saving actions fill their container. Email, password, numeric quantity, and authenticator fields select their appropriate keyboard types; passwords use masking. Inputs use `OutlinedTextField` with persistent labels. Busy state adds a top progress indicator and disables affected actions. General notices appear in a dismissible tonal strip; saved receipt rejection/conflict/invalidation also uses error-colored text.

### Filters and need cards

Native `FilterChip` selection expresses Active needs/Completed and category filters in the shared scrolling row. A need card is one clickable `OutlinedCard` with a written action; line icons supplement its text. Active cards show remaining quantity and committed progress. Completed cards use received quantity and received progress, “View impact,” and “Help has arrived”; they omit urgency and “still needed.”

### Navigation and Back

Updates, Chats, Nearby, Needs, and More are the five persistent destinations. Native navigation bar and rail use selected tonal indicators and visible labels; phone icon accessibility descriptions also include each destination's name. The main app bar uses a group line icon, SWARM/by CJP, and Team sign in/My team action. Conversation view keeps a compact SWARM title and contextual Back; the Team action and phone bottom bar yield to the transcript. More contains Team, Saved, and Connection options.

One contextual callback serves system Back and the visible app-bar Back: close the form first, then the detail, then the conversation, then return to Chats. Closing an edit form preserves its underlying request detail. At the root Chats screen, Back is left to Android. This is the implemented callback contract; this finish pass supplies source/capture evidence rather than an independent gesture test.

### Chats and conversation states

Chats groups direct messages and channels and sorts conversations by latest saved message. Each row shows the latest preview, time, per-message unread count (capped visually at 99), mute state, and the current outgoing delivery label. Pending membership reads “Waiting for the channel owner”; left/removed conversations keep saved-history wording. Empty and unmatched searches provide written next steps; search covers saved information on this phone.

Owned bubbles align to the trailing edge with `primaryContainer`; inbound bubbles align to the leading edge with `surfaceVariant`. Both retain readable text and timestamps; owned messages add explicit delivery meaning. Channel messages name their author. The delivery labels are “Saved · Waiting for connection,” “Sent,” “Sent nearby,” “Delivered,” “Read,” and “Needs attention.” They are separate from public relief publication states.

The retained reverse transcript opens at the latest message. New inbound messages preserve deliberate history position and remain unread until visible; “Latest messages” returns to the newest end. New own sends remain visible. The visible-item effect passes message keys to suspending read work, which cancels when its viewport changes or the conversation is disposed. The repository limits read confirmation to the named inbound unread records, checks cancellation per record, and saves local read state used by chat badges. The [UI/storage record](benchmarks/android-swarm-ui-storage.json) documents the rendered-screen assertions for these behaviors.

The composer saves a local draft by conversation. Its written/icon accessibility labels identify attachment, member mention, voice recording, and send actions. Sending is unavailable for left/removed or pending conversations. Active recording displays “Recording · Microphone on” with Discard and Send voice note. Attachments distinguish waiting from saved private copies and expose download/resume or export actions; complete photos and voice notes have native preview/playback. Need/update references point back to authoritative relief information. Creating a need from a message still requires the relief team's authority.

### Channels and conversation decisions

Channel creation is a scrollable Material dialog with a written name field, Open nearby/Private · Invitation required radio choices, an announcement-with-thread-replies switch, and an approval-before-admission checkbox. It states the 16-member limit and pending independent private-group security review. Announcement timelines keep top-level posting separate from permitted thread replies; role restrictions and moderator thread locks use written sending-unavailable meaning while saved history remains readable. Moderation actions include thanks, reports, hide/restore and lock/unlock where the role permits them.

Joining uses a deliberate two-step invitation decision: paste or scan, then Review invitation before Request to join/Join channel. The review names the channel, inviter and expiry and states when approval is required with no history or keys before admission. Conversation settings provide mute, DM block/report, clear-copy decisions, membership, join requests and owner actions; recipient-only invitations have an explicit expiry and QR when the link fits. Leave/remove/delete/ban decisions explain retained copies or reconnect-dependent changes. Huddles and group video remain unavailable. Channel membership and a chosen chat name never confer verified relief identity.

### Nearby Help and participant reports

Nearby Help is explicitly unverified and separate from Official Needs. Its dialog asks for category, Myself/My group, quantity, details, approximate public area and priority. Privacy copy asks for public landmarks and removal of names, contact details, precise coordinates and home addresses; urgent has a specific safety/medical explanation. Save and share, owner update/resolve/cancel, responder offer/assignment, and request expiry are visible decisions. A prepared relief-team phone can review an aggregate as an Official Need; ordinary Help does not acquire relief authority through that action.

Updates distinguishes “Participant report · Unverified” from “Verified volunteer update.” Creation copy states that participant reports are unverified, public publication requires review, personal details should be removed, and selected media is processed on the phone before sharing. The dialog provides named Gallery, Camera photo and Camera video actions, a removable selected-media state and an optional content warning. Its Publish Update action retains that review disclosure; it does not promise immediate public publication.

Report copy separates “Saved on your phone,” “Shared with nearby Swarm,” “Received online · Awaiting review,” “Published,” rejection, and withdrawal/removal. Created, received-online and published times remain distinct, with a conditions-may-have-changed notice for older reports. Media distinguishes local saved copies, nearby transfer waiting and online arrival; saved playback requires a user action. Content-warning media is concealed until View media. Report/block/withdraw and moderator review decisions are written actions, and reporting explicitly does not erase other phones' copies.

### Appearance, profile and relay consent

More provides System/Light/Dark radio choices, a chosen nearby name and visible chat identity, and separate relief-team access. The nearby profile explicitly says its name is not a verified volunteer identity. Nearby visibility retains the one-minute foreground search meaning and explains that turning it off ends discovery and the active connection.

“Help Swarm send public updates online” presents Off/Wi-Fi only/Wi-Fi + mobile data radio choices, with Off as the default, and a separate Relay public media switch. Storage/data controls show a daily relay limit, minimum battery threshold, saved-media amount and reserved allowance; written copy explains retries and retention of private or pending files. These explicit consent controls do not turn nearby connection into verification or publication.

### Request truth and contribution eligibility

Request detail maps state to written open, partially received, fully committed, fulfilled, cancelled, closed, or verification-unavailable meaning. New contribution controls appear only when the organization is verified, the status is not DRAFT/COMPLETED/CANCELLED/EXPIRED, the deadline is valid and future, and remaining quantity is positive. Unavailable states replace the controls with current-status checks and recovery guidance. Completed detail retains received quantities; an unresolved earlier reservation points to retrying its original action from Saved.

### Nearby decisions and capabilities

Nearby discovery asks Android for access when initiated and offers a one-minute search. Only a confirmed, unblocked compatible participant counts as reachable: the current UI can show one person, with no invented wider swarm. Named open channels appear after a compatible connection; invite-only channels remain hidden. Local pairing exposes an invitation/reply field, matching-code confirmation, and explicit text that a connection does not verify identity. Native dialogs handle pairing, file acceptance, and incoming calls. Microphone/camera access begins through the permission action associated with accepting or starting the call. Current message, signed-update, file, and media capability copy is separate from saved work; the earlier disconnected message action reads “Save message on this phone.”

The global strip says “Connected · Saved work can sync,” “Connected nearby · Saved work remains on this phone,” or “Waiting for connection · Saved information still works.” These describe connection/persistence rather than cryptographic safety. Conversation voice/video buttons appear only for a direct conversation with that matching unblocked peer and an actual media-capable local Wi-Fi session. Calls are 1:1 and foreground-only; group calls stay off. The [testing report](android-testing.md) binds earlier hardware call evidence to its own artifact, not this UI capture build.

Capability labels describe the implemented branches, not a new interoperability guarantee. The connected Nearby branch currently labels Files as available; the visual verdict does not certify the browser file path or every transport state.

### Saved work and recovery

Drafts, signed updates, pending operations, contributions, files, and earlier messages are distinct ruled groups under More → Saved. Signed updates distinguish “Saved on this phone,” “Shared nearby,” “Reached Swarm · Awaiting publication,” “Published · Verified Swarm confirmation,” and “Needs attention.” Carried updates retain the original author and signature. Message delivery confirmation and file completion/hash checking have their own copy; neither implies public publication.

Same-account sign-in recovery explicitly preserves saved work and signing identity. Removal, prepared-phone revocation, delivery confirmation, and sign-out cleanup use decision dialogs. Pending operations offer “Retry original action,” retaining their original details and identity rather than presenting an unconfirmed response as rejection.

### Assets

The startup ships the supplied-logo transparent raster [swarm_lockup.png](../apps/android/app/src/main/res/drawable-nodpi/swarm_lockup.png), rendered by [SwarmStartup.kt](../apps/android/app/src/main/java/org/saathi/android/SwarmStartup.kt). The supplied board is local `C:/Users/aakas/Downloads/SWARM by CJP Brand Identity Board.png`; [brand assets](swarm-brand-assets.md) records the generated background-extraction derivative, prompt provenance and limits. The emblem retains its cream network-ant, tricolor ribbons and stacked wordmark. This supplied identity does not create a new dimensional interface world or authorize modification of factual field photos.

The source reveal runs once for 650ms with `FastOutSlowInEasing`, alpha, scale from 0.94 to 1, and 8dp settling travel; the product subtitle fades into the same finite sequence. Tap or Back skips it. Disabled animator duration or enabled touch exploration takes the immediate path; activity recreation does not replay it. These are source-coded behaviors: the current visual verdict leaves hardware startup motion and reduced-motion behavior unverified.

The launcher identity remains [ic_saathi.xml](../apps/android/app/src/main/res/drawable/ic_saathi.xml), a connected-node native vector with forest fill and warm light strokes. Its stable resource name does not change with the display name. Material icons are vector controls, and the Nearby signature is drawn in Compose. Review PNGs are ignored local evidence, rather than shipping assets or public Git artifacts. User-selected or received files and invitation QR codes are product content.

## Do's and Don'ts

### Do:

- **Do** retain the paper/forest world through Material roles, System/Light/Dark preferences and matching system-bar icon contrast.
- **Do** use `sp` for text and `dp` for layout; keep native roles and metrics separate from web CSS.
- **Do** keep title, quantity, organization, and complete action near the top of need cards.
- **Do** retain rail navigation and two-column needs at the implemented 600dp threshold.
- **Do** keep keyboard, landscape, and large-text content scrollable and evaluate future changes against those captures.
- **Do** distinguish saving, sharing, acceptance, publication, and attention in visible language.
- **Do** preserve contextual Back and same-account recovery without losing saved work.
- **Do** keep all five phone destination labels visible and reserve the conversation viewport for its header, transcript, and composer.
- **Do** give conversation titles their own avatar/title row and keep named search/settings in the separate status/action row.
- **Do** preserve 8dp horizontal separation between Help category, audience and priority choices, including wrapped large-text states.
- **Do** keep Nearby Help and participant reports visibly unverified, distinct from Official Needs and verified volunteer updates.
- **Do** require invitation review and explicit relay consent, with the shipping Off default and a separate media choice.
- **Do** keep incoming offscreen history unread, preserve deliberate scrolling, and keep own sends visible.
- **Do** use the measured connection/persistence copy and the finite Nearby formation with its Remove animations path.

### Don't:

- **Don't** replace native Material controls with copied web or iOS interaction patterns.
- **Don't** expose new contribution controls on closed, expired, unverified, or fully committed needs.
- **Don't** describe a completed card as urgent or still needing its received supplies.
- **Don't** imply that nearby pairing verifies a team or that a saved/shared update is published.
- **Don't** invent reachable people, verified chat identities, group calls, or reviewed cryptographic safety.
- **Don't** enable release private chat before its independent security review gate is satisfied.
- **Don't** promote a bounded ship verdict into whole-surface, transport, cryptographic, production or hardware-motion certification, or call the retained historical phone captures final-APK coverage.
- **Don't** treat review screenshots as shipping raster assets or add unsupported native token values.
