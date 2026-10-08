import { describe, expect, it } from 'vitest';
import {
  loginWithTurnstileSchema,
  verifyTurnstileResponse,
} from '../../apps/api/src/auth/turnstile';

function verifier(payload: unknown, status = 200) {
  const requests: RequestInit[] = [];
  const fetcher: typeof fetch = async (_input, init) => {
    requests.push(init ?? {});
    return new Response(JSON.stringify(payload), {
      status,
      headers: { 'content-type': 'application/json' },
    });
  };
  return { fetcher, requests };
}

const config = {
  secret: 'server-only-secret',
  hostnames: ['swarm.cockroachjantaparty.org'],
  appEnv: 'staging' as const,
};

describe('Turnstile web login verification', () => {
  it('requires a token on hosted login requests but leaves local development usable', () => {
    const credentials = { email: 'admin@example.org', password: 'example-password' };
    expect(() => loginWithTurnstileSchema('staging').parse(credentials)).toThrow();
    expect(
      loginWithTurnstileSchema('staging').parse({ ...credentials, turnstileToken: 'token' })
        .turnstileToken,
    ).toBe('token');
    expect(loginWithTurnstileSchema('development').parse(credentials).email).toBe(
      credentials.email,
    );
  });

  it('accepts only a successful login token for the configured hostname', async () => {
    const { fetcher, requests } = verifier({
      success: true,
      action: 'login',
      hostname: 'swarm.cockroachjantaparty.org',
    });
    await expect(
      verifyTurnstileResponse('single-use-token', { ...config, fetcher }),
    ).resolves.toBeUndefined();
    expect(requests).toHaveLength(1);
    expect(requests[0]?.method).toBe('POST');
    expect(String(requests[0]?.body)).toContain('secret=server-only-secret');
    expect(String(requests[0]?.body)).toContain('response=single-use-token');
  });

  it.each([
    { success: false, action: 'login', hostname: 'swarm.cockroachjantaparty.org' },
    { success: true, action: 'other', hostname: 'swarm.cockroachjantaparty.org' },
    { success: true, action: 'login', hostname: 'attacker.example' },
  ])('rejects a failed, wrong-action, or wrong-host challenge', async (payload) => {
    const { fetcher } = verifier(payload);
    await expect(verifyTurnstileResponse('token', { ...config, fetcher })).rejects.toThrow(
      'Complete the security check and try again.',
    );
  });

  it('fails closed in hosted environments when verification is not configured', async () => {
    await expect(
      verifyTurnstileResponse('token', { ...config, secret: undefined }),
    ).rejects.toThrow('Web sign-in verification is not configured.');
  });

  it('fails closed when Cloudflare cannot verify the challenge', async () => {
    const { fetcher } = verifier({}, 503);
    await expect(verifyTurnstileResponse('token', { ...config, fetcher })).rejects.toThrow(
      'Web sign-in verification is temporarily unavailable.',
    );
  });

  it('rejects a replay when Siteverify reports the token as already used', async () => {
    let attempts = 0;
    const fetcher: typeof fetch = async () =>
      new Response(
        JSON.stringify(
          attempts++ === 0
            ? { success: true, action: 'login', hostname: 'swarm.cockroachjantaparty.org' }
            : { success: false, 'error-codes': ['timeout-or-duplicate'] },
        ),
        { status: 200 },
      );
    await expect(verifyTurnstileResponse('single-use-token', { ...config, fetcher })).resolves.toBe(
      undefined,
    );
    await expect(
      verifyTurnstileResponse('single-use-token', { ...config, fetcher }),
    ).rejects.toThrow('Complete the security check and try again.');
  });
});
