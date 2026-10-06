# CJP Swarm

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

Implemented surfaces: web and native Android. The web retains its responsive noticeboard; Android uses native Material 3. No iOS surface is implemented.

## Stack

Web and service stack: TypeScript, pnpm, Turborepo, Next.js, NestJS, PostgreSQL/Prisma, Redis/BullMQ. Implemented Android client: Kotlin, Jetpack Compose and Material 3 in `apps/android`, supporting API 26+.

## Users

Public donors find verified needs without signing up. Verified volunteers request supplies and report deliveries. Coordinators approve volunteers and moderate their organization; admins manage organizations. Nearby participants can communicate in the development/QA preview without gaining humanitarian authority.

## Product Purpose

Coordinate lawful humanitarian relief during emergencies and connectivity outages. A canonical request page is always authoritative about current quantities and status.

## Operating Context

Mobile browsing, shared screenshots and links, time-sensitive deliveries, volunteers at designated relief points. Local development data is explicitly demonstration data.

## Capabilities and Constraints

Partial reservations, anonymous private donor tracking, verified field updates, append-only audit history, metadata-safe media. Public locations are approximate unless explicitly approved. Build and test the web milestone before mobile and nearby relay.

## Brand Commitments

Official app/store name: CJP Swarm. Display: SWARM, with by CJP as the endorsement and “Connect nearby. Coordinate together.” as the subtitle. Full Cockroach Janta Party credit belongs in About/footer. Branding is configurable; existing Saathi protocol, repository and storage identifiers remain compatible. Calm, credible, humane, accessible, politically neutral. Light and dark themes. No flashy startup presentation or gamification.

## Evidence on Hand

The attached implementation brief; no real organizations, emergencies, or media supplied. Seed records must be labeled as demo content.

## Product Principles

1. Remaining quantities and verification lead the donor experience.
2. Database state is primary; notifications can retry independently.
3. Private information stays private.
4. Server authorization and concurrency rules protect every write.

## Accessibility & Inclusion

Responsive mobile-first design, keyboard operation, visible focus, meaningful labels, reduced-motion support, WCAG-conscious contrast.
