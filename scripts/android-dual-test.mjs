/* global indexedDB -- Playwright callback executes in the third-peer fixture browser. */
import { createServer } from 'node:http';
import { chromium, expect } from '@playwright/test';
import { hash } from '../packages/protocol/dist/index.js';
import { execFileSync, spawn } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { randomBytes, createHash } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { setTimeout, clearTimeout } from 'node:timers';
import { join } from 'node:path';
import { parse } from 'dotenv';

// Explicitly opt in with two approved model names; never select somebody's phone implicitly.
const [authorModel, carrierModel, transport = 'nearby', extraPeer] = process.argv.slice(2);
if (!authorModel || !carrierModel || !['nearby', 'wifi'].includes(transport)) throw new Error('Usage: node scripts/android-dual-test.mjs AUTHOR_MODEL CARRIER_MODEL nearby|wifi');
if (extraPeer && (extraPeer !== 'browser' || transport !== 'wifi')) throw new Error('The optional browser third peer requires wifi mode.');
const adb = join(process.env.ANDROID_HOME || join(process.env.LOCALAPPDATA, 'Android', 'Sdk'), 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const devices = execFileSync(adb, ['devices'], { encoding: 'utf8' }).split('\n').filter(x => /\tdevice\s*$/.test(x)).map(x => x.split('\t')[0]);
const models = devices.map(device => [device, execFileSync(adb, ['-s', device, 'shell', 'getprop', 'ro.product.model'], { encoding: 'utf8' }).trim()]);
const selected = [authorModel, carrierModel].map(model => models.find(([, value]) => value === model)?.[0]);
if (selected.some(x => !x) || selected[0] === selected[1]) throw new Error('Two distinct authorized requested devices are required.');
const password = parse(readFileSync('.env', 'utf8')).SEED_PASSWORD;
if (!password) throw new Error('Set a development fixture password locally; never put it in an APK.');
const token = randomBytes(24).toString('hex');
const pending = new Map(), measurements = []; let completed = false;
const browser = extraPeer ? await chromium.launch({ headless: true }) : null;
const page = browser ? await (await browser.newContext()).newPage() : null;
const browserEvents = () => page.evaluate(() => new Promise((resolve, reject) => { const request = indexedDB.open('saathi-offline-v1'); request.onerror = () => reject(request.error); request.onsuccess = () => { const result = request.result.transaction('events').objectStore('events').getAll(); result.onsuccess = () => { resolve(result.result); request.result.close(); }; result.onerror = () => reject(result.error); }; }));
async function thirdPeerStep(path, value) {
  if (!page) return null;
  if (path.startsWith('/browser-offer-')) {
    await page.goto('http://localhost:3000/nearby');
    await page.getByLabel('Import the other person’s invitation or reply').fill(value.description);
    await page.getByRole('button', { name: 'Use invitation or reply' }).click();
    const reply = page.getByLabel('Invitation or reply to share'); await expect(reply).toHaveValue(/^SAATHI1:/, { timeout: 20000 });
    return { reply: await reply.inputValue() };
  }
  if (path.startsWith('/browser-code-')) {
    await expect(page.getByRole('heading', { name: 'Connected nearby', exact: true })).toBeVisible({ timeout: 20000 });
    expect((await page.locator('.pairing-code').textContent()).trim()).toBe(value.code);
    await page.getByRole('button', { name: 'The codes match' }).click();
  } else if (path === '/browser-original-event') {
    await expect.poll(async () => (await browserEvents()).some(x => x.id === value.eventId), { timeout: 20000 }).toBe(true);
    const event = (await browserEvents()).find(x => x.id === value.eventId);
    expect(event.hops).toBe(2); expect(await hash(event.envelope)).toBe(value.eventHash);
    await page.goto('http://localhost:3000/connectivity');
    await page.getByRole('checkbox', { name: 'Allow this phone to send eligible public relief updates' }).check();
    await page.getByRole('button', { name: 'Send and check saved relief updates' }).click();
    await expect.poll(async () => (await browserEvents()).find(x => x.id === value.eventId)?.receipt?.body.status, { timeout: 20000 }).toBe('PUBLISHED');
  } else if (path === '/browser-share-receipt') {
    await page.getByRole('button', { name: 'Share saved relief updates and confirmations' }).click();
  } else if (path === '/browser-receipt-returned') {
    await page.getByRole('button', { name: 'Disconnect nearby' }).click();
  }
  return null;
}
const server = createServer(async (req, res) => {
  if (req.headers.authorization !== `Bearer ${token}`) { res.writeHead(403).end(); return; }
  try {
    let length = 0; const chunks = [];
    for await (const chunk of req) { length += chunk.length; if (length > 32768) throw new Error('Fixture request too large.'); chunks.push(chunk); }
    const input = JSON.parse(Buffer.concat(chunks).toString());
    if (!['author', 'carrier'].includes(input.role) || !/^\/[a-z0-9-]+$/.test(req.url)) throw new Error('Invalid fixture step.');
    let step = pending.get(req.url);
    if (!step) { step = { created: Date.now(), roles: {} }; pending.set(req.url, step); }
    if (step.roles[input.role]) throw new Error('Duplicate fixture barrier.');
    step.roles[input.role] = { value: input.value, res };
    const timer = setTimeout(() => { if (!res.writableEnded) res.writeHead(504).end('{}'); }, 140000); res.on('close', () => clearTimeout(timer));
    if (step.roles.author && step.roles.carrier) {
      const browserReply = await thirdPeerStep(req.url, step.roles.carrier.value);
      const safe = { step: req.url.slice(1), milliseconds: Date.now() - step.created };
      for (const role of ['author', 'carrier']) {
        const current = step.roles[role], other = step.roles[role === 'author' ? 'carrier' : 'author'];
        current.res.writeHead(200, { 'Content-Type': 'application/json' }); current.res.end(JSON.stringify(role === 'carrier' && browserReply ? browserReply : other.value));
        if (typeof current.value.internetValidated === 'boolean') safe[`${role}InternetValidated`] = current.value.internetValidated;
        if (typeof current.value.audioBytes === 'number') safe[`${role}AudioBytes`] = current.value.audioBytes;
        if (typeof current.value.videoFrames === 'number') safe[`${role}VideoFrames`] = current.value.videoFrames;
      }
      measurements.push(safe); console.log(`Physical ${transport}: ${safe.step}`);
      if (req.url === '/complete') completed = true;
    }
  } catch { if (!res.writableEnded) res.writeHead(500).end('{}'); }
});
await new Promise(resolve => server.listen(4010, '127.0.0.1', resolve));
const processes = [];
try {
  for (const [index, device] of selected.entries()) {
    console.log(`Preparing ${index === 0 ? 'author' : 'carrier'} product app.`);
    const run = (...args) => execFileSync(adb, ['-s', device, ...args], { stdio: 'pipe', timeout: args[0] === 'install' ? 120000 : 20000 });
    const install = (packageId, path) => {
      const localHash = createHash('sha256').update(readFileSync(path)).digest('hex');
      const location = run('shell', 'pm', 'path', packageId).toString().trim().replace(/^package:/, '');
      const installedHash = /^\/data\/app\/[A-Za-z0-9_~+/.=-]+$/.test(location) ? run('shell', 'sha256sum', location).toString().split(/\s+/)[0] : '';
      if (installedHash !== localHash) run('install', '-r', path);
    };
    install('org.saathi.android.dev', 'apps/android/app/build/outputs/apk/debug/app-debug.apk');
    install('org.saathi.android.dev.test', 'apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk');
    run('reverse', 'tcp:4000', 'tcp:4000'); run('reverse', 'tcp:4010', 'tcp:4010');
    for (const permission of ['BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'BLUETOOTH_ADVERTISE', 'NEARBY_WIFI_DEVICES', ...(transport === 'wifi' ? ['RECORD_AUDIO', 'CAMERA'] : [])]) run('shell', 'pm', 'grant', 'org.saathi.android.dev', `android.permission.${permission}`);
    console.log(`${index === 0 ? 'Author' : 'Carrier'} product app ready.`);
  }
  console.log('Both approved devices are installed and ready for the physical fixture.');
  for (const [index, device] of selected.entries()) {
    const quote = value => "'" + value.replace(/'/g, "'\\''") + "'";
    const child = spawn(adb, ['-s', device, 'shell', 'am', 'instrument', '-w', '-e', 'class', 'org.saathi.android.NativeDualTest', '-e', 'dualFixture', 'true', '-e', 'role', index === 0 ? 'author' : 'carrier', '-e', 'transport', transport, '-e', 'browserRelay', extraPeer ? 'true' : 'false', '-e', 'bridgeToken', token, '-e', 'fixturePassword', quote(password), 'org.saathi.android.dev.test/androidx.test.runner.AndroidJUnitRunner'], { windowsHide: true });
    let output = ''; child.stdout.on('data', x => output += x); child.stderr.on('data', x => output += x);
    const timer = setTimeout(() => child.kill(), 600000);
    processes.push(new Promise(resolve => child.once('close', code => { clearTimeout(timer); const safe = output.replaceAll(password, '[redacted]').replaceAll(token, '[redacted]'); console.log(`${index === 0 ? 'Author' : 'Carrier'} instrumentation:\n${safe}`); resolve(code === 0 && /OK \(1 test\)/.test(output)); })));
  }
  const results = await Promise.all(processes);
  if (!completed || results.some(x => !x)) process.exitCode = 1;
} finally {
  server.close(); for (const step of pending.values()) for (const item of Object.values(step.roles)) if (!item.res.writableEnded) item.res.writeHead(503).end('{}');
  await browser?.close();
  mkdirSync('.data/android-measurements', { recursive: true });
  writeFileSync(`.data/android-measurements/dual-${transport}${extraPeer ? '-browser' : ''}.json`, JSON.stringify({ testedAt: new Date().toISOString(), models: [authorModel, carrierModel], transport, thirdPeer: extraPeer ? 'Windows Chromium' : null, completed, steps: measurements }, null, 2));
}
