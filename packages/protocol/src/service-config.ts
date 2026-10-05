import { z } from 'zod';
import { bytes, hash, publicKeySchema, verify, type PublicKey } from './crypto';
import { validReceipt, type Receipt, type Envelope } from './index';

const timestamp = z.string().datetime();
export const receiptKeySchema = z
  .object({
    keyId: z.string().regex(/^[a-f0-9]{64}$/),
    publicKey: publicKeySchema,
    status: z.enum(['ACTIVE', 'RETIRED', 'REVOKED']),
    signingFrom: timestamp,
    signingUntil: timestamp,
    verifyUntil: timestamp,
  })
  .strict()
  .refine(
    (k) =>
      Date.parse(k.signingFrom) < Date.parse(k.signingUntil) &&
      Date.parse(k.signingUntil) <= Date.parse(k.verifyUntil),
    'Invalid receipt key lifetime.',
  );
export const receiptKeyringSchema = z
  .array(receiptKeySchema)
  .min(1)
  .max(20)
  .refine(
    (keys) =>
      new Set(keys.map((k) => k.keyId)).size === keys.length &&
      keys.filter((k) => k.status === 'ACTIVE').length === 1,
    'Receipt key IDs must be unique, with one active signing key.',
  );
export type ReceiptKeyPolicy = z.infer<typeof receiptKeySchema>;
export const serviceConfigBodySchema = z
  .object({
    format: z.literal('saathi-service-config-v1'),
    environment: z.enum(['development', 'staging', 'production']),
    version: z.number().int().positive().max(Number.MAX_SAFE_INTEGER),
    issuedAt: timestamp,
    expiresAt: timestamp,
    protocolVersions: z.array(z.literal(1)).length(1),
    minimumAndroidVersionCode: z.number().int().positive(),
    apiEndpoints: z.array(z.string().url().max(300)).min(1).max(3),
    publicUrl: z.string().url().max(300),
    webOrigin: z.string().url().max(300),
    receiptKeys: receiptKeyringSchema,
    features: z
      .object({ nearby: z.boolean(), localCalls: z.boolean(), largeFiles: z.boolean() })
      .strict(),
  })
  .strict();
export const signedServiceConfigSchema = z
  .object({
    body: serviceConfigBodySchema,
    rootKeyId: z.string().regex(/^[a-f0-9]{64}$/),
    signature: z.string().regex(/^[A-Za-z0-9_-]{86}$/),
  })
  .strict();
export type ServiceConfig = z.infer<typeof serviceConfigBodySchema>;
export type SignedServiceConfig = z.infer<typeof signedServiceConfigSchema>;
export type ConfigPolicy = {
  environment: ServiceConfig['environment'];
  minimumVersion: number;
  androidVersionCode: number;
  now?: number;
  allowLoopbackHttp?: boolean;
  previous?: SignedServiceConfig;
};

function safeUrl(text: string, allowLoopbackHttp: boolean) {
  const url = new URL(text);
  return (
    !url.username &&
    !url.password &&
    !url.hash &&
    !url.search &&
    (url.protocol === 'https:' ||
      (allowLoopbackHttp &&
        url.protocol === 'http:' &&
        ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)))
  );
}
export async function verifyServiceConfig(
  value: unknown,
  root: PublicKey,
  policy: ConfigPolicy,
): Promise<SignedServiceConfig> {
  if (bytes(value).length > 32768) throw new Error('Service configuration is too large.');
  const config = signedServiceConfigSchema.parse(value),
    body = config.body,
    now = policy.now ?? Date.now();
  if (config.rootKeyId !== (await hash(root)) || !(await verify(body, config.signature, root)))
    throw new Error('Service configuration signature is invalid.');
  if (
    body.environment !== policy.environment ||
    body.version < policy.minimumVersion ||
    body.minimumAndroidVersionCode > policy.androidVersionCode
  )
    throw new Error('Service configuration environment, version or app compatibility is invalid.');
  if (
    Date.parse(body.issuedAt) > now + 300000 ||
    Date.parse(body.expiresAt) <= now ||
    Date.parse(body.expiresAt) <= Date.parse(body.issuedAt) ||
    Date.parse(body.expiresAt) - Date.parse(body.issuedAt) > 31 * 86400000
  )
    throw new Error('Service configuration is expired or its time is invalid.');
  if (
    policy.previous &&
    body.version === policy.previous.body.version &&
    (await hash(body)) !== (await hash(policy.previous.body))
  )
    throw new Error('A configuration version cannot change its contents.');
  if (
    ![...body.apiEndpoints, body.publicUrl, body.webOrigin].every((url) =>
      safeUrl(url, policy.allowLoopbackHttp === true && body.environment !== 'production'),
    )
  )
    throw new Error('Service configuration requires safe HTTPS endpoints.');
  for (const key of body.receiptKeys)
    if (key.keyId !== (await hash(key.publicKey)))
      throw new Error('Receipt key ID does not match its public key.');
  return config;
}
export async function validReceiptWithKeyring(
  receipt: Receipt,
  keys: ReceiptKeyPolicy[],
  envelope: Envelope,
  now = Date.now(),
) {
  const key = keys.find((k) => k.keyId === receipt.body.keyId),
    recorded = Date.parse(receipt.body.recordedAt);
  return Boolean(
    key &&
    key.status !== 'REVOKED' &&
    recorded >= Date.parse(key.signingFrom) &&
    recorded <= Date.parse(key.signingUntil) &&
    recorded <= now + 300000 &&
    now <= Date.parse(key.verifyUntil) &&
    (await validReceipt(receipt, key.publicKey, envelope)),
  );
}
