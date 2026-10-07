// iPhone Universal Links for https://swarm.cockroachjantaparty.org/join#… (see apps/ios/App/Release.entitlements).
// TODO(Apple Team ID): set this to the paid team's 10-character Team ID, then deploy the web app.
// Until then this route answers 404 rather than naming the wrong team.
const APPLE_TEAM_ID: string = '';
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
