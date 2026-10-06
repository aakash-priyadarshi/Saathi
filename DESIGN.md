---
name: CJP Swarm
description: Calm, credible humanitarian coordination through a community noticeboard.
colors:
  paper: '#f8f7f2'
  surface: '#fffefa'
  ink: '#243d35'
  muted: '#626e64'
  line: '#dedfd5'
  accent: '#216352'
  accent-hover: '#174c3e'
  soft: '#eaf0e7'
  amber: '#805619'
  amber-bg: '#fcf0d9'
  red: '#923d31'
  red-bg: '#fae9e4'
  dark-paper: '#15221e'
  dark-surface: '#1d2e27'
  dark-ink: '#e6eee6'
  dark-muted: '#b3bfb5'
  dark-line: '#3b4d41'
  dark-accent: '#a0d7bd'
  dark-accent-hover: '#c1e7d4'
  dark-soft: '#273b31'
  dark-amber: '#f0ca81'
  dark-amber-bg: '#3f321f'
  dark-red: '#ffc0ad'
  dark-red-bg: '#482c27'
typography:
  display:
    fontFamily: "'Lora Variable', serif"
    fontSize: 'clamp(32px, 3.5vw, 46px)'
    fontWeight: 500
    lineHeight: 1.2
    letterSpacing: '-0.025em'
  headline:
    fontFamily: "'Manrope Variable', sans-serif"
    fontSize: '20px'
    fontWeight: 700
    lineHeight: 1.4
  section-headline:
    fontFamily: "'Manrope Variable', sans-serif"
    fontSize: '24px'
    fontWeight: 700
    lineHeight: 1.4
    letterSpacing: '-0.03em'
  title:
    fontFamily: "'Manrope Variable', sans-serif"
    fontSize: '16px'
    fontWeight: 700
    lineHeight: 1.4
  body:
    fontFamily: "'Manrope Variable', sans-serif"
    fontSize: '14px'
    fontWeight: 400
    lineHeight: 1.65
  label:
    fontFamily: "'Manrope Variable', sans-serif"
    fontSize: '12px'
    fontWeight: 650
    lineHeight: 1.65
  category:
    fontFamily: "'Manrope Variable', sans-serif"
    fontSize: '10px'
    fontWeight: 750
    lineHeight: 1.65
    letterSpacing: '0.11em'
  quantity:
    fontFamily: "'Manrope Variable', sans-serif"
    fontSize: '29px'
    fontWeight: 750
    lineHeight: 1.2
    letterSpacing: '-0.04em'
rounded:
  badge: '5px'
  field: '6px'
  control: '7px'
  notice: '8px'
  media: '10px'
  card: '12px'
  pill: '24px'
  circle: '50%'
spacing:
  compact: '8px'
  control: '12px'
  action: '16px'
  section: '20px'
  card: '22px'
  panel: '28px'
  page: '40px'
components:
  button-primary:
    backgroundColor: '{colors.accent}'
    textColor: '{colors.paper}'
    rounded: '{rounded.control}'
    padding: '11px 16px'
  button-primary-hover:
    backgroundColor: '{colors.accent-hover}'
    textColor: '{colors.paper}'
    rounded: '{rounded.control}'
    padding: '11px 16px'
  button-secondary:
    backgroundColor: 'transparent'
    textColor: '{colors.accent}'
    rounded: '{rounded.control}'
    padding: '11px 16px'
  button-secondary-hover:
    backgroundColor: '{colors.soft}'
    textColor: '{colors.accent}'
    rounded: '{rounded.control}'
    padding: '11px 16px'
  button-danger:
    backgroundColor: '{colors.red-bg}'
    textColor: '{colors.red}'
    rounded: '{rounded.control}'
    padding: '11px 16px'
  icon-button:
    backgroundColor: 'transparent'
    textColor: '{colors.ink}'
    rounded: '{rounded.circle}'
    height: '40px'
    width: '40px'
  input:
    backgroundColor: '{colors.surface}'
    textColor: '{colors.ink}'
    rounded: '{rounded.field}'
    padding: '11px 12px'
  category-filter:
    backgroundColor: 'transparent'
    textColor: '{colors.muted}'
    rounded: '{rounded.pill}'
    padding: '7px 16px'
  category-filter-selected:
    backgroundColor: '{colors.ink}'
    textColor: '{colors.paper}'
    rounded: '{rounded.pill}'
    padding: '7px 16px'
  need-card:
    backgroundColor: '{colors.surface}'
    textColor: '{colors.ink}'
    rounded: '{rounded.card}'
    padding: '22px'
  status-notice:
    backgroundColor: '{colors.soft}'
    textColor: '{colors.ink}'
    rounded: '{rounded.notice}'
    padding: '20px'
  status-notice-closed:
    backgroundColor: '{colors.amber-bg}'
    textColor: '{colors.ink}'
    rounded: '{rounded.notice}'
    padding: '20px'
  error-notice:
    backgroundColor: '{colors.red-bg}'
    textColor: '{colors.red}'
    rounded: '{rounded.notice}'
    padding: '16px'
  mobile-nav-item-selected:
    backgroundColor: '{colors.soft}'
    textColor: '{colors.accent}'
    rounded: '{rounded.control}'
    padding: '6px 8px'
---

# Design System: CJP Swarm

## Overview

**Creative North Star: "The Community Noticeboard"**

CJP Swarm is a calm, humane place to find a verified need, check its current quantities, and coordinate help. Warm paper surfaces, forest ink, short Lora headings, and precise Manrope controls make the interface approachable while keeping operational information legible. The noticeboard is the recorded direction: approachable need cards, chronological field reports, and durable request notices.

The official app/store name is CJP Swarm. The default web header displays SWARM with by CJP and a connected-node line icon. The footer carries “Connect nearby. Coordinate together.” and “Developed by Cockroach Janta Party.” Branding remains configurable; existing Saathi repository, package, protocol, and storage identifiers stay compatible. This naming extension preserves the noticeboard's tokens, components, and layout.

The built world is text-led and task-first. Thin rules separate evidence, actions, and field updates; icons clarify labels rather than replace them. There is no decorative hero imagery. User-supplied photos and videos belong inside field reports. Light and dark themes preserve the same semantic hierarchy. The product commitments remain calm, credible, accessible, politically neutral, and free of flashy startup presentation or gamification.

**Key Characteristics:**

- Warm paper and forest ink with restrained teal actions.
- Humane serif page headings with compact, legible sans-serif data.
- Flat, bordered surfaces and explicit quantities, deadlines, and verification.
- Mobile task clarity with persistent public navigation.
- Current status and permanent closed notices over stale shared screenshots.

This document refreshes the directional contract from the implemented source in `apps/web/src` and `packages/ui/src`. Token frontmatter is normative; the extension sidecar contains responsive values, motion, and self-contained component specimens. Product facts remain in `PRODUCT.md`.

Native Android extends the same community noticeboard into direct messages and named channels through Kotlin, Jetpack Compose, and Material 3. Read the [Android design extension](docs/android-design.md) with its [surface contract](.impeccable/surfaces/android.md) for implemented native roles, `dp`/`sp` measurements, five-destination navigation, visible-message reads, states, motion, assets, and bounded finish evidence. The root frontmatter and HTML/CSS component specimens describe the web; the native theme and Compose sources are authoritative for Android. Native metadata is recorded separately at `extensions.platforms.android` in the sidecar.

## Colors

The palette feels like a public community noticeboard: warm neutrals carry most of the page, with teal for helpful actions and verification, amber for caution, and red for urgency and failure.

### Primary

- **Community Teal** (`accent`): primary actions, text links, verified labels, live dots, committed-quantity bars, focus outlines, and the brand.
- **Deep Community Teal** (`accent-hover`): primary button hover.
- **Night Mint** (`dark-accent`) and **Light Night Mint** (`dark-accent-hover`): the dark theme's action and hover counterparts. Button text follows the dark paper token.

### Secondary

- **Notice Amber** (`amber`) on **Notice Paper** (`amber-bg`): high priority, demonstration disclosure, and caution icons.
- **Night Amber** (`dark-amber`) on **Night Notice** (`dark-amber-bg`): the same roles in dark theme.
- Closed and unavailable-verification notices use the amber background with normal ink text; their icon changes to amber.

### Tertiary

- **Urgent Clay** (`red`) on **Clay Paper** (`red-bg`): urgent badges, error notices, and destructive actions.
- **Night Clay** (`dark-red`) on **Night Clay Paper** (`dark-red-bg`): corresponding dark theme states.

### Neutral

- **Warm Paper** (`paper`): page canvas and contrast text on primary actions and selected filters.
- **Notice Surface** (`surface`): header, need cards, input fields, pledge panel, tables, and mobile navigation.
- **Forest Ink** (`ink`): headings and primary information; selected category filters invert ink and paper.
- **Quiet Ink** (`muted`): descriptions, locations, dates, hints, and secondary navigation.
- **Paper Rule** (`line`): boundaries, table rules, field outlines, and separators.
- **Sage Wash** (`soft`): progress tracks, verification badges, selected quantity options, table headings, secondary hover, and active mobile navigation.
- Each neutral has a `dark-` counterpart; selecting dark theme replaces all twelve CSS color variables together. Layout, typography, and status meaning remain consistent.

**The Semantic Color Rule.** Use color with visible status text or a recognizable control. Urgency, verification, fulfillment, and failure must remain understandable without color.

## Typography

**Display Font:** Lora Variable, with a serif fallback.  
**Body and Control Font:** Manrope Variable, with a sans-serif fallback.

Both variable fonts are imported locally through Fontsource in the root layout. Lora gives brief page headings a humane voice; Manrope handles quantities, controls, cards, and dense coordination information. Italic emphasis in the needs introduction uses the accent color. There is no separate mono family.

### Hierarchy

- **Display:** the frontmatter display role applies to page-level headings. Text is balanced. The needs introduction becomes a compact heading on mobile (28px, line-height 1.16); request details use 34px and verification/about pages use 35px.
- **Headline:** general section headings and need-card titles use the frontmatter headline role. Card titles tighten tracking (-0.025em). Need-list section headings use the separate section-headline role, reducing to 22px on mobile.
- **Title:** short supporting headings use the title role. Field-column headings use 18px on desktop and 21px on mobile.
- **Body:** the frontmatter body role applies to the page. Paragraphs cap at 70ch; introduction copy caps at 52ch, then 45ch on mobile. Field reports use a generous line-height (1.8).
- **Label:** controls and form labels use the label role; primary buttons use a heavier weight (700). Supporting information uses compact text from 9px to 12px, with explicit labels.
- **Category:** category labels are uppercase with the frontmatter tracking; status badges use 10px, weight 700, line-height 1.4.
- **Quantity:** the quantity role uses tabular numerals. Card numbers increase to 31px on mobile; the pledge panel uses 42px; fulfillment summaries use 22px.

**The Heading First Rule.** Lead a card or detail header with the human-readable title. Category, priority, request ID, and organization metadata follow it.

## Layout

The outer page and header are centered, with a normal maximum width (1280px) and page gutters (40px). At wide screens (1500px and above), the outer maximum expands to 1360px. Narrow reading, verification, and contribution pages retain a 790px maximum; login retains 490px. Editor forms cap at 760px.

The needs surface pairs a two-column request grid with a field column (285px), separated by a thin vertical rule. Card gaps are 20px, and the main column gap is 32px. At 1100px and below, request cards become one column, the field column narrows to 250px, and gutters become 25px. Request detail pairs content with a sticky pledge panel (360px, 65px gap); this becomes a 330px panel with a 35px gap at the same breakpoint.

At 760px and below, the page uses one column and 20px side gutters. The needs introduction is compact; its desktop trust explanation moves after the request and field content. Search becomes full width, category filters scroll horizontally, and supporting section subtitles are hidden. The first usable request keeps its quantity, verification, and action above the fixed navigation in the reviewed mobile viewport. The field column follows the needs and changes its divider to a top rule. Cards use 18px padding and 16px gaps. Detail panels become static and stack below request information; two-column forms become one column.

Public navigation moves from the header to a fixed bottom bar with safe-area padding. The footer leaves room below content (100px bottom padding) for that bar. Workspace links wrap; incoming deliveries and management rows stack or wrap; wide tables scroll within their own container.

Connectivity and saved-work surfaces retain flat ruled groups within a 1240px maximum. Task/conversation columns use equal tracks with a 48px gap, stacking below 760px with a 28px gap. Connection-detail rows use a label/value grid on desktop and full-width, left-aligned values on mobile. Main connection actions precede the detailed status rows. Reopening a draft focuses its editor heading and scrolls it into view; reduced motion uses immediate scrolling.

Spacing is deliberately varied by information role rather than a fabricated uniform scale. Compact control gaps, 18–20px form grouping, 22px desktop card padding, and 28px pledge padding distinguish scanning, input, and explanation.

**The Task First Rule.** On the needs surface, keep the route to a usable request short: compact explanation, heading, search, filters, then need cards. Supporting trust copy must not push the first request action below mobile navigation.

## Elevation & Depth

This system has no box shadows, glass effects, or backdrop blur. Depth comes from paper and surface tones, thin borders, and whitespace. Need cards and pledge panels remain flat at rest and on hover. The sticky desktop pledge panel and fixed mobile navigation use position to support tasks, not simulated material elevation.

**The Flat Noticeboard Rule.** Use tonal surfaces and thin rules to group information. Do not add decorative shadows or hover lifts to the existing noticeboard.

## Shapes

Corners are restrained: cards and pledge panels use the card radius, notices and table containers use the notice radius, and controls use the control or field radius. Badges are compact rectangles; category filters are pills. The theme switch is circular. Field media uses the media radius. Borders are consistently thin (1px); desktop active navigation uses a stronger bottom rule (2px). Progress tracks are slim (6px) with rounded ends (8px).

## Components

### Buttons

Clear, compact actions with written labels and optional line icons.

- Primary buttons use accent against paper, control corners, frontmatter padding, weight 700, and a minimum height (42px).
- Primary hover changes to accent-hover over a brief background transition (0.15s). Keyboard focus uses the global accent outline (3px, 4px offset).
- Secondary buttons are transparent with accent text and a line border; hover adds sage wash and an accent border.
- Destructive buttons use urgent-clay text and outline on clay paper. They inherit the generic button hover treatment in the current source.
- Full-width actions fill their panel. Text links remain understated accent text with a written label and underline on hover.
- Busy or disabled buttons use reduced opacity (0.6) and a wait cursor; busy labels describe the active operation, such as “Checking…” or “Reserving…”.
- The theme button is a circular outline control (40px, 36px on mobile), with sage wash on hover and an accessible label naming the theme it will activate.

### Chips

Category filters use transparent pills with muted text and a line border. Selected filters invert ink and paper; hover changes the border to accent. They are pressable filters with `aria-pressed`, a minimum height (36px), and horizontal overflow on narrow screens. Mobile filter padding becomes 8px 14px.

Quantity presets use squarer field corners, a minimum height (42px), and surface backgrounds. Selection adds sage wash, accent text, and an accent border. Priority badges are compact and explicitly named: “Urgent need,” “High priority,” or “Fulfilled.”

### Cards / Containers

Need cards are flat surface rectangles with a thin line border and card corners. The order is title, category and priority, description, prominent remaining quantity, committed progress, location and deadline, then verified organization and action. The action stays aligned at the bottom through a flex column; a rule separates it from the request information.

Completed cards show received quantities and a “Fulfilled” badge. Their progress measures received supplies, and the secondary action reads “View impact.” An active need with no remaining quantity changes its action to “View request.” The bar always has a text count and accessible progress values.

The pledge panel uses the same card language with more internal space and a larger quantity. Tables use a flat surface, sage heading row, thin row rules, and a scrolling wrapper.

### Inputs / Fields

Fields use surface backgrounds, ink text, a line border, field corners, and a minimum height (44px). Labels sit above the field. Optional notes and form hints use quiet ink; placeholders retain muted color at full opacity. Textareas are vertically resizable with a minimum height (105px). Checkboxes use the accent color.

Search is an outlined control with a search icon and a programmatic label. Its corners follow buttons, and it becomes full width on mobile. Focus uses the global visible accent outline; the implementation uses alert notices for server or mutation failures rather than a separate red field-border pattern.

### Navigation

The public destinations are Live, Needs, Completed, and Verify. Desktop links use muted Manrope text; the active page uses accent text and a bottom rule. A small live dot accompanies Live. The brand and theme switch remain in the header; the volunteer portal is a compact labeled link.

Mobile navigation presents four equal destinations with line icons above labels. The active page uses sage wash, accent text, and control corners. It remains fixed at the bottom with a top rule and safe-area inset. Public and workspace navigation have explicit accessible names. A skip link becomes visible on focus.

### Field Reports

Chronological reports read as a ruled list. Each starts with a live dot, time, and relief point; caption text follows, then verified volunteer and author/organization metadata. A linked request uses a labeled accent text link. Times are shown in IST. Compact home reports omit media; the full live feed displays supplied photos or controlled videos with restrained corners.

### Request Status and Verification

A status notice pairs a shield icon with a bold answer and explanatory text. The open verified state uses sage wash. Closed requests and unavailable verification use the caution background.

The displayed states are distinct: accepting help; all supplies committed and awaiting delivery; fulfilled; cancelled; expired; and “Organization verification unavailable.” Closed notices instruct donors not to send more supplies, and the canonical page stays available. Unavailable verification explicitly tells users to wait for verification to be restored. Its verification result does not display a verified badge. The pledge form is replaced by an appropriate notice and route to another need when verification is unavailable, the request is closed, or all quantities are committed.

The verification page accepts a request ID or Swarm link, shows “Checking…” while busy, and presents the current quantity, status, and last update. It labels an unavailable organization truthfully and provides the canonical request link.

### Connectivity and Relief Delivery

The persistent connection strip uses sage wash for reachability and amber paper when Swarm is unavailable, with a line icon, explicit text and a connection-details link. It is compact (12px text, 10px vertical padding), stacks on mobile and retains the existing theme tokens.

Delivery progress is a compact vertical list (12px, 6px row gaps) with check/minus line icons and separate written labels for local saving, another phone, Swarm and public publication. A server rejection uses urgent-clay text with a recovery message. Nearby pairing and file controls use the existing button/field shapes; unavailable file actions expose a disabled state and the prerequisite. Saved-information time is distinct from the last successful connection.

### Loading, Empty, Error, and Contribution States

Loading combines a spinner with “Loading the latest information…” in a status region. A red alert notice presents an explicit failure, with “Try again” where retry is supplied. Empty request results use a dashed outline, a readable heading, and a category/location or live-update next step. Successful management feedback uses sage wash and accent text in a status region.

Private contribution pages label the tracking link and explain that its holder can manage the contribution. A reservation notice gives its expiry; delivery details, in-transit information, received confirmation, and expired/cancelled notices use the same restrained structure. Copied links and reported concerns receive written confirmation. Demonstration mode adds an amber disclosure above the header and labels local demo login content.

Motion is limited to the loading spinner, short button color changes, and quantity-bar transforms (0.6s, cubic-bezier(0.2, 0.8, 0.2, 1)). A reduced-motion preference disables animations and transitions.

## Do's and Don'ts

### Do:

- **Do** preserve the community noticeboard: warm paper, humane short headings, flat surfaces, and thin rules.
- **Do** put human-readable headings before category, ID, and organization metadata.
- **Do** keep remaining quantity, verification, and the next action clear on a narrow screen.
- **Do** pair status color with explicit text and maintain the same semantics in both themes.
- **Do** keep permanent request notices readable when a shared link outlives a need.
- **Do** use visible keyboard focus, written control labels, and the existing reduced-motion behavior.
- **Do** show genuine field media in its report context and label demonstration content.

### Don't:

- **Don't** add flashy startup presentation, gamification, decorative shadows, or hover lifts.
- **Don't** replace operational quantity bars with decorative progress or invented impact statistics.
- **Don't** imply verification when the organization’s verification is unavailable.
- **Don't** leave a donation form in a closed, unavailable-verification, or fully committed request state.
- **Don't** let supporting trust copy hide the first usable need below fixed mobile navigation.
- **Don't** invent real relief operations, partners, testimonials, or imagery from the demonstration records.
