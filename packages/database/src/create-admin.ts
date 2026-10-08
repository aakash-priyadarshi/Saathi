import { randomBytes } from 'node:crypto';
import { PrismaClient } from '@prisma/client';
import { env } from '@saathi/config';
import { hashPassword } from '@saathi/auth';

function argumentsFromCli(args: string[]) {
  const options = new Map<string, string>();
  for (let index = 0; index < args.length; index += 2) {
    const key = args[index];
    const value = args[index + 1];
    if (!key?.startsWith('--') || !value || value.startsWith('--') || options.has(key))
      throw new Error('Usage: create-admin --email <email> --name <display name>');
    options.set(key, value);
  }
  const email = options.get('--email')?.trim().toLowerCase();
  const displayName = options.get('--name')?.trim();
  if (
    options.size !== 2 ||
    !email ||
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
    email.length > 254 ||
    !displayName ||
    displayName.length > 80
  )
    throw new Error('Usage: create-admin --email <email> --name <display name>');
  return { email, displayName };
}

async function main() {
  if (env.APP_ENV === 'development' || env.DEMO_MODE !== 'false')
    throw new Error('Admin bootstrap only runs against a non-demo staging or production database.');

  const { email, displayName } = argumentsFromCli(process.argv.slice(2));
  const password = randomBytes(24).toString('base64url');
  const passwordHash = await hashPassword(password);
  const db = new PrismaClient({ datasourceUrl: env.DATABASE_URL });
  try {
    const user = await db.$transaction(async (tx) => {
      const existing = await tx.user.findUnique({ where: { email } });
      if (existing) throw new Error('That email already has an account; no changes were made.');
      const created = await tx.user.create({
        data: {
          email,
          displayName,
          passwordHash,
          role: 'ADMIN',
          active: true,
          emailVerifiedAt: new Date(),
        },
        select: { id: true, email: true },
      });
      await tx.auditEvent.create({
        data: {
          event: 'ADMIN_BOOTSTRAPPED',
          entityType: 'User',
          entityId: created.id,
          next: { email: created.email, role: 'ADMIN' },
        },
      });
      return created;
    });
    console.log(`Admin created: ${user.email}`);
    console.log(`One-time password: ${password}`);
    console.log('Save this password in a password manager; it will not be shown again.');
  } finally {
    await db.$disconnect();
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : 'Admin bootstrap failed.');
  process.exitCode = 1;
});
