import Link from 'next/link';
export default function Page() {
  return (
    <div className="page-wrap narrow prose">
      <h1>Here for each other.</h1>
      <p>
        Swarm connects community support with verified humanitarian needs. Anyone can browse needs
        or offer supplies without creating an account.
      </p>
      <h2>Find a need. Give what you can.</h2>
      <p>
        Each request comes from an approved volunteer at a verified organization. Choose a quantity,
        reserve it, and record your delivery provider, order reference, and expected arrival. Your
        reservation is temporary until you record the order.
      </p>
      <h2>Know where your help goes.</h2>
      <p>
        Deliver to the designated public relief point using the instructions on the request page. A
        volunteer records the supplies received. Your private contribution link shows delivery
        progress; an optional email keeps you updated.
      </p>
      <h2>Check before you share.</h2>
      <p>
        A request’s permanent page is the source of truth. Completed, cancelled, and expired
        requests remain accessible, even when old screenshots circulate. Always{' '}
        <Link href="/verify">verify the current status</Link> before sending supplies.
      </p>
      <h2>Privacy comes first.</h2>
      <p>
        Public pages do not show donor emails, private volunteer contact details, or home addresses.
        Uploaded media has metadata removed before publication; photos and videos publish without an
        approval step. Exact coordinates are not public by default.
      </p>
      <h2>For volunteer teams</h2>
      <p>
        Verified organizations approve volunteers. The{' '}
        <Link href="/dashboard">volunteer portal</Link> supports requests, incoming deliveries, and
        field updates. Nearby relay and the offline mobile app are future phases; this website
        requires an internet connection.
      </p>
    </div>
  );
}
