'use client';
import Script from 'next/script';
import { useEffect, useRef, useState } from 'react';

const siteKey = process.env.NEXT_PUBLIC_TURNSTILE_SITE_KEY ?? '';

export function NativeTurnstileChallenge() {
  const container = useRef<HTMLDivElement>(null),
    widget = useRef<string | null>(null);
  const [ready, setReady] = useState(false),
    [error, setError] = useState(false);

  useEffect(() => {
    if (!ready || !siteKey || !container.current || !window.turnstile) return;
    const id = window.turnstile.render(container.current, {
      sitekey: siteKey,
      action: 'login',
      callback: (token) => {
        window.location.replace(`cjpswarm-turnstile://token?response=${encodeURIComponent(token)}`);
      },
      'expired-callback': () =>
        window.location.replace('cjpswarm-turnstile://token?status=expired'),
      'error-callback': () => {
        setError(true);
        window.location.replace('cjpswarm-turnstile://token?status=error');
      },
    });
    widget.current = id;
    return () => {
      if (widget.current) window.turnstile?.remove(widget.current);
      widget.current = null;
    };
  }, [ready]);

  return (
    <div className="native-turnstile-page">
      <p>Complete this security check to sign in.</p>
      {siteKey ? (
        <>
          <Script
            src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit"
            strategy="afterInteractive"
            onReady={() => setReady(true)}
            onError={() => {
              setError(true);
              window.location.replace('cjpswarm-turnstile://token?status=error');
            }}
          />
          <div ref={container} />
        </>
      ) : (
        <p>Security verification is not configured.</p>
      )}
      {error && <p role="status">Verification could not load. Return to Swarm and try again.</p>}
    </div>
  );
}
