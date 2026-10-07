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
// A noisy 1080p clip above the 2 Mbps target, so the compression test can see it shrink.
execFileSync(
  ffmpeg,
  [
    '-nostdin',
    '-y',
    '-f',
    'lavfi',
    '-i',
    'testsrc2=size=1920x1080:rate=30,noise=alls=40:allf=t',
    '-t',
    '1',
    '-c:v',
    'libx264',
    '-pix_fmt',
    'yuv420p',
    '-b:v',
    '5M',
    '-minrate',
    '5M',
    '-maxrate',
    '5M',
    '-bufsize',
    '1M',
    '-x264-params',
    'nal-hrd=cbr',
    '-metadata',
    'location=+19.12345+073.54321/',
    'apps/android/app/src/androidTest/assets/field-1080p-qa.mp4',
  ],
  { windowsHide: true, stdio: 'pipe' },
);
console.log('Generated synthetic video fixtures; no user or camera material.');
