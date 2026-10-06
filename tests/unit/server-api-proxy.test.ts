import { afterEach, describe, expect, it } from 'vitest';
import { createServer, type RequestListener, type Server } from 'node:http';
import { proxyApiRequest } from '../../apps/web/src/lib/server-api-proxy';

let server: Server | undefined;
let origin = '';

async function listen(handler: RequestListener) {
  server = createServer(handler);
  await new Promise<void>((resolve) => server!.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('Test server did not bind a TCP port');
  origin = `http://127.0.0.1:${address.port}`;
}

afterEach(async () => {
  if (!server) return;
  await new Promise<void>((resolve, reject) => server!.close((error) => (error ? reject(error) : resolve())));
  server = undefined;
});

describe('runtime API proxy', () => {
  it('forwards the path, query, cookies, and upstream JSON without exposing the API host', async () => {
    await listen((request, response) => {
      expect(request.url).toBe('/api/v1/public/requests?area=Fictional+Gate');
      expect(request.headers.cookie).toBe('saathi_csrf=fixture');
      expect(request.headers['x-forwarded-host']).toBe('swarm.example.test');
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end('{"requests":[]}');
    });

    const request = new Request('https://swarm.example.test/api/v1/public/requests?area=Fictional+Gate', {
      headers: { cookie: 'saathi_csrf=fixture' },
    });
    const response = await proxyApiRequest(request, ['public', 'requests'], origin);

    expect(response.status).toBe(200);
    expect(response.headers.get('content-type')).toBe('application/json');
    expect(await response.json()).toEqual({ requests: [] });
  });

  it('streams write bodies and preserves status and response cookies', async () => {
    await listen(async (request, response) => {
      let body = '';
      for await (const part of request) body += part.toString();
      expect(request.method).toBe('POST');
      expect(body).toBe('{"caption":"fixture"}');
      response.statusCode = 201;
      response.setHeader('content-type', 'application/json');
      response.setHeader('set-cookie', ['saathi_session=fixture; Path=/; HttpOnly', 'saathi_csrf=next; Path=/; SameSite=Lax']);
      response.end('{"id":"fixture"}');
    });

    const request = new Request('https://swarm.example.test/api/v1/public/reports', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{"caption":"fixture"}',
    });
    const response = await proxyApiRequest(request, ['public', 'reports'], origin);

    expect(response.status).toBe(201);
    expect(response.headers.getSetCookie()).toHaveLength(2);
    expect(await response.json()).toEqual({ id: 'fixture' });
  });

  it('returns a non-cacheable service-unavailable response if the internal API is unreachable', async () => {
    const response = await proxyApiRequest(
      new Request('https://swarm.example.test/api/v1/public/config'),
      ['public', 'config'],
      'http://127.0.0.1:1',
    );

    expect(response.status).toBe(503);
    expect(response.headers.get('cache-control')).toBe('no-store');
    expect(await response.json()).toEqual({ message: 'The service is temporarily unavailable. Please try again.' });
  });
});
