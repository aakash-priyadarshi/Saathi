import { createApp } from './app';
import { env } from '@saathi/config';
async function main() {
  if (env.SERVICE_ROLE !== 'api') throw new Error('HTTP API requires SERVICE_ROLE=api.');
  const app = await createApp();
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
