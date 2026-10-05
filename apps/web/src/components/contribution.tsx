'use client';
import { useState } from 'react';
import Link from 'next/link';
import { LockKeyhole, Package, CheckCircle2, Clock, ArrowRight, Copy } from 'lucide-react';
import { ErrorNotice, Loading } from '@saathi/ui';
import type { PublicRequest } from '@saathi/types';
import { useResource, useMutation, count, formatDate } from '../lib/api';
import { useConnection } from './connectivity-provider';
type Tracking = {
  id: string;
  quantity: number;
  receivedQuantity: number;
  status: string;
  expiresAt: string;
  provider: string | null;
  externalOrderId: string | null;
  eta: string | null;
  request: PublicRequest;
  deliveries: { quantity: number; receivedAt: string }[];
};
export function ContributionPage({ token }: { token: string }) {
  const {
      data: c,
      error,
      loading,
      refresh,
    } = useResource<Tracking>(`/donations/tracking/${encodeURIComponent(token)}`, true),
    mutation = useMutation();
  const connection = useConnection();
  const [provider, setProvider] = useState('Self delivery'),
    [order, setOrder] = useState(''),
    [eta, setEta] = useState(''),
    [notes, setNotes] = useState(''),
    [copied, setCopied] = useState(false);
  if (loading)
    return (
      <div className="page-wrap narrow">
        <Loading />
      </div>
    );
  if (error || !c)
    return (
      <div className="page-wrap narrow">
        <ErrorNotice message={error || 'Contribution unavailable.'} retry={() => void refresh()} />
      </div>
    );
  async function record(e: React.FormEvent) {
    e.preventDefault();
    if (!connection.internet) return;
    const result = await mutation.run(
      `/donations/tracking/${token}/order`,
      {
        provider,
        externalOrderId: order,
        eta: new Date(eta).toISOString(),
        notes: notes || undefined,
      },
      true,
    );
    if (result) void refresh();
  }
  async function cancel() {
    if (!connection.internet) return;
    const result = await mutation.run(`/donations/tracking/${token}/cancel`, {}, true);
    if (result) void refresh();
  }
  const delivered = c.status === 'DELIVERED',
    expired = ['EXPIRED', 'CANCELLED'].includes(c.status);
  return (
    <div className="page-wrap narrow">
      <div className="page-heading">
        <h1>{delivered ? 'Your help arrived.' : 'You’re showing up.'}</h1>
        <p>
          Keep this link to track your contribution. Anyone with this private link can access and
          manage this contribution.
        </p>
      </div>
      <div className="private-label">
        <LockKeyhole size={15} />
        Your private contribution link
      </div>
      {!connection.internet && (
        <p className="status-notice closed">
          Your connection to Saathi is unavailable. Reconnect and check this reservation before
          purchasing supplies or recording an order.
        </p>
      )}
      <section className="contribution-summary">
        <div className="contribution-icon">
          {delivered ? <CheckCircle2 size={35} /> : <Package size={35} />}
        </div>
        <div>
          <h2>
            {count(c.quantity)} {c.request.unit}
          </h2>
          <p>
            {c.request.title} · {c.request.reliefPoint.name}
          </p>
          <span className={`badge ${delivered ? 'completed' : 'high'}`}>
            {c.status.replaceAll('_', ' ')}
          </span>
        </div>
      </section>
      <button
        className="text-link"
        onClick={() =>
          void navigator.clipboard.writeText(location.href).then(() => setCopied(true))
        }
      >
        <Copy size={15} />
        {copied ? 'Link copied' : 'Save your tracking link'}
      </button>
      {delivered ? (
        <div className="status-notice closed">
          <CheckCircle2 size={25} />
          <div>
            <strong>Received by the volunteer team</strong>
            <p>
              Thank you for helping your community today. {c.receivedQuantity} {c.request.unit} have
              been received.
            </p>
          </div>
        </div>
      ) : expired ? (
        <div className="status-notice closed">
          <Clock size={24} />
          <div>
            <strong>
              {c.status === 'EXPIRED'
                ? 'Your reservation has expired'
                : 'Your reservation was cancelled'}
            </strong>
            <p>
              Check the current need before placing an order.{' '}
              <Link href={`/r/${c.request.publicId}`}>View this request</Link>.
            </p>
          </div>
        </div>
      ) : c.status === 'RESERVED' ? (
        <>
          <div className="status-notice open">
            <Clock size={24} />
            <div>
              <strong>Reserved until {formatDate(c.expiresAt)} IST</strong>
              <p>
                Add your order reference before this time. Unconfirmed reservations become available
                to other donors.
              </p>
            </div>
          </div>
          <h2>Arrange your delivery</h2>
          <p>
            Use these public receiving details in your delivery app. Saathi does not place or pay
            for external orders.
          </p>
          <div className="delivery-instructions">
            <strong>{c.request.reliefPoint.name}</strong>
            <p>{c.request.reliefPoint.publicLocation}</p>
            <p>{c.request.reliefPoint.instructions}</p>
            <p>Receiving hours: {c.request.reliefPoint.operatingHours}</p>
          </div>
          <form className="stack-form" onSubmit={record}>
            <label>
              Delivery method
              <select value={provider} onChange={(e) => setProvider(e.target.value)}>
                {['Self delivery', 'Local shop', 'Zomato', 'Swiggy', 'Courier', 'Other'].map(
                  (p) => (
                    <option key={p}>{p}</option>
                  ),
                )}
              </select>
            </label>
            <div className="form-grid">
              <label>
                {provider === 'Self delivery'
                  ? 'Delivery reference (e.g. your name)'
                  : 'External order ID'}
                <input
                  value={order}
                  onChange={(e) => setOrder(e.target.value)}
                  required
                  maxLength={100}
                />
              </label>
              <label>
                Expected arrival (your local time)
                <input
                  type="datetime-local"
                  value={eta}
                  onChange={(e) => setEta(e.target.value)}
                  required
                />
              </label>
            </div>
            <label>
              Notes <span className="optional">optional</span>
              <textarea value={notes} onChange={(e) => setNotes(e.target.value)} maxLength={1000} />
            </label>
            {mutation.error && <ErrorNotice message={mutation.error} />}
            <button className="button" disabled={mutation.busy || !connection.internet}>
              {mutation.busy ? 'Saving…' : 'Record delivery details'}
              <ArrowRight size={16} />
            </button>
            <button
              className="text-link muted"
              type="button"
              disabled={mutation.busy}
              onClick={() => void cancel()}
            >
              Release my reservation
            </button>
          </form>
        </>
      ) : (
        <section className="tracking-order">
          <h2>Help is on its way</h2>
          <p>The team has your delivery details and will record supplies when they arrive.</p>
          <dl>
            <div>
              <dt>Provider</dt>
              <dd>{c.provider}</dd>
            </div>
            <div>
              <dt>Order reference</dt>
              <dd>{c.externalOrderId}</dd>
            </div>
            <div>
              <dt>Expected arrival</dt>
              <dd>{c.eta ? `${formatDate(c.eta)} IST` : 'Awaiting details'}</dd>
            </div>
            <div>
              <dt>Received</dt>
              <dd>
                {c.receivedQuantity} of {c.quantity} {c.request.unit}
              </dd>
            </div>
          </dl>
        </section>
      )}
      {c.deliveries.length > 0 && (
        <section className="delivery-history">
          <h2>Delivery confirmations</h2>
          {c.deliveries.map((d, i) => (
            <p key={i}>
              <CheckCircle2 size={16} />
              {d.quantity} {c.request.unit} received · {formatDate(d.receivedAt)} IST
            </p>
          ))}
        </section>
      )}
      <Link className="back-link" href={`/r/${c.request.publicId}`}>
        View the request’s current status <ArrowRight size={15} />
      </Link>
    </div>
  );
}
