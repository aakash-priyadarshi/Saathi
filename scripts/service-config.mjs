import { generateKeyPairSync, webcrypto } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const {
  sign,
  hash,
  serviceConfigBodySchema,
  verifyServiceConfig,
} = require('../packages/protocol/dist');
const [command, environment, directory, endpoint, versionArg = '1'] = process.argv.slice(2);
if (!['development', 'staging', 'production'].includes(environment) || !directory)
  throw new Error(
    'Usage: node scripts/service-config.mjs init|sign|check ENV PRIVATE_DIRECTORY [HTTPS_ENDPOINT] [VERSION]',
  );
const location = resolve(directory);
const save = (name, value) =>
  writeFile(resolve(location, name), JSON.stringify(value, null, 2) + '\n', {
    mode: 0o600,
    flag: command === 'init' ? 'wx' : 'w',
  });
const load = async (name) => JSON.parse(await readFile(resolve(location, name), 'utf8'));
if (command === 'init') {
  if (environment === 'production')
    throw new Error(
      'Production keys must be provisioned by the designated operator in their secret-management ceremony. This helper creates only local/staging keys.',
    );
  await mkdir(location, { recursive: true });
  for (const name of ['configuration-root', 'receipt-signer']) {
    const pair = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
    await save(name + '.private.json', pair.privateKey.export({ format: 'jwk' }));
    await save(name + '.public.json', pair.publicKey.export({ format: 'jwk' }));
  }
  console.log(
    'Created isolated non-production keys. Keep this directory private and back up the configuration root offline.',
  );
} else if (command === 'sign') {
  const rootPrivate = await load('configuration-root.private.json');
  const publicKey = await load('receipt-signer.public.json');
  const root = await load('configuration-root.public.json');
  const now = new Date();
  let keyring;
  try {
    keyring = await load('receipt-keyring.json');
  } catch (e) {
    if (e.code !== 'ENOENT') throw e;
    keyring = [
      {
        keyId: await hash(publicKey),
        publicKey,
        status: 'ACTIVE',
        signingFrom: new Date(now.getTime() - 86400000).toISOString(),
        signingUntil: new Date(now.getTime() + 90 * 86400000).toISOString(),
        verifyUntil: new Date(now.getTime() + 365 * 86400000).toISOString(),
      },
    ];
  }
  const body = serviceConfigBodySchema.parse({
    format: 'saathi-service-config-v1',
    environment,
    version: Number(versionArg),
    issuedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + 30 * 86400000).toISOString(),
    protocolVersions: [1],
    minimumAndroidVersionCode: 1,
    apiEndpoints: [endpoint],
    publicUrl: process.env.SAATHI_PUBLIC_ADDRESS || endpoint,
    webOrigin: process.env.SAATHI_BROWSER_ORIGIN || endpoint,
    receiptKeys: keyring,
    features: { nearby: true, localCalls: true, largeFiles: true },
  });
  const key = await webcrypto.subtle.importKey(
    'jwk',
    rootPrivate,
    { name: 'ECDSA', namedCurve: 'P-256' },
    false,
    ['sign'],
  );
  const signed = { body, rootKeyId: await hash(root), signature: await sign(body, key) };
  await verifyServiceConfig(signed, root, {
    environment,
    androidVersionCode: 1,
    minimumVersion: 1,
    allowLoopbackHttp: environment === 'development',
  });
  await save('receipt-keyring.json', keyring);
  await save('service-config.json', signed);
  // Only the receipt private key goes to the API's managed secrets. The configuration root stays offline.
  const settings = {
    APP_ENV: environment,
    SYNC_SIGNING_PRIVATE_JWK: JSON.stringify(await load('receipt-signer.private.json')),
    SYNC_RECEIPT_KEYRING_JSON: JSON.stringify(keyring),
    SERVICE_CONFIG_JSON: JSON.stringify(signed),
    SERVICE_CONFIG_ROOT_PUBLIC_JWK: JSON.stringify(root),
    WEB_ORIGIN: body.webOrigin,
    PUBLIC_URL: body.publicUrl,
  };
  await save('api-secrets.private.json', settings);
  console.log(
    `Signed ${environment} configuration version ${body.version}. API secret bundle saved privately; no secret values printed.`,
  );
} else if (command === 'check') {
  const signed = await load('service-config.json');
  await verifyServiceConfig(signed, await load('configuration-root.public.json'), {
    environment,
    androidVersionCode: Number.MAX_SAFE_INTEGER,
    minimumVersion: Number(versionArg),
    allowLoopbackHttp: environment === 'development',
  });
  console.log('Service configuration verified.');
} else throw new Error('Unknown service configuration command.');
