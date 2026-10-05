import 'reflect-metadata';
import { env } from '@saathi/config';
import { Database } from '../database';
import { AuthService } from '../auth/auth.service';
import { S3Storage } from './storage';
import { MediaService } from './media.service';
import { MediaWorker } from './media-worker.service';

async function main() {
  if (env.SERVICE_ROLE !== 'media-worker' || env.MEDIA_PROCESSING_MODE !== 'worker')
    throw new Error('Media inventory requires the isolated media-worker environment.');
  const args = process.argv.slice(2);
  if (args.some((value) => value !== '--prune') || args.length > 1)
    throw new Error('Use no argument for dry-run, or --prune.');
  const db = new Database(),
    storage = new S3Storage();
  try {
    await db.$connect();
    const worker = new MediaWorker(db, new MediaService(db, new AuthService(db), storage));
    console.log(
      JSON.stringify({
        event: 'media.orphan-inventory',
        ...(await worker.reconcileOrphans(storage, args.includes('--prune'))),
      }),
    );
  } finally {
    await db.$disconnect();
  }
}
void main().catch(() => {
  console.error('Media inventory failed; inspect database/storage connectivity and permissions.');
  process.exitCode = 1;
});
