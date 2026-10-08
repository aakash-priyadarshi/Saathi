'use client';
import { useState } from 'react';
import { ErrorNotice, Loading } from '@saathi/ui';
import type { CurrentUser } from '@saathi/types';
import { api, useMutation, useResource, formatDate } from '../lib/api';
import { DashboardFrame } from './dashboard';
import { IndiaAddressFields } from './india-address-fields';
import { LocationPicker } from './location-map';
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
  const [pointFormKey, setPointFormKey] = useState(0);
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
  async function submitPoint(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    const values = new FormData(form);
    const body: Record<string, string | number | boolean | undefined> = {};
    values.forEach((value, key) => {
      body[key] = String(value);
    });
    for (const key of ['latitude', 'longitude']) {
      const value = String(values.get(key) ?? '').trim();
      body[key] = value ? Number(value) : undefined;
    }
    body.landmark = String(values.get('landmark') ?? '').trim() || undefined;
    body.exactLocationApproved = values.get('exactLocationApproved') === 'on';
    const result = await mutation.run('/coordinator/points', body);
    if (result) {
      form.reset();
      setPointFormKey((key) => key + 1);
      setMessage('Relief point saved. Its formatted address will appear on linked requests.');
      void orgs.refresh();
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
                Review{' '}
                {m.mimeType.startsWith('audio')
                  ? 'audio'
                  : m.mimeType.startsWith('video')
                    ? 'video'
                    : 'photo'}{' '}
                ({m.processingState})
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
        <section className="relief-point-section">
          <h2>Designate a relief point</h2>
          <p className="form-hint">
            Enter the full public receiving address. The PIN lookup fills in the area details; the
            map lets you set the exact handoff point.
          </p>
          <form className="stack-form" onSubmit={(e) => void submitPoint(e)}>
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
            <div className="address-fields">
              <label>
                Public name
                <input name="name" required minLength={3} maxLength={100} />
              </label>
              <label>
                Description
                <input name="description" required minLength={3} maxLength={1000} />
              </label>
              <h3 className="address-group-title">Street and locality</h3>
              <label>
                Building and street address
                <input
                  name="addressLine1"
                  autoComplete="street-address"
                  placeholder="Building, gate, street or road"
                  required
                  minLength={3}
                  maxLength={120}
                />
              </label>
              <label>
                Area / locality
                <input
                  name="locality"
                  autoComplete="address-level3"
                  required
                  minLength={2}
                  maxLength={80}
                />
              </label>
              <label>
                Landmark <span className="optional">Optional</span>
                <input name="landmark" maxLength={100} placeholder="Nearby public landmark" />
              </label>
              <h3 className="address-group-title">PIN code and area</h3>
              <IndiaAddressFields key={pointFormKey} />
              <h3 className="address-group-title">Delivery arrangements</h3>
              <label>
                Delivery instructions
                <textarea name="instructions" required minLength={3} maxLength={1000} />
              </label>
              <label>
                Receiving hours
                <input
                  name="operatingHours"
                  placeholder="e.g. Daily, 9 am–6 pm IST"
                  required
                  minLength={3}
                  maxLength={100}
                />
              </label>
            </div>
            <MapCoordinateFields key={`map-${pointFormKey}`} />
            <p className="form-hint">
              This address is shown on public requests so people can arrange a delivery. Use a
              public receiving point, never a volunteer’s home address.
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

function MapCoordinateFields() {
  const [latitude, setLatitude] = useState('');
  const [longitude, setLongitude] = useState('');
  const [publishExact, setPublishExact] = useState(false);
  const [locationError, setLocationError] = useState('');
  const [focusVersion, setFocusVersion] = useState(0);
  const lat = Number(latitude);
  const lon = Number(longitude);
  const coordinatesReady =
    latitude.trim() !== '' &&
    longitude.trim() !== '' &&
    Number.isFinite(lat) &&
    Number.isFinite(lon) &&
    lat >= -90 &&
    lat <= 90 &&
    lon >= -180 &&
    lon <= 180;

  function useCurrentLocation() {
    setLocationError('');
    if (!navigator.geolocation) {
      setLocationError(
        'This browser cannot read the device location. Tap the map to place the pin.',
      );
      return;
    }
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => {
        setLatitude(coords.latitude.toFixed(6));
        setLongitude(coords.longitude.toFixed(6));
        setFocusVersion((version) => version + 1);
      },
      () =>
        setLocationError(
          'Location unavailable or permission denied. Tap the map to place the pin instead.',
        ),
      { enableHighAccuracy: true, timeout: 12000, maximumAge: 30000 },
    );
  }

  return (
    <fieldset className="coordinate-fields">
      <legend>
        Exact handoff point <span className="optional">Optional</span>
      </legend>
      <p className="form-hint">
        A PIN code covers an area. Place the pin on the public handoff desk: tap the map or drag the
        marker to the right spot. Zoom with the map controls or pinch, and use GPS if you are at the
        location.
      </p>
      <input type="hidden" name="latitude" value={latitude} />
      <input type="hidden" name="longitude" value={longitude} />
      <button className="button secondary" type="button" onClick={useCurrentLocation}>
        Use my current location
      </button>
      <LocationPicker
        latitude={coordinatesReady ? lat : null}
        longitude={coordinatesReady ? lon : null}
        focusVersion={focusVersion}
        onSelect={(selectedLatitude, selectedLongitude) => {
          setLatitude(selectedLatitude.toFixed(6));
          setLongitude(selectedLongitude.toFixed(6));
          setLocationError('');
        }}
      />
      {coordinatesReady ? (
        <p className="selected-location" role="status">
          Pin selected · {lat.toFixed(6)}, {lon.toFixed(6)}
          <button
            className="text-link"
            type="button"
            onClick={() => {
              setLatitude('');
              setLongitude('');
              setPublishExact(false);
            }}
          >
            Remove pin
          </button>
        </p>
      ) : (
        <p className="form-hint">
          No exact pin selected. The public page will show the address only.
        </p>
      )}
      {locationError && (
        <p className="form-error" role="alert">
          {locationError}
        </p>
      )}
      <label className="checkbox-label">
        <input
          type="checkbox"
          name="exactLocationApproved"
          checked={publishExact}
          disabled={!coordinatesReady}
          onChange={(event) => setPublishExact(event.target.checked)}
        />
        <span>Show this exact pin on public request pages</span>
      </label>
      <p className="form-hint">
        Only check this for a public handoff point. People viewing its requests will see the exact
        coordinates and map.
      </p>
    </fieldset>
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
