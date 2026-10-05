import { describe, it, expect } from 'vitest';
import { randomUUID } from 'node:crypto';
import {
  generateKeys,
  createEnvelope,
  validEnvelope,
  exportPublic,
  sign,
  hash,
  validReceipt,
  choosePath,
  type Receipt,
} from '../../packages/protocol/src';
function input() {
  return {
    authorId: randomUUID(),
    deviceId: randomUUID(),
    organizationId: randomUUID(),
    type: 'REQUEST_CREATED' as const,
    payload: {
      reliefPointId: randomUUID(),
      category: 'WATER' as const,
      title: 'Offline drinking water',
      description: 'Sealed drinking water for a public relief point.',
      requestedQuantity: 10,
      unit: 'bottles',
      priority: 'URGENT' as const,
      deadline: new Date(Date.now() + 3600000).toISOString(),
    },
  };
}
describe('Transport-independent signed relief events', () => {
  it('uses non-extractable private keys and verifies canonical key-order-independent content', async () => {
    const keys = await generateKeys(),
      envelope = await createEnvelope(input(), keys);
    expect(keys.privateKey.extractable).toBe(false);
    expect(await validEnvelope(envelope)).toBe(true);
    expect(await hash({ b: 2, a: 1 })).toBe(await hash({ a: 1, b: 2 }));
    await expect(crypto.subtle.exportKey('jwk', keys.privateKey)).rejects.toThrow();
  });
  it('rejects payload, author, expiry, hop-limit and public-key alterations', async () => {
    const envelope = await createEnvelope(input(), await generateKeys());
    expect(
      await validEnvelope({ ...envelope, body: { ...envelope.body, authorId: randomUUID() } }),
    ).toBe(false);
    expect(await validEnvelope({ ...envelope, body: { ...envelope.body, maxHops: 8 } })).toBe(
      false,
    );
    expect(
      await validEnvelope({
        ...envelope,
        body: { ...envelope.body, expiresAt: new Date().toISOString() },
      }),
    ).toBe(false);
    expect(
      await validEnvelope({
        ...envelope,
        publicKey: await exportPublic((await generateKeys()).publicKey),
      }),
    ).toBe(false);
  });
  it('accepts only a receipt bound to the original envelope and pinned server key', async () => {
    const envelope = await createEnvelope(input(), await generateKeys()),
      keys = await generateKeys(),
      publicKey = await exportPublic(keys.publicKey);
    const body: Receipt['body'] = {
      eventId: envelope.body.id,
      payloadHash: envelope.body.payloadHash,
      signatureHash: await hash(envelope.signature),
      status: 'PUBLISHED',
      recordedAt: new Date().toISOString(),
      message: 'Published',
      keyId: await hash(publicKey),
    };
    const receipt = { body, signature: await sign(body, keys.privateKey) };
    expect(await validReceipt(receipt, publicKey, envelope)).toBe(true);
    expect(
      await validReceipt(
        { ...receipt, body: { ...body, status: 'REJECTED' } },
        publicKey,
        envelope,
      ),
    ).toBe(false);
    expect(
      await validReceipt(receipt, await exportPublic((await generateKeys()).publicKey), envelope),
    ).toBe(false);
  });
  it('never allocates supplies offline or sends calls through an internet gateway', () => {
    const offline = {
      internet: false,
      nearby: true,
      media: true,
      storage: true,
      lowBandwidth: true,
    };
    expect(choosePath('AUTHORITATIVE_WRITE', offline)).toBe('UNAVAILABLE');
    expect(choosePath('EVENT', offline)).toBe('NEARBY');
    expect(choosePath('VIDEO', offline)).toBe('UNAVAILABLE');
    expect(choosePath('VOICE', offline)).toBe('NEARBY');
    expect(choosePath('FILE', offline)).toBe('LOCAL');
    expect(choosePath('VOICE', { ...offline, internet: true, nearby: false })).toBe('UNAVAILABLE');
  });
});
