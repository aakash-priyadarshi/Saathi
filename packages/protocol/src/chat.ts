import { z } from 'zod';
import { bytes, hash, publicKeySchema, verify } from './crypto';
import { validChatJweHeader } from './chat-encryption';

export const participantIdSchema = z.string().regex(/^[a-f0-9]{64}$/);
const signature = z.string().regex(/^[A-Za-z0-9_-]{86}$/);
const instant = z.string().datetime();
const displayName = (max: number) =>
  z
    .string()
    .min(1)
    .max(max)
    .refine(
      (s) =>
        s === s.trim() &&
        [...s].every((c) => {
          const n = c.codePointAt(0)!;
          return (
            n >= 32 &&
            !(n >= 127 && n <= 159) &&
            !(n >= 0x202a && n <= 0x202e) &&
            !(n >= 0x2066 && n <= 0x2069)
          );
        }),
      'Use a plain display name.',
    );
export const chatProfileSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_PROFILE'),
        id: participantIdSchema,
        name: displayName(32),
        publicKey: publicKeySchema,
        encryptionKey: publicKeySchema,
        updatedAt: instant,
      })
      .strict(),
    signature,
  })
  .strict();
export type ChatProfile = z.infer<typeof chatProfileSchema>;
export const channelPolicySchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_CHANNEL'),
        id: z.string().uuid(),
        name: displayName(48),
        visibility: z.enum(['OPEN', 'INVITE']),
        owner: chatProfileSchema,
        version: z.number().int().min(1).max(2147483647),
        epoch: z.string().uuid(),
        issuedAt: instant,
        expiresAt: instant,
        deleted: z.boolean(),
        members: z
          .array(
            z
              .object({
                profile: chatProfileSchema,
                role: z.enum(['OWNER', 'MODERATOR', 'MEMBER']),
                joinedAt: instant,
                removedAt: instant.nullable(),
              })
              .strict(),
          )
          .min(1)
          .max(16),
        keys: z
          .array(
            z
              .object({ participantId: participantIdSchema, jwe: z.string().min(100).max(2048) })
              .strict(),
          )
          .max(16),
      })
      .strict(),
    signature,
  })
  .strict();
export type ChannelPolicy = z.infer<typeof channelPolicySchema>;
export const chatInviteSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_INVITE'),
        id: z.string().uuid(),
        policy: channelPolicySchema,
        recipientId: participantIdSchema,
        issuedAt: instant,
        expiresAt: instant,
      })
      .strict(),
    signature,
  })
  .strict();
export type ChatInvite = z.infer<typeof chatInviteSchema>;
export const chatMessageSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_MESSAGE'),
        id: z.string().uuid(),
        conversationId: z.string().min(1).max(80),
        author: chatProfileSchema,
        recipientId: participantIdSchema.nullable(),
        policyHash: participantIdSchema.nullable(),
        channelVersion: z.number().int().min(0).max(2147483647),
        epoch: z.string().uuid().nullable(),
        sequence: z.number().int().min(1).max(2147483647),
        createdAt: instant,
        expiresAt: instant,
        format: z.enum(['TEXT', 'PHOTO', 'VIDEO', 'VOICE', 'FILE', 'SYSTEM', 'RELIEF']),
        encrypted: z.boolean(),
        content: z.string().min(1).max(14000),
      })
      .strict(),
    signature,
  })
  .strict();
export type ChatMessage = z.infer<typeof chatMessageSchema>;
export const chatPayloadSchema = z
  .object({
    text: z.string().min(1).max(4000).optional(),
    mentions: z
      .array(participantIdSchema)
      .max(8)
      .refine((ids) => new Set(ids).size === ids.length)
      .optional(),
    reference: z
      .object({
        type: z.enum(['NEED', 'UPDATE']),
        id: z.string().min(1).max(80),
        title: z.string().min(1).max(120),
      })
      .strict()
      .optional(),
    attachment: z
      .object({
        id: z.string().uuid(),
        name: z.string().min(1).max(100),
        mime: z.enum([
          'image/jpeg',
          'image/png',
          'image/webp',
          'audio/mp4',
          'audio/mpeg',
          'video/mp4',
          'video/webm',
          'text/plain',
        ]),
        size: z.number().int().min(1).max(16777188),
        hash: participantIdSchema,
        cipherHash: participantIdSchema,
        key: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
      })
      .strict()
      .optional(),
  })
  .strict()
  .refine((p) => !!(p.text || p.reference || p.attachment));
export function validChatPayload(value: unknown, format: ChatMessage['body']['format']) {
  const payload = chatPayloadSchema.parse(value);
  if (
    bytes(payload).length > 12000 ||
    (['TEXT', 'SYSTEM'].includes(format) && !payload.text) ||
    (format === 'RELIEF' && !payload.reference) ||
    (['PHOTO', 'VIDEO', 'VOICE', 'FILE'].includes(format) && !payload.attachment) ||
    (format === 'PHOTO' && !payload.attachment?.mime.startsWith('image/')) ||
    (format === 'VIDEO' && !payload.attachment?.mime.startsWith('video/')) ||
    (format === 'VOICE' && !payload.attachment?.mime.startsWith('audio/'))
  )
    throw new Error('Invalid chat payload.');
  return payload;
}
export const chatReceiptSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_RECEIPT'),
        messageId: z.string().uuid(),
        conversationId: z.string().min(1).max(80),
        recipient: chatProfileSchema,
        status: z.enum(['DELIVERED', 'READ']),
        recordedAt: instant,
        messageHash: participantIdSchema,
      })
      .strict(),
    signature,
  })
  .strict();
export type ChatReceipt = z.infer<typeof chatReceiptSchema>;
export const chatJoinSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_JOIN'),
        id: z.string().uuid(),
        channelId: z.string().uuid(),
        participant: chatProfileSchema,
        action: z.enum(['JOIN', 'LEAVE']),
        issuedAt: instant,
        expiresAt: instant,
      })
      .strict(),
    signature,
  })
  .strict();
export type ChatJoin = z.infer<typeof chatJoinSchema>;
export const chatSyncSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_SYNC'),
        id: z.string().uuid(),
        profile: chatProfileSchema,
        issuedAt: instant,
        channelIds: z.array(z.string().uuid()).max(16),
        knownMessages: z.array(z.string().uuid()).max(500),
        receiptMessageIds: z.array(z.string().uuid()).max(50),
        peers: z.array(chatProfileSchema).max(16),
        policies: z.array(channelPolicySchema).max(8),
        messages: z.array(chatMessageSchema).max(20),
        receipts: z.array(chatReceiptSchema).max(30),
        joins: z.array(chatJoinSchema).max(8),
        blocks: z.array(participantIdSchema).max(100),
        reports: z
          .array(
            z
              .object({
                id: z.string().uuid(),
                messageId: z.string().uuid(),
                reason: z.enum(['ABUSE', 'SPAM', 'SAFETY']),
              })
              .strict(),
          )
          .max(8),
      })
      .strict(),
    signature,
  })
  .strict();
export type ChatSync = z.infer<typeof chatSyncSchema>;
export async function validChatJoin(input: unknown, now = Date.now()): Promise<ChatJoin> {
  const join = chatJoinSchema.parse(input);
  boundedTime(join.body.issuedAt, join.body.expiresAt, 6 * 3600000, now);
  await validChatProfile(join.body.participant, now);
  if (!(await verify(join.body, join.signature, join.body.participant.body.publicKey)))
    throw new Error('Invalid membership request.');
  return join;
}

export async function directConversationId(a: string, b: string) {
  participantIdSchema.parse(a);
  participantIdSchema.parse(b);
  if (a === b) throw new Error('Direct conversations need two participants.');
  return `dm:${await hash(['SWARM_DM_V1', ...[a, b].sort()])}`;
}
export async function validChatProfile(input: unknown, now = Date.now()): Promise<ChatProfile> {
  const profile = chatProfileSchema.parse(input);
  await crypto.subtle.importKey(
    'jwk',
    profile.body.encryptionKey,
    { name: 'ECDH', namedCurve: 'P-256' },
    false,
    [],
  );
  if (
    Date.parse(profile.body.updatedAt) > now + 300000 ||
    profile.body.id !== (await hash(profile.body.publicKey)) ||
    !(await verify(profile.body, profile.signature, profile.body.publicKey))
  )
    throw new Error('Invalid chat identity.');
  return profile;
}
function boundedTime(created: string, expires: string, max: number, now: number) {
  const start = Date.parse(created),
    end = Date.parse(expires);
  if (start > now + 300000 || end <= now || end <= start || end - start > max)
    throw new Error('Expired or invalid chat information.');
}
export async function validChannelPolicy(input: unknown, now = Date.now()): Promise<ChannelPolicy> {
  const policy = channelPolicySchema.parse(input),
    body = policy.body;
  if (bytes(policy).length > 22000) throw new Error('Channel policy is too large.');
  boundedTime(body.issuedAt, body.expiresAt, 6 * 3600000, now);
  await validChatProfile(body.owner, now);
  if (!(await verify(body, policy.signature, body.owner.body.publicKey)))
    throw new Error('Invalid channel policy signature.');
  const ids = body.members.map((member) => member.profile.body.id);
  if (
    new Set(ids).size !== ids.length ||
    body.members.filter((member) => member.role === 'OWNER').length !== 1 ||
    !body.members.some(
      (member) =>
        member.profile.body.id === body.owner.body.id &&
        member.role === 'OWNER' &&
        !member.removedAt,
    )
  )
    throw new Error('Invalid channel ownership.');
  for (const member of body.members) {
    await validChatProfile(member.profile, now);
    if (Date.parse(member.joinedAt) > now + 300000) throw new Error('Invalid membership time.');
    if (
      member.removedAt &&
      (Date.parse(member.removedAt) < Date.parse(member.joinedAt) ||
        Date.parse(member.removedAt) > now + 300000)
    )
      throw new Error('Invalid removal time.');
  }
  const active = body.members
    .filter((member) => !member.removedAt)
    .map((member) => member.profile.body.id)
    .sort();
  const keyIds = body.keys.map((key) => key.participantId).sort();
  if (
    body.visibility === 'OPEN'
      ? keyIds.length !== 0
      : JSON.stringify(keyIds) !== JSON.stringify(active)
  )
    throw new Error('Invalid channel key recipients.');
  for (const key of body.keys)
    validChatJweHeader(
      key.jwe,
      'ECDH-ES',
      'channel:' + body.id + ':' + body.epoch + ':' + key.participantId,
    );
  return policy;
}
export async function validChatInvite(
  input: unknown,
  recipient: string,
  now = Date.now(),
): Promise<ChatInvite> {
  const invite = chatInviteSchema.parse(input),
    body = invite.body;
  boundedTime(body.issuedAt, body.expiresAt, 6 * 3600000, now);
  const policy = await validChannelPolicy(body.policy, now);
  if (
    body.recipientId !== recipient ||
    Date.parse(body.expiresAt) > Date.parse(policy.body.expiresAt) ||
    !policy.body.members.some(
      (member) => member.profile.body.id === recipient && !member.removedAt,
    ) ||
    !(await verify(body, invite.signature, policy.body.owner.body.publicKey))
  )
    throw new Error('Invitation is not authorized for this participant.');
  return invite;
}
export async function validChatMessage(
  input: unknown,
  policy?: ChannelPolicy,
  now = Date.now(),
): Promise<ChatMessage> {
  const message = chatMessageSchema.parse(input),
    body = message.body;
  if (bytes(message).length > 22000) throw new Error('Chat message is too large.');
  boundedTime(body.createdAt, body.expiresAt, 7 * 86400000, now);
  await validChatProfile(body.author, now);
  if (!(await verify(body, message.signature, body.author.body.publicKey)))
    throw new Error('Invalid chat message signature.');
  if (body.recipientId) {
    if (
      body.conversationId !== (await directConversationId(body.author.body.id, body.recipientId)) ||
      !body.encrypted ||
      body.policyHash !== null ||
      body.channelVersion !== 0 ||
      body.epoch !== null
    )
      throw new Error('Invalid direct conversation.');
  } else {
    if (!policy) throw new Error('Channel policy is required.');
    await validChannelPolicy(policy, now);
    if (
      policy.body.deleted ||
      body.conversationId !== policy.body.id ||
      body.policyHash !== (await hash(policy)) ||
      body.channelVersion !== policy.body.version ||
      body.epoch !== policy.body.epoch ||
      Date.parse(body.createdAt) < Date.parse(policy.body.issuedAt) ||
      Date.parse(body.createdAt) >= Date.parse(policy.body.expiresAt) ||
      body.encrypted !== (policy.body.visibility === 'INVITE') ||
      !policy.body.members.some(
        (member) => member.profile.body.id === body.author.body.id && !member.removedAt,
      )
    )
      throw new Error('Channel message is not authorized by current membership.');
  }
  if (body.encrypted)
    validChatJweHeader(
      body.content,
      body.recipientId ? 'ECDH-ES' : 'dir',
      body.recipientId
        ? 'dm:' + body.conversationId + ':' + body.id + ':' + body.recipientId
        : 'channel:' + body.conversationId + ':' + body.epoch + ':' + body.id,
    );
  else validChatPayload(JSON.parse(body.content), body.format);
  return message;
}
/** Archived policy verifies authorship at creation; it never grants current membership. */
export async function validStoredChatMessage(
  input: unknown,
  policy?: ChannelPolicy,
  now = Date.now(),
) {
  const message = chatMessageSchema.parse(input);
  if (
    Date.parse(message.body.expiresAt) <= now ||
    Date.parse(message.body.createdAt) > now + 300000
  )
    throw new Error('Message has expired.');
  return validChatMessage(message, policy, policy ? Date.parse(message.body.createdAt) : now);
}
export async function validChatReceipt(
  input: unknown,
  message: ChatMessage,
  policy?: ChannelPolicy,
  now = Date.now(),
): Promise<ChatReceipt> {
  const receipt = chatReceiptSchema.parse(input),
    body = receipt.body;
  await validChatProfile(body.recipient, now);
  if (
    Date.parse(body.recordedAt) > now + 300000 ||
    body.messageId !== message.body.id ||
    body.conversationId !== message.body.conversationId ||
    body.messageHash !== (await hash(message)) ||
    !(await verify(body, receipt.signature, body.recipient.body.publicKey))
  )
    throw new Error('Invalid delivery confirmation.');
  if (
    message.body.recipientId
      ? body.recipient.body.id !== message.body.recipientId
      : !policy?.body.members.some(
          (member) => member.profile.body.id === body.recipient.body.id && !member.removedAt,
        )
  )
    throw new Error('A carrier cannot confirm recipient delivery.');
  return receipt;
}
