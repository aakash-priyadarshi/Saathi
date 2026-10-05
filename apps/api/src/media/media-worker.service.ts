import { randomUUID } from 'node:crypto';
import { Logger } from '@nestjs/common';
import { Database } from '../database';
import { MediaService } from './media.service';
import { S3Storage } from './storage';

/** PostgreSQL is the durable queue. Replicas claim one bounded job, outside HTTP processes. */
export class MediaWorker {
  private readonly log = new Logger('MediaWorker');
  constructor(
    private readonly db: Database,
    private readonly media: MediaService,
  ) {}
  async claim() {
    const lease = randomUUID();
    return this.db.atomic(async (tx) => {
      const rows = await tx.$queryRaw<{ id: string }[]>`
        SELECT id FROM "MediaAsset"
        WHERE "processingAttempts" < 3 AND
          ("processingState"='PENDING' OR ("processingState"='PROCESSING' AND "processingStartedAt" < (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - INTERVAL '10 minutes'))
        ORDER BY "createdAt" FOR UPDATE SKIP LOCKED LIMIT 1`;
      if (!rows[0]) return null;
      return tx.mediaAsset.update({
        where: { id: rows[0].id },
        data: {
          processingState: 'PROCESSING',
          processingLease: lease,
          processingStartedAt: new Date(),
          processingAttempts: { increment: 1 },
        },
      });
    });
  }
  async runNext() {
    const asset = await this.claim();
    if (!asset) return false;
    const started = Date.now();
    try {
      await this.media.process(asset.id, asset.processingLease!);
      this.log.log(
        JSON.stringify({
          event: 'media.processed',
          assetId: asset.id,
          milliseconds: Date.now() - started,
        }),
      );
    } catch {
      await this.db.mediaAsset.updateMany({
        where: { id: asset.id, processingLease: asset.processingLease },
        data: { processingState: 'FAILED', processingLease: null, processingStartedAt: null },
      });
      this.log.warn(
        JSON.stringify({
          event: 'media.failed',
          assetId: asset.id,
          attempt: asset.processingAttempts,
        }),
      );
    }
    return true;
  }
  async reconcile() {
    // Exhausted interrupted jobs remain visible for owner review, rather than processing forever.
    return this.db.mediaAsset.updateMany({
      where: {
        processingState: 'PROCESSING',
        processingAttempts: { gte: 3 },
        processingStartedAt: { lt: new Date(Date.now() - 10 * 60 * 1000) },
      },
      data: { processingState: 'FAILED', processingLease: null, processingStartedAt: null },
    });
  }
  async reconcilePublic(storage: S3Storage) {
    // Object operations cannot commit atomically with PostgreSQL. Repair desired visibility.
    let cursor: string | undefined;
    while (true) {
      const assets = await this.db.mediaAsset.findMany({
        where: { publicKey: { not: null } },
        orderBy: { id: 'asc' },
        take: 100,
        ...(cursor ? { cursor: { id: cursor }, skip: 1 } : {}),
      });
      for (const asset of assets) {
        await this.db.atomic(async (tx) => {
          await tx.$queryRaw`SELECT id FROM "MediaAsset" WHERE id=${asset.id} FOR UPDATE`;
          const current = await tx.mediaAsset.findUniqueOrThrow({ where: { id: asset.id } });
          const publish = current.moderation === 'APPROVED' && current.processingState === 'READY';
          for (const [key, mime] of [
            [current.publicKey, current.mimeType],
            [current.thumbnailKey, 'image/jpeg'],
          ] as const)
            if (key) {
              if (publish) await storage.publish(key, mime);
              else await storage.hide(key);
            }
        });
      }
      if (assets.length < 100) break;
      cursor = assets[assets.length - 1]!.id;
    }
  }
}
