import { randomBytes } from 'node:crypto';
import { PrismaClient } from '@prisma/client';
import { env } from '@saathi/config';
import { hashPassword } from '@saathi/auth';
import { bootstrapAdmin } from './admin-bootstrap';

function argumentsFromCli(args: string[]) {
  const additionalAdminFlag = '--allow-additional-admin';
  const additionalAdminFlagCount = args.filter((arg) => arg === additionalAdminFlag).length;
  const optionArgs = args.filter((arg) => arg !== additionalAdminFlag);
  const options = new Map<string, string>();
  for (let index = 0; index < optionArgs.length; index += 2) {
    const key = optionArgs[index];
    const value = optionArgs[index + 1];
    if (!key?.startsWith('--') || !value || value.startsWith('--') || options.has(key))
      throw new Error(
        'Usage: create-admin --email <email> --name <display name> [--allow-additional-admin]',
      );
    options.set(key, value);
  }
  const email = options.get('--email')?.trim().toLowerCase();
  const displayName = options.get('--name')?.trim();
  if (
    additionalAdminFlagCount > 1 ||
    options.size !== 2 ||
    !email ||
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
    email.length > 254 ||
    email.endsWith('@saathi.test') ||
    !displayName ||
    displayName.length > 80
  )
    throw new Error(
      'Usage: create-admin --email <email> --name <display name> [--allow-additional-admin]',
    );
  return { email, displayName, allowAdditionalAdministrators: additionalAdminFlagCount === 1 };
}

async function main() {
  if (env.APP_ENV === 'development' || env.DEMO_MODE !== 'false')
    throw new Error('Admin bootstrap only runs against a non-demo staging or production database.');

  const { email, displayName, allowAdditionalAdministrators } = argumentsFromCli(
    process.argv.slice(2),
  );
  const password = randomBytes(24).toString('base64url');
  const passwordHash = await hashPassword(password);
  const db = new PrismaClient({ datasourceUrl: env.DATABASE_URL });
  try {
    const user = await bootstrapAdmin(db, {
      email,
      displayName,
      passwordHash,
      allowAdditionalAdministrators,
    });
    console.log(`Admin created: ${user.email}`);
    console.log(`Initial password: ${password}`);
    console.log('Save this password in a password manager; it will not be shown again.');
  } finally {
    await db.$disconnect();
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : 'Admin bootstrap failed.');
  process.exitCode = 1;
});
