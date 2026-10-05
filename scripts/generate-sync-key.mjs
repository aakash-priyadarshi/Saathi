import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { webcrypto } from 'node:crypto';
const target = resolve(process.argv[2] ?? '.data/production-sync-key.json');
const keys = await webcrypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, [
  'sign',
  'verify',
]);
await mkdir(dirname(target), { recursive: true });
await writeFile(target, JSON.stringify(await webcrypto.subtle.exportKey('jwk', keys.privateKey)), {
  flag: 'wx',
  mode: 0o600,
});
console.log(
  `Signing key saved at ${target}. Load its contents into the deployment secret SYNC_SIGNING_PRIVATE_JWK. The private key was not printed. Keep this file private and backed up.`,
);
