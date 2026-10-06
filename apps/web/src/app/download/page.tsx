import Link from 'next/link';
import { Download, Smartphone } from 'lucide-react';
export const metadata = { title: 'Swarm for Android' };
export default function Page() {
  const configured = process.env.ANDROID_QA_APK_URL;
  const url = configured && /^https:\/\//.test(configured) ? configured : undefined;
  return (
    <div className="page-wrap prose-page">
      <h1>Take Swarm with you</h1>
      <p className="lead">
        Relief needs, protected drafts and nearby conversations in a native Android app.
      </p>
      <section>
        <h2>
          <Smartphone size={24} aria-hidden="true" /> Android testing build
        </h2>
        <p>
          For Android 8.0 or newer. The QA app uses separate test data and shows its environment on
          every screen. It needs a connection to prepare volunteer publishing; saved work can then
          travel nearby.
        </p>
        {url ? (
          <a className="button" href={url}>
            <Download size={18} />
            Download Swarm QA APK
          </a>
        ) : (
          <p>
            The hosted QA download is being prepared.{' '}
            <a href="https://github.com/aakash-priyadarshi/Saathi/actions/workflows/android-product.yml">
              View Android build availability
            </a>
            .
          </p>
        )}
        <p>
          Install only a build from the Swarm repository or your verified team. Check its published
          checksum and environment before signing in. Production distribution is awaiting release
          verification.
        </p>
      </section>
      <section>
        <h2>What nearby can do</h2>
        <p>
          Messages and signed updates work over supported local connections. Calls require local
          Wi-Fi pairing. The devices must stay within connection range; a nearby acknowledgement
          does not prove publication.
        </p>
        <Link href="/connectivity" className="text-link">
          See what works right now
        </Link>
      </section>
    </div>
  );
}
