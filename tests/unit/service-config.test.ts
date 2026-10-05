import { describe, it, expect } from 'vitest';
import { randomUUID } from 'node:crypto';
import {
  generateKeys,
  exportPublic,
  hash,
  sign,
  createEnvelope,
  verifyServiceConfig,
  validReceiptWithKeyring,
  type ServiceConfig,
  type ReceiptKeyPolicy,
  type Receipt,
} from '../../packages/protocol/src';
const now = Date.parse('2026-10-05T12:00:00Z');
const date = (offset: number) => new Date(now + offset).toISOString();
async function fixture() {
  const root = await generateKeys(),
    signer = await generateKeys(),
    publicKey = await exportPublic(signer.publicKey);
  const key: ReceiptKeyPolicy = {
    keyId: await hash(publicKey),
    publicKey,
    status: 'ACTIVE',
    signingFrom: date(-86400000),
    signingUntil: date(86400000),
    verifyUntil: date(365 * 86400000),
  };
  const body: ServiceConfig = {
    format: 'saathi-service-config-v1',
    environment: 'staging',
    version: 3,
    issuedAt: date(-1000),
    expiresAt: date(86400000),
    protocolVersions: [1],
    minimumAndroidVersionCode: 1,
    apiEndpoints: ['https://ops.example.test/api/v1'],
    publicUrl: 'https://public.example.test',
    webOrigin: 'https://public.example.test',
    receiptKeys: [key],
    features: { nearby: true, localCalls: true, largeFiles: false },
  };
  const publicRoot = await exportPublic(root.publicKey);
  const signed = async (changed: ServiceConfig = body) => ({
    body: changed,
    rootKeyId: await hash(publicRoot),
    signature: await sign(changed, root.privateKey),
  });
  return {
    root,
    publicRoot,
    signer,
    key,
    body,
    signed,
    policy: { environment: 'staging' as const, minimumVersion: 3, androidVersionCode: 1, now },
  };
}
describe('Authenticated service configuration', () => {
  it('accepts signed endpoint migration and rejects forged, unsigned, wrong-environment and incompatible configurations', async () => {
    const f = await fixture(),
      c = await f.signed();
    expect((await verifyServiceConfig(c, f.publicRoot, f.policy)).body.version).toBe(3);
    const next = await f.signed({
      ...f.body,
      version: 4,
      apiEndpoints: ['https://replacement.example.test/api/v1'],
    });
    expect(
      (await verifyServiceConfig(next, f.publicRoot, { ...f.policy, previous: c })).body
        .apiEndpoints[0],
    ).toContain('replacement');
    await expect(
      verifyServiceConfig(
        { ...c, body: { ...c.body, apiEndpoints: ['https://attacker.test'] } },
        f.publicRoot,
        f.policy,
      ),
    ).rejects.toThrow('signature');
    await expect(verifyServiceConfig({ body: c.body }, f.publicRoot, f.policy)).rejects.toThrow();
    await expect(
      verifyServiceConfig(
        await f.signed({ ...f.body, environment: 'production' }),
        f.publicRoot,
        f.policy,
      ),
    ).rejects.toThrow('environment');
    await expect(
      verifyServiceConfig(
        await f.signed({ ...f.body, minimumAndroidVersionCode: 2 }),
        f.publicRoot,
        f.policy,
      ),
    ).rejects.toThrow('compatibility');
  });
  it('rejects rollback, same-version equivocation, expiry, clock skew and unsafe URL credentials', async () => {
    const f = await fixture(),
      previous = await f.signed();
    for (const body of [
      { ...f.body, version: 2 },
      { ...f.body, expiresAt: date(-1) },
      { ...f.body, issuedAt: date(600000) },
      { ...f.body, apiEndpoints: ['https://user:password@example.test'] },
      { ...f.body, apiEndpoints: ['http://example.test'] },
      { ...f.body, apiEndpoints: ['https://example.test/?token=not-allowed'] },
    ])
      await expect(
        verifyServiceConfig(await f.signed(body), f.publicRoot, f.policy),
      ).rejects.toThrow();
    await expect(
      verifyServiceConfig(
        await f.signed({ ...f.body, apiEndpoints: ['https://changed.example.test'] }),
        f.publicRoot,
        { ...f.policy, previous },
      ),
    ).rejects.toThrow('contents');
  });
  it('allows only explicit non-production loopback HTTP, and rejects misidentified receipt keys', async () => {
    const f = await fixture(),
      body = {
        ...f.body,
        environment: 'development' as const,
        apiEndpoints: ['http://127.0.0.1:4001/api/v1'],
      };
    const c = await f.signed(body);
    await expect(
      verifyServiceConfig(c, f.publicRoot, { ...f.policy, environment: 'development' }),
    ).rejects.toThrow('HTTPS');
    expect(
      await verifyServiceConfig(c, f.publicRoot, {
        ...f.policy,
        environment: 'development',
        allowLoopbackHttp: true,
      }),
    ).toBeTruthy();
    await expect(
      verifyServiceConfig(
        await f.signed({ ...f.body, receiptKeys: [{ ...f.key, keyId: '0'.repeat(64) }] }),
        f.publicRoot,
        f.policy,
      ),
    ).rejects.toThrow('does not match');
  });
});
describe('Receipt key lifecycle', () => {
  it('verifies old receipts during retirement, fails closed on revocation, and rejects signatures outside the signing window', async () => {
    const f = await fixture();
    const envelope = await createEnvelope(
      {
        authorId: randomUUID(),
        deviceId: randomUUID(),
        organizationId: randomUUID(),
        type: 'FIELD_PUBLISHED',
        payload: {
          caption: 'Synthetic public relief update',
          reliefPointId: randomUUID(),
          mediaIds: [],
        },
      },
      await generateKeys(),
    );
    const body: Receipt['body'] = {
      eventId: envelope.body.id,
      payloadHash: envelope.body.payloadHash,
      signatureHash: await hash(envelope.signature),
      status: 'PUBLISHED',
      recordedAt: date(-1000),
      message: 'Published',
      keyId: f.key.keyId,
    };
    const receipt = { body, signature: await sign(body, f.signer.privateKey) };
    expect(
      await validReceiptWithKeyring(
        receipt,
        [{ ...f.key, status: 'RETIRED', signingUntil: date(0) }],
        envelope,
        now + 86400000,
      ),
    ).toBe(true);
    expect(
      await validReceiptWithKeyring(receipt, [{ ...f.key, status: 'REVOKED' }], envelope, now),
    ).toBe(false);
    expect(
      await validReceiptWithKeyring(receipt, [{ ...f.key, signingFrom: date(0) }], envelope, now),
    ).toBe(false);
    expect(await validReceiptWithKeyring(receipt, [f.key], envelope, now + 366 * 86400000)).toBe(
      false,
    );
    expect(
      await validReceiptWithKeyring(
        { ...receipt, body: { ...body, status: 'ACCEPTED' } },
        [f.key],
        envelope,
        now,
      ),
    ).toBe(false);
  });
});
