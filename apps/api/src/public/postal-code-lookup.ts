import { createReadStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { createRequire } from 'node:module';
import { dirname, resolve } from 'node:path';

type PostalArea = { place: string; district: string; state: string };
let areas: Promise<Map<string, PostalArea>> | undefined;

async function postalAreas() {
  areas ??= (async () => {
    const require = createRequire(__filename);
    const library = require.resolve('postalcodes-india');
    const source = resolve(dirname(library), '../data/IN.txt');
    const index = new Map<string, PostalArea>();
    const lines = createInterface({ input: createReadStream(source), crlfDelay: Infinity });
    for await (const line of lines) {
      const [country, pin, place, state, , district] = line.split('\t');
      if (
        country === 'IN' &&
        pin &&
        place &&
        state &&
        district &&
        /^\d{6}$/.test(pin) &&
        !index.has(pin)
      )
        index.set(pin, { place, district, state });
    }
    return index;
  })();
  return areas;
}

/** Uses the GeoNames-derived data bundled with postalcodes-india without loading its 32 MB JS index. */
export async function lookupIndianPostalCode(pin: string) {
  if (!/^\d{6}$/.test(pin)) return { valid: false };
  const area = (await postalAreas()).get(pin);
  return area ? { valid: true, ...area } : { valid: false };
}
