import { z } from 'zod';
import { bytes, hash, publicKeySchema, verify } from './crypto';
import { validChatJweHeader } from './chat-encryption';

export const MAX_CHANNEL_MEMBERS = 200;
export const MAX_CHANNEL_POLICY_BYTES = 384 * 1024;
export const MAX_CHAT_SYNC_BYTES = 900000;

/** One limit for every photo/video/file path: uploads, reports, chat and nearby transfer. */
export const MAX_MEDIA_BYTES = 250 * 1024 * 1024;
/** Largest 8 KiB part index; 256 MiB leaves room for encryption overhead. */
export const MAX_MEDIA_PART = 32767;
/** Parts per signed HTTP request (1 MiB) keep a 250 MB upload to ~250 requests. */
export const MAX_MEDIA_PARTS_PER_REQUEST = 128;
export const participantIdSchema = z.string().regex(/^[a-f0-9]{64}$/);
const signature = z.string().regex(/^[A-Za-z0-9_-]{86}$/);
const instant = z.string().datetime();
export const channelRoleSchema = z.enum(['OWNER', 'ADMIN', 'MODERATOR', 'MEMBER', 'READ_ONLY']);
export type ChannelRole = z.infer<typeof channelRoleSchema>;
export const channelCapabilitiesSchema = z
  .object({
    canRead: z.boolean(),
    canPostTopLevel: z.boolean(),
    canReplyInThreads: z.boolean(),
    canCreateThreads: z.boolean(),
    canAttachMedia: z.boolean(),
    canReact: z.boolean(),
    canInvite: z.boolean(),
    canModerate: z.boolean(),
    canStartCalls: z.boolean(),
    canJoinCalls: z.boolean(),
    canManageMembers: z.boolean(),
  })
  .strict();
export type ChannelCapabilities = z.infer<typeof channelCapabilitiesSchema>;
export const channelSettingsSchema = z
  .object({
    mode: z.enum(['DISCUSSION', 'ANNOUNCEMENT']),
    admission: z.enum(['OPEN', 'INVITE_AUTO', 'INVITE_PLUS_APPROVAL', 'APPROVAL_ONLY']),
    capabilities: z
      .object({
        ADMIN: channelCapabilitiesSchema.optional(),
        MODERATOR: channelCapabilitiesSchema.optional(),
        MEMBER: channelCapabilitiesSchema.optional(),
        READ_ONLY: channelCapabilitiesSchema.optional(),
      })
      .strict()
      .optional(),
  })
  .strict();
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
        settings: channelSettingsSchema.optional(),
        bannedIds: z.array(participantIdSchema).max(100).optional(),
        appliedActions: z.array(z.string().uuid()).max(100).optional(),
        moderation: z
          .object({
            lockedThreads: z.array(z.string().uuid()).max(100),
            hiddenMessages: z.array(z.string().uuid()).max(100),
          })
          .strict()
          .optional(),
        members: z
          .array(
            z
              .object({
                profile: chatProfileSchema,
                role: channelRoleSchema,
                joinedAt: instant,
                removedAt: instant.nullable(),
              })
              .strict(),
          )
          .min(1)
          .max(MAX_CHANNEL_MEMBERS),
        keys: z
          .array(
            z
              .object({ participantId: participantIdSchema, jwe: z.string().min(100).max(2048) })
              .strict(),
          )
          .max(MAX_CHANNEL_MEMBERS),
      })
      .strict(),
    signature,
  })
  .strict();
export type ChannelPolicy = z.infer<typeof channelPolicySchema>;
/** Role ceilings are enforced even when a channel supplies explicit capability sets. */
export function channelCapabilities(
  policy: ChannelPolicy,
  participant: string,
): ChannelCapabilities {
  const role = policy.body.members.find(
    (m) => m.profile.body.id === participant && !m.removedAt,
  )?.role;
  const admitted = !!role && !policy.body.deleted && !policy.body.bannedIds?.includes(participant);
  const manager = role === 'OWNER' || role === 'ADMIN';
  const moderator = manager || role === 'MODERATOR';
  const writer = admitted && role !== 'READ_ONLY';
  const ceiling: ChannelCapabilities = {
    canRead: admitted,
    canPostTopLevel: writer && (policy.body.settings?.mode !== 'ANNOUNCEMENT' || moderator),
    canReplyInThreads: writer,
    canCreateThreads: writer,
    canAttachMedia: writer,
    canReact: admitted,
    canInvite: admitted && manager,
    canModerate: admitted && moderator,
    canStartCalls: false,
    canJoinCalls: false,
    canManageMembers: admitted && moderator,
  };
  const configured =
    role && role !== 'OWNER' ? policy.body.settings?.capabilities?.[role] : undefined;
  const result = { ...ceiling };
  for (const key of Object.keys(result) as (keyof ChannelCapabilities)[]) {
    result[key] =
      ceiling[key] && (configured?.[key] ?? (key === 'canManageMembers' ? manager : true));
  }
  if (!result.canRead)
    for (const key of Object.keys(result) as (keyof ChannelCapabilities)[]) result[key] = false;
  return result;
}
export const chatAdmissionSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_ADMISSION'),
        id: z.string().uuid(),
        channelId: z.string().uuid(),
        name: displayName(48),
        owner: chatProfileSchema,
        issuer: chatProfileSchema.optional(),
        recipientId: participantIdSchema,
        policyHash: participantIdSchema,
        admission: z.enum(['INVITE_PLUS_APPROVAL', 'APPROVAL_ONLY']),
        issuedAt: instant,
        expiresAt: instant,
      })
      .strict(),
    signature,
  })
  .strict();
export type ChatAdmission = z.infer<typeof chatAdmissionSchema>;
export async function validChatAdmission(input: unknown, recipient: string, now = Date.now()) {
  const invite = chatAdmissionSchema.parse(input),
    b = invite.body;
  boundedTime(b.issuedAt, b.expiresAt, 6 * 3600000, now);
  await validChatProfile(b.owner, now);
  if (b.issuer) await validChatProfile(b.issuer, now);
  if (
    b.recipientId !== recipient ||
    !(await verify(b, invite.signature, (b.issuer ?? b.owner).body.publicKey))
  )
    throw new Error('Invitation is not authorized for this participant.');
  return invite;
}
export const chatActionSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('CHAT_ACTION'),
        id: z.string().uuid(),
        channelId: z.string().uuid(),
        actor: chatProfileSchema,
        policyHash: participantIdSchema,
        version: z.number().int().positive(),
        action: z.enum([
          'APPROVE_JOIN',
          'REJECT_JOIN',
          'REMOVE',
          'BAN',
          'UNBAN',
          'SET_ROLE',
          'LOCK_THREAD',
          'UNLOCK_THREAD',
          'HIDE_MESSAGE',
          'RESTORE_MESSAGE',
          'REVIEW_REPORT',
          'REACT',
          'UNREACT',
        ]),
        targetId: z.union([participantIdSchema, z.string().uuid()]),
        role: channelRoleSchema.optional(),
        reaction: z.enum(['THANKS', 'SUPPORT']).optional(),
        issuedAt: instant,
        expiresAt: instant,
      })
      .strict(),
    signature,
  })
  .strict();
export type ChatAction = z.infer<typeof chatActionSchema>;
export async function validChatAction(input: unknown, policy: ChannelPolicy, now = Date.now()) {
  const action = chatActionSchema.parse(input),
    b = action.body;
  boundedTime(b.issuedAt, b.expiresAt, 6 * 3600000, now);
  await validChatProfile(b.actor, now);
  await validChannelPolicy(policy, now);
  const actor = b.actor.body.id,
    caps = channelCapabilities(policy, actor);
  const role = policy.body.members.find((m) => m.profile.body.id === actor)?.role;
  const target = policy.body.members.find((m) => m.profile.body.id === b.targetId)?.role;
  if (
    b.channelId !== policy.body.id ||
    b.version !== policy.body.version ||
    b.policyHash !== (await hash(policy)) ||
    !(await verify(b, action.signature, b.actor.body.publicKey))
  )
    throw new Error('Stale or invalid channel action.');
  const membership = ['APPROVE_JOIN', 'REJECT_JOIN', 'REMOVE', 'BAN', 'UNBAN', 'SET_ROLE'].includes(
    b.action,
  );
  const reaction = ['REACT', 'UNREACT'].includes(b.action);
  if (!(membership ? caps.canManageMembers : reaction ? caps.canReact : caps.canModerate))
    throw new Error('Channel action is not permitted.');
  if (
    membership &&
    (!participantIdSchema.safeParse(b.targetId).success ||
      target === 'OWNER' ||
      (target === 'ADMIN' && role !== 'OWNER') ||
      (b.action === 'SET_ROLE' &&
        (!b.role ||
          b.role === 'OWNER' ||
          (b.role === 'ADMIN' && role !== 'OWNER') ||
          role === 'MODERATOR')))
  )
    throw new Error('Role authority is not permitted.');
  if (!membership && !z.string().uuid().safeParse(b.targetId).success)
    throw new Error('Message target is invalid.');
  if (reaction !== !!b.reaction || (b.action === 'SET_ROLE') !== !!b.role)
    throw new Error('Unexpected action fields.');
  return action;
}
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
        threadRootId: z.string().uuid().optional(),
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
        size: z.number().int().min(1).max(MAX_MEDIA_BYTES),
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
        invitation: chatAdmissionSchema.optional(),
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
        knownPolicyHashes: z.array(participantIdSchema).max(128).optional(),
        knownJoinIds: z.array(z.string().uuid()).max(100).optional(),
        knownMessages: z.array(z.string().uuid()).max(500),
        receiptMessageIds: z.array(z.string().uuid()).max(50),
        peers: z.array(chatProfileSchema).max(16),
        policies: z.array(channelPolicySchema).max(8),
        messages: z.array(chatMessageSchema).max(20),
        receipts: z.array(chatReceiptSchema).max(30),
        joins: z.array(chatJoinSchema).max(8),
        actions: z.array(chatActionSchema).max(8).optional(),
        blocks: z.array(participantIdSchema).max(100),
        reports: z
          .array(
            z
              .object({
                id: z.string().uuid(),
                messageId: z.string().uuid().optional(),
                personId: participantIdSchema.optional(),
                reason: z.enum(['ABUSE', 'SPAM', 'SAFETY', 'OTHER']),
              })
              .strict()
              .refine(
                (r) => Boolean(r.messageId) !== Boolean(r.personId),
                'Choose a message or a person to report.',
              ),
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
  if (join.body.invitation) {
    await validChatAdmission(join.body.invitation, join.body.participant.body.id, now);
    if (join.body.action !== 'JOIN' || join.body.invitation.body.channelId !== join.body.channelId)
      throw new Error('Invalid admission request.');
  }
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
  if (bytes(policy).length > MAX_CHANNEL_POLICY_BYTES)
    throw new Error('Channel policy is too large.');
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
  const readers = active.filter(
    (id) => channelCapabilities({ ...policy, body: { ...body, deleted: false } }, id).canRead,
  );
  if (
    new Set(body.bannedIds ?? []).size !== (body.bannedIds?.length ?? 0) ||
    active.some((id) => body.bannedIds?.includes(id)) ||
    (body.settings &&
      (body.visibility === 'OPEN'
        ? !['OPEN', 'APPROVAL_ONLY'].includes(body.settings.admission)
        : body.settings.admission === 'OPEN'))
  )
    throw new Error('Invalid channel admission policy.');
  if (
    body.visibility === 'OPEN'
      ? keyIds.length !== 0
      : JSON.stringify(keyIds) !== JSON.stringify(readers)
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
      body.epoch !== null ||
      body.threadRootId
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
    const caps = channelCapabilities(policy, body.author.body.id);
    if (
      !(body.threadRootId ? caps.canReplyInThreads : caps.canPostTopLevel) ||
      (['PHOTO', 'VIDEO', 'VOICE', 'FILE'].includes(body.format) && !caps.canAttachMedia)
    )
      throw new Error('Channel posting capability is required.');
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
