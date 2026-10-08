# AWS Lightsail staging

This stack is for QA only. It runs the Next.js web app, NestJS API, PostgreSQL,
Redis and Caddy on one Lightsail instance. It uses persistent local media,
log-only email and disabled malware scanning; do not put real relief or
production data here. Production still requires private S3 storage, real email,
malware scanning, backups, monitoring and a security review.

The web and API names are `swarm.cockroachjantaparty.org` and
`api.swarm.cockroachjantaparty.org`. Create DNS-only A records for both names,
pointing to the instance's Lightsail static IPv4 address. Caddy provisions and
renews HTTPS certificates after both names resolve publicly. Do not expose the
database or Redis ports.

The 4 GB Lightsail plan in Mumbai is currently listed at USD $24/month and
includes 80 GB SSD plus 2 TB/month transfer allowance in that region. This is
in addition to the existing AWS account resources and any domain, snapshot,
egress or tax charges. See [Lightsail bundle pricing](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-bundles.html).

## Deploy

1. Create a Lightsail Ubuntu 24.04 instance with the `medium_3_1` bundle in
   `ap-south-1`, allocate and attach a static IPv4 address, and allow inbound
   SSH only from the operator's current IP plus TCP 80/443 from the internet.
2. Install Docker Engine and the Docker Compose plugin on the instance.
3. Create `.env` from `.env.example`, set a fresh PostgreSQL password, the
   image tag published by `publish-container.yml`, and the two web/API hostnames.
4. Copy the locally generated `runtime.secrets.env` to this directory on the
   instance. It contains staging receipt/configuration-signing values and must
   remain private. Never put it, `.env`, a private key, or the repository's
   GitHub token in the public repository.
5. Wait until both DNS A records resolve to the static address. Then run:

   ```sh
   docker compose pull
   docker compose up -d --wait postgres redis
   docker compose --profile tools run --rm migrate
   docker compose --profile tools run --rm seed
   docker compose up -d --wait api web caddy
   docker compose ps
   ```

6. Verify `/health` and `/ready` on the API hostname and the web root over
   HTTPS. Test signed service discovery from the staging Android build before
   changing or removing any existing bootstrap endpoint.

`runtime.secrets.env` must contain `SYNC_SIGNING_PRIVATE_JWK`,
`SYNC_RECEIPT_KEYRING_JSON`, `SERVICE_CONFIG_JSON`, and
`SERVICE_CONFIG_ROOT_PUBLIC_JWK`. Generate its values with
`scripts/service-config.mjs`; keep the configuration-root private key offline.
The API container alone receives this file. For web sign-in, add the existing
Cloudflare Turnstile secret as `TURNSTILE_SECRET` in this file and set
`TURNSTILE_HOSTNAMES=swarm.cockroachjantaparty.org`. The site key is public and
is embedded in the web image; never put the secret in the web image, browser
code, or repository. Web sign-in fails closed until the secret is configured.

After the image containing the admin bootstrap command is deployed, create the
first real administrator with a randomly generated initial password:

```sh
sudo docker compose run --rm --no-deps --entrypoint node api \
  packages/database/dist/create-admin.js \
  --email admin@example.org --name "Site Administrator"
```

The command is restricted to non-demo staging/production databases, refuses to
overwrite an existing account or add a second real administrator, records an
audit event, and prints the password once. If staging contains only the seeded
`admin@saathi.test` fixture, the transaction replaces that fixture with the
requested administrator and revokes its old sessions. Save the password in a
password manager.

The image is published to `ghcr.io/aakash-priyadarshi/saathi` by the public
repository's GitHub Actions workflow. The package must be marked public once in
GitHub Packages so the Lightsail host can pull it without a long-lived token.

## Android QA build

Build the installable staging app on Windows with Android Studio installed:

```powershell
./scripts/android-build.ps1 -BuildType staging -TrustDirectory .data/aws-staging
```

This builds the complete QA app as `org.saathi.android.qa` with chat enabled and
Android debugging disabled. The APK is written to
`apps/android/app/build/outputs/apk/staging/app-staging.apk`. A Play Store
release still needs its production signing key and production service bootstrap;
the staging APK is for direct QA installs only.

## QA data and service updates

The staging API runs with `DEMO_MODE=false`, so public pages do not advertise
seeded relief examples. The optional seed job still provisions the synthetic
`@saathi.test` QA accounts, but `STAGING_SEED_RELIEF_DATA=false` prevents it
from recreating sample requests, updates, commitments or receiving points.
To withdraw the six existing marked relief fixtures after updating the stack,
run this guarded command from `/opt/saathi-staging`:

```sh
sudo docker compose exec -T api env SAATHI_CLEAR_DEMO_RELIEF=true pnpm --filter @saathi/database clear-staging-demo-relief
```

It is restricted to `APP_ENV=staging`, verifies the known seeded request IDs,
accounts, demo receiving points and audit markers, and refuses to alter linked
updates with unexpected authors or media. Database safeguards require relief
requests to be archived and audit records to remain append-only, so this task
redacts and cancels the requests, hides and redacts their field updates, removes
only the exact local-shop fixture commitments and deliveries, and deactivates
and redacts receiving points with no non-fixture references. QA accounts,
organizations and audit history remain. The operation is idempotent. An empty
AWS Postgres volume does not need replacement; this cleanup targets only the
sample relief content.
