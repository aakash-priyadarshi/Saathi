import { beforeAll, afterAll, describe, it, expect } from 'vitest';
import request from 'supertest';
import { createHash, randomUUID } from 'node:crypto';
import sharp from 'sharp';
import { createApp } from '../../apps/api/src/app';
import { Database } from '../../apps/api/src/database';
import { env } from '../../packages/config/src';
import { chatPerson, type Person } from '../chat-fixtures';
import { communityEvent, communityBatch, helpPayload } from '../community-fixtures';
import { hash, sign, validReceipt, type CommunityEnvelope } from '../../packages/protocol/src';
import { SyncService } from '../../apps/api/src/sync/sync.service';
import { CommunityService } from '../../apps/api/src/community/community.service';
import { ManagementService } from '../../apps/api/src/management/management.service';
import { PublicReadService } from '../../apps/api/src/public/public-read.service';
import { S3Storage } from '../../apps/api/src/media/storage';
import type { Actor } from '../../apps/api/src/auth/auth.service';
let app: Awaited<ReturnType<typeof createApp>>, db: Database;
const admin = { id: randomUUID(), role: 'ADMIN', memberships: [] } as unknown as Actor;
async function sync(person: Person, events: CommunityEnvelope[] = [], known: string[] = []) {
  const r = await request(app.getHttpServer())
    .post('/api/v1/community/sync')
    .set('Origin', 'http://localhost:3000')
    .send(await communityBatch(person, events, known));
  expect(r.status, r.body.message).toBe(201);
  return r.body;
}
async function media(
  person: Person,
  reportId: string,
  chunks: { index: number; data: string }[] = [],
) {
  const body = {
    v: 1,
    kind: 'COMMUNITY_MEDIA',
    profile: person.profile,
    issuedAt: new Date().toISOString(),
    reportId,
    chunks,
  };
  return request(app.getHttpServer())
    .post('/api/v1/community/attachment')
    .set('Origin', 'http://localhost:3000')
    .send({ body, signature: await sign(body, person.signing.privateKey) });
}
beforeAll(async () => {
  if (!env.DATABASE_URL.includes('schema=saathi_test'))
    throw new Error('Isolated test schema required.');
  app = await createApp();
  await app.init();
  db = app.get(Database);
});
afterAll(async () => {
  await app?.close();
});
describe('Participant help and report publication on PostgreSQL', () => {
  it('backfills moved-area dependencies in order, follows known requests, and excludes hidden rows from discovery', async () => {
    const author = await chatPerson('Moving requester'),
      reader = await chatPerson('Area reader');
    const help = await communityEvent(author, 'HELP', {
      version: 1,
      previousHash: null,
      help: helpPayload(),
    });
    await sync(author, [help]);
    const moved = await communityEvent(
      author,
      'HELP',
      {
        version: 2,
        previousHash: await hash(help),
        help: helpPayload({ area: 'Fictional Gate 4' }),
      },
      help.body.id,
      { expiresAt: help.body.expiresAt },
    );
    await sync(author, [moved]);
    const response = await request(app.getHttpServer())
      .post('/api/v1/community/sync')
      .set('Origin', 'http://localhost:3000')
      .send(await communityBatch(reader, [], [], ['Fictional Gate 4']));
    expect(response.status).toBe(201);
    expect(
      response.body.events
        .map((e: CommunityEnvelope) => e.body.id)
        .filter((id: string) => [help.body.id, moved.body.id].includes(id)),
    ).toEqual([help.body.id, moved.body.id]);
    expect(Buffer.byteLength(JSON.stringify(response.body))).toBeLessThan(262144);
    const follow = await sync(reader, [], [help.body.id]);
    expect(follow.events.some((e: CommunityEnvelope) => e.body.id === moved.body.id)).toBe(true);
    await app.get(CommunityService).hide(admin, help.body.id);
    const hidden = await sync(reader);
    expect(hidden.events.some((e: CommunityEnvelope) => e.body.objectId === help.body.id)).toBe(
      false,
    );
  });
  it('enforces help duplicate/cooldown bounds, requester-owned revisions, offers and terminal state', async () => {
    const author = await chatPerson('Help requester'),
      helper = await chatPerson('Help responder');
    const help = await communityEvent(author, 'HELP', {
      version: 1,
      previousHash: null,
      help: helpPayload(),
    });
    expect((await sync(helper, [help])).accepted).toContain(help.body.id);
    expect((await sync(author, [help])).accepted).toContain(help.body.id);
    const duplicate = await communityEvent(author, 'HELP', {
      version: 1,
      previousHash: null,
      help: helpPayload(),
    });
    expect((await sync(author, [duplicate])).rejected[0].reason).toContain('active request');
    const cooldown = await communityEvent(author, 'HELP', {
      version: 1,
      previousHash: null,
      help: helpPayload({ category: 'FOOD' }),
    });
    expect((await sync(author, [cooldown])).rejected[0].reason).toContain('one minute');
    const offer = await communityEvent(
      helper,
      'HELP_OFFER',
      { requestHash: await hash(help) },
      help.body.id,
      { expiresAt: help.body.expiresAt },
    );
    expect((await sync(helper, [offer])).accepted).toContain(offer.body.id);
    const assigned = await communityEvent(
      author,
      'HELP',
      {
        version: 2,
        previousHash: await hash(help),
        help: helpPayload({ status: 'RESPONDER_ASSIGNED', responderId: helper.profile.body.id }),
      },
      help.body.id,
      { expiresAt: help.body.expiresAt },
    );
    expect((await sync(author, [assigned])).accepted).toContain(assigned.body.id);
    const forged = await communityEvent(
      helper,
      'HELP',
      { version: 3, previousHash: await hash(assigned), help: helpPayload({ status: 'RESOLVED' }) },
      help.body.id,
      { expiresAt: help.body.expiresAt },
    );
    expect((await sync(helper, [forged])).rejected).toHaveLength(1);
    const resolved = await communityEvent(
      author,
      'HELP',
      { version: 3, previousHash: await hash(assigned), help: helpPayload({ status: 'RESOLVED' }) },
      help.body.id,
      { expiresAt: help.body.expiresAt },
    );
    expect((await sync(author, [resolved])).accepted).toContain(resolved.body.id);
    const reopen = await communityEvent(
      author,
      'HELP',
      { version: 4, previousHash: await hash(resolved), help: helpPayload() },
      help.body.id,
      { expiresAt: help.body.expiresAt },
    );
    expect((await sync(author, [reopen])).rejected).toHaveLength(1);
    expect(await db.reliefRequest.count({ where: { creatorId: author.profile.body.id } })).toBe(0);
  });
  it('deduplicates competing carriers, preserves report author/time, gates public feed and authenticates receipts', async () => {
    const author = await chatPerson('Original reporter'),
      b = await chatPerson('Carrier B'),
      d = await chatPerson('Carrier D');
    const createdAt = new Date(Date.now() - 3600000).toISOString();
    const report = await communityEvent(
      author,
      'REPORT',
      {
        caption: 'Fictional access route blocked',
        area: 'Fictional Gate 2',
        contentWarning: true,
        media: null,
      },
      undefined,
      { createdAt, expiresAt: new Date(Date.parse(createdAt) + 7 * 86400000).toISOString() },
    );
    const results = await Promise.all([sync(b, [report]), sync(d, [report])]);
    for (const r of results) expect(r.accepted).toContain(report.body.id);
    expect(await db.fieldUpdate.count({ where: { id: report.body.id } })).toBe(1);
    const post = await db.fieldUpdate.findUniqueOrThrow({ where: { id: report.body.id } });
    expect(post.authorId).toBeNull();
    expect(post.participantName).toBe('Original reporter');
    expect(post.createdAt.toISOString()).toBe(createdAt);
    expect(post.receivedAt.getTime()).toBeGreaterThan(Date.parse(createdAt));
    expect((await new PublicReadService(db).feed()).some((p) => p.id === report.body.id)).toBe(
      false,
    );
    await app.get(ManagementService).moderate(admin, report.body.id, 'APPROVED');
    const published = (await new PublicReadService(db).feed()).find(
      (p) => p.id === report.body.id,
    )!;
    expect(published.verificationState).toBe('PARTICIPANT');
    expect(published.organization.verified).toBe(false);
    expect(published.contentWarning).toBe(true);
    expect(published.createdAt).toBe(createdAt);
    const response = await sync(b, [], [report.body.id]);
    const receipt = response.receipts.find(
      (r: { body: { eventId: string } }) => r.body.eventId === report.body.id,
    );
    expect(receipt.body.status).toBe('PUBLISHED');
    expect(receipt.body.receivedAt).toBe(post.receivedAt.toISOString());
    expect(await validReceipt(receipt, app.get(SyncService).receiptKey().publicKey, report)).toBe(
      true,
    );
    const withdrawal = await communityEvent(
      author,
      'WITHDRAW',
      { reportHash: await hash(report) },
      report.body.id,
    );
    await sync(d, [withdrawal]);
    expect((await new PublicReadService(db).feed()).some((p) => p.id === report.body.id)).toBe(
      false,
    );
    await expect(
      app.get(ManagementService).moderate(admin, report.body.id, 'APPROVED'),
    ).rejects.toThrow('withdrew');
    await sync(b, [report]);
    expect(
      (await db.fieldUpdate.findUniqueOrThrow({ where: { id: report.body.id } })).moderation,
    ).toBe('HIDDEN');
  });
  it('resumes signed derivative chunks, rejects altered parts, sanitizes again and publishes one asset', async () => {
    const author = await chatPerson('Photo author'),
      carrier = await chatPerson('Photo carrier');
    const raw = await sharp({
      create: { width: 200, height: 120, channels: 3, background: '#075536' },
    })
      .jpeg()
      .withMetadata()
      .toBuffer();
    const id = randomUUID();
    const report = await communityEvent(author, 'REPORT', {
      caption: 'Fictional supplies at the public gate',
      area: 'Fictional Gate 2',
      contentWarning: false,
      media: {
        id,
        mime: 'image/jpeg',
        size: raw.length,
        hash: createHash('sha256').update(raw).digest('hex'),
        width: 200,
        height: 120,
        durationSeconds: 0,
      },
    });
    await sync(carrier, [report]);
    const initial = await media(carrier, report.body.id);
    expect(initial.status).toBe(201);
    expect(initial.body.missing).toEqual([0]);
    const response = await media(carrier, report.body.id, [
      { index: 0, data: raw.toString('base64url') },
    ]);
    expect(response.status, response.body.message).toBe(201);
    expect(response.body.complete).toBe(true);
    expect(
      (await media(author, report.body.id, [{ index: 0, data: raw.toString('base64url') }])).body
        .complete,
    ).toBe(true);
    const asset = await db.mediaAsset.findUniqueOrThrow({ where: { id } });
    expect(asset.processingState).toBe('READY');
    const sanitized = await app.get(S3Storage).readPrivate(asset.publicKey!);
    expect((await sharp(sanitized).metadata()).exif).toBeUndefined();
    await app.get(ManagementService).moderate(admin, report.body.id, 'APPROVED');
    expect(
      (await new PublicReadService(db).feed()).find((p) => p.id === report.body.id)!.media,
    ).toHaveLength(1);
    const large = Buffer.concat([raw, Buffer.alloc(9000)]);
    const second = await communityEvent(author, 'REPORT', {
      caption: 'Fictional transfer test',
      area: 'Fictional Gate 2',
      contentWarning: false,
      media: {
        id: randomUUID(),
        mime: 'image/jpeg',
        size: large.length,
        hash: createHash('sha256').update(large).digest('hex'),
        width: 200,
        height: 120,
        durationSeconds: 0,
      },
    });
    await sync(carrier, [second]);
    const first = { index: 0, data: large.subarray(0, 8192).toString('base64url') };
    expect((await media(carrier, second.body.id, [first])).body.complete).toBe(false);
    const changed = Buffer.from(large.subarray(0, 8192));
    changed[0] = changed[0]! ^ 1;
    expect(
      (await media(carrier, second.body.id, [{ index: 0, data: changed.toString('base64url') }]))
        .status,
    ).toBe(409);
    expect((await media(carrier, second.body.id)).body.missing).toEqual([1]);
  });
  it('limits moderation authority and exposes signed generic reports without official-need mutation', async () => {
    const author = await chatPerson('Reported requester'),
      reporter = await chatPerson('Flagger');
    const help = await communityEvent(author, 'HELP', {
      version: 1,
      previousHash: null,
      help: helpPayload(),
    });
    await sync(author, [help]);
    const flag = await communityEvent(
      reporter,
      'FLAG',
      { targetHash: await hash(help), reason: 'OTHER' },
      help.body.id,
    );
    await sync(reporter, [flag]);
    expect(
      await db.moderationReport.count({ where: { entityId: help.body.id, reason: 'OTHER' } }),
    ).toBe(1);
    const service = app.get(CommunityService);
    await expect(service.hide({ ...admin, role: 'PUBLIC' }, help.body.id)).rejects.toThrow();
    await service.hide(admin, help.body.id);
    expect(
      (await sync(author, [], [help.body.id])).receipts.find(
        (r: { body: { eventId: string } }) => r.body.eventId === help.body.id,
      ).body.status,
    ).toBe('INVALIDATED');
  });
});
