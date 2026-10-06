import type { PrismaClient } from '@saathi/database';

type SettingReader = Pick<PrismaClient, 'platformSetting'>;

export async function platformFeatures(db: SettingReader) {
  const row = await db.platformSetting.findUnique({ where: { key: 'feature.needs' } });
  const value = row?.value as { enabled?: unknown } | undefined;
  return { needs: typeof value?.enabled === 'boolean' ? value.enabled : true };
}
