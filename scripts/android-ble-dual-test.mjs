import { createServer } from 'node:http';
import { execFileSync, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { Buffer } from 'node:buffer';
import { clearTimeout, setTimeout } from 'node:timers';

const androidHome = process.env.ANDROID_HOME ?? join(process.env.LOCALAPPDATA ?? '', 'Android', 'Sdk');
const adb = join(androidHome, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const root = process.cwd();
const appApk = join(root, 'apps', 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
const testApk = join(root, 'apps', 'android', 'app', 'build', 'outputs', 'apk', 'androidTest', 'debug', 'app-debug-androidTest.apk');
const port = 4012;
const token = randomBytes(24).toString('hex');
const allowedSteps = new Set(['pair-code', 'pair-confirmed', 'message-a-to-b', 'message-b-to-a', 'complete']);
const barriers = new Map();
let bridgeClosed = false;

function run(args, timeout = 120_000) {
  try {
    return execFileSync(adb, args, { encoding: 'utf8', timeout, stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  } catch {
    throw new Error('ADB command failed for an approved test device; command output is redacted.');
  }
}

function requireArtifact(path) {
  if (!existsSync(path)) throw new Error('Build the debug app and instrumentation APK first: :app:assembleDebug :app:assembleDebugAndroidTest');
}

function deviceFor(model) {
  const devices = run(['devices', '-l']).split(/\r?\n/).filter(line => /\sdevice\s/.test(line));
  const matches = devices.flatMap(line => {
    const id = line.trim().split(/\s+/)[0];
    return run(['-s', id, 'shell', 'getprop', 'ro.product.model']) === model ? [id] : [];
  });
  if (matches.length !== 1) throw new Error(`Connect exactly one authorized ${model} with USB debugging.`);
  return matches[0];
}

function rendezvousServer() {
  const server = createServer(async (request, response) => {
    if (request.method !== 'POST' || request.headers.authorization !== `Bearer ${token}`) {
      response.writeHead(403).end();
      return;
    }
    const step = new URL(request.url, 'http://127.0.0.1').pathname.slice(1);
    if (!allowedSteps.has(step)) {
      response.writeHead(404).end();
      return;
    }
    let size = 0;
    const chunks = [];
    for await (const chunk of request) {
      size += chunk.length;
      if (size > 4096) {
        response.writeHead(413).end();
        return;
      }
      chunks.push(chunk);
    }
    let input;
    try { input = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
    catch { response.writeHead(400).end(); return; }
    const { role, value } = input;
    if (!['author', 'carrier'].includes(role) || !value || typeof value !== 'object') {
      response.writeHead(400).end();
      return;
    }
    let barrier = barriers.get(step);
    if (!barrier) {
      let release;
      const ready = new Promise(resolve => { release = resolve; });
      barrier = { values: {}, ready, release };
      barriers.set(step, barrier);
    }
    if (Object.hasOwn(barrier.values, role)) {
      response.writeHead(409).end();
      return;
    }
    barrier.values[role] = value;
    if (barrier.values.author && barrier.values.carrier) barrier.release();
    const timer = setTimeout(() => barrier.release(), 90_000);
    await barrier.ready;
    clearTimeout(timer);
    const ready = Boolean(barrier.values.author && barrier.values.carrier);
    const result = step === 'pair-code'
      ? { matching: barrier.values.author?.code === barrier.values.carrier?.code, values: barrier.values }
      : { values: barrier.values };
    response.writeHead(ready ? 200 : 408, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    response.end(JSON.stringify(result));
    console.log(`BLE test barrier: ${step} (${ready ? '2/2' : 'timed out'})`);
    if (step === 'complete' && ready && !bridgeClosed) {
      bridgeClosed = true;
      server.close();
    }
  });
  server.requestTimeout = 100_000;
  return server;
}

function runInstrumentation(device, role) {
  const args = [
    '-s', device, 'shell', 'am', 'instrument', '-w',
    '-e', 'class', 'org.saathi.android.NativeBleDualTest#bluetoothOnlyPairAndAcknowledgedMessagesInBothDirections',
    '-e', 'dualFixture', 'true', '-e', 'role', role, '-e', 'bridgeToken', token,
    'org.saathi.android.dev.test/androidx.test.runner.AndroidJUnitRunner',
  ];
  return new Promise((resolve, reject) => {
    const child = spawn(adb, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });
    child.on('error', () => reject(new Error(`Instrumentation could not start on ${role}.`)));
    child.on('close', code => {
      if (code === 0 && /OK \(1 test\)/.test(output)) resolve({ role, output: output.replaceAll(token, '[redacted]') });
      else reject(new Error(`${role} BLE instrumentation failed; review local test output.\n${output.replaceAll(token, '[redacted]')}`));
    });
  });
}

requireArtifact(appApk);
requireArtifact(testApk);
const phone = deviceFor('SM-S921B');
const tablet = deviceFor('SM-X510');
for (const [device, model] of [[phone, 'S24'], [tablet, 'Tab S9']]) {
  if (run(['-s', device, 'shell', 'settings', 'get', 'global', 'wifi_on']) !== '0') {
    throw new Error(`Turn Wi-Fi off on the ${model} before starting the Bluetooth-only test; no radio setting was changed.`);
  }
  if (run(['-s', device, 'shell', 'settings', 'get', 'global', 'bluetooth_on']) !== '1') {
    throw new Error(`Turn Bluetooth on on the ${model} before starting the Bluetooth-only test.`);
  }
}

run(['-s', phone, 'install', '-r', appApk]);
run(['-s', tablet, 'install', '-r', appApk]);
run(['-s', phone, 'install', '-r', testApk]);
run(['-s', tablet, 'install', '-r', testApk]);
run(['-s', phone, 'reverse', `tcp:${port}`, `tcp:${port}`]);
run(['-s', tablet, 'reverse', `tcp:${port}`, `tcp:${port}`]);

const server = rendezvousServer();
await new Promise((resolve, reject) => {
  server.once('error', () => reject(new Error(`The local test bridge could not bind port ${port}.`)));
  server.listen(port, '127.0.0.1', resolve);
});
console.log('Running Bluetooth-only Swarm pairing and message proof on S24 and Tab S9.');
try {
  const results = await Promise.all([runInstrumentation(phone, 'author'), runInstrumentation(tablet, 'carrier')]);
  for (const result of results) console.log(result.output.trim());
  console.log('Physical BLE/GATT dual-device test passed. The script leaves Wi-Fi and screen settings unchanged.');
} finally {
  if (server.listening) await new Promise(resolve => server.close(resolve));
  for (const device of [phone, tablet]) {
    try { run(['-s', device, 'reverse', '--remove', `tcp:${port}`]); } catch { /* the device may have disconnected after the test */ }
  }
}
