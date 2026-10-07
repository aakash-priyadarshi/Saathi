import { Injectable, NotFoundException } from '@nestjs/common';
import { z } from 'zod';
import { env } from '@saathi/config';
import { Prisma } from '@saathi/database';
import type { PublicRequest, PublicPost } from '@saathi/types';
import { Database } from '../database';
import { closed } from '../domain/request';
import { platformFeatures } from './platform-features';
export const requestInclude = {
  organization: true,
  reliefPoint: true,
  creator: { select: { displayName: true } },
} satisfies Prisma.ReliefRequestInclude;
export type ExpandedRequest = Prisma.ReliefRequestGetPayload<{ include: typeof requestInclude }>;
export function publicRequest(r: ExpandedRequest): PublicRequest {
  const expired = r.expiresAt <= new Date() && !closed.has(r.status);
  return {
    publicId: r.publicId,
    title: r.title,
    description: r.description,
    category: r.category,
    unit: r.unit,
    requestedQuantity: r.requestedQuantity,
    committedQuantity: r.committedQuantity,
    receivedQuantity: r.receivedQuantity,
    remainingQuantity:
      closed.has(r.status) || expired ? 0 : r.requestedQuantity - r.committedQuantity,
    priority: r.priority,
    status: expired ? 'EXPIRED' : r.status,
    version: r.version,
    deadline: r.deadline.toISOString(),
    updatedAt: r.updatedAt.toISOString(),
    canonicalUrl: `${env.PUBLIC_URL}/r/${r.publicId}`,
    deliveryLocation: r.deliveryLocation,
    organization: {
      name: r.organization.name,
      verified: r.organization.verified && r.organization.active,
    },
    creator: r.creator,
    reliefPoint: {
      name: r.reliefPoint.name,
      publicLocation: r.deliveryLocation ?? r.reliefPoint.publicLocation,
      instructions: r.reliefPoint.instructions,
      operatingHours: r.reliefPoint.operatingHours,
      ...(r.reliefPoint.exactLocationApproved &&
      r.reliefPoint.latitude !== null &&
      r.reliefPoint.longitude !== null
        ? { latitude: r.reliefPoint.latitude, longitude: r.reliefPoint.longitude }
        : {}),
    },
  };
}
@Injectable()
export class PublicReadService {
  constructor(private readonly db: Database) {}
  async configuration() {
    return {
      platformName: env.PLATFORM_NAME,
      demo: env.DEMO_MODE === 'true',
      reservationMinutes: env.RESERVATION_MINUTES,
      features: await platformFeatures(this.db),
    };
  }
  async list(completed = false, category?: string) {
    if (!(await platformFeatures(this.db)).needs) return [];
    const where: Prisma.ReliefRequestWhereInput = {
      organization: { active: true, verified: true },
      status: completed ? 'COMPLETED' : { notIn: ['DRAFT', 'COMPLETED', 'CANCELLED', 'EXPIRED'] },
      ...(completed ? {} : { expiresAt: { gt: new Date() } }),
    };
    if (category)
      where.category = z
        .enum(['FOOD', 'WATER', 'MEDICAL', 'HYGIENE', 'CLOTHING', 'POWER', 'SHELTER', 'OTHER'])
        .parse(category);
    return (
      await this.db.reliefRequest.findMany({
        where,
        include: requestInclude,
        orderBy: [{ priority: 'desc' }, { deadline: 'asc' }],
        take: 100,
      })
    ).map(publicRequest);
  }
  async get(publicId: string) {
    if (!(await platformFeatures(this.db)).needs)
      throw new NotFoundException('Relief needs are temporarily paused.');
    const r = await this.db.reliefRequest.findUnique({
      where: { publicId },
      include: requestInclude,
    });
    if (!r || r.status === 'DRAFT')
      throw new NotFoundException('Request not found. Check the ID and try again.');
    return { ...publicRequest(r), verified: r.organization.verified && r.organization.active };
  }
  async feed(): Promise<PublicPost[]> {
    const posts = await this.db.fieldUpdate.findMany({
      where: {
        moderation: 'APPROVED',
        publishAt: { lte: new Date() },
        OR: [
          { organization: { active: true, verified: true } },
          { organizationId: null, participantName: { not: null } },
        ],
      },
      include: {
        organization: true,
        author: { select: { displayName: true } },
        reliefPoint: true,
        request: { select: { publicId: true } },
        media: { where: { moderation: 'APPROVED', processingState: 'READY' } },
      },
      orderBy: { createdAt: 'desc' },
      take: 50,
    });
    const participants = await this.db.communityEvent.findMany({
      where: {
        id: { in: posts.filter((p) => p.participantName).map((p) => p.id) },
        type: 'REPORT',
      },
      select: { id: true, authorId: true },
    });
    const base = env.STORAGE_PROVIDER === 'local' ? '/api/v1/public/media' : env.S3_PUBLIC_URL;
    return posts.map((p) => ({
      id: p.id,
      caption: p.caption,
      createdAt: p.createdAt.toISOString(),
      organization: {
        name: p.organization?.name ?? 'Participant report',
        verified: p.organization?.verified ?? false,
      },
      author: p.author ?? { displayName: p.participantName ?? 'Participant' },
      reliefPoint: {
        name: p.reliefPoint?.name ?? p.publicArea ?? 'Public area',
        publicLocation: p.reliefPoint?.publicLocation ?? p.publicArea ?? 'Public area',
      },
      verificationState: p.participantName ? 'PARTICIPANT' : 'VERIFIED',
      participantId: participants.find((r) => r.id === p.id)?.authorId ?? null,
      receivedAt: p.receivedAt.toISOString(),
      publishedAt: p.publishedAt?.toISOString() ?? p.publishAt.toISOString(),
      contentWarning: p.contentWarning,
      requestPublicId: p.request?.publicId ?? null,
      media: p.media
        .filter((m) => m.publicKey)
        .map((m) => ({
          id: m.id,
          url: `${base}/${m.publicKey}`,
          thumbnailUrl: m.thumbnailKey ? `${base}/${m.thumbnailKey}` : null,
          mimeType: m.mimeType,
        })),
    }));
  }
}
