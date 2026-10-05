import type { Metadata } from 'next';
import { ContributionPage } from '../../../components/contribution';
export const metadata: Metadata = {
  title: 'Your private contribution',
  robots: { index: false, follow: false },
};
export default async function Page({ params }: { params: Promise<{ token: string }> }) {
  return <ContributionPage token={(await params).token} />;
}
