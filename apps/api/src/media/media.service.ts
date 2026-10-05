import { Injectable, BadRequestException, NotFoundException } from '@nestjs/common';
import sharp from 'sharp';
import { randomUUID } from 'node:crypto';
import { createConnection } from 'node:net';
import { spawn } from 'node:child_process';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import ffmpeg from 'ffmpeg-static';
import { env } from '@saathi/config';
import { Database, audit } from '../database';
import { AuthService, Actor } from '../auth/auth.service';
import { S3Storage } from './storage';
export async function sanitizeImage(bytes: Buffer) {
  const image = sharp(bytes, { limitInputPixels: 40000000, failOn: 'warning' });
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
export type UploadFile = { buffer: Buffer; size: number };
export async function scan(bytes: Buffer) {
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
    socket.on('connect', () => {
      socket.write('zINSTREAM\0');
      for (let offset = 0; offset < bytes.length; offset += 65536) {
        const chunk = bytes.subarray(offset, offset + 65536),
          size = Buffer.alloc(4);
        size.writeUInt32BE(chunk.length);
        socket.write(size);
        socket.write(chunk);
      }
      socket.write(Buffer.alloc(4));
    });
  });
}
async function runFfmpeg(args: string[]) {
  await new Promise<void>((resolve, reject) => {
    const child = spawn(
      env.FFMPEG_PATH === 'ffmpeg' ? (ffmpeg ?? 'ffmpeg') : env.FFMPEG_PATH,
      args,
      { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] },
    );
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error('Video processing timed out'));
    }, 120000);
    child.stderr.on('data', (d) => {
      stderr = (stderr + d.toString()).slice(-2000);
    });
    child.on('error', (e) => {
      clearTimeout(timer);
      reject(e);
    });
    child.on('exit', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve();
      else reject(new BadRequestException(`Video could not be processed: ${stderr.slice(-150)}`));
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
    if (!file?.buffer?.length) throw new BadRequestException('Choose a photo or video.');
    if (file.size > 25 * 1024 * 1024)
      throw new BadRequestException('Uploads must be smaller than 25 MB.');
    await scan(file.buffer);
    const isMp4 = file.buffer.subarray(4, 8).toString() === 'ftyp',
      isWebm = file.buffer.subarray(0, 4).equals(Buffer.from([0x1a, 0x45, 0xdf, 0xa3]));
    const video = isMp4 || isWebm;
    if (!video)
      await sharp(file.buffer, { limitInputPixels: 40000000 })
        .metadata()
        .catch(() => {
          throw new BadRequestException(
            'Unsupported file type. Choose a photo, MP4 or WebM video.',
          );
        });
    await this.storage.initializeLocal();
    const id = randomUUID(),
      originalKey = `original/${id}`;
    await this.storage.putPrivate(originalKey, file.buffer, 'application/octet-stream');
    const asset = await this.db.atomic(async (tx) => {
      const a = await tx.mediaAsset.create({
        data: {
          id,
          ownerId: actor.id,
          organizationId,
          originalKey,
          size: file.size,
          mimeType: video ? 'video/mp4' : 'image/jpeg',
        },
      });
      await audit(tx, 'MEDIA_UPLOADED', 'MediaAsset', id, actor.id, undefined, {
        size: file.size,
        mimeType: a.mimeType,
      });
      return a;
    });
    try {
      await this.process(asset.id);
    } catch (e) {
      await this.db.mediaAsset.update({ where: { id }, data: { processingState: 'FAILED' } });
      throw e;
    }
    return { id, processingState: 'READY', moderation: 'PENDING' };
  }
  async process(id: string) {
    const asset = await this.db.mediaAsset.findUniqueOrThrow({ where: { id } }),
      bytes = await this.storage.readPrivate(asset.originalKey);
    await scan(bytes);
    const base = `sanitized/${id}`,
      publicKey = asset.mimeType === 'video/mp4' ? `${base}.mp4` : `${base}.jpg`,
      thumbnailKey = `${base}-thumb.jpg`;
    if (asset.mimeType === 'image/jpeg') {
      const { safe, thumbnail } = await sanitizeImage(bytes);
      await this.storage.putPrivate(publicKey, safe, 'image/jpeg');
      await this.storage.putPrivate(thumbnailKey, thumbnail, 'image/jpeg');
    } else {
      const dir = await mkdtemp(join(tmpdir(), 'saathi-media-'));
      try {
        const input = join(dir, 'input'),
          output = join(dir, 'safe.mp4'),
          thumb = join(dir, 'thumb.jpg');
        await writeFile(input, bytes);
        await runFfmpeg([
          '-y',
          '-i',
          input,
          '-map',
          '0:v:0',
          '-map',
          '0:a:0?',
          '-t',
          '120',
          '-vf',
          'scale=1280:1280:force_original_aspect_ratio=decrease:force_divisible_by=2',
          '-c:v',
          'libx264',
          '-preset',
          'fast',
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
        await runFfmpeg([
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
        await this.storage.putPrivate(publicKey, await readFile(output), 'video/mp4');
        const { safe } = await sanitizeImage(await readFile(thumb));
        await this.storage.putPrivate(thumbnailKey, safe, 'image/jpeg');
      } finally {
        await rm(dir, { recursive: true, force: true });
      }
    }
    await this.db.mediaAsset.update({
      where: { id },
      data: { publicKey, thumbnailKey, processingState: 'READY' },
    });
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
