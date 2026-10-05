import { randomBytes, scrypt as scryptCallback, timingSafeEqual, createHash } from 'node:crypto';
import { TOTP, Secret } from 'otpauth';
const scrypt = (password: string, salt: string, strong = true) =>
  new Promise<Buffer>((resolve, reject) =>
    scryptCallback(
      password,
      salt,
      64,
      { N: strong ? 131072 : 16384, r: 8, p: 1, maxmem: 192 * 1024 * 1024 },
      (error, result) => {
        if (error) reject(error);
        else resolve(result);
      },
    ),
  );
export const token = () => randomBytes(32).toString('base64url');
export const digest = (value: string) => createHash('sha256').update(value).digest('hex');
export async function hashPassword(password: string) {
  const salt = randomBytes(16).toString('hex');
  const hash = await scrypt(password, salt);
  return `scrypt-v1:${salt}:${hash.toString('hex')}`;
}
export async function verifyPassword(password: string, stored: string) {
  const [algorithm, salt, hash] = stored.split(':');
  if (!['scrypt', 'scrypt-v1'].includes(algorithm ?? '') || !salt || !hash) return false;
  const actual = await scrypt(password, salt, algorithm === 'scrypt-v1');
  const expected = Buffer.from(hash, 'hex');
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}
export function verifyTotp(secret: string, code: string) {
  return (
    new TOTP({
      secret: Secret.fromBase32(secret),
      algorithm: 'SHA1',
      digits: 6,
      period: 30,
    }).validate({ token: code, window: 1 }) !== null
  );
}
