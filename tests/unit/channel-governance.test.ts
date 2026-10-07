import { describe, it, expect } from 'vitest';
import { randomUUID } from 'node:crypto';
import { chatPerson, chatPolicy, chatMessage } from '../chat-fixtures';
import {
  sign,
  hash,
  channelCapabilities,
  validChannelPolicy,
  validChatMessage,
  validChatAction,
  validChatAdmission,
  validChatJoin,
  type ChatAction,
} from '../../packages/protocol/src';

describe('Signed channel capability boundaries', () => {
  it('denies every capability and fresh key material when an explicit read capability is removed', async () => {
    const owner = await chatPerson('Owner'),
      member = await chatPerson('Restricted member'),
      { policy } = await chatPolicy(owner, [owner, member], 'INVITE');
    policy.body.settings = {
      mode: 'DISCUSSION',
      admission: 'INVITE_AUTO',
      capabilities: {
        MEMBER: { ...channelCapabilities(policy, member.profile.body.id), canRead: false },
      },
    };
    policy.signature = await sign(policy.body, owner.signing.privateKey);
    expect(
      Object.values(channelCapabilities(policy, member.profile.body.id)).every((v) => !v),
    ).toBe(true);
    await expect(validChannelPolicy(policy)).rejects.toThrow();
    policy.body.keys = policy.body.keys.filter((k) => k.participantId === owner.profile.body.id);
    policy.signature = await sign(policy.body, owner.signing.privateKey);
    await expect(validChannelPolicy(policy)).resolves.toBeDefined();
  });
  it('rejects raw member announcements, permits replies and rejects read-only replies', async () => {
    const owner = await chatPerson('Owner'),
      member = await chatPerson('Member'),
      admin = await chatPerson('Admin');
    const channel = await chatPolicy(owner, [owner, member, admin]);
    channel.policy.body.settings = { mode: 'ANNOUNCEMENT', admission: 'APPROVAL_ONLY' };
    channel.policy.body.members[2]!.role = 'ADMIN';
    channel.policy.signature = await sign(channel.policy.body, owner.signing.privateKey);
    await expect(
      validChatMessage(await chatMessage(member, channel), channel.policy),
    ).rejects.toThrow('posting capability');
    await expect(
      validChatMessage(await chatMessage(admin, channel), channel.policy),
    ).resolves.toBeDefined();
    const reply = await chatMessage(member, channel);
    reply.body.threadRootId = randomUUID();
    reply.signature = await sign(reply.body, member.signing.privateKey);
    await expect(validChatMessage(reply, channel.policy)).resolves.toBeDefined();
    channel.policy.body.members[1]!.role = 'READ_ONLY';
    channel.policy.signature = await sign(channel.policy.body, owner.signing.privateKey);
    const readOnly = await chatMessage(member, channel);
    readOnly.body.threadRootId = randomUUID();
    readOnly.signature = await sign(readOnly.body, member.signing.privateKey);
    await expect(validChatMessage(readOnly, channel.policy)).rejects.toThrow('posting capability');
  });
  it('enforces capability ceilings even with a maliciously broad member override', async () => {
    const owner = await chatPerson('Owner'),
      member = await chatPerson('Member'),
      channel = await chatPolicy(owner, [owner, member]);
    channel.policy.body.settings = {
      mode: 'ANNOUNCEMENT',
      admission: 'OPEN',
      capabilities: {
        MEMBER: Object.fromEntries(
          Object.keys(channelCapabilities(channel.policy, owner.profile.body.id)).map((k) => [
            k,
            true,
          ]),
        ) as ReturnType<typeof channelCapabilities>,
      },
    };
    expect(channelCapabilities(channel.policy, member.profile.body.id).canManageMembers).toBe(
      false,
    );
    expect(channelCapabilities(channel.policy, member.profile.body.id).canPostTopLevel).toBe(false);
    expect(channelCapabilities(channel.policy, member.profile.body.id).canStartCalls).toBe(false);
  });
  it('authenticates delegated moderation, rejects stale/demoted actions and protects owners', async () => {
    const owner = await chatPerson('Owner'),
      mod = await chatPerson('Moderator'),
      member = await chatPerson('Member');
    const { policy } = await chatPolicy(owner, [owner, mod, member]);
    policy.body.members[1]!.role = 'MODERATOR';
    policy.signature = await sign(policy.body, owner.signing.privateKey);
    const body: ChatAction['body'] = {
      v: 1,
      kind: 'CHAT_ACTION',
      id: randomUUID(),
      channelId: policy.body.id,
      actor: mod.profile,
      policyHash: await hash(policy),
      version: policy.body.version,
      action: 'LOCK_THREAD',
      targetId: randomUUID(),
      issuedAt: new Date().toISOString(),
      expiresAt: policy.body.expiresAt,
    };
    const action = { body, signature: await sign(body, mod.signing.privateKey) };
    await expect(validChatAction(action, policy)).resolves.toEqual(action);
    body.action = 'BAN';
    body.targetId = member.profile.body.id;
    action.signature = await sign(body, mod.signing.privateKey);
    await expect(validChatAction(action, policy)).rejects.toThrow('not permitted');
    policy.body.members[1]!.role = 'ADMIN';
    policy.signature = await sign(policy.body, owner.signing.privateKey);
    body.policyHash = await hash(policy);
    action.signature = await sign(body, mod.signing.privateKey);
    await expect(validChatAction(action, policy)).resolves.toBeDefined();
    body.targetId = owner.profile.body.id;
    action.signature = await sign(body, mod.signing.privateKey);
    await expect(validChatAction(action, policy)).rejects.toThrow('Role authority');
    policy.body.version++;
    policy.body.members[1]!.role = 'MEMBER';
    policy.signature = await sign(policy.body, owner.signing.privateKey);
    await expect(validChatAction(action, policy)).rejects.toThrow();
  });
  it('approval invitations disclose no roster/keys and retain recipient, expiry and signature binding', async () => {
    const owner = await chatPerson('Owner'),
      member = await chatPerson('Member'),
      stranger = await chatPerson('Stranger'),
      { policy } = await chatPolicy(owner, [owner], 'INVITE');
    const body = {
      v: 1 as const,
      kind: 'CHAT_ADMISSION' as const,
      id: randomUUID(),
      channelId: policy.body.id,
      name: policy.body.name,
      owner: owner.profile,
      recipientId: member.profile.body.id,
      policyHash: await hash(policy),
      admission: 'INVITE_PLUS_APPROVAL' as const,
      issuedAt: new Date().toISOString(),
      expiresAt: policy.body.expiresAt,
    };
    const invite = { body, signature: await sign(body, owner.signing.privateKey) };
    expect(JSON.stringify(invite)).not.toMatch(/"members"|"keys"|"jwe"/);
    await expect(validChatAdmission(invite, member.profile.body.id)).resolves.toEqual(invite);
    await expect(validChatAdmission(invite, stranger.profile.body.id)).rejects.toThrow();
    await expect(
      validChatAdmission(
        { ...invite, signature: await sign(body, stranger.signing.privateKey) },
        member.profile.body.id,
      ),
    ).rejects.toThrow();
    await expect(
      validChatAdmission(invite, member.profile.body.id, Date.now() + 8 * 3600000),
    ).rejects.toThrow();
  });
  it('join links admit any signed requester for approval, never an unsigned or forged one', async () => {
    const owner = await chatPerson('Owner'),
      visitor = await chatPerson('Visitor'),
      { policy } = await chatPolicy(owner, [owner], 'INVITE');
    const body = {
      v: 1 as const,
      kind: 'CHAT_ADMISSION' as const,
      id: randomUUID(),
      channelId: policy.body.id,
      name: policy.body.name,
      owner: owner.profile,
      recipientId: '*',
      policyHash: await hash(policy),
      admission: 'INVITE_PLUS_APPROVAL' as const,
      issuedAt: new Date().toISOString(),
      expiresAt: policy.body.expiresAt,
    };
    const link = { body, signature: await sign(body, owner.signing.privateKey) };
    await expect(validChatAdmission(link, visitor.profile.body.id)).resolves.toEqual(link);
    await expect(
      validChatAdmission(
        { ...link, signature: await sign(body, visitor.signing.privateKey) },
        visitor.profile.body.id,
      ),
    ).rejects.toThrow();
    const now = new Date();
    const joinBody = {
      v: 1 as const,
      kind: 'CHAT_JOIN' as const,
      id: randomUUID(),
      channelId: policy.body.id,
      participant: visitor.profile,
      action: 'JOIN' as const,
      invitation: link,
      issuedAt: now.toISOString(),
      expiresAt: new Date(now.getTime() + 3600000).toISOString(),
    };
    const join = { body: joinBody, signature: await sign(joinBody, visitor.signing.privateKey) };
    await expect(validChatJoin(join)).resolves.toEqual(join);
    await expect(
      validChatJoin({ ...join, signature: await sign(joinBody, owner.signing.privateKey) }),
    ).rejects.toThrow();
  });
});
