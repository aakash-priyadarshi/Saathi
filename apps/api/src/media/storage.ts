import { Injectable } from '@nestjs/common';
import {
  S3Client,
  CreateBucketCommand,
  HeadBucketCommand,
  PutObjectCommand,
  GetObjectCommand,
  CopyObjectCommand,
  DeleteObjectCommand,
  PutBucketPolicyCommand,
  ListObjectsV2Command,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { env } from '@saathi/config';
import { resolve, dirname } from 'node:path';
import {
  mkdir,
  readFile,
  writeFile,
  unlink,
  readdir,
  lstat,
  copyFile,
  stat,
} from 'node:fs/promises';
import { createReadStream, createWriteStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';
import type { Readable } from 'node:stream';
import { createHmac, timingSafeEqual, randomBytes } from 'node:crypto';
export interface MediaStorageProvider {
  putPrivate(key: string, bytes: Buffer, mime: string): Promise<void>;
  putPrivateFile(key: string, file: string, mime: string): Promise<void>;
  readPrivate(key: string): Promise<Buffer>;
  downloadPrivate(key: string, file: string): Promise<void>;
  deletePrivate(key: string): Promise<void>;
  publish(key: string, mime: string): Promise<void>;
  hide(key: string): Promise<void>;
  signedOriginal(key: string): Promise<string>;
}
@Injectable()
export class S3Storage implements MediaStorageProvider {
  private readonly provider = env.STORAGE_PROVIDER;
  private readonly localRoot = env.LOCAL_MEDIA_DIR;
  private readonly signingKey = randomBytes(32);
  private readonly client = new S3Client({
    endpoint: env.S3_ENDPOINT,
    region: env.S3_REGION,
    forcePathStyle: true,
    credentials: { accessKeyId: env.S3_ACCESS_KEY, secretAccessKey: env.S3_SECRET_KEY },
  });
  async initializeLocal() {
    if (this.provider === 'local') {
      await mkdir(this.localRoot, { recursive: true });
      return;
    }
    if (env.NODE_ENV === 'production') return;
    for (const bucket of [env.S3_PRIVATE_BUCKET, env.S3_PUBLIC_BUCKET]) {
      try {
        await this.client.send(new HeadBucketCommand({ Bucket: bucket }));
      } catch {
        await this.client.send(new CreateBucketCommand({ Bucket: bucket }));
      }
    }
    await this.client.send(
      new PutBucketPolicyCommand({
        Bucket: env.S3_PUBLIC_BUCKET,
        Policy: JSON.stringify({
          Version: '2012-10-17',
          Statement: [
            {
              Effect: 'Allow',
              Principal: '*',
              Action: ['s3:GetObject'],
              Resource: [`arn:aws:s3:::${env.S3_PUBLIC_BUCKET}/*`],
            },
          ],
        }),
      }),
    );
  }
  private path(area: 'private' | 'public', key: string) {
    if (!/^[a-z]+\/[a-f0-9-]+(?:-thumb)?(?:\.[a-z0-9]+)?$/.test(key))
      throw new Error('Invalid object key');
    return resolve(this.localRoot, area, key);
  }
  /** Only Saathi-owned media prefixes; never inventory or delete unrelated bucket objects. */
  async *inventory(area: 'private' | 'public') {
    const owned = /^(original|sanitized)\/[a-f0-9-]+(?:-thumb)?(?:\.[a-z0-9]+)?$/;
    for (const prefix of ['original', 'sanitized']) {
      if (this.provider === 'local') {
        const directory = resolve(this.localRoot, area, prefix);
        const folder = await lstat(directory).catch((error: NodeJS.ErrnoException) => {
          if (error.code !== 'ENOENT') throw error;
          return null;
        });
        if (!folder) continue;
        if (!folder.isDirectory() || folder.isSymbolicLink())
          throw new Error('Media inventory requires an ordinary directory.');
        for (const entry of await readdir(directory, { withFileTypes: true })) {
          const key = `${prefix}/${entry.name}`;
          if (!entry.isFile() || !owned.test(key)) continue;
          const metadata = await lstat(this.path(area, key)).catch(
            (error: NodeJS.ErrnoException) => {
              if (error.code !== 'ENOENT') throw error;
              return null;
            },
          );
          if (metadata?.isFile()) yield { key, modifiedAt: metadata.mtime };
        }
      } else {
        let continuation: string | undefined;
        do {
          const result = await this.client.send(
            new ListObjectsV2Command({
              Bucket: area === 'private' ? env.S3_PRIVATE_BUCKET : env.S3_PUBLIC_BUCKET,
              Prefix: `${prefix}/`,
              MaxKeys: 200,
              ContinuationToken: continuation,
            }),
          );
          for (const object of result.Contents || [])
            if (object.Key && object.LastModified && owned.test(object.Key))
              yield { key: object.Key, modifiedAt: object.LastModified };
          const next = result.IsTruncated ? result.NextContinuationToken : undefined;
          if (result.IsTruncated && (!next || next === continuation))
            throw new Error('Media inventory pagination did not advance.');
          continuation = next;
        } while (continuation);
      }
    }
  }
  async deletePrivate(key: string) {
    if (this.provider === 'local') {
      await unlink(this.path('private', key)).catch((error: NodeJS.ErrnoException) => {
        if (error.code !== 'ENOENT') throw error;
      });
    } else
      await this.client.send(new DeleteObjectCommand({ Bucket: env.S3_PRIVATE_BUCKET, Key: key }));
  }
  async putPrivate(key: string, bytes: Buffer, mime: string) {
    if (this.provider === 'local') {
      const path = this.path('private', key);
      await mkdir(dirname(path), { recursive: true });
      await writeFile(path, bytes);
      return;
    }
    await this.client.send(
      new PutObjectCommand({
        Bucket: env.S3_PRIVATE_BUCKET,
        Key: key,
        Body: bytes,
        ContentType: mime,
      }),
    );
  }
  /** Streams from disk so a 250 MB upload never sits in memory. */
  async putPrivateFile(key: string, file: string, mime: string) {
    if (this.provider === 'local') {
      const path = this.path('private', key);
      await mkdir(dirname(path), { recursive: true });
      await copyFile(file, path);
      return;
    }
    await this.client.send(
      new PutObjectCommand({
        Bucket: env.S3_PRIVATE_BUCKET,
        Key: key,
        Body: createReadStream(file),
        ContentLength: (await stat(file)).size,
        ContentType: mime,
      }),
    );
  }
  async downloadPrivate(key: string, file: string) {
    if (this.provider === 'local') return copyFile(this.path('private', key), file);
    const r = await this.client.send(
      new GetObjectCommand({ Bucket: env.S3_PRIVATE_BUCKET, Key: key }),
    );
    if (!r.Body) throw new Error('Empty storage object');
    await pipeline(r.Body as Readable, createWriteStream(file));
  }
  async readPrivate(key: string) {
    if (this.provider === 'local') return readFile(this.path('private', key));
    const r = await this.client.send(
      new GetObjectCommand({ Bucket: env.S3_PRIVATE_BUCKET, Key: key }),
    );
    if (!r.Body) throw new Error('Empty storage object');
    return Buffer.from(await r.Body.transformToByteArray());
  }
  async publish(key: string, mime: string) {
    if (this.provider === 'local') {
      const path = this.path('public', key);
      await mkdir(dirname(path), { recursive: true });
      await copyFile(this.path('private', key), path);
      return;
    }
    // Server-side copy: large videos never pass through the API process.
    await this.client.send(
      new CopyObjectCommand({
        Bucket: env.S3_PUBLIC_BUCKET,
        Key: key,
        CopySource: `${env.S3_PRIVATE_BUCKET}/${key}`,
        MetadataDirective: 'REPLACE',
        ContentType: mime,
        CacheControl: 'no-store',
      }),
    );
  }
  async hide(key: string) {
    if (this.provider === 'local') {
      await unlink(this.path('public', key)).catch((error: NodeJS.ErrnoException) => {
        if (error.code !== 'ENOENT') throw error;
      });
      return;
    }
    await this.client.send(new DeleteObjectCommand({ Bucket: env.S3_PUBLIC_BUCKET, Key: key }));
  }
  async readPublic(key: string) {
    return readFile(this.path('public', key));
  }
  publicPath(key: string) {
    return this.path('public', key);
  }
  async signedOriginal(key: string) {
    if (this.provider === 'local') {
      const expires = Date.now() + 60000;
      const sig = createHmac('sha256', this.signingKey).update(`${key}:${expires}`).digest('hex');
      return `${env.PUBLIC_URL}/api/v1/local-original?key=${encodeURIComponent(key)}&expires=${expires}&signature=${sig}`;
    }
    return getSignedUrl(
      this.client,
      new GetObjectCommand({ Bucket: env.S3_PRIVATE_BUCKET, Key: key }),
      { expiresIn: 60 },
    );
  }
  validSignature(key: string, expires: number, signature: string) {
    const expected = createHmac('sha256', this.signingKey)
      .update(`${key}:${expires}`)
      .digest('hex');
    return (
      expires > Date.now() &&
      expires <= Date.now() + 60000 &&
      signature.length === expected.length &&
      timingSafeEqual(Buffer.from(expected), Buffer.from(signature))
    );
  }
}
