'use client';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import {
  LogOut,
  Plus,
  Radio,
  ArrowRight,
  PackageCheck,
  ShieldCheck,
  Settings,
  RefreshCw,
} from 'lucide-react';
import { Loading, ErrorNotice, Verified } from '@saathi/ui';
import { categories } from '@saathi/types';
import type { CurrentUser, PublicRequest } from '@saathi/types';
import { api, useMutation, useResource, count, formatDate } from '../lib/api';
import { clearPrivate, db, events, setting } from '../lib/offline/store';
type Point = { id: string; name: string; publicLocation: string; organizationId: string };
type Incoming = {
  id: string;
  quantity: number;
  receivedQuantity: number;
  status: string;
  provider: string;
  externalOrderId: string;
  eta: string | null;
  notes: string | null;
  request: { publicId: string; title: string; unit: string; version: number };
};
type DashboardData = {
  requests: PublicRequest[];
  points: Point[];
  deliveries: Incoming[];
  posts: { id: string; caption: string; moderation: string }[];
};
export function LoginPage() {
  const router = useRouter(),
    mutation = useMutation();
  const [email, setEmail] = useState(''),
    [password, setPassword] = useState(''),
    [totp, setTotp] = useState('');
  const { data: config } = useResource<{ demo: boolean }>('/public/config');
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const result = await mutation.run('/auth/login', { email, password, totp: totp || undefined });
    if (result) {
      router.push('/dashboard');
      router.refresh();
    }
  }
  return (
    <div className="page-wrap login-wrap">
      <ShieldCheck size={35} strokeWidth={1.5} />
      <h1>Welcome back.</h1>
      <p>Sign in to coordinate help with your team.</p>
      <form className="stack-form" onSubmit={submit}>
        <label>
          Email
          <input
            type="email"
            autoComplete="username"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        <label>
          Authenticator code <span className="optional">if enabled</span>
          <input
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="[0-9]{6}"
            maxLength={6}
            value={totp}
            onChange={(e) => setTotp(e.target.value)}
          />
        </label>
        {mutation.error && <ErrorNotice message={mutation.error} />}
        <button className="button full" disabled={mutation.busy}>
          {mutation.busy ? 'Signing in…' : 'Sign in'}
          <ArrowRight size={16} />
        </button>
      </form>
      {config?.demo && (
        <div className="demo-login">
          <strong>Local demonstration accounts</strong>
          <p>
            volunteer@saathi.test
            <br />
            coordinator@saathi.test
            <br />
            admin@saathi.test
          </p>
          <p>
            Password: <code>Saathi-demo-2026!</code>
          </p>
        </div>
      )}
      <p className="form-hint">
        Volunteer accounts are invited and approved by an organization coordinator.
      </p>
    </div>
  );
}
export function DashboardFrame({
  children,
  title = 'Volunteer workspace',
}: {
  children: React.ReactNode;
  title?: string;
}) {
  const { data: user, error, loading } = useResource<CurrentUser>('/auth/me'),
    mutation = useMutation(),
    router = useRouter();
  useEffect(() => {
    if (error) router.replace('/login');
  }, [error, router]);
  if (loading || !user)
    return (
      <div className="page-wrap">
        <Loading />
      </div>
    );
  return (
    <div className="page-wrap dashboard-wrap">
      <div className="workspace-header">
        <div>
          <h1>{title}</h1>
          <span className="workspace-label">
            {user.displayName} · {user.role.toLowerCase()}
          </span>
          <Verified label="Connected to server" />
        </div>
        <button
          className="text-link"
          onClick={async () => {
            let hasWork = false,
              device: { id: string } | undefined;
            try {
              hasWork =
                (await (await db()).count('drafts')) > 0 ||
                (await events()).some((e) => !e.receipt) ||
                (await (await db()).count('messages')) > 0 ||
                (await (await db()).count('attachments')) > 0;
              device = await setting<{ id: string }>('signing-device');
            } catch {
              /* Authentication can still be ended when local storage is unavailable. */
            }
            if (
              hasWork &&
              !window.confirm(
                'Signing out clears private saved work on this phone. Export important drafts from Saved work first. Continue signing out?',
              )
            )
              return;
            if (device) await mutation.run(`/sync/devices/${device.id}/revoke`);
            const result = await mutation.run('/auth/logout');
            if (result) {
              try {
                await clearPrivate();
              } catch {
                window.alert(
                  'Signed out. Browser storage could not be cleared; remove Swarm’s site data in browser settings on this shared phone.',
                );
              }
              router.push('/login');
            }
          }}
          disabled={mutation.busy}
        >
          <LogOut size={16} />
          Sign out
        </button>
      </div>
      <nav className="workspace-nav" aria-label="Workspace navigation">
        <Link href="/offline">Saved work</Link>
        <Link href="/dashboard">
          <PackageCheck size={17} />
          Deliveries & requests
        </Link>
        <Link href="/dashboard/new">
          <Plus size={17} />
          Create a need
        </Link>
        <Link href="/dashboard/post">
          <Radio size={17} />
          Publish an update
        </Link>
        {['ADMIN', 'COORDINATOR'].includes(user.role) && (
          <Link href="/dashboard/manage">
            <Settings size={17} />
            Manage team
          </Link>
        )}
      </nav>
      {children}
    </div>
  );
}
function DeliveryRow({
  delivery: c,
  refresh,
}: {
  delivery: Incoming;
  refresh: () => Promise<void>;
}) {
  const mutation = useMutation(),
    [quantity, setQuantity] = useState(c.quantity - c.receivedQuantity);
  async function act(action: string) {
    const result = await mutation.run(
      `/volunteer/deliveries/${c.id}/${action}`,
      action === 'receive' ? { quantity, version: c.request.version } : {},
      true,
    );
    if (result) await refresh();
  }
  return (
    <article className="incoming-row">
      <div>
        <h3>
          {c.quantity - c.receivedQuantity} {c.request.unit} incoming
        </h3>
        <p>
          <Link href={`/r/${c.request.publicId}`}>{c.request.title}</Link>
        </p>
        <p className="muted">
          {c.provider} · Order {c.externalOrderId}
          <br />
          {c.eta ? `ETA ${formatDate(c.eta)} IST` : 'ETA pending'} · {c.status.replaceAll('_', ' ')}
        </p>
        {c.notes && <p>{c.notes}</p>}
      </div>
      <div className="incoming-actions">
        {c.status === 'ORDER_PLACED' ? (
          <button
            className="button secondary"
            disabled={mutation.busy}
            onClick={() => void act('confirm')}
          >
            Order seen
          </button>
        ) : (
          <>
            <label>
              Quantity received
              <input
                type="number"
                min={1}
                max={c.quantity - c.receivedQuantity}
                value={quantity}
                onChange={(e) => setQuantity(Number(e.target.value))}
              />
            </label>
            <button
              className="button"
              disabled={mutation.busy || quantity < 1 || quantity > c.quantity - c.receivedQuantity}
              onClick={() => void act('receive')}
            >
              {mutation.busy ? 'Recording…' : 'Mark received'}
            </button>
            {c.status === 'VOLUNTEER_CONFIRMED' && (
              <button
                className="text-link"
                disabled={mutation.busy}
                onClick={() => void act('transit')}
              >
                Mark in transit
              </button>
            )}
          </>
        )}
        {mutation.error && <ErrorNotice message={mutation.error} />}
      </div>
    </article>
  );
}
export function DashboardPage() {
  const { data, error, loading, refresh } = useResource<DashboardData>(
    '/volunteer/dashboard',
    true,
  );
  return (
    <DashboardFrame>
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNotice message={error} retry={() => void refresh()} />
      ) : (
        data && (
          <>
            <div className="section-title">
              <div>
                <h2>Incoming deliveries</h2>
                <p>Confirm orders and record what actually arrives.</p>
              </div>
              <button
                className="icon-button"
                onClick={() => void refresh()}
                aria-label="Refresh deliveries"
              >
                <RefreshCw size={19} />
              </button>
            </div>
            {data.deliveries.length ? (
              data.deliveries.map((c) => <DeliveryRow key={c.id} delivery={c} refresh={refresh} />)
            ) : (
              <div className="empty-state compact-empty">
                No incoming orders. New delivery commitments will appear here.
              </div>
            )}
            <div className="section-title">
              <div>
                <h2>Your team’s requests</h2>
                <p>Active and archived requests, with their current quantities.</p>
              </div>
              <Link className="button" href="/dashboard/new">
                <Plus size={16} />
                Create need
              </Link>
            </div>
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Request</th>
                    <th>Status</th>
                    <th>Committed</th>
                    <th>Received</th>
                    <th>Manage</th>
                  </tr>
                </thead>
                <tbody>
                  {data.requests.map((r) => (
                    <tr key={r.publicId}>
                      <td>
                        <Link href={`/r/${r.publicId}`}>{r.title}</Link>
                        <small>
                          {r.publicId} · {r.reliefPoint.name}
                        </small>
                      </td>
                      <td>{r.status.replaceAll('_', ' ')}</td>
                      <td>
                        {count(r.committedQuantity)} / {count(r.requestedQuantity)}
                      </td>
                      <td>{count(r.receivedQuantity)}</td>
                      <td>
                        <Link href={`/dashboard/request/${r.publicId}`}>Edit</Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <h2 className="section-spacer">Recent field updates</h2>
            {data.posts.map((p) => (
              <div className="post-review" key={p.id}>
                <p>{p.caption}</p>
                <span className="badge">{p.moderation}</span>
              </div>
            ))}
          </>
        )
      )}
    </DashboardFrame>
  );
}
function RequestFields({ points, prefix = '' }: { points: Point[]; prefix?: string }) {
  return (
    <>
      <div className="form-grid">
        <label>
          Relief point
          <select name={`${prefix}reliefPointId`} required>
            {points.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name} · {p.publicLocation}
              </option>
            ))}
          </select>
        </label>
        <label>
          Category
          <select name={`${prefix}category`}>
            {categories.map((c) => (
              <option key={c} value={c}>
                {c[0] + c.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
        </label>
      </div>
      <label>
        What is needed?
        <input
          name={`${prefix}title`}
          required
          minLength={3}
          maxLength={100}
          placeholder="e.g. Sealed drinking water"
        />
      </label>
      <label>
        Details
        <textarea
          name={`${prefix}description`}
          required
          minLength={5}
          maxLength={2000}
          placeholder="Sizes, packaging and useful delivery details"
        />
      </label>
      <div className="form-grid">
        <label>
          Quantity
          <input name={`${prefix}requestedQuantity`} type="number" required min={1} max={1000000} />
        </label>
        <label>
          Unit
          <input
            name={`${prefix}unit`}
            required
            maxLength={30}
            placeholder="bottles, meals, kits"
          />
        </label>
      </div>
      <div className="form-grid">
        <label>
          Priority
          <select name={`${prefix}priority`}>
            <option value="NORMAL">Normal</option>
            <option value="HIGH">High</option>
            <option value="URGENT">Urgent</option>
          </select>
        </label>
        <label>
          Needed before (your local time)
          <input name={`${prefix}deadline`} type="datetime-local" required />
        </label>
      </div>
    </>
  );
}
function requestFromForm(f: FormData, prefix = '') {
  return {
    reliefPointId: String(f.get(`${prefix}reliefPointId`)),
    category: String(f.get(`${prefix}category`)),
    title: String(f.get(`${prefix}title`)),
    description: String(f.get(`${prefix}description`)),
    requestedQuantity: Number(f.get(`${prefix}requestedQuantity`)),
    unit: String(f.get(`${prefix}unit`)),
    priority: String(f.get(`${prefix}priority`)),
    deadline: new Date(String(f.get(`${prefix}deadline`))).toISOString(),
  };
}
export function NewRequestPage() {
  const { data, error, loading } = useResource<DashboardData>('/volunteer/dashboard'),
    mutation = useMutation(),
    router = useRouter();
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const result = await mutation.run<PublicRequest>(
      '/volunteer/requests',
      requestFromForm(new FormData(e.currentTarget)),
    );
    if (result) router.push(`/r/${result.publicId}`);
  }
  return (
    <DashboardFrame title="Create a verified need">
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNotice message={error} />
      ) : data?.points.length ? (
        <form className="stack-form editor-form" onSubmit={submit}>
          <p>
            This request will be public and linked to your verified organization. Use designated
            receiving locations only.
          </p>
          <RequestFields points={data.points} />
          {mutation.error && <ErrorNotice message={mutation.error} />}
          <button className="button" disabled={mutation.busy}>
            {mutation.busy ? 'Publishing…' : 'Publish verified request'}
            <ArrowRight size={16} />
          </button>
        </form>
      ) : (
        <ErrorNotice message="Your team needs an active relief point before creating a request. Ask your coordinator." />
      )}
    </DashboardFrame>
  );
}
export function PostPage() {
  const { data, error, loading } = useResource<DashboardData>('/volunteer/dashboard'),
    mutation = useMutation(),
    router = useRouter();
  const [files, setFiles] = useState<File[]>([]),
    [linked, setLinked] = useState(false),
    [uploading, setUploading] = useState(false),
    [uploadError, setUploadError] = useState('');
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!data) return;
    const f = new FormData(e.currentTarget);
    const point = data.points.find((p) => p.id === f.get('reliefPointId'));
    if (!point) return;
    setUploading(true);
    setUploadError('');
    try {
      const mediaIds: string[] = [];
      for (const file of files) {
        const form = new FormData();
        form.set('file', file);
        form.set('organizationId', point.organizationId);
        const result = await api<{ id: string }>('/volunteer/media', {
          method: 'POST',
          body: form,
        });
        mediaIds.push(result.id);
      }
      const publishAt = String(f.get('publishAt'));
      const need = linked ? { ...requestFromForm(f, 'need-'), reliefPointId: point.id } : undefined;
      const result = await mutation.run('/volunteer/feed', {
        caption: String(f.get('caption')),
        reliefPointId: point.id,
        mediaIds,
        publishAt: publishAt ? new Date(publishAt).toISOString() : undefined,
        request: need,
      });
      if (result) router.push('/dashboard');
    } catch (e) {
      setUploadError(e instanceof Error ? e.message : 'Upload failed. Try again.');
    } finally {
      setUploading(false);
    }
  }
  return (
    <DashboardFrame title="Publish a field update">
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNotice message={error} />
      ) : (
        data && (
          <form className="stack-form editor-form" onSubmit={submit}>
            <label>
              Relief point
              <select name="reliefPointId" required>
                {data.points.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              What is happening on the ground?
              <textarea
                name="caption"
                minLength={5}
                maxLength={4000}
                required
                rows={5}
                placeholder="A clear, factual update for the community"
              />
            </label>
            <label>
              Photos or videos <span className="optional">optional</span>
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp,video/mp4,video/webm"
                multiple
                onChange={(e) => setFiles(Array.from(e.target.files ?? []).slice(0, 6))}
              />
            </label>
            <p className="form-hint">
              Up to 6 files, 25 MB each. Metadata is removed. Media posts require coordinator
              approval. Check visible faces and personal details before uploading; automatic face
              blur is not available.
            </p>
            <label>
              Publish after <span className="optional">leave blank to publish now</span>
              <input type="datetime-local" name="publishAt" />
            </label>
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={linked}
                onChange={(e) => setLinked(e.target.checked)}
              />
              Create a supply request with this update
            </label>
            {linked && (
              <fieldset>
                <legend>Linked supply need</legend>
                <RequestFields points={data.points} prefix="need-" />
              </fieldset>
            )}
            <p className="privacy-note">
              <ShieldCheck size={18} />
              Only the public relief point is shared. Exact location is off.
            </p>
            {(mutation.error || uploadError) && (
              <ErrorNotice message={mutation.error || uploadError} />
            )}
            <button className="button" disabled={mutation.busy || uploading || !data.points.length}>
              {uploading
                ? 'Uploading and preparing media…'
                : mutation.busy
                  ? 'Publishing…'
                  : files.length
                    ? 'Submit update for approval'
                    : 'Publish update'}
            </button>
          </form>
        )
      )}
    </DashboardFrame>
  );
}
export function EditRequestPage({ id }: { id: string }) {
  const { data: r, error, loading, refresh } = useResource<PublicRequest>(`/public/requests/${id}`),
    mutation = useMutation();
  const [message, setMessage] = useState('');
  async function edit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!r) return;
    const f = new FormData(e.currentTarget);
    const result = await mutation.run(
      `/volunteer/requests/${id}`,
      {
        version: r.version,
        title: String(f.get('title')),
        description: String(f.get('description')),
        requestedQuantity: Number(f.get('quantity')),
        priority: String(f.get('priority')),
      },
      false,
      'PATCH',
    );
    if (result) {
      setMessage('Request updated.');
      await refresh();
    }
  }
  async function cancel() {
    const result = await mutation.run(`/coordinator/requests/${id}/cancel`);
    if (result) {
      setMessage('Request cancelled. Its public page remains available.');
      await refresh();
    }
  }
  return (
    <DashboardFrame title="Manage request">
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorNotice message={error} />
      ) : (
        r && (
          <form key={r.version} className="stack-form editor-form" onSubmit={edit}>
            <p>
              {r.publicId} · {r.status.replaceAll('_', ' ')} · {r.committedQuantity} committed
            </p>
            <label>
              Title
              <input name="title" defaultValue={r.title} required minLength={3} maxLength={100} />
            </label>
            <label>
              Description
              <textarea name="description" defaultValue={r.description} required minLength={5} />
            </label>
            <label>
              Requested quantity
              <input
                name="quantity"
                type="number"
                defaultValue={r.requestedQuantity}
                min={Math.max(1, r.committedQuantity)}
                required
              />
            </label>
            <label>
              Priority
              <select name="priority" defaultValue={r.priority}>
                {['NORMAL', 'HIGH', 'URGENT'].map((p) => (
                  <option key={p}>{p}</option>
                ))}
              </select>
            </label>
            {mutation.error && <ErrorNotice message={mutation.error} />}
            <button className="button" disabled={mutation.busy}>
              Save changes
            </button>
            <button
              type="button"
              className="button danger"
              disabled={mutation.busy}
              onClick={() => void cancel()}
            >
              Cancel request (coordinator only)
            </button>
            {message && <p role="status">{message}</p>}
            <Link href={`/r/${id}`}>View public request</Link>
          </form>
        )
      )}
    </DashboardFrame>
  );
}
