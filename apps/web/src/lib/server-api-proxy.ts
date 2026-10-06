const HOP_BY_HOP_HEADERS = [
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
];

function upstreamUrl(request: Request, segments: string[], apiOrigin: string): URL {
  const base = new URL(apiOrigin);
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password) {
    throw new Error('Invalid internal API origin');
  }
  if (base.pathname !== '/' || base.search || base.hash) {
    throw new Error('Internal API origin must not contain a path, query, or fragment');
  }
  if (
    segments.length === 0 ||
    segments.some(
      (segment) =>
        !segment ||
        segment === '.' ||
        segment === '..' ||
        segment.includes('/') ||
        segment.includes('\\') ||
        [...segment].some((character) => character.charCodeAt(0) < 0x20),
    )
  ) {
    throw new Error('Invalid API path');
  }

  const target = new URL(`/api/v1/${segments.map(encodeURIComponent).join('/')}`, base);
  target.search = new URL(request.url).search;
  return target;
}

function requestHeaders(request: Request): Headers {
  const headers = new Headers(request.headers);
  headers.delete('host');
  for (const name of HOP_BY_HOP_HEADERS) headers.delete(name);

  const source = new URL(request.url);
  headers.set('x-forwarded-host', source.host);
  headers.set('x-forwarded-proto', source.protocol.slice(0, -1));
  return headers;
}

function responseHeaders(response: Response): Headers {
  const headers = new Headers(response.headers);
  for (const name of HOP_BY_HOP_HEADERS) headers.delete(name);
  // Fetch transparently decodes compressed upstream bodies. Do not forward stale encoding
  // or length metadata for that decoded body.
  headers.delete('content-encoding');
  headers.delete('content-length');

  const cookies = response.headers.getSetCookie?.() ?? [];
  if (cookies.length > 0) {
    headers.delete('set-cookie');
    for (const cookie of cookies) headers.append('set-cookie', cookie);
  }
  return headers;
}

export async function proxyApiRequest(
  request: Request,
  segments: string[],
  apiOrigin = process.env.API_INTERNAL_URL ?? 'http://localhost:4000',
  fetcher: typeof fetch = fetch,
): Promise<Response> {
  try {
    const target = upstreamUrl(request, segments, apiOrigin);
    const method = request.method.toUpperCase();
    const init: RequestInit & { duplex?: 'half' } = {
      method,
      headers: requestHeaders(request),
      cache: 'no-store',
      redirect: 'manual',
      signal: request.signal,
    };
    if (method !== 'GET' && method !== 'HEAD' && request.body) {
      init.body = request.body;
      init.duplex = 'half';
    }

    const upstream = await fetcher(target, init);
    const body = method === 'HEAD' || upstream.status === 204 || upstream.status === 304 ? null : upstream.body;
    return new Response(body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: responseHeaders(upstream),
    });
  } catch (error) {
    const name = error instanceof Error ? error.name : 'UnknownError';
    console.error(`[api-proxy] ${request.method} /api/v1/${segments.join('/')} failed (${name})`);
    return Response.json(
      { message: 'The service is temporarily unavailable. Please try again.' },
      { status: 503, headers: { 'cache-control': 'no-store' } },
    );
  }
}
