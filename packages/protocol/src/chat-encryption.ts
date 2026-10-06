import { CompactEncrypt, compactDecrypt, decodeProtectedHeader, importJWK, type JWK } from 'jose';
import { bytes, publicKeySchema } from './crypto';
export function validChatJweHeader(value: string, algorithm: 'dir' | 'ECDH-ES', kid: string) {
  if (value.length > 22000 || value.split('.').length !== 5)
    throw new Error('Invalid encrypted chat payload.');
  const header = decodeProtectedHeader(value);
  if (
    header.alg !== algorithm ||
    header.enc !== 'A256GCM' ||
    header.typ !== 'SWARM_CHAT_V1' ||
    header.kid !== kid ||
    Object.keys(header).some((k) => !['alg', 'enc', 'typ', 'kid', 'epk'].includes(k))
  )
    throw new Error('Encrypted chat context does not match.');
  if (algorithm === 'ECDH-ES') publicKeySchema.parse(header.epk);
  else if (header.epk) throw new Error('Invalid symmetric chat payload.');
}

/** RFC 7516/7518 via JOSE; no custom key agreement/KDF or forward-secrecy claim. */
export async function encryptChatValue(value: unknown, key: JWK | Uint8Array, kid: string) {
  const symmetric = key instanceof Uint8Array;
  const encryptionKey = symmetric ? key : await importJWK(key, 'ECDH-ES');
  return new CompactEncrypt(bytes(value))
    .setProtectedHeader({
      alg: symmetric ? 'dir' : 'ECDH-ES',
      enc: 'A256GCM',
      typ: 'SWARM_CHAT_V1',
      kid,
    })
    .encrypt(encryptionKey);
}
export async function decryptChatValue(
  value: string,
  key: JWK | Uint8Array,
  kid: string,
): Promise<unknown> {
  if (value.length > 22000) throw new Error('Encrypted chat payload is too large.');
  const symmetric = key instanceof Uint8Array;
  validChatJweHeader(value, symmetric ? 'dir' : 'ECDH-ES', kid);
  const encryptionKey = symmetric ? key : await importJWK(key, 'ECDH-ES');
  const { plaintext, protectedHeader } = await compactDecrypt(value, encryptionKey, {
    keyManagementAlgorithms: [symmetric ? 'dir' : 'ECDH-ES'],
    contentEncryptionAlgorithms: ['A256GCM'],
  });
  if (
    protectedHeader.kid !== kid ||
    protectedHeader.typ !== 'SWARM_CHAT_V1' ||
    protectedHeader.zip ||
    plaintext.length > 16000
  )
    throw new Error('Encrypted chat context does not match.');
  return JSON.parse(new TextDecoder().decode(plaintext));
}
