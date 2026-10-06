import { PrismaClient } from '@prisma/client';
import { env } from '@saathi/config';

const seededRequests = [
  'SAA-7F3K92',
  'SAA-8M2P41',
  'SAA-4R9W63',
  'SAA-6N8C25',
  'SAA-3B5Q87',
  'SAA-2A6D19',
] as const;
const seededUsers = new Set([
  'admin@saathi.test',
  'coordinator@saathi.test',
  'volunteer@saathi.test',
  'medical@saathi.test',
  'public@saathi.test',
  'android-volunteer@saathi.test',
]);
const seededPoints = new Map([
  ['Point A', 'Community Relief Network'],
  ['Point B', 'Community Relief Network'],
  ['Medical Desk', 'Medical Volunteers Group'],
]);
const demoPointDescription = 'Designated public receiving point (demonstration).';
const archivedPointName = 'Archived demonstration point';
const archivedPointDescription = 'Archived staging fixture.';
const archivedRequestTitle = 'Archived staging test request';
const archivedRequestDescription = 'This staging demonstration fixture was removed from public listings.';
const archivedUpdateCaption = 'This staging demonstration update was withdrawn.';

function hasDemoMarker(value: unknown, publicId: string) {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    'demo' in value &&
    value.demo === true &&
    'publicId' in value &&
    value.publicId === publicId
  );
}

function hasCleanupMarker(value: unknown, publicId: string) {
  return (
    value !== null &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    'reliefFixtureArchived' in value &&
    value.reliefFixtureArchived === true &&
    'publicId' in value &&
    value.publicId === publicId
  );
}

function assertSeededRequest(
  request: {
    publicId: string;
    creator: { email: string };
    organization: { name: string };
    reliefPoint: { name: string; description: string; organization: { name: string } };
    posts: { author: { email: string } | null; media: unknown[] }[];
  },
  audits: { next: unknown }[],
) {
  if (!seededRequests.includes(request.publicId as (typeof seededRequests)[number]))
    throw new Error('Refusing to clean an unrecognized relief request.');
  if (!seededUsers.has(request.creator.email))
    throw new Error(`Refusing to clean ${request.publicId}: creator is not a seeded test account.`);
  if (!['Community Relief Network', 'Medical Volunteers Group'].includes(request.organization.name))
    throw new Error(`Refusing to clean ${request.publicId}: organization is not a seeded demo organization.`);
  const originalPoint =
    request.reliefPoint.description === demoPointDescription &&
    seededPoints.get(request.reliefPoint.name) === request.reliefPoint.organization.name;
  const archivedPoint =
    request.reliefPoint.name === archivedPointName &&
    request.reliefPoint.description === archivedPointDescription &&
    ['Community Relief Network', 'Medical Volunteers Group'].includes(
      request.reliefPoint.organization.name,
    );
  if (!originalPoint && !archivedPoint)
    throw new Error(`Refusing to clean ${request.publicId}: receiving point is not a seeded demo point.`);
  if (!audits.some((audit) => hasDemoMarker(audit.next, request.publicId)))
    throw new Error(`Refusing to clean ${request.publicId}: seeded demo audit marker is missing.`);
  if (request.posts.some((post) => (post.author && !seededUsers.has(post.author.email)) || post.media.length))
    throw new Error(`Refusing to clean ${request.publicId}: linked updates contain non-demo authors or media.`);
}

export async function clearStagingDemoRelief(
  db: PrismaClient,
  options: { appEnvironment: string; execute: boolean },
) {
  if (options.appEnvironment !== 'staging')
    throw new Error('This cleanup is restricted to APP_ENV=staging.');
  if (!options.execute)
    throw new Error('Set SAATHI_CLEAR_DEMO_RELIEF=true to authorize the targeted cleanup.');

  return db.$transaction(async (tx) => {
    const requests = await tx.reliefRequest.findMany({
      where: { publicId: { in: [...seededRequests] } },
      include: {
        creator: { select: { email: true } },
        organization: { select: { name: true } },
        reliefPoint: { include: { organization: { select: { name: true } } } },
        posts: { include: { author: { select: { email: true } }, media: true } },
      },
    });
    const auditsByRequest = new Map<string, { event: string; next: unknown }[]>();
    for (const request of requests) {
      const audits = await tx.auditEvent.findMany({
        where: { entityType: 'ReliefRequest', entityId: request.id },
        select: { event: true, next: true },
      });
      assertSeededRequest(request, audits);
      auditsByRequest.set(request.id, audits);
    }

    const requestIds = requests.map((request) => request.id);
    const posts = requests.flatMap((request) => request.posts);
    const pointIds = [...new Set(requests.map((request) => request.reliefPointId))];
    const pointCandidates = [
      ...new Map(requests.map((request) => [request.reliefPoint.id, request.reliefPoint])).values(),
    ];
    const commitments = requestIds.length
      ? await tx.donationCommitment.findMany({
          where: { requestId: { in: requestIds } },
          select: { id: true, externalOrderId: true, provider: true, request: { select: { publicId: true } } },
        })
      : [];
    for (const commitment of commitments) {
      if (
        commitment.provider !== 'Local shop' ||
        commitment.externalOrderId !== `DEMO-${commitment.request.publicId}`
      )
        throw new Error('Refusing to remove an unrecognized contribution linked to a demo request.');
    }

    const now = new Date();
    let newlyArchived = 0;
    let withdrawnUpdates = 0;
    let removedCommitments = 0;
    let deactivatedPoints = 0;
    for (const request of requests) {
      const audits = auditsByRequest.get(request.id) ?? [];
      const alreadyArchived = audits.some((audit) =>
        audit.event === 'STAGING_DEMO_RELIEF_ARCHIVED' &&
        hasCleanupMarker(audit.next, request.publicId),
      ) &&
        request.status === 'CANCELLED' &&
        request.archivedAt !== null &&
        request.title === archivedRequestTitle &&
        request.description === archivedRequestDescription;
      if (!alreadyArchived) {
        await tx.reliefRequest.update({
          where: { id: request.id },
          data: {
            title: archivedRequestTitle,
            description: archivedRequestDescription,
            category: 'OTHER',
            requestedQuantity: 1,
            committedQuantity: 0,
            receivedQuantity: 0,
            unit: 'item',
            priority: 'NORMAL',
            status: 'CANCELLED',
            version: { increment: 1 },
            deadline: now,
            expiresAt: now,
            archivedAt: now,
          },
        });
        await tx.auditEvent.create({
          data: {
            event: 'STAGING_DEMO_RELIEF_ARCHIVED',
            entityType: 'ReliefRequest',
            entityId: request.id,
            next: { reliefFixtureArchived: true, publicId: request.publicId },
          },
        });
        newlyArchived++;
      }

      for (const post of request.posts) {
        if (post.moderation !== 'HIDDEN' || post.caption !== archivedUpdateCaption) {
          await tx.fieldUpdate.update({
            where: { id: post.id },
            data: {
              caption: archivedUpdateCaption,
              moderation: 'HIDDEN',
              publishedAt: null,
              participantName: null,
              publicArea: null,
            },
          });
          withdrawnUpdates++;
        }
      }
    }

    const commitmentIds = commitments.map(({ id }) => id);
    if (commitmentIds.length) {
      await tx.delivery.deleteMany({ where: { commitmentId: { in: commitmentIds } } });
      await tx.donationCommitment.deleteMany({ where: { id: { in: commitmentIds } } });
      removedCommitments = commitmentIds.length;
    }

    for (const point of pointCandidates) {
      const originalPoint =
        point.description === demoPointDescription &&
        seededPoints.get(point.name) === point.organization.name;
      const alreadyArchived =
        point.name === archivedPointName && point.description === archivedPointDescription;
      if ((!originalPoint && !alreadyArchived) || !pointIds.includes(point.id)) continue;
      const [otherRequestCount, otherPostCount] = await Promise.all([
        tx.reliefRequest.count({
          where: { reliefPointId: point.id, ...(requestIds.length ? { id: { notIn: requestIds } } : {}) },
        }),
        tx.fieldUpdate.count({
          where: { reliefPointId: point.id, ...(posts.length ? { id: { notIn: posts.map((post) => post.id) } } : {}) },
        }),
      ]);
      if (otherRequestCount || otherPostCount) continue;
      if (
        point.active ||
        !alreadyArchived ||
        point.publicLocation !== archivedPointDescription ||
        point.instructions !== archivedPointDescription ||
        point.operatingHours !== archivedPointDescription
      ) {
        await tx.reliefPoint.update({
          where: { id: point.id },
          data: {
            name: archivedPointName,
            description: archivedPointDescription,
            publicLocation: archivedPointDescription,
            instructions: archivedPointDescription,
            operatingHours: archivedPointDescription,
            active: false,
          },
        });
        await tx.auditEvent.create({
          data: {
            event: 'STAGING_DEMO_RELIEF_ARCHIVED',
            entityType: 'ReliefPoint',
            entityId: point.id,
            next: { reliefFixtureArchived: true },
          },
        });
        deactivatedPoints++;
      }
    }

    const retainedAuditEvents = requestIds.length
      ? await tx.auditEvent.count({ where: { entityType: 'ReliefRequest', entityId: { in: requestIds } } })
      : 0;
    return {
      requestsArchived: newlyArchived,
      updatesWithdrawn: withdrawnUpdates,
      commitmentsRemoved: removedCommitments,
      pointsDeactivated: deactivatedPoints,
      requestAuditEventsRetained: retainedAuditEvents,
    };
  });
}

if (process.argv[1]?.replaceAll('\\', '/').endsWith('/clear-staging-demo-relief.ts')) {
  const db = new PrismaClient({ datasourceUrl: env.DATABASE_URL });
  void clearStagingDemoRelief(db, {
    appEnvironment: env.APP_ENV,
    execute: process.env.SAATHI_CLEAR_DEMO_RELIEF === 'true',
  })
    .then((counts) => console.log(`Staging demo relief cleanup complete: ${JSON.stringify(counts)}`))
    .catch((error: unknown) => {
      console.error(error instanceof Error ? error.message : 'Staging cleanup failed.');
      process.exitCode = 1;
    })
    .finally(() => db.$disconnect());
}
