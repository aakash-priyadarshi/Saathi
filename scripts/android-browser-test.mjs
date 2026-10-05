/* global window, indexedDB -- Playwright callbacks execute in the fixture browser. */
import { createServer } from 'node:http';
import { execFileSync, spawn } from 'node:child_process';
import { chromium, expect } from '@playwright/test';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { parse } from 'dotenv';
import { hash } from '../packages/protocol/dist/index.js';
import { gunzipSync } from 'node:zlib';
import { join } from 'node:path';
import { createHash, randomBytes } from 'node:crypto';
import { Buffer } from 'node:buffer';
import { setTimeout, clearTimeout } from 'node:timers';
const target = process.argv[2];
if (!target) throw new Error('Usage: node scripts/android-browser-test.mjs APPROVED_MODEL');
const token = randomBytes(24).toString('hex');
const adb = join(process.env.ANDROID_HOME || join(process.env.LOCALAPPDATA, 'Android', 'Sdk'), 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const devices = execFileSync(adb, ['devices'], { encoding: 'utf8' }).split('\n').filter(x => /\tdevice\s*$/.test(x)).map(x => x.split('\t')[0]);
const device = devices.find(id => execFileSync(adb, ['-s', id, 'shell', 'getprop', 'ro.product.model'], { encoding: 'utf8' }).trim() === target);
if (!device) throw new Error('Requested test device is unavailable.');
const run = (...args) => { try { return execFileSync(adb, ['-s', device, ...args], { stdio: 'pipe', timeout: 120000 }); } catch { throw new Error('ADB operation failed on the approved model; no device identifier printed.'); } };
const install = (packageId, path) => {
  const localHash = createHash('sha256').update(readFileSync(path)).digest('hex');
  const location = run('shell', 'pm', 'path', packageId).toString().trim().replace(/^package:/, '');
  const installedHash = /^\/data\/app\/[A-Za-z0-9_~+/.=-]+$/.test(location) ? run('shell', 'sha256sum', location).toString().split(/\s+/)[0] : '';
  if (installedHash !== localHash) run('install', '-r', path);
};
install('org.saathi.android.dev', 'apps/android/app/build/outputs/apk/debug/app-debug.apk');
install('org.saathi.android.dev.test', 'apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk');
run('reverse', 'tcp:4009', 'tcp:4009'); run('reverse', 'tcp:4000', 'tcp:4000');
const browser = await chromium.launch({ headless: true, args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] });
const context = await browser.newContext({ permissions: ['camera', 'microphone'], viewport: { width: 1280, height: 900 } });
await context.addInitScript(() => { const Original = window.RTCPeerConnection; window.RTCPeerConnection = class extends Original { constructor(...args) { super(...args); Reflect.set(window, '__nativeFixturePeer', this); } }; });
const page = await context.newPage();
const results = []; let completed = false;
const records = store => page.evaluate(store => new Promise((resolve, reject) => { const r = indexedDB.open('saathi-offline-v1'); r.onerror = () => reject(r.error); r.onsuccess = () => { const q = r.result.transaction(store).objectStore(store).getAll(); q.onsuccess = () => { resolve(q.result); r.result.close(); }; q.onerror = () => reject(q.error); }; }), store);
const server = createServer(async (req, res) => {
  if (req.headers.authorization !== `Bearer ${token}`) { res.writeHead(403).end(); return; }
  const started = Date.now();
  try {
    let length = 0; const chunks = []; for await (const chunk of req) { length += chunk.length; if (length > 32768) throw new Error('Fixture body too large.'); chunks.push(chunk); } const input = JSON.parse(Buffer.concat(chunks).toString());
    let output = {};
    if (req.url === '/offer') {
      await page.goto('http://localhost:3000/nearby');
      await page.getByLabel('Import the other person’s invitation or reply').fill(input.invitation);
      await page.getByRole('button', { name: 'Use invitation or reply' }).click();
      const reply = page.getByLabel('Invitation or reply to share'); await expect(reply).toHaveValue(/^SAATHI1:/, { timeout: 20000 });
      output = { reply: await reply.inputValue() };
      const summarize = encoded => { const sdp = JSON.parse(gunzipSync(Buffer.from(encoded.slice(8), 'base64url')).toString()).sdp; const lines = sdp.split('\n').filter(x => x.startsWith('a=candidate:')); return { candidates: lines.length, multicastNames: lines.filter(x => /\.local\b/.test(x)).length, udp: lines.filter(x => / udp /i.test(x)).length }; };
      console.log('Pairing candidate summary:', JSON.stringify({ native: summarize(input.invitation), browser: summarize(output.reply) }));
    } else if (req.url === '/confirm') {
      await expect(page.getByRole('heading', { name: 'Connected nearby', exact: true })).toBeVisible({ timeout: 20000 });
      expect((await page.locator('.pairing-code').textContent()).trim()).toBe(input.code);
      await page.getByRole('button', { name: 'The codes match' }).click();
    } else if (req.url === '/message') {
      await expect(page.getByText('Native fixture message ✓', { exact: true })).toBeVisible();
      await page.getByLabel('Message', { exact: true }).fill('Browser fixture reply ✓');
      await page.getByRole('button', { name: 'Send nearby message' }).click();
    } else if (req.url === '/file-to-native') {
      await page.getByLabel('Offer a small image or text file').setInputFiles({ name: 'browser-fixture.txt', mimeType: 'text/plain', buffer: Buffer.alloc(1048576, 65) });
    } else if (req.url === '/file-to-browser') {
      await expect(page.getByText('native-fixture.txt · 1024 KB', { exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Receive or resume attachment' }).click();
      await expect(page.getByText('native-fixture.txt · Checked and saved on this phone', { exact: true })).toBeVisible({ timeout: 60000 });
      const files = await records('attachments'); expect(files.find(x => x.name === 'native-fixture.txt').hash).toBe(input.hash);
    } else if (req.url === '/event') {
      await expect.poll(async () => (await records('events')).some(x => x.id === input.id), { timeout: 20000 }).toBe(true);
      const event = (await records('events')).find(x => x.id === input.id); expect(await hash(event.envelope)).toBe(input.hash); expect(event.hops).toBe(1);
    } else if (req.url === '/disconnect') {
      await page.getByRole('button', { name: 'Disconnect nearby' }).click();
    } else if (req.url === '/reconnect') {
      await expect(page.getByText('Native reconnect fixture', { exact: true })).toBeVisible();
    } else if (req.url === '/diagnostic') console.log('Native connection diagnostics:', JSON.stringify(input));
    else if (req.url === '/complete') completed = true;
    else throw new Error('Unknown test fixture step.');
    results.push({ step: req.url.slice(1), milliseconds: Date.now() - started });
    console.log(`Native/browser fixture: ${req.url.slice(1)}`);
    res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(output));
  } catch { console.error('Browser fixture step failed:', req.url); res.writeHead(500); res.end('{}'); }
});
await new Promise(resolve => server.listen(4009, '127.0.0.1', resolve));
const password = parse(readFileSync('.env', 'utf8')).SEED_PASSWORD; if (!password) throw new Error('A local fictional fixture password is required.'); const escaped = "'" + password.replace(/'/g, "'\\''") + "'";
const processTest = spawn(adb, ['-s', device, 'shell', 'am', 'instrument', '-w', '-e', 'class', 'org.saathi.android.NativeBrowserTest', '-e', 'browserBridge', 'true', '-e', 'bridgeToken', token, '-e', 'fixturePassword', escaped, 'org.saathi.android.dev.test/androidx.test.runner.AndroidJUnitRunner'], { windowsHide: true });
let output = ''; processTest.stdout.on('data', x => { output += x; }); processTest.stderr.on('data', x => { output += x; });
const timer = setTimeout(() => { processTest.kill(); }, 240000);
const diagnosticTimer = setTimeout(async () => { console.log('Browser connection diagnostics:', JSON.stringify(await page.evaluate(async () => { const peer = Reflect.get(window, '__nativeFixturePeer'); if (!peer) return {}; const stats = [...(await peer.getStats()).values()]; return { signaling: peer.signalingState, ice: peer.iceConnectionState, data: peer.connectionState, pairs: stats.filter(s => s.type === 'candidate-pair').map(s => ({ state: s.state, sent: s.requestsSent, responses: s.responsesReceived })) }; }))); }, 45000);
const code = await new Promise(resolve => processTest.once('close', resolve)); clearTimeout(timer);
clearTimeout(diagnosticTimer);
console.log(output.replaceAll(password, '[redacted]').replaceAll(token, '[redacted]'));
mkdirSync('.data/android-measurements', { recursive: true }); writeFileSync(`.data/android-measurements/${target}-browser.json`, JSON.stringify({ testedAt: new Date().toISOString(), target, completed, steps: results }, null, 2));
await browser.close(); server.close();
if (code !== 0 || !completed || !/OK \(1 test\)/.test(output)) process.exitCode = 1;
