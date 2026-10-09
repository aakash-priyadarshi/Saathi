import {
  Injectable,
  BadRequestException,
  ConflictException,
  NotFoundException,
  ForbiddenException,
  ServiceUnavailableException,
} from '@nestjs/common';
import { randomBytes } from 'node:crypto';
import { z } from 'zod';
import { env } from '@saathi/config';
import { requestSchema, editSchema, fieldSchema } from '@saathi/validation';
import { Prisma } from '@saathi/database';
import { Database, audit, json } from '../database';
import { AuthService, Actor } from '../auth/auth.service';
import type { MediaService } from '../media/media.service';
import { closed, deriveStatus } from '../domain/request';
import { platformFeatures } from '../public/platform-features';
export { requestInclude, publicRequest, type ExpandedRequest } from '../public/public-read.service';
import { requestInclude, publicRequest, PublicReadService } from '../public/public-read.service';
@Injectable()
export class RequestsService {
  constructor(
    private readonly db: Database,
    private readonly auth: AuthService,
    private readonly media: MediaService,
  ) {
    this.publicRead = new PublicReadService(db);
  }
  private readonly publicRead: PublicReadService;
  list(completed = false, category?: string) {
    return this.publicRead.list(completed, category);
  }
  get(publicId: string) {
    return this.publicRead.get(publicId);
  }
  async assertLiveEnabled() {
    if (!(await platformFeatures(this.db)).live)
      throw new ForbiddenException('Field updates are temporarily paused by an administrator.');
  }
  async createIn(tx: Prisma.TransactionClient, actor: Actor, input: z.infer<typeof requestSchema>) {
    if (!(await platformFeatures(tx)).needs)
      throw new ForbiddenException('Relief needs are temporarily paused by an administrator.');
    const point = await tx.reliefPoint.findUnique({ where: { id: input.reliefPointId } });
    if (!point?.active) throw new BadRequestException('Select an active relief point.');
    this.auth.requireOrg(actor, point.organizationId);
    const expiresAt = new Date(input.expiresAt ?? input.deadline);
    if (
      new Date(input.deadline) <= new Date() ||
      expiresAt < new Date() ||
      expiresAt < new Date(input.deadline)
    )
      throw new BadRequestException('Expiry must be at or after the deadline.');
    const r = await tx.reliefRequest.create({
      data: {
        ...input,
        deadline: new Date(input.deadline),
        expiresAt,
        creatorId: actor.id,
        organizationId: point.organizationId,
        publicId: `${env.REQUEST_ID_PREFIX}-${randomBytes(4).toString('hex').toUpperCase()}`,
      },
      include: requestInclude,
    });
    await tx.reliefRequestRevision.create({
      data: {
        requestId: r.id,
        version: r.version,
        actorId: actor.id,
        snapshot: json(publicRequest(r)),
      },
    });
    await audit(
      tx,
      'REQUEST_CREATED',
      'ReliefRequest',
      r.id,
      actor.id,
      undefined,
      publicRequest(r),
    );
    return r;
  }
  async create(actor: Actor, input: z.infer<typeof requestSchema>) {
    return publicRequest(await this.db.atomic((tx) => this.createIn(tx, actor, input)));
  }
  async edit(actor: Actor, publicId: string, input: z.infer<typeof editSchema>) {
    return this.db.atomic((tx) => this.editIn(tx, actor, publicId, input));
  }
  async editIn(
    tx: Prisma.TransactionClient,
    actor: Actor,
    publicId: string,
    input: z.infer<typeof editSchema>,
  ) {
    if (!(await platformFeatures(tx)).needs)
      throw new ForbiddenException('Relief needs are temporarily paused by an administrator.');
    await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE "publicId"=${publicId} FOR UPDATE`;
    const r = await tx.reliefRequest.findUnique({ where: { publicId }, include: requestInclude });
    if (!r) throw new NotFoundException();
    this.auth.requireOrg(actor, r.organizationId);
    if (actor.role === 'VOLUNTEER' && r.creatorId !== actor.id)
      throw new ForbiddenException('Only the creator or coordinator may edit.');
    if (closed.has(r.status) || r.expiresAt <= new Date())
      throw new ConflictException('This request is closed.');
    if (r.version !== input.version)
      throw new ConflictException('This request changed. Refresh before editing.');
    if ((input.requestedQuantity ?? r.requestedQuantity) < r.committedQuantity)
      throw new ConflictException('Quantity cannot be below existing commitments.');
    const { version: _version, ...change } = input;
    const next = await tx.reliefRequest.update({
      where: { id: r.id },
      data: {
        ...change,
        version: { increment: 1 },
        status: deriveStatus(
          input.requestedQuantity ?? r.requestedQuantity,
          r.committedQuantity,
          r.receivedQuantity,
          r.status,
        ),
      },
      include: requestInclude,
    });
    await tx.reliefRequestRevision.create({
      data: {
        requestId: r.id,
        version: next.version,
        actorId: actor.id,
        snapshot: json(publicRequest(next)),
      },
    });
    await audit(
      tx,
      'REQUEST_EDITED',
      'ReliefRequest',
      r.id,
      actor.id,
      publicRequest(r),
      publicRequest(next),
    );
    return publicRequest(next);
  }
  async cancel(actor: Actor, publicId: string) {
    return this.db.atomic(async (tx) => {
      await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE "publicId"=${publicId} FOR UPDATE`;
      const r = await tx.reliefRequest.findUnique({ where: { publicId } });
      if (!r) throw new NotFoundException();
      this.auth.requireOrg(actor, r.organizationId, true);
      if (closed.has(r.status)) throw new ConflictException('Request already closed.');
      const next = await tx.reliefRequest.update({
        where: { id: r.id },
        data: { status: 'CANCELLED', archivedAt: new Date(), version: { increment: 1 } },
      });
      await audit(tx, 'REQUEST_CANCELLED', 'ReliefRequest', r.id, actor.id, r, next);
      return { ok: true };
    });
  }
  /** Keep attached derivatives private until an administrator approves the field update. */
  async holdForReview(tx: Prisma.TransactionClient, ids: string[]) {
    await tx.mediaAsset.updateMany({
      where: { id: { in: ids } },
      data: { moderation: 'PENDING' },
    });
  }
  /** Guest posts are unverified and stay out of the public feed until admin review. */
  async publishGuest(
    ownerId: string,
    input: { caption: string; area: string; contentWarning: boolean; mediaIds: string[] },
  ) {
    return this.db.atomic(async (tx) => {
      if (!(await platformFeatures(tx)).live)
        throw new ForbiddenException('Field updates are temporarily paused by an administrator.');
      const assets = await tx.mediaAsset.findMany({
        where: {
          id: { in: input.mediaIds },
          ownerId,
          organizationId: null,
          fieldUpdateId: null,
          processingState: { in: ['PENDING', 'PROCESSING', 'READY'] },
        },
      });
      if (assets.length !== input.mediaIds.length)
        throw new BadRequestException('Upload your photos or videos again, then share.');
      const post = await tx.fieldUpdate.create({
        data: {
          caption: input.caption,
          participantName: 'Guest',
          publicArea: input.area,
          contentWarning: input.contentWarning,
          moderation: 'PENDING',
          media: { connect: assets.map((a) => ({ id: a.id })) },
        },
      });
      await this.holdForReview(
        tx,
        assets.map((a) => a.id),
      );
      await audit(tx, 'GUEST_POST_CREATED', 'FieldUpdate', post.id, undefined, undefined, {
        caption: post.caption,
        media: assets.length,
      });
      return { id: post.id };
    });
  }
  async publish(actor: Actor, input: z.infer<typeof fieldSchema>) {
    return this.db.atomic((tx) => this.publishIn(tx, actor, input));
  }
  async publishIn(tx: Prisma.TransactionClient, actor: Actor, input: z.infer<typeof fieldSchema>) {
    if (!(await platformFeatures(tx)).live)
      throw new ServiceUnavailableException(
        'Field updates are temporarily paused by an administrator.',
      );
    const point = await tx.reliefPoint.findUnique({ where: { id: input.reliefPointId } });
    if (!point?.active) throw new BadRequestException('Relief point unavailable.');
    this.auth.requireOrg(actor, point.organizationId);
    let requestId: string | undefined;
    if (input.request) {
      if (input.request.reliefPointId !== point.id)
        throw new BadRequestException('Linked need must use the same relief point.');
      requestId = (await this.createIn(tx, actor, input.request)).id;
    } else if (input.requestPublicId) {
      const r = await tx.reliefRequest.findUnique({ where: { publicId: input.requestPublicId } });
      if (!r || r.organizationId !== point.organizationId)
        throw new ForbiddenException('Linked request belongs to another organization.');
      requestId = r.id;
    }
    const assets = await tx.mediaAsset.findMany({
      where: {
        id: { in: input.mediaIds },
        ownerId: actor.id,
        organizationId: point.organizationId,
        fieldUpdateId: null,
        processingState: { in: ['PENDING', 'PROCESSING', 'READY'] },
        moderation: { notIn: ['REJECTED', 'HIDDEN'] },
      },
    });
    if (assets.length !== input.mediaIds.length)
      throw new BadRequestException(
        'Media must belong to you, not have failed processing, and not already be attached.',
      );
    const post = await tx.fieldUpdate.create({
      data: {
        caption: input.caption,
        authorId: actor.id,
        organizationId: point.organizationId,
        reliefPointId: point.id,
        requestId,
        publishAt: input.publishAt ? new Date(input.publishAt) : new Date(),
        // Sanitized derivatives remain private until an administrator approves the post.
        moderation: 'PENDING',
        media: { connect: assets.map((a) => ({ id: a.id })) },
      },
    });
    await this.holdForReview(
      tx,
      assets.map((a) => a.id),
    );
    await audit(tx, 'FIELD_POST_CREATED', 'FieldUpdate', post.id, actor.id, undefined, {
      caption: post.caption,
      moderation: post.moderation,
    });
    return { id: post.id, moderation: post.moderation };
  }
  feed() {
    return this.publicRead.feed();
  }
}
