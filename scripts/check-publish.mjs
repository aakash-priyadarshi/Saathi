import { readFileSync } from 'node:fs';
import { Buffer } from 'node:buffer';
import { spawnSync } from 'node:child_process';
import { parse } from 'dotenv';

const listing = spawnSync('git', ['ls-files', '-z'], { encoding: 'utf8' });
if (listing.status !== 0) throw new Error('Run the publication check inside the Git repository.');
const paths = listing.stdout.split('\0').filter(Boolean);
const local = parse(readFileSync('.env', 'utf8'));
const example = parse(readFileSync('.env.example', 'utf8'));
const secrets = Object.entries(local)
  .filter(
    ([key, value]) =>
      /TOKEN|SECRET|PASSWORD|PRIVATE.*KEY|GITHUB_PAT|ACCESS_KEY/i.test(key) &&
      value.length >= 16 &&
      value !== example[key],
  )
  .map(([, value]) => Buffer.from(value));
const failures = [];
for (const path of paths) {
  if (
    (/(^|\/)\.env(?:\.|$)/.test(path) && !path.endsWith('.env.example')) ||
    /(^|\/)(\.data|node_modules|test-results|playwright-report|\.gradle)(\/|$)/.test(path) ||
    /\.(?:jks|keystore|pem|apk|aab)$/.test(path)
  )
    failures.push(`${path}: private/generated file`);
  const bytes = readFileSync(path);
  if (secrets.some((secret) => bytes.includes(secret)))
    failures.push(`${path}: local credential detected`);
  if (/github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{30,}/.test(bytes.toString('utf8')))
    failures.push(`${path}: GitHub credential pattern detected`);
}
if (failures.length) {
  console.error(failures.join('\n'));
  process.exitCode = 1;
} else
  console.log(
    `Publication check passed for ${paths.length} tracked files; local credentials excluded.`,
  );
