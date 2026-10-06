'use client';
import { useState } from 'react';
import { ErrorNotice, Loading } from '@saathi/ui';
import type { CurrentUser } from '@saathi/types';
import { api, useMutation, useResource, formatDate } from '../lib/api';
import { DashboardFrame } from './dashboard';
type Volunteer = {
  id: string;
  organizationId: string;
  approved: boolean;
  organization: { name: string };
  user: { id: string; displayName: string; active: boolean };
};
type Organization = { id: string; name: string; active: boolean; verified: boolean };
type Review = {
  id: string;
  caption: string;
  moderation: string;
  author: { displayName: string } | null;
  participantName?: string | null;
  media: { id: string; mimeType: string; processingState: string }[];
};
type Audit = { id: string; event: string; entityType: string; entityId: string; createdAt: string };
type Report = { id: string; reason: string; entityType: string; entityId: string; status: string };
export function ManagementPage() {
  const { data: user } = useResource<CurrentUser>('/auth/me');
  return (
    <DashboardFrame title="Coordinate your team">
      {user && <ManagementContent user={user} />}
    </DashboardFrame>
  );
}
function ManagementContent({ user }: { user: CurrentUser }) {
  const volunteers = useResource<Volunteer[]>('/coordinator/volunteers'),
    reviews = useResource<Review[]>('/coordinator/moderation'),
    orgs = useResource<Organization[]>(user.role === 'ADMIN' ? '/admin/organizations' : '/auth/me'),
    mutation = useMutation();
  const [message, setMessage] = useState('');
  const organizations =
    user.role === 'ADMIN' && Array.isArray(orgs.data)
      ? orgs.data
      : user.memberships.map((m) => ({
          id: m.organizationId,
          name: m.organization.name,
          active: true,
          verified: true,
        }));
  async function act(path: string, body: unknown = {}) {
    const r = await mutation.run(path, body);
    if (r) {
      setMessage('Changes saved.');
      void volunteers.refresh();
      void reviews.refresh();
      void orgs.refresh();
    }
  }
  async function submit(e: React.FormEvent<HTMLFormElement>, path: string) {
    e.preventDefault();
    const form = e.currentTarget,
      f = new FormData(form),
      body: Record<string, unknown> = {};
    f.forEach((v, k) => {
      body[k] = v;
    });
    const r = await mutation.run(path, body);
    if (r) {
      form.reset();
      setMessage('Saved successfully.');
      void orgs.refresh();
      void volunteers.refresh();
    }
  }
  return (
    <>
      {mutation.error && <ErrorNotice message={mutation.error} />}{' '}
      {message && (
        <p className="success-message" role="status">
          {message}
        </p>
      )}
      <h2>Volunteer approvals</h2>
      {volunteers.loading ? (
        <Loading />
      ) : volunteers.error ? (
        <ErrorNotice message={volunteers.error} />
      ) : (
        volunteers.data?.map((v) => (
          <div className="management-row" key={v.id}>
            <div>
              <strong>{v.user.displayName}</strong>
              <p>
                {v.organization.name} · {v.approved ? 'Approved' : 'Awaiting approval'}
              </p>
            </div>
            <button
              className={`button ${v.approved ? 'secondary' : ''}`}
              disabled={mutation.busy}
              onClick={() =>
                void act(`/coordinator/volunteers/${v.id}/${v.approved ? 'suspend' : 'approve'}`)
              }
            >
              {v.approved ? 'Suspend' : 'Approve volunteer'}
            </button>
          </div>
        ))
      )}
      <h2 className="section-spacer">Field reporting review</h2>
      {reviews.error ? (
        <ErrorNotice message={reviews.error} />
      ) : reviews.data?.length ? (
        reviews.data.map((p) => (
          <div className="moderation-item" key={p.id}>
            <p>{p.caption}</p>
            <small>
              {p.author?.displayName ?? p.participantName ?? 'Participant'} ·{' '}
              {p.participantName ? 'Participant report · ' : ''}
              {p.moderation}
            </small>
            {p.media.map((m) => (
              <button
                className="text-link"
                key={m.id}
                onClick={async () => {
                  const r = await api<{ url: string }>(`/coordinator/media/${m.id}/original`);
                  window.open(r.url, '_blank', 'noopener,noreferrer');
                }}
              >
                Review {m.mimeType.startsWith('video') ? 'video' : 'photo'} ({m.processingState})
              </button>
            ))}
            <div className="button-row">
              {p.moderation === 'PENDING' && (
                <button
                  className="button"
                  disabled={mutation.busy}
                  onClick={() =>
                    void act(`/coordinator/moderation/${p.id}`, { action: 'APPROVED' })
                  }
                >
                  Approve & publish
                </button>
              )}
              <button
                className="button secondary"
                disabled={mutation.busy}
                onClick={() =>
                  void act(`/coordinator/moderation/${p.id}`, {
                    action: p.moderation === 'PENDING' ? 'REJECTED' : 'HIDDEN',
                  })
                }
              >
                {p.moderation === 'PENDING' ? 'Reject' : 'Hide update'}
              </button>
            </div>
          </div>
        ))
      ) : (
        <p className="muted">No posts awaiting review.</p>
      )}
      <div className="management-forms">
        {user.role === 'ADMIN' && (
          <section>
            <h2>Create organization</h2>
            <form className="stack-form" onSubmit={(e) => void submit(e, '/admin/organizations')}>
              <label>
                Verified organization name
                <input name="name" minLength={3} maxLength={100} required />
              </label>
              <button className="button" disabled={mutation.busy}>
                Create organization
              </button>
            </form>
            {organizations.map((o) => (
              <div className="management-row" key={o.id}>
                <span>{o.name}</span>
                <button
                  className="text-link"
                  disabled={mutation.busy}
                  onClick={() =>
                    void act(`/admin/organizations/${o.id}/status`, { active: !o.active })
                  }
                >
                  {o.active ? 'Deactivate' : 'Activate'}
                </button>
              </div>
            ))}
          </section>
        )}
        <section>
          <h2>Invite a team member</h2>
          <form className="stack-form" onSubmit={(e) => void submit(e, '/coordinator/invite')}>
            <label>
              Organization
              <select name="organizationId" required>
                {organizations.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Display name
              <input name="displayName" required minLength={2} maxLength={60} />
            </label>
            <label>
              Email
              <input type="email" name="email" required />
            </label>
            <label>
              Initial password
              <input
                type="password"
                name="password"
                minLength={12}
                required
                autoComplete="new-password"
              />
            </label>
            <label>
              Role
              <select name="role">
                <option value="VOLUNTEER">Volunteer (approval required)</option>
                {user.role === 'ADMIN' && <option value="COORDINATOR">Coordinator</option>}
              </select>
            </label>
            <p className="form-hint">
              Verify the invited person’s email and identity before approving their account.
            </p>
            <button className="button" disabled={mutation.busy}>
              Invite member
            </button>
          </form>
        </section>
        <section>
          <h2>Designate a relief point</h2>
          <form className="stack-form" onSubmit={(e) => void submit(e, '/coordinator/points')}>
            <label>
              Organization
              <select name="organizationId">
                {organizations.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </select>
            </label>
            {[
              ['name', 'Public name'],
              ['description', 'Description'],
              ['publicLocation', 'Approximate public location'],
              ['instructions', 'Delivery instructions'],
              ['operatingHours', 'Receiving hours'],
            ].map(([key, label]) => (
              <label key={key}>
                {label}
                <input
                  name={key}
                  required
                  minLength={3}
                  maxLength={key === 'description' || key === 'instructions' ? 1000 : 100}
                />
              </label>
            ))}
            <p className="form-hint">
              Use public receiving locations. Never enter a volunteer’s home address.
            </p>
            <button className="button" disabled={mutation.busy}>
              Create relief point
            </button>
          </form>
        </section>
      </div>
      {user.role === 'ADMIN' && <AdminReview />}
    </>
  );
}
function AdminReview() {
  const audits = useResource<Audit[]>('/admin/audits'),
    reports = useResource<Report[]>('/admin/reports'),
    mutation = useMutation();
  return (
    <>
      <h2 className="section-spacer">Abuse reports</h2>
      {reports.data?.map((r) => (
        <div className="management-row" key={r.id}>
          <div>
            <strong>{r.reason}</strong>
            <p>
              {r.entityType} · {r.entityId} · {r.status}
            </p>
          </div>
          {r.status === 'OPEN' && (
            <button
              className="button secondary"
              disabled={mutation.busy}
              onClick={async () => {
                const result = await mutation.run(`/admin/reports/${r.id}/resolve`);
                if (result) void reports.refresh();
              }}
            >
              Resolve
            </button>
          )}
        </div>
      ))}
      <h2 className="section-spacer">Audit history</h2>
      <p className="muted">Append-only history of important actions. Most recent 200 entries.</p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Time (IST)</th>
              <th>Action</th>
              <th>Entity</th>
            </tr>
          </thead>
          <tbody>
            {audits.data?.map((a) => (
              <tr key={a.id}>
                <td>{formatDate(a.createdAt)}</td>
                <td>{a.event.replaceAll('_', ' ')}</td>
                <td>{a.entityType}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
