import { Injectable, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { PrismaClient, Prisma } from '@saathi/database';
import { env } from '@saathi/config';
@Injectable()
export class Database extends PrismaClient implements OnModuleInit, OnModuleDestroy {
  constructor() {
    super({ datasourceUrl: env.DATABASE_URL });
  }
  async onModuleInit() {
    await this.$connect();
  }
  async onModuleDestroy() {
    await this.$disconnect();
  }
  async atomic<T>(operation: (tx: Prisma.TransactionClient) => Promise<T>): Promise<T> {
    for (let attempt = 0; ; attempt++) {
      try {
        return await this.$transaction(operation, {
          isolationLevel: Prisma.TransactionIsolationLevel.ReadCommitted,
          timeout: 15000,
          maxWait: 10000,
        });
      } catch (error) {
        if (
          !(error instanceof Prisma.PrismaClientKnownRequestError) ||
          !['P2034', 'P2002'].includes(error.code) ||
          attempt >= 4
        )
          throw error;
      }
    }
  }
}
export const json = (value: unknown): Prisma.InputJsonValue =>
  JSON.parse(JSON.stringify(value)) as Prisma.InputJsonValue;
export async function audit(
  tx: Prisma.TransactionClient,
  event: string,
  entityType: string,
  entityId: string,
  actorId?: string,
  previous?: unknown,
  next?: unknown,
) {
  await tx.auditEvent.create({
    data: {
      event,
      entityType,
      entityId,
      actorId,
      previous: previous === undefined ? undefined : json(previous),
      next: next === undefined ? undefined : json(next),
    },
  });
}
