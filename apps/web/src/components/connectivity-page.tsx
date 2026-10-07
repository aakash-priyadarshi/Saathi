'use client';
import { useEffect, useState } from 'react';
import Link from 'next/link';
import { Check, Minus, Download, Trash2, RefreshCw } from 'lucide-react';
import { ErrorNotice } from '@saathi/ui';
import { useConnection } from './connectivity-provider';
import {
  db,
  events,
  setting,
  setSetting,
  exportWork,
  clearPrivate,
  type SavedEvent,
  type Preparation,
} from '../lib/offline/store';
import { pinReceiptKey, preparePublishing, syncEvents } from '../lib/offline/sync';
import { formatDate } from '../lib/api';
import { api, write } from '../lib/api';
import { changed } from '../lib/offline/store';
export function downloadJson(value: unknown, name: string) {
  const url = URL.createObjectURL(
    new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' }),
  );
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
export function EventStatus({ event }: { event: SavedEvent }) {
  const accepted = event.receipt && ['PUBLISHED', 'ACCEPTED'].includes(event.receipt.body.status);
  return (
    <div className="event-status">
      <span>
        <Check size={14} />
        Saved on this phone
      </span>
      <span>{event.sharedAt ? <Check size={14} /> : <Minus size={14} />}Reached another phone</span>
      <span>{event.receipt ? <Check size={14} /> : <Minus size={14} />}Reached Swarm</span>
      <span>
        {event.receipt?.body.status === 'PUBLISHED' ? <Check size={14} /> : <Minus size={14} />}
        Published
      </span>
      {event.receipt && !accepted && (
        <strong className="danger-text">Needs your attention: {event.receipt.body.message}</strong>
      )}
      {event.error && !event.receipt && <small>{event.error}</small>}
    </div>
  );
}
export function ConnectivityPage() {
  const c = useConnection(),
    [local, setLocal] = useState({
      events: [] as SavedEvent[],
      draftCount: 0,
      mediaCount: 0,
      prepared: false,
      gateway: false,
      persisted: false,
      storage: false,
      storageChecked: false,
      savedPublic: 0,
      savedAt: '',
    });
  const [error, setError] = useState(''),
    [busy, setBusy] = useState(false),
    [preparingOffline, setPreparingOffline] = useState(false),
    [notice, setNotice] = useState('');
  const [devices, setDevices] = useState<
    { id: string; createdAt: string; revokedAt: string | null }[]
  >([]);
  useEffect(() => {
    if (c.internet && local.prepared)
      void api<typeof devices>('/sync/devices')
        .then(setDevices)
        .catch(() => setDevices([]));
  }, [c.internet, local.prepared]);
  useEffect(() => {
    const load = async () => {
      try {
        const database = await db(),
          preparation = await setting<Preparation>('preparation'),
          saved = (
            await Promise.all([
              database.get('snapshots', '/public/requests'),
              database.get('snapshots', '/public/feed'),
            ])
          ).filter((item) => item !== undefined);
        setLocal({
          events: await events(),
          draftCount: await database.count('drafts'),
          mediaCount: (await database.getAll('drafts')).filter((d) => d.media?.length).length,
          prepared: Boolean(preparation),
          gateway: Boolean(await setting('gateway-consent')),
          persisted: (await navigator.storage?.persisted?.()) ?? false,
          storage: true,
          storageChecked: true,
          savedPublic: saved.length,
          savedAt: saved.map((item) => item.savedAt).sort()[0] ?? '',
        });
      } catch {
        setLocal((previous) => ({ ...previous, storage: false, storageChecked: true }));
        setError('This browser could not open saved work. Check its storage permissions.');
      }
    };
    void load();
    window.addEventListener('saathi-local-change', load);
    return () => window.removeEventListener('saathi-local-change', load);
  }, []);
  async function run(work: () => Promise<void>) {
    setBusy(true);
    setError('');
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Please try again.');
    } finally {
      setBusy(false);
    }
  }
  async function prepare() {
    if (!window.isSecureContext || !('serviceWorker' in navigator))
      throw new Error('Offline opening needs a secure browser that supports service workers.');
    const registration = await navigator.serviceWorker.ready;
    const reply = await new Promise<{ ok: boolean }>((resolve, reject) => {
      const channel = new MessageChannel();
      const timer = setTimeout(
        () => reject(new Error('Preparation is taking too long. Reconnect and try again.')),
        30000,
      );
      channel.port1.onmessage = (event) => {
        clearTimeout(timer);
        resolve(event.data);
      };
      registration.active?.postMessage('PREPARE', [channel.port2]);
    });
    if (!reply.ok) throw new Error('Offline opening could not be prepared.');
    // A carrier must be able to verify returned confirmations without internet.
    // Do not rely on the background connection check finishing before navigation.
    await pinReceiptKey();
    const persistent = await navigator.storage?.persist?.();
    await setSetting('offline-prepared', new Date().toISOString());
    setNotice(
      persistent
        ? 'Swarm is ready to open offline. Your browser granted protected storage.'
        : 'Swarm is ready to open offline. Export important work: this browser may remove stored data when space is low.',
    );
  }
  async function prepareOffline() {
    if (preparingOffline) return;
    setPreparingOffline(true);
    setError('');
    try {
      await prepare();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Please try again.');
    } finally {
      setPreparingOffline(false);
    }
  }
  const storageUsable = local.storage && !c.storageError;
  const can = [
    ...(storageUsable && local.savedPublic ? ['Read relief information saved on this phone'] : []),
    ...(storageUsable ? ['Write and save request drafts, field updates and messages'] : []),
    ...(c.internet ? ['Check current website information'] : []),
    ...(c.internet && storageUsable && local.prepared
      ? ['Send prepared relief updates to Swarm']
      : c.internet && storageUsable && local.events.some((event) => !event.receipt)
        ? ['Carry signed relief updates received nearby to Swarm']
        : []),
    ...(c.nearby && storageUsable
      ? [
          'Message the paired person after confirming the matching codes',
          'Share saved relief updates nearby after confirming the codes',
        ]
      : []),
  ];
  const cannot = [
    ...(!storageUsable
      ? ['Saving work is unavailable until browser storage is ready. Check storage permissions.']
      : []),
    ...(storageUsable && !local.savedPublic
      ? ['Open Live or Needs while connected to save relief information on this phone']
      : []),
    ...(!local.prepared
      ? [
          'Approved volunteers must prepare publishing while connected before sending their own relief updates',
        ]
      : []),
    ...(!c.internet
      ? [
          'Confirm current supply quantities or place an order',
          'See new website updates or publish directly online',
        ]
      : []),
    ...(!c.nearby
      ? ['Call or message another person until a nearby connection is paired']
      : ['Keep communicating if the nearby connection goes out of range']),
  ];
  return (
    <div className="page-wrap connectivity-wrap">
      <h1>What works right now?</h1>
      <p className="page-intro">
        Your connection can change. Your saved work stays separate from what has reached Swarm.
      </p>
      <div className="connect-actions">
        <Link className="button" href="/offline">
          Open saved work
        </Link>
        <Link className="button secondary" href="/nearby">
          Connect nearby
        </Link>
      </div>
      <dl className="connection-details">
        <div>
          <dt>Internet / Swarm</dt>
          <dd>
            {!c.checked
              ? 'Checking…'
              : c.internet
                ? c.weak
                  ? 'Connected, but slow'
                  : 'Connected'
                : 'Not available'}
          </dd>
        </div>
        <div>
          <dt>Nearby Swarm</dt>
          <dd>{c.nearby ? 'One paired person is connected' : 'No paired person connected'}</dd>
        </div>
        <div>
          <dt>Information on this phone</dt>
          <dd>
            {!local.storageChecked
              ? 'Checking saved information…'
              : !local.storage
                ? 'Saved information is unavailable. Check storage permissions.'
                : local.savedAt
                  ? `Relief information last saved ${formatDate(local.savedAt)}. Things may have changed.`
                  : 'No relief information saved yet'}
          </dd>
        </div>
        <div>
          <dt>Waiting to be sent</dt>
          <dd>
            {local.events.filter((e) => !e.receipt).length} relief updates · {local.draftCount}{' '}
            drafts · {local.mediaCount} drafts with media
          </dd>
        </div>
        <div>
          <dt>Last connected to Swarm</dt>
          <dd>
            {c.lastConnected ? formatDate(c.lastConnected) : 'No successful connection recorded'}
          </dd>
        </div>
      </dl>
      <div className="capability-columns">
        <section>
          <h2>You can do this now</h2>
          <ul className="capability-list">
            {can.map((text) => (
              <li key={text}>
                <Check size={17} />
                {text}
              </li>
            ))}
          </ul>
        </section>
        <section>
          <h2>Things to keep in mind</h2>
          <ul className="capability-list">
            {cannot.map((text) => (
              <li key={text}>
                <Minus size={17} />
                {text}
              </li>
            ))}
          </ul>
        </section>
      </div>
      <section className="offline-preparation">
        <h2>Prepare before you lose internet</h2>
        <p>
          Open Swarm once while connected. Save the app for offline opening, then prepare publishing
          if you are an approved volunteer. Removing Swarm or clearing browser data removes local
          work and keys.
        </p>
        <div className="connect-actions">
          <button
            className="button secondary"
            disabled={preparingOffline}
            onClick={() => void prepareOffline()}
          >
            Save app for offline opening
          </button>
          <button
            className="button secondary"
            disabled={busy || !c.internet}
            onClick={() =>
              void run(async () => {
                await preparePublishing();
                setNotice('Offline publishing is prepared for this signed-in volunteer.');
              })
            }
          >
            {local.prepared ? 'Refresh volunteer preparation' : 'Prepare volunteer publishing'}
          </button>
        </div>
        <p className="form-hint">
          On Android, use your browser menu to install Swarm. On iPhone, use Share → Add to Home
          Screen. Installation does not grant radio permissions.
        </p>
      </section>
      <section className="gateway-settings">
        <h2>Carry relief updates when connected</h2>
        <label className="check-label">
          <input
            type="checkbox"
            checked={local.gateway}
            onChange={(e) => void setSetting('gateway-consent', e.target.checked)}
          />
          Allow this phone to send eligible public relief updates to Swarm when it reconnects,
          including updates received nearby.
        </label>
        <p className="form-hint">
          This sends signed relief text only. Private messages, calls and media originals stay off
          this gateway. Your normal data charges may apply.
        </p>
        <button
          className="button secondary"
          disabled={busy || !c.internet}
          onClick={() =>
            void run(async () => {
              await syncEvents();
              setNotice(
                'Saved relief updates were checked with Swarm. See their individual results in Saved work.',
              );
            })
          }
        >
          <RefreshCw size={16} />
          Send and check saved relief updates
        </button>
      </section>
      <section>
        <h2>Keep control of this phone</h2>
        {devices
          .filter((d) => !d.revokedAt)
          .map((d) => (
            <div className="saved-item" key={d.id}>
              <p>
                Publishing device prepared {formatDate(d.createdAt)} · {d.id.slice(0, 8)}
              </p>
              <button
                className="text-link"
                disabled={busy || !c.internet}
                onClick={() =>
                  void run(async () => {
                    await write(`/sync/devices/${d.id}/revoke`, {});
                    if ((await setting<{ id: string }>('signing-device'))?.id === d.id) {
                      await (await db()).delete('settings', 'preparation');
                      changed();
                    }
                    setDevices(await api<typeof devices>('/sync/devices'));
                    setNotice(
                      'Publishing approval was removed for that device. Its waiting updates will be rejected; refresh preparation before writing new ones.',
                    );
                  })
                }
              >
                Remove publishing approval for this device
              </button>
            </div>
          ))}
        <div className="connect-actions">
          <button
            className="button secondary"
            disabled={busy}
            onClick={() =>
              void run(async () => downloadJson(await exportWork(), 'saathi-saved-work.json'))
            }
          >
            <Download size={16} />
            Export saved text and events
          </button>
          <button
            className="button danger"
            disabled={busy}
            onClick={() => {
              if (
                window.confirm(
                  'Clear private drafts, messages, attachments and publishing keys on this phone? Export important work first.',
                )
              )
                void run(async () => {
                  const device = await setting<{ id: string }>('signing-device');
                  let revoked = !device;
                  if (device && c.internet) {
                    try {
                      await write(`/sync/devices/${device.id}/revoke`, {});
                      revoked = true;
                    } catch {
                      /* Local privacy controls must still work after a session expires. */
                    }
                  }
                  await clearPrivate();
                  setDevices([]);
                  setNotice(
                    revoked
                      ? 'Private saved work and publishing keys were cleared.'
                      : 'Private saved work and keys were cleared here. Reconnect and remove this device’s publishing approval from your account too.',
                  );
                });
            }}
          >
            <Trash2 size={16} />
            Clear private saved work
          </button>
        </div>
        <p className="form-hint">
          The text export does not contain media files or private signing keys. Save your original
          media separately.
        </p>
      </section>
      {(error || c.storageError) && <ErrorNotice message={error || c.storageError} />}
      {notice && (
        <p className="success-notice" role="status">
          {notice}
        </p>
      )}
    </div>
  );
}
