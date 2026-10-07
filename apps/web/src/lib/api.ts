'use client';
import { useCallback, useEffect, useState, useRef } from 'react';
import { snapshot, saveSnapshot } from './offline/store';
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}
export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (typeof options.body === 'string' && !headers.has('Content-Type'))
    headers.set('Content-Type', 'application/json');
  if (options.method && options.method !== 'GET') {
    const csrf = document.cookie
      .split('; ')
      .find((c) => c.startsWith('saathi_csrf='))
      ?.split('=')[1];
    if (csrf) headers.set('X-CSRF-Token', decodeURIComponent(csrf));
  }
  const began = performance.now();
  let res: Response;
  try {
    res = await fetch(`/api/v1${path}`, {
      ...options,
      headers,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: options.signal ?? AbortSignal.timeout(12000),
    });
  } catch (error) {
    window.dispatchEvent(
      new CustomEvent('saathi-api-result', {
        detail: { ok: false, elapsed: performance.now() - began },
      }),
    );
    throw error;
  }
  window.dispatchEvent(
    new CustomEvent('saathi-api-result', {
      detail: { ok: res.status < 500, elapsed: performance.now() - began },
    }),
  );
  const body = await res
    .json()
    .catch(() => ({ message: 'The service is unavailable. Please try again.' }));
  if (!res.ok) throw new ApiError(body.message ?? 'Unable to complete this action.', res.status);
  return body as T;
}
/** Matches the server: any single photo or video may be up to 250 MB. */
export const MAX_MEDIA_BYTES = 250 * 1024 * 1024;
/** A random secret that proves a guest owns the files they upload. */
export function guestToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}
/** Uploads in parts with retries, so a 250 MB video survives slow or flaky connections. */
export async function uploadMedia(
  file: File,
  owner: { organizationId: string } | { guestToken: string },
  onProgress?: (fraction: number) => void,
) {
  if (file.size > MAX_MEDIA_BYTES) throw new Error(`${file.name} is larger than 250 MB.`);
  const headers: Record<string, string> =
    'guestToken' in owner ? { 'X-Upload-Token': owner.guestToken } : {};
  const minutes = (n: number) => AbortSignal.timeout(n * 60000);
  const start = await api<{ uploadId: string; partSize: number; parts: number }>('/uploads', {
    method: 'POST',
    headers,
    body: JSON.stringify({
      size: file.size,
      ...('organizationId' in owner ? { organizationId: owner.organizationId } : {}),
    }),
  });
  for (let index = 0; index < start.parts; index++) {
    const body = file.slice(index * start.partSize, (index + 1) * start.partSize);
    for (let attempt = 0; ; attempt++) {
      try {
        await api(`/uploads/${start.uploadId}/parts/${index}`, {
          method: 'PUT',
          headers: { ...headers, 'Content-Type': 'application/octet-stream' },
          body,
          signal: minutes(10),
        });
        break;
      } catch (error) {
        const permanent = error instanceof ApiError && error.status < 500 && error.status !== 429;
        if (permanent || attempt >= 4) throw error;
        await new Promise((resolve) => setTimeout(resolve, 2000 * 2 ** attempt));
      }
    }
    onProgress?.((index + 1) / start.parts);
  }
  return api<{ id: string; processingState: string }>(`/uploads/${start.uploadId}/complete`, {
    method: 'POST',
    headers,
    signal: minutes(10),
  });
}
export function write<T>(path: string, body: unknown = {}, key?: string, method = 'POST') {
  return api<T>(path, {
    method,
    body: JSON.stringify(body),
    headers: key ? { 'Idempotency-Key': key } : {},
  });
}
export function useMutation() {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState('');
  const saved = useRef<{ fingerprint: string; key: string } | null>(null);
  async function run<T>(
    path: string,
    body: unknown = {},
    idempotent = false,
    method = 'POST',
  ): Promise<T | undefined> {
    const fingerprint = path + JSON.stringify(body);
    if (!saved.current || saved.current.fingerprint !== fingerprint)
      saved.current = { fingerprint, key: crypto.randomUUID() };
    setBusy(true);
    setError('');
    try {
      const result = await write<T>(path, body, idempotent ? saved.current.key : undefined, method);
      saved.current = null;
      return result;
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Action failed. Try again.');
    } finally {
      setBusy(false);
    }
  }
  return { busy, error, run };
}
export function useResource<T>(path: string, realtime = false) {
  const [data, setData] = useState<T | null>(null),
    [error, setError] = useState(''),
    [loading, setLoading] = useState(true),
    [stale, setStale] = useState(false),
    [savedAt, setSavedAt] = useState<string | undefined>();
  const saveable =
    /^\/public\/(config|requests(?:\/[A-Z0-9-]+)?|feed|verify\/[A-Z0-9-]+)(?:\?.*)?$/.test(path);
  const refresh = useCallback(async () => {
    try {
      const result = await api<T>(path);
      setData(result);
      setStale(false);
      setSavedAt(new Date().toISOString());
      if (saveable)
        await saveSnapshot(path, result).catch(() =>
          window.dispatchEvent(new CustomEvent('saathi-storage-error')),
        );
      setError('');
    } catch (e) {
      const saved = saveable ? await snapshot<T>(path).catch(() => undefined) : undefined;
      if (saved) {
        setData(saved.data);
        setStale(true);
        setSavedAt(saved.savedAt);
        setError('');
      } else setError(e instanceof Error ? e.message : 'Unable to connect.');
    } finally {
      setLoading(false);
    }
  }, [path, saveable]);
  useEffect(() => {
    setLoading(true);
    void refresh();
  }, [refresh]);
  useEffect(() => {
    if (!realtime) return;
    const stream = new EventSource('/api/v1/public/events');
    stream.addEventListener('change', () => void refresh());
    const timer = setInterval(() => void refresh(), 30000);
    return () => {
      stream.close();
      clearInterval(timer);
    };
  }, [refresh, realtime]);
  return { data, error, loading, refresh, stale, savedAt };
}
export const formatTime = (value: string) =>
  new Intl.DateTimeFormat('en-IN', {
    hour: 'numeric',
    minute: '2-digit',
    timeZone: 'Asia/Kolkata',
  }).format(new Date(value));
export const formatDate = (value: string) =>
  new Intl.DateTimeFormat('en-IN', {
    day: 'numeric',
    month: 'short',
    hour: 'numeric',
    minute: '2-digit',
    timeZone: 'Asia/Kolkata',
  }).format(new Date(value));
export const count = (value: number) => new Intl.NumberFormat('en-IN').format(value);
export function deadline(value: string) {
  const minutes = Math.ceil((new Date(value).getTime() - Date.now()) / 60000);
  if (minutes <= 0) return 'Deadline passed';
  if (minutes < 60) return `Needed in ${minutes} min`;
  if (minutes < 1440) return `Needed in ${Math.ceil(minutes / 60)} hours`;
  return `Needed by ${formatDate(value)}`;
}
