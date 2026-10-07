'use client';
import { useEffect, useState } from 'react';
import { Smartphone } from 'lucide-react';

/** The group name from a signed CHAT_ADMISSION or CHAT_INVITE, shown as plain text only. Decoded on this phone; never sent anywhere. */
async function groupName(token: string) {
  try {
    const b64 = token.replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4)), (c) =>
      c.charCodeAt(0),
    );
    const invite = await new Response(
      new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip')),
    ).json();
    const name = invite?.body?.name ?? invite?.body?.policy?.body?.name;
    return typeof name === 'string' ? name.slice(0, 120) : '';
  } catch {
    return '';
  }
}

/** `/join#<payload>`: the invitation stays in the fragment, so the server never sees it. This page only hands it to the app. */
export function JoinPage() {
  const [token, setToken] = useState<string | null>(null),
    [name, setName] = useState(''),
    [android, setAndroid] = useState(false);
  useEffect(() => {
    const t = location.hash.slice(1);
    if (!/^[A-Za-z0-9_-]{1,44000}$/.test(t)) return setToken('');
    const ua = navigator.userAgent,
      ios = /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
    setToken(t);
    setAndroid(/Android/.test(ua));
    void groupName(t).then(setName);
    // iPhone: try the app once. Chrome on Android blocks scheme redirects without a tap, so Android relies on the button.
    if (ios) location.href = `cjpswarm://invite/${t}`;
  }, []);
  if (token === null) return <div className="page-wrap narrow" />;
  if (!token)
    return (
      <div className="page-wrap narrow prose-page">
        <h1>This join link is incomplete</h1>
        <p className="lead">Ask the group admin to share the link again.</p>
      </div>
    );
  const href = android
    ? `intent://invite/${token}#Intent;scheme=cjpswarm;end`
    : `cjpswarm://invite/${token}`;
  return (
    <div className="page-wrap narrow prose-page">
      <h1>{name ? `Join “${name}”` : 'Join a group'}</h1>
      <p className="lead">This invitation opens in the CJP Swarm app.</p>
      <a className="button full" href={href} style={{ fontSize: 16, minHeight: 56 }}>
        <Smartphone size={20} aria-hidden="true" />
        Open in CJP Swarm
      </a>
      <p className="muted">Install CJP Swarm first, then tap the link again.</p>
    </div>
  );
}
