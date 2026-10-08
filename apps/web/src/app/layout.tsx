import type { Metadata } from 'next';
import '@fontsource-variable/manrope';
import '@fontsource-variable/lora';
import 'leaflet/dist/leaflet.css';
import './globals.css';
import { Shell } from '../components/shell';
import { ConnectionProvider } from '../components/connectivity-provider';
const name = process.env.NEXT_PUBLIC_PLATFORM_NAME ?? 'CJP Swarm';
export const metadata: Metadata = {
  title: { default: `${name} — Here for each other`, template: `%s · ${name}` },
  description:
    'Verified humanitarian needs, community contributions, and live updates from volunteer teams.',
  referrer: 'no-referrer',
  manifest: '/manifest.webmanifest',
  appleWebApp: { capable: true, title: name, statusBarStyle: 'default' },
};
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <ConnectionProvider>
          <Shell>{children}</Shell>
        </ConnectionProvider>
      </body>
    </html>
  );
}
