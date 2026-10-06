import { createApp } from './app';
import { env } from '@saathi/config';
async function main() {
  if (env.SERVICE_ROLE !== 'api') throw new Error('HTTP API requires SERVICE_ROLE=api.');
  const app = await createApp();
  // Node's 5-minute default would cut off a 250 MB upload on a slow connection.
  (app.getHttpServer() as import('node:http').Server).requestTimeout = 2 * 60 * 60 * 1000;
  try {
    await app.listen(env.API_PORT, '0.0.0.0');
  } catch (error) {
    await app.close();
    throw error;
  }
}
void main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
