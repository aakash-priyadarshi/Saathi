'use client';
import Link from 'next/link';
import { useState } from 'react';
import {
  ArrowRight,
  MapPin,
  Clock,
  Search,
  ShieldCheck,
  CheckCircle2,
  PackageCheck,
} from 'lucide-react';
import { Verified, Loading, ErrorNotice } from '@saathi/ui';
import { categories, terminalStatuses } from '@saathi/types';
import type { PublicRequest, PublicPost } from '@saathi/types';
import { count, deadline, formatTime, formatDate, useResource } from '../lib/api';
import { useConnection } from './connectivity-provider';
const categoryLabels: Record<string, string> = {
  FOOD: 'Food',
  WATER: 'Water',
  MEDICAL: 'Medical',
  HYGIENE: 'Hygiene',
  CLOTHING: 'Clothing',
  POWER: 'Power',
  SHELTER: 'Shelter',
  OTHER: 'Other',
};
export function RequestCard({ request: r }: { request: PublicRequest }) {
  const complete = r.status === 'COMPLETED',
    closed = terminalStatuses.includes(r.status);
  const progress = Math.min(
    100,
    ((complete ? r.receivedQuantity : r.committedQuantity) / r.requestedQuantity) * 100,
  );
  return (
    <article className={`need-card ${complete ? 'complete-card' : ''}`}>
      <h2>
        <Link href={`/r/${r.publicId}`}>
          {r.title}
          <ArrowRight size={20} />
        </Link>
      </h2>
      <div className="card-top">
        <span className="category-label">{categoryLabels[r.category]}</span>
        {complete ? (
          <span className="badge completed">
            <CheckCircle2 size={13} />
            Fulfilled
          </span>
        ) : (
          r.priority !== 'NORMAL' && (
            <span className={`badge ${r.priority === 'URGENT' ? 'urgent' : 'high'}`}>
              {r.priority === 'URGENT' ? 'Urgent need' : 'High priority'}
            </span>
          )
        )}
      </div>
      <p className="request-description">{r.description}</p>
      <div className="quantity-line">
        {complete ? (
          <>
            <strong>{count(r.receivedQuantity)}</strong>
            <span>{r.unit} received</span>
          </>
        ) : (
          <>
            <strong>{count(r.remainingQuantity)}</strong>
            <span>{r.unit} still needed</span>
          </>
        )}
      </div>
      <div
        className="progress"
        role="progressbar"
        aria-label={complete ? 'Supplies received' : 'Supplies committed'}
        aria-valuenow={complete ? r.receivedQuantity : r.committedQuantity}
        aria-valuemin={0}
        aria-valuemax={r.requestedQuantity}
      >
        <span style={{ transform: `scaleX(${progress / 100})` }} />
      </div>
      <div className="progress-caption">
        <span>
          {count(complete ? r.receivedQuantity : r.committedQuantity)} of{' '}
          {count(r.requestedQuantity)} {complete ? 'received' : 'committed'}
        </span>
        <span>{Math.round(progress)}%</span>
      </div>
      <div className="request-meta">
        <span>
          <MapPin size={15} />
          {r.reliefPoint.name} · {r.reliefPoint.publicLocation}
        </span>
        <span>
          <Clock size={15} />
          {closed ? 'This request is closed' : deadline(r.deadline)}
        </span>
      </div>
      <div className="card-bottom">
        <div>
          <Verified />
          <small>{r.organization.name}</small>
        </div>
        <Link
          className={`button ${closed || r.remainingQuantity === 0 ? 'secondary' : ''}`}
          href={`/r/${r.publicId}`}
        >
          {complete ? 'View impact' : r.remainingQuantity === 0 ? 'View request' : 'I can help'}
          <ArrowRight size={16} />
        </Link>
      </div>
    </article>
  );
}
export function FieldPost({ post: p, compact = false }: { post: PublicPost; compact?: boolean }) {
  const [showMedia, setShowMedia] = useState(!p.contentWarning);
  return (
    <article className={`field-post ${compact ? 'compact' : ''}`}>
      <div className="post-time">
        <span className="live-dot" />
        <time dateTime={p.createdAt}>Reported {formatTime(p.createdAt)}</time>
        <span>{p.reliefPoint.name}</span>
      </div>
      <p>{p.caption}</p>
      {p.receivedAt && (
        <small>
          Received online {formatDate(p.receivedAt)}
          {Date.parse(p.receivedAt) - Date.parse(p.createdAt) > 900000 ? ' · Delayed report' : ''}
          {Date.now() - Date.parse(p.createdAt) > 86400000 ? ' · Conditions may have changed' : ''}
        </small>
      )}
      {!compact && p.contentWarning && !showMedia && (
        <button className="button secondary" onClick={() => setShowMedia(true)}>
          Content warning · View media
        </button>
      )}
      {!compact &&
        showMedia &&
        p.media.map((m) =>
          m.mimeType.startsWith('video') ? (
            <video
              key={m.id}
              controls
              preload="metadata"
              poster={m.thumbnailUrl ?? undefined}
              src={m.url}
              aria-label={p.caption}
            />
          ) : (
            <img key={m.id} src={m.url} alt={p.caption} loading="lazy" />
          ),
        )}
      <div className="post-author">
        {p.verificationState === 'PARTICIPANT' ? (
          <span>Participant report · Unverified</span>
        ) : (
          <Verified label="Verified volunteer" />
        )}
        <span>
          {p.author.displayName} · {p.organization.name}
        </span>
      </div>
      {p.requestPublicId && (
        <Link className="text-link" href={`/r/${p.requestPublicId}`}>
          View the related need <ArrowRight size={14} />
        </Link>
      )}
    </article>
  );
}
export function NeedsPage({ completed = false }: { completed?: boolean }) {
  const connection = useConnection();
  const config = useResource<{ features?: { needs?: boolean } }>('/public/config', true);
  const needsEnabled = config.data?.features?.needs !== false;
  const { data, error, loading, refresh, stale, savedAt } = useResource<PublicRequest[]>(
    `/public/requests${completed ? '?completed=true' : ''}`,
    true,
  );
  const feed = useResource<PublicPost[]>('/public/feed', true);
  const [category, setCategory] = useState('ALL'),
    [search, setSearch] = useState('');
  const filtered = data?.filter(
    (r) =>
      (category === 'ALL' || r.category === category) &&
      `${r.title} ${r.reliefPoint.name} ${r.reliefPoint.publicLocation}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  return (
    <div className="page-wrap">
      {data && (stale || (connection.checked && !connection.internet)) && (
        <p className="status-notice closed">
          Last known information{savedAt ? `, saved ${formatDate(savedAt)}` : ''}. Things may have
          changed. Reconnect before buying or sending supplies.
        </p>
      )}
      <section className="intro">
        <div>
          <h1>
            {!needsEnabled ? (
              <>Relief needs are paused.</>
            ) : completed ? (
              <>
                Help that made
                <br />
                its way home.
              </>
            ) : (
              <>
                A little help.
                <br />
                <em>Right where it’s needed.</em>
              </>
            )}
          </h1>
          <p>
            {!needsEnabled
              ? 'Public relief requests and contributions are temporarily paused by the network administrator.'
              : completed
                ? 'These needs have been met. Their pages stay available so every shared link tells the full story.'
                : 'Find a verified need, give what you can, and see your help arrive. Together, we look after each other.'}
          </p>
        </div>
        <div className="trust-note">
          <ShieldCheck size={28} strokeWidth={1.5} />
          <div>
            <strong>Real needs. Verified teams.</strong>
            <p>
              Requests from approved volunteers.
              <br />
              Quantities kept up to date.
            </p>
            <Link href="/about" className="text-link">
              How it works <ArrowRight size={14} />
            </Link>
          </div>
        </div>
      </section>
      {needsEnabled && (
        <div className="section-title">
          <div>
            <h2>{completed ? 'Completed requests' : 'What’s needed now'}</h2>
            <p>
              {completed
                ? 'Every delivery counts.'
                : 'Choose a need. Your contribution can be any size.'}
            </p>
          </div>
          <label className="search">
            <Search size={17} />
            <span className="sr-only">Search needs</span>
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Search needs or locations"
            />
          </label>
        </div>
      )}
      {needsEnabled && (
        <div className="category-filters" aria-label="Filter needs by category">
          {['ALL', ...categories].map((c) => (
            <button key={c} aria-pressed={category === c} onClick={() => setCategory(c)}>
              {c === 'ALL' ? 'All needs' : categoryLabels[c]}
            </button>
          ))}
        </div>
      )}
      <div className="content-columns">
        <section aria-label="Relief requests">
          {!needsEnabled ? (
            <div className="empty-state" role="status">
              <PackageCheck size={32} />
              <h3>Requests are temporarily paused</h3>
              <p>Check back later for verified needs.</p>
            </div>
          ) : loading ? (
            <Loading />
          ) : error ? (
            <ErrorNotice message={error} retry={() => void refresh()} />
          ) : filtered?.length ? (
            <div className="needs-grid">
              {filtered.map((r) => (
                <RequestCard key={r.publicId} request={r} />
              ))}
            </div>
          ) : (
            <div className="empty-state">
              <PackageCheck size={32} />
              <h3>{completed ? 'No completed requests yet' : 'No matching needs right now'}</h3>
              <p>
                {search || category !== 'ALL'
                  ? 'Try another category or location.'
                  : 'Check the live updates for the latest from the field.'}
              </p>
            </div>
          )}
        </section>
        <aside className="live-column">
          <div className="live-heading">
            <h2>
              <span className="live-dot" />
              From the field
            </h2>
            <Link href="/live" aria-label="View all field updates">
              <ArrowUpRightIcon />
            </Link>
          </div>
          <p className="muted">Updates from people on the ground.</p>
          {feed.error ? (
            <ErrorNotice message={feed.error} />
          ) : feed.loading ? (
            <Loading />
          ) : feed.data?.length ? (
            feed.data.slice(0, 3).map((p) => <FieldPost key={p.id} post={p} compact />)
          ) : (
            <p className="muted">No field updates yet.</p>
          )}
          <div className="verify-note">
            <ShieldCheck size={23} />
            <h3>Seen a request shared online?</h3>
            <p>A screenshot can get old. Check the request ID for its current status.</p>
            <Link className="button secondary" href="/verify">
              Verify a request <ArrowRight size={15} />
            </Link>
          </div>
        </aside>
      </div>
      {needsEnabled && (
        <div className="trust-note mobile-trust">
          <ShieldCheck size={28} strokeWidth={1.5} />
          <div>
            <strong>Real needs. Verified teams.</strong>
            <p>
              Requests from approved volunteers.
              <br />
              Quantities kept up to date.
            </p>
            <Link href="/about" className="text-link">
              How it works <ArrowRight size={14} />
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}
function ArrowUpRightIcon() {
  return <ArrowRight size={20} style={{ transform: 'rotate(-45deg)' }} />;
}
export function LivePage() {
  const connection = useConnection();
  const { data, error, loading, refresh, stale, savedAt } = useResource<PublicPost[]>(
    '/public/feed',
    true,
  );
  return (
    <div className="page-wrap narrow">
      <div className="page-heading">
        <h1>From the field</h1>
        <p>
          Reports from the field. Volunteer updates and unverified participant reports are labeled
          separately.
        </p>
      </div>
      <div className="feed-status">
        <span className="live-dot" />
        {stale || !connection.internet ? 'Saved updates' : 'Live updates'} · Times shown in IST
      </div>
      {data && (stale || !connection.internet) && (
        <p className="status-notice closed">
          Last saved{savedAt ? ` ${formatDate(savedAt)}` : ''}. New field information may be missing
          until you reconnect.
        </p>
      )}
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNotice message={error} retry={() => void refresh()} />
      ) : data?.length ? (
        data.map((p) => <FieldPost key={p.id} post={p} />)
      ) : (
        <div className="empty-state">No field updates have been published yet.</div>
      )}
    </div>
  );
}
