'use client';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  ArrowLeft,
  MapPin,
  Clock,
  ShieldCheck,
  Copy,
  CheckCircle2,
  ArrowRight,
  Flag,
} from 'lucide-react';
import { Verified, Loading, ErrorNotice } from '@saathi/ui';
import { terminalStatuses } from '@saathi/types';
import type { PublicRequest } from '@saathi/types';
import { useResource, useMutation, count, formatDate, deadline } from '../lib/api';
import { useConnection } from './connectivity-provider';
export function StatusNotice({
  request: r,
  verified = r.organization.verified,
  stale = false,
}: {
  request: PublicRequest;
  verified?: boolean;
  stale?: boolean;
}) {
  const closed = terminalStatuses.includes(r.status);
  return (
    <div className={`status-notice ${closed || !verified || stale ? 'closed' : 'open'}`}>
      <ShieldCheck size={24} />
      <div>
        <strong>
          {stale
            ? 'Last known request information'
            : !verified
              ? 'Organization verification unavailable'
              : r.status === 'COMPLETED'
                ? 'This need has been fulfilled'
                : r.status === 'CANCELLED'
                  ? 'This request was cancelled'
                  : r.status === 'EXPIRED'
                    ? 'This request has expired'
                    : r.remainingQuantity === 0
                      ? 'All supplies are committed'
                      : 'Verified request · Accepting help'}
        </strong>
        <p>
          {stale
            ? 'Things may have changed. Reconnect to check the current need before buying or sending supplies.'
            : !verified
              ? 'Do not send supplies until the organization’s verification is restored.'
              : closed
                ? 'Do not send additional supplies. This page stays available so shared links show the current status.'
                : r.remainingQuantity === 0
                  ? 'The team is waiting for deliveries. Please check back before sending more supplies.'
                  : `Posted by ${r.organization.name}. Last updated ${formatDate(r.updatedAt)}.`}
        </p>
      </div>
    </div>
  );
}
export function RequestDetail({ id }: { id: string }) {
  const {
    data: r,
    error,
    loading,
    refresh,
    stale,
    savedAt,
  } = useResource<PublicRequest>(`/public/requests/${encodeURIComponent(id)}`, true);
  const mutation = useMutation(),
    router = useRouter();
  const connection = useConnection();
  const [quantity, setQuantity] = useState(10),
    [email, setEmail] = useState(''),
    [copied, setCopied] = useState(false),
    [reportOpen, setReportOpen] = useState(false),
    [reason, setReason] = useState(''),
    [reported, setReported] = useState(false);
  if (loading)
    return (
      <div className="page-wrap">
        <Loading />
      </div>
    );
  if (error || !r)
    return (
      <div className="page-wrap narrow">
        <ErrorNotice message={error || 'Request unavailable.'} retry={() => void refresh()} />
        <Link href="/">Browse active needs</Link>
      </div>
    );
  const closed = terminalStatuses.includes(r.status),
    progress = Math.min(100, (r.committedQuantity / r.requestedQuantity) * 100);
  async function reserve(e: React.FormEvent) {
    e.preventDefault();
    if (stale || !connection.internet) return;
    const result = await mutation.run<{ trackingToken: string }>(
      '/donations',
      { publicId: id, quantity, email: email || undefined },
      true,
    );
    if (result) router.push(`/contribution/${result.trackingToken}`);
  }
  async function report(e: React.FormEvent) {
    e.preventDefault();
    const result = await mutation.run('/public/reports', {
      entityType: 'ReliefRequest',
      entityId: id,
      reason,
    });
    if (result) {
      setReported(true);
      setReportOpen(false);
    }
  }
  return (
    <div className="page-wrap">
      <Link className="back-link" href="/">
        <ArrowLeft size={15} />
        All needs
      </Link>
      <div className="detail-layout">
        <section>
          <div className="detail-title">
            <h1>{r.title}</h1>
            <span className="request-id">{r.publicId}</span>
            {r.organization.verified && <Verified />}
            <p>
              {r.organization.name} · Posted by {r.creator.displayName}
            </p>
          </div>
          <StatusNotice request={r} stale={stale || !connection.internet} />
          <div className="detail-body">
            <h2>What the team needs</h2>
            <p>{r.description}</p>
            <div className="fulfillment-summary">
              <div>
                <strong>{count(r.requestedQuantity)}</strong>
                <span>{r.unit} requested</span>
              </div>
              <div>
                <strong>{count(r.committedQuantity)}</strong>
                <span>committed</span>
              </div>
              <div>
                <strong>{count(r.receivedQuantity)}</strong>
                <span>received</span>
              </div>
            </div>
            <div
              className="progress"
              role="progressbar"
              aria-label="Supplies committed"
              aria-valuenow={r.committedQuantity}
              aria-valuemin={0}
              aria-valuemax={r.requestedQuantity}
            >
              <span style={{ transform: `scaleX(${progress / 100})` }} />
            </div>
            <h2>
              <MapPin size={21} />
              Where to deliver
            </h2>
            <h3>{r.reliefPoint.name}</h3>
            <p>{r.reliefPoint.publicLocation}</p>
            <p>{r.reliefPoint.instructions}</p>
            <p className="muted">Receiving hours: {r.reliefPoint.operatingHours}</p>
            <div className="deadline-note">
              <Clock size={18} />
              <div>
                <strong>{deadline(r.deadline)}</strong>
                <p>{formatDate(r.deadline)} IST</p>
              </div>
            </div>
          </div>
          <div className="detail-tools">
            <button
              className="text-link"
              onClick={() => {
                void navigator.clipboard.writeText(r.canonicalUrl).then(() => setCopied(true));
              }}
            >
              <Copy size={15} />
              {copied ? 'Link copied' : 'Copy verified link'}
            </button>
            <button className="text-link muted" onClick={() => setReportOpen(!reportOpen)}>
              <Flag size={14} />
              Report a concern
            </button>
          </div>
          {reported && <p role="status">Your concern has been sent to the moderation team.</p>}
          {reportOpen && (
            <form className="stack-form" onSubmit={report}>
              <label>
                Tell the moderation team what seems wrong
                <textarea
                  minLength={10}
                  maxLength={1000}
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  required
                />
              </label>
              <button className="button secondary" disabled={mutation.busy}>
                Send concern
              </button>
            </form>
          )}
        </section>
        <aside className="pledge-panel">
          {stale || !connection.internet ? (
            <>
              <h2>Please check before buying.</h2>
              <p>
                The last time Swarm checked, this location needed {count(r.remainingQuantity)}{' '}
                {r.unit}.{savedAt && ` Checked ${formatDate(savedAt)}.`} Things may have changed.
                Reconnect before reserving or placing an order.
              </p>
              <button className="button secondary" onClick={() => void refresh()}>
                Check with Swarm again
              </button>
            </>
          ) : !r.organization.verified ? (
            <>
              <h2>This organization is unavailable.</h2>
              <p>Please choose a currently verified need before sending supplies.</p>
              <Link className="button" href="/">
                Browse verified needs
              </Link>
            </>
          ) : closed ? (
            <>
              <CheckCircle2 size={38} />
              <h2>
                {r.status === 'COMPLETED' ? 'Thank you, community.' : 'This request is closed.'}
              </h2>
              <p>Please do not send more supplies to this request.</p>
              <Link className="button" href="/">
                Find another need <ArrowRight size={16} />
              </Link>
            </>
          ) : r.remainingQuantity === 0 ? (
            <>
              <h2>Help is on its way.</h2>
              <p>
                All {count(r.requestedQuantity)} {r.unit} are committed. The team will update this
                page as deliveries arrive.
              </p>
              <Link className="button secondary" href="/">
                Browse other needs
              </Link>
            </>
          ) : (
            <>
              <div className="quantity-line">
                <strong>{count(r.remainingQuantity)}</strong>
                <span>{r.unit} still needed</span>
              </div>
              <h2>Your help makes a difference.</h2>
              <p>Reserve what you can provide. You’ll add delivery details next.</p>
              <form className="stack-form" onSubmit={reserve}>
                <fieldset>
                  <legend>How many {r.unit} can you send?</legend>
                  <div className="quantity-options">
                    {[10, 25, 50]
                      .filter((q) => q <= r.remainingQuantity)
                      .map((q) => (
                        <button
                          type="button"
                          key={q}
                          aria-pressed={quantity === q}
                          onClick={() => setQuantity(q)}
                        >
                          {q}
                        </button>
                      ))}
                    <button
                      type="button"
                      aria-pressed={quantity === r.remainingQuantity}
                      onClick={() => setQuantity(r.remainingQuantity)}
                    >
                      All remaining
                    </button>
                  </div>
                </fieldset>
                <label>
                  Quantity
                  <input
                    aria-label="Quantity"
                    type="number"
                    min={1}
                    max={r.remainingQuantity}
                    value={quantity}
                    onChange={(e) => setQuantity(Number(e.target.value))}
                    required
                  />
                </label>
                <label>
                  Email <span className="optional">optional</span>
                  <input
                    type="email"
                    autoComplete="email"
                    placeholder="For updates when your help arrives"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                </label>
                {mutation.error && <ErrorNotice message={mutation.error} />}
                <button
                  className="button full"
                  disabled={mutation.busy || quantity < 1 || quantity > r.remainingQuantity}
                >
                  {mutation.busy ? 'Reserving…' : `Reserve ${count(quantity)} ${r.unit}`}
                  <ArrowRight size={16} />
                </button>
                <p className="form-hint">
                  No account needed. Your private tracking link appears next. Reserve before placing
                  an external order.
                </p>
              </form>
            </>
          )}
        </aside>
      </div>
    </div>
  );
}
