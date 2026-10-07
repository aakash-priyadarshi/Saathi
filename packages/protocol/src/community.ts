import { z } from 'zod';
import { bytes, hash, verify } from './crypto';
import {
  chatProfileSchema,
  participantIdSchema,
  validChatProfile,
  MAX_MEDIA_BYTES,
  MAX_MEDIA_PART,
  MAX_MEDIA_PARTS_PER_REQUEST,
} from './chat';

// Bound asynchronous field relay depth while allowing events to cross several
// disconnected encounter chains before reaching an internet gateway.
export const MAX_COMMUNITY_HOPS = 16;

// Public participant statements do not confer volunteer or relief authority.
export const communityText = (max: number) =>
  z
    .string()
    .min(1)
    .max(max)
    .refine(
      (value) =>
        value === value.trim() &&
        [...value].every((c) => {
          const n = c.codePointAt(0)!;
          return (
            n >= 32 &&
            !(n >= 127 && n <= 159) &&
            !(n >= 0x202a && n <= 0x202e) &&
            !(n >= 0x2066 && n <= 0x2069)
          );
        }) &&
        !/(?:[\w.+-]+@[\w.-]+\.[a-z]{2,}|(?:\+?\d[\d ()-]{7,}\d)|-?\d{1,3}\.\d{4,}\s*[,/]\s*-?\d{1,3}\.\d{4,})/iu.test(
          value,
        ),
      'Use public-area details; remove phone, email and exact coordinates.',
    );
export const helpPayloadSchema = z
  .object({
    category: z.enum(['WATER', 'FIRST_AID', 'FOOD', 'CHARGING', 'ACCESSIBILITY', 'OTHER']),
    audience: z.enum(['MYSELF', 'GROUP']),
    quantity: z.number().int().min(1).max(1000),
    details: communityText(400),
    area: communityText(80),
    priority: z.enum(['NORMAL', 'IMPORTANT', 'URGENT']),
    status: z.enum(['OPEN', 'RESPONDER_ASSIGNED', 'RESOLVED', 'CANCELLED']),
    responderId: participantIdSchema.nullable(),
  })
  .strict()
  .refine(
    (p) => (p.status === 'RESPONDER_ASSIGNED') === (p.responderId !== null),
    'Assign a responder only while help is in progress.',
  );
export const reportMediaSchema = z
  .object({
    id: z.string().uuid(),
    mime: z.enum(['image/jpeg', 'video/mp4', 'audio/mp4']),
    size: z.number().int().min(1).max(MAX_MEDIA_BYTES),
    hash: z.string().regex(/^[a-f0-9]{64}$/),
    width: z.number().int().min(1).max(8192),
    height: z.number().int().min(1).max(8192),
    durationSeconds: z.number().int().min(0),
  })
  .strict()
  .refine((m) =>
    m.mime === 'image/jpeg'
      ? m.durationSeconds === 0
      : m.durationSeconds > 0 &&
        (m.mime !== 'audio/mp4' || (m.width === 1 && m.height === 1)),
  );
const common = {
  v: z.literal(1),
  kind: z.literal('COMMUNITY_EVENT'),
  id: z.string().uuid(),
  objectId: z.string().uuid(),
  author: chatProfileSchema,
  createdAt: z.string().datetime(),
  expiresAt: z.string().datetime(),
  maxHops: z.number().int().min(1).max(MAX_COMMUNITY_HOPS),
  payloadHash: z.string().regex(/^[a-f0-9]{64}$/),
};
export const communityBodySchema = z.discriminatedUnion('type', [
  z
    .object({
      ...common,
      type: z.literal('HELP'),
      payload: z
        .object({
          version: z.number().int().min(1).max(1000),
          previousHash: z
            .string()
            .regex(/^[a-f0-9]{64}$/)
            .nullable(),
          help: helpPayloadSchema,
        })
        .strict(),
    })
    .strict(),
  z
    .object({
      ...common,
      type: z.literal('HELP_OFFER'),
      payload: z.object({ requestHash: z.string().regex(/^[a-f0-9]{64}$/) }).strict(),
    })
    .strict(),
  z
    .object({
      ...common,
      type: z.literal('REPORT'),
      payload: z
        .object({
          caption: communityText(2000),
          area: communityText(80),
          contentWarning: z.boolean(),
          media: reportMediaSchema.nullable(),
        })
        .strict(),
    })
    .strict(),
  z
    .object({
      ...common,
      type: z.literal('WITHDRAW'),
      payload: z.object({ reportHash: z.string().regex(/^[a-f0-9]{64}$/) }).strict(),
    })
    .strict(),
  z
    .object({
      ...common,
      type: z.literal('FLAG'),
      payload: z
        .object({
          targetHash: z.string().regex(/^[a-f0-9]{64}$/),
          reason: z.enum(['SPAM', 'HARASSMENT', 'UNSAFE', 'OTHER']),
        })
        .strict(),
    })
    .strict(),
]);
export const communityEnvelopeSchema = z
  .object({ body: communityBodySchema, signature: z.string().regex(/^[A-Za-z0-9_-]{86}$/) })
  .strict();
export type CommunityEnvelope = z.infer<typeof communityEnvelopeSchema>;
export async function validCommunityEnvelope(event: CommunityEnvelope, now = Date.now()) {
  const b = event.body,
    created = Date.parse(b.createdAt),
    expires = Date.parse(b.expiresAt);
  const lifetime = b.type === 'HELP' || b.type === 'HELP_OFFER' ? 7200000 : 7 * 86400000;
  if (
    bytes(event).length > 32768 ||
    created > now + 300000 ||
    expires <= now ||
    expires <= created ||
    expires - created > lifetime ||
    !communityText(32).safeParse(b.author.body.name).success ||
    !(await validChatProfile(b.author, now)) ||
    b.payloadHash !== (await hash(b.payload)) ||
    !(await verify(b, event.signature, b.author.body.publicKey))
  )
    return false;
  if (b.type === 'HELP')
    return b.payload.version === 1
      ? b.id === b.objectId && b.payload.previousHash === null && b.payload.help.status === 'OPEN'
      : b.payload.previousHash !== null && b.id !== b.objectId;
  return b.type !== 'REPORT' || b.id === b.objectId;
}
export const communitySyncSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('COMMUNITY_SYNC'),
        id: z.string().uuid(),
        profile: chatProfileSchema,
        issuedAt: z.string().datetime(),
        areas: z.array(communityText(80)).max(10),
        known: z.array(z.string().uuid()).max(500),
        receiptIds: z.array(z.string().uuid()).max(100).optional(),
        events: z.array(communityEnvelopeSchema).max(20),
      })
      .strict(),
    signature: z.string().regex(/^[A-Za-z0-9_-]{86}$/),
  })
  .strict();
export const communityMediaRequestSchema = z
  .object({
    body: z
      .object({
        v: z.literal(1),
        kind: z.literal('COMMUNITY_MEDIA'),
        profile: chatProfileSchema,
        issuedAt: z.string().datetime(),
        reportId: z.string().uuid(),
        chunks: z
          .array(
            z
              .object({
                index: z.number().int().min(0).max(MAX_MEDIA_PART),
                data: z
                  .string()
                  .regex(/^[A-Za-z0-9_-]+$/)
                  .max(10923),
              })
              .strict(),
          )
          .max(MAX_MEDIA_PARTS_PER_REQUEST),
      })
      .strict(),
    signature: z.string().regex(/^[A-Za-z0-9_-]{86}$/),
  })
  .strict();
