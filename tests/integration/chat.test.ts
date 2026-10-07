import { beforeAll, afterAll, describe, it, expect } from 'vitest';
import request from 'supertest';
import { randomUUID, createHash } from 'node:crypto';
import { createApp } from '../../apps/api/src/app';
import { Database } from '../../apps/api/src/database';
import { env } from '../../packages/config/src';
import {
  chatPerson,
  chatPolicy,
  chatMessage,
  chatBatch,
  channelAction,
  type Person,
} from '../chat-fixtures';
import {
  sign,
  hash,
  encryptChatValue,
  channelCapabilities,
  type ChatMessage,
} from '../../packages/protocol/src';
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
  it('synchronizes a 200-person private roster and pages unchanged policies by signed hash', async () => {
    const people = await Promise.all(
      Array.from({ length: 200 }, (_, i) => chatPerson(`Large member ${i}`)),
    );
    const owner = people[0]!,
      recipient = people[199]!;
    const channel = await chatPolicy(owner, people, 'INVITE');
    const digest = await hash(channel.policy);
    expect((await sync(owner, { policies: [channel.policy] })).acceptedPolicies).toContain(digest);
    const message = await chatMessage(owner, channel, 'A message for the whole offline group');
    await sync(owner, { messages: [message] });
    const first = await sync(recipient);
    expect(first.capabilities).toEqual({ policyPaging: true, channelMembers: 200 });
    expect(
      first.policies.some((p: { body: { id: string } }) => p.body.id === channel.policy.body.id),
    ).toBe(true);
    expect(
      first.messages.some((m: { body: { id: string } }) => m.body.id === message.body.id),
    ).toBe(true);
    const second = await sync(recipient, {
      knownPolicyHashes: [digest],
      knownMessages: [message.body.id],
    });
    expect(second.policies).toEqual([]);
    expect(second.historyPolicies).toEqual([]);
    expect(Buffer.byteLength(JSON.stringify(first))).toBeLessThan(1500000);
    const outsider = await chatPerson('Large outsider');
    const denied = await sync(outsider, { channelIds: [channel.policy.body.id] });
    expect(denied.messages).toEqual([]);
    expect(denied.policies).toEqual([]);
  }, 30000);
  it('keeps person reports generic, acknowledges queued reports, and delegates private report review without plaintext', async () => {
    const owner = await chatPerson('Report owner'),
      mod = await chatPerson('Report moderator'),
      member = await chatPerson('Report member'),
      channel = await chatPolicy(owner, [owner, mod, member], 'INVITE');
    channel.policy.body.members[1]!.role = 'MODERATOR';
    channel.policy.signature = await sign(channel.policy.body, owner.signing.privateKey);
    await sync(owner, { policies: [channel.policy] });
    const message = await chatMessage(member, channel, 'Private reported message');
    await sync(member, { messages: [message] });
    const report = { id: randomUUID(), messageId: message.body.id, reason: 'OTHER' as const };
    expect((await sync(owner, { reports: [report] })).acceptedReports).toContain(report.id);
    expect((await sync(member)).reports).toEqual([]);
    const inbox = (await sync(mod)).reports;
    expect(inbox.find((r: { id: string }) => r.id === report.id).messageId).toBe(message.body.id);
    expect(JSON.stringify(inbox)).not.toContain('Private reported message');
    const review = await channelAction(mod, channel.policy, 'REVIEW_REPORT', message.body.id);
    expect((await sync(mod, { actions: [review] })).acceptedActions).toContain(review.body.id);
    expect((await sync(mod)).reports).toEqual([]);
    const person = { id: randomUUID(), personId: member.profile.body.id, reason: 'OTHER' as const };
    expect((await sync(owner, { reports: [person] })).acceptedReports).toContain(person.id);
    await sync(owner, { reports: [person] });
    expect(
      await db.moderationReport.count({ where: { id: person.id, entityType: 'CHAT_USER' } }),
    ).toBe(1);
    expect(await db.user.count({ where: { id: member.profile.body.id } })).toBe(0);
  });
  it('distinguishes creating a thread from replying and rejects opposing offline locks on one policy', async () => {
    const owner = await chatPerson('Thread owner'),
      member = await chatPerson('Reply only'),
      mod = await chatPerson('Thread moderator'),
      channel = await chatPolicy(owner, [owner, member, mod]);
    channel.policy.body.members[2]!.role = 'MODERATOR';
    channel.policy.body.settings = {
      mode: 'DISCUSSION',
      admission: 'OPEN',
      capabilities: {
        MEMBER: {
          ...channelCapabilities(channel.policy, member.profile.body.id),
          canCreateThreads: false,
        },
      },
    };
    channel.policy.signature = await sign(channel.policy.body, owner.signing.privateKey);
    await sync(owner, { policies: [channel.policy] });
    const root = await chatMessage(owner, channel);
    await sync(owner, { messages: [root] });
    const first = await chatMessage(member, channel);
    first.body.threadRootId = root.body.id;
    first.signature = await sign(first.body, member.signing.privateKey);
    expect((await sync(member, { messages: [first] })).rejected[0].reason).toBe(
      'THREAD_CREATION_NOT_PERMITTED',
    );
    const start = await chatMessage(owner, channel);
    start.body.threadRootId = root.body.id;
    start.signature = await sign(start.body, owner.signing.privateKey);
    await sync(owner, { messages: [start] });
    const reply = await chatMessage(member, channel);
    reply.body.threadRootId = root.body.id;
    reply.signature = await sign(reply.body, member.signing.privateKey);
    expect((await sync(member, { messages: [reply] })).accepted).toContain(reply.body.id);
    const lock = await channelAction(mod, channel.policy, 'LOCK_THREAD', root.body.id);
    await sync(mod, { actions: [lock] });
    const opposing = await channelAction(owner, channel.policy, 'UNLOCK_THREAD', root.body.id);
    expect((await sync(owner, { actions: [opposing] })).rejectedActions).toHaveLength(1);
  });
  it('enforces announcement/read-only roles, separate replies, thread locks and stale moderation on raw requests', async () => {
    const owner = await chatPerson('Announcement owner'),
      admin = await chatPerson('Admin'),
      mod = await chatPerson('Moderator'),
      member = await chatPerson('Member'),
      reader = await chatPerson('Reader');
    const channel = await chatPolicy(owner, [owner, admin, mod, member, reader]);
    channel.policy.body.settings = { mode: 'ANNOUNCEMENT', admission: 'OPEN' };
    channel.policy.body.members[1]!.role = 'ADMIN';
    channel.policy.body.members[2]!.role = 'MODERATOR';
    channel.policy.body.members[4]!.role = 'READ_ONLY';
    channel.policy.signature = await sign(channel.policy.body, owner.signing.privateKey);
    await sync(owner, { policies: [channel.policy] });
    const root = await chatMessage(admin, channel, 'Distribution at Gate 4');
    expect((await sync(admin, { messages: [root] })).accepted).toContain(root.body.id);
    const forgedTop = await chatMessage(member, channel);
    expect((await sync(member, { messages: [forgedTop] })).rejected[0].reason).toBe(
      'MEMBERSHIP_OR_SIGNATURE',
    );
    const reply = await chatMessage(member, channel);
    reply.body.threadRootId = root.body.id;
    reply.signature = await sign(reply.body, member.signing.privateKey);
    expect((await sync(member, { messages: [reply] })).accepted).toContain(reply.body.id);
    expect((await sync(member, { messages: [reply] })).accepted).toContain(reply.body.id);
    expect(await db.chatMessage.count({ where: { id: reply.body.id } })).toBe(1);
    const readOnly = await chatMessage(reader, channel);
    readOnly.body.threadRootId = root.body.id;
    readOnly.signature = await sign(readOnly.body, reader.signing.privateKey);
    expect((await sync(reader, { messages: [readOnly] })).rejected).toHaveLength(1);
    const lock = await channelAction(mod, channel.policy, 'LOCK_THREAD', root.body.id);
    expect((await sync(mod, { actions: [lock] })).acceptedActions).toContain(lock.body.id);
    const blockedReply = await chatMessage(member, channel);
    blockedReply.body.threadRootId = root.body.id;
    blockedReply.signature = await sign(blockedReply.body, member.signing.privateKey);
    expect((await sync(member, { messages: [blockedReply] })).rejected[0].reason).toBe(
      'THREAD_UNAVAILABLE_OR_LOCKED',
    );
    const next = await chatPolicy(
      owner,
      [owner, admin, mod, member, reader],
      'OPEN',
      channel.policy,
    );
    next.policy.body.settings = channel.policy.body.settings;
    next.policy.body.members[1]!.role = 'ADMIN';
    next.policy.body.members[2]!.role = 'MEMBER';
    next.policy.body.members[4]!.role = 'READ_ONLY';
    next.policy.body.appliedActions = [lock.body.id];
    next.policy.body.moderation = { lockedThreads: [root.body.id], hiddenMessages: [] };
    next.policy.signature = await sign(next.policy.body, owner.signing.privateKey);
    await sync(owner, { policies: [next.policy] });
    const stale = await channelAction(mod, channel.policy, 'UNLOCK_THREAD', root.body.id);
    expect((await sync(mod, { actions: [stale] })).rejectedActions).toHaveLength(1);
    const demoted = await channelAction(mod, next.policy, 'UNLOCK_THREAD', root.body.id);
    expect((await sync(mod, { actions: [demoted] })).rejectedActions).toHaveLength(1);
  });
  it('accepts a join-link request into review without failing the sync or sharing the channel', async () => {
    const owner = await chatPerson('Link owner'),
      visitor = await chatPerson('Link visitor');
    const channel = await chatPolicy(owner, [owner], 'INVITE');
    channel.policy.body.settings = { mode: 'DISCUSSION', admission: 'INVITE_AUTO' };
    channel.policy.signature = await sign(channel.policy.body, owner.signing.privateKey);
    await sync(owner, { policies: [channel.policy] });
    // Android join links always require approval, even when invitations normally admit at once.
    const descriptor = {
      v: 1 as const,
      kind: 'CHAT_ADMISSION' as const,
      id: randomUUID(),
      channelId: channel.policy.body.id,
      name: channel.policy.body.name,
      owner: owner.profile,
      issuer: owner.profile,
      recipientId: '*',
      policyHash: await hash(channel.policy),
      admission: 'INVITE_PLUS_APPROVAL' as const,
      issuedAt: new Date().toISOString(),
      expiresAt: channel.policy.body.expiresAt,
    };
    const invitation = {
      body: descriptor,
      signature: await sign(descriptor, owner.signing.privateKey),
    };
    const joinBody = {
      v: 1 as const,
      kind: 'CHAT_JOIN' as const,
      id: randomUUID(),
      channelId: channel.policy.body.id,
      participant: visitor.profile,
      action: 'JOIN' as const,
      invitation,
      issuedAt: new Date().toISOString(),
      expiresAt: channel.policy.body.expiresAt,
    };
    const join = { body: joinBody, signature: await sign(joinBody, visitor.signing.privateKey) };
    visitor.channels.add(channel.policy.body.id);
    const pending = await sync(visitor, { joins: [join] });
    expect(pending.policies).toEqual([]);
    expect(pending.messages).toEqual([]);
    expect((await sync(owner)).joins).toHaveLength(1);
  });
  it('gates private admission, delegated approval and bans, pauses old epochs, and keeps rename-bound bans', async () => {
    const owner = await chatPerson('Private owner'),
      admin = await chatPerson('Private admin'),
      member = await chatPerson('Applicant');
    const channel = await chatPolicy(owner, [owner, admin], 'INVITE');
    channel.policy.body.settings = { mode: 'DISCUSSION', admission: 'INVITE_PLUS_APPROVAL' };
    channel.policy.body.members[1]!.role = 'ADMIN';
    channel.policy.signature = await sign(channel.policy.body, owner.signing.privateKey);
    await sync(owner, { policies: [channel.policy] });
    const descriptor = {
      v: 1 as const,
      kind: 'CHAT_ADMISSION' as const,
      id: randomUUID(),
      channelId: channel.policy.body.id,
      name: channel.policy.body.name,
      owner: owner.profile,
      issuer: admin.profile,
      recipientId: member.profile.body.id,
      policyHash: await hash(channel.policy),
      admission: 'INVITE_PLUS_APPROVAL' as const,
      issuedAt: new Date().toISOString(),
      expiresAt: channel.policy.body.expiresAt,
    };
    const invitation = {
      body: descriptor,
      signature: await sign(descriptor, admin.signing.privateKey),
    };
    const joinBody = {
      v: 1 as const,
      kind: 'CHAT_JOIN' as const,
      id: randomUUID(),
      channelId: channel.policy.body.id,
      participant: member.profile,
      action: 'JOIN' as const,
      invitation,
      issuedAt: new Date().toISOString(),
      expiresAt: channel.policy.body.expiresAt,
    };
    const join = { body: joinBody, signature: await sign(joinBody, member.signing.privateKey) };
    member.channels.add(channel.policy.body.id);
    const pending = await sync(member, { joins: [join] });
    expect(pending.messages).toEqual([]);
    expect(pending.policies).toEqual([]);
    expect((await sync(admin)).joins).toHaveLength(1);
    const approve = await channelAction(
      admin,
      channel.policy,
      'APPROVE_JOIN',
      member.profile.body.id,
    );
    expect((await sync(admin, { actions: [approve] })).acceptedActions).toContain(approve.body.id);
    const approved = await chatPolicy(owner, [owner, admin, member], 'INVITE', channel.policy);
    approved.policy.body.settings = channel.policy.body.settings;
    approved.policy.body.members[1]!.role = 'ADMIN';
    approved.policy.body.appliedActions = [approve.body.id];
    approved.policy.signature = await sign(approved.policy.body, owner.signing.privateKey);
    await sync(owner, { policies: [approved.policy] });
    expect((await sync(member)).policies).toHaveLength(1);
    const privateMessage = await chatMessage(member, approved, 'Private admission worked');
    expect((await sync(member, { messages: [privateMessage] })).accepted).toContain(
      privateMessage.body.id,
    );
    expect(
      JSON.stringify(
        (await db.chatMessage.findUniqueOrThrow({ where: { id: privateMessage.body.id } }))
          .envelope,
      ),
    ).not.toContain('Private admission worked');
    const ban = await channelAction(admin, approved.policy, 'BAN', member.profile.body.id);
    expect((await sync(admin, { actions: [ban] })).acceptedActions).toContain(ban.body.id);
    expect(
      (await sync(owner, { messages: [await chatMessage(owner, approved)] })).rejected[0].reason,
    ).toBe('WAITING_FOR_FRESH_MEMBERSHIP');
    const removed = await chatPolicy(owner, [owner, admin], 'INVITE', approved.policy);
    removed.policy.body.settings = approved.policy.body.settings;
    removed.policy.body.members[1]!.role = 'ADMIN';
    removed.policy.body.appliedActions = [approve.body.id, ban.body.id];
    removed.policy.body.bannedIds = [member.profile.body.id];
    removed.policy.signature = await sign(removed.policy.body, owner.signing.privateKey);
    await sync(owner, { policies: [removed.policy] });
    expect(removed.policy.body.keys.some((k) => k.participantId === member.profile.body.id)).toBe(
      false,
    );
    const renamed = {
      ...member.profile.body,
      name: 'Different visible name',
      updatedAt: new Date().toISOString(),
    };
    member.profile = { body: renamed, signature: await sign(renamed, member.signing.privateKey) };
    const retryBody = {
      ...joinBody,
      id: randomUUID(),
      participant: member.profile,
      issuedAt: new Date().toISOString(),
    };
    const response = await request(app.getHttpServer())
      .post('/api/v1/chat/sync')
      .set('Origin', origin)
      .send(
        await chatBatch(member, {
          joins: [{ body: retryBody, signature: await sign(retryBody, member.signing.privateKey) }],
        }),
      );
    expect(response.status).toBe(403);
    const unban = await channelAction(owner, removed.policy, 'UNBAN', member.profile.body.id);
    expect((await sync(owner, { actions: [unban] })).acceptedActions).toContain(unban.body.id);
    const fresh = await chatPolicy(owner, [owner, admin], 'INVITE', removed.policy);
    fresh.policy.body.settings = removed.policy.body.settings;
    fresh.policy.body.members[1]!.role = 'ADMIN';
    fresh.policy.body.bannedIds = [];
    fresh.policy.body.appliedActions = [approve.body.id, ban.body.id, unban.body.id];
    fresh.policy.signature = await sign(fresh.policy.body, owner.signing.privateKey);
    await sync(owner, { policies: [fresh.policy] });
  });
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
