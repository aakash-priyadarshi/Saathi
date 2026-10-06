'use client';
import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { FileText, Send, Download } from 'lucide-react';
import { ErrorNotice } from '@saathi/ui';
import type { PublicRequest, PublicPost } from '@saathi/types';
import { categories } from '@saathi/types';
import {
  db,
  events,
  snapshot,
  setting,
  changed,
  type SavedEvent,
  type Preparation,
} from '../lib/offline/store';
import { authorEvent, syncEvents } from '../lib/offline/sync';
import { useConnection } from './connectivity-provider';
import { EventStatus, downloadJson } from './connectivity-page';
import { formatDate } from '../lib/api';
import { api, write } from '../lib/api';
import { acceptReceipt } from '../lib/offline/sync';
import type { Receipt } from '@saathi/protocol';
type Draft = {
  id: string;
  kind: string;
  values: Record<string, string>;
  media?: File[];
  savedAt: string;
};
export function OfflineWorkspace() {
  const editorHeading = useRef<HTMLHeadingElement>(null);
  const c = useConnection(),
    [needs, setNeeds] = useState<PublicRequest[]>([]),
    [posts, setPosts] = useState<PublicPost[]>([]),
    [age, setAge] = useState(''),
    [preparation, setPreparation] = useState<Preparation>(),
    [work, setWork] = useState<SavedEvent[]>([]),
    [drafts, setDrafts] = useState<Draft[]>([]);
  const [kind, setKind] = useState('REQUEST_CREATED'),
    [values, setValues] = useState<Record<string, string>>({}),
    [files, setFiles] = useState<File[]>([]),
    [editing, setEditing] = useState<string>(),
    [error, setError] = useState(''),
    [notice, setNotice] = useState(''),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    async function load() {
      try {
        const saved = await snapshot<PublicRequest[]>('/public/requests'),
          feed = await snapshot<PublicPost[]>('/public/feed');
        setNeeds(saved?.data ?? []);
        setPosts(feed?.data ?? []);
        setAge(saved?.savedAt ?? '');
        setPreparation(await setting('preparation'));
        setWork(await events());
        setDrafts(await (await db()).getAll('drafts'));
      } catch {
        setError('This browser could not open saved work. Check storage permissions.');
      }
    }
    void load();
    window.addEventListener('saathi-local-change', load);
    return () => window.removeEventListener('saathi-local-change', load);
  }, []);
  function value(name: string, text: string) {
    setValues((v) => ({ ...v, [name]: text }));
  }
  async function save(send: boolean) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const id = editing ?? crypto.randomUUID(),
        draft: Draft = { id, kind, values, media: files, savedAt: new Date().toISOString() };
      const database = await db();
      if (!editing && (await database.count('drafts')) >= 100)
        throw new Error('Draft storage is full. Export or remove old work first.');
      await database.put('drafts', draft, id);
      setEditing(id);
      changed();
      if (!send) {
        setNotice('Saved on this phone. It has not reached anyone else or appeared on Swarm.');
        return;
      }
      const point = preparation?.points.find((p) => p.id === values.reliefPointId);
      if (!point)
        throw new Error(
          'Prepare volunteer publishing and choose your team’s relief point. Your draft is saved.',
        );
      let payload;
      if (kind === 'REQUEST_CREATED')
        payload = {
          reliefPointId: point.id,
          category: values.category ?? 'WATER',
          title: values.title ?? '',
          description: values.description ?? '',
          requestedQuantity: Number(values.quantity),
          unit: values.unit ?? '',
          priority: values.priority ?? 'NORMAL',
          deadline: new Date(values.deadline ?? '').toISOString(),
        };
      else if (kind === 'REQUEST_UPDATED')
        payload = {
          publicId: values.publicId ?? '',
          changes: {
            version: Number(values.version),
            title: values.title || undefined,
            description: values.description || undefined,
            ...(values.quantity ? { requestedQuantity: Number(values.quantity) } : {}),
          },
        };
      else payload = { reliefPointId: point.id, caption: values.caption ?? '', mediaIds: [] };
      const event = await authorEvent(
        kind as 'REQUEST_CREATED' | 'REQUEST_UPDATED' | 'FIELD_PUBLISHED',
        payload as Parameters<typeof authorEvent>[1],
        point.organizationId,
      );
      if (files.length) {
        await database.put(
          'drafts',
          { ...draft, values: { ...values, relatedEventId: event.id } },
          id,
        );
        setValues((v) => ({ ...v, relatedEventId: event.id }));
        setNotice(
          'Relief text is ready to send. Your media is saved privately on this phone and waits for a direct connection.',
        );
      } else {
        await database.delete('drafts', id);
        setEditing(undefined);
        setValues({});
        setNotice('Saved and ready to send. Its progress is shown below.');
      }
      changed();
      if (c.internet) await syncEvents();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not save this work. Please try again.');
    } finally {
      setBusy(false);
    }
  }
  async function sendMedia(draft: Draft) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      if (!c.internet) throw new Error('Reconnect to Swarm to send private media.');
      const author = await api<{ id: string }>('/auth/me');
      const event = (await events()).find((e) => e.id === draft.values.relatedEventId);
      if (!event || event.envelope.body.authorId !== author.id)
        throw new Error('Sign in as the original author before sending this media.');
      await syncEvents();
      const accepted = (await events()).find((e) => e.id === event.id);
      if (
        !accepted?.receipt?.body.fieldId ||
        !['PUBLISHED', 'ACCEPTED'].includes(accepted.receipt.body.status)
      )
        throw new Error('The relief text must be accepted by Swarm before its media can be sent.');
      const database = await db(),
        ids: string[] = JSON.parse(draft.values.uploadedIds ?? '[]');
      for (let index = ids.length; index < (draft.media?.length ?? 0); index++) {
        const form = new FormData();
        form.set('file', draft.media![index]!);
        form.set('organizationId', event.envelope.body.organizationId);
        const result = await api<{ id: string }>('/volunteer/media', {
          method: 'POST',
          body: form,
          signal: AbortSignal.timeout(150000),
        });
        ids.push(result.id);
        draft = { ...draft, values: { ...draft.values, uploadedIds: JSON.stringify(ids) } };
        await database.put('drafts', draft, draft.id);
        changed();
      }
      await acceptReceipt(
        await write<Receipt>(`/sync/events/${event.id}/media`, { mediaIds: ids }),
      );
      await database.delete('drafts', draft.id);
      if (editing === draft.id) {
        setEditing(undefined);
        setValues({});
        setFiles([]);
      }
      changed();
      setNotice(
        'Media reached Swarm and is waiting for moderation. The original field post is used.',
      );
    } catch (e) {
      setError(
        e instanceof Error ? e.message : 'Media is still saved here. Reconnect and try again.',
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="page-wrap offline-wrap">
      <div className="workspace-header">
        <div>
          <h1>Saved work</h1>
          <p>Read what is on this phone. Write now, send when a connection is available.</p>
        </div>
        <Link className="text-link" href="/connectivity">
          Connection details
        </Link>
      </div>
      <div className="offline-layout">
        <section className="offline-editor">
          <h2 ref={editorHeading} tabIndex={-1}>
            Write a relief update
          </h2>
          <p className="form-hint">
            {preparation
              ? `Prepared for ${preparation.user.displayName}. Swarm rechecks approval when an update arrives.`
              : 'Anyone can save a draft. Approved volunteers prepare publishing while connected first.'}
          </p>
          <form
            className="stack-form"
            onSubmit={(e) => {
              e.preventDefault();
              void save(false);
            }}
          >
            <label>
              What are you writing?
              <select
                value={kind}
                disabled={Boolean(values.relatedEventId)}
                onChange={(e) => setKind(e.target.value)}
              >
                <option value="REQUEST_CREATED">New supply request</option>
                <option value="FIELD_PUBLISHED">Field update</option>
                <option value="REQUEST_UPDATED">Change a saved request</option>
              </select>
            </label>
            <label>
              Relief point
              <select
                value={values.reliefPointId ?? ''}
                disabled={Boolean(values.relatedEventId)}
                onChange={(e) => value('reliefPointId', e.target.value)}
              >
                <option value="">Choose a prepared relief point</option>
                {preparation?.points.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name} · {p.publicLocation}
                  </option>
                ))}
              </select>
            </label>
            {kind === 'FIELD_PUBLISHED' ? (
              <>
                <label>
                  What is happening?
                  <textarea
                    maxLength={4000}
                    aria-label="What is happening?"
                    disabled={Boolean(values.relatedEventId)}
                    value={values.caption ?? ''}
                    onChange={(e) => value('caption', e.target.value)}
                  />
                </label>
                <label>
                  Private photo or video{' '}
                  <span className="optional">optional; sent directly while signed in</span>
                  <input
                    type="file"
                    accept="image/jpeg,image/png,image/webp,video/mp4,video/webm"
                    multiple
                    disabled={Boolean(values.relatedEventId)}
                    onChange={(e) => {
                      const selected = Array.from(e.target.files ?? []);
                      if (selected.length > 4 || selected.some((f) => f.size > 25 * 1024 * 1024)) {
                        setError('Choose up to four files, each below 25 MB.');
                        return;
                      }
                      setFiles(selected);
                    }}
                  />
                </label>
                {files.length > 0 && (
                  <p className="form-hint">
                    {files.length} originals stay privately on this phone. Text can travel first.
                    Media still needs Swarm’s sanitization and moderation.
                  </p>
                )}
              </>
            ) : (
              <>
                {kind === 'REQUEST_UPDATED' && (
                  <div className="form-grid">
                    <label>
                      Public request ID
                      <input
                        value={values.publicId ?? ''}
                        onChange={(e) => value('publicId', e.target.value.toUpperCase())}
                      />
                    </label>
                    <label>
                      Last known version
                      <input
                        type="number"
                        min={1}
                        value={values.version ?? ''}
                        onChange={(e) => value('version', e.target.value)}
                      />
                    </label>
                  </div>
                )}
                <label>
                  Title
                  <input
                    maxLength={100}
                    value={values.title ?? ''}
                    onChange={(e) => value('title', e.target.value)}
                  />
                </label>
                <label>
                  Description
                  <textarea
                    maxLength={2000}
                    aria-label="Description"
                    value={values.description ?? ''}
                    onChange={(e) => value('description', e.target.value)}
                  />
                </label>
                <div className="form-grid">
                  <label>
                    Quantity
                    <input
                      type="number"
                      min={1}
                      max={1000000}
                      value={values.quantity ?? ''}
                      onChange={(e) => value('quantity', e.target.value)}
                    />
                  </label>
                  {kind === 'REQUEST_CREATED' && (
                    <label>
                      Unit
                      <input
                        maxLength={30}
                        placeholder="bottles, meals, kits…"
                        value={values.unit ?? ''}
                        onChange={(e) => value('unit', e.target.value)}
                      />
                    </label>
                  )}
                </div>
                {kind === 'REQUEST_CREATED' && (
                  <>
                    <div className="form-grid">
                      <label>
                        Category
                        <select
                          value={values.category ?? 'WATER'}
                          onChange={(e) => value('category', e.target.value)}
                        >
                          {categories.map((category) => (
                            <option key={category} value={category}>
                              {category.toLowerCase()}
                            </option>
                          ))}
                        </select>
                      </label>
                      <label>
                        Priority
                        <select
                          value={values.priority ?? 'NORMAL'}
                          onChange={(e) => value('priority', e.target.value)}
                        >
                          <option value="NORMAL">Normal</option>
                          <option value="HIGH">High</option>
                          <option value="URGENT">Urgent</option>
                        </select>
                      </label>
                    </div>
                    <label>
                      Needed by
                      <input
                        type="datetime-local"
                        value={values.deadline ?? ''}
                        onChange={(e) => value('deadline', e.target.value)}
                      />
                    </label>
                  </>
                )}
              </>
            )}
            <div className="connect-actions">
              <button className="button secondary" disabled={busy}>
                <FileText size={16} />
                Save draft on this phone
              </button>
              <button
                type="button"
                className="button"
                disabled={busy || !preparation || Boolean(values.relatedEventId)}
                onClick={() => void save(true)}
              >
                <Send size={16} />
                Save and prepare to send
              </button>
            </div>
            <p className="form-hint">
              Donor reservations and purchases require a current connection to Swarm. Nearby
              delivery does not make an update public.
            </p>
          </form>
          {error && <ErrorNotice message={error} />}
          {notice && (
            <p className="success-notice" role="status">
              {notice}
            </p>
          )}
        </section>
        <aside className="saved-work-list">
          <h2>On this phone</h2>
          {drafts.length === 0 && work.length === 0 && (
            <p>No saved work yet. Your first draft will appear here.</p>
          )}
          {drafts.map((d) => (
            <article className="saved-item" key={d.id}>
              <h3>{d.values.title || d.values.caption?.slice(0, 60) || 'Untitled draft'}</h3>
              <p>
                Saved {formatDate(d.savedAt)}
                {d.media?.length ? ` · ${d.media.length} private files` : ''}
              </p>
              <button
                className="text-link"
                onClick={() => {
                  setEditing(d.id);
                  setKind(d.kind);
                  setValues(d.values);
                  setFiles(d.media ?? []);
                  editorHeading.current?.focus({ preventScroll: true });
                  editorHeading.current?.scrollIntoView({
                    block: 'start',
                    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
                      ? 'instant'
                      : 'smooth',
                  });
                }}
              >
                Open draft
              </button>
              {d.media?.map((file, index) => (
                <button
                  key={index}
                  className="text-link"
                  onClick={() => {
                    const url = URL.createObjectURL(file),
                      link = document.createElement('a');
                    link.href = url;
                    link.download = file.name;
                    link.click();
                    setTimeout(() => URL.revokeObjectURL(url), 1000);
                  }}
                >
                  Save original {index + 1}
                </button>
              ))}
              {d.media?.length && d.values.relatedEventId ? (
                <button
                  className="button secondary"
                  disabled={busy || !c.internet}
                  onClick={() => void sendMedia(d)}
                >
                  Send private media to Swarm
                </button>
              ) : null}
            </article>
          ))}
          {work
            .sort((a, b) => b.savedAt.localeCompare(a.savedAt))
            .map((event) => (
              <article className="saved-item" key={event.id}>
                <h3>
                  {event.envelope.body.type === 'REQUEST_CREATED'
                    ? event.envelope.body.payload.title
                    : event.envelope.body.type === 'FIELD_PUBLISHED'
                      ? event.envelope.body.payload.caption.slice(0, 60)
                      : `Change to ${event.envelope.body.payload.publicId}`}
                </h3>
                <p>
                  {event.own
                    ? 'Your update'
                    : 'Received nearby · Swarm checks the author when this arrives online'}
                </p>
                <EventStatus event={event} />
                {(event.receipt?.body.publicId ||
                  event.envelope.body.type === 'REQUEST_UPDATED') && (
                  <Link
                    href={`/r/${event.receipt?.body.publicId ?? (event.envelope.body.type === 'REQUEST_UPDATED' ? event.envelope.body.payload.publicId : '')}`}
                    className="text-link"
                  >
                    Check the canonical request
                  </Link>
                )}
                <button
                  className="text-link"
                  onClick={() => downloadJson(event.envelope, 'saathi-relief-update.json')}
                >
                  <Download size={14} />
                  Export original signed update
                </button>
              </article>
            ))}
        </aside>
      </div>
      <section className="saved-information">
        <h2>Last known relief information</h2>
        <p className="status-notice closed">
          {age
            ? `Last saved ${formatDate(age)}. Things may have changed. Reconnect before buying or sending supplies.`
            : 'Visit Needs and Live while connected to save public information on this phone.'}
        </p>
        <div className="saved-needs">
          {needs.map((need) => (
            <article key={need.publicId} className="saved-item">
              <h3>{need.title}</h3>
              <p>
                {need.reliefPoint.name} · {need.reliefPoint.publicLocation}
              </p>
              <p>
                Last known: {need.remainingQuantity} {need.unit} needed.{' '}
                {need.status.toLowerCase().replaceAll('_', ' ')}.
              </p>
              <Link href={`/r/${need.publicId}`} className="text-link">
                Check this request when connected
              </Link>
            </article>
          ))}
        </div>
        {posts.slice(0, 10).map((post) => (
          <article className="saved-item" key={post.id}>
            <p>{post.caption}</p>
            <small>
              {post.author.displayName} · Reported {formatDate(post.createdAt)}. Media may require a
              connection.
            </small>
          </article>
        ))}
      </section>
    </div>
  );
}
