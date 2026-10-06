import { beforeAll, afterAll, describe, it, expect } from 'vitest';
import request from 'supertest';
import { randomUUID, createHash } from 'node:crypto';
import { createApp } from '../../apps/api/src/app';
import { Database } from '../../apps/api/src/database';
import { env } from '../../packages/config/src';
import { chatPerson, chatPolicy, chatMessage, chatBatch, type Person } from '../chat-fixtures';
import { sign, hash, encryptChatValue, type ChatMessage } from '../../packages/protocol/src';
let app: Awaited<ReturnType<typeof createApp>>, db: Database;
const origin = 'http://localhost:3000';
async function sync(person: Person, fields: Parameters<typeof chatBatch>[1] = {}) {
  const response = await request(app.getHttpServer())
    .post('/api/v1/chat/sync')
    .set('Origin', origin)
    .send(await chatBatch(person, fields));
  expect(response.status, response.body.message).toBe(201);
  return response.body;
}
beforeAll(async () => {
  if (!env.DATABASE_URL.includes('schema=saathi_test'))
    throw new Error('Chat fixtures require the isolated schema.');
  app = await createApp();
  await app.init();
  db = app.get(Database);
  await db.$executeRawUnsafe(
    'TRUNCATE TABLE "ChatParticipant", "ChatConversation", "ChatAttachment", "ChatBlock", "ChatReport", "ChatSyncNonce" CASCADE',
  );
});
afterAll(async () => {
  await app?.close();
});
describe('Real PostgreSQL operational chat boundary', () => {
  it('replaying a cached request cannot undo a later block', async () => {
    const a = await chatPerson('Replay A'),
      b = await chatPerson('Replay B'),
      m = await chatMessage(a, b);
    await sync(a, { peers: [b.profile], messages: [m] });
    const old = await chatBatch(b, { blocks: [] });
    expect(
      (await request(app.getHttpServer()).post('/api/v1/chat/sync').set('Origin', origin).send(old))
        .status,
    ).toBe(201);
    await sync(b, { blocks: [a.profile.body.id] });
    expect(
      (await request(app.getHttpServer()).post('/api/v1/chat/sync').set('Origin', origin).send(old))
        .status,
    ).toBe(201);
    expect(
      await db.chatBlock.count({
        where: { participantId: b.profile.body.id, blockedId: a.profile.body.id },
      }),
    ).toBe(1);
  });
  it('resumes opaque private media, rejects conflicting chunks and denies nonmembers and blocks', async () => {
    const a = await chatPerson('Media A'),
      b = await chatPerson('Media B'),
      c = await chatPerson('Media outsider');
    const m = await chatMessage(a, b),
      plain = Buffer.alloc(17000, 37),
      key = crypto.getRandomValues(new Uint8Array(32)),
      nonce = crypto.getRandomValues(new Uint8Array(12)),
      fileId = randomUUID();
    const cipher = Buffer.concat([
      Buffer.from(nonce),
      Buffer.from(
        await crypto.subtle.encrypt(
          {
            name: 'AES-GCM',
            iv: nonce,
            additionalData: new TextEncoder().encode('SWARM_ATTACHMENT_V1/' + fileId),
          },
          await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['encrypt']),
          plain,
        ),
      ),
    ]);
    const digest = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');
    const payload = {
      attachment: {
        id: fileId,
        name: 'Private file.txt',
        mime: 'text/plain',
        size: plain.length,
        hash: digest(plain),
        cipherHash: digest(cipher),
        key: Buffer.from(key).toString('base64url'),
      },
    };
    m.body.format = 'FILE';
    m.body.content = await encryptChatValue(
      payload,
      b.profile.body.encryptionKey,
      `dm:${m.body.conversationId}:${m.body.id}:${b.profile.body.id}`,
    );
    m.signature = await sign(m.body, a.signing.privateKey);
    await sync(a, { peers: [b.profile], messages: [m] });
    const manifestBody = {
      v: 1 as const,
      kind: 'CHAT_ATTACHMENT' as const,
      id: fileId,
      messageId: m.body.id,
      messageHash: await hash(m),
      author: a.profile,
      size: cipher.length,
      cipherHash: digest(cipher),
      expiresAt: m.body.expiresAt,
    };
    const manifest = {
      body: manifestBody,
      signature: await sign(manifestBody, a.signing.privateKey),
    };
    const attachment = async (person: Person, fields: object) => {
      const body = {
        v: 1,
        kind: 'CHAT_ATTACHMENT_REQUEST',
        profile: person.profile,
        issuedAt: new Date().toISOString(),
        messageId: m.body.id,
        manifest: null,
        parts: [],
        chunks: [],
        ...fields,
      };
      return request(app.getHttpServer())
        .post('/api/v1/chat/attachment')
        .set('Origin', origin)
        .send({ body, signature: await sign(body, person.signing.privateKey) });
    };
    const first = await attachment(a, {
      manifest,
      chunks: [{ part: 0, data: cipher.subarray(0, 8192).toString('base64url') }],
    });
    expect(first.status, first.body.message).toBe(201);
    expect(first.body.received).toEqual([0]);
    expect(first.body.complete).toBe(false);
    expect(
      (
        await attachment(a, {
          manifest,
          chunks: [{ part: 0, data: cipher.subarray(0, 8192).toString('base64url') }],
        })
      ).status,
    ).toBe(201);
    expect(
      (
        await attachment(a, {
          chunks: [{ part: 0, data: Buffer.alloc(8192).toString('base64url') }],
        })
      ).status,
    ).toBe(409);
    expect((await attachment(c, { parts: [0] })).status).toBe(403);
    const end = await attachment(a, {
      chunks: [1, 2].map((part) => ({
        part,
        data: cipher.subarray(part * 8192, (part + 1) * 8192).toString('base64url'),
      })),
    });
    expect(end.status, end.body.message).toBe(201);
    expect(end.body.complete).toBe(true);
    const received = await attachment(b, { parts: [0, 1, 2] });
    expect(received.status, received.body.message).toBe(201);
    const downloaded = Buffer.concat(
      received.body.chunks.map((chunk: { data: string }) => Buffer.from(chunk.data, 'base64url')),
    );
    expect(digest(downloaded)).toBe(payload.attachment.cipherHash);
    const decrypted = await crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv: downloaded.subarray(0, 12),
        additionalData: new TextEncoder().encode('SWARM_ATTACHMENT_V1/' + fileId),
      },
      await crypto.subtle.importKey('raw', key, 'AES-GCM', false, ['decrypt']),
      downloaded.subarray(12),
    );
    expect(Buffer.from(decrypted).equals(plain)).toBe(true);
    expect(await db.fieldUpdate.count({ where: { id: fileId } })).toBe(0);
    await sync(b, { blocks: [a.profile.body.id] });
    expect((await attachment(b, { parts: [0] })).status).toBe(403);
    await db.chatAttachment.update({
      where: { id: fileId },
      data: { expiresAt: new Date(Date.now() - 1000) },
    });
    await sync(b, { blocks: [] });
    expect((await attachment(b, { parts: [0] })).status).toBe(400);
    expect(await db.chatAttachmentChunk.count({ where: { attachmentId: fileId } })).toBe(0);
  });
  it('delivers one encrypted online DM over repeated submissions and authenticates recipient confirmations', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      m = await chatMessage(a, b),
      batch = await chatBatch(a, { peers: [b.profile], messages: [m] });
    const first = await request(app.getHttpServer())
      .post('/api/v1/chat/sync')
      .set('Origin', origin)
      .send(batch);
    expect(first.status).toBe(201);
    const again = await request(app.getHttpServer())
      .post('/api/v1/chat/sync')
      .set('Origin', origin)
      .send(batch);
    expect(again.status).toBe(201);
    expect(await db.chatMessage.count({ where: { id: m.body.id } })).toBe(1);
    const received = await sync(b);
    expect(received.messages.map((x: ChatMessage) => x.body.id)).toContain(m.body.id);
    const body = {
      v: 1 as const,
      kind: 'CHAT_RECEIPT' as const,
      messageId: m.body.id,
      conversationId: m.body.conversationId,
      recipient: b.profile,
      status: 'DELIVERED' as const,
      recordedAt: new Date().toISOString(),
      messageHash: await hash(m),
    };
    await sync(b, { receipts: [{ body, signature: await sign(body, b.signing.privateKey) }] });
    const confirmations = await sync(a, {
      knownMessages: [m.body.id],
      receiptMessageIds: [m.body.id],
    });
    expect(confirmations.receipts).toHaveLength(1);
    expect(confirmations.messages).toHaveLength(0);
    expect(
      JSON.stringify(
        (await db.chatMessage.findUniqueOrThrow({ where: { id: m.body.id } })).envelope,
      ),
    ).not.toContain('Gate 2');
  });
  it('rejects forged requests and nonce reuse with different content', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      batch = await chatBatch(a);
    expect(
      (
        await request(app.getHttpServer())
          .post('/api/v1/chat/sync')
          .set('Origin', origin)
          .send({ ...batch, signature: await sign(batch.body, b.signing.privateKey) })
      ).status,
    ).toBe(403);
    await request(app.getHttpServer()).post('/api/v1/chat/sync').set('Origin', origin).send(batch);
    const body = { ...batch.body, blocks: [b.profile.body.id] };
    expect(
      (
        await request(app.getHttpServer())
          .post('/api/v1/chat/sync')
          .set('Origin', origin)
          .send({ body, signature: await sign(body, a.signing.privateKey) })
      ).status,
    ).toBe(409);
  });
  it('does not expose private DMs to another signed participant', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      c = await chatPerson('Carrier'),
      m = await chatMessage(a, b);
    await sync(a, { peers: [b.profile], messages: [m] });
    expect((await sync(c)).messages).toEqual([]);
    expect(
      (
        await request(app.getHttpServer())
          .post('/api/v1/chat/sync')
          .set('Origin', origin)
          .send(await chatBatch(c, { messages: [m] }))
      ).status,
    ).toBe(403);
  });
  it('queues an open join for its owner and keeps membership roles separate from relief roles', async () => {
    const a = await chatPerson('Owner'),
      b = await chatPerson('Participant'),
      p = await chatPolicy(a, [a]);
    await sync(a, { policies: [p.policy] });
    const body = {
        v: 1 as const,
        kind: 'CHAT_JOIN' as const,
        id: randomUUID(),
        channelId: p.policy.body.id,
        participant: b.profile,
        action: 'JOIN' as const,
        issuedAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 3600000).toISOString(),
      },
      join = { body, signature: await sign(body, b.signing.privateKey) };
    await sync(b, { joins: [join] });
    expect((await sync(a)).joins).toHaveLength(1);
    expect((await sync(b)).policies).toEqual([]);
    const next = await chatPolicy(a, [a, b], 'OPEN', p.policy);
    await sync(a, { policies: [next.policy] });
    expect((await sync(b)).policies[0].body.id).toBe(p.policy.body.id);
    expect(await db.user.count({ where: { id: b.profile.body.id } })).toBe(0);
    expect(
      (
        await request(app.getHttpServer())
          .post('/api/v1/volunteer/requests')
          .set('Origin', origin)
          .send({ chatProfile: b.profile })
      ).status,
    ).toBe(401);
  });
  it('deduplicates a three-participant channel relay and supplies authorized late history', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      c = await chatPerson('C'),
      p = await chatPolicy(a, [a, b, c]),
      m = await chatMessage(a, p);
    await sync(a, { policies: [p.policy], messages: [m] });
    await sync(b, { messages: [m] });
    const late = await sync(c);
    expect(late.messages.map((x: ChatMessage) => x.body.id)).toEqual([m.body.id]);
    expect(late.historyPolicies).toHaveLength(1);
    expect(await db.chatMessage.count({ where: { id: m.body.id } })).toBe(1);
  });
  it('rejects nonmember messages and preserves removal against stale offline policy', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      c = await chatPerson('Outsider'),
      p = await chatPolicy(a, [a, b]);
    await sync(a, { policies: [p.policy] });
    const forged = await chatMessage(c, p);
    expect((await sync(c, { messages: [forged] })).rejected[0].reason).toBe(
      'MEMBERSHIP_OR_SIGNATURE',
    );
    const removed = await chatPolicy(a, [a], 'OPEN', p.policy);
    await sync(a, { policies: [removed.policy] });
    const stale = await chatMessage(b, p);
    const result = await sync(b, { policies: [p.policy], messages: [stale] });
    expect(result.rejected).toHaveLength(1);
    expect(result.removed).toContain(p.policy.body.id);
    expect(result.messages).toHaveLength(0);
    expect(
      (await db.chatConversation.findUniqueOrThrow({ where: { id: p.policy.body.id } })).version,
    ).toBe(2);
  });
  it('requires authenticated recipient membership for invite-only channels and does not permit open joining', async () => {
    const a = await chatPerson('Owner'),
      b = await chatPerson('Invitee'),
      c = await chatPerson('Outsider'),
      p = await chatPolicy(a, [a, b], 'INVITE'),
      m = await chatMessage(a, p);
    await sync(a, { policies: [p.policy], messages: [m] });
    expect((await sync(b)).messages).toHaveLength(1);
    expect((await sync(c)).messages).toEqual([]);
    const body = {
      v: 1 as const,
      kind: 'CHAT_JOIN' as const,
      id: randomUUID(),
      channelId: p.policy.body.id,
      participant: c.profile,
      action: 'JOIN' as const,
      issuedAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 3600000).toISOString(),
    };
    expect(
      (
        await request(app.getHttpServer())
          .post('/api/v1/chat/sync')
          .set('Origin', origin)
          .send(
            await chatBatch(c, {
              joins: [{ body, signature: await sign(body, c.signing.privateKey) }],
            }),
          )
      ).status,
    ).toBe(403);
  });
  it('keeps leave effective while the owner is absent and rejects channel resurrection', async () => {
    const a = await chatPerson('Owner'),
      b = await chatPerson('Leaving'),
      p = await chatPolicy(a, [a, b]);
    await sync(a, { policies: [p.policy] });
    const body = {
      v: 1 as const,
      kind: 'CHAT_JOIN' as const,
      id: randomUUID(),
      channelId: p.policy.body.id,
      participant: b.profile,
      action: 'LEAVE' as const,
      issuedAt: new Date().toISOString(),
      expiresAt: new Date(Date.now() + 3600000).toISOString(),
    };
    await sync(b, { joins: [{ body, signature: await sign(body, b.signing.privateKey) }] });
    await sync(a, { policies: [p.policy] });
    await sync(a, { policies: [p.policy] });
    expect((await sync(b)).removed).toContain(p.policy.body.id);
    const deletedBody = { ...p.policy.body, version: 2, deleted: true },
      deleted = { body: deletedBody, signature: await sign(deletedBody, a.signing.privateKey) };
    await sync(a, { policies: [deleted] });
    const resurrect = { ...p.policy.body, version: 3 };
    await sync(a, {
      policies: [{ body: resurrect, signature: await sign(resurrect, a.signing.privateKey) }],
    });
    expect(
      (await db.chatConversation.findUniqueOrThrow({ where: { id: p.policy.body.id } })).deleted,
    ).toBe(true);
  });
  it('honors blocks in both directions and records bounded member reports', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      m = await chatMessage(a, b);
    await sync(a, { peers: [b.profile], messages: [m] });
    const report = { id: randomUUID(), messageId: m.body.id, reason: 'ABUSE' as const };
    await sync(b, { reports: [report], blocks: [a.profile.body.id] });
    expect(await db.chatReport.count({ where: { id: report.id } })).toBe(1);
    const another = await chatMessage(a, b);
    expect((await sync(a, { messages: [another] })).rejected[0].reason).toBe('BLOCKED');
    await sync(b, { blocks: [], reports: [report] });
    expect(await db.chatReport.count({ where: { id: report.id } })).toBe(1);
  });
  it('mounts no chat writes on the public API plane', async () => {
    const publicApp = await createApp('public');
    await publicApp.init();
    try {
      expect(
        (
          await request(publicApp.getHttpServer())
            .post('/api/v1/chat/sync')
            .set('Origin', origin)
            .send(await chatBatch(await chatPerson('Guest')))
        ).status,
      ).toBe(404);
    } finally {
      await publicApp.close();
    }
  });
});
