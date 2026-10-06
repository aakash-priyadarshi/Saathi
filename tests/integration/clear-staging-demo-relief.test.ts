import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { PrismaClient } from '../../packages/database/src';
import { env } from '../../packages/config/src';
import { seed } from '../../packages/database/src/seed';
import { clearStagingDemoRelief } from '../../packages/database/src/clear-staging-demo-relief';

const db = new PrismaClient({ datasourceUrl: env.DATABASE_URL });

beforeAll(async () => {
  const databaseUrl = new URL(env.DATABASE_URL);
  if (!['localhost', '127.0.0.1'].includes(databaseUrl.hostname) || !databaseUrl.searchParams.get('schema')?.includes('saathi_test'))
    throw new Error('Refusing cleanup fixtures outside the isolated saathi_test schema.');
  if (env.APP_ENV === 'staging') {
    process.env.STAGING_SEED_ALLOWED = 'true';
    process.env.STAGING_SEED_RELIEF_DATA = 'true';
  }
  await db.$executeRawUnsafe(
    'TRUNCATE TABLE "User", "Organization", "FieldUpdate", "MediaAsset", "AuditEvent", "Notification", "IdempotencyRecord", "ModerationReport", "LoginAttempt", "OfflineEvent" RESTART IDENTITY CASCADE',
  );
  await seed(db);
});

afterAll(async () => db.$disconnect());

describe('staging relief-fixture cleanup against PostgreSQL', () => {
  it('archives and redacts only seeded relief fixtures while retaining QA accounts and audit history', async () => {
    const result = await clearStagingDemoRelief(db, { appEnvironment: 'staging', execute: true });
    expect(result).toEqual({
      requestsArchived: 6,
      updatesWithdrawn: 3,
      commitmentsRemoved: 5,
      pointsDeactivated: 3,
      requestAuditEventsRetained: 12,
    });

    const archivedRequests = await db.reliefRequest.findMany({
      orderBy: { publicId: 'asc' },
      select: { publicId: true, title: true, description: true, status: true, archivedAt: true },
    });
    expect(archivedRequests).toHaveLength(6);
    expect(archivedRequests.every((request) =>
      request.status === 'CANCELLED' &&
      request.archivedAt !== null &&
      request.title === 'Archived staging test request' &&
      request.description === 'This staging demonstration fixture was removed from public listings.',
    )).toBe(true);

    const withdrawnUpdates = await db.fieldUpdate.findMany({
      select: { caption: true, moderation: true, publishedAt: true, participantName: true, publicArea: true },
    });
    expect(withdrawnUpdates).toHaveLength(3);
    expect(withdrawnUpdates.every((update) =>
      update.moderation === 'HIDDEN' &&
      update.publishedAt === null &&
      update.participantName === null &&
      update.publicArea === null &&
      update.caption === 'This staging demonstration update was withdrawn.',
    )).toBe(true);
    expect(await db.donationCommitment.count()).toBe(0);
    expect(await db.delivery.count()).toBe(0);
    expect(await db.reliefRequestRevision.count()).toBe(0);
    expect(await db.auditEvent.count({ where: { entityType: 'ReliefRequest' } })).toBe(12);
    const archivedPoints = await db.reliefPoint.findMany({
      select: { name: true, description: true, publicLocation: true, instructions: true, operatingHours: true, active: true },
    });
    expect(archivedPoints).toHaveLength(3);
    expect(archivedPoints.every((point) =>
      point.active === false &&
      point.name === 'Archived demonstration point' &&
      point.description === 'Archived staging fixture.' &&
      point.publicLocation === 'Archived staging fixture.' &&
      point.instructions === 'Archived staging fixture.' &&
      point.operatingHours === 'Archived staging fixture.',
    )).toBe(true);
    expect(await db.reliefRequest.count({ where: { status: { notIn: ['CANCELLED', 'COMPLETED', 'EXPIRED', 'DRAFT'] } } })).toBe(0);
    expect(await db.fieldUpdate.count({ where: { moderation: 'APPROVED' } })).toBe(0);
    expect(await db.user.count()).toBe(6);
    expect(await db.organization.count()).toBe(2);
    expect(await db.user.findUnique({ where: { email: 'android-volunteer@saathi.test' } })).not.toBeNull();

    const repeated = await clearStagingDemoRelief(db, { appEnvironment: 'staging', execute: true });
    expect(repeated).toEqual({
      requestsArchived: 0,
      updatesWithdrawn: 0,
      commitmentsRemoved: 0,
      pointsDeactivated: 0,
      requestAuditEventsRetained: 12,
    });
    expect(await db.auditEvent.count({ where: { entityType: 'ReliefRequest' } })).toBe(12);
  });
});
