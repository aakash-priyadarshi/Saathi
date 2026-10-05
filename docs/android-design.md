# Design System: Saathi Android

## Overview

**Creative North Star: "The Community Noticeboard"**

Android is a native extension of the established Saathi world: warm paper, forest ink, short Lora headings, and Manrope operational text. Material 3 supplies familiar controls, dialogs, and adaptive navigation. The [root design system](../DESIGN.md) remains the web contract; this document records the actual Kotlin/Compose implementation without converting web `px` values into native measurements.

**Key Characteristics:**

- Paper and forest roles in explicit light and dark schemes.
- Native Material navigation bar on phones and rail at wider widths.
- Title, quantity, organization, and next action near the top of a need card.
- Explicit freshness, nonproduction disclosure, and current connection capability.
- Separate local saving, nearby sharing, central acceptance, publication, and attention states.

Implementation authority is [SaathiTheme.kt](../apps/android/app/src/main/java/org/saathi/android/SaathiTheme.kt), [MainActivity.kt](../apps/android/app/src/main/java/org/saathi/android/MainActivity.kt), [TeamScreens.kt](../apps/android/app/src/main/java/org/saathi/android/TeamScreens.kt), [NearbyScreens.kt](../apps/android/app/src/main/java/org/saathi/android/NearbyScreens.kt), and [SaathiViewModel.kt](../apps/android/app/src/main/java/org/saathi/android/SaathiViewModel.kt). The [Android surface contract](../.impeccable/surfaces/android.md) records its Operate mode and direction. Measurements below are source observations, not a new token scale.

### Finish evidence

The local-only QA verdict at `.impeccable/review/android/finish-verdict-final.md` records `disposition: ship` for exactly four original material findings: public request state truth, completed meaning, phone task priority, and contextual native Back. All four are resolved. It is a fix-list verdict, not whole-surface certification, an independent gesture/device test, or a production-readiness assessment.

The verdict and review captures are ignored local QA evidence, intentionally absent from public Git artifacts. Public evidence and its limits are recorded in the committed [Android testing report](android-testing.md) and [device benchmark record](benchmarks/android-product-devices.json). The verdict's ten valid captures reside under the local `.impeccable/review/android/` directory:

| Evidence                         | Recorded surface                                                                                                                            |
| -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `phone-needs-dark.png`           | Physical Samsung S24, normal owner settings; first need title, quantity, organization, and complete action visible above bottom navigation. |
| `tablet-needs-dark.png`          | Physical SM-X510 in landscape; native rail and two-column needs. The lower taskbar is Android system chrome.                                |
| `emulator-needs-light.png`       | Emulator light needs, including the actual sign-in notice.                                                                                  |
| `emulator-nearby-dark.png`       | Emulator dark Nearby.                                                                                                                       |
| `emulator-nearby-large-text.png` | Emulator light Nearby at 2× text; a scrollable viewport continuation.                                                                       |
| `emulator-team-keyboard.png`     | Emulator team form with keyboard; lower form content remains scrollable.                                                                    |
| `emulator-team-landscape.png`    | Emulator team form in landscape; scrollable viewport continuation.                                                                          |
| `emulator-team-recovery.png`     | Emulator same-account recovery, preserving saved work and signing identity.                                                                 |
| `emulator-completed-light.png`   | Emulator completed card with received quantity, received progress, “View impact,” and “Help has arrived.”                                   |
| `emulator-closed-detail.png`     | Emulator fulfilled request detail, with received quantities and closed-state wording.                                                       |

Unavailable-verification eligibility and the shared Back callback are source evidence; the verdict did not separately picture the former or exercise a Back gesture. No additional defect hunt accompanies this documentation. The HTML/CSS detector is inapplicable to this native Kotlin/Compose surface and was skipped. Transport, browser file interoperability, and release limitations belong to [Android testing](android-testing.md) and [Android release](android-release.md); the visual verdict does not certify them.

## Colors

The native theme reuses the root palette through Material roles. These are aliases to existing root frontmatter primitives, not new colors. `SaathiTheme` selects an explicit scheme with `isSystemInDarkTheme()`; it does not enable wallpaper-derived Dynamic Color.

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

Unlisted roles retain Material 3 defaults, including the `titleSmall` used by shared notice headings. Do not describe every native text role as a custom Manrope token.

**The Heading First Rule.** Keep the human-readable need title ahead of quantity, category, and organization metadata.

## Layout

The edge-to-edge activity uses a Material `Scaffold`, its content padding, status-bar padding on the rail, and `imePadding()` for keyboard clearance. Content is centered with a maximum width (1000dp). Compact width uses four bottom destinations; at width (600dp) and above, a rail (104dp) replaces the bottom bar and needs use two columns. Other screens remain scrollable single-column lists.

Needs use a lazy grid with outer padding (20dp) and horizontal/vertical gaps (16dp). The full-width grid header contains the compact two-line invitation, section heading and refresh action, search, one horizontally scrolling row of state and category filters, then snapshot freshness. A vertical divider (24dp high) separates state filters from categories. The two state filters and all categories share this row to keep the first usable need close to the search.

Need cards place title first, remaining or received quantity second, and organization plus “View need” or “View impact” third. Category/priority, short description, progress, counts, and public location follow. Card padding (20dp) and internal gaps (12dp) preserve readable grouping. The description caps at three lines; operational state and action labels are not placed in that truncated copy.

Field, Nearby, Saved, Team, and detail screens use lazy lists with padding (20dp). Observed list gaps are 16dp for draft forms, 18dp for Nearby/Saved/Team/detail, and 20dp for Field. Shared headings use gaps (8dp); notices use internal padding (16dp), icon/text gaps (12dp), and text gaps (6dp). These values are contextual measurements, not a universal spacing ladder.

**The Task First Rule.** Preserve the reviewed normal-phone path from compact invitation to search, filters, freshness, and the first need's quantity, organization, and complete action. Large text, landscape, and keyboard states may continue below the viewport through scrolling; the captures do not prove all device/font combinations.

## Elevation & Depth

Need cards are outlined, flat surfaces. Thin Material rules and tonal containers carry grouping; no custom shadow or hover lift is implemented. The compact `NavigationBar` explicitly uses tonal elevation (0dp). Other Material controls and dialogs retain their library defaults. Android therefore inherits Material depth behavior rather than the web's blanket CSS no-shadow rule.

Motion consists of the current Material controls, progress indicators, and interaction feedback. No custom transition duration or native motion token is established in the source, and no independent Remove animations verification is claimed by this finish verdict.

## Shapes

Need cards use restrained corners (12dp) and a thin outline (1dp). Shared notices use corners (8dp); urgency badges use corners (4dp). Buttons, chips, dialogs, fields, message surfaces, and other cards use Material 3 shape defaults unless their call site overrides them. There is no global custom `Shapes` scale in `SaathiTheme`.

## Components

### Buttons and fields

Filled, outlined, and text buttons are native Material 3 components with written actions. Team sign-in, draft-saving, and signed-saving actions fill their container. Email, password, numeric quantity, and authenticator fields select their appropriate keyboard types; passwords use masking. Inputs use `OutlinedTextField` with persistent labels. Busy state adds a top progress indicator and disables affected actions. General notices appear in a dismissible tonal strip; saved receipt rejection/conflict/invalidation also uses error-colored text.

### Filters and need cards

Native `FilterChip` selection expresses Active needs/Completed and category filters in the shared scrolling row. A need card is one clickable `OutlinedCard` with a written action; line icons supplement its text. Active cards show remaining quantity and committed progress. Completed cards use received quantity and received progress, “View impact,” and “Help has arrived”; they omit urgency and “still needed.”

### Navigation and Back

Needs, Field, Nearby, and Saved are the four persistent destinations. The app bar retains Saathi's heart outline, name, “Here for each other,” and Team sign in/My team action. Native navigation bar and rail use selected tonal indicators and visible labels.

One contextual callback serves system Back and the visible app-bar Back on Team, detail, and form screens: close the form first, then the detail, then return to Needs. Closing an edit form preserves its underlying request detail. At the root Needs screen, Back is left to Android. This is the implemented callback contract; the finish verdict verified its source assignment without a gesture test.

### Request truth and contribution eligibility

Request detail maps state to written open, partially received, fully committed, fulfilled, cancelled, closed, or verification-unavailable meaning. New contribution controls appear only when the organization is verified, the status is not DRAFT/COMPLETED/CANCELLED/EXPIRED, the deadline is valid and future, and remaining quantity is positive. Unavailable states replace the controls with current-status checks and recovery guidance. Completed detail retains received quantities; an unresolved earlier reservation points to retrying its original action from Saved.

### Nearby decisions and capabilities

Discovery asks Android for access when initiated. Local pairing exposes an invitation/reply field, matching-code confirmation, and explicit text that a connection does not verify identity. Native dialogs handle pairing, file acceptance, and incoming calls. Microphone/camera access begins through the permission action associated with accepting or starting the call. Current message, signed-update, file, and media capability copy is separate from saved work; a disconnected message action reads “Save message on this phone.”

Capability labels describe the implemented branches, not a new interoperability guarantee. The connected Nearby branch currently labels Files as available; the visual verdict does not certify the browser file path or every transport state.

### Saved work and recovery

Drafts, signed updates, pending operations, contributions, files, and messages are distinct ruled groups. Signed updates distinguish “Saved on this phone,” “Shared nearby,” “Reached Saathi · Awaiting publication,” “Published · Verified Saathi confirmation,” and “Needs attention.” Carried updates retain the original author and signature. Message delivery confirmation and file completion/hash checking have their own copy; neither implies public publication.

Same-account sign-in recovery explicitly preserves saved work and signing identity. Removal, prepared-phone revocation, delivery confirmation, and sign-out cleanup use decision dialogs. Pending operations offer “Retry original action,” retaining their original details and identity rather than presenting an unconfirmed response as rejection.

### Assets

The shipping native source contains no raster artwork. The launcher identity is [ic_saathi.xml](../apps/android/app/src/main/res/drawable/ic_saathi.xml), a native vector heart/hand mark with forest fill and warm light strokes. Material icons are vector controls. Review PNGs are ignored local-only evidence captures under `.impeccable/review/android`, not shipping assets or public Git artifacts. User-selected or received files remain product content, not invented brand imagery.

## Do's and Don'ts

### Do:

- **Do** retain the paper/forest world through Material roles and the explicit system-selected light/dark schemes.
- **Do** use `sp` for text and `dp` for layout; keep native roles and metrics separate from web CSS.
- **Do** keep title, quantity, organization, and complete action near the top of need cards.
- **Do** retain rail navigation and two-column needs at the implemented 600dp threshold.
- **Do** keep keyboard, landscape, and large-text content scrollable and evaluate future changes against those captures.
- **Do** distinguish saving, sharing, acceptance, publication, and attention in visible language.
- **Do** preserve contextual Back and same-account recovery without losing saved work.

### Don't:

- **Don't** replace native Material controls with copied web or iOS interaction patterns.
- **Don't** expose new contribution controls on closed, expired, unverified, or fully committed needs.
- **Don't** describe a completed card as urgent or still needing its received supplies.
- **Don't** imply that nearby pairing verifies a team or that a saved/shared update is published.
- **Don't** promote the four-finding ship verdict into whole-surface, transport, or production certification.
- **Don't** treat review screenshots as shipping raster assets or add unsupported native token values.
