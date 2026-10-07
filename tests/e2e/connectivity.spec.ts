import { test, expect, type Page } from '@playwright/test';
import { createHash } from 'node:crypto';
import type { SavedEvent, Attachment } from '../../apps/web/src/lib/offline/store';

test.use({
  launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
});

async function records<T>(page: Page, store: string): Promise<T[]> {
  return page.evaluate(
    (store) =>
      new Promise<unknown[]>((resolve, reject) => {
        const open = indexedDB.open('saathi-offline-v1');
        open.onerror = () => reject(open.error);
        open.onsuccess = () => {
          const request = open.result.transaction(store).objectStore(store).getAll();
          request.onsuccess = () => {
            resolve(request.result);
            open.result.close();
          };
          request.onerror = () => reject(request.error);
        };
      }),
    store,
  ) as Promise<T[]>;
}
async function prepareApp(page: Page) {
  await page.goto('/connectivity');
  await expect(page.getByRole('button', { name: 'Save app for offline opening' })).toBeEnabled();
  await page.getByRole('button', { name: 'Save app for offline opening' }).click();
  await expect(page.getByText('Swarm is ready to open offline.', { exact: false })).toBeVisible();
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
}
async function pair(a: Page, b: Page) {
  await a.getByRole('button', { name: 'Create nearby invitation' }).click();
  const outputA = a.getByLabel('Invitation or reply to share');
  await expect(outputA).toHaveValue(/^SAATHI1:/);
  await b
    .getByLabel('Import the other person’s invitation or reply')
    .fill(await outputA.inputValue());
  await b.getByRole('button', { name: 'Use invitation or reply' }).click();
  const outputB = b.getByLabel('Invitation or reply to share');
  await expect(outputB).toHaveValue(/^SAATHI1:/);
  await a
    .getByLabel('Import the other person’s invitation or reply')
    .fill(await outputB.inputValue());
  await a.getByRole('button', { name: 'Use invitation or reply' }).click();
  await Promise.all(
    [a, b].map((p) =>
      expect(p.getByRole('heading', { name: 'Connected nearby', exact: true })).toBeVisible(),
    ),
  );
  expect(await a.locator('.pairing-code').textContent()).toBe(
    await b.locator('.pairing-code').textContent(),
  );
  await a.getByRole('button', { name: 'The codes match' }).click();
  await b.getByRole('button', { name: 'The codes match' }).click();
  await Promise.all(
    [a, b].map((p) => expect(p.getByLabel('Offer a small image or text file')).toBeEnabled()),
  );
}

test('offline cold opening preserves public information and drafts without caching private URLs', async ({
  page,
  context,
}) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Drinking water', exact: true })).toBeVisible();
  await expect.poll(() => records(page, 'snapshots').then((r) => r.length)).toBeGreaterThan(0);
  await page.goto('/r/SAA-7F3K92');
  await prepareApp(page);
  await expect(page.getByText('Relief information last saved', { exact: false })).toBeVisible();
  await expect(
    page.getByText('Send prepared relief updates to Swarm', { exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByText('Write and save request drafts, field updates and messages', { exact: true }),
  ).toBeVisible();
  await page.evaluate(() => window.dispatchEvent(new Event('saathi-storage-error')));
  await expect(
    page.getByText('Write and save request drafts, field updates and messages', { exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByText('Saving work is unavailable until browser storage is ready.', { exact: false }),
  ).toBeVisible();
  await page.reload();
  await page.goto('/r/SAA-7F3K92');
  await expect(page.getByRole('button', { name: /Reserve/ })).toBeVisible();
  await context.setOffline(true);
  await expect(page.getByRole('button', { name: /Reserve/ })).toHaveCount(0);
  // A successful request started before the outage must not reopen online actions.
  await page.evaluate(async () => {
    window.dispatchEvent(
      new CustomEvent('saathi-api-result', { detail: { ok: true, elapsed: 50 } }),
    );
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    );
  });
  await expect(page.getByRole('button', { name: /Reserve/ })).toHaveCount(0);
  await expect(page.getByText('Last known request information', { exact: true })).toBeVisible();
  await page.goto('/offline');
  await expect(page.getByRole('heading', { name: 'Saved work', exact: true })).toBeVisible();
  await expect(page.getByText('Things may have changed.', { exact: false }).last()).toBeVisible();
  await page.getByLabel('Title', { exact: true }).fill('A draft saved during an outage');
  await page
    .getByLabel('Description', { exact: true })
    .fill('Local work must survive reopening the app.');
  await page.getByRole('button', { name: 'Save draft on this phone' }).click();
  await expect(page.getByRole('heading', { name: 'A draft saved during an outage' })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', { name: 'A draft saved during an outage' })).toBeVisible();
  await page.getByRole('button', { name: 'Open draft', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Write a relief update' })).toBeFocused();
  await expect(page.getByLabel('Description', { exact: true })).toHaveValue(
    'Local work must survive reopening the app.',
  );
  const cached = await page.evaluate(async () =>
    (
      await Promise.all(
        (await caches.keys()).map(async (key) =>
          (await (await caches.open(key)).keys()).map((r) => new URL(r.url).pathname),
        ),
      )
    ).flat(),
  );
  expect(
    cached.some(
      (path) =>
        path.startsWith('/api/') ||
        path.startsWith('/contribution/') ||
        path.startsWith('/dashboard'),
    ),
  ).toBe(false);
  await page.goto('/nearby');
  await expect(page.getByRole('heading', { name: 'Connect with someone nearby' })).toBeVisible();
  await page
    .getByLabel('Message', { exact: true })
    .fill('A private message saved without a connection');
  await page.getByRole('button', { name: 'Save message on this phone' }).click();
  await expect(
    page
      .locator('.message-list')
      .getByText('A private message saved without a connection', { exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page
      .locator('.message-list')
      .getByText('A private message saved without a connection', { exact: true }),
  ).toBeVisible();
});

test('nearby messages, consented attachment and synthetic video work with website access blocked', async ({
  page: a,
  context: ca,
  browser,
}, info) => {
  test.setTimeout(100000);
  const cb = await browser.newContext({
      baseURL: 'http://localhost:3000',
      permissions: ['camera', 'microphone'],
    }),
    b = await cb.newPage();
  try {
    await ca.grantPermissions(['camera', 'microphone']);
    // Lose the first capability frame on both phones. Code confirmation must
    // refresh capabilities rather than permanently disabling files or calls.
    await Promise.all(
      [ca, cb].map((context) =>
        context.addInitScript(() => {
          // A data channel can open while applying the answer, before the
          // remoteDescription getter exposes it. Hold that window open so the
          // verification code must wait for setRemoteDescription to finish.
          const remoteDescription = Object.getOwnPropertyDescriptor(
            RTCPeerConnection.prototype,
            'remoteDescription',
          )!;
          const applyDescription = RTCPeerConnection.prototype.setRemoteDescription;
          const pendingAnswers = new WeakSet<RTCPeerConnection>();
          let earlyAnswerReads = 0;
          Object.defineProperty(window, '__saathiEarlyAnswerReads', {
            get: () => earlyAnswerReads,
          });
          Object.defineProperty(RTCPeerConnection.prototype, 'remoteDescription', {
            ...remoteDescription,
            get(this: RTCPeerConnection) {
              if (pendingAnswers.has(this)) {
                earlyAnswerReads++;
                return null;
              }
              return remoteDescription.get!.call(this);
            },
          });
          RTCPeerConnection.prototype.setRemoteDescription = async function (description) {
            if (description?.type === 'answer') pendingAnswers.add(this);
            try {
              await Reflect.apply(applyDescription, this, [description]);
              if (description?.type === 'answer')
                await new Promise((resolve) => setTimeout(resolve, 500));
            } finally {
              pendingAnswers.delete(this);
            }
          };
          const send = RTCDataChannel.prototype.send;
          let dropped = false;
          RTCDataChannel.prototype.send = function (
            data: string | Blob | ArrayBuffer | ArrayBufferView,
          ) {
            if (!dropped && typeof data === 'string' && JSON.parse(data).kind === 'HELLO') {
              dropped = true;
              return;
            }
            return Reflect.apply(send, this, [data]);
          };
        }),
      ),
    );
    await Promise.all([prepareApp(a), prepareApp(b)]);
    await Promise.all([a.goto('/nearby'), b.goto('/nearby')]);
    // Block website traffic while retaining the local interfaces used by ICE.
    // DevTools' global offline switch can also disable mDNS candidate resolution.
    await Promise.all([
      ca.route('**/api/**', (route) => route.abort()),
      cb.route('**/api/**', (route) => route.abort()),
    ]);
    await pair(a, b);
    expect(
      await a.evaluate(() => Reflect.get(window, '__saathiEarlyAnswerReads') as number),
    ).toBeGreaterThan(0);
    await a.getByLabel('Message', { exact: true }).fill('Local check: clean water is ready');
    await a.getByRole('button', { name: 'Send nearby message' }).click();
    await expect(b.getByText('Local check: clean water is ready', { exact: true })).toBeVisible();
    await expect(a.getByText('Reached another phone', { exact: true })).toBeVisible();
    const bytes = Buffer.alloc(1048576, 7);
    await a
      .getByLabel('Offer a small image or text file')
      .setInputFiles({ name: 'relief-transfer.txt', mimeType: 'text/plain', buffer: bytes });
    await expect(b.getByText('relief-transfer.txt · 1024 KB', { exact: true })).toBeVisible();
    expect((await records<Attachment>(b, 'attachments')).length).toBe(0);
    await b.getByRole('button', { name: 'Receive or resume attachment' }).click();
    await expect(
      b.getByText('relief-transfer.txt · Checked and saved on this phone', { exact: true }),
    ).toBeVisible();
    const checksum = await b.evaluate(async () => {
      const open = await new Promise<IDBDatabase>((resolve) => {
        const r = indexedDB.open('saathi-offline-v1');
        r.onsuccess = () => resolve(r.result);
      });
      const files = await new Promise<Attachment[]>((resolve) => {
        const r = open.transaction('attachments').objectStore('attachments').getAll();
        r.onsuccess = () => resolve(r.result);
      });
      const file = files[0]!,
        raw = new Uint8Array(file.size);
      Object.entries(file.chunks).forEach(([i, chunk]) => raw.set(chunk, Number(i) * 8192));
      open.close();
      return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', raw)))
        .map((b) => b.toString(16).padStart(2, '0'))
        .join('');
    });
    expect(checksum).toBe(createHash('sha256').update(bytes).digest('hex'));
    await a.screenshot({
      path: `.impeccable/review/nearby-paired-${info.project.name}.png`,
      fullPage: true,
      animations: 'disabled',
      scale: 'css',
    });
    await a.getByRole('button', { name: 'Nearby video call' }).click();
    await expect(
      b.getByText('The paired person would like a video call.', { exact: true }),
    ).toBeVisible();
    expect(
      await b
        .locator('video[aria-label="Your camera preview"]')
        .evaluate((v: HTMLVideoElement) => v.srcObject === null),
    ).toBe(true);
    await b.getByRole('button', { name: 'Accept call' }).click();
    await Promise.all(
      [a, b].map((p) =>
        expect(p.getByText('Call connected. Microphone active.', { exact: false })).toBeVisible(),
      ),
    );
    await Promise.all(
      [a, b].map((p) =>
        p.waitForFunction(() => {
          const video = document.querySelector(
            'video[aria-label="Nearby person’s call"]',
          ) as HTMLVideoElement;
          return (
            (video.srcObject as MediaStream | null)
              ?.getVideoTracks()
              .some((t) => t.readyState === 'live') && video.videoWidth > 0
          );
        }),
      ),
    );
    await a.getByRole('button', { name: 'End call' }).click();
    await expect(b.getByRole('button', { name: 'End call' })).toHaveCount(0);
    await a.getByRole('button', { name: 'Disconnect nearby' }).click();
    await a.getByLabel('Message', { exact: true }).fill('Saved after the connection dropped');
    await a.getByRole('button', { name: 'Save message on this phone' }).click();
    await pair(a, b);
    await a.getByRole('button', { name: 'Send my unsent messages to this person' }).click();
    await expect(b.getByText('Saved after the connection dropped', { exact: true })).toBeVisible();
  } finally {
    await cb.close();
  }
});

test('an interrupted attachment resumes from persisted parts after receiver reload and re-pairing', async ({
  page: a,
  context: ca,
  browser,
}) => {
  test.setTimeout(60000);
  const cb = await browser.newContext({ baseURL: 'http://localhost:3000' }),
    b = await cb.newPage();
  try {
    await ca.addInitScript(() => {
      const send = RTCDataChannel.prototype.send;
      let interrupted = false;
      Object.defineProperty(RTCDataChannel.prototype, 'send', {
        value: function (this: RTCDataChannel, data: unknown) {
          if (!interrupted && typeof data === 'string') {
            const frame = JSON.parse(data);
            if (frame.kind === 'FILE_CHUNK' && frame.value.index === 32) {
              interrupted = true;
              this.close();
              throw new Error('Injected local connection loss');
            }
          }
          return Reflect.apply(send, this, [data]);
        },
      });
    });
    await Promise.all([prepareApp(a), prepareApp(b)]);
    await Promise.all([a.goto('/nearby'), b.goto('/nearby')]);
    await Promise.all([
      ca.route('**/api/**', (route) => route.abort()),
      cb.route('**/api/**', (route) => route.abort()),
    ]);
    await pair(a, b);
    await a.getByLabel('Offer a small image or text file').setInputFiles({
      name: 'resume-relief.txt',
      mimeType: 'text/plain',
      buffer: Buffer.alloc(1048576, 4),
    });
    await b.getByRole('button', { name: 'Receive or resume attachment' }).click();
    await expect(a.getByRole('heading', { name: 'Nearby connection lost' })).toBeVisible();
    await expect
      .poll(() =>
        records<Attachment>(b, 'attachments').then((f) => Object.keys(f[0]?.chunks ?? {}).length),
      )
      .toBeGreaterThan(0);
    const savedParts = Object.keys((await records<Attachment>(b, 'attachments'))[0]!.chunks).length;
    expect(savedParts).toBeLessThan(128);
    await b.reload();
    expect(Object.keys((await records<Attachment>(b, 'attachments'))[0]!.chunks).length).toBe(
      savedParts,
    );
    await pair(a, b);
    await a.getByRole('button', { name: 'Offer saved attachment to this person' }).click();
    await b.getByRole('button', { name: 'Receive or resume attachment' }).click();
    await expect(
      b.getByText('resume-relief.txt · Checked and saved on this phone', { exact: true }),
    ).toBeVisible();
    const complete = (await records<Attachment>(b, 'attachments'))[0]!;
    expect(Object.keys(complete.chunks)).toHaveLength(128);
    expect(complete.hash).toBe(createHash('sha256').update(Buffer.alloc(1048576, 4)).digest('hex'));
  } finally {
    await cb.close();
  }
});

test('two-hop relay publishes once while the author is absent and carries a signed receipt back', async ({
  page: a,
  context: ca,
  browser,
}, info) => {
  test.setTimeout(120000);
  const cb = await browser.newContext({ baseURL: 'http://localhost:3000' }),
    cc = await browser.newContext({ baseURL: 'http://localhost:3000' });
  const b = await cb.newPage(),
    c = await cc.newPage(),
    title = `Offline relay proof ${Date.now()}`;
  try {
    await a.goto('/login');
    await a.getByLabel('Email', { exact: true }).fill('volunteer@saathi.test');
    await a
      .getByLabel('Password', { exact: true })
      .fill(process.env.SEED_PASSWORD ?? 'Saathi-demo-2026!');
    await a.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(a).toHaveURL('/dashboard');
    await Promise.all([prepareApp(a), prepareApp(b), prepareApp(c)]);
    await a.getByRole('button', { name: 'Prepare volunteer publishing' }).click();
    await expect(
      a.getByText('Offline publishing is prepared for this signed-in volunteer.'),
    ).toBeVisible();
    await a.goto('/offline');
    await ca.setOffline(true);
    await a.getByRole('combobox', { name: 'Relief point', exact: true }).selectOption({ index: 1 });
    await a.getByLabel('Title', { exact: true }).fill(title);
    await a
      .getByLabel('Description', { exact: true })
      .fill('Urgent signed supply text, relayed without the author being connected.');
    await a.getByLabel('Quantity', { exact: true }).fill('25');
    await a.getByLabel('Unit', { exact: true }).fill('bottles');
    await a.getByRole('combobox', { name: 'Priority', exact: true }).selectOption('URGENT');
    await a.getByLabel('Needed by', { exact: true }).fill('2099-01-01T18:00');
    await a.getByRole('button', { name: 'Save and prepare to send' }).click();
    await expect.poll(() => records<SavedEvent>(a, 'events').then((e) => e.length)).toBe(1);
    const original = (await records<SavedEvent>(a, 'events'))[0]!;
    await Promise.all([a.goto('/nearby'), b.goto('/nearby'), c.goto('/nearby')]);
    await ca.setOffline(false);
    await Promise.all([
      ca.route('**/api/**', (route) => route.abort()),
      cb.route('**/api/**', (route) => route.abort()),
    ]);
    await pair(a, b);
    await a.getByRole('button', { name: 'Share saved relief updates and confirmations' }).click();
    await expect.poll(() => records<SavedEvent>(b, 'events').then((e) => e.length)).toBe(1);
    expect((await records<SavedEvent>(b, 'events'))[0]!.envelope).toEqual(original.envelope);
    await a.close();
    await expect(b.getByRole('button', { name: 'Create nearby invitation' })).toBeVisible();
    await pair(b, c);
    await b.getByRole('button', { name: 'Share saved relief updates and confirmations' }).click();
    await expect.poll(() => records<SavedEvent>(c, 'events').then((e) => e[0]?.hops)).toBe(2);
    await c.goto('/connectivity');
    await c.getByRole('button', { name: 'Send and check saved relief updates' }).click();
    await expect
      .poll(() => records<SavedEvent>(c, 'events').then((e) => e[0]?.receipt?.body.status))
      .toBe('PUBLISHED');
    const published = (await records<SavedEvent>(c, 'events'))[0]!,
      publicId = published.receipt!.body.publicId!;
    const canonical = await (await c.request.get(`/api/v1/public/requests/${publicId}`)).json();
    expect(canonical.title).toBe(title);
    expect(canonical.creator.displayName).toBe('Aman K.');
    await c.getByRole('button', { name: 'Send and check saved relief updates' }).click();
    expect((await records<SavedEvent>(c, 'events'))[0]!.receipt!.body.publicId).toBe(publicId);
    await c.goto('/nearby');
    await pair(c, b);
    await c.getByRole('button', { name: 'Share saved relief updates and confirmations' }).click();
    await expect
      .poll(() => records<SavedEvent>(b, 'events').then((e) => e[0]?.receipt?.body.publicId))
      .toBe(publicId);
    await c.getByRole('button', { name: 'Disconnect nearby' }).click();
    const returned = await ca.newPage();
    await returned.goto('/nearby');
    await pair(b, returned);
    await b.getByRole('button', { name: 'Share saved relief updates and confirmations' }).click();
    await expect
      .poll(() => records<SavedEvent>(returned, 'events').then((e) => e[0]?.receipt?.body.publicId))
      .toBe(publicId);
    await returned.goto('/offline');
    await expect(returned.getByText('Published', { exact: true })).toBeVisible();
    await returned.screenshot({
      path: `.impeccable/review/offline-published-${info.project.name}.png`,
      fullPage: true,
      animations: 'disabled',
      scale: 'css',
    });
  } finally {
    await cb.close();
    await cc.close();
  }
});
