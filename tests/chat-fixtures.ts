import { randomUUID } from 'node:crypto';
import {
  exportPublic,
  generateKeys,
  hash,
  sign,
  directConversationId,
  encryptChatValue,
  type ChatProfile,
  type ChannelPolicy,
  type ChatMessage,
  type ChatSync,
  type ChatAction,
} from '../packages/protocol/src';
export async function chatPerson(name: string) {
  const signing = await generateKeys(),
    ecdh = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, [
      'deriveBits',
    ]);
  const publicKey = await exportPublic(signing.publicKey),
    encryptionKey = await exportPublic(ecdh.publicKey);
  const body = {
    v: 1 as const,
    kind: 'CHAT_PROFILE' as const,
    id: await hash(publicKey),
    name,
    publicKey,
    encryptionKey,
    updatedAt: new Date().toISOString(),
  };
  return {
    signing,
    ecdh,
    channels: new Set<string>(),
    profile: { body, signature: await sign(body, signing.privateKey) } as ChatProfile,
  };
}
export type Person = Awaited<ReturnType<typeof chatPerson>>;
export async function chatPolicy(
  owner: Person,
  people: Person[],
  visibility: 'OPEN' | 'INVITE' = 'OPEN',
  previous?: ChannelPolicy,
) {
  const epoch = randomUUID(),
    id = previous?.body.id ?? randomUUID(),
    key = crypto.getRandomValues(new Uint8Array(32));
  for (const person of people) person.channels.add(id);
  const keys =
    visibility === 'OPEN'
      ? []
      : await Promise.all(
          people.map(async (p) => ({
            participantId: p.profile.body.id,
            jwe: await encryptChatValue(
              { key: Buffer.from(key).toString('base64url') },
              p.profile.body.encryptionKey,
              'channel:' + id + ':' + epoch + ':' + p.profile.body.id,
            ),
          })),
        );
  const body = {
    v: 1 as const,
    kind: 'CHAT_CHANNEL' as const,
    id,
    name: 'Medical Help',
    visibility,
    owner: owner.profile,
    version: (previous?.body.version ?? 0) + 1,
    epoch,
    issuedAt: new Date(Date.now() - 1000).toISOString(),
    expiresAt: new Date(Date.now() + 3600000).toISOString(),
    deleted: false,
    members: people.map((p) => ({
      profile: p.profile,
      role: p === owner ? ('OWNER' as const) : ('MEMBER' as const),
      joinedAt: new Date(Date.now() - 1000).toISOString(),
      removedAt: null,
    })),
    keys,
  };
  return {
    policy: { body, signature: await sign(body, owner.signing.privateKey) } as ChannelPolicy,
    key,
  };
}
export async function chatMessage(
  author: Person,
  recipient: Person | { policy: ChannelPolicy; key: Uint8Array },
  text = 'Where is Gate 2?',
) {
  const id = randomUUID(),
    direct = 'profile' in recipient,
    conversationId = direct
      ? await directConversationId(author.profile.body.id, recipient.profile.body.id)
      : recipient.policy.body.id,
    encrypted = direct || recipient.policy.body.visibility === 'INVITE';
  const kid = direct
    ? 'dm:' + conversationId + ':' + id + ':' + recipient.profile.body.id
    : 'channel:' + conversationId + ':' + recipient.policy.body.epoch + ':' + id;
  const content = encrypted
    ? await encryptChatValue(
        { text },
        direct ? recipient.profile.body.encryptionKey : recipient.key,
        kid,
      )
    : JSON.stringify({ text });
  const body = {
    v: 1 as const,
    kind: 'CHAT_MESSAGE' as const,
    id,
    conversationId,
    author: author.profile,
    recipientId: direct ? recipient.profile.body.id : null,
    policyHash: direct ? null : await hash(recipient.policy),
    channelVersion: direct ? 0 : recipient.policy.body.version,
    epoch: direct ? null : recipient.policy.body.epoch,
    sequence: 1,
    createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 86400000).toISOString(),
    format: 'TEXT' as const,
    encrypted,
    content,
  };
  return { body, signature: await sign(body, author.signing.privateKey) } as ChatMessage;
}
export async function chatBatch(person: Person, fields: Partial<ChatSync['body']> = {}) {
  const body = {
    v: 1 as const,
    kind: 'CHAT_SYNC' as const,
    id: randomUUID(),
    profile: person.profile,
    issuedAt: new Date().toISOString(),
    channelIds: [...person.channels].slice(0, 16),
    knownMessages: [],
    receiptMessageIds: [],
    peers: [],
    policies: [],
    messages: [],
    receipts: [],
    joins: [],
    blocks: [],
    reports: [],
    ...fields,
  };
  return { body, signature: await sign(body, person.signing.privateKey) };
}
export async function channelAction(
  actor: Person,
  policy: ChannelPolicy,
  action: ChatAction['body']['action'],
  targetId: string,
  role?: ChatAction['body']['role'],
) {
  const body: ChatAction['body'] = {
    v: 1,
    kind: 'CHAT_ACTION',
    id: randomUUID(),
    channelId: policy.body.id,
    actor: actor.profile,
    policyHash: await hash(policy),
    version: policy.body.version,
    action,
    targetId,
    issuedAt: new Date().toISOString(),
    expiresAt: policy.body.expiresAt,
    ...(role ? { role } : {}),
    ...(['REACT', 'UNREACT'].includes(action) ? { reaction: 'THANKS' as const } : {}),
  };
  return { body, signature: await sign(body, actor.signing.privateKey) };
}
