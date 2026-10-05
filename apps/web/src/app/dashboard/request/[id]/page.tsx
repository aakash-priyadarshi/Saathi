import { EditRequestPage } from '../../../../components/dashboard';
export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  return <EditRequestPage id={(await params).id} />;
}
