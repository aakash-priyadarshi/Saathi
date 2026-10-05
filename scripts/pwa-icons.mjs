import sharp from 'sharp';
import { fileURLToPath } from 'node:url';
const input = new URL('../apps/web/public/icons/saathi.svg', import.meta.url);
for (const size of [192, 512])
  await sharp(fileURLToPath(input))
    .resize(size, size)
    .png()
    .toFile(fileURLToPath(new URL(`../apps/web/public/icons/saathi-${size}.png`, import.meta.url)));
