import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
const model = process.argv[2] || 'emulator';
const adb = join(
  process.env.ANDROID_HOME || join(process.env.LOCALAPPDATA, 'Android', 'Sdk'),
  'platform-tools',
  process.platform === 'win32' ? 'adb.exe' : 'adb',
);
const devices = execFileSync(adb, ['devices'], { encoding: 'utf8' })
  .split('\n')
  .filter((line) => /\tdevice\s*$/.test(line))
  .map((line) => line.split('\t')[0]);
const matching = devices.filter((id) =>
  model === 'emulator'
    ? id.startsWith('emulator-')
    : execFileSync(adb, ['-s', id, 'shell', 'getprop', 'ro.product.model'], {
        encoding: 'utf8',
      }).trim() === model,
);
if (matching.length !== 1) throw new Error('Exactly one approved requested device is required.');
const device = matching[0];
for (const path of [
  'apps/android/app/build/outputs/apk/debug/app-debug.apk',
  'apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk',
])
  execFileSync(adb, ['-s', device, 'install', '-r', path], { stdio: 'pipe', timeout: 120000 });
const output = execFileSync(
  adb,
  [
    '-s',
    device,
    'shell',
    'am',
    'instrument',
    '-w',
    '-e',
    'class',
    'org.saathi.android.ChatStoreTest,org.saathi.android.SecureStoreTest,org.saathi.android.ChatReadUiTest',
    'org.saathi.android.dev.test/androidx.test.runner.AndroidJUnitRunner',
  ],
  { encoding: 'utf8', timeout: 180000 },
);
console.log(`Encrypted store instrumentation on ${model}:\n${output}`);
if (!/OK \(5 tests\)/.test(output)) process.exitCode = 1;
