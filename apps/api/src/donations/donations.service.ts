import {
  Injectable,
  ConflictException,
  NotFoundException,
  BadRequestException,
} from '@nestjs/common';
import { Prisma } from '@saathi/database';
import { token, digest } from '@saathi/auth';
import { env } from '@saathi/config';
import { Database, audit, json } from '../database';
import { AuthService, Actor } from '../auth/auth.service';
import { closed, deriveStatus } from '../domain/request';
import { publicRequest, requestInclude } from '../requests/requests.service';
type Result = {
  id: string;
  trackingToken: string;
  trackingUrl: string;
  expiresAt: string;
  quantity: number;
};
const activeStatuses = ['RESERVED', 'ORDER_PLACED', 'VOLUNTEER_CONFIRMED', 'IN_TRANSIT'] as const;
@Injectable()
export class DonationsService {
  constructor(
    private readonly db: Database,
    private readonly auth: AuthService,
  ) {}
  async idempotent<T>(
    scope: string,
    key: string,
    input: unknown,
    operation: (tx: Prisma.TransactionClient) => Promise<T>,
  ): Promise<T> {
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(key))
      throw new BadRequestException('Supply a UUID Idempotency-Key.');
    const requestHash = digest(JSON.stringify(input));
    return this.db.atomic(async (tx) => {
      await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${scope + ':' + key},0))`;
      const record = await tx.idempotencyRecord.findUnique({
        where: { scope_key: { scope, key } },
      });
      if (record) {
        if (record.requestHash !== requestHash)
          throw new ConflictException('Idempotency key was used with different input.');
        return record.response as T;
      }
      const response = await operation(tx);
      await tx.idempotencyRecord.create({
        data: { scope, key, requestHash, response: json(response) },
      });
      return response;
    });
  }
  async expireFor(tx: Prisma.TransactionClient, requestId: string) {
    const expired = await tx.donationCommitment.findMany({
      where: { requestId, status: 'RESERVED', expiresAt: { lte: new Date() } },
    });
    for (const c of expired) {
      await tx.donationCommitment.update({ where: { id: c.id }, data: { status: 'EXPIRED' } });
      await tx.reliefRequest.update({
        where: { id: requestId },
        data: { committedQuantity: { decrement: c.quantity }, version: { increment: 1 } },
      });
      await audit(tx, 'RESERVATION_EXPIRED', 'DonationCommitment', c.id, undefined, undefined, {
        quantity: c.quantity,
      });
      await this.notify(tx, c.email, 'RESERVATION_EXPIRED', c.id, { quantity: c.quantity });
    }
    if (expired.length) {
      const r = await tx.reliefRequest.findUniqueOrThrow({ where: { id: requestId } });
      await tx.reliefRequest.update({
        where: { id: requestId },
        data: {
          status: deriveStatus(
            r.requestedQuantity,
            r.committedQuantity,
            r.receivedQuantity,
            r.status,
          ),
        },
      });
    }
  }
  async reserve(
    publicId: string,
    quantity: number,
    email: string | undefined,
    key: string,
  ): Promise<Result> {
    return this.idempotent(
      `reserve:${publicId}`,
      key,
      { publicId, quantity, email },
      async (tx) => {
        await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE "publicId"=${publicId} FOR UPDATE`;
        let r = await tx.reliefRequest.findUnique({ where: { publicId }, include: requestInclude });
        if (!r) throw new NotFoundException('Request not found.');
        await this.expireFor(tx, r.id);
        r = await tx.reliefRequest.findUniqueOrThrow({
          where: { id: r.id },
          include: requestInclude,
        });
        if (
          closed.has(r.status) ||
          r.status === 'DRAFT' ||
          r.expiresAt <= new Date() ||
          !r.organization.active ||
          !r.organization.verified
        )
          throw new ConflictException('This request is closed. Please choose another active need.');
        if (quantity > r.requestedQuantity - r.committedQuantity)
          throw new ConflictException(
            `Only ${r.requestedQuantity - r.committedQuantity} ${r.unit} remain. Refresh and choose a smaller quantity.`,
          );
        const trackingToken = token(),
          expiresAt = new Date(
            Math.min(Date.now() + env.RESERVATION_MINUTES * 60000, r.expiresAt.getTime()),
          );
        const c = await tx.donationCommitment.create({
          data: { requestId: r.id, quantity, email, tokenHash: digest(trackingToken), expiresAt },
        });
        const next = await tx.reliefRequest.update({
          where: { id: r.id },
          data: {
            committedQuantity: { increment: quantity },
            version: { increment: 1 },
            status: deriveStatus(
              r.requestedQuantity,
              r.committedQuantity + quantity,
              r.receivedQuantity,
              r.status,
            ),
          },
        });
        await audit(tx, 'DONATION_RESERVED', 'DonationCommitment', c.id, undefined, undefined, {
          requestPublicId: publicId,
          quantity,
        });
        await audit(
          tx,
          'REQUEST_QUANTITY_UPDATED',
          'ReliefRequest',
          r.id,
          undefined,
          { committedQuantity: r.committedQuantity },
          { committedQuantity: next.committedQuantity },
        );
        const trackingUrl = `${env.PUBLIC_URL}/contribution/${trackingToken}`;
        await this.notify(tx, email, 'DONATION_RESERVED', c.id, {
          quantity,
          unit: r.unit,
          point: r.reliefPoint.name,
          trackingUrl,
          expiresAt: expiresAt.toISOString(),
        });
        return {
          id: c.id,
          trackingToken,
          trackingUrl,
          expiresAt: expiresAt.toISOString(),
          quantity,
        };
      },
    );
  }
  async tracking(raw: string) {
    const c = await this.db.donationCommitment.findUnique({
      where: { tokenHash: digest(raw) },
      include: {
        request: { include: requestInclude },
        deliveries: { select: { quantity: true, receivedAt: true } },
      },
    });
    if (!c) throw new NotFoundException('Contribution link not found.');
    return {
      id: c.id,
      quantity: c.quantity,
      receivedQuantity: c.receivedQuantity,
      status: c.status === 'RESERVED' && c.expiresAt <= new Date() ? 'EXPIRED' : c.status,
      expiresAt: c.expiresAt.toISOString(),
      provider: c.provider,
      externalOrderId: c.externalOrderId,
      eta: c.eta?.toISOString() ?? null,
      request: publicRequest(c.request),
      deliveries: c.deliveries,
    };
  }
  async order(
    raw: string,
    input: { provider: string; externalOrderId: string; eta: string; notes?: string },
    key: string,
  ) {
    return this.idempotent(`order:${digest(raw)}`, key, input, async (tx) => {
      const initial = await tx.donationCommitment.findUnique({ where: { tokenHash: digest(raw) } });
      if (!initial) throw new NotFoundException();
      await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE id=${initial.requestId} FOR UPDATE`;
      await this.expireFor(tx, initial.requestId);
      const c = await tx.donationCommitment.findUniqueOrThrow({ where: { id: initial.id } });
      if (c.status !== 'RESERVED')
        throw new ConflictException(
          'Reservation expired or order already recorded. Check your contribution before placing an order.',
        );
      const r = await tx.reliefRequest.findUniqueOrThrow({
        where: { id: c.requestId },
        include: { organization: true },
      });
      if (
        closed.has(r.status) ||
        r.expiresAt <= new Date() ||
        !r.organization.active ||
        !r.organization.verified
      )
        throw new ConflictException(
          'Request is closed or its organization is unavailable. Contact the relief point before sending supplies.',
        );
      await tx.donationCommitment.update({
        where: { id: c.id },
        data: { ...input, eta: new Date(input.eta), status: 'ORDER_PLACED' },
      });
      await audit(tx, 'ORDER_ADDED', 'DonationCommitment', c.id, undefined, undefined, {
        provider: input.provider,
        eta: input.eta,
      });
      await this.notify(tx, c.email, 'ORDER_RECORDED', c.id, {
        quantity: c.quantity,
        provider: input.provider,
      });
      return { ok: true };
    });
  }
  async confirm(actor: Actor, id: string, key: string) {
    return this.idempotent(`confirm:${actor.id}:${id}`, key, { id }, async (tx) => {
      const initial = await tx.donationCommitment.findUnique({ where: { id } });
      if (!initial) throw new NotFoundException();
      await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE id=${initial.requestId} FOR UPDATE`;
      const c = await tx.donationCommitment.findUnique({
        where: { id },
        include: { request: true },
      });
      if (!c) throw new NotFoundException();
      this.auth.requireOrg(actor, c.request.organizationId);
      if (c.status !== 'ORDER_PLACED')
        throw new ConflictException('Only placed orders can be confirmed.');
      await tx.donationCommitment.update({
        where: { id },
        data: { status: 'VOLUNTEER_CONFIRMED' },
      });
      await this.updatePhase(tx, c.requestId);
      await audit(tx, 'VOLUNTEER_CONFIRMED', 'DonationCommitment', id, actor.id);
      return { ok: true };
    });
  }
  async inTransit(actor: Actor, id: string, key: string) {
    return this.idempotent(`transit:${actor.id}:${id}`, key, { id }, async (tx) => {
      const initial = await tx.donationCommitment.findUnique({ where: { id } });
      if (!initial) throw new NotFoundException();
      await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE id=${initial.requestId} FOR UPDATE`;
      const c = await tx.donationCommitment.findUnique({
        where: { id },
        include: { request: true },
      });
      if (!c) throw new NotFoundException();
      this.auth.requireOrg(actor, c.request.organizationId);
      if (c.status !== 'VOLUNTEER_CONFIRMED')
        throw new ConflictException('Confirm the order first.');
      await tx.donationCommitment.update({ where: { id }, data: { status: 'IN_TRANSIT' } });
      await this.updatePhase(tx, c.requestId);
      await audit(tx, 'ORDER_IN_TRANSIT', 'DonationCommitment', id, actor.id);
      return { ok: true };
    });
  }
  async receive(actor: Actor, id: string, quantity: number, version: number, key: string) {
    return this.idempotent(`receive:${actor.id}:${id}`, key, { quantity, version }, async (tx) => {
      const first = await tx.donationCommitment.findUnique({ where: { id } });
      if (!first) throw new NotFoundException();
      await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE id=${first.requestId} FOR UPDATE`;
      const c = await tx.donationCommitment.findUniqueOrThrow({
        where: { id },
        include: { request: { include: requestInclude } },
      });
      const r = c.request;
      this.auth.requireOrg(actor, r.organizationId);
      if (
        !activeStatuses.includes(c.status as (typeof activeStatuses)[number]) ||
        ['RESERVED', 'ORDER_PLACED'].includes(c.status)
      )
        throw new ConflictException('Record and confirm the order before receiving it.');
      if (r.version !== version)
        throw new ConflictException('Quantities changed. Refresh before confirming delivery.');
      if (quantity > c.quantity - c.receivedQuantity)
        throw new ConflictException('Quantity exceeds the undelivered commitment.');
      // Late goods are recorded even after cancellation/expiry. Closed state never reopens.
      await tx.delivery.create({ data: { commitmentId: id, quantity, confirmedBy: actor.id } });
      await tx.donationCommitment.update({
        where: { id },
        data: {
          receivedQuantity: { increment: quantity },
          status: c.receivedQuantity + quantity === c.quantity ? 'DELIVERED' : c.status,
        },
      });
      const status = deriveStatus(
        r.requestedQuantity,
        r.committedQuantity,
        r.receivedQuantity + quantity,
        r.status,
      );
      const next = await tx.reliefRequest.update({
        where: { id: r.id },
        data: {
          receivedQuantity: { increment: quantity },
          version: { increment: 1 },
          status,
          archivedAt: status === 'COMPLETED' ? new Date() : r.archivedAt,
        },
        include: requestInclude,
      });
      await audit(tx, 'DELIVERY_RECEIVED', 'DonationCommitment', id, actor.id, undefined, {
        quantity,
        requestPublicId: r.publicId,
      });
      await audit(
        tx,
        'REQUEST_QUANTITY_UPDATED',
        'ReliefRequest',
        r.id,
        actor.id,
        { receivedQuantity: r.receivedQuantity },
        { receivedQuantity: next.receivedQuantity },
      );
      await this.notify(tx, c.email, 'DELIVERY_CONFIRMED', `${id}:${next.receivedQuantity}`, {
        quantity,
        unit: r.unit,
        point: r.reliefPoint.name,
        time: new Date().toISOString(),
      });
      if (status === 'COMPLETED' && r.status !== 'COMPLETED') {
        await audit(tx, 'REQUEST_COMPLETED', 'ReliefRequest', r.id, actor.id);
        const donors = await tx.donationCommitment.findMany({
          where: { requestId: r.id, email: { not: null } },
        });
        for (const donor of donors)
          await this.notify(tx, donor.email, 'REQUEST_COMPLETED', donor.id, {
            title: r.title,
            point: r.reliefPoint.name,
          });
      }
      return { ok: true, request: publicRequest(next) };
    });
  }
  async cancel(raw: string, key: string) {
    return this.idempotent(`cancel:${digest(raw)}`, key, {}, async (tx) => {
      const first = await tx.donationCommitment.findUnique({ where: { tokenHash: digest(raw) } });
      if (!first) throw new NotFoundException();
      await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE id=${first.requestId} FOR UPDATE`;
      const c = await tx.donationCommitment.findUniqueOrThrow({ where: { id: first.id } });
      if (c.status !== 'RESERVED')
        throw new ConflictException(
          'Only unplaced reservations may be cancelled. Contact the team about an order already placed.',
        );
      await tx.donationCommitment.update({ where: { id: c.id }, data: { status: 'CANCELLED' } });
      const r = await tx.reliefRequest.update({
        where: { id: c.requestId },
        data: { committedQuantity: { decrement: c.quantity }, version: { increment: 1 } },
      });
      await tx.reliefRequest.update({
        where: { id: r.id },
        data: {
          status: deriveStatus(
            r.requestedQuantity,
            r.committedQuantity,
            r.receivedQuantity,
            r.status,
          ),
        },
      });
      await audit(tx, 'DONATION_CANCELLED', 'DonationCommitment', c.id);
      return { ok: true };
    });
  }
  async notify(
    tx: Prisma.TransactionClient,
    email: string | null | undefined,
    kind: string,
    reference: string,
    payload: unknown,
  ) {
    if (!email) return;
    await tx.notification.upsert({
      where: { deduplicationKey: `${kind}:${reference}` },
      create: { kind, email, payload: json(payload), deduplicationKey: `${kind}:${reference}` },
      update: {},
    });
  }
  async updatePhase(tx: Prisma.TransactionClient, requestId: string) {
    const r = await tx.reliefRequest.findUniqueOrThrow({ where: { id: requestId } });
    let status = deriveStatus(
      r.requestedQuantity,
      r.committedQuantity,
      r.receivedQuantity,
      r.status,
    );
    if (status === 'FULLY_COMMITTED') {
      const commitments = await tx.donationCommitment.findMany({
        where: {
          requestId,
          status: { in: ['RESERVED', 'ORDER_PLACED', 'VOLUNTEER_CONFIRMED', 'IN_TRANSIT'] },
        },
      });
      if (commitments.every((c) => ['VOLUNTEER_CONFIRMED', 'IN_TRANSIT'].includes(c.status)))
        status = commitments.some((c) => c.status === 'IN_TRANSIT') ? 'IN_TRANSIT' : 'CONFIRMED';
    }
    await tx.reliefRequest.update({
      where: { id: requestId },
      data: { status, version: { increment: 1 } },
    });
  }
  async sweep() {
    const ids = await this.db.donationCommitment.findMany({
      where: { status: 'RESERVED', expiresAt: { lte: new Date() } },
      select: { requestId: true },
      distinct: ['requestId'],
      take: 100,
    });
    for (const { requestId } of ids)
      await this.db.atomic(async (tx) => {
        await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE id=${requestId} FOR UPDATE`;
        await this.expireFor(tx, requestId);
      });
    const expiring = await this.db.donationCommitment.findMany({
      where: {
        status: 'RESERVED',
        expiresAt: { gt: new Date(), lte: new Date(Date.now() + 300000) },
        email: { not: null },
      },
      take: 100,
    });
    for (const c of expiring)
      await this.db.atomic((tx) =>
        this.notify(tx, c.email, 'RESERVATION_EXPIRING', c.id, {
          quantity: c.quantity,
          expiresAt: c.expiresAt.toISOString(),
        }),
      );
    const requests = await this.db.reliefRequest.findMany({
      where: {
        status: { notIn: ['DRAFT', 'COMPLETED', 'CANCELLED', 'EXPIRED'] },
        expiresAt: { lte: new Date() },
      },
      select: { id: true },
      take: 100,
    });
    for (const { id } of requests)
      await this.db.atomic(async (tx) => {
        await tx.$queryRaw`SELECT id FROM "ReliefRequest" WHERE id=${id} FOR UPDATE`;
        const r = await tx.reliefRequest.findUniqueOrThrow({ where: { id } });
        if (closed.has(r.status)) return;
        await tx.reliefRequest.update({
          where: { id },
          data: { status: 'EXPIRED', archivedAt: new Date(), version: { increment: 1 } },
        });
        await audit(tx, 'REQUEST_EXPIRED', 'ReliefRequest', id);
      });
  }
}
