import { z } from 'zod';
import { requestPayloadSchema, editSchema, fieldSchema } from '@saathi/validation';
export {
  receiptKeySchema,
  receiptKeyringSchema,
  serviceConfigBodySchema,
  signedServiceConfigSchema,
  verifyServiceConfig,
  validReceiptWithKeyring,
} from './service-config';
export type {
  ReceiptKeyPolicy,
  ServiceConfig,
  SignedServiceConfig,
  ConfigPolicy,
} from './service-config';
export * from './crypto';
import { publicKeySchema, bytes, hash, sign, verify, exportPublic, type PublicKey } from './crypto';
export const MAX_EVENT_BYTES = 65536;
const common = {
  id: z.string().uuid(),
  protocolVersion: z.literal(1),
  authorId: z.string().uuid(),
  organizationId: z.string().uuid(),
  deviceId: z.string().uuid(),
  createdAt: z.string().datetime(),
  expiresAt: z.string().datetime(),
  intent: z.literal('PUBLIC_RELIEF'),
  maxHops: z.number().int().min(1).max(8),
  payloadHash: z.string().regex(/^[a-f0-9]{64}$/),
};
export const bodySchema = z.discriminatedUnion('type', [
  z
    .object({ ...common, type: z.literal('REQUEST_CREATED'), payload: requestPayloadSchema })
    .strict(),
  z
    .object({
      ...common,
      type: z.literal('REQUEST_UPDATED'),
      payload: z
        .object({ publicId: z.string().regex(/^[A-Z]{2,6}-[A-Z0-9]{6,12}$/), changes: editSchema })
        .strict(),
    })
    .strict(),
  z
    .object({
      ...common,
      type: z.literal('FIELD_PUBLISHED'),
      payload: fieldSchema
        .omit({ request: true })
        .extend({ request: requestPayloadSchema.optional() })
        .refine(
          (f) => f.mediaIds.length === 0,
          'Offline carriers cannot publish private media originals.',
        ),
    })
    .strict(),
]);
export const envelopeSchema = z
  .object({
    body: bodySchema,
    publicKey: publicKeySchema,
    signature: z.string().regex(/^[A-Za-z0-9_-]{86}$/),
  })
  .strict();
export type EventBody = z.infer<typeof bodySchema>;
export type Envelope = z.infer<typeof envelopeSchema>;
export type EventInput = Omit<
  EventBody,
  'id' | 'protocolVersion' | 'createdAt' | 'expiresAt' | 'intent' | 'maxHops' | 'payloadHash'
>;
export const receiptBodySchema = z
  .object({
    eventId: z.string().uuid(),
    payloadHash: z.string().regex(/^[a-f0-9]{64}$/),
    signatureHash: z.string().regex(/^[a-f0-9]{64}$/),
    status: z.enum(['PUBLISHED', 'ACCEPTED', 'REJECTED', 'CONFLICT', 'INVALIDATED']),
    recordedAt: z.string().datetime(),
    publicId: z.string().optional(),
    fieldId: z.string().uuid().optional(),
    message: z.string().max(400),
    keyId: z.string(),
  })
  .strict();
export const receiptSchema = z
  .object({ body: receiptBodySchema, signature: z.string().regex(/^[A-Za-z0-9_-]{86}$/) })
  .strict();
export type Receipt = z.infer<typeof receiptSchema>;
export async function createEnvelope(input: EventInput, keys: CryptoKeyPair): Promise<Envelope> {
  const body = bodySchema.parse({
    ...input,
    id: crypto.randomUUID(),
    protocolVersion: 1,
    createdAt: new Date().toISOString(),
    expiresAt: new Date(Date.now() + 86400000).toISOString(),
    intent: 'PUBLIC_RELIEF',
    maxHops: 6,
    payloadHash: await hash(input.payload),
  });
  const envelope = {
    body,
    publicKey: await exportPublic(keys.publicKey),
    signature: await sign(body, keys.privateKey),
  };
  if (bytes(envelope).length > MAX_EVENT_BYTES)
    throw new Error('This update is too large to share nearby.');
  return envelope;
}
// This checks consistency, not volunteer authority. Only Saathi validates the registered author.
export async function validEnvelope(envelope: Envelope) {
  return (
    bytes(envelope).length <= MAX_EVENT_BYTES &&
    envelope.body.payloadHash === (await hash(envelope.body.payload)) &&
    (await verify(envelope.body, envelope.signature, envelope.publicKey))
  );
}
export async function validReceipt(receipt: Receipt, key: PublicKey, envelope: Envelope) {
  return (
    receipt.body.eventId === envelope.body.id &&
    receipt.body.payloadHash === envelope.body.payloadHash &&
    receipt.body.signatureHash === (await hash(envelope.signature)) &&
    receipt.body.keyId === (await hash(key)) &&
    (await verify(receipt.body, receipt.signature, key))
  );
}
export type Operation = 'AUTHORITATIVE_WRITE' | 'EVENT' | 'MESSAGE' | 'FILE' | 'VOICE' | 'VIDEO';
export type Capabilities = {
  internet: boolean;
  nearby: boolean;
  media: boolean;
  storage: boolean;
  lowBandwidth: boolean;
};
export function choosePath(
  operation: Operation,
  c: Capabilities,
): 'SERVER' | 'NEARBY' | 'LOCAL' | 'UNAVAILABLE' {
  if (operation === 'AUTHORITATIVE_WRITE') return c.internet ? 'SERVER' : 'UNAVAILABLE';
  if (operation === 'EVENT' && c.internet) return 'SERVER';
  if (
    c.nearby &&
    (!['VOICE', 'VIDEO'].includes(operation) || c.media) &&
    !(c.lowBandwidth && ['FILE', 'VIDEO'].includes(operation))
  )
    return 'NEARBY';
  return c.storage && !['VOICE', 'VIDEO'].includes(operation) ? 'LOCAL' : 'UNAVAILABLE';
}
