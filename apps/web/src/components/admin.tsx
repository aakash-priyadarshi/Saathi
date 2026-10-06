'use client';
import Link from 'next/link';
import { useState } from 'react';
import { ArrowRight, Check, ExternalLink, ShieldCheck, X } from 'lucide-react';
import { ErrorNotice, Loading } from '@saathi/ui';
import type { CurrentUser } from '@saathi/types';
import { api, formatDate, useMutation, useResource } from '../lib/api';
import { DashboardFrame } from './dashboard';

type FeatureState = { features: { needs: boolean }; updatedAt: string | null };
type FeedReview = {
  id: string;
  caption: string;
  moderation: string;
  createdAt: string;
  author: { displayName: string } | null;
  participantName?: string | null;
  media: { id: string; mimeType: string; processingState: string }[];
};
type HelpReview = {
  id: string;
  objectId: string;
  version: number;
  moderation: 'PENDING' | 'APPROVED';
  authorName: string;
  area: string | null;
  receivedAt: string;
  expiresAt: string;
  help: {
    category: string;
    audience: string;
    quantity: number;
    details: string;
    priority: string;
    status: string;
  };
};
type Audit = { id: string; event: string; entityType: string; entityId: string; createdAt: string };

export function AdminPage() {
  const { data: user, error, loading } = useResource<CurrentUser>('/auth/me');
  if (loading)
    return (
      <div className="page-wrap">
        <Loading />
      </div>
    );
  if (error || !user)
    return (
      <div className="page-wrap">
        <ErrorNotice message={error || 'Sign in to continue.'} />
      </div>
    );
  if (user.role !== 'ADMIN')
    return (
      <div className="page-wrap">
        <ErrorNotice message="This workspace is available to administrators only." />
      </div>
    );
  return (
    <DashboardFrame title="Administration">
      <AdminContent user={user} />
    </DashboardFrame>
  );
}

function AdminContent({ user }: { user: CurrentUser }) {
  const features = useResource<FeatureState>('/admin/features');
  const feed = useResource<FeedReview[]>('/coordinator/moderation');
  const help = useResource<HelpReview[]>('/community/moderation');
  const audits = useResource<Audit[]>('/admin/audits');
  const mutation = useMutation();
  const [message, setMessage] = useState('');

  async function act(path: string, body: unknown = {}, method = 'POST') {
    const result = await mutation.run(path, body, false, method);
    if (!result) return;
    setMessage('Saved. Connected apps will receive the change the next time they sync.');
    await Promise.all([features.refresh(), feed.refresh(), help.refresh(), audits.refresh()]);
  }

  const pendingFeed = feed.data?.filter((item) => item.moderation === 'PENDING') ?? [];
  const approvedFeed = feed.data?.filter((item) => item.moderation === 'APPROVED') ?? [];

  return (
    <main className="admin-page">
      <div className="admin-intro">
        <div>
          <h2>Keep the network safe and useful.</h2>
          <p>
            Review public updates, decide which nearby help requests can circulate, and pause public
            relief needs when operations require it.
          </p>
        </div>
        <Link className="button secondary" href="/dashboard/manage">
          Team and organizations <ArrowRight size={16} />
        </Link>
      </div>

      {mutation.error && <ErrorNotice message={mutation.error} />}
      {message && (
        <p className="success-message" role="status">
          {message}
        </p>
      )}

      <section className="admin-section" aria-labelledby="feature-heading">
        <div className="admin-section-heading">
          <div>
            <h2 id="feature-heading">Feature availability</h2>
            <p>Changes are enforced by the API and published to Android when it reconnects.</p>
          </div>
          <ShieldCheck size={24} aria-hidden="true" />
        </div>
        {features.loading ? (
          <Loading />
        ) : features.error ? (
          <ErrorNotice message={features.error} retry={() => void features.refresh()} />
        ) : (
          <div className="feature-control">
            <div>
              <h3>Public relief needs</h3>
              <p>
                {features.data?.features.needs
                  ? 'People can browse and contribute to verified relief requests. Android removes the Needs destination and cached public requests after its next successful connection when paused.'
                  : 'Browsing and new contributions are paused. Android hides Needs and clears its saved request lists after it reconnects.'}
              </p>
              {features.data?.updatedAt && (
                <small>Last changed {formatDate(features.data.updatedAt)}</small>
              )}
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={features.data?.features.needs ?? false}
              aria-label="Enable public relief needs"
              className={`admin-switch ${features.data?.features.needs ? 'is-on' : ''}`}
              disabled={mutation.busy || !features.data}
              onClick={() =>
                void act(
                  '/admin/features/needs',
                  { enabled: !features.data?.features.needs },
                  'PATCH',
                )
              }
            >
              <span />
            </button>
          </div>
        )}
      </section>

      <section className="admin-section" aria-labelledby="feed-heading">
        <div className="admin-section-heading">
          <div>
            <h2 id="feed-heading">Field updates</h2>
            <p>Participant reports and media stay out of the public feed until approved.</p>
          </div>
          <span className="admin-count">{pendingFeed.length} awaiting review</span>
        </div>
        {feed.loading ? (
          <Loading />
        ) : feed.error ? (
          <ErrorNotice message={feed.error} retry={() => void feed.refresh()} />
        ) : (
          <>
            {pendingFeed.length === 0 && (
              <p className="admin-empty">No field updates are waiting for review.</p>
            )}
            {pendingFeed.map((item) => (
              <article className="admin-review" key={item.id}>
                <div className="admin-review-copy">
                  <h3>{item.participantName ? 'Participant report' : 'Volunteer update'}</h3>
                  <p>{item.caption}</p>
                  <small>
                    {item.author?.displayName ?? item.participantName ?? 'Participant'} · Received{' '}
                    {formatDate(item.createdAt)}
                  </small>
                  {item.media.map((media) => (
                    <button
                      type="button"
                      className="text-link"
                      key={media.id}
                      onClick={async () => {
                        const result = await api<{ url: string }>(
                          `/coordinator/media/${media.id}/original`,
                        );
                        window.open(result.url, '_blank', 'noopener,noreferrer');
                      }}
                    >
                      Review {media.mimeType.startsWith('video') ? 'video' : 'photo'} ·{' '}
                      {media.processingState.toLowerCase()}
                    </button>
                  ))}
                </div>
                <div className="button-row">
                  <button
                    className="button"
                    disabled={
                      mutation.busy || item.media.some((media) => media.processingState !== 'READY')
                    }
                    onClick={() =>
                      void act(`/coordinator/moderation/${item.id}`, { action: 'APPROVED' })
                    }
                  >
                    <Check size={16} /> Approve & publish
                  </button>
                  <button
                    className="button secondary"
                    disabled={mutation.busy}
                    onClick={() =>
                      void act(`/coordinator/moderation/${item.id}`, { action: 'REJECTED' })
                    }
                  >
                    <X size={16} /> Reject
                  </button>
                </div>
              </article>
            ))}
            {approvedFeed.length > 0 && (
              <details className="admin-published">
                <summary>
                  {approvedFeed.length} published update{approvedFeed.length === 1 ? '' : 's'}
                </summary>
                {approvedFeed.map((item) => (
                  <article className="admin-review" key={item.id}>
                    <div className="admin-review-copy">
                      <p>{item.caption}</p>
                      <small>
                        {item.author?.displayName ?? item.participantName ?? 'Volunteer'} ·
                        Published
                      </small>
                    </div>
                    <button
                      className="button secondary"
                      disabled={mutation.busy}
                      onClick={() =>
                        void act(`/coordinator/moderation/${item.id}`, { action: 'HIDDEN' })
                      }
                    >
                      Hide update
                    </button>
                  </article>
                ))}
              </details>
            )}
          </>
        )}
      </section>

      <section className="admin-section" aria-labelledby="help-heading">
        <div className="admin-section-heading">
          <div>
            <h2 id="help-heading">Nearby help requests</h2>
            <p>
              Approval lets a request sync to other people in its area. Requests expire
              automatically.
            </p>
          </div>
          <span className="admin-count">
            {help.data?.filter((item) => item.moderation === 'PENDING').length ?? 0} awaiting review
          </span>
        </div>
        {help.loading ? (
          <Loading />
        ) : help.error ? (
          <ErrorNotice message={help.error} retry={() => void help.refresh()} />
        ) : help.data?.length ? (
          help.data.map((item) => (
            <article className="admin-review" key={item.objectId}>
              <div className="admin-review-copy">
                <h3>
                  {item.help.category.replaceAll('_', ' ')} · {item.help.priority.toLowerCase()}
                </h3>
                <p>{item.help.details}</p>
                <small>
                  {item.authorName} · {item.help.audience.toLowerCase()} · {item.help.quantity}{' '}
                  requested · {item.area ?? 'Area not specified'} · {item.moderation} · Expires{' '}
                  {formatDate(item.expiresAt)}
                </small>
              </div>
              <div className="button-row">
                {item.moderation === 'PENDING' ? (
                  <>
                    <button
                      className="button"
                      disabled={mutation.busy}
                      onClick={() => void act(`/community/moderation/${item.objectId}/approve`)}
                    >
                      <Check size={16} /> Approve
                    </button>
                    <button
                      className="button secondary"
                      disabled={mutation.busy}
                      onClick={() => void act(`/community/moderation/${item.objectId}/reject`)}
                    >
                      <X size={16} /> Reject
                    </button>
                  </>
                ) : (
                  <button
                    className="button secondary"
                    disabled={mutation.busy}
                    onClick={() => void act(`/community/moderation/${item.objectId}/hide`)}
                  >
                    Hide request
                  </button>
                )}
              </div>
            </article>
          ))
        ) : (
          <p className="admin-empty">No nearby help requests are awaiting review.</p>
        )}
      </section>

      <section className="admin-section" aria-labelledby="audit-heading">
        <div className="admin-section-heading">
          <div>
            <h2 id="audit-heading">Recent administrator activity</h2>
            <p>Feature switches and moderation decisions are recorded here.</p>
          </div>
          <Link className="text-link" href="/dashboard/manage">
            Full management <ExternalLink size={15} />
          </Link>
        </div>
        {audits.loading ? (
          <Loading />
        ) : audits.error ? (
          <ErrorNotice message={audits.error} retry={() => void audits.refresh()} />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Action</th>
                  <th>Record</th>
                </tr>
              </thead>
              <tbody>
                {audits.data?.slice(0, 12).map((item) => (
                  <tr key={item.id}>
                    <td>{formatDate(item.createdAt)}</td>
                    <td>{item.event.replaceAll('_', ' ')}</td>
                    <td>
                      {item.entityType} · {item.entityId}
                    </td>
                  </tr>
                ))}
                {!audits.data?.length && (
                  <tr>
                    <td colSpan={3}>No administrator actions recorded yet.</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </section>
      <p className="admin-identity">Administrator: {user.displayName}</p>
    </main>
  );
}
