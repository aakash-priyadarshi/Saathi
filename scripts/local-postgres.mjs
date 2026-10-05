import EmbeddedPostgres from 'embedded-postgres';
import { resolve } from 'node:path';
import { existsSync } from 'node:fs';
import { setInterval } from 'node:timers';
const directory = resolve('.data/postgres');
const pg = new EmbeddedPostgres({
  databaseDir: directory,
  user: 'saathi',
  password: 'saathi_local',
  port: 5432,
  persistent: true,
  authMethod: 'scram-sha-256',
  postgresFlags: ['-h', '127.0.0.1'],
  onLog: () => {},
  onError: (message) => console.error(String(message)),
});
if (!existsSync(resolve(directory, 'PG_VERSION'))) await pg.initialise();
await pg.start();
const client = pg.getPgClient();
await client.connect();
const exists = await client.query("SELECT 1 FROM pg_database WHERE datname='saathi'");
await client.end();
if (!exists.rowCount) await pg.createDatabase('saathi');
console.log(
  'Saathi local PostgreSQL is running on 127.0.0.1:5432. Ctrl+C stops it; data persists in .data/postgres.',
);
async function stop() {
  await pg.stop();
  process.exit(0);
}
process.on('SIGINT', () => void stop());
process.on('SIGTERM', () => void stop());
setInterval(() => {}, 60000);
