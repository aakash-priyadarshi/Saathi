import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { generateKeys, exportPublic, hash, sign } = require('../packages/protocol/dist');
const root = await generateKeys(),
  author = await generateKeys(),
  receipt = await generateKeys();
const now = '2026-10-05T10:00:00.000Z';
const publicRoot = await exportPublic(root.publicKey),
  receiptPublic = await exportPublic(receipt.publicKey);
const keys = [
  {
    keyId: await hash(receiptPublic),
    publicKey: receiptPublic,
    status: 'ACTIVE',
    signingFrom: '2026-10-01T00:00:00.000Z',
    signingUntil: '2026-12-01T00:00:00.000Z',
    verifyUntil: '2027-10-01T00:00:00.000Z',
  },
];
const body = {
  format: 'saathi-service-config-v1',
  environment: 'staging',
  version: 7,
  issuedAt: now,
  expiresAt: '2026-11-01T00:00:00.000Z',
  protocolVersions: [1],
  minimumAndroidVersionCode: 1,
  apiEndpoints: ['https://stage.example.org'],
  publicUrl: 'https://www.example.org',
  webOrigin: 'https://www.example.org',
  receiptKeys: keys,
  features: { nearby: true, localCalls: true, largeFiles: true },
};
const configuration = {
  body,
  rootKeyId: await hash(publicRoot),
  signature: await sign(body, root.privateKey),
};
const payload = {
  reliefPointId: randomUUID(),
  category: 'WATER',
  title: 'Public interoperability fixture',
  description: 'Fictional signed need, no personal data.',
  requestedQuantity: 15,
  unit: 'bottles',
  priority: 'URGENT',
  deadline: '2026-10-06T10:00:00.000Z',
};
const eventBody = {
  id: randomUUID(),
  protocolVersion: 1,
  authorId: randomUUID(),
  organizationId: randomUUID(),
  deviceId: randomUUID(),
  createdAt: now,
  expiresAt: '2026-10-06T10:00:00.000Z',
  intent: 'PUBLIC_RELIEF',
  maxHops: 6,
  type: 'REQUEST_CREATED',
  payload,
  payloadHash: await hash(payload),
};
const envelope = {
  body: eventBody,
  publicKey: await exportPublic(author.publicKey),
  signature: await sign(eventBody, author.privateKey),
};
const receiptBody = {
  eventId: eventBody.id,
  payloadHash: eventBody.payloadHash,
  signatureHash: await hash(envelope.signature),
  keyId: keys[0].keyId,
  status: 'PUBLISHED',
  recordedAt: '2026-10-05T10:00:01.000Z',
  message: 'Fixture published',
  publicId: 'SAA-TEST1234',
};
const signedReceipt = { body: receiptBody, signature: await sign(receiptBody, receipt.privateKey) };
const directory = 'apps/android/app/src/test/resources';
await mkdir(directory, { recursive: true });
await writeFile(
  directory + '/protocol-vectors.json',
  JSON.stringify(
    {
      now,
      root: publicRoot,
      configuration,
      envelope,
      receipt: signedReceipt,
      stringHash: await hash('canonical string ✓'),
    },
    null,
    2,
  ) + '\n',
);
console.log('Wrote public-only interoperability vectors. Ephemeral private keys were discarded.');
