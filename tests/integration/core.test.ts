import { beforeAll, afterAll, describe, it, expect } from 'vitest';
import request from 'supertest';
import { randomBytes, randomUUID } from 'node:crypto';
import { createApp } from '../../apps/api/src/app';
import { Database } from '../../apps/api/src/database';
import { AuthService, actorInclude, Actor } from '../../apps/api/src/auth/auth.service';
import { DonationsService } from '../../apps/api/src/donations/donations.service';
import { RequestsService } from '../../apps/api/src/requests/requests.service';
import { seed } from '../../packages/database/src/seed';
import { env } from '../../packages/config/src';
import { digest } from '../../packages/auth/src';
import sharp from 'sharp';
import { MediaService, sanitizeImage } from '../../apps/api/src/media/media.service';
import { ManagementService } from '../../apps/api/src/management/management.service';
import { S3Storage } from '../../apps/api/src/media/storage';
import { MediaWorker } from '../../apps/api/src/media/media-worker.service';
import { PublicReadService } from '../../apps/api/src/public/public-read.service';
import { mkdtemp, readFile, rm, utimes, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createRequire } from 'node:module';
import { SyncService } from '../../apps/api/src/sync/sync.service';
import {
  createEnvelope,
  generateKeys,
  exportPublic,
  sign,
  validReceipt,
  hash,
} from '../../packages/protocol/src';
let app: Awaited<ReturnType<typeof createApp>>,
  db: Database,
  donations: DonationsService,
  requests: RequestsService,
  auth: AuthService,
  volunteer: Actor,
  doctor: Actor;
let pointId: string;
let management: ManagementService;
const origin = 'http://localhost:3000';
/** Uploads arrive on disk (multer or assembled parts), so tests hand the service a file. */
async function onDisk(bytes: Buffer) {
  const path = join(await mkdtemp(join(tmpdir(), 'saathi-upload-test-')), 'file');
  await writeFile(path, bytes);
  return { path, size: bytes.length };
}
async function ready(id: string) {
  for (let i = 0; i < 300; i++) {
    const row = await db.mediaAsset.findUniqueOrThrow({ where: { id } });
    if (row.processingState !== 'PENDING') return row;
    await new Promise((done) => setTimeout(done, 100));
  }
  throw new Error('Media processing did not finish.');
}
async function login(email: string) {
  const response = await request(app.getHttpServer())
    .post('/api/v1/auth/login')
    .set('Origin', origin)
    .send({ email, password: process.env.SEED_PASSWORD });
  expect(response.status).toBe(201);
  const cookies = (response.headers['set-cookie'] as unknown as string[])
    .map((c) => c.split(';')[0]!)
    .join('; ');
  const csrf = /saathi_csrf=([^;]+)/.exec(cookies)?.[1] ?? '';
  return { cookies, csrf };
}
async function makeNeed(quantity = 100) {
  return requests.create(volunteer, {
    reliefPointId: pointId,
    category: 'WATER',
    title: 'Integration drinking water',
    description: 'Sealed bottles for an isolated integration test.',
    requestedQuantity: quantity,
    unit: 'bottles',
    priority: 'NORMAL',
    deadline: new Date(Date.now() + 3600000).toISOString(),
  });
}
beforeAll(async () => {
  if (!env.DATABASE_URL.includes('schema=saathi_test'))
    throw new Error('Refusing to run destructive fixtures outside the isolated test schema.');
  app = await createApp();
  await app.init();
  db = app.get(Database);
  donations = app.get(DonationsService);
  requests = app.get(RequestsService);
  auth = app.get(AuthService);
  await db.$executeRawUnsafe(
    'TRUNCATE TABLE "User", "Organization", "FieldUpdate", "MediaAsset", "AuditEvent", "Notification", "IdempotencyRecord", "ModerationReport", "LoginAttempt", "OfflineEvent", "PlatformSetting" RESTART IDENTITY CASCADE',
  );
  await seed(db);
  volunteer = await db.user.findUniqueOrThrow({
    where: { email: 'volunteer@saathi.test' },
    include: actorInclude,
  });
  doctor = await db.user.findUniqueOrThrow({
    where: { email: 'medical@saathi.test' },
    include: actorInclude,
  });
  pointId = (await db.reliefPoint.findFirstOrThrow({ where: { name: 'Point A' } })).id;
  management = app.get(ManagementService);
  const admin = await db.user.findUniqueOrThrow({
    where: { email: 'admin@saathi.test' },
    include: actorInclude,
  });
  expect((await management.features(admin)).features).toEqual({ live: false, needs: false });
  await management.setLiveFeature(admin, true);
  await management.setNeedsFeature(admin, true);
});
afterAll(async () => {
  await app?.close();
});
describe('Real PostgreSQL core workflow', () => {
  it('claims isolated media work once and repairs interrupted leases and public visibility', async () => {
    const storage = app.get(S3Storage),
      media = app.get(MediaService);
    const worker = new MediaWorker(db, media);
    const id = randomUUID(),
      originalKey = `original/${id}`;
    const bytes = await sharp({
      create: { width: 24, height: 24, channels: 3, background: '#315a41' },
    })
      .jpeg()
      .toBuffer();
    await storage.putPrivate(originalKey, bytes, 'image/jpeg');
    await db.mediaAsset.create({
      data: {
        id,
        ownerId: volunteer.id,
        organizationId: volunteer.memberships[0]!.organizationId,
        originalKey,
        size: bytes.length,
        mimeType: 'image/jpeg',
      },
    });
    const pending = await db.mediaAsset.count({ where: { processingState: 'PENDING' } });
    const claimed = (
      await Promise.all(Array.from({ length: pending + 1 }, () => worker.claim()))
    ).filter((item) => item !== null);
    expect(claimed).toHaveLength(pending);
    expect(new Set(claimed.map((item) => item.id)).size).toBe(pending);
    expect(claimed.filter((item) => item.id === id)).toHaveLength(1);
    const lease = claimed.find((item) => item.id === id)!;
    for (const other of claimed.filter((item) => item.id !== id))
      await db.mediaAsset.update({
        where: { id: other.id },
        data: {
          processingState: 'PENDING',
          processingLease: null,
          processingStartedAt: null,
          processingAttempts: 0,
        },
      });
    await media.process(id, lease.processingLease!);
    const ready = await db.mediaAsset.findUniqueOrThrow({ where: { id } });
    expect(ready.processingState).toBe('READY');
    expect(ready.processingLease).toBeNull();
    expect((await storage.readPrivate(ready.publicKey!)).length).toBeGreaterThan(0);
    await storage.publish(ready.publicKey!, ready.mimeType);
    await worker.reconcilePublic(storage);
    if (env.STORAGE_PROVIDER === 'local')
      await expect(
        readFile(resolve(env.LOCAL_MEDIA_DIR, 'public', ready.publicKey!)),
      ).rejects.toThrow();
    await db.mediaAsset.update({
      where: { id },
      data: {
        processingState: 'PROCESSING',
        processingAttempts: 3,
        processingStartedAt: new Date(Date.now() - 11 * 60 * 1000),
        processingLease: randomUUID(),
      },
    });
    // Long videos may legitimately encode for hours; an 11-minute run is not stale.
    expect((await worker.reconcile()).count).toBe(0);
    await db.mediaAsset.update({
      where: { id },
      data: { processingStartedAt: new Date(Date.now() - 5 * 60 * 60 * 1000) },
    });
    expect((await worker.reconcile()).count).toBe(1);
    expect((await db.mediaAsset.findUniqueOrThrow({ where: { id } })).processingState).toBe(
      'FAILED',
    );
  });
  it('lets only admins enable or pause Live and Needs across public reads and writes', async () => {
    const admin = await db.user.findUniqueOrThrow({
      where: { email: 'admin@saathi.test' },
      include: actorInclude,
    });
    const need = await makeNeed();
    await expect(management.setNeedsFeature(volunteer, false)).rejects.toThrow();
    await expect(management.setLiveFeature(volunteer, true)).rejects.toThrow();
    await management.setLiveFeature(admin, false);
    await management.setNeedsFeature(admin, false);
    try {
      expect((await management.features(admin)).features).toEqual({ live: false, needs: false });
      expect(await new PublicReadService(db).list()).toEqual([]);
      expect(await new PublicReadService(db).feed()).toEqual([]);
      await expect(requests.get(need.publicId)).rejects.toThrow('temporarily paused');
      await expect(
        requests.create(volunteer, {
          reliefPointId: pointId,
          category: 'WATER',
          title: 'Paused request',
          description: 'This must not enter public circulation.',
          requestedQuantity: 5,
          unit: 'bottles',
          priority: 'NORMAL',
          deadline: new Date(Date.now() + 3600000).toISOString(),
        }),
      ).rejects.toThrow('temporarily paused');
      await expect(
        requests.publishGuest('guest:test', {
          caption: 'A field update should be blocked.',
          area: 'Integration fixture',
          contentWarning: false,
          mediaIds: [],
        }),
      ).rejects.toThrow('Field updates are temporarily paused');
      await expect(donations.reserve(need.publicId, 1, undefined, randomUUID())).rejects.toThrow(
        'temporarily paused',
      );
      const config = await request(app.getHttpServer())
        .get('/api/v1/public/config')
        .set('Origin', origin);
      expect(config.body.features.needs).toBe(false);
      expect(config.body.features.live).toBe(false);
    } finally {
      await management.setLiveFeature(admin, true);
      await management.setNeedsFeature(admin, true);
    }
  });
  it('prunes only aged unreferenced media while preserving references, fresh uploads and active work', async () => {
    expect(env.STORAGE_PROVIDER).toBe('local');
    const root = await mkdtemp(join(tmpdir(), 'saathi-orphan-test-'));
    const storage = new S3Storage();
    // Scope this real filesystem provider to the fixture, without changing global configuration.
    Reflect.set(storage, 'localRoot', root);
    Reflect.set(storage, 'provider', 'local');
    const worker = new MediaWorker(db, new MediaService(db, auth, storage));
    const referencedId = randomUUID(),
      activeId = randomUUID(),
      legacyId = randomUUID();
    const orphanOriginal = `original/${randomUUID()}`;
    const orphanPublic = `sanitized/${randomUUID()}.jpg`;
    const fresh = `original/${randomUUID()}`;
    const referenced = [
      `original/${referencedId}`,
      `sanitized/${referencedId}.jpg`,
      `sanitized/${referencedId}-thumb.jpg`,
    ];
    const active = `sanitized/${activeId}-${randomUUID()}.jpg`;
    const legacy = `sanitized/${legacyId}.jpg`;
    const unrelated = `unrelated/${randomUUID()}`;
    const bytes = Buffer.from('isolated storage inventory fixture');
    const old = new Date(Date.now() - 96 * 60 * 60 * 1000);
    async function put(key: string, published = false, aged = true) {
      await storage.putPrivate(key, bytes, 'image/jpeg');
      if (published) await storage.publish(key, 'image/jpeg');
      if (aged) {
        await utimes(resolve(root, 'private', key), old, old);
        if (published) await utimes(resolve(root, 'public', key), old, old);
      }
    }
    try {
      for (const [id, processingState] of [
        [referencedId, 'READY'],
        [activeId, 'PROCESSING'],
        [legacyId, 'FAILED'],
      ] as const)
        await db.mediaAsset.create({
          data: {
            id,
            processingState,
            ownerId: volunteer.id,
            organizationId: volunteer.memberships[0]!.organizationId,
            originalKey: `original/${id}`,
            size: bytes.length,
            mimeType: 'image/jpeg',
            ...(id === referencedId
              ? { publicKey: referenced[1], thumbnailKey: referenced[2] }
              : {}),
          },
        });
      await put(orphanOriginal);
      await put(orphanPublic, true);
      await put(fresh, false, false);
      await put(referenced[0]!);
      await put(referenced[1]!, true);
      await put(referenced[2]!, true);
      await put(active, true);
      await put(legacy, true);
      await put(unrelated);
      expect(await worker.reconcileOrphans(storage)).toEqual({
        inspected: 13,
        retained: 10,
        orphaned: 3,
        deleted: 0,
        prune: false,
      });
      expect(await storage.readPrivate(orphanOriginal)).toEqual(bytes);
      expect(await storage.readPublic(orphanPublic)).toEqual(bytes);
      expect(await worker.reconcileOrphans(storage, true)).toEqual({
        inspected: 13,
        retained: 10,
        orphaned: 3,
        deleted: 3,
        prune: true,
      });
      await expect(storage.readPrivate(orphanOriginal)).rejects.toMatchObject({ code: 'ENOENT' });
      await expect(storage.readPrivate(orphanPublic)).rejects.toMatchObject({ code: 'ENOENT' });
      await expect(storage.readPublic(orphanPublic)).rejects.toMatchObject({ code: 'ENOENT' });
      for (const key of [...referenced, fresh, active, legacy, unrelated])
        expect(await storage.readPrivate(key!)).toEqual(bytes);
      expect(await storage.readPublic(referenced[1]!)).toEqual(bytes);
      expect(await storage.readPublic(active)).toEqual(bytes);
      await db.mediaAsset.update({ where: { id: activeId }, data: { processingState: 'READY' } });
      expect((await worker.reconcileOrphans(storage, true)).deleted).toBe(2);
      await expect(storage.readPrivate(active)).rejects.toMatchObject({ code: 'ENOENT' });
      await expect(storage.readPublic(active)).rejects.toMatchObject({ code: 'ENOENT' });
      expect(await storage.readPrivate(legacy)).toEqual(bytes);
      expect(await storage.readPrivate(unrelated)).toEqual(bytes);
    } finally {
      await db.mediaAsset.deleteMany({ where: { id: { in: [referencedId, activeId, legacyId] } } });
      // mkdtemp creates this exact, isolated directory; ordinary media storage is never pruned.
      await rm(root, { recursive: true, force: true });
    }
  });
  it('serves only sanitized reads in the public deployment without auth, signing or write routes', async () => {
    const publicApp = await createApp('public');
    try {
      await publicApp.init();
      expect(() => publicApp.get(AuthService)).toThrow();
      expect(() => publicApp.get(SyncService)).toThrow();
      const response = await request(publicApp.getHttpServer()).get('/api/v1/public/requests');
      expect(response.status).toBe(200);
      expect(response.body.length).toBeGreaterThan(0);
      expect(response.body[0]).not.toHaveProperty('creatorId');
      expect(response.body[0]).not.toHaveProperty('organizationId');
      expect(response.body[0].creator).not.toHaveProperty('email');
      for (const path of [
        '/api/v1/auth/me',
        '/api/v1/sync/receipt-key',
        '/api/v1/volunteer/dashboard',
        '/api/v1/admin/audits',
        '/api/docs',
        '/metrics',
      ])
        expect((await request(publicApp.getHttpServer()).get(path)).status).toBe(404);
      expect(
        (
          await request(publicApp.getHttpServer())
            .post('/api/v1/public/reports')
            .set('Origin', origin)
            .send({})
        ).status,
      ).toBe(404);
    } finally {
      await publicApp.close();
    }
  });
  async function signedNeed() {
    const keys = await generateKeys(),
      sync = app.get(SyncService);
    const device = await sync.register(volunteer, await exportPublic(keys.publicKey));
    const envelope = await createEnvelope(
      {
        type: 'REQUEST_CREATED',
        authorId: volunteer.id,
        deviceId: device.id,
        organizationId: volunteer.memberships[0]!.organizationId,
        payload: {
          reliefPointId: pointId,
          category: 'WATER',
          title: 'Signed offline need',
          description: 'An original volunteer request carried by other devices.',
          requestedQuantity: 100,
          unit: 'bottles',
          priority: 'URGENT',
          deadline: new Date(Date.now() + 3600000).toISOString(),
        },
      },
      keys,
    );
    return { keys, sync, envelope, device };
  }
  it('reissues trusted stored receipts after rotating the durable signer while preserving the event action', async () => {
    const { sync, envelope } = await signedNeed();
    const old = await sync.ingest(envelope, randomUUID());
    const pair = (await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, [
      'sign',
      'verify',
    ])) as CryptoKeyPair;
    const publicKey = await exportPublic(pair.publicKey);
    const now = Date.now();
    const privateJwk = JSON.stringify(await crypto.subtle.exportKey('jwk', pair.privateKey));
    const keyring = JSON.stringify([
      {
        ...sync.receiptKey().keys[0],
        status: 'RETIRED',
        signingUntil: new Date(now + 1000).toISOString(),
      },
      {
        keyId: await hash(publicKey),
        publicKey,
        status: 'ACTIVE',
        signingFrom: new Date(now - 1000).toISOString(),
        signingUntil: new Date(now + 86400000).toISOString(),
        verifyUntil: new Date(now + 2 * 86400000).toISOString(),
      },
    ]);
    const rotated = new SyncService(db, auth, requests, app.get(MediaService), {
      SYNC_SIGNING_PRIVATE_JWK: privateJwk,
      SYNC_RECEIPT_KEYRING_JSON: keyring,
    });
    await rotated.onModuleInit();
    const receipt = await rotated.ingest(envelope, randomUUID());
    expect(receipt.body.keyId).not.toBe(old.body.keyId);
    expect(receipt.body.publicId).toBe(old.body.publicId);
    expect(await validReceipt(receipt, publicKey, envelope)).toBe(true);
    expect(
      await db.auditEvent.count({
        where: { event: 'OFFLINE_EVENT_ACCEPTED', entityId: envelope.body.id },
      }),
    ).toBe(1);
  });
  it('publishes a relayed original-author request exactly once under concurrent carriers and signs its receipt', async () => {
    const { sync, envelope } = await signedNeed();
    const results = await Promise.all(
      Array.from({ length: 5 }, () => sync.ingest(envelope, randomUUID())),
    );
    expect(new Set(results.map((r) => r.body.publicId)).size).toBe(1);
    expect(results.every((r) => r.body.status === 'PUBLISHED')).toBe(true);
    expect(await validReceipt(results[0]!, sync.receiptKey().publicKey, envelope)).toBe(true);
    const record = await db.offlineEvent.findUniqueOrThrow({ where: { id: envelope.body.id } });
    expect(record.authorId).toBe(volunteer.id);
    expect(
      (await db.reliefRequest.findUniqueOrThrow({ where: { publicId: results[0]!.body.publicId } }))
        .creatorId,
    ).toBe(volunteer.id);
    expect(
      await db.auditEvent.count({
        where: { event: 'OFFLINE_EVENT_ACCEPTED', entityId: envelope.body.id },
      }),
    ).toBe(1);
    expect(await db.syncReceipt.count({ where: { eventId: envelope.body.id } })).toBe(5);
    expect(
      await db.syncReceipt.aggregate({
        where: { eventId: envelope.body.id },
        _sum: { observations: true },
      }),
    ).toMatchObject({ _sum: { observations: 5 } });
  });
  it('rejects altered signatures without changing canonical quantities', async () => {
    const { sync, envelope } = await signedNeed();
    const changed = { ...envelope, body: { ...envelope.body, authorId: doctor.id } };
    await expect(sync.ingest(changed, randomUUID())).rejects.toMatchObject({ status: 400 });
    expect(await db.offlineEvent.count({ where: { id: envelope.body.id } })).toBe(0);
  });
  it('returns signed rejections for revoked devices and expired original events', async () => {
    const { sync, envelope, device, keys } = await signedNeed();
    await sync.revoke(volunteer, device.id);
    expect((await sync.ingest(envelope, randomUUID())).body.status).toBe('REJECTED');
    const second = await signedNeed();
    const body = {
      ...second.envelope.body,
      createdAt: new Date(Date.now() - 7200000).toISOString(),
      expiresAt: new Date(Date.now() - 3600000).toISOString(),
    };
    const expired = {
      ...second.envelope,
      body,
      signature: await sign(body, second.keys.privateKey),
    };
    expect((await sync.ingest(expired, randomUUID())).body.status).toBe('REJECTED');
    expect(keys.privateKey.extractable).toBe(false);
  });
  it('does not let a registered device forge another author or organization', async () => {
    const { sync, envelope, keys } = await signedNeed();
    const forgedBody = { ...envelope.body, authorId: doctor.id };
    await expect(
      sync.ingest(
        { ...envelope, body: forgedBody, signature: await sign(forgedBody, keys.privateKey) },
        randomUUID(),
      ),
    ).rejects.toMatchObject({ status: 400 });
    const otherOrganization = {
      ...envelope.body,
      organizationId: doctor.memberships[0]!.organizationId,
    };
    const rejected = await sync.ingest(
      {
        ...envelope,
        body: otherOrganization,
        signature: await sign(otherOrganization, keys.privateKey),
      },
      randomUUID(),
    );
    expect(rejected.body.status).toBe('REJECTED');
  });
  it('returns a signed conflict for stale changes and never reopens a terminal request', async () => {
    const { sync, keys, device } = await signedNeed(),
      need = await makeNeed();
    const changes = await createEnvelope(
      {
        type: 'REQUEST_UPDATED',
        authorId: volunteer.id,
        deviceId: device.id,
        organizationId: volunteer.memberships[0]!.organizationId,
        payload: {
          publicId: need.publicId,
          changes: { version: need.version, title: 'A stale offline edit' },
        },
      },
      keys,
    );
    await donations.reserve(need.publicId, 10, undefined, randomUUID());
    expect((await sync.ingest(changes, randomUUID())).body.status).toBe('CONFLICT');
    expect((await requests.get(need.publicId)).title).toBe(need.title);
  });
  it('propagates admin withdrawal in a signed receipt and canonical request state', async () => {
    const { sync, envelope } = await signedNeed(),
      first = await sync.ingest(envelope, randomUUID());
    const admin = await db.user.findUniqueOrThrow({
      where: { email: 'admin@saathi.test' },
      include: actorInclude,
    });
    const withdrawn = await sync.invalidate(admin, envelope.body.id);
    expect(withdrawn.body.status).toBe('INVALIDATED');
    expect(await validReceipt(withdrawn, sync.receiptKey().publicKey, envelope)).toBe(true);
    expect((await requests.get(first.body.publicId!)).status).toBe('CANCELLED');
    expect((await sync.ingest(envelope, randomUUID())).body.status).toBe('INVALIDATED');
  });
  it('keeps offline field reports private until review after later media arrives and propagates withdrawal', async () => {
    const { sync, keys, device } = await signedNeed();
    const bytes = await sharp({
      create: { width: 30, height: 30, channels: 3, background: '#216352' },
    })
      .jpeg()
      .toBuffer();
    const media = app.get(MediaService),
      storage = app.get(S3Storage);
    const envelope = await createEnvelope(
      {
        type: 'FIELD_PUBLISHED',
        authorId: volunteer.id,
        deviceId: device.id,
        organizationId: volunteer.memberships[0]!.organizationId,
        payload: {
          reliefPointId: pointId,
          caption: 'Offline field report with private media arriving later.',
          mediaIds: [],
        },
      },
      keys,
    );
    const receipt = await sync.ingest(envelope, randomUUID());
    expect(receipt.body.status).toBe('ACCEPTED');
    expect(receipt.body.fieldId).toBeTruthy();
    expect((await requests.feed()).some((post) => post.id === receipt.body.fieldId)).toBe(false);
    const initialAsset = await media.upload(
      volunteer,
      volunteer.memberships[0]!.organizationId,
      await onDisk(bytes),
    );
    const initialMedia = await db.mediaAsset.findUniqueOrThrow({ where: { id: initialAsset.id } });
    const initialAttachment = await sync.attachMedia(volunteer, envelope.body.id, [
      initialAsset.id,
    ]);
    expect(initialAttachment.body.status).toBe('ACCEPTED');
    await expect(storage.readPublic(initialMedia.publicKey!)).rejects.toThrow();
    const admin = await db.user.findUniqueOrThrow({
      where: { email: 'admin@saathi.test' },
      include: actorInclude,
    });
    const management = app.get(ManagementService);
    await management.moderate(admin, receipt.body.fieldId!, 'APPROVED');
    const firstPublished = await db.mediaAsset.findUniqueOrThrow({
      where: { id: initialAsset.id },
    });
    expect((await storage.readPublic(firstPublished.publicKey!)).length).toBeGreaterThan(0);
    expect(firstPublished.thumbnailKey).toBeTruthy();
    expect((await storage.readPublic(firstPublished.thumbnailKey!)).length).toBeGreaterThan(0);

    const attachedAsset = await media.upload(
      volunteer,
      volunteer.memberships[0]!.organizationId,
      await onDisk(bytes),
    );
    await expect(
      sync.attachMedia(doctor, envelope.body.id, [attachedAsset.id]),
    ).rejects.toMatchObject({
      status: 403,
    });
    const attached = await sync.attachMedia(volunteer, envelope.body.id, [attachedAsset.id]);
    expect(attached.body.status).toBe('ACCEPTED');
    expect(attached.body.message).toContain('administrator review');
    expect((await requests.feed()).some((post) => post.id === receipt.body.fieldId)).toBe(false);
    expect(await db.mediaAsset.findUniqueOrThrow({ where: { id: initialAsset.id } })).toMatchObject(
      {
        moderation: 'PENDING',
        processingState: 'READY',
      },
    );
    expect(
      await db.mediaAsset.findUniqueOrThrow({ where: { id: attachedAsset.id } }),
    ).toMatchObject({
      moderation: 'PENDING',
      processingState: 'READY',
    });
    await expect(storage.readPublic(firstPublished.publicKey!)).rejects.toThrow();
    await expect(storage.readPublic(firstPublished.thumbnailKey!)).rejects.toThrow();
    await sync.attachMedia(volunteer, envelope.body.id, [attachedAsset.id]);
    expect(await db.mediaAsset.count({ where: { fieldUpdateId: receipt.body.fieldId } })).toBe(2);
    const pending = (await sync.receipts([envelope.body.id]))[0]!;
    expect(pending.body.status).toBe('ACCEPTED');
    expect(await validReceipt(pending, sync.receiptKey().publicKey, envelope)).toBe(true);
    await management.moderate(admin, receipt.body.fieldId!, 'APPROVED');
    const published = (await sync.receipts([envelope.body.id]))[0]!;
    expect(published.body.status).toBe('PUBLISHED');
    expect(
      (await requests.feed()).find((post) => post.id === receipt.body.fieldId)?.media,
    ).toHaveLength(2);
    const republished = await db.mediaAsset.findUniqueOrThrow({ where: { id: initialAsset.id } });
    expect((await storage.readPublic(republished.publicKey!)).length).toBeGreaterThan(0);
    expect((await storage.readPublic(republished.thumbnailKey!)).length).toBeGreaterThan(0);
    const newlyPublished = await db.mediaAsset.findUniqueOrThrow({
      where: { id: attachedAsset.id },
    });
    expect((await storage.readPublic(newlyPublished.publicKey!)).length).toBeGreaterThan(0);
    expect((await storage.readPublic(newlyPublished.thumbnailKey!)).length).toBeGreaterThan(0);
    expect(await validReceipt(published, sync.receiptKey().publicKey, envelope)).toBe(true);
    expect((await sync.invalidate(admin, envelope.body.id)).body.status).toBe('INVALIDATED');
    expect((await requests.feed()).some((post) => post.id === receipt.body.fieldId)).toBe(false);
    await expect(storage.readPublic(republished.publicKey!)).rejects.toThrow();
    await expect(storage.readPublic(republished.thumbnailKey!)).rejects.toThrow();
    await expect(storage.readPublic(newlyPublished.publicKey!)).rejects.toThrow();
    await expect(storage.readPublic(newlyPublished.thumbnailKey!)).rejects.toThrow();
  });
  it('does not report a scheduled offline field post as published early', async () => {
    const { sync, keys, device } = await signedNeed();
    const envelope = await createEnvelope(
      {
        type: 'FIELD_PUBLISHED',
        authorId: volunteer.id,
        deviceId: device.id,
        organizationId: volunteer.memberships[0]!.organizationId,
        payload: {
          reliefPointId: pointId,
          caption: 'A field report scheduled for later publication.',
          mediaIds: [],
          publishAt: new Date(Date.now() + 3600000).toISOString(),
        },
      },
      keys,
    );
    const receipt = await sync.ingest(envelope, randomUUID());
    expect(receipt.body.status).toBe('ACCEPTED');
    const admin = await db.user.findUniqueOrThrow({
      where: { email: 'admin@saathi.test' },
      include: actorInclude,
    });
    await app.get(ManagementService).moderate(admin, receipt.body.fieldId!, 'APPROVED');
    expect((await sync.receipts([envelope.body.id]))[0]!.body.status).toBe('ACCEPTED');
    await db.fieldUpdate.update({
      where: { id: receipt.body.fieldId! },
      data: { publishAt: new Date(Date.now() - 1000) },
    });
    expect((await sync.receipts([envelope.body.id]))[0]!.body.status).toBe('PUBLISHED');
  });
  it('supports admin organization setup, coordinator approval, and immediate volunteer suspension', async () => {
    const admin = await login('admin@saathi.test');
    const write = (path: string, credentials: typeof admin, body: object) =>
      request(app.getHttpServer())
        .post(`/api/v1/${path}`)
        .set('Origin', origin)
        .set('Cookie', credentials.cookies)
        .set('X-CSRF-Token', credentials.csrf)
        .send(body);
    const organization = await write('admin/organizations', admin, {
      name: 'Integration verified relief team',
    });
    expect(organization.status).toBe(201);
    expect(organization.body.verified).toBe(true);
    const coordinatorEmail = 'new-coordinator@saathi.test';
    const coordinatorInvite = await write('coordinator/invite', admin, {
      email: coordinatorEmail,
      displayName: 'New coordinator',
      password: process.env.SEED_PASSWORD,
      organizationId: organization.body.id,
      role: 'COORDINATOR',
    });
    expect(coordinatorInvite.status).toBe(201);
    const coordinator = await login(coordinatorEmail);
    const volunteerEmail = 'new-volunteer@saathi.test';
    const invitation = await write('coordinator/invite', coordinator, {
      email: volunteerEmail,
      displayName: 'New volunteer',
      password: process.env.SEED_PASSWORD,
      organizationId: organization.body.id,
      role: 'VOLUNTEER',
    });
    expect(invitation.status).toBe(201);
    const unapproved = await request(app.getHttpServer())
      .post('/api/v1/auth/login')
      .set('Origin', origin)
      .send({ email: volunteerEmail, password: process.env.SEED_PASSWORD });
    expect(unapproved.status).toBe(401);
    const membership = await db.organizationMembership.findFirstOrThrow({
      where: { userId: invitation.body.id },
    });
    expect(
      (await write(`coordinator/volunteers/${membership.id}/approve`, coordinator, {})).status,
    ).toBe(201);
    const approved = await login(volunteerEmail);
    const me = await request(app.getHttpServer())
      .get('/api/v1/auth/me')
      .set('Cookie', approved.cookies);
    expect(me.status).toBe(200);
    expect(
      (await write(`coordinator/volunteers/${membership.id}/suspend`, coordinator, {})).status,
    ).toBe(201);
    expect(
      (await request(app.getHttpServer()).get('/api/v1/auth/me').set('Cookie', approved.cookies))
        .status,
    ).toBe(401);
    expect(
      await db.auditEvent.count({
        where: { entityId: invitation.body.id, event: 'VOLUNTEER_APPROVED' },
      }),
    ).toBe(1);
  });
  it("preserves an unavailable organization's canonical record without claiming verification or accepting supplies", async () => {
    const need = await makeNeed();
    const organizationId = volunteer.memberships[0]!.organizationId;
    await db.organization.update({ where: { id: organizationId }, data: { active: false } });
    try {
      const canonical = await requests.get(need.publicId);
      expect(canonical.verified).toBe(false);
      expect(canonical.organization.verified).toBe(false);
      expect((await requests.list()).some((r) => r.publicId === need.publicId)).toBe(false);
      await expect(
        donations.reserve(need.publicId, 10, undefined, randomUUID()),
      ).rejects.toMatchObject({ status: 409 });
    } finally {
      await db.organization.update({ where: { id: organizationId }, data: { active: true } });
    }
  });
  it('lets public users browse without exposing internal or private details', async () => {
    const result = await request(app.getHttpServer()).get('/api/v1/public/requests');
    expect(result.status).toBe(200);
    expect(result.body.length).toBeGreaterThan(0);
    const record = result.body[0];
    expect(record.id).toBeUndefined();
    expect(record.creator.email).toBeUndefined();
    expect(record.reliefPoint.latitude).toBeUndefined();
    expect(record.organization.id).toBeUndefined();
  });
  it('rejects a normal public account creating a verified request', async () => {
    const { cookies, csrf } = await login('public@saathi.test');
    const need = await makeNeed();
    const result = await request(app.getHttpServer())
      .post('/api/v1/volunteer/requests')
      .set('Origin', origin)
      .set('Cookie', cookies)
      .set('X-CSRF-Token', csrf)
      .send({
        reliefPointId: pointId,
        category: 'WATER',
        title: 'Fake verified need',
        description: 'An unauthorized need',
        requestedQuantity: 10,
        unit: 'bottles',
        priority: 'NORMAL',
        deadline: need.deadline,
      });
    expect(result.status).toBe(403);
  });
  it('rejects cross-organization edits', async () => {
    const need = await makeNeed();
    await expect(
      requests.edit(doctor, need.publicId, {
        version: need.version,
        title: 'Changed by another organization',
      }),
    ).rejects.toMatchObject({ status: 403 });
  });
  it('protects against CSRF and forged origins', async () => {
    const { cookies } = await login('volunteer@saathi.test');
    const result = await request(app.getHttpServer())
      .post('/api/v1/volunteer/feed')
      .set('Origin', origin)
      .set('Cookie', cookies)
      .send({ caption: 'Valid caption but invalid security token', reliefPointId: pointId });
    expect(result.status).toBe(403);
    const forged = await request(app.getHttpServer())
      .post('/api/v1/auth/login')
      .set('Origin', 'https://forged.test')
      .send({ email: 'volunteer@saathi.test', password: process.env.SEED_PASSWORD });
    expect(forged.status).toBe(403);
  });
  it('prevents two simultaneous donors from exceeding the final 100 units', async () => {
    const need = await makeNeed();
    const result = await Promise.allSettled([
      donations.reserve(need.publicId, 100, undefined, randomUUID()),
      donations.reserve(need.publicId, 100, undefined, randomUUID()),
    ]);
    expect(result.filter((r) => r.status === 'fulfilled')).toHaveLength(1);
    expect((await requests.get(need.publicId)).committedQuantity).toBe(100);
    expect(
      await db.donationCommitment.count({ where: { request: { publicId: need.publicId } } }),
    ).toBe(1);
  });
  it('keeps twenty parallel partial commitments within the requested quantity', async () => {
    const need = await makeNeed();
    const result = await Promise.allSettled(
      Array.from({ length: 20 }, () =>
        donations.reserve(need.publicId, 10, undefined, randomUUID()),
      ),
    );
    expect(result.filter((r) => r.status === 'fulfilled')).toHaveLength(10);
    expect((await requests.get(need.publicId)).committedQuantity).toBe(100);
  });
  it('deduplicates simultaneous retries and rejects key reuse with changed input', async () => {
    const need = await makeNeed(),
      key = randomUUID();
    const results = await Promise.all(
      Array.from({ length: 5 }, () =>
        donations.reserve(need.publicId, 20, 'donor@example.org', key),
      ),
    );
    expect(new Set(results.map((r) => r.id)).size).toBe(1);
    expect((await requests.get(need.publicId)).committedQuantity).toBe(20);
    await expect(
      donations.reserve(need.publicId, 21, 'donor@example.org', key),
    ).rejects.toMatchObject({ status: 409 });
  });
  it('requires an idempotency key on donation HTTP endpoints', async () => {
    const need = await makeNeed();
    const result = await request(app.getHttpServer())
      .post('/api/v1/donations')
      .set('Origin', origin)
      .send({ publicId: need.publicId, quantity: 10 });
    expect(result.status).toBe(400);
  });
  it('releases expired reservations and prevents placing orders on them', async () => {
    const need = await makeNeed(),
      c = await donations.reserve(need.publicId, 100, undefined, randomUUID());
    await db.donationCommitment.update({
      where: { id: c.id },
      data: { expiresAt: new Date(Date.now() - 1000) },
    });
    await donations.sweep();
    expect((await requests.get(need.publicId)).remainingQuantity).toBe(100);
    await expect(
      donations.order(
        c.trackingToken,
        { provider: 'Self delivery', externalOrderId: 'test', eta: new Date().toISOString() },
        randomUUID(),
      ),
    ).rejects.toMatchObject({ status: 409 });
  });
  it('records partial deliveries, completes the request, preserves its canonical page, and queues thank-you email', async () => {
    const need = await makeNeed(40),
      c = await donations.reserve(need.publicId, 40, 'donor@example.org', randomUUID());
    await donations.order(
      c.trackingToken,
      {
        provider: 'Local shop',
        externalOrderId: 'TEST-40',
        eta: new Date(Date.now() + 60000).toISOString(),
      },
      randomUUID(),
    );
    await donations.confirm(volunteer, c.id, randomUUID());
    let current = await requests.get(need.publicId);
    const firstKey = randomUUID();
    await donations.receive(volunteer, c.id, 15, current.version, firstKey);
    await donations.receive(volunteer, c.id, 15, current.version, firstKey);
    current = await requests.get(need.publicId);
    expect(current.receivedQuantity).toBe(15);
    expect(current.status).toBe('PARTIALLY_RECEIVED');
    await donations.receive(volunteer, c.id, 25, current.version, randomUUID());
    const canonical = await requests.get(need.publicId);
    expect(canonical.status).toBe('COMPLETED');
    expect(canonical.remainingQuantity).toBe(0);
    expect((await requests.list()).some((r) => r.publicId === need.publicId)).toBe(false);
    expect((await donations.tracking(c.trackingToken)).status).toBe('DELIVERED');
    expect(
      await db.notification.count({
        where: { kind: 'DELIVERY_CONFIRMED', email: 'donor@example.org' },
      }),
    ).toBeGreaterThan(0);
    expect(
      await db.auditEvent.count({
        where: {
          event: 'REQUEST_COMPLETED',
          entityId: (
            await db.reliefRequest.findUniqueOrThrow({ where: { publicId: need.publicId } })
          ).id,
        },
      }),
    ).toBe(1);
  });
  it('enforces optimistic concurrency and rejects stale quantity edits', async () => {
    const need = await makeNeed();
    await donations.reserve(need.publicId, 10, undefined, randomUUID());
    await expect(
      requests.edit(volunteer, need.publicId, { version: need.version, title: 'Stale edit' }),
    ).rejects.toMatchObject({ status: 409 });
  });
  it('requires an unguessable contribution token', async () => {
    const need = await makeNeed(),
      c = await donations.reserve(need.publicId, 10, undefined, randomUUID());
    expect((await db.donationCommitment.findUniqueOrThrow({ where: { id: c.id } })).tokenHash).toBe(
      digest(c.trackingToken),
    );
    await expect(donations.tracking('not-a-valid-token')).rejects.toMatchObject({ status: 404 });
    expect(await donations.tracking(c.trackingToken)).not.toHaveProperty('email');
  });
  it('holds a verified field update for review while atomically creating its linked need', async () => {
    const input = {
      reliefPointId: pointId,
      category: 'WATER' as const,
      title: 'Linked supply need',
      description: 'A linked public relief need.',
      requestedQuantity: 20,
      unit: 'bottles',
      priority: 'NORMAL' as const,
      deadline: new Date(Date.now() + 3600000).toISOString(),
    };
    const post = await requests.publish(volunteer, {
      reliefPointId: pointId,
      caption: 'A verified test field update with a linked need.',
      mediaIds: [],
      request: input,
    });
    expect(post.moderation).toBe('PENDING');
    const row = await db.fieldUpdate.findUniqueOrThrow({ where: { id: post.id } });
    expect(row.requestId).toBeTruthy();
    expect((await requests.feed()).some((p) => p.id === row.id)).toBe(false);
    const administrator = await db.user.findUniqueOrThrow({
      where: { email: 'admin@saathi.test' },
      include: actorInclude,
    });
    await app.get(ManagementService).moderate(administrator, row.id, 'APPROVED');
    expect((await requests.feed()).some((p) => p.id === row.id)).toBe(true);
  });
  it('blocks audit mutation and request deletion at the database layer', async () => {
    const audit = await db.auditEvent.findFirstOrThrow();
    await expect(
      db.auditEvent.update({ where: { id: audit.id }, data: { event: 'TAMPERED' } }),
    ).rejects.toThrow('append-only');
    const need = await makeNeed();
    await expect(db.reliefRequest.delete({ where: { publicId: need.publicId } })).rejects.toThrow(
      'archived',
    );
  });
  it('revokes a session immediately', async () => {
    const { cookies, csrf } = await login('volunteer@saathi.test');
    const result = await request(app.getHttpServer())
      .post('/api/v1/auth/logout')
      .set('Origin', origin)
      .set('Cookie', cookies)
      .set('X-CSRF-Token', csrf);
    expect(result.status).toBe(201);
    const me = await request(app.getHttpServer()).get('/api/v1/auth/me').set('Cookie', cookies);
    expect(me.status).toBe(401);
  });
  it('rejects organization spoofing using server-side membership', () => {
    expect(() => auth.requireOrg(volunteer, doctor.memberships[0]!.organizationId)).toThrow();
  });
  it('keeps sanitized field media private until approval and removes derivatives when hidden', async () => {
    const bytes = await sharp({
      create: { width: 40, height: 30, channels: 3, background: '#216352' },
    })
      .jpeg()
      .withExif({
        IFD0: {
          Artist: 'PRIVATE-VOLUNTEER',
          Make: 'PRIVATE-DEVICE',
          ImageDescription: 'Private location',
        },
      })
      .toBuffer();
    expect((await sharp(bytes).metadata()).exif).toBeDefined();
    const media = app.get(MediaService),
      management = app.get(ManagementService),
      storage = app.get(S3Storage);
    const a = await media.upload(
      volunteer,
      volunteer.memberships[0]!.organizationId,
      await onDisk(bytes),
    );
    const row = await db.mediaAsset.findUniqueOrThrow({ where: { id: a.id } });
    expect(row.processingState).toBe('READY');
    const safe = await storage.readPrivate(row.publicKey!);
    const meta = await sharp(safe).metadata();
    expect(meta.exif).toBeUndefined();
    expect(meta.icc).toBeUndefined();
    await expect(storage.readPublic(row.publicKey!)).rejects.toThrow();
    const p = await requests.publish(volunteer, {
      caption: 'A demo media post published without approval.',
      reliefPointId: pointId,
      mediaIds: [a.id],
    });
    expect(p.moderation).toBe('PENDING');
    expect((await requests.feed()).some((item) => item.id === p.id)).toBe(false);
    const coordinator = await db.user.findUniqueOrThrow({
      where: { email: 'coordinator@saathi.test' },
      include: actorInclude,
    });
    await expect(storage.readPublic(row.publicKey!)).rejects.toThrow();
    await management.moderate(coordinator, p.id, 'APPROVED');
    expect((await requests.feed()).find((item) => item.id === p.id)?.media).toHaveLength(1);
    expect((await storage.readPublic(row.publicKey!)).length).toBeGreaterThan(0);
    await management.moderate(coordinator, p.id, 'HIDDEN');
    expect((await requests.feed()).some((item) => item.id === p.id)).toBe(false);
    await expect(storage.readPublic(row.publicKey!)).rejects.toThrow();
  });
  it('lets a guest submit a multi-part upload without an account, pending admin review', async () => {
    const server = app.getHttpServer(),
      token = randomBytes(32).toString('base64url'),
      other = randomBytes(32).toString('base64url');
    // Incompressible pixels give a PNG larger than one 8 MiB upload part.
    const bytes = await sharp(randomBytes(1800 * 1800 * 3), {
      raw: { width: 1800, height: 1800, channels: 3 },
    })
      .png({ compressionLevel: 0 })
      .toBuffer();
    expect(bytes.length).toBeGreaterThan(8 * 1024 * 1024);
    const tooLarge = await request(server)
      .post('/api/v1/uploads')
      .set('Origin', origin)
      .set('X-Upload-Token', token)
      .send({ size: 250 * 1024 * 1024 + 1 });
    expect(tooLarge.status).toBe(400);
    const begin = await request(server)
      .post('/api/v1/uploads')
      .set('Origin', origin)
      .set('X-Upload-Token', token)
      .send({ size: bytes.length });
    expect(begin.status, begin.body.message).toBe(201);
    expect(begin.body.parts).toBe(2);
    const part = (index: number, owner: string) =>
      request(server)
        .put(`/api/v1/uploads/${begin.body.uploadId}/parts/${index}`)
        .set('Origin', origin)
        .set('X-Upload-Token', owner)
        .set('Content-Type', 'application/octet-stream')
        .send(bytes.subarray(index * begin.body.partSize, (index + 1) * begin.body.partSize));
    expect((await part(0, other)).status).toBe(404);
    expect((await part(0, token)).status).toBe(200);
    const early = await request(server)
      .post(`/api/v1/uploads/${begin.body.uploadId}/complete`)
      .set('Origin', origin)
      .set('X-Upload-Token', token);
    expect(early.status).toBe(400);
    expect((await part(1, token)).status).toBe(200);
    const done = await request(server)
      .post(`/api/v1/uploads/${begin.body.uploadId}/complete`)
      .set('Origin', origin)
      .set('X-Upload-Token', token);
    expect(done.status, done.body.message).toBe(201);
    expect(done.body.processingState).toBe('READY');
    const post = (owner: string, caption: string) =>
      request(server)
        .post('/api/v1/guest/posts')
        .set('Origin', origin)
        .set('X-Upload-Token', owner)
        .send({ caption, area: 'Fictional Gate 4', mediaIds: [done.body.id] });
    expect((await post(other, 'Fictional flooding near the gate')).status).toBe(400);
    expect((await post(token, 'Call me on +91 98765 43210')).status).toBe(400);
    const shared = await post(token, 'Fictional flooding near the gate');
    expect(shared.status, shared.body.message).toBe(201);
    expect((await requests.feed()).some((p) => p.id === shared.body.id)).toBe(false);
    const administrator = await db.user.findUniqueOrThrow({
      where: { email: 'admin@saathi.test' },
      include: actorInclude,
    });
    await app.get(ManagementService).moderate(administrator, shared.body.id, 'APPROVED');
    const item = (await requests.feed()).find((p) => p.id === shared.body.id);
    expect(item?.verificationState).toBe('PARTICIPANT');
    expect(item?.author.displayName).toBe('Guest');
    expect(item?.media).toHaveLength(1);
    expect(await db.mediaAsset.count({ where: { id: begin.body.uploadId } })).toBe(0);
  });
  it('keeps the full length of a video longer than the former two-minute cut', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'saathi-long-video-'));
    try {
      const binary = createRequire(resolve('apps/api/package.json'))('ffmpeg-static') as string,
        source = join(dir, 'long.mp4');
      const run = (args: string[]) =>
        promisify(execFile)(binary, args, { windowsHide: true }).catch(
          (error: { stderr?: string }) => ({ stderr: error.stderr ?? '' }),
        );
      await run([
        '-y',
        '-f',
        'lavfi',
        '-i',
        'color=c=blue:s=64x48:r=2:d=150',
        '-c:v',
        'libx264',
        '-threads',
        '1',
        source,
      ]);
      const a = await app
        .get(MediaService)
        .upload(
          volunteer,
          volunteer.memberships[0]!.organizationId,
          await onDisk(await readFile(source)),
        );
      const row = await ready(a.id);
      expect(row.processingState).toBe('READY');
      const output = join(dir, 'sanitized.mp4');
      await writeFile(output, await app.get(S3Storage).readPrivate(row.publicKey!));
      // ffmpeg -i with no output exits non-zero but prints the input duration.
      const { stderr } = await run(['-i', output]);
      const [, m, sec] = /Duration: 00:(\d\d):(\d\d)/.exec(stderr) ?? [];
      expect(Number(m) * 60 + Number(sec)).toBeGreaterThanOrEqual(149);
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });
  it('rejects executable content disguised as a photo', async () => {
    await expect(sanitizeImage(Buffer.from('<script>malicious</script>'))).rejects.toThrow();
  });
  it('sanitizes video and generates a private thumbnail', async () => {
    const dir = await mkdtemp(join(tmpdir(), 'saathi-video-test-'));
    try {
      const binary = createRequire(resolve('apps/api/package.json'))('ffmpeg-static') as string,
        source = join(dir, 'source.mp4');
      await promisify(execFile)(
        binary,
        [
          '-y',
          '-f',
          'lavfi',
          '-i',
          'color=c=green:s=160x120:d=1',
          '-metadata',
          'title=PRIVATE LOCATION',
          '-metadata',
          'artist=PRIVATE VOLUNTEER',
          '-c:v',
          'libx264',
          '-threads',
          '1',
          source,
        ],
        { windowsHide: true },
      );
      const bytes = await readFile(source);
      const a = await app
        .get(MediaService)
        .upload(volunteer, volunteer.memberships[0]!.organizationId, await onDisk(bytes));
      // Videos have no length limit, so inline mode finishes them in the background.
      expect(a.processingState).toBe('PENDING');
      const row = await ready(a.id);
      expect(row.mimeType).toBe('video/mp4');
      expect(row.processingState).toBe('READY');
      const sanitized = await app.get(S3Storage).readPrivate(row.publicKey!);
      expect(sanitized.includes(Buffer.from('PRIVATE LOCATION'))).toBe(false);
      expect(sanitized.includes(Buffer.from('PRIVATE VOLUNTEER'))).toBe(false);
      expect(
        (await sharp(await app.get(S3Storage).readPrivate(row.thumbnailKey!)).metadata()).exif,
      ).toBeUndefined();
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  });
});
