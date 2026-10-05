import 'reflect-metadata';
import { env } from '@saathi/config';
import { Database } from '../database';
import { AuthService } from '../auth/auth.service';
import { S3Storage } from './storage';
import { MediaService } from './media.service';
import { MediaWorker } from './media-worker.service';
import { setTimeout } from 'node:timers/promises';

async function main() {
  if (env.SERVICE_ROLE !== 'media-worker' || env.MEDIA_PROCESSING_MODE !== 'worker')
    throw new Error(
      'Standalone media worker requires its explicit role and worker processing mode.',
    );
  const db = new Database(),
    storage = new S3Storage();
  const worker = new MediaWorker(db, new MediaService(db, new AuthService(db), storage));
  let stopping = false;
  process.once('SIGTERM', () => {
    stopping = true;
  });
  process.once('SIGINT', () => {
    stopping = true;
  });
  await db.$connect();
  await storage.initializeLocal();
  let reconciled = 0;
  try {
    while (!stopping) {
      await worker.reconcile();
      if (Date.now() - reconciled > 60000) {
        await worker.reconcilePublic(storage);
        reconciled = Date.now();
      }
      if (!(await worker.runNext())) await setTimeout(2000);
    }
  } finally {
    await db.$disconnect();
  }
}
void main().catch(() => {
  console.error('Media worker stopped; inspect health and provider connectivity.');
  process.exitCode = 1;
});
