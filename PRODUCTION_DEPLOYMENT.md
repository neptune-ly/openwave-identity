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

## Deploying the service (GitHub Actions)

`.github/workflows/deploy-service.yml` — **Deploy Service**, manual
(`workflow_dispatch`) with a ref to deploy.

Note the other workflow, *Deploy Pages*, publishes DOCUMENTATION only. It
touches no running service. Before this file existed, shipping the registry
meant SSH-ing in by hand with no record of who deployed what.

What it does: SSH to the app VPS, check out the requested ref in
`/opt/openwave/openwave-identity`, `docker compose up -d --build identity` from
the Astro repo's `deploy/hetzner`, wait for the container healthcheck, and then
probe alias resolution over the public internet.

**A deploy is not green because the container started.** On 2026-08-01 the alias
surface returned HTTP 500 for every input for a full day while
`/actuator/health` reported UP throughout — the health probe only asks whether
the process is running, and a staff tester was the detection mechanism. So the
final step calls `/identity/resolve` as an anonymous caller: **200 or 404
passes** (the registry is reachable, authenticating and routing), **any 5xx
fails the run**, because that is the exact shape the outage took.

Required repository secrets:

| Secret | Meaning |
|---|---|
| `IDENTITY_DEPLOY_HOST` | App VPS hostname or IP |
| `IDENTITY_DEPLOY_USER` | SSH user that owns `/opt/openwave` and is in the `docker` group |
| `IDENTITY_DEPLOY_SSH_KEY` | Private key for that user. Deploy-only, no passphrase |
| `IDENTITY_DEPLOY_KNOWN_HOSTS` | Host key line. Optional but strongly preferred — without it the run falls back to trust-on-first-use and cannot detect a substituted host |
| `IDENTITY_PUBLIC_BASE_URL` | Public origin **plus `/v1`**, e.g. `https://identity.example.com/v1` |

The workflow holds no database credentials; those stay in the VPS `.env` as
before. Give the deploy key its own account rather than reusing an operator's.

## Operational rule

Register banks and portal users through controlled operator onboarding. Do not seed shared bank keys, sample portal users, or localhost callback URLs into production data.
