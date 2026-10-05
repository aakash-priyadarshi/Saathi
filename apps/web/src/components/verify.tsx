'use client';
import { useState } from 'react';
import Link from 'next/link';
import { ScanLine, ShieldCheck, ArrowRight } from 'lucide-react';
import type { PublicRequest } from '@saathi/types';
import { ErrorNotice, Verified } from '@saathi/ui';
import { api, count, formatDate } from '../lib/api';
import { StatusNotice } from './request-detail';
import { useConnection } from './connectivity-provider';
export function VerifyPage() {
  const connection = useConnection();
  const [id, setId] = useState(''),
    [result, setResult] = useState<(PublicRequest & { verified: boolean }) | null>(null),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  async function verify(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    setResult(null);
    try {
      const raw = id.trim().split('/').pop() ?? '';
      setResult(await api(`/public/verify/${encodeURIComponent(raw.toUpperCase())}`));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not verify this request.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="page-wrap narrow verify-page">
      <ScanLine size={38} strokeWidth={1.5} />
      <h1>
        A shared request.
        <br />
        <em>A clear answer.</em>
      </h1>
      <p>
        Before sending supplies, check that the request is verified and still needs help. Old
        screenshots can keep circulating after a need is met.
      </p>
      <form className="verify-form" onSubmit={verify}>
        <label htmlFor="request-id">Request ID or Saathi link</label>
        <div>
          <input
            id="request-id"
            value={id}
            onChange={(e) => setId(e.target.value)}
            placeholder="e.g. SAA-7F3K92"
            autoCapitalize="characters"
            required
            maxLength={300}
          />
          <button className="button" disabled={busy}>
            {busy ? 'Checking…' : 'Check request'}
            <ArrowRight size={17} />
          </button>
        </div>
      </form>
      {error && <ErrorNotice message={error} />}{' '}
      {result && (
        <section className="verification-result" aria-live="polite">
          <div className="verified-result-title">
            <ShieldCheck size={26} />
            <div>
              <strong>
                {!connection.internet
                  ? 'Last known verification'
                  : result.verified
                    ? 'Verified request'
                    : 'Organization verification unavailable'}
              </strong>
              <span>{result.publicId}</span>
            </div>
          </div>
          <h2>{result.title}</h2>
          {result.verified && <Verified />}
          <p>
            {result.organization.name} · {result.reliefPoint.name}
          </p>
          <StatusNotice request={result} verified={result.verified} stale={!connection.internet} />
          <div className="fulfillment-summary">
            <div>
              <strong>{count(result.remainingQuantity)}</strong>
              <span>{result.unit} remaining</span>
            </div>
            <div>
              <strong>{result.status.replaceAll('_', ' ')}</strong>
              <span>{connection.internet ? 'status when checked' : 'last known status'}</span>
            </div>
          </div>
          <p className="muted">Last updated {formatDate(result.updatedAt)} IST</p>
          <Link className="button" href={`/r/${result.publicId}`}>
            Open the canonical request <ArrowRight size={16} />
          </Link>
        </section>
      )}
    </div>
  );
}
