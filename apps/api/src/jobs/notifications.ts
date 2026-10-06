import { Injectable, OnModuleInit, OnModuleDestroy, Logger } from '@nestjs/common';
import { Resend } from 'resend';
import { Queue, Worker } from 'bullmq';
import { env } from '@saathi/config';
import { Database } from '../database';
import { DonationsService } from '../donations/donations.service';
export function emailTemplate(kind: string, payload: unknown) {
  const p = (payload && typeof payload === 'object' ? payload : {}) as Record<string, unknown>;
  const titles: Record<string, string> = {
    DONATION_RESERVED: 'Your help is reserved',
    ORDER_RECORDED: 'Your delivery details are recorded',
    DELIVERY_CONFIRMED: 'Your help arrived ❤️',
    REQUEST_COMPLETED: 'This community need has been met',
    RESERVATION_EXPIRING: 'Your reservation expires soon',
    RESERVATION_EXPIRED: 'Your reservation has expired',
  };
  const subject = titles[kind] ?? 'An update from Saathi';
  const text =
    kind === 'DELIVERY_CONFIRMED'
      ? `${p.quantity} ${p.unit} were received by the volunteer team at ${p.point}.\n\nWhen people needed help, you showed up. Thank you for helping your community today.`
      : kind === 'REQUEST_COMPLETED'
        ? `${p.title} at ${p.point} has been fulfilled. Please do not send additional supplies. Thank you for helping.`
        : kind === 'DONATION_RESERVED'
          ? `${p.quantity} ${p.unit} reserved at ${p.point} until ${p.expiresAt}.\n\nRecord your order and track your contribution: ${p.trackingUrl}`
          : kind === 'ORDER_RECORDED'
            ? `Your ${p.quantity} supplies through ${p.provider} are recorded. The volunteer team can now see the delivery details.`
            : kind === 'RESERVATION_EXPIRING'
              ? `Your reservation of ${p.quantity} supplies expires at ${p.expiresAt}. Record your order before then, or the supplies will become available to other donors.`
              : `Your reservation has expired. Check the live request before placing an order.`;
  return { subject, text };
}
@Injectable()
export class Jobs implements OnModuleInit, OnModuleDestroy {
  private timer?: ReturnType<typeof setInterval>;
  private queue?: Queue;
  private worker?: Worker;
  private busy = false;
  private readonly log = new Logger(Jobs.name);
  private warnedRedis = false;
  private redisWarning() {
    if (!this.warnedRedis) {
      this.warnedRedis = true;
      this.log.warn('Redis unavailable; database maintenance fallback remains active.');
    }
  }
  constructor(
    private readonly db: Database,
    private readonly donations: DonationsService,
  ) {}
  async onModuleInit() {
    if (env.NODE_ENV === 'test') return;
    const url = new URL(env.REDIS_URL);
    const connection = {
      host: url.hostname,
      port: Number(url.port) || 6379,
      password: url.password || undefined,
      maxRetriesPerRequest: null,
      retryStrategy: (times: number) => Math.min(times * 1000, 10000),
    };
    this.queue = new Queue('saathi-maintenance', { connection });
    this.worker = new Worker('saathi-maintenance', async () => this.tick(), {
      connection,
      concurrency: 1,
    });
    this.queue.on('error', () => this.redisWarning());
    this.worker.on('error', () => this.redisWarning());
    void this.queue
      .upsertJobScheduler(
        'maintenance',
        { every: 30000 },
        { name: 'sweep', opts: { removeOnComplete: true, removeOnFail: 50 } },
      )
      .catch(() => {});
    // Database outbox and expiry remain functional when Redis is unavailable.
    this.timer = setInterval(() => {
      void this.tick().catch((e) => this.log.error(String(e)));
    }, 30000);
    this.timer.unref();
  }
  async tick() {
    if (this.busy) return;
    this.busy = true;
    try {
      await this.donations.sweep();
      await this.db.chatAttachment.deleteMany({ where: { expiresAt: { lte: new Date() } } });
      await this.db.chatAction.deleteMany({ where: { expiresAt: { lte: new Date() } } });
      await this.db.communityMediaChunk.deleteMany({ where: { expiresAt: { lte: new Date() } } });
      await this.flush();
    } finally {
      this.busy = false;
    }
  }
  async flush() {
    // Claim each notification under a database lock. Concurrent workers cannot double-send a normal attempt.
    const ids = await this.db.notification.findMany({
      where: { status: 'PENDING', nextAttemptAt: { lte: new Date() } },
      select: { id: true },
      take: 50,
    });
    for (const { id } of ids)
      await this.db.atomic(async (tx) => {
        await tx.$queryRaw`SELECT id FROM "Notification" WHERE id=${id} FOR UPDATE`;
        const n = await tx.notification.findUniqueOrThrow({ where: { id } });
        if (n.status !== 'PENDING' || n.nextAttemptAt > new Date()) return;
        try {
          const template = emailTemplate(n.kind, n.payload);
          if (env.EMAIL_PROVIDER === 'resend') {
            const result = await new Resend(env.RESEND_API_KEY).emails.send(
              { from: env.EMAIL_FROM, to: n.email, ...template },
              { idempotencyKey: n.id },
            );
            if (result.error) throw new Error(result.error.message);
          } else
            this.log.log(
              JSON.stringify({ event: 'email.local', notificationId: n.id, kind: n.kind }),
            );
          await tx.notification.update({
            where: { id },
            data: {
              status: env.EMAIL_PROVIDER === 'resend' ? 'SENT' : 'LOGGED',
              sentAt: env.EMAIL_PROVIDER === 'resend' ? new Date() : null,
              attempts: { increment: 1 },
              lastError: null,
            },
          });
        } catch (e) {
          await tx.notification.update({
            where: { id },
            data: {
              attempts: { increment: 1 },
              nextAttemptAt: new Date(
                Date.now() + Math.min(3600000, 30000 * 2 ** Math.min(n.attempts, 7)),
              ),
              lastError: String(e).slice(0, 500),
            },
          });
        }
      });
  }
  async onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
    await this.worker?.close(true);
    await this.queue?.close();
  }
}
