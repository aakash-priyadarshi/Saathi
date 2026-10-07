import { randomUUID } from 'node:crypto';
import {
  hash,
  sign,
  communityEnvelopeSchema,
  type CommunityEnvelope,
} from '../packages/protocol/src';
import type { Person } from './chat-fixtures';
export const helpPayload = (changes: Record<string, unknown> = {}) => ({
  category: 'WATER',
  audience: 'MYSELF',
  quantity: 2,
  details: 'Two drinking water bottles needed',
  area: 'Fictional Gate 2',
  priority: 'NORMAL',
  status: 'OPEN',
  responderId: null,
  ...changes,
});
export async function communityEvent(
  person: Person,
  type: CommunityEnvelope['body']['type'],
  payload: unknown,
  objectId?: string,
  changes: Record<string, unknown> = {},
): Promise<CommunityEnvelope> {
  const id = randomUUID();
  const body = {
    v: 1,
    kind: 'COMMUNITY_EVENT',
    id,
    objectId: objectId ?? id,
    author: person.profile,
    createdAt: new Date().toISOString(),
    expiresAt: new Date(
      Date.now() + (type === 'HELP' || type === 'HELP_OFFER' ? 7200000 : 7 * 86400000),
    ).toISOString(),
    maxHops: 16,
    type,
    payload,
    payloadHash: await hash(payload),
    ...changes,
  };
  return communityEnvelopeSchema.parse({
    body,
    signature: await sign(body, person.signing.privateKey),
  });
}
export async function communityBatch(
  person: Person,
  events: CommunityEnvelope[] = [],
  known: string[] = [],
  areas = ['Fictional Gate 2'],
) {
  const body = {
    v: 1,
    kind: 'COMMUNITY_SYNC',
    id: randomUUID(),
    profile: person.profile,
    issuedAt: new Date().toISOString(),
    areas,
    known,
    events,
  };
  return { body, signature: await sign(body, person.signing.privateKey) };
}
