import { ServiceUnavailableException, UnauthorizedException } from '@nestjs/common';
import { loginSchema } from '@saathi/validation';
import { z } from 'zod';

const siteverifyUrl = 'https://challenges.cloudflare.com/turnstile/v0/siteverify';

type TurnstileResult = {
  success?: boolean;
  action?: string;
  hostname?: string;
};

type TurnstileOptions = {
  secret: string | undefined;
  hostnames: string[];
  appEnv: 'development' | 'staging' | 'production';
  fetcher?: typeof fetch;
};

export function loginWithTurnstileSchema(appEnv: TurnstileOptions['appEnv']) {
  return z
    .object({
      ...loginSchema.shape,
      turnstileToken:
        appEnv === 'development' ? z.string().max(2048).optional() : z.string().min(1).max(2048),
    })
    .strict();
}

export async function verifyTurnstileResponse(responseToken: string, options: TurnstileOptions) {
  if (!options.secret) {
    if (options.appEnv === 'development') return;
    throw new ServiceUnavailableException('Web sign-in verification is not configured.');
  }
  if (options.hostnames.length === 0)
    throw new ServiceUnavailableException('Web sign-in verification is not configured.');

  const body = new URLSearchParams({ secret: options.secret, response: responseToken });
  let result: Response;
  try {
    result = await (options.fetcher ?? fetch)(siteverifyUrl, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body,
      signal: AbortSignal.timeout(10000),
    });
  } catch {
    throw new ServiceUnavailableException('Web sign-in verification is temporarily unavailable.');
  }
  if (!result.ok)
    throw new ServiceUnavailableException('Web sign-in verification is temporarily unavailable.');

  let verification: TurnstileResult;
  try {
    verification = (await result.json()) as TurnstileResult;
  } catch {
    throw new ServiceUnavailableException('Web sign-in verification is temporarily unavailable.');
  }
  if (
    verification.success !== true ||
    verification.action !== 'login' ||
    typeof verification.hostname !== 'string' ||
    !options.hostnames.includes(verification.hostname)
  )
    throw new UnauthorizedException('Complete the security check and try again.');
}
