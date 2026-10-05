import { chromium } from '@playwright/test';
import { createServer } from 'node:http';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
const html = await readFile(new URL('../spikes/browser-peer.html', import.meta.url));
const server = createServer((_req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/html', 'Permissions-Policy': 'camera=(self), microphone=(self)' });
  res.end(html);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
const browsers = [];
const result = { measuredAt: new Date().toISOString(), scope: 'Two independent desktop Chromium processes on one Windows host. Synthetic camera/microphone. Not phone/radio/range/battery verification.', iceServers: [], trials: [], range: null, battery: null };
try {
  for (let i = 0; i < 2; i++) browsers.push(await chromium.launch({ args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] }));
  result.browser = browsers[0].version();
  const contexts = await Promise.all(browsers.map(browser => browser.newContext({ permissions: ['camera', 'microphone'] })));
  const pages = await Promise.all(contexts.map(context => context.newPage()));
  await Promise.all(pages.map(page => page.goto(origin)));
  await Promise.all(contexts.map(context => context.route('**/*', route => route.abort())));
  for (let trial = 0; trial < 3; trial++) {
    const started = performance.now();
    await Promise.all(pages.map(page => page.evaluate(() => window.spike.start())));
    const offer = await pages[0].evaluate(() => window.spike.description(true));
    await pages[1].evaluate(offer => window.spike.pc.setRemoteDescription(offer), offer);
    const answer = await pages[1].evaluate(() => window.spike.description(false));
    await pages[0].evaluate(answer => window.spike.pc.setRemoteDescription(answer), answer);
    await Promise.all(pages.map(page => page.waitForFunction(() => window.spike.dc?.readyState === 'open', null, { timeout: 20000 })));
    const connectionMs = performance.now() - started;
    const rtt = [];
    for (let ping = 1; ping <= 20; ping++) {
      const began = performance.now();
      await pages[0].evaluate(ping => window.spike.dc.send(JSON.stringify({ ping })), ping);
      await pages[0].waitForFunction(ping => window.spike.pong === ping, ping);
      rtt.push(performance.now() - began);
    }
    const bytes = 1024 * 1024, began = performance.now();
    await pages[0].evaluate(async bytes => {
      const channel = window.spike.dc;
      channel.send(JSON.stringify({ begin: bytes }));
      for (let offset = 0; offset < bytes; offset += 12288) {
        while (channel.bufferedAmount > 65536) await new Promise(resolve => setTimeout(resolve, 5));
        channel.send(new Uint8Array(Math.min(12288, bytes - offset)).fill(7));
      }
    }, bytes);
    await pages[1].waitForFunction(bytes => window.spike.received === bytes, bytes);
    const elapsed = performance.now() - began;
    const actual = await pages[1].evaluate(() => window.spike.checksum());
    if (actual !== createHash('sha256').update(Buffer.alloc(bytes, 7)).digest('hex')) throw new Error('Bulk checksum failed');
    await pages[0].waitForFunction(async () => {
      const stats = await window.spike.pc.getStats();
      return [...stats.values()].some(s => s.type === 'outbound-rtp' && s.kind === 'audio' && s.packetsSent > 0) && [...stats.values()].some(s => s.type === 'outbound-rtp' && s.kind === 'video' && s.packetsSent > 0);
    });
    const media = await pages[0].evaluate(async () => [...(await window.spike.pc.getStats()).values()].filter(s => s.type === 'outbound-rtp').map(s => ({ kind: s.kind, packetsSent: s.packetsSent })));
    rtt.sort((a, b) => a - b);
    result.trials.push({ success: true, connectionMs: Math.round(connectionMs), controlRttMedianMs: Math.round(rtt[10]), controlRttP95Ms: Math.round(rtt[18]), verifiedBytes: bytes, bulkMiBPerSecond: +(1000 / elapsed).toFixed(2), media, remoteTracks: await pages[1].evaluate(() => window.spike.tracks) });
    await Promise.all(pages.map(page => page.evaluate(() => window.spike.close())));
  }
  result.reconnection = 'Three complete close/re-pair trials succeeded; no radio loss or phone background claims.';
} catch (error) { result.failure = String(error); process.exitCode = 1; }
finally {
  await Promise.all(browsers.map(browser => browser.close()));
  server.close();
  await mkdir(new URL('../docs/benchmarks/', import.meta.url), { recursive: true });
  await writeFile(new URL('../docs/benchmarks/chromium-nearby.json', import.meta.url), JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify(result, null, 2));
}
