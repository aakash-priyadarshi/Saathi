import Link from 'next/link';
export default function NotFound() {
  return (
    <div className="page-wrap narrow">
      <h1>We couldn’t find that page.</h1>
      <p>Check the link, or find a verified need from the home page.</p>
      <Link className="button" href="/">
        Browse needs
      </Link>
    </div>
  );
}
