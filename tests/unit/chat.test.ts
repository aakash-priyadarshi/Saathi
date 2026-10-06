import { describe, it, expect } from 'vitest';
import { randomUUID } from 'node:crypto';
import { chatPerson, chatPolicy, chatMessage } from '../chat-fixtures';
import {
  directConversationId,
  validChatProfile,
  validChatMessage,
  validChatInvite,
  validChannelPolicy,
  validStoredChatMessage,
  validChatReceipt,
  sign,
  hash,
  decryptChatValue,
} from '../../packages/protocol/src';
describe('Swarm chat identities, policies and established encryption', () => {
  it('keeps a DM identity unchanged when order and route change', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B');
    expect(await directConversationId(a.profile.body.id, b.profile.body.id)).toBe(
      await directConversationId(b.profile.body.id, a.profile.body.id),
    );
    await expect(directConversationId(a.profile.body.id, a.profile.body.id)).rejects.toThrow();
  });
  it('authenticates profile names and rejects invalid encryption points', async () => {
    const a = await chatPerson('A');
    await expect(validChatProfile(a.profile)).resolves.toEqual(a.profile);
    await expect(
      validChatProfile({ ...a.profile, body: { ...a.profile.body, name: 'Impersonator' } }),
    ).rejects.toThrow();
    const body = {
      ...a.profile.body,
      encryptionKey: { ...a.profile.body.encryptionKey, x: 'A'.repeat(43), y: 'A'.repeat(43) },
    };
    await expect(
      validChatProfile({ body, signature: await sign(body, a.signing.privateKey) }),
    ).rejects.toThrow();
  });
  it('encrypts a DM for only its recipient and binds its conversation/message context', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      c = await chatPerson('C'),
      message = await chatMessage(a, b),
      kid = 'dm:' + message.body.conversationId + ':' + message.body.id + ':' + b.profile.body.id;
    const key = await crypto.subtle.exportKey('jwk', b.ecdh.privateKey);
    expect(message.body.content).not.toContain('Gate');
    await expect(validChatMessage(message)).resolves.toEqual(message);
    await expect(decryptChatValue(message.body.content, key, kid)).resolves.toEqual({
      text: 'Where is Gate 2?',
    });
    await expect(decryptChatValue(message.body.content, key, kid + 'wrong')).rejects.toThrow();
    await expect(
      decryptChatValue(
        message.body.content,
        await crypto.subtle.exportKey('jwk', c.ecdh.privateKey),
        kid,
      ),
    ).rejects.toThrow();
    const parts = message.body.content.split('.');
    parts[3] = parts[3]!.slice(0, -2) + 'AA';
    await expect(decryptChatValue(parts.join('.'), key, kid)).rejects.toThrow();
  });
  it('rejects plaintext DMs even when signed by the author', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      m = await chatMessage(a, b),
      body = { ...m.body, encrypted: false, content: 'cleartext' };
    await expect(
      validChatMessage({ body, signature: await sign(body, a.signing.privateKey) }),
    ).rejects.toThrow();
  });
  it('wraps private channel keys for exactly the current roster', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      channel = await chatPolicy(a, [a, b], 'INVITE');
    await expect(validChannelPolicy(channel.policy)).resolves.toEqual(channel.policy);
    const body = { ...channel.policy.body, keys: channel.policy.body.keys.slice(0, 1) };
    await expect(
      validChannelPolicy({ body, signature: await sign(body, a.signing.privateKey) }),
    ).rejects.toThrow();
  });
  it('rejects nonmember and stale-epoch channel messages', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      c = await chatPerson('C'),
      p = await chatPolicy(a, [a, b]),
      m = await chatMessage(b, p),
      revoked = await chatPolicy(a, [a], 'OPEN', p.policy);
    await expect(validChatMessage(m, p.policy)).resolves.toEqual(m);
    await expect(validChatMessage(await chatMessage(c, p), p.policy)).rejects.toThrow();
    await expect(validChatMessage(m, revoked.policy)).rejects.toThrow();
  });
  it('validates old authorized history without treating an expired policy as live authority', async () => {
    const a = await chatPerson('A'),
      p = await chatPolicy(a, [a]),
      m = await chatMessage(a, p),
      future = Date.now() + 7200000;
    await expect(validChatMessage(m, p.policy, future)).rejects.toThrow();
    await expect(validStoredChatMessage(m, p.policy, future)).resolves.toEqual(m);
    await expect(validStoredChatMessage(m, p.policy, Date.now() + 8 * 86400000)).rejects.toThrow();
  });
  it('binds invitations to a recipient, signature and bounded expiry; replay is idempotent input', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      c = await chatPerson('C'),
      { policy } = await chatPolicy(a, [a, b], 'INVITE');
    const body = {
        v: 1 as const,
        kind: 'CHAT_INVITE' as const,
        id: randomUUID(),
        policy,
        recipientId: b.profile.body.id,
        issuedAt: new Date().toISOString(),
        expiresAt: policy.body.expiresAt,
      },
      invite = { body, signature: await sign(body, a.signing.privateKey) };
    await expect(validChatInvite(invite, b.profile.body.id)).resolves.toEqual(invite);
    await expect(validChatInvite(invite, b.profile.body.id)).resolves.toEqual(invite);
    await expect(validChatInvite(invite, c.profile.body.id)).rejects.toThrow();
    await expect(
      validChatInvite(
        { ...invite, signature: await sign(body, c.signing.privateKey) },
        b.profile.body.id,
      ),
    ).rejects.toThrow();
    await expect(
      validChatInvite(invite, b.profile.body.id, Date.now() + 7 * 3600000),
    ).rejects.toThrow();
  });
  it('only accepts a recipient-signed confirmation of the original message hash', async () => {
    const a = await chatPerson('A'),
      b = await chatPerson('B'),
      c = await chatPerson('Carrier'),
      m = await chatMessage(a, b);
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
    await expect(
      validChatReceipt({ body, signature: await sign(body, b.signing.privateKey) }, m),
    ).resolves.toBeTruthy();
    const forged = { ...body, recipient: c.profile };
    await expect(
      validChatReceipt({ body: forged, signature: await sign(forged, c.signing.privateKey) }, m),
    ).rejects.toThrow();
    const other = { ...body, messageHash: '0'.repeat(64) };
    await expect(
      validChatReceipt({ body: other, signature: await sign(other, b.signing.privateKey) }, m),
    ).rejects.toThrow();
  });
});
