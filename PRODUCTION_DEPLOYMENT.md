# OpenWave Identity Production Deployment Notes

OpenWave Identity is the NPT handle registry. It must be operated as shared financial infrastructure, not as a demo service.

## Recommended low-cost starting layout

- 1 API VM, 2 vCPU / 4 GB RAM minimum.
- Managed PostgreSQL or a dedicated encrypted database VM.
- Reverse proxy with TLS, HSTS, and request-size limits.
- Strict firewall rules: public access only to API/UI ports and health checks; database private only.
- Daily encrypted database backups with restore testing.

Hetzner Cloud is the cheapest practical VPS option for the first production launch. DigitalOcean is a good fallback when operational simplicity matters more than minimum cost. Avoid serverless-only hosting for the registry because the service needs stable database connectivity, predictable audit logging, and explicit operational control.

For the first combined Astro + OpenWave Identity Hetzner launch, use the Docker Compose deployment in the Astro repository at `deploy/hetzner`.

## Mandatory environment variables

```bash
SERVER_PORT=8095
DB_URL=jdbc:postgresql://...
DB_USER=...
DB_PASSWORD=...
REGISTRY_ADMIN_KEY=...
```

For the embedded UI, configure the registry endpoint at build/runtime:

```bash
VITE_OPENWAVE_REGISTRY_URL=https://identity.example.com/v1
```

## Removed launch blockers

- The portal login no longer exposes or accepts a user-entered registry URL.
- The registry admin key has no production fallback.
- Fresh migrations do not seed sample bank API keys or localhost bank callback URLs.
- Existing legacy sample bank credentials are disabled by migration if present.

## Before deploying a handle-lifecycle migration

1. Take an encrypted PostgreSQL backup and verify that it can be restored.
2. Inspect production `flyway_schema_history`. V18 must be absent. If any V18
   attempt failed or partially applied, audit and repair that state deliberately
   before starting the service. Never deploy this corrected V18 over a database
   that successfully recorded the older, incompatible V18 checksum.
3. Run the complete Flyway chain against a disposable PostgreSQL database or
   staging clone using `PostgresMigrationTest`; the database name must contain
   `test` or `ci`.
4. Build the portal source with `npm ci`, `npm run check`, and `npm run build` in
   `openwave-ui`. The static adapter writes the embedded bundle to
   `src/main/resources/static`; deploy only source and bundle from the same ref.
5. Confirm the target ref contains the service rule that checks both live and
   retired handles. Never deploy a rename UI without the retirement enforcement.

V18 is additive: it creates the permanent reservation table and adds rename
accounting columns. Do not delete retirement rows as rollback cleanup. They are
payment-address reservations and therefore audit/safety data, not a cache.

## Deploying the service (self-hosted GitHub Actions)

`.github/workflows/deploy-service.yml` — **Deploy Service**, manual
(`workflow_dispatch`) with a ref to deploy.

The workflow has a GitHub-hosted guard job, followed by a job on the production
box labelled `self-hosted`, `openwave`, and `astro-app`. Enable it deliberately
with the repository variable `IDENTITY_PROD_ENABLED=true`. The self-hosted runner
uses the workflow-scoped `github.token`; no long-lived SSH deploy key is stored.
Identity and Astro currently have separate repo-scoped runner registrations on
the same host, so the shared host lock—not runner identity—prevents overlap.

Note the other workflow, *Deploy Pages*, publishes documentation only and touches
no running service.

What it does: on the host, resolve the requested remote ref in
`/opt/openwave/openwave-identity`, refuse an unreconciled or dirty checkout,
`docker compose up -d --build identity` from the Astro repo's `deploy/hetzner`,
wait for the container healthcheck, and probe alias resolution over the public
internet. After the backend probe passes, it copies the reviewed static bundle
to the exact host-mounted `/opt/openwave/ui/identity` target, retains the prior
bundle beside it, and requires the public portal to return HTTP 200. Identity and
Astro use a shared host-level `flock`; GitHub concurrency groups are repository-
scoped and are not the cross-repository lock.

**A deploy is not green because the container started.** On 2026-08-01 the alias
surface returned HTTP 500 for every input for a full day while
`/actuator/health` reported UP throughout — the health probe only asks whether
the process is running, and a staff tester was the detection mechanism. So the
final step calls `/identity/resolve` as an anonymous caller: **200 or 404
passes** (the registry is reachable, authenticating and routing), **any 5xx
fails the run**, because that is the exact shape the outage took.

Required repository variables:

| Variable | Meaning |
|---|---|
| `IDENTITY_PROD_ENABLED` | Must be exactly `true` after the self-hosted runner and checkout are ready |
| `IDENTITY_PUBLIC_BASE_URL` | Public origin **plus `/v1`**, e.g. `https://identity.example.com/v1`; defaults to the production Identity URL |
| `IDENTITY_PUBLIC_UI_URL` | Public portal route used after the host static sync; defaults to `https://identity.neptune.ly/portal/identity` |

Deploys accept no human safety attestation. Run **Production Preflight** first
for the exact immutable SHA. On the production runner it creates an encrypted
streaming PostgreSQL backup, restores it into an isolated disposable PostgreSQL
container, checks the target checkout/Caddy/compose environment, and signs
short-lived SHA-bound evidence. The deploy verifies that signature and repeats
the V18 live-state assertion while holding the shared persistent compose lock.
The first V18 release requires no V18 history row and no `retired_handles`
object; later releases require the signed receipt written only after the first
successful public deploy.

The encrypted backups, signed evidence, and V18 receipt live under the
runner-owned mode-700 namespace
`/opt/openwave/neptune-astro/deploy/hetzner/.env.openwave-release`. The existing
`.env.*` ignore rule keeps this operational state out of the live Git checkout;
do not move it to a new root-owned `/opt/openwave` directory or grant the runner
broader sudo access just to create release evidence. The shared lock remains at
`/opt/openwave/neptune-astro/deploy/hetzner/.openwave-prod-deploy.lock` so old
and new release scripts serialize against the same inode.

Database proof containers share the live `identity` service's network namespace
so production-only Docker/DNS names resolve exactly as they do for the app. They
must never fall back to host networking or reinterpret a service name as
loopback.

Deploy an immutable reviewed SHA only after those checks:

```bash
gh workflow run production-preflight.yml \
  --repo neptune-ly/openwave-identity \
  --ref <release-branch> \
  -f ref=<immutable-40-character-release-sha>

gh workflow run deploy-service.yml \
  --repo neptune-ly/openwave-identity \
  --ref <release-branch> \
  -f ref=<the-same-immutable-40-character-release-sha> \
  -f probe_handle=deploy-probe-does-not-exist
```

Configure these repository secrets in both `openwave-identity` and
`neptune-astro`: `OPENWAVE_PROD_BACKUP_ENCRYPTION_KEY` and
`OPENWAVE_PROD_PREFLIGHT_ATTESTATION_KEY`. They must be distinct, one-line
32+-character values and must not equal any app/database credential. The backup
key is used only by preflight; the attestation key is also used by deploy to
verify evidence and write a V18 success receipt. Database credentials remain in
the mode-600 VPS `.env`. The host checkout must already be reconciled as a real
Git checkout; the deploy script refuses to convert the legacy rsync tree.

## Post-deploy lifecycle checks

- The automated probe uses a deliberately unknown alias. `200` or `404` proves
  the public resolution surface is answering; any 5xx or unexpected status fails.
- Verify authenticated availability against a known free test candidate and
  confirm it returns a typed status. A network failure is not `AVAILABLE`.
- On staging, verify a retired fixture returns `410 HANDLE_RETIRED` and does not
  reveal or redirect to a successor. Do not create a throwaway production rename
  merely as a smoke test: every successful rename reserves a string forever.
- Verify a bank approval that has expired returns 410, a repeated action returns
  409, invalid filters return 400, an unrelated bank sees 404, and a bad customer
  reference returns 403.
- After a real rename, verify the old customer portal session is rejected and
  OAuth introspection reports old-subject tokens inactive. The rename transaction
  revokes those tokens and grants; the customer signs in with the new handle and
  re-authorizes delegated apps.

## Rollback boundary

Before the first successful production rename, the application can be rolled back
while leaving V18's empty additive structures in place. After any handle has been
retired, **do not roll back to an application version that ignores
`retired_handles`**: it could re-issue a former payment address. Disable claim and
rename traffic and deploy a forward fix instead. Never drop or truncate the table.

## Operational rule

Register banks and portal users through controlled operator onboarding. Do not seed shared bank keys, sample portal users, or localhost callback URLs into production data.
