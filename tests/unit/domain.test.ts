import { describe, it, expect } from 'vitest';
import { deriveStatus, assertQuantities } from '../../apps/api/src/domain/request';
import { hashPassword, verifyPassword, digest, token } from '../../packages/auth/src';
describe('Request lifecycle', () => {
  it('derives open, partial, fully committed, received and completed states', () => {
    expect(deriveStatus(100, 0, 0, 'OPEN')).toBe('OPEN');
    expect(deriveStatus(100, 40, 0, 'OPEN')).toBe('PARTIALLY_COMMITTED');
    expect(deriveStatus(100, 100, 0, 'OPEN')).toBe('FULLY_COMMITTED');
    expect(deriveStatus(100, 100, 20, 'OPEN')).toBe('PARTIALLY_RECEIVED');
    expect(deriveStatus(100, 100, 100, 'OPEN')).toBe('COMPLETED');
  });
  it.each(['COMPLETED', 'CANCELLED', 'EXPIRED'] as const)(
    'never reopens a terminal %s request',
    (status) => {
      expect(deriveStatus(100, 0, 0, status)).toBe(status);
    },
  );
  it('rejects over-allocation and inconsistent counters', () => {
    expect(() => assertQuantities(100, 101, 0)).toThrow();
    expect(() => assertQuantities(100, 40, 50)).toThrow();
    expect(() => assertQuantities(100, 40, 30)).not.toThrow();
  });
});
describe('Credentials', () => {
  it('hashes with a fresh salt and verifies without exposing the password', async () => {
    const a = await hashPassword('a-strong-password'),
      b = await hashPassword('a-strong-password');
    expect(a).not.toBe(b);
    expect(await verifyPassword('a-strong-password', a)).toBe(true);
    expect(await verifyPassword('wrong-password', a)).toBe(false);
  });
  it('generates unguessable tracking tokens and stable hashes', () => {
    const a = token(),
      b = token();
    expect(a.length).toBeGreaterThanOrEqual(43);
    expect(a).not.toBe(b);
    expect(digest(a)).toHaveLength(64);
  });
});
