'use client';
import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { ErrorNotice } from '@saathi/ui';
import { api, guestToken, uploadMedia, MAX_MEDIA_BYTES } from '../lib/api';
/** Anyone can share photos and videos; posts are labelled unverified and publish immediately. */
export function SharePage() {
  const router = useRouter();
  const [files, setFiles] = useState<File[]>([]),
    [busy, setBusy] = useState(false),
    [progress, setProgress] = useState(0),
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
      router.push('/live');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sharing failed. Try again.');
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="page-wrap narrow">
      <div className="page-heading">
        <h1>Share a photo or video</h1>
        <p>No account needed. Your post appears on the live feed as an unverified report.</p>
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
          {busy ? `Uploading… ${Math.round(progress * 100)}%` : 'Share now'}
        </button>
      </form>
    </div>
  );
}
