import { BadRequestException, ConflictException, ForbiddenException } from '@nestjs/common';
import { createHash } from 'node:crypto';
import {
  bytes,
  hash,
  verify,
  communitySyncSchema,
  communityMediaRequestSchema,
  validCommunityEnvelope,
  validChatProfile,
  type CommunityEnvelope,
} from '@saathi/protocol';
import { Prisma } from '@saathi/database';
import { Database, json, audit } from '../database';
import { SyncService } from '../sync/sync.service';
import { MediaService } from '../media/media.service';
import { S3Storage } from '../media/storage';
import type { Actor } from '../auth/auth.service';
type Tx = Prisma.TransactionClient;
const read = (v: unknown) => v as CommunityEnvelope;
export class CommunityService {
  constructor(
    private readonly db: Database,
    private readonly sync: SyncService,
    private readonly media: MediaService,
    private readonly storage: S3Storage,
  ) {}
  private async ingest(tx: Tx, e: CommunityEnvelope, now: number) {
    const b = e.body,
      digest = await hash(e),
      existing = await tx.communityEvent.findUnique({ where: { id: b.id } });
    if (existing) {
      if (existing.envelopeHash !== digest)
        throw new ConflictException('Statement identifier was reused.');
      return;
    }
    if (!(await validCommunityEnvelope(e, now)))
      throw new BadRequestException('Participant statement could not be verified.');
    const daily = await tx.communityEvent.count({
      where: { authorId: b.author.body.id, receivedAt: { gte: new Date(now - 86400000) } },
    });
    if (daily >= 100) throw new BadRequestException('Daily participant statement limit reached.');
    const prior = await tx.communityEvent.findFirst({
      where: {
        objectId: b.objectId,
        type: b.type === 'HELP' || b.type === 'HELP_OFFER' ? 'HELP' : 'REPORT',
      },
      orderBy: { version: 'desc' },
    });
    let area: string | undefined,
      version = 1;
    if (b.type === 'HELP') {
      const p = b.payload;
      area = p.help.area;
      version = p.version;
      if (prior) {
        const old = read(prior.envelope);
        if (
          old.body.type !== 'HELP' ||
          prior.authorId !== b.author.body.id ||
          prior.hidden ||
          prior.expiresAt.getTime() <= now ||
          prior.envelopeHash !== p.previousHash ||
          prior.version + 1 !== p.version ||
          ['RESOLVED', 'CANCELLED'].includes(old.body.payload.help.status)
        )
          throw new ConflictException('Help changed or ended. Refresh before updating.');
        if (Date.parse(b.expiresAt) > prior.expiresAt.getTime())
          throw new BadRequestException('An update cannot extend a temporary help request.');
        if (
          p.help.status === 'RESPONDER_ASSIGNED' &&
          !(
            old.body.payload.help.status === 'RESPONDER_ASSIGNED' &&
            old.body.payload.help.responderId === p.help.responderId
          )
        ) {
          const offers = await tx.communityEvent.findMany({
            where: {
              objectId: b.objectId,
              type: 'HELP_OFFER',
              authorId: p.help.responderId!,
              expiresAt: { gt: new Date(now) },
            },
            take: 100,
          });
          if (
            !offers.some(
              (o) =>
                read(o.envelope).body.type === 'HELP_OFFER' &&
                (
                  read(o.envelope).body as Extract<
                    CommunityEnvelope['body'],
                    { type: 'HELP_OFFER' }
                  >
                ).payload.requestHash === prior.envelopeHash,
            )
          )
            throw new BadRequestException(
              'Choose a responder who offered help for the current request.',
            );
        }
      } else {
        if (p.version !== 1) throw new ConflictException('The original help request is missing.');
        const recent = await tx.communityEvent.findMany({
          where: { type: 'HELP', authorId: b.author.body.id, expiresAt: { gt: new Date(now) } },
          orderBy: { version: 'desc' },
          take: 300,
        });
        const latest = new Map<string, (typeof recent)[number]>();
        for (const row of recent) if (!latest.has(row.objectId)) latest.set(row.objectId, row);
        const active = [...latest.values()].filter(
          (row) =>
            !row.hidden &&
            ['OPEN', 'RESPONDER_ASSIGNED'].includes(
              (read(row.envelope).body as Extract<CommunityEnvelope['body'], { type: 'HELP' }>)
                .payload.help.status,
            ),
        );
        if (active.length >= 3)
          throw new BadRequestException(
            'Resolve or cancel an active request before creating another.',
          );
        if (
          active.some(
            (row) =>
              (read(row.envelope).body as Extract<CommunityEnvelope['body'], { type: 'HELP' }>)
                .payload.help.category === p.help.category,
          )
        )
          throw new ConflictException(
            'You already have an active request in this category. Update it instead.',
          );
        if (recent.some((row) => row.version === 1 && row.receivedAt.getTime() > now - 60000))
          throw new BadRequestException('Wait one minute before creating another help request.');
      }
    } else if (b.type === 'HELP_OFFER') {
      if (!prior) throw new ConflictException('The original help request is missing.');
      if (
        prior.hidden ||
        prior.expiresAt.getTime() <= now ||
        prior.envelopeHash !== b.payload.requestHash ||
        (read(prior.envelope).body as Extract<CommunityEnvelope['body'], { type: 'HELP' }>).payload
          .help.status !== 'OPEN'
      )
        throw new ConflictException('This help request is no longer open.');
      if (prior.authorId === b.author.body.id)
        throw new BadRequestException('You cannot respond to your own request.');
      if (
        await tx.communityEvent.findFirst({
          where: { objectId: b.objectId, type: 'HELP_OFFER', authorId: b.author.body.id },
        })
      )
        throw new ConflictException('Your offer is already saved.');
      area = prior.area ?? undefined;
    } else if (b.type === 'REPORT') {
      if (prior) throw new ConflictException('Report identifier was reused.');
      area = b.payload.area;
      if (b.payload.media) {
        const m = b.payload.media;
        await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended('community-media-quota',0))`;
        const global = await tx.mediaAsset.aggregate({
          where: { organizationId: null },
          _sum: { size: true },
        });
        const authored = await tx.mediaAsset.aggregate({
          where: { organizationId: null, ownerId: b.author.body.id },
          _sum: { size: true },
        });
        if (
          (global._sum.size ?? 0) + m.size > 512 * 1048576 ||
          (authored._sum.size ?? 0) + m.size > 64 * 1048576
        )
          throw new BadRequestException(
            'Public media storage quota reached. Contact a moderator to review retained media.',
          );
        if (await tx.mediaAsset.findUnique({ where: { id: m.id } }))
          throw new ConflictException('Media identifier was reused.');
        await tx.fieldUpdate.create({
          data: {
            id: b.id,
            caption: b.payload.caption,
            participantName: b.author.body.name,
            publicArea: area,
            contentWarning: b.payload.contentWarning,
            createdAt: new Date(b.createdAt),
            receivedAt: new Date(now),
            media: {
              create: {
                id: m.id,
                ownerId: b.author.body.id,
                originalKey: `original/${m.id}`,
                size: m.size,
                mimeType: m.mime,
                processingState: 'RECEIVING',
              },
            },
          },
        });
      } else
        await tx.fieldUpdate.create({
          data: {
            id: b.id,
            caption: b.payload.caption,
            participantName: b.author.body.name,
            publicArea: area,
            contentWarning: b.payload.contentWarning,
            createdAt: new Date(b.createdAt),
            receivedAt: new Date(now),
          },
        });
    } else if (b.type === 'WITHDRAW') {
      if (!prior) throw new ConflictException('The original report is missing.');
      if (prior.authorId !== b.author.body.id || prior.envelopeHash !== b.payload.reportHash)
        throw new ForbiddenException('Only the original author may withdraw this report.');
      await tx.$queryRaw`SELECT id FROM "FieldUpdate" WHERE id=${b.objectId} FOR UPDATE`;
      await tx.$queryRaw`SELECT id FROM "MediaAsset" WHERE "fieldUpdateId"=${b.objectId} ORDER BY id FOR UPDATE`;
      const assets = await tx.mediaAsset.findMany({ where: { fieldUpdateId: b.objectId } });
      await this.media.publication(assets, false);
      await tx.fieldUpdate.update({ where: { id: b.objectId }, data: { moderation: 'HIDDEN' } });
      await tx.mediaAsset.updateMany({
        where: { fieldUpdateId: b.objectId },
        data: { moderation: 'HIDDEN' },
      });
      await tx.communityEvent.updateMany({
        where: { objectId: b.objectId },
        data: { hidden: true },
      });
      area = prior.area ?? undefined;
    } else {
      const target = await tx.communityEvent.findUnique({
        where: { envelopeHash: b.payload.targetHash },
      });
      if (!target) throw new ConflictException('The original statement is missing.');
      if (target.objectId !== b.objectId)
        throw new BadRequestException('The reported statement is unavailable.');
      area = target.area ?? undefined;
      await tx.moderationReport.create({
        data: { entityType: 'COMMUNITY', entityId: b.objectId, reason: b.payload.reason },
      });
    }
    await tx.communityEvent.create({
      data: {
        id: b.id,
        objectId: b.objectId,
        type: b.type,
        authorId: b.author.body.id,
        area,
        version,
        envelope: json(e),
        envelopeHash: digest,
        hidden: b.type === 'WITHDRAW',
        receivedAt: new Date(now),
        expiresAt: new Date(b.expiresAt),
      },
    });
  }
  async exchange(input: unknown) {
    const request = communitySyncSchema.parse(input),
      b = request.body,
      now = Date.now();
    if (
      bytes(request).length > 700000 ||
      Math.abs(Date.parse(b.issuedAt) - now) > 300000 ||
      !(await validChatProfile(b.profile, now)) ||
      !(await verify(b, request.signature, b.profile.body.publicKey))
    )
      throw new BadRequestException('Public sync request could not be verified.');
    const accepted: string[] = [],
      rejected: { id: string; reason: string; retryable: boolean }[] = [];
    // Serialize statement identity, author quotas and each object chain across competing gateways.
    for (const event of b.events) {
      try {
        await this.db.atomic(async (tx) => {
          const locks = [
            'community:author:' + event.body.author.body.id,
            'community:object:' + event.body.objectId,
          ];
          if (event.body.type === 'REPORT' && event.body.payload.media)
            locks.push('community-media-quota');
          for (const lock of locks.sort())
            await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${lock},0))`;
          await this.ingest(tx, event, now);
        });
        accepted.push(event.body.id);
      } catch (error) {
        if (!(
          error instanceof BadRequestException ||
          error instanceof ConflictException ||
          error instanceof ForbiddenException
        ))
          throw error;
        rejected.push({
          id: event.body.id,
          reason: error.message,
          retryable: /original .*missing/i.test(error.message),
        });
      }
    }
    const held = await this.db.communityEvent.findMany({
      where: { id: { in: b.known } },
      select: { objectId: true },
      take: 500,
    });
    const blocked = (
      await this.db.chatBlock.findMany({
        where: { participantId: b.profile.body.id },
        select: { blockedId: true },
        take: 100,
      })
    ).map((r) => r.blockedId);
    const visible = await this.db.communityEvent.findMany({
      where: {
        id: { notIn: b.known },
        hidden: false,
        authorId: { notIn: blocked },
        type: { not: 'FLAG' },
        expiresAt: { gt: new Date(now) },
        OR: [
          { area: { in: b.areas } },
          { authorId: b.profile.body.id },
          { objectId: { in: held.map((r) => r.objectId) } },
        ],
      },
      orderBy: [{ receivedAt: 'asc' }, { version: 'asc' }, { id: 'asc' }],
      take: 50,
    });
    const confirmations = await this.db.communityEvent.findMany({
      where: { id: { in: [...accepted, ...(b.receiptIds ?? b.known)].slice(0, 100) } },
      take: 100,
    });
    const receipts = [];
    for (const row of confirmations) {
      const e = read(row.envelope);
      const post =
        e.body.type === 'REPORT'
          ? await this.db.fieldUpdate.findUnique({ where: { id: row.objectId } })
          : null;
      const status =
        row.hidden || post?.moderation === 'HIDDEN'
          ? 'INVALIDATED'
          : post?.moderation === 'APPROVED' && post.publishAt.getTime() <= now
            ? 'PUBLISHED'
            : post?.moderation === 'REJECTED'
              ? 'REJECTED'
              : 'ACCEPTED';
      receipts.push(
        await this.sync.issueObjectReceipt(
          e,
          status,
          status === 'PUBLISHED'
            ? 'Published on CJP Swarm.'
            : status === 'ACCEPTED'
              ? 'Received online; participant reports await review.'
              : 'This public statement is unavailable.',
          post?.id,
          {
            receivedAt: row.receivedAt.toISOString(),
            ...(post?.publishedAt ? { publishedAt: post.publishedAt.toISOString() } : {}),
          },
        ),
      );
    }
    const ordered = new Map<string, (typeof visible)[number]>();
    const include = async (row: (typeof visible)[number]): Promise<void> => {
      if (b.known.includes(row.id) || ordered.has(row.id) || ordered.size >= 100 || row.hidden)
        return;
      const e = read(row.envelope),
        p = e.body.payload;
      const references =
        'previousHash' in p
          ? [p.previousHash]
          : 'requestHash' in p
            ? [p.requestHash]
            : 'reportHash' in p
              ? [p.reportHash]
              : [];
      for (const reference of references)
        if (reference) {
          const parent = await this.db.communityEvent.findUnique({
            where: { envelopeHash: reference },
          });
          if (parent && parent.expiresAt.getTime() > now) await include(parent);
        }
      if (e.body.type === 'HELP' && e.body.payload.help.status === 'RESPONDER_ASSIGNED') {
        const offers = await this.db.communityEvent.findMany({
          where: {
            objectId: row.objectId,
            type: 'HELP_OFFER',
            authorId: e.body.payload.help.responderId!,
            hidden: false,
            expiresAt: { gt: new Date(now) },
          },
          take: 100,
        });
        for (const offer of offers)
          if (
            (
              read(offer.envelope).body as Extract<
                CommunityEnvelope['body'],
                { type: 'HELP_OFFER' }
              >
            ).payload.requestHash === e.body.payload.previousHash
          )
            await include(offer);
      }
      if (ordered.size < 100) ordered.set(row.id, row);
    };
    for (const row of visible) await include(row);
    const response = {
      accepted,
      rejected,
      events: [] as unknown[],
      hidden: confirmations.filter((r) => r.hidden).map((r) => r.objectId),
      receipts,
      receivedAt: new Date(now).toISOString(),
    };
    let budget = bytes(response).length;
    for (const row of ordered.values()) {
      const cost = bytes(row.envelope).length + 1;
      if (budget + cost > 262000) break;
      response.events.push(row.envelope);
      budget += cost;
    }
    return response;
  }
  async attachment(input: unknown) {
    const request = communityMediaRequestSchema.parse(input),
      b = request.body,
      now = Date.now();
    if (
      bytes(request).length > 90000 ||
      Math.abs(Date.parse(b.issuedAt) - now) > 300000 ||
      !(await validChatProfile(b.profile, now)) ||
      !(await verify(b, request.signature, b.profile.body.publicKey))
    )
      throw new BadRequestException('Media carrier request could not be verified.');
    const result = await this.db.atomic(async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended('community-media-quota',0))`;
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${'community:object:' + b.reportId},0))`;
      const row = await tx.communityEvent.findUnique({ where: { id: b.reportId } });
      if (!row || row.hidden || row.expiresAt.getTime() <= now)
        throw new ForbiddenException('Report is unavailable.');
      const e = read(row.envelope);
      if (e.body.type !== 'REPORT' || !e.body.payload.media)
        throw new BadRequestException('This report has no media.');
      const m = e.body.payload.media;
      const asset = await tx.mediaAsset.findUniqueOrThrow({ where: { id: m.id } });
      if (asset.processingState !== 'RECEIVING')
        return { mediaId: m.id, missing: [] as number[], complete: true };
      await tx.communityMediaChunk.deleteMany({ where: { expiresAt: { lte: new Date(now) } } });
      const usage = await tx.$queryRaw<
        { size: bigint }[]
      >`SELECT COALESCE(SUM(octet_length(data)),0)::bigint AS size FROM "CommunityMediaChunk"`;
      const own = await tx.mediaAsset.aggregate({
        where: { ownerId: row.authorId, processingState: 'RECEIVING' },
        _sum: { size: true },
      });
      if (
        Number(usage[0]!.size) + b.chunks.length * 8192 > 512 * 1048576 ||
        (own._sum.size ?? 0) > 64 * 1048576
      )
        throw new BadRequestException('Public media storage quota reached.');
      const count = Math.ceil(m.size / 8192),
        indices = new Set<number>();
      for (const part of b.chunks) {
        if (indices.has(part.index)) throw new BadRequestException('Duplicate chunk index.');
        indices.add(part.index);
        const data = Buffer.from(part.data, 'base64url');
        if (
          part.index >= count ||
          data.length !== Math.min(8192, m.size - part.index * 8192) ||
          data.toString('base64url') !== part.data
        )
          throw new BadRequestException('Invalid media chunk.');
        const old = await tx.communityMediaChunk.findUnique({
          where: { mediaId_index: { mediaId: m.id, index: part.index } },
        });
        if (old && !Buffer.from(old.data).equals(data))
          throw new ConflictException('A media chunk changed.');
        if (!old)
          await tx.communityMediaChunk.create({
            data: { mediaId: m.id, index: part.index, data, expiresAt: new Date(now + 86400000) },
          });
      }
      const parts = await tx.communityMediaChunk.findMany({
          where: { mediaId: m.id },
          orderBy: { index: 'asc' },
        }),
        missing = Array.from({ length: count }, (_, i) => i).filter(
          (i) => !parts.some((p) => p.index === i),
        );
      if (missing.length) return { mediaId: m.id, missing, complete: false };
      const raw = Buffer.concat(parts.map((p) => Buffer.from(p.data)));
      if (raw.length !== m.size || createHash('sha256').update(raw).digest('hex') !== m.hash) {
        await tx.communityMediaChunk.deleteMany({ where: { mediaId: m.id } });
        return {
          mediaId: m.id,
          missing: Array.from({ length: count }, (_, i) => i),
          complete: false,
          invalidHash: true,
        };
      }
      await this.storage.initializeLocal();
      await this.storage.putPrivate(asset.originalKey, raw, 'application/octet-stream');
      await tx.mediaAsset.update({ where: { id: m.id }, data: { processingState: 'PENDING' } });
      await tx.communityMediaChunk.deleteMany({ where: { mediaId: m.id } });
      return { mediaId: m.id, missing: [] as number[], complete: true };
    });
    if (result.complete) {
      const asset = await this.db.mediaAsset.findUniqueOrThrow({ where: { id: result.mediaId } });
      if (
        asset.processingState === 'PENDING' &&
        (await import('@saathi/config')).env.MEDIA_PROCESSING_MODE === 'inline'
      )
        await this.media.process(asset.id);
    }
    return result;
  }
  async moderation(actor: Actor) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    return this.db.communityEvent.findMany({
      where: { type: { in: ['HELP', 'FLAG'] }, hidden: false, expiresAt: { gt: new Date() } },
      orderBy: { receivedAt: 'desc' },
      take: 100,
    });
  }
  async hide(actor: Actor, id: string) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    return this.db.atomic(async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${'community:object:' + id},0))`;
      await tx.communityEvent.updateMany({ where: { objectId: id }, data: { hidden: true } });
      await audit(tx, 'COMMUNITY_HIDDEN', 'CommunityEvent', id, actor.id);
      return { ok: true };
    });
  }
}
