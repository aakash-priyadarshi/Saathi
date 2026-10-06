import { describe, expect, it, vi } from 'vitest';
import type { PrismaClient } from '../../packages/database/src';
import { clearStagingDemoRelief } from '../../packages/database/src/clear-staging-demo-relief';

function fakeDatabase(requests: unknown[] = []) {
  const database = {
    $transaction: vi.fn(async (callback: (tx: unknown) => Promise<unknown>) => callback(database)),
    reliefRequest: {
      findMany: vi.fn().mockResolvedValue(requests),
      count: vi.fn().mockResolvedValue(0),
      update: vi.fn().mockResolvedValue({}),
    },
    auditEvent: {
      findMany: vi.fn().mockResolvedValue([
        { event: 'REQUEST_CREATED', next: { demo: true, publicId: 'SAA-7F3K92' } },
      ]),
      create: vi.fn().mockResolvedValue({}),
      count: vi.fn().mockResolvedValue(requests.length * 2),
    },
    donationCommitment: {
      findMany: vi.fn().mockResolvedValue([
        {
          id: 'demo-commitment',
          provider: 'Local shop',
          externalOrderId: 'DEMO-SAA-7F3K92',
          request: { publicId: 'SAA-7F3K92' },
        },
      ]),
      deleteMany: vi.fn().mockResolvedValue({ count: 1 }),
    },
    fieldUpdate: {
      update: vi.fn().mockResolvedValue({}),
      count: vi.fn().mockResolvedValue(0),
    },
    delivery: { deleteMany: vi.fn().mockResolvedValue({ count: 1 }) },
    reliefPoint: { update: vi.fn().mockResolvedValue({}) },
  };
  return database as unknown as PrismaClient & typeof database;
}

const seededRequest = {
  id: 'demo-request',
  publicId: 'SAA-7F3K92',
  title: 'Drinking water',
  description: 'Seeded sample request',
  status: 'OPEN',
  archivedAt: null,
  reliefPointId: 'demo-point',
  creator: { email: 'volunteer@saathi.test' },
  organization: { name: 'Community Relief Network' },
  reliefPoint: {
    id: 'demo-point',
    name: 'Point A',
    description: 'Designated public receiving point (demonstration).',
    publicLocation: 'Community hall, east entrance',
    instructions: 'Demo delivery point.',
    operatingHours: '8:00 AM–8:00 PM IST',
    active: true,
    organization: { name: 'Community Relief Network' },
  },
  posts: [
    {
      id: 'demo-update',
      caption: 'Seeded update',
      moderation: 'APPROVED',
      author: { email: 'volunteer@saathi.test' },
      media: [],
    },
  ],
};

describe('staging demo relief cleanup', () => {
  it('refuses to run outside staging or without explicit execution authorization', async () => {
    const db = fakeDatabase();
    await expect(
      clearStagingDemoRelief(db, { appEnvironment: 'production', execute: true }),
    ).rejects.toThrow('restricted to APP_ENV=staging');
    await expect(
      clearStagingDemoRelief(db, { appEnvironment: 'staging', execute: false }),
    ).rejects.toThrow('SAATHI_CLEAR_DEMO_RELIEF=true');
    expect(db.$transaction).not.toHaveBeenCalled();
  });

  it('archives only marked demo relief, redacts linked updates, and retains audit history', async () => {
    const db = fakeDatabase([seededRequest]);
    const result = await clearStagingDemoRelief(db, { appEnvironment: 'staging', execute: true });
    expect(result).toEqual({
      requestsArchived: 1,
      updatesWithdrawn: 1,
      commitmentsRemoved: 1,
      pointsDeactivated: 1,
      requestAuditEventsRetained: 2,
    });
    expect(db.reliefRequest.findMany).toHaveBeenCalledWith({
      where: {
        publicId: {
          in: ['SAA-7F3K92', 'SAA-8M2P41', 'SAA-4R9W63', 'SAA-6N8C25', 'SAA-3B5Q87', 'SAA-2A6D19'],
        },
      },
      include: expect.any(Object),
    });
    expect(db.reliefRequest.update).toHaveBeenCalledWith({
      where: { id: 'demo-request' },
      data: expect.objectContaining({ status: 'CANCELLED', title: 'Archived staging test request' }),
    });
    expect(db.fieldUpdate.update).toHaveBeenCalledWith({
      where: { id: 'demo-update' },
      data: expect.objectContaining({
        moderation: 'HIDDEN',
        caption: 'This staging demonstration update was withdrawn.',
      }),
    });
    expect(db.reliefRequest.deleteMany).toBeUndefined();
    expect(db.delivery.deleteMany).toHaveBeenCalledWith({ where: { commitmentId: { in: ['demo-commitment'] } } });
    expect(db.reliefPoint.update).toHaveBeenCalledWith({
      where: { id: 'demo-point' },
      data: expect.objectContaining({ active: false, name: 'Archived demonstration point' }),
    });
    expect(db.auditEvent.create).toHaveBeenCalledTimes(2);
  });

  it('aborts before changing data when a linked update contains media or a non-demo author', async () => {
    const db = fakeDatabase([
      { ...seededRequest, posts: [{ ...seededRequest.posts[0], author: { email: 'real@example.org' } }] },
    ]);
    await expect(
      clearStagingDemoRelief(db, { appEnvironment: 'staging', execute: true }),
    ).rejects.toThrow('non-demo authors or media');
    expect(db.reliefRequest.update).not.toHaveBeenCalled();
    expect(db.fieldUpdate.update).not.toHaveBeenCalled();
    expect(db.delivery.deleteMany).not.toHaveBeenCalled();
  });
});
