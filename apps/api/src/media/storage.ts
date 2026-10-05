import { Injectable } from '@nestjs/common';
import {
  S3Client,
  CreateBucketCommand,
  HeadBucketCommand,
  PutObjectCommand,
  GetObjectCommand,
  DeleteObjectCommand,
  PutBucketPolicyCommand,
} from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';
import { env } from '@saathi/config';
import { resolve, dirname } from 'node:path';
import { mkdir, readFile, writeFile, unlink } from 'node:fs/promises';
import { createHmac, timingSafeEqual, randomBytes } from 'node:crypto';
export interface MediaStorageProvider {
  putPrivate(key: string, bytes: Buffer, mime: string): Promise<void>;
  readPrivate(key: string): Promise<Buffer>;
  publish(key: string, mime: string): Promise<void>;
  hide(key: string): Promise<void>;
  signedOriginal(key: string): Promise<string>;
}
@Injectable()
export class S3Storage implements MediaStorageProvider {
  private readonly localRoot = env.LOCAL_MEDIA_DIR;
  private readonly signingKey = randomBytes(32);
  private readonly client = new S3Client({
    endpoint: env.S3_ENDPOINT,
    region: env.S3_REGION,
    forcePathStyle: true,
    credentials: { accessKeyId: env.S3_ACCESS_KEY, secretAccessKey: env.S3_SECRET_KEY },
  });
  async initializeLocal() {
    if (env.STORAGE_PROVIDER === 'local') {
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
  async putPrivate(key: string, bytes: Buffer, mime: string) {
    if (env.STORAGE_PROVIDER === 'local') {
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
  async readPrivate(key: string) {
    if (env.STORAGE_PROVIDER === 'local') return readFile(this.path('private', key));
    const r = await this.client.send(
      new GetObjectCommand({ Bucket: env.S3_PRIVATE_BUCKET, Key: key }),
    );
    if (!r.Body) throw new Error('Empty storage object');
    return Buffer.from(await r.Body.transformToByteArray());
  }
  async publish(key: string, mime: string) {
    const bytes = await this.readPrivate(key);
    if (env.STORAGE_PROVIDER === 'local') {
      const path = this.path('public', key);
      await mkdir(dirname(path), { recursive: true });
      await writeFile(path, bytes);
      return;
    }
    await this.client.send(
      new PutObjectCommand({
        Bucket: env.S3_PUBLIC_BUCKET,
        Key: key,
        Body: bytes,
        ContentType: mime,
        CacheControl: 'no-store',
      }),
    );
  }
  async hide(key: string) {
    if (env.STORAGE_PROVIDER === 'local') {
      await unlink(this.path('public', key)).catch(() => {});
      return;
    }
    await this.client.send(new DeleteObjectCommand({ Bucket: env.S3_PUBLIC_BUCKET, Key: key }));
  }
  async readPublic(key: string) {
    return readFile(this.path('public', key));
  }
  async signedOriginal(key: string) {
    if (env.STORAGE_PROVIDER === 'local') {
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
