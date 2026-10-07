'use client';
import { createContext, useContext, useEffect, useState } from 'react';
import Link from 'next/link';
import { Wifi, WifiOff, Radio } from 'lucide-react';
import { setting, setSetting } from '../lib/offline/store';
import { syncEvents, pinReceiptKey } from '../lib/offline/sync';
type Connection = {
  internet: boolean;
  checked: boolean;
  weak: boolean;
  nearby: boolean;
  lastConnected?: string;
  storageError: string;
  prepared: boolean;
};
const ConnectionContext = createContext<Connection>({
  internet: false,
  checked: false,
  weak: false,
  nearby: false,
  storageError: '',
  prepared: false,
});
export const useConnection = () => useContext(ConnectionContext);
export function ConnectionProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<Connection>({
    internet: false,
    checked: false,
    weak: false,
    nearby: false,
    storageError: '',
    prepared: false,
  });
  useEffect(() => {
    let alive = true;
    const update = (values: Partial<Connection>) => {
      if (alive) setState((s) => ({ ...s, ...values }));
    };
    void setting<string>('last-connected')
      .then((lastConnected) => update({ lastConnected }))
      .catch(() =>
        update({
          storageError: 'This browser could not open saved information. Check storage permissions.',
        }),
      );
    if ('serviceWorker' in navigator && window.isSecureContext)
      void navigator.serviceWorker
        .register('/sw.js')
        .then(() => navigator.serviceWorker.ready)
        .then(() => update({ prepared: true }))
        .catch(() =>
          update({
            storageError: 'Swarm could not prepare offline opening. Reconnect and try again.',
          }),
        );
    const result = (event: Event) => {
      const { ok, elapsed } = (event as CustomEvent<{ ok: boolean; elapsed: number }>).detail;
      // A response begun before network loss can finish after the offline event.
      const connected = ok && navigator.onLine;
      const lastConnected = connected ? new Date().toISOString() : undefined;
      update({
        internet: connected,
        checked: true,
        weak: connected && elapsed > 2000,
        ...(lastConnected ? { lastConnected } : {}),
      });
      if (lastConnected)
        void setSetting('last-connected', lastConnected).catch(() =>
          update({ storageError: 'This browser could not save your latest connection time.' }),
        );
    };
    const nearby = (event: Event) =>
      update({ nearby: Boolean((event as CustomEvent<boolean>).detail) });
    const offline = () => update({ internet: false, checked: true });
    async function check() {
      const began = performance.now();
      try {
        const response = await fetch('/api/v1/public/config', {
          cache: 'no-store',
          signal: AbortSignal.timeout(6000),
        });
        if (!response.ok) throw new Error('Swarm is unavailable');
        await response.json();
        result(
          new CustomEvent('saathi-api-result', {
            detail: { ok: true, elapsed: performance.now() - began },
          }),
        );
      } catch {
        offline();
        return;
      }
      try {
        await pinReceiptKey();
        if (await setting<boolean>('gateway-consent')) await syncEvents();
      } catch (error) {
        update({
          storageError:
            error instanceof Error
              ? error.message
              : 'Saved work could not be checked. Open connection details to try again.',
        });
      }
    }
    const storageError = () =>
      update({
        storageError:
          'This browser could not save information. Export important work and check storage permissions.',
      });
    window.addEventListener('saathi-api-result', result);
    window.addEventListener('saathi-nearby', nearby);
    window.addEventListener('offline', offline);
    window.addEventListener('online', check);
    window.addEventListener('saathi-storage-error', storageError);
    const foreground = () => {
      if (document.visibilityState === 'visible') void check();
    };
    document.addEventListener('visibilitychange', foreground);
    void check();
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') void check();
    }, 20000);
    return () => {
      alive = false;
      clearInterval(timer);
      window.removeEventListener('saathi-api-result', result);
      window.removeEventListener('saathi-nearby', nearby);
      window.removeEventListener('offline', offline);
      window.removeEventListener('online', check);
      window.removeEventListener('saathi-storage-error', storageError);
      document.removeEventListener('visibilitychange', foreground);
    };
  }, []);
  return <ConnectionContext.Provider value={state}>{children}</ConnectionContext.Provider>;
}
export function ConnectionBar() {
  const state = useConnection();
  return (
    <div
      className={`connection-bar ${state.checked && !state.internet ? 'connection-caution' : ''}`}
      role="status"
    >
      <span>
        {state.nearby ? (
          <Radio size={15} />
        ) : state.internet ? (
          <Wifi size={15} />
        ) : (
          <WifiOff size={15} />
        )}
        {!state.checked
          ? 'Checking your connection…'
          : state.internet
            ? state.weak
              ? 'Your connection is slow. Saved work stays on this phone.'
              : 'Connected to Swarm'
            : state.nearby
              ? 'No internet. Connected to nearby Swarm.'
              : 'No connection to Swarm. Read saved information or write a draft.'}
      </span>
      <Link href="/connectivity">What works right now?</Link>
    </div>
  );
}
