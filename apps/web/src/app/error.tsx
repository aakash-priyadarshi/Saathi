'use client';
import { ErrorNotice } from '@saathi/ui';
export default function Error({ reset }: { reset: () => void }) {
  return (
    <div className="page-wrap narrow">
      <h1>We couldn’t load this page.</h1>
      <ErrorNotice
        message="Try again in a moment. Your confirmed contributions remain recorded."
        retry={reset}
      />
    </div>
  );
}
