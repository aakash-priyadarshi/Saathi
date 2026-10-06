import { config } from 'dotenv';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
config({ quiet: true });
const url = new URL(process.env.DATABASE_URL);
if (!['localhost', '127.0.0.1'].includes(url.hostname))
  throw new Error('Integration runner only accepts a local database.');
url.searchParams.set('schema', 'saathi_test');
const env = {
  ...process.env,
  DATABASE_URL: url.toString(),
  NODE_ENV: 'test',
  DEMO_MODE: 'true',
  STORAGE_PROVIDER: 'local',
};
function run(args) {
  if (!process.env.npm_execpath) throw new Error('Run this runner through pnpm test:integration.');
  const result = spawnSync(process.execPath, [process.env.npm_execpath, ...args], {
    stdio: 'inherit',
    env,
  });
  if (result.status !== 0) process.exit(result.status ?? 1);
}
run(['build:packages']);
const migration = spawnSync(
  process.execPath,
  [resolve('packages/database/node_modules/prisma/build/index.js'), 'migrate', 'deploy', '--schema', 'prisma/schema.prisma'],
  { cwd: resolve('packages/database'), stdio: 'inherit', env },
);
if (migration.status !== 0) process.exit(migration.status ?? 1);
run(['exec', 'vitest', 'run', '--config', 'vitest.integration.config.ts']);
