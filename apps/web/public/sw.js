/* Public shell only. Private API responses and donor bearer URLs never enter CacheStorage. */
const VERSION = 'saathi-shell-v2';
const SHELL = [
  '/offline',
  '/nearby',
  '/connectivity',
  '/manifest.webmanifest',
  '/icons/saathi.svg',
  '/icons/saathi-192.png',
  '/icons/saathi-512.png',
];
async function warm() {
  const cache = await caches.open(VERSION);
  for (const path of SHELL) {
    const response = await fetch(path, { cache: 'reload', credentials: 'omit' });
    if (!response.ok) throw new Error('Offline preparation could not finish.');
    await cache.put(path, response.clone());
    if (response.headers.get('Content-Type')?.includes('text/html')) {
      const html = await response.text();
      const paths = [...html.matchAll(/(?:src|href)="(\/_next\/static\/[^"?]+(?:\?[^"]*)?)"/g)].map(
        (m) => m[1].replaceAll('&amp;', '&'),
      );
      for (const asset of new Set(paths)) {
        const r = await fetch(asset, { credentials: 'omit' });
        if (r.ok) await cache.put(asset, r);
      }
    }
  }
}
self.addEventListener('install', (event) => event.waitUntil(warm()));
self.addEventListener('activate', (event) =>
  event.waitUntil(
    (async () => {
      for (const key of await caches.keys())
        if (key.startsWith('saathi-shell-') && key !== VERSION) await caches.delete(key);
      await self.clients.claim();
    })(),
  ),
);
self.addEventListener('message', (event) => {
  if (event.data === 'PREPARE')
    event.waitUntil(
      warm()
        .then(() => event.ports[0]?.postMessage({ ok: true }))
        .catch(() => event.ports[0]?.postMessage({ ok: false })),
    );
});
self.addEventListener('fetch', (event) => {
  const { request } = event,
    url = new URL(request.url);
  if (
    request.method !== 'GET' ||
    url.origin !== self.location.origin ||
    url.pathname.startsWith('/api/') ||
    url.pathname.startsWith('/contribution/')
  )
    return;
  if (url.pathname.startsWith('/_next/static/')) {
    event.respondWith(
      (async () => {
        const cache = await caches.open(VERSION),
          saved = await cache.match(request);
        if (saved) return saved;
        const response = await fetch(request);
        if (response.ok) await cache.put(request, response.clone());
        return response;
      })(),
    );
    return;
  }
  if (request.mode === 'navigate')
    event.respondWith(
      (async () => {
        try {
          const response = await fetch(request, { signal: AbortSignal.timeout(5000) });
          if (response.ok && SHELL.includes(url.pathname))
            await (await caches.open(VERSION)).put(url.pathname, response.clone());
          return response;
        } catch {
          const saved = await (
            await caches.open(VERSION)
          ).match(SHELL.includes(url.pathname) ? url.pathname : '/offline');
          return (
            saved ||
            new Response('Connect once to prepare Saathi for offline use.', {
              status: 503,
              headers: { 'Content-Type': 'text/plain' },
            })
          );
        }
      })(),
    );
});
