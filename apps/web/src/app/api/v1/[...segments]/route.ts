import { proxyApiRequest } from '../../../../lib/server-api-proxy';

type RouteContext = { params: Promise<{ segments: string[] }> };

async function forward(request: Request, context: RouteContext) {
  const { segments } = await context.params;
  return proxyApiRequest(request, segments);
}

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';
export const GET = forward;
export const HEAD = forward;
export const POST = forward;
export const PUT = forward;
export const PATCH = forward;
export const DELETE = forward;
export const OPTIONS = forward;
