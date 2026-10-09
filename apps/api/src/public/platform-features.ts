import type { PrismaClient } from '@saathi/database';

type SettingReader = Pick<PrismaClient, 'platformSetting'>;

export async function platformFeatures(db: SettingReader) {
  const rows = await db.platformSetting.findMany({
    where: { key: { in: ['feature.live', 'feature.needs'] } },
  });
  const settings = new Map(rows.map((row) => [row.key, row.value as { enabled?: unknown }]));
  const enabled = (key: string) => {
    const value = settings.get(key)?.enabled;
    return typeof value === 'boolean' ? value : false;
  };
  return { live: enabled('feature.live'), needs: enabled('feature.needs') };
}
