'use client';
import { useState } from 'react';
import Link from 'next/link';
import { ErrorNotice } from '@saathi/ui';
import { api, guestToken, uploadMedia, MAX_MEDIA_BYTES, useResource } from '../lib/api';
/** Guest posts are labelled unverified and remain private until administrator approval. */
export function SharePage() {
  const config = useResource<{ features?: { live?: boolean } }>('/public/config', true);
  const [files, setFiles] = useState<File[]>([]),
    [busy, setBusy] = useState(false),
    [progress, setProgress] = useState(0),
    [submitted, setSubmitted] = useState(false),
    [error, setError] = useState('');
  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    if (!files.length) return setError('Choose at least one photo or video.');
    setBusy(true);
    setProgress(0);
    setError('');
    try {
      const token = guestToken(),
        mediaIds: string[] = [];
      for (const [index, file] of files.entries()) {
        const result = await uploadMedia(file, { guestToken: token }, (part) =>
          setProgress((index + part) / files.length),
        );
        mediaIds.push(result.id);
      }
      await api('/guest/posts', {
        method: 'POST',
        headers: { 'X-Upload-Token': token },
        body: JSON.stringify({
          caption: String(f.get('caption')).trim(),
          area: String(f.get('area')).trim(),
          contentWarning: f.get('contentWarning') === 'on',
          mediaIds,
        }),
      });
      setSubmitted(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sharing failed. Try again.');
    } finally {
      setBusy(false);
    }
  }
  if (submitted)
    return (
      <div className="page-wrap narrow">
        <div className="page-heading">
          <h1>Sent for review.</h1>
          <p>
            Your unverified post is saved. An administrator must approve it before it appears on the
            public feed.
          </p>
          <Link className="button secondary" href="/live">
            Browse published updates
          </Link>
        </div>
      </div>
    );
  if (config.data?.features?.live !== true)
    return (
      <div className="page-wrap narrow">
        <div className="empty-state" role="status">
          <h1>Field updates are paused.</h1>
          <p>An administrator will make submissions available when this feature is enabled.</p>
        </div>
      </div>
    );
  return (
    <div className="page-wrap narrow">
      <div className="page-heading">
        <h1>Share a photo or video</h1>
        <p>
          No account needed. Your unverified post appears publicly after an administrator approves
          it.
        </p>
      </div>
      <form className="stack-form editor-form" onSubmit={submit}>
        <label>
          What is happening?
          <textarea name="caption" required maxLength={2000} rows={4} />
        </label>
        <label>
          Public area or landmark
          <input
            name="area"
            required
            maxLength={80}
            placeholder="e.g. Near the central bus stand"
          />
        </label>
        <label>
          Photos or videos
          <input
            type="file"
            accept="image/jpeg,image/png,image/webp,video/mp4,video/webm"
            multiple
            required
            onChange={(e) => {
              const chosen = Array.from(e.target.files ?? []).slice(0, 6);
              const large = chosen.find((file) => file.size > MAX_MEDIA_BYTES);
              setError(large ? `${large.name} is larger than 250 MB.` : '');
              setFiles(large ? [] : chosen);
            }}
          />
        </label>
        <p className="form-hint">
          Up to 6 files, 250 MB each, any video length. Location and device metadata are removed. Do
          not include phone numbers, emails or exact coordinates. Check faces before sharing;
          automatic face blur is not available.
        </p>
        <label className="checkbox-label">
          <input type="checkbox" name="contentWarning" />
          Content warning for graphic imagery
        </label>
        {error && <ErrorNotice message={error} />}
        <button className="button" disabled={busy}>
          {busy ? `Uploading… ${Math.round(progress * 100)}%` : 'Send for review'}
        </button>
      </form>
    </div>
  );
}
