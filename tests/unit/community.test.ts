import { describe, it, expect } from 'vitest';
import { chatPerson } from '../chat-fixtures';
import { communityEvent, helpPayload } from '../community-fixtures';
import {
  communityEnvelopeSchema,
  validCommunityEnvelope,
  hash,
  sign,
} from '../../packages/protocol/src';
describe('Public participant statement boundary', () => {
  it('binds original author, immutable payload, expiry and help creation identity', async () => {
    const author = await chatPerson('Fictional participant'),
      other = await chatPerson('Fictional carrier');
    const e = await communityEvent(author, 'HELP', {
      version: 1,
      previousHash: null,
      help: helpPayload(),
    });
    expect(await validCommunityEnvelope(e)).toBe(true);
    expect(
      await validCommunityEnvelope({
        ...e,
        signature: await sign(e.body, other.signing.privateKey),
      }),
    ).toBe(false);
    const forged = structuredClone(e);
    if (forged.body.type !== 'HELP') throw new Error();
    forged.body.payload.help.quantity = 99;
    expect(await validCommunityEnvelope(forged)).toBe(false);
    expect(await validCommunityEnvelope(e, Date.parse(e.body.expiresAt))).toBe(false);
  });
  it('rejects private contact fields and invalid media profiles without creating verified authority', async () => {
    const author = await chatPerson('Fictional author');
    const e = await communityEvent(author, 'REPORT', {
      caption: 'Road blocked near the public gate',
      area: 'Fictional Gate 2',
      contentWarning: false,
      media: null,
    });
    expect(await validCommunityEnvelope(e)).toBe(true);
    expect(JSON.stringify(e)).not.toContain('organizationId');
    for (const text of [
      'Contact me at a@example.org',
      'Call +91 9876543210',
      '18.12345, 73.54321',
    ]) {
      const clone = structuredClone(e);
      if (clone.body.type !== 'REPORT') throw new Error();
      clone.body.payload.area = text;
      expect(communityEnvelopeSchema.safeParse(clone).success).toBe(false);
    }
    const clone = structuredClone(e);
    if (clone.body.type !== 'REPORT') throw new Error();
    clone.body.payloadHash = await hash({ caption: 'changed' });
    expect(await validCommunityEnvelope(clone)).toBe(false);
  });
});
