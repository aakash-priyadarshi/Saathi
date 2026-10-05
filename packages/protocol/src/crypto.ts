import { z } from 'zod';
import canonicalize from 'canonicalize';
export const publicKeySchema = z
  .object({
    kty: z.literal('EC'),
    crv: z.literal('P-256'),
    x: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
    y: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
  })
  .strict();
export type PublicKey = z.infer<typeof publicKeySchema>;
export function bytes(value: unknown) {
  const encoded = canonicalize(value);
  if (encoded === undefined) throw new Error('The signed value must be valid JSON.');
  return new TextEncoder().encode(encoded);
}
export function base64(data: ArrayBuffer | Uint8Array) {
  const array = data instanceof Uint8Array ? data : new Uint8Array(data);
  return btoa(Array.from(array, (x) => String.fromCharCode(x)).join(''))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}
export function unbase64(value: string) {
  return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
}
export async function hash(value: unknown) {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes(value))), (x) =>
    x.toString(16).padStart(2, '0'),
  ).join('');
}
export async function generateKeys() {
  return crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, [
    'sign',
    'verify',
  ]) as Promise<CryptoKeyPair>;
}
export async function exportPublic(key: CryptoKey): Promise<PublicKey> {
  const jwk = await crypto.subtle.exportKey('jwk', key);
  return publicKeySchema.parse({ kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y });
}
export async function sign(value: unknown, key: CryptoKey) {
  return base64(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, bytes(value)));
}
export async function verify(value: unknown, signature: string, publicKey: PublicKey) {
  try {
    const key = await crypto.subtle.importKey(
      'jwk',
      publicKeySchema.parse(publicKey),
      { name: 'ECDSA', namedCurve: 'P-256' },
      false,
      ['verify'],
    );
    return await crypto.subtle.verify(
      { name: 'ECDSA', hash: 'SHA-256' },
      key,
      unbase64(signature),
      bytes(value),
    );
  } catch {
    return false;
  }
}
