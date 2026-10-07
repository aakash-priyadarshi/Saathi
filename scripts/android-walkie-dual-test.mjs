import { createServer } from 'node:http';
import { execFileSync, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { Buffer } from 'node:buffer';
import { setTimeout, clearTimeout } from 'node:timers';

const sdk = process.env.ANDROID_HOME ?? join(process.env.LOCALAPPDATA, 'Android', 'Sdk');
const adb = join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const token = randomBytes(24).toString('hex');
const port = 4014;
const barriers = new Map();
const measurements = [];
const allowed = new Set([
  'prepared',
  'offer',
  'reply',
  'code',
  'ready',
  'author-audio',
  'author-released',
  'carrier-audio',
  'carrier-released',
  'switch-talking',
  'recipient-switch-stopped',
  'ready-again',
  'background-talking',
  'complete',
]);
const run = (args, timeout = 120000) =>
  execFileSync(adb, args, { timeout, stdio: ['ignore', 'pipe', 'pipe'] });
const ids = run(['devices'])
  .toString()
  .split(/\r?\n/)
  .filter((line) => /\tdevice\s*$/.test(line))
  .map((line) => line.split('\t')[0]);
const devices = ['SM-S921B', 'SM-X510'].map((model) => {
  const matches = ids.filter(
    (id) => run(['-s', id, 'shell', 'getprop', 'ro.product.model']).toString().trim() === model,
  );
  if (matches.length !== 1) throw new Error(`Exactly one authorized ${model} is required.`);
  return matches[0];
});
for (const id of devices) {
  if (!/inet \d/.test(run(['-s', id, 'shell', 'ip', '-4', 'addr', 'show', 'wlan0']).toString()))
    throw new Error('Both approved devices need a shared Wi-Fi network.');
}
const originalAwake = devices.map((id) =>
  run(['-s', id, 'shell', 'settings', 'get', 'global', 'stay_on_while_plugged_in'])
    .toString()
    .trim(),
);
const server = createServer(async (request, response) => {
  const step = new URL(request.url, 'http://localhost').pathname.slice(1);
  if (
    request.method !== 'POST' ||
    request.headers.authorization !== `Bearer ${token}` ||
    !allowed.has(step)
  ) {
    response.writeHead(403).end();
    return;
  }
  const chunks = [];
  let size = 0;
  for await (const part of request) {
    size += part.length;
    if (size > 64000) {
      response.writeHead(413).end();
      return;
    }
    chunks.push(part);
  }
  let input;
  try {
    input = JSON.parse(Buffer.concat(chunks).toString());
  } catch {
    response.writeHead(400).end();
    return;
  }
  if (
    !['author', 'carrier'].includes(input.role) ||
    !input.value ||
    typeof input.value !== 'object'
  ) {
    response.writeHead(400).end();
    return;
  }
  let barrier = barriers.get(step);
  if (!barrier) {
    let release;
    const ready = new Promise((resolve) => {
      release = resolve;
    });
    barrier = { values: {}, release, ready };
    barriers.set(step, barrier);
  }
  if (Object.hasOwn(barrier.values, input.role)) {
    response.writeHead(409).end();
    return;
  }
  barrier.values[input.role] = input.value;
  if (barrier.values.author && barrier.values.carrier) barrier.release();
  const timer = setTimeout(barrier.release, 90000);
  await barrier.ready;
  clearTimeout(timer);
  const complete = Boolean(barrier.values.author && barrier.values.carrier);
  response.writeHead(complete ? 200 : 408, {
    'content-type': 'application/json',
    'cache-control': 'no-store',
  });
  response.end(
    JSON.stringify(barrier.values[input.role === 'author' ? 'carrier' : 'author'] ?? {}),
  );
  if (input.role === 'author') {
    const safe = { step, complete };
    if (step.endsWith('-audio')) {
      safe.authorAudioBytes = barrier.values.author?.audioBytes;
      safe.carrierAudioBytes = barrier.values.carrier?.audioBytes;
    }
    measurements.push(safe);
    console.log(`Physical walkie-talkie: ${step} (${complete ? '2/2' : 'timeout'})`);
  }
});
function instrument(id, role) {
  const child = spawn(
    adb,
    [
      '-s',
      id,
      'shell',
      'am',
      'instrument',
      '-w',
      '-e',
      'class',
      'org.saathi.android.NativeWalkieDualTest',
      '-e',
      'dualFixture',
      'true',
      '-e',
      'role',
      role,
      '-e',
      'bridgeToken',
      token,
      'org.saathi.android.dev.test/androidx.test.runner.AndroidJUnitRunner',
    ],
    { windowsHide: true },
  );
  let output = '';
  child.stdout.on('data', (part) => {
    output += part;
  });
  child.stderr.on('data', (part) => {
    output += part;
  });
  const timer = setTimeout(() => child.kill(), 480000);
  return new Promise((resolve) =>
    child.on('close', (code) => {
      clearTimeout(timer);
      console.log(`${role}:\n${output.replaceAll(token, '[redacted]')}`);
      resolve(code === 0 && /OK \(1 test\)/.test(output));
    }),
  );
}
let passed = false;
try {
  for (const id of devices) {
    for (const apk of [
      'apps/android/app/build/outputs/apk/debug/app-debug.apk',
      'apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
    ])
      run(['-s', id, 'install', '-r', apk]);
    for (const permission of [
      'RECORD_AUDIO',
      'BLUETOOTH_SCAN',
      'BLUETOOTH_CONNECT',
      'BLUETOOTH_ADVERTISE',
      'NEARBY_WIFI_DEVICES',
    ])
      run([
        '-s',
        id,
        'shell',
        'pm',
        'grant',
        'org.saathi.android.dev',
        `android.permission.${permission}`,
      ]);
    run(['-s', id, 'shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', '7']);
    run(['-s', id, 'reverse', `tcp:${port}`, `tcp:${port}`]);
  }
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });
  passed = (
    await Promise.all(
      devices.map((id, index) => instrument(id, index === 0 ? 'author' : 'carrier')),
    )
  ).every(Boolean);
  mkdirSync('.impeccable/review', { recursive: true });
  if (passed)
    for (const [index, id] of devices.entries())
      for (const state of ['ready', 'talking', 'listening']) {
        writeFileSync(
          `.impeccable/review/${index === 0 ? 'phone' : 'tablet'}-walkie-${state}.png`,
          run([
            '-s',
            id,
            'exec-out',
            'run-as',
            'org.saathi.android.dev',
            'cat',
            `cache/swarm-walkie-${state}.png`,
          ]),
        );
      }
} finally {
  server.close();
  for (const step of barriers.values()) {
    step.release();
  }
  for (const [index, id] of devices.entries()) {
    try {
      run(['-s', id, 'reverse', '--remove', `tcp:${port}`]);
      run([
        '-s',
        id,
        'shell',
        'settings',
        'put',
        'global',
        'stay_on_while_plugged_in',
        originalAwake[index],
      ]);
    } catch {
      console.error('A device disconnected before restoring its temporary keep-awake setting.');
    }
  }
  mkdirSync('.data/android-measurements', { recursive: true });
  writeFileSync(
    '.data/android-measurements/walkie-wifi.json',
    JSON.stringify(
      {
        testedAt: new Date().toISOString(),
        passed,
        models: ['SM-S921B', 'SM-X510'],
        scope:
          'Local Wi-Fi RTP and actual hold/release UI; USB carries pairing/barriers only; not a full internet-outage or battery test',
        steps: measurements,
      },
      null,
      2,
    ),
  );
}
if (!passed) process.exitCode = 1;
