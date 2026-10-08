import {
  Injectable,
  BadRequestException,
  ForbiddenException,
  NotFoundException,
} from '@nestjs/common';
import { Database, audit } from '../database';
import { Actor, AuthService } from '../auth/auth.service';
import { requestInclude, publicRequest } from '../requests/requests.service';
import { MediaService } from '../media/media.service';
import { platformFeatures } from '../public/platform-features';
import { formatPublicAddress } from './relief-point-address';
@Injectable()
export class ManagementService {
  constructor(
    private readonly db: Database,
    private readonly auth: AuthService,
    private readonly media: MediaService,
  ) {}
  orgs(actor: Actor, coordinator = false) {
    return actor.role === 'ADMIN'
      ? undefined
      : actor.memberships
          .filter(
            (m) =>
              m.approved && m.organization.active && (!coordinator || m.role === 'COORDINATOR'),
          )
          .map((m) => m.organizationId);
  }
  async dashboard(actor: Actor) {
    if (actor.role === 'PUBLIC') throw new ForbiddenException();
    const orgs = this.orgs(actor);
    const where = orgs ? { organizationId: { in: orgs } } : {};
    const [requests, points, deliveries, posts] = await Promise.all([
      this.db.reliefRequest.findMany({
        where,
        include: requestInclude,
        orderBy: { updatedAt: 'desc' },
        take: 100,
      }),
      this.db.reliefPoint.findMany({
        where: { ...where, active: true },
        select: {
          id: true,
          name: true,
          description: true,
          publicLocation: true,
          addressLine1: true,
          locality: true,
          landmark: true,
          postalCode: true,
          city: true,
          district: true,
          state: true,
          country: true,
          instructions: true,
          operatingHours: true,
          exactLocationApproved: true,
          latitude: true,
          longitude: true,
          organizationId: true,
        },
      }),
      this.db.donationCommitment.findMany({
        where: {
          request: where,
          status: { in: ['ORDER_PLACED', 'VOLUNTEER_CONFIRMED', 'IN_TRANSIT'] },
        },
        include: {
          request: {
            select: {
              publicId: true,
              title: true,
              unit: true,
              version: true,
              organizationId: true,
            },
          },
        },
        orderBy: { createdAt: 'desc' },
        take: 100,
      }),
      this.db.fieldUpdate.findMany({
        where,
        select: { id: true, caption: true, moderation: true },
        orderBy: { createdAt: 'desc' },
        take: 30,
      }),
    ]);
    return {
      requests: requests.map(publicRequest),
      points: points.map(({ latitude, longitude, ...point }) => ({
        ...point,
        ...(point.exactLocationApproved && latitude !== null && longitude !== null
          ? { latitude, longitude }
          : {}),
      })),
      posts,
      deliveries: deliveries.map((c) => ({
        id: c.id,
        quantity: c.quantity,
        receivedQuantity: c.receivedQuantity,
        status: c.status,
        provider: c.provider,
        externalOrderId: c.externalOrderId,
        eta: c.eta,
        notes: c.notes,
        request: c.request,
      })),
    };
  }
  async volunteers(actor: Actor) {
    const orgs = this.orgs(actor, true);
    if (orgs?.length === 0) throw new ForbiddenException();
    return this.db.organizationMembership.findMany({
      where: { ...(orgs ? { organizationId: { in: orgs } } : {}), role: 'VOLUNTEER' },
      select: {
        id: true,
        organizationId: true,
        approved: true,
        organization: { select: { name: true } },
        user: { select: { id: true, displayName: true, active: true, emailVerifiedAt: true } },
      },
    });
  }
  async approve(actor: Actor, membershipId: string, suspend = false) {
    return this.db.atomic(async (tx) => {
      const m = await tx.organizationMembership.findUnique({ where: { id: membershipId } });
      if (!m) throw new NotFoundException();
      this.auth.requireOrg(actor, m.organizationId, true);
      if (m.role !== 'VOLUNTEER')
        throw new ForbiddenException('This action only applies to volunteers.');
      await tx.organizationMembership.update({ where: { id: m.id }, data: { approved: !suspend } });
      await tx.volunteerProfile.upsert({
        where: { userId: m.userId },
        create: {
          userId: m.userId,
          approvedAt: suspend ? null : new Date(),
          suspendedAt: suspend ? new Date() : null,
        },
        update: {
          approvedAt: suspend ? undefined : new Date(),
          suspendedAt: suspend ? new Date() : null,
        },
      });
      // Coordinator approval attests to the invited account's email verification.
      if (!suspend)
        await tx.user.update({ where: { id: m.userId }, data: { emailVerifiedAt: new Date() } });
      else {
        await tx.session.updateMany({
          where: { userId: m.userId },
          data: { revokedAt: new Date() },
        });
        await tx.device.updateMany({
          where: { userId: m.userId },
          data: { revokedAt: new Date() },
        });
      }
      await audit(
        tx,
        suspend ? 'VOLUNTEER_SUSPENDED' : 'VOLUNTEER_APPROVED',
        'User',
        m.userId,
        actor.id,
        undefined,
        { organizationId: m.organizationId },
      );
      return { ok: true };
    });
  }
  async organizations(actor: Actor) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    return this.db.organization.findMany({ orderBy: { name: 'asc' } });
  }
  async features(actor: Actor) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    const [features, setting] = await Promise.all([
      platformFeatures(this.db),
      this.db.platformSetting.findUnique({ where: { key: 'feature.needs' } }),
    ]);
    return { features, updatedAt: setting?.updatedAt.toISOString() ?? null };
  }
  async setNeedsFeature(actor: Actor, enabled: boolean) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    return this.db.atomic(async (tx) => {
      const previous = await tx.platformSetting.findUnique({ where: { key: 'feature.needs' } });
      const oldValue = previous?.value as { enabled?: boolean } | undefined;
      if ((oldValue?.enabled ?? true) === enabled)
        return {
          features: { needs: enabled },
          updatedAt: previous?.updatedAt.toISOString() ?? null,
        };
      const setting = await tx.platformSetting.upsert({
        where: { key: 'feature.needs' },
        create: { key: 'feature.needs', value: { enabled }, updatedBy: actor.id },
        update: { value: { enabled }, updatedBy: actor.id },
      });
      await audit(
        tx,
        'PLATFORM_FEATURE_CHANGED',
        'PlatformSetting',
        'feature.needs',
        actor.id,
        { enabled: oldValue?.enabled ?? true },
        { enabled },
      );
      return { features: { needs: enabled }, updatedAt: setting.updatedAt.toISOString() };
    });
  }
  async createOrg(actor: Actor, name: string) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    return this.db.atomic(async (tx) => {
      const org = await tx.organization.create({ data: { name, verified: true } });
      await audit(tx, 'ORGANIZATION_CREATED', 'Organization', org.id, actor.id, undefined, {
        name,
      });
      return org;
    });
  }
  async createPoint(
    actor: Actor,
    input: {
      organizationId: string;
      name: string;
      description: string;
      addressLine1: string;
      locality: string;
      landmark?: string;
      postalCode: string;
      city: string;
      district: string;
      state: string;
      country: 'India';
      instructions: string;
      operatingHours: string;
      latitude?: number;
      longitude?: number;
      exactLocationApproved: boolean;
    },
  ) {
    this.auth.requireOrg(actor, input.organizationId, true);
    const publicLocation = formatPublicAddress(input);
    if (publicLocation.length > 500) throw new BadRequestException('Address is too long.');
    return this.db.atomic(async (tx) => {
      const p = await tx.reliefPoint.create({ data: { ...input, publicLocation } });
      await audit(tx, 'RELIEF_POINT_CREATED', 'ReliefPoint', p.id, actor.id, undefined, input);
      return p;
    });
  }
  async moderation(actor: Actor) {
    const orgs = this.orgs(actor, true);
    if (orgs?.length === 0) throw new ForbiddenException();
    return this.db.fieldUpdate.findMany({
      where: {
        ...(orgs ? { organizationId: { in: orgs } } : {}),
        moderation: { in: ['PENDING', 'APPROVED'] },
      },
      include: { media: true, author: { select: { displayName: true } } },
      orderBy: { createdAt: 'desc' },
      take: 100,
    });
  }
  async moderate(actor: Actor, id: string, action: 'APPROVED' | 'REJECTED' | 'HIDDEN') {
    const post = await this.db.fieldUpdate.findUnique({ where: { id }, include: { media: true } });
    if (!post) throw new NotFoundException();
    this.auth.requireOrg(actor, post.organizationId, true);
    return this.db.atomic(async (tx) => {
      await tx.$queryRaw`SELECT id FROM "FieldUpdate" WHERE id=${id} FOR UPDATE`;
      await tx.$queryRaw`SELECT id FROM "MediaAsset" WHERE "fieldUpdateId"=${id} ORDER BY id FOR UPDATE`;
      const p = await tx.fieldUpdate.findUnique({ where: { id }, include: { media: true } });
      if (!p) throw new NotFoundException();
      this.auth.requireOrg(actor, p.organizationId, true);
      if (
        p.participantName &&
        (await tx.communityEvent.findFirst({ where: { objectId: id, type: 'WITHDRAW' } }))
      )
        throw new ForbiddenException('The author withdrew this participant report.');
      if (action === 'APPROVED' && p.media.some((m) => m.processingState !== 'READY'))
        throw new ForbiddenException('Media is not ready for publication.');
      await this.media.publication(p.media, action === 'APPROVED');
      await tx.fieldUpdate.update({
        where: { id },
        data: { moderation: action, ...(action === 'APPROVED' ? { publishedAt: new Date() } : {}) },
      });
      await tx.mediaAsset.updateMany({
        where: { fieldUpdateId: id },
        data: { moderation: action },
      });
      await tx.communityEvent.updateMany({
        where: { objectId: id, type: 'REPORT' },
        data: { moderation: action },
      });
      await audit(
        tx,
        'FIELD_POST_MODERATED',
        'FieldUpdate',
        id,
        actor.id,
        { moderation: p.moderation },
        { moderation: action },
      );
      return { ok: true };
    });
  }
  async audits(actor: Actor) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    return this.db.auditEvent.findMany({ orderBy: { createdAt: 'desc' }, take: 200 });
  }
  async reports(actor: Actor) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    return this.db.moderationReport.findMany({ orderBy: { createdAt: 'desc' }, take: 100 });
  }
  async report(entityType: string, entityId: string, reason: string) {
    return this.db.moderationReport.create({
      data: { entityType, entityId, reason },
      select: { id: true },
    });
  }
  async setOrgActive(actor: Actor, id: string, active: boolean) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    return this.db.atomic(async (tx) => {
      const previous = await tx.organization.findUniqueOrThrow({ where: { id } });
      await tx.organization.update({ where: { id }, data: { active } });
      await audit(
        tx,
        'ORGANIZATION_STATUS_CHANGED',
        'Organization',
        id,
        actor.id,
        { active: previous.active },
        { active },
      );
      return { ok: true };
    });
  }
  async resolveReport(actor: Actor, id: string) {
    if (actor.role !== 'ADMIN') throw new ForbiddenException();
    return this.db.atomic(async (tx) => {
      await tx.moderationReport.update({ where: { id }, data: { status: 'RESOLVED' } });
      await audit(tx, 'ABUSE_REPORT_RESOLVED', 'ModerationReport', id, actor.id);
      return { ok: true };
    });
  }
  async listSessions(actor: Actor) {
    return this.db.session.findMany({
      where: { userId: actor.id, revokedAt: null, expiresAt: { gt: new Date() } },
      select: { id: true, label: true, createdAt: true, expiresAt: true },
    });
  }
  async revokeSession(actor: Actor, id: string) {
    await this.db.session.updateMany({
      where: { id, userId: actor.id },
      data: { revokedAt: new Date() },
    });
    return { ok: true };
  }
}
