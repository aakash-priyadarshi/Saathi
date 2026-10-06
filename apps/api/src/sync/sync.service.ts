import {
  Injectable,
  ForbiddenException,
  BadRequestException,
  ConflictException,
  HttpException,
} from '@nestjs/common';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { env } from '@saathi/config';
import {
  envelopeSchema,
  publicKeySchema,
  validEnvelope,
  hash,
  sign,
  verify,
  type Envelope,
  type Receipt,
  type PublicKey,
  receiptKeyringSchema,
  verifyServiceConfig,
  type ReceiptKeyPolicy,
  type SignedServiceConfig,
} from '@saathi/protocol';
import { Database, audit, json } from '../database';
import { AuthService, actorInclude, type Actor } from '../auth/auth.service';
import { RequestsService } from '../requests/requests.service';
import { MediaService } from '../media/media.service';

@Injectable()
export class SyncService {
  private signingKey!: CryptoKey;
  private publicKey!: PublicKey;
  private keyId!: string;
  private receiptKeys: ReceiptKeyPolicy[] = [];
  private configuration?: SignedServiceConfig;
  constructor(
    private readonly db: Database,
    private readonly auth: AuthService,
    private readonly requests: RequestsService,
    private readonly media: MediaService,
    private readonly receiptConfig: Pick<
      typeof env,
      'SYNC_SIGNING_PRIVATE_JWK' | 'SYNC_RECEIPT_KEYRING_JSON'
    > = env,
  ) {}
  async onModuleInit() {
    const location = resolve(dirname(env.LOCAL_MEDIA_DIR), 'sync-receipt-key.json');
    let stored = this.receiptConfig.SYNC_SIGNING_PRIVATE_JWK;
    if (!stored) {
      if (env.APP_ENV !== 'development' || env.NODE_ENV === 'production')
        throw new Error(
          'A durable operator receipt-signing key is required; regeneration is disabled.',
        );
      try {
        stored = await readFile(location, 'utf8');
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
        const keys = (await crypto.subtle.generateKey(
          { name: 'ECDSA', namedCurve: 'P-256' },
          true,
          ['sign', 'verify'],
        )) as CryptoKeyPair;
        stored = JSON.stringify(await crypto.subtle.exportKey('jwk', keys.privateKey));
        await mkdir(dirname(location), { recursive: true });
        try {
          await writeFile(location, stored, { mode: 0o600, flag: 'wx' });
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
          stored = await readFile(location, 'utf8');
        }
      }
    }
    try {
      const jwk: JsonWebKey = JSON.parse(stored);
      this.publicKey = publicKeySchema.parse({ kty: jwk.kty, crv: jwk.crv, x: jwk.x, y: jwk.y });
      this.signingKey = await crypto.subtle.importKey(
        'jwk',
        jwk,
        { name: 'ECDSA', namedCurve: 'P-256' },
        false,
        ['sign'],
      );
      const challenge = { startup: crypto.randomUUID() };
      if (!(await verify(challenge, await sign(challenge, this.signingKey), this.publicKey)))
        throw new Error();
    } catch {
      throw new Error('Invalid operator receipt-signing key; startup refused.');
    }
    this.keyId = await hash(this.publicKey);
    this.receiptKeys = this.receiptConfig.SYNC_RECEIPT_KEYRING_JSON
      ? receiptKeyringSchema.parse(JSON.parse(this.receiptConfig.SYNC_RECEIPT_KEYRING_JSON))
      : [
          {
            keyId: this.keyId,
            publicKey: this.publicKey,
            status: 'ACTIVE',
            signingFrom: new Date(Date.now() - 7 * 86400000).toISOString(),
            signingUntil: new Date(Date.now() + 90 * 86400000).toISOString(),
            verifyUntil: new Date(Date.now() + 365 * 86400000).toISOString(),
          },
        ];
    for (const key of this.receiptKeys)
      if (key.keyId !== (await hash(key.publicKey)))
        throw new Error('Receipt keyring has an invalid key identifier.');
    const active = this.receiptKeys.find((key) => key.status === 'ACTIVE')!;
    if (
      active.keyId !== this.keyId ||
      Date.parse(active.signingFrom) > Date.now() ||
      Date.parse(active.signingUntil) <= Date.now()
    )
      throw new Error(
        'The configured active receipt key does not match the signer or is outside its signing window.',
      );
    if (env.SERVICE_CONFIG_JSON) {
      if (!env.SERVICE_CONFIG_ROOT_PUBLIC_JWK)
        throw new Error('Signed service configuration requires its verification root.');
      this.configuration = await verifyServiceConfig(
        JSON.parse(env.SERVICE_CONFIG_JSON),
        publicKeySchema.parse(JSON.parse(env.SERVICE_CONFIG_ROOT_PUBLIC_JWK)),
        {
          environment: env.APP_ENV,
          minimumVersion: 1,
          androidVersionCode: Number.MAX_SAFE_INTEGER,
          allowLoopbackHttp: env.APP_ENV === 'development',
        },
      );
      if ((await hash(this.configuration.body.receiptKeys)) !== (await hash(this.receiptKeys)))
        throw new Error('Signed configuration and operational receipt keyring disagree.');
    }
  }
  receiptKey() {
    return { keyId: this.keyId, publicKey: this.publicKey, keys: this.receiptKeys };
  }
  serviceConfig() {
    if (!this.configuration)
      throw new HttpException('Service configuration has not been provisioned.', 503);
    return this.configuration;
  }
  devices(actor: Actor) {
    return this.db.device.findMany({
      where: { userId: actor.id },
      select: { id: true, createdAt: true, revokedAt: true },
    });
  }
  async prepare(actor: Actor) {
    if (actor.role === 'PUBLIC')
      throw new ForbiddenException(
        'Only approved relief team members can prepare offline publishing.',
      );
    const organizations = actor.memberships
      .filter((m) => {
        try {
          this.auth.requireOrg(actor, m.organizationId);
          return true;
        } catch {
          return false;
        }
      })
      .map((m) => ({ id: m.organizationId, name: m.organization.name }));
    const points = await this.db.reliefPoint.findMany({
      where: { organizationId: { in: organizations.map((o) => o.id) }, active: true },
      select: { id: true, name: true, publicLocation: true, organizationId: true },
    });
    return {
      user: { id: actor.id, displayName: actor.displayName },
      organizations,
      points,
      preparedAt: new Date().toISOString(),
    };
  }
  async register(actor: Actor, publicKey: PublicKey) {
    if (!(await this.prepare(actor)).organizations.length) throw new ForbiddenException();
    try {
      await crypto.subtle.importKey(
        'jwk',
        publicKey,
        { name: 'ECDSA', namedCurve: 'P-256' },
        false,
        ['verify'],
      );
    } catch {
      throw new BadRequestException('This publishing key is invalid. Refresh preparation.');
    }
    return this.db.atomic(async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${actor.id + ':signing-devices'}))`;
      if ((await tx.device.count({ where: { userId: actor.id, revokedAt: null } })) >= 10)
        throw new ConflictException('Remove an old prepared device first.');
      const device = await tx.device.create({
        data: { userId: actor.id, publicKey: JSON.stringify(publicKey) },
      });
      await audit(tx, 'SIGNING_DEVICE_REGISTERED', 'Device', device.id, actor.id);
      return { id: device.id, publicKey };
    });
  }
  async revoke(actor: Actor, id: string) {
    return this.db.atomic(async (tx) => {
      const device = await tx.device.findUnique({ where: { id } });
      if (!device || (actor.role !== 'ADMIN' && device.userId !== actor.id))
        throw new ForbiddenException();
      await tx.device.update({ where: { id }, data: { revokedAt: new Date() } });
      await audit(tx, 'SIGNING_DEVICE_REVOKED', 'Device', id, actor.id);
      return { ok: true };
    });
  }
  private async receipt(
    envelope: { body: { id: string; payloadHash: string }; signature: string },
    status: Receipt['body']['status'],
    message: string,
    publicId?: string,
    fieldId?: string,
    times?: { receivedAt: string; publishedAt?: string },
  ): Promise<Receipt> {
    const active = this.receiptKeys.find(
      (key) => key.keyId === this.keyId && key.status === 'ACTIVE',
    );
    if (
      !active ||
      Date.parse(active.signingFrom) > Date.now() ||
      Date.parse(active.signingUntil) <= Date.now()
    )
      throw new Error('Receipt signing lifetime has ended; operator rotation is required.');
    const body: Receipt['body'] = {
      eventId: envelope.body.id,
      payloadHash: envelope.body.payloadHash,
      signatureHash: await hash(envelope.signature),
      status,
      recordedAt: new Date().toISOString(),
      message: message.slice(0, 400),
      keyId: this.keyId,
      ...(publicId ? { publicId } : {}),
      ...(fieldId ? { fieldId } : {}),
      ...times,
    };
    return { body, signature: await sign(body, this.signingKey) };
  }
  // Internal domain services share the provisioned signer; no public signing endpoint.
  issueObjectReceipt(
    envelope: { body: { id: string; payloadHash: string }; signature: string },
    status: Receipt['body']['status'],
    message: string,
    fieldId?: string,
    times?: { receivedAt: string; publishedAt?: string },
  ) {
    return this.receipt(envelope, status, message, undefined, fieldId, times);
  }
  async ingest(value: unknown, carrierId: string): Promise<Receipt> {
    const envelope = envelopeSchema.parse(value),
      body = envelope.body;
    if (!(await validEnvelope(envelope)))
      throw new BadRequestException('This update could not be verified.');
    const existing = await this.db.offlineEvent.findUnique({ where: { id: body.id } });
    if (existing) {
      if ((await hash(existing.envelope)) !== (await hash(envelope)))
        throw new ConflictException('This update ID already contains different information.');
      const receipt = await this.refreshReceipt(existing.id);
      await this.observeCarrier(body.id, carrierId, receipt.body.status);
      return receipt;
    }
    const device = await this.db.device.findUnique({ where: { id: body.deviceId } });
    if (
      !device ||
      device.userId !== body.authorId ||
      (await hash(JSON.parse(device.publicKey))) !== (await hash(envelope.publicKey))
    )
      throw new BadRequestException('This publishing device is not registered with Saathi.');
    let receipt: Receipt;
    try {
      receipt = await this.db.atomic(async (tx) => {
        await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${body.id}))`;
        const previous = await tx.offlineEvent.findUnique({ where: { id: body.id } });
        if (previous) {
          if ((await hash(previous.envelope)) !== (await hash(envelope)))
            throw new ConflictException('This update ID already contains different information.');
          return previous.receipt as unknown as Receipt;
        }
        const registered = await tx.device.findUnique({ where: { id: body.deviceId } });
        const author = await tx.user.findUnique({
          where: { id: body.authorId },
          include: actorInclude,
        });
        if (!author?.active || !author.emailVerifiedAt || registered?.revokedAt || !registered)
          throw new ForbiddenException(
            'The original author or publishing device is no longer approved.',
          );
        this.auth.requireOrg(author, body.organizationId);
        const expires = Date.parse(body.expiresAt),
          created = Date.parse(body.createdAt);
        if (
          expires <= Date.now() ||
          created > Date.now() + 300000 ||
          expires <= created ||
          expires - created > 7 * 86400000
        )
          throw new BadRequestException(
            'This saved update has expired or its device time needs checking.',
          );
        let publicId: string | undefined,
          fieldId: string | undefined,
          published = true;
        if (body.type === 'REQUEST_CREATED') {
          const point = await tx.reliefPoint.findUnique({
            where: { id: body.payload.reliefPointId },
          });
          if (point?.organizationId !== body.organizationId)
            throw new ForbiddenException('The relief point belongs to a different team.');
          publicId = (await this.requests.createIn(tx, author, body.payload)).publicId;
        } else if (body.type === 'REQUEST_UPDATED') {
          const request = await tx.reliefRequest.findUnique({
            where: { publicId: body.payload.publicId },
          });
          if (request?.organizationId !== body.organizationId) throw new ForbiddenException();
          publicId = (
            await this.requests.editIn(tx, author, body.payload.publicId, body.payload.changes)
          ).publicId;
        } else {
          const point = await tx.reliefPoint.findUnique({
            where: { id: body.payload.reliefPointId },
          });
          if (point?.organizationId !== body.organizationId) throw new ForbiddenException();
          fieldId = (await this.requests.publishIn(tx, author, body.payload)).id;
          published = !body.payload.publishAt || Date.parse(body.payload.publishAt) <= Date.now();
        }
        const accepted = await this.receipt(
          envelope,
          published ? 'PUBLISHED' : 'ACCEPTED',
          published
            ? 'Published on Saathi.'
            : 'Reached Saathi. It will appear at the scheduled time.',
          publicId,
          fieldId,
        );
        await tx.offlineEvent.create({
          data: {
            id: body.id,
            authorId: body.authorId,
            organizationId: body.organizationId,
            deviceId: body.deviceId,
            protocolVersion: 1,
            type: body.type,
            payloadHash: body.payloadHash,
            payload: json(body.payload),
            signature: envelope.signature,
            envelope: json(envelope),
            receipt: json(accepted),
            status: accepted.body.status,
            expiresAt: new Date(body.expiresAt),
            createdAt: new Date(body.createdAt),
          },
        });
        await audit(tx, 'OFFLINE_EVENT_ACCEPTED', 'OfflineEvent', body.id, author.id, undefined, {
          deviceId: body.deviceId,
          type: body.type,
        });
        return accepted;
      });
    } catch (error) {
      if (!(error instanceof HttpException)) throw error;
      // Domain failure rolls back every mutation; retain a separately signed rejection for carriers.
      receipt = await this.receipt(
        envelope,
        error.getStatus() === 409 ? 'CONFLICT' : 'REJECTED',
        error.message,
      );
      await this.db.atomic(async (tx) => {
        await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${body.id}))`;
        const previous = await tx.offlineEvent.findUnique({ where: { id: body.id } });
        if (previous) {
          if ((await hash(previous.envelope)) !== (await hash(envelope)))
            throw new ConflictException();
          receipt = previous.receipt as unknown as Receipt;
          return;
        }
        await tx.offlineEvent.create({
          data: {
            id: body.id,
            authorId: body.authorId,
            organizationId: body.organizationId,
            deviceId: body.deviceId,
            protocolVersion: 1,
            type: body.type,
            payloadHash: body.payloadHash,
            payload: json(body.payload),
            signature: envelope.signature,
            envelope: json(envelope),
            receipt: json(receipt),
            status: receipt.body.status,
            expiresAt: new Date(body.expiresAt),
            createdAt: new Date(body.createdAt),
          },
        });
        await audit(
          tx,
          'OFFLINE_EVENT_REJECTED',
          'OfflineEvent',
          body.id,
          device.userId,
          undefined,
          { reason: receipt.body.message },
        );
      });
    }
    await this.observeCarrier(body.id, carrierId, receipt.body.status);
    return receipt;
  }
  private async observeCarrier(eventId: string, carrierId: string, status: string) {
    // Event-scoped pseudonyms cannot correlate a carrier across distinct events.
    const transportDeviceId = await hash({ eventId, carrierId });
    await this.db.atomic(async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${eventId}))`;
      const where = { eventId_transportDeviceId: { eventId, transportDeviceId } };
      const previous = await tx.syncReceipt.findUnique({ where });
      if (!previous && (await tx.syncReceipt.count({ where: { eventId } })) >= 50) return;
      await tx.syncReceipt.upsert({
        where,
        create: { eventId, transportDeviceId, status },
        update: {
          status,
          lastSeenAt: new Date(),
          observations: { increment: previous && previous.observations >= 10000 ? 0 : 1 },
        },
      });
    });
  }
  private async refreshReceipt(id: string): Promise<Receipt> {
    return this.db.atomic(async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${id}))`;
      const record = await tx.offlineEvent.findUniqueOrThrow({ where: { id } });
      const previous = record.receipt as unknown as Receipt;
      if (previous.body.keyId !== this.keyId) {
        const renewed = await this.receipt(
          record.envelope as unknown as Envelope,
          previous.body.status,
          previous.body.message,
          previous.body.publicId,
          previous.body.fieldId,
        );
        await tx.offlineEvent.update({ where: { id }, data: { receipt: json(renewed) } });
        // Recheck field visibility below before returning a renewed status.
        previous.body = renewed.body;
        previous.signature = renewed.signature;
      }
      if (
        !['ACCEPTED', 'PUBLISHED'].includes(previous.body.status) ||
        !previous.body.fieldId ||
        record.invalidatedAt
      )
        return previous;
      const post = await tx.fieldUpdate.findUnique({
        where: { id: previous.body.fieldId },
        include: { organization: true },
      });
      if (!post) return previous;
      const visible =
        post.moderation === 'APPROVED' &&
        post.publishAt <= new Date() &&
        post.organization?.active === true &&
        post.organization.verified;
      const rejected = ['REJECTED', 'HIDDEN'].includes(post.moderation);
      if ((!visible && !rejected) || (visible && previous.body.status === 'PUBLISHED'))
        return previous;
      const receipt = await this.receipt(
        record.envelope as unknown as Envelope,
        visible ? 'PUBLISHED' : 'REJECTED',
        visible ? 'Published on Saathi.' : 'This field update did not pass moderation.',
        previous.body.publicId,
        post.id,
      );
      await tx.offlineEvent.update({
        where: { id },
        data: { receipt: json(receipt), status: receipt.body.status },
      });
      return receipt;
    });
  }
  async receipts(ids: string[]) {
    const records = await this.db.offlineEvent.findMany({
      where: { id: { in: ids } },
      select: { id: true },
    });
    return Promise.all(records.map((e) => this.refreshReceipt(e.id)));
  }
  async attachMedia(actor: Actor, id: string, mediaIds: string[]) {
    return this.db.atomic(async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtext(${id}))`;
      const record = await tx.offlineEvent.findUniqueOrThrow({ where: { id } });
      const previous = record.receipt as unknown as Receipt;
      if (
        record.authorId !== actor.id ||
        record.type !== 'FIELD_PUBLISHED' ||
        record.invalidatedAt ||
        !previous.body.fieldId ||
        !['ACCEPTED', 'PUBLISHED'].includes(previous.body.status)
      )
        throw new ForbiddenException(
          'Only the original author can send media for this field update.',
        );
      this.auth.requireOrg(actor, record.organizationId);
      const post = await tx.fieldUpdate.findUniqueOrThrow({
        where: { id: previous.body.fieldId },
        include: { media: true },
      });
      if (['HIDDEN', 'REJECTED'].includes(post.moderation))
        throw new ForbiddenException('This field update is no longer available.');
      const newIds = mediaIds.filter((assetId) => !post.media.some((m) => m.id === assetId));
      if (!newIds.length) return previous;
      if (post.media.length + newIds.length > 4)
        throw new BadRequestException('A field update can contain up to four media files.');
      const assets = await tx.mediaAsset.findMany({
        where: {
          id: { in: newIds },
          ownerId: actor.id,
          organizationId: record.organizationId,
          fieldUpdateId: null,
          processingState: { in: ['PENDING', 'PROCESSING', 'READY'] },
          moderation: { notIn: ['REJECTED', 'HIDDEN'] },
        },
      });
      if (assets.length !== newIds.length)
        throw new BadRequestException(
          'Media must belong to the original author, not have failed processing, and not already be attached.',
        );
      await tx.fieldUpdate.update({
        where: { id: post.id },
        data: { media: { connect: assets.map((a) => ({ id: a.id })) } },
      });
      await this.requests.attachApproved(tx, newIds);
      const receipt = await this.receipt(
        record.envelope as unknown as Envelope,
        previous.body.status,
        'Media reached Saathi. It appears on the update once processing finishes.',
        previous.body.publicId,
        post.id,
      );
      await tx.offlineEvent.update({
        where: { id },
        data: { receipt: json(receipt), status: receipt.body.status },
      });
      await audit(tx, 'OFFLINE_MEDIA_ATTACHED', 'FieldUpdate', post.id, actor.id, undefined, {
        mediaIds: newIds,
      });
      return receipt;
    });
  }
  async invalidate(actor: Actor, id: string) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    const record = await this.db.offlineEvent.findUniqueOrThrow({ where: { id } });
    const envelope = record.envelope as unknown as Envelope;
    const receipt = await this.receipt(
      envelope,
      'INVALIDATED',
      'Saathi withdrew this update. Check the canonical request before sending supplies.',
      (record.receipt as unknown as Receipt).body.publicId,
      (record.receipt as unknown as Receipt).body.fieldId,
    );
    const result = await this.db.atomic(async (tx) => {
      if (receipt.body.publicId) {
        await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE "publicId"=${receipt.body.publicId} FOR UPDATE`;
        await tx.reliefRequest.update({
          where: { publicId: receipt.body.publicId },
          data: { status: 'CANCELLED', archivedAt: new Date(), version: { increment: 1 } },
        });
      }
      if (receipt.body.fieldId)
        await tx.fieldUpdate.update({
          where: { id: receipt.body.fieldId },
          data: { moderation: 'HIDDEN' },
        });
      await tx.offlineEvent.update({
        where: { id },
        data: { invalidatedAt: new Date(), status: 'INVALIDATED', receipt: json(receipt) },
      });
      await audit(tx, 'OFFLINE_EVENT_INVALIDATED', 'OfflineEvent', id, actor.id);
      return receipt;
    });
    if (receipt.body.fieldId) {
      const assets = await this.db.mediaAsset.findMany({
        where: { fieldUpdateId: receipt.body.fieldId },
      });
      await this.media.publication(assets, false);
    }
    return result;
  }
}
