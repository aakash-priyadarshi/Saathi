'use client';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useEffect, useState } from 'react';
import {
  Network,
  Sun,
  Moon,
  ArrowUpRight,
  Radio,
  HandHeart,
  CheckCheck,
  ScanLine,
} from 'lucide-react';
import { useResource } from '../lib/api';
import { ConnectionBar } from './connectivity-provider';
const links = [
  { href: '/live', label: 'Live', icon: Radio },
  { href: '/', label: 'Needs', icon: HandHeart },
  { href: '/completed', label: 'Completed', icon: CheckCheck },
  { href: '/verify', label: 'Verify', icon: ScanLine },
];
export function Shell({ children }: { children: React.ReactNode }) {
  const path = usePathname(),
    [dark, setDark] = useState(false);
  const { data } = useResource<{ demo: boolean; platformName: string }>('/public/config');
  useEffect(() => {
    const enabled = localStorage.getItem('saathi-theme') === 'dark';
    setDark(enabled);
    document.documentElement.dataset.theme = enabled ? 'dark' : 'light';
  }, []);
  function toggle() {
    const next = !dark;
    setDark(next);
    localStorage.setItem('saathi-theme', next ? 'dark' : 'light');
    document.documentElement.dataset.theme = next ? 'dark' : 'light';
  }
  const name = data?.platformName ?? process.env.NEXT_PUBLIC_PLATFORM_NAME ?? 'CJP Swarm';
  const display = name === 'CJP Swarm' ? 'SWARM' : name;
  if (path === '/native-turnstile') return <main>{children}</main>;
  return (
    <>
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      {data?.demo && (
        <div className="demo-banner">
          Demonstration environment · Sample requests and organizations. No real deliveries.
        </div>
      )}
      <header className="site-header">
        <div className="header-inner">
          <Link className="brand" href="/" aria-label={`${name} home`}>
            <Network size={30} strokeWidth={1.8} />
            <span>
              {display}
              <small>{name === 'CJP Swarm' ? 'by CJP' : 'Here for each other'}</small>
            </span>
          </Link>
          <nav aria-label="Main navigation" className="desktop-nav">
            {links.map((l) => (
              <Link key={l.href} href={l.href} aria-current={path === l.href ? 'page' : undefined}>
                {l.label === 'Live' && <span className="live-dot" />}
                {l.label}
              </Link>
            ))}
          </nav>
          <div className="header-actions">
            <button
              onClick={toggle}
              className="icon-button"
              aria-label={dark ? 'Use light theme' : 'Use dark theme'}
            >
              {dark ? <Sun size={19} /> : <Moon size={19} />}
            </button>
            <Link href="/dashboard" className="volunteer-link">
              Volunteer portal <ArrowUpRight size={15} />
            </Link>
          </div>
        </div>
      </header>
      <ConnectionBar />
      <main id="main">{children}</main>
      <footer className="site-footer">
        <div>
          <Link className="footer-brand" href="/">
            {name}
          </Link>
          <p>Connect nearby. Coordinate together.</p>
          {name === 'CJP Swarm' && <p>Developed by Cockroach Janta Party</p>}
        </div>
        <p>
          Always check a request’s current status before sending supplies.
          <br />
          <Link href="/verify">Verify a shared request</Link>
          <span> · </span>
          <Link href="/about">How Swarm works</Link>
          <span> · </span>
          <Link href="/offline">Saved work</Link>
          <span> · </span>
          <Link href="/nearby">Nearby</Link>
          <span> · </span>
          <Link href="/download">Android app</Link>
        </p>
      </footer>
      <nav className="mobile-nav" aria-label="Mobile navigation">
        {links.map((l) => (
          <Link key={l.href} href={l.href} aria-current={path === l.href ? 'page' : undefined}>
            <l.icon size={21} />
            {l.label}
          </Link>
        ))}
      </nav>
    </>
  );
}
