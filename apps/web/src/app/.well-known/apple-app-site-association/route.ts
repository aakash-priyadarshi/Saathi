// iPhone Universal Links for https://swarm.cockroachjantaparty.org/join#… (see apps/ios/App/Release.entitlements).
// The paid team (Sahil Patel's membership); the route answers 404 if this is ever cleared rather than naming a wrong team.
const APPLE_TEAM_ID: string = 'K95FX728A7';
export function GET() {
  if (!/^[A-Z0-9]{10}$/.test(APPLE_TEAM_ID)) return new Response('Not found', { status: 404 });
  return Response.json({
    applinks: {
      details: [
        {
          appIDs: [`${APPLE_TEAM_ID}.org.cockroachjantaparty.swarm`],
          components: [{ '/': '/join*' }],
        },
      ],
    },
  });
}
