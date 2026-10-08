import { Prisma, type PrismaClient } from '@prisma/client';

const bootstrapLock = [1397248340, 1212766532] as const;
const seededAdminEmail = 'admin@saathi.test';

export async function bootstrapAdmin(
  db: PrismaClient,
  account: { email: string; displayName: string; passwordHash: string },
) {
  return db.$transaction(
    async (tx) => {
      await tx.$queryRaw`SELECT pg_advisory_xact_lock(${bootstrapLock[0]}::int, ${bootstrapLock[1]}::int) IS NULL`;
      const existingAccount = await tx.user.findUnique({ where: { email: account.email } });
      if (existingAccount)
        throw new Error('That email already has an account; no changes were made.');

      const administrators = await tx.user.findMany({
        where: { role: 'ADMIN' },
        select: { id: true, email: true },
      });
      const seededAdmins = administrators.filter((admin) => admin.email === seededAdminEmail);
      if (
        seededAdmins.length > 1 ||
        administrators.some((admin) => admin.email !== seededAdminEmail)
      )
        throw new Error('An administrator already exists; no changes were made.');

      const demoAdmin = seededAdmins[0];
      const user = demoAdmin
        ? await tx.user.update({
            where: { id: demoAdmin.id },
            data: {
              email: account.email,
              displayName: account.displayName,
              passwordHash: account.passwordHash,
              active: true,
              emailVerifiedAt: new Date(),
              totpSecret: null,
            },
            select: { id: true, email: true },
          })
        : await tx.user.create({
            data: {
              email: account.email,
              displayName: account.displayName,
              passwordHash: account.passwordHash,
              role: 'ADMIN',
              active: true,
              emailVerifiedAt: new Date(),
            },
            select: { id: true, email: true },
          });

      if (demoAdmin)
        await tx.session.updateMany({
          where: { userId: demoAdmin.id, revokedAt: null },
          data: { revokedAt: new Date() },
        });
      await tx.auditEvent.create({
        data: {
          event: 'ADMIN_BOOTSTRAPPED',
          entityType: 'User',
          entityId: user.id,
          previous: demoAdmin
            ? { email: demoAdmin.email, role: 'ADMIN', fixture: true }
            : undefined,
          next: { email: user.email, role: 'ADMIN' },
        },
      });
      return user;
    },
    { isolationLevel: Prisma.TransactionIsolationLevel.Serializable },
  );
}
