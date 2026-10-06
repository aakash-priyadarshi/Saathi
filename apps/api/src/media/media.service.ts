import {
  Injectable,
  BadRequestException,
  ConflictException,
  NotFoundException,
} from '@nestjs/common';
import sharp from 'sharp';
import { randomUUID } from 'node:crypto';
import { createConnection } from 'node:net';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdtemp, open, rm, stat } from 'node:fs/promises';
import { pipeline } from 'node:stream/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import ffmpeg from 'ffmpeg-static';
import { env } from '@saathi/config';
import { MAX_MEDIA_BYTES } from '@saathi/protocol';
import type { Prisma } from '@saathi/database';
import { Database, audit } from '../database';
import { AuthService, Actor } from '../auth/auth.service';
import { S3Storage } from './storage';
/** Resumable uploads arrive in parts so slow links never hit one request's timeout. */
export const UPLOAD_PART_BYTES = 8 * 1024 * 1024;
// ponytail: one global daily cap on guest storage; per-guest quotas need accounts or IP accounting.
const GUEST_DAILY_BYTES = 20 * 1024 * 1024 * 1024;
/** Longest a single sanitizing run may take; videos have no duration limit. */
export const MEDIA_PROCESSING_MS = 4 * 60 * 60 * 1000;
// ponytail: a hard output cap bounds worker disk; a re-encode this large fails instead of truncating.
const MAX_SANITIZED_VIDEO_BYTES = 1024 * 1024 * 1024;
export async function sanitizeImage(input: Buffer | string) {
  const image = sharp(input, { limitInputPixels: 40000000, failOn: 'warning' });
  const metadata = await image.metadata();
  if (!['jpeg', 'png', 'webp', 'heif'].includes(metadata.format ?? '') || (metadata.pages ?? 1) > 1)
    throw new BadRequestException('Use a still JPEG, PNG, or WebP photo.');
  // Sharp drops EXIF, GPS, ICC and device metadata unless explicitly retained.
  const safe = await image
    .rotate()
    .resize(1600, 1600, { fit: 'inside', withoutEnlargement: true })
    .jpeg({ quality: 84 })
    .toBuffer();
  const thumbnail = await sharp(safe)
    .resize(600, 600, { fit: 'inside', withoutEnlargement: true })
    .jpeg({ quality: 78 })
    .toBuffer();
  return { safe, thumbnail };
}
/** A file multer has already written to disk; it is removed once stored. */
export type UploadFile = { path: string; size: number };
export async function scan(file: string) {
  if (env.MEDIA_SCAN_PROVIDER === 'disabled') return;
  await new Promise<void>((resolve, reject) => {
    const socket = createConnection({ host: env.CLAMAV_HOST, port: env.CLAMAV_PORT });
    let response = '';
    socket.setTimeout(15000, () => socket.destroy(new Error('Malware scanner timed out')));
    socket.on('error', reject);
    socket.on('data', (d) => (response += d.toString()));
    socket.on('end', () =>
      response.includes('OK')
        ? resolve()
        : reject(new BadRequestException('File rejected by malware scanner.')),
    );
    socket.on('connect', async () => {
      try {
        socket.write('zINSTREAM\0');
        for await (const chunk of createReadStream(file, { highWaterMark: 65536 })) {
          const size = Buffer.alloc(4);
          size.writeUInt32BE((chunk as Buffer).length);
          socket.write(size);
          if (!socket.write(chunk)) await once(socket, 'drain');
        }
        socket.write(Buffer.alloc(4));
      } catch (error) {
        socket.destroy(error as Error);
      }
    });
  });
}
async function header(file: string) {
  const handle = await open(file, 'r');
  try {
    const bytes = Buffer.alloc(16);
    await handle.read(bytes, 0, 16, 0);
    return bytes;
  } finally {
    await handle.close();
  }
}
async function runFfmpeg(args: string[]) {
  await new Promise<void>((resolve, reject) => {
    const child = spawn(
      env.FFMPEG_PATH === 'ffmpeg' ? (ffmpeg ?? 'ffmpeg') : env.FFMPEG_PATH,
      args,
      { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] },
    );
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error('Video processing timed out'));
    }, MEDIA_PROCESSING_MS);
    child.stderr.resume(); // Decoder diagnostics can contain private media metadata.
    child.on('error', (e) => {
      clearTimeout(timer);
      reject(e);
    });
    child.on('exit', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve();
      else reject(new BadRequestException('Video could not be processed. Try an MP4 or WebM.'));
    });
  });
}
@Injectable()
export class MediaService {
  constructor(
    private readonly db: Database,
    private readonly auth: AuthService,
    private readonly storage: S3Storage,
  ) {}
  async upload(actor: Actor, organizationId: string, file: UploadFile) {
    this.auth.requireOrg(actor, organizationId);
    return this.store(actor.id, organizationId, file);
  }
  /** Guests have no account: the owner is a hash of a secret only the uploader holds. */
  async uploadGuest(ownerId: string, file: UploadFile) {
    return this.store(ownerId, null, file);
  }
  async beginUpload(ownerId: string, organizationId: string | null, size: number) {
    if (!Number.isInteger(size) || size < 1 || size > MAX_MEDIA_BYTES)
      throw new BadRequestException('Uploads must be 250 MB or smaller.');
    if (!organizationId) {
      const recent = await this.db.mediaAsset.aggregate({
        where: {
          ownerId: { startsWith: 'guest:' },
          createdAt: { gt: new Date(Date.now() - 864e5) },
        },
        _sum: { size: true },
      });
      if ((recent._sum.size ?? 0) + size > GUEST_DAILY_BYTES)
        throw new BadRequestException('Guest sharing is busy today. Try again tomorrow.');
    }
    const id = randomUUID();
    await this.db.mediaAsset.create({
      data: {
        id,
        ownerId,
        organizationId,
        originalKey: `original/${id}`,
        size,
        mimeType: 'application/octet-stream',
        processingState: 'RECEIVING',
      },
    });
    return {
      uploadId: id,
      partSize: UPLOAD_PART_BYTES,
      parts: Math.ceil(size / UPLOAD_PART_BYTES),
    };
  }
  /** The organization an upload belongs to, so callers can authenticate its owner. */
  async uploadOrganization(id: string) {
    const a = await this.db.mediaAsset.findUnique({ where: { id } });
    if (!a || a.processingState !== 'RECEIVING') throw new NotFoundException('Upload not found.');
    return a.organizationId;
  }
  private async receiving(id: string, ownerId: string) {
    const a = await this.db.mediaAsset.findUnique({ where: { id } });
    if (!a || a.ownerId !== ownerId || a.processingState !== 'RECEIVING')
      throw new NotFoundException('Upload not found.');
    return a;
  }
  private partKey = (id: string, index: number) => `original/${id}-${index}`;
  async putPart(id: string, ownerId: string, index: number, bytes: Buffer) {
    const a = await this.receiving(id, ownerId),
      count = Math.ceil(a.size / UPLOAD_PART_BYTES);
    if (
      !Number.isInteger(index) ||
      index < 0 ||
      index >= count ||
      bytes.length !== Math.min(UPLOAD_PART_BYTES, a.size - index * UPLOAD_PART_BYTES)
    )
      throw new BadRequestException('Invalid upload part.');
    await this.storage.initializeLocal();
    await this.storage.putPrivate(this.partKey(id, index), bytes, 'application/octet-stream');
    return { ok: true };
  }
  async completeUpload(id: string, ownerId: string) {
    const a = await this.receiving(id, ownerId),
      count = Math.ceil(a.size / UPLOAD_PART_BYTES);
    // Claim the upload so a duplicate completion cannot assemble it twice.
    const claimed = await this.db.mediaAsset.updateMany({
      where: { id, processingState: 'RECEIVING' },
      data: { processingState: 'ASSEMBLING' },
    });
    if (claimed.count !== 1) throw new ConflictException('This upload is already completing.');
    const dir = await mkdtemp(join(tmpdir(), 'saathi-upload-'));
    try {
      const file = join(dir, 'upload'),
        part = join(dir, 'part');
      for (let index = 0; index < count; index++) {
        await this.storage.downloadPrivate(this.partKey(id, index), part).catch(() => {
          throw new BadRequestException(`Part ${index} is missing. Upload it again.`);
        });
        await pipeline(createReadStream(part), createWriteStream(file, { flags: 'a' }));
      }
      if ((await stat(file)).size !== a.size)
        throw new BadRequestException('The upload is incomplete. Upload it again.');
      const result = await this.store(ownerId, a.organizationId, { path: file, size: a.size });
      for (let index = 0; index < count; index++)
        await this.storage.deletePrivate(this.partKey(id, index));
      await this.db.mediaAsset.delete({ where: { id } });
      return result;
    } catch (error) {
      await this.db.mediaAsset.updateMany({
        where: { id, processingState: 'ASSEMBLING' },
        data: { processingState: 'RECEIVING' },
      });
      throw error;
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  }
  private async store(ownerId: string, organizationId: string | null, file: UploadFile) {
    try {
      if (!file?.path || !file.size) throw new BadRequestException('Choose a photo or video.');
      if (file.size > MAX_MEDIA_BYTES)
        throw new BadRequestException('Uploads must be 250 MB or smaller.');
      await scan(file.path);
      const head = await header(file.path),
        isMp4 = head.subarray(4, 8).toString() === 'ftyp',
        isWebm = head.subarray(0, 4).equals(Buffer.from([0x1a, 0x45, 0xdf, 0xa3]));
      const video = isMp4 || isWebm;
      if (!video)
        await sharp(file.path, { limitInputPixels: 40000000 })
          .metadata()
          .catch(() => {
            throw new BadRequestException(
              'Unsupported file type. Choose a photo, MP4 or WebM video.',
            );
          });
      await this.storage.initializeLocal();
      const id = randomUUID(),
        originalKey = `original/${id}`;
      await this.storage.putPrivateFile(originalKey, file.path, 'application/octet-stream');
      const asset = await this.db
        .atomic(async (tx) => {
          const a = await tx.mediaAsset.create({
            data: {
              id,
              ownerId,
              organizationId,
              originalKey,
              size: file.size,
              mimeType: video ? 'video/mp4' : 'image/jpeg',
            },
          });
          await audit(
            tx,
            'MEDIA_UPLOADED',
            'MediaAsset',
            id,
            organizationId ? ownerId : undefined,
            undefined,
            { size: file.size, mimeType: a.mimeType },
          );
          return a;
        })
        .catch(async (error: unknown) => {
          await this.storage.deletePrivate(originalKey);
          throw error;
        });
      if (env.MEDIA_PROCESSING_MODE === 'worker')
        return { id, processingState: asset.processingState };
      // Long videos would outlast HTTP timeouts; inline mode finishes them in the background.
      // ponytail: a restart loses that background run; the retry endpoint or worker mode recovers it.
      if (video) {
        void this.process(id).catch(() =>
          this.db.mediaAsset.update({ where: { id }, data: { processingState: 'FAILED' } }),
        );
        return { id, processingState: 'PENDING' };
      }
      try {
        await this.process(asset.id);
      } catch (e) {
        await this.db.mediaAsset.update({ where: { id }, data: { processingState: 'FAILED' } });
        throw e;
      }
      return { id, processingState: 'READY' };
    } finally {
      if (file?.path) await rm(file.path, { force: true });
    }
  }
  async process(id: string, lease?: string) {
    const asset = await this.db.mediaAsset.findUniqueOrThrow({ where: { id } });
    const dir = await mkdtemp(join(tmpdir(), 'saathi-media-'));
    const base = `sanitized/${id}${lease ? '-' + lease : ''}`,
      publicKey =
        asset.mimeType === 'video/mp4'
          ? `${base}.mp4`
          : asset.mimeType === 'audio/mp4'
            ? `${base}.m4a`
            : `${base}.jpg`,
      thumbnailKey: string | null = asset.mimeType === 'audio/mp4' ? null : `${base}-thumb.jpg`;
    try {
      const input = join(dir, 'input');
      await this.storage.downloadPrivate(asset.originalKey, input);
      await scan(input);
      if (lease && asset.processingLease !== lease) throw new Error('Media lease changed.');
      if (asset.mimeType === 'image/jpeg') {
        const { safe, thumbnail } = await sanitizeImage(input);
        await this.storage.putPrivate(publicKey, safe, 'image/jpeg');
        await this.storage.putPrivate(thumbnailKey!, thumbnail, 'image/jpeg');
      } else if (asset.mimeType === 'audio/mp4') {
        // No duration cut: only the shared size ceiling bounds audio.
        const output = join(dir, 'safe.m4a');
        await runFfmpeg([
          '-nostdin', '-max_alloc', '67108864', '-protocol_whitelist', 'file,pipe', '-threads', '2',
          '-y', '-i', input, '-map', '0:a:0', '-vn', '-fs', String(MAX_SANITIZED_VIDEO_BYTES),
          '-c:a', 'aac', '-b:a', '96k', '-map_metadata', '-1', '-map_chapters', '-1',
          '-movflags', '+faststart', output,
        ]);
        if ((await stat(output)).size >= MAX_SANITIZED_VIDEO_BYTES)
          throw new BadRequestException('This audio is too long to prepare safely.');
        await this.storage.putPrivateFile(publicKey, output, 'audio/mp4');
      } else {
        const output = join(dir, 'safe.mp4'),
          thumb = join(dir, 'thumb.jpg');
        await runFfmpeg([
          '-nostdin',
          '-max_alloc',
          '67108864',
          '-protocol_whitelist',
          'file,pipe',
          '-filter_threads',
          '2',
          '-y',
          '-i',
          input,
          '-map',
          '0:v:0',
          '-map',
          '0:a:0?',
          '-fs',
          String(MAX_SANITIZED_VIDEO_BYTES),
          '-vf',
          'scale=1280:1280:force_original_aspect_ratio=decrease:force_divisible_by=2',
          '-c:v',
          'libx264',
          '-preset',
          'veryfast',
          '-crf',
          '25',
          '-threads',
          '2',
          '-c:a',
          'aac',
          '-b:a',
          '96k',
          '-map_metadata',
          '-1',
          '-map_chapters',
          '-1',
          '-movflags',
          '+faststart',
          output,
        ]);
        // -fs stops silently; a full-size output means the video was truncated.
        if ((await stat(output)).size >= MAX_SANITIZED_VIDEO_BYTES)
          throw new BadRequestException('This video is too long to prepare safely.');
        await runFfmpeg([
          '-nostdin',
          '-max_alloc',
          '67108864',
          '-protocol_whitelist',
          'file,pipe',
          '-threads',
          '2',
          '-filter_threads',
          '2',
          '-y',
          '-i',
          output,
          '-frames:v',
          '1',
          '-vf',
          'scale=600:-2',
          '-map_metadata',
          '-1',
          thumb,
        ]);
        await this.storage.putPrivateFile(publicKey, output, 'video/mp4');
        const { safe } = await sanitizeImage(thumb);
        await this.storage.putPrivate(thumbnailKey!, safe, 'image/jpeg');
      }
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
    const finished = await this.db.atomic(async (tx) => {
      const updated = await tx.mediaAsset.updateMany({
        where: { id, ...(lease ? { processingLease: lease } : {}) },
        data: {
          publicKey,
          thumbnailKey,
          processingState: 'READY',
          processingLease: null,
          processingStartedAt: null,
        },
      });
      if (updated.count === 1) await this.publishReady(tx, [id]);
      return updated.count;
    });
    if (finished !== 1) {
      await this.storage.deletePrivate(publicKey);
      if (thumbnailKey) await this.storage.deletePrivate(thumbnailKey);
      throw new Error('Media lease changed.');
    }
  }
  /**
   * Media needs no approval: an asset attached to a live post becomes public as soon as it is
   * sanitized. Row locks serialize this with attachment, so whichever finishes last publishes.
   */
  async publishReady(tx: Prisma.TransactionClient, ids: string[]) {
    if (!ids.length) return;
    await tx.$queryRaw`SELECT id FROM "MediaAsset" WHERE id = ANY(${ids}::text[]) ORDER BY id FOR UPDATE`;
    const assets = await tx.mediaAsset.findMany({
      where: { id: { in: ids }, moderation: 'APPROVED', processingState: 'READY' },
    });
    await this.publication(assets, true);
  }
  async original(actor: Actor, id: string) {
    const a = await this.db.mediaAsset.findUnique({ where: { id } });
    if (!a) throw new NotFoundException();
    this.auth.requireOrg(actor, a.organizationId, true);
    return { url: await this.storage.signedOriginal(a.originalKey) };
  }
  async retry(actor: Actor, id: string) {
    const a = await this.db.mediaAsset.findUnique({ where: { id } });
    if (!a) throw new NotFoundException();
    this.auth.requireOrg(actor, a.organizationId);
    if (env.MEDIA_PROCESSING_MODE === 'worker') {
      if (a.processingState === 'PROCESSING') return { ok: true, processingState: 'PROCESSING' };
      await this.db.mediaAsset.update({
        where: { id },
        data: {
          processingState: 'PENDING',
          processingAttempts: 0,
          processingLease: null,
          processingStartedAt: null,
        },
      });
      return { ok: true, processingState: 'PENDING' };
    }
    await this.process(id);
    return { ok: true };
  }
  async publication(
    assets: { publicKey: string | null; thumbnailKey: string | null; mimeType: string }[],
    approve: boolean,
  ) {
    for (const a of assets) {
      if (a.publicKey) {
        if (approve) await this.storage.publish(a.publicKey, a.mimeType);
        else await this.storage.hide(a.publicKey);
      }
      if (a.thumbnailKey) {
        if (approve) await this.storage.publish(a.thumbnailKey, 'image/jpeg');
        else await this.storage.hide(a.thumbnailKey);
      }
    }
  }
}
