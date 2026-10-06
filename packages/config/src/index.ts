import { config } from 'dotenv';
import { resolve } from 'node:path';
import { z } from 'zod';
const workspaceRoot = resolve(__dirname, '../../..');
config({ path: resolve(workspaceRoot, '.env'), quiet: true });
const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  DATABASE_URL: z.string().url(),
  REDIS_URL: z.string().url().default('redis://localhost:6379'),
  API_PORT: z.coerce.number().default(4000),
  WEB_ORIGIN: z.string().url().default('http://localhost:3000'),
  PUBLIC_URL: z.string().url().default('http://localhost:3000'),
  PLATFORM_NAME: z.string().default('CJP Swarm'),
  APP_ENV: z
    .enum(['development', 'staging', 'production'])
    .default(process.env.NODE_ENV === 'production' ? 'production' : 'development'),
  API_PLANE: z.enum(['combined', 'public', 'operational']).default('combined'),
  SERVICE_ROLE: z.enum(['api', 'media-worker', 'seed']).default('api'),
  MEDIA_PROCESSING_MODE: z.enum(['inline', 'worker']).default('inline'),
  SYNC_SIGNING_PRIVATE_JWK: z.string().optional(),
  SYNC_RECEIPT_KEYRING_JSON: z.string().optional(),
  SERVICE_CONFIG_JSON: z.string().optional(),
  SERVICE_CONFIG_ROOT_PUBLIC_JWK: z.string().optional(),
  REQUEST_ID_PREFIX: z
    .string()
    .regex(/^[A-Z]{2,6}$/)
    .default('SAA'),
  RESERVATION_MINUTES: z.coerce.number().int().min(1).max(120).default(20),
  SESSION_DAYS: z.coerce.number().int().min(1).max(30).default(7),
  DEMO_MODE: z.enum(['true', 'false']).default('false'),
  EMAIL_PROVIDER: z.enum(['log', 'resend']).default('log'),
  RESEND_API_KEY: z.string().optional(),
  EMAIL_FROM: z.string().default('Saathi <help@example.org>'),
  S3_ENDPOINT: z.string().url().default('http://localhost:9000'),
  S3_REGION: z.string().default('us-east-1'),
  S3_ACCESS_KEY: z.string().default('saathi_local'),
  S3_SECRET_KEY: z.string().default('saathi_local_storage'),
  S3_PRIVATE_BUCKET: z.string().default('saathi-originals'),
  S3_PUBLIC_BUCKET: z.string().default('saathi-public'),
  S3_PUBLIC_URL: z.string().url().default('http://localhost:9000/saathi-public'),
  MEDIA_SCAN_PROVIDER: z.enum(['disabled', 'clamav']).default('disabled'),
  CLAMAV_HOST: z.string().default('localhost'),
  CLAMAV_PORT: z.coerce.number().default(3310),
  FFMPEG_PATH: z.string().default('ffmpeg'),
  STORAGE_PROVIDER: z.enum(['s3', 'local']).default('s3'),
  LOCAL_MEDIA_DIR: z
    .string()
    .default('.data/media')
    .transform((directory) => resolve(workspaceRoot, directory)),
});
export const env = schema.parse(process.env);
if (env.APP_ENV === 'production' && env.API_PLANE !== 'public') {
  if (
    env.DEMO_MODE === 'true' ||
    env.MEDIA_SCAN_PROVIDER === 'disabled' ||
    (env.SERVICE_ROLE === 'api' && (env.EMAIL_PROVIDER !== 'resend' || !env.RESEND_API_KEY)) ||
    !env.WEB_ORIGIN.startsWith('https://') ||
    env.S3_SECRET_KEY === 'saathi_local_storage' ||
    env.STORAGE_PROVIDER === 'local' ||
    env.MEDIA_PROCESSING_MODE !== 'worker'
  )
    throw new Error(
      'Production requires HTTPS, real email, malware scanning, non-demo data and private S3 storage credentials.',
    );
}
if (
  env.APP_ENV !== 'development' &&
  (!env.WEB_ORIGIN.startsWith('https://') || !env.PUBLIC_URL.startsWith('https://'))
)
  throw new Error('Hosted staging and production require HTTPS public and browser origins.');
if (
  env.APP_ENV !== 'development' &&
  env.SERVICE_ROLE === 'api' &&
  env.API_PLANE !== 'public' &&
  (!env.SYNC_SIGNING_PRIVATE_JWK ||
    !env.SYNC_RECEIPT_KEYRING_JSON ||
    !env.SERVICE_CONFIG_JSON ||
    !env.SERVICE_CONFIG_ROOT_PUBLIC_JWK)
)
  throw new Error(
    'Staging/production operations require durable signing secrets, a receipt keyring and signed service configuration.',
  );
