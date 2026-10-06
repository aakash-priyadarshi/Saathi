import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
const require = createRequire(new URL('../apps/api/package.json', import.meta.url));
const ffmpeg = require('ffmpeg-static');
execFileSync(
  ffmpeg,
  [
    '-nostdin',
    '-y',
    '-f',
    'lavfi',
    '-i',
    'testsrc2=size=320x240:rate=15',
    '-t',
    '2',
    '-c:v',
    'libx264',
    '-preset',
    'fast',
    '-metadata',
    'location=+19.12345+073.54321/',
    '-metadata',
    'comment=SYNTHETIC PRIVATE METADATA FIXTURE',
    'apps/android/app/src/androidTest/assets/public-report-qa.mp4',
  ],
  { windowsHide: true, stdio: 'pipe' },
);
console.log('Generated a two-second synthetic video fixture; no user or camera material.');
