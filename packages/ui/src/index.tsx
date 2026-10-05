import { BadgeCheck, LoaderCircle } from 'lucide-react';
export function Verified({ label = 'Verified organization' }: { label?: string }) {
  return (
    <span className="verified">
      <BadgeCheck size={15} aria-hidden="true" />
      {label}
    </span>
  );
}
export function Loading() {
  return (
    <div className="loading" role="status">
      <LoaderCircle size={22} aria-hidden="true" />
      Loading the latest information…
    </div>
  );
}
export function ErrorNotice({ message, retry }: { message: string; retry?: () => void }) {
  return (
    <div className="error-notice" role="alert">
      <p>{message}</p>
      {retry && (
        <button className="button secondary" onClick={retry}>
          Try again
        </button>
      )}
    </div>
  );
}
