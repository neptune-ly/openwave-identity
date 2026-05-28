# OpenWave Identity Production Deployment Notes

OpenWave Identity is the NPT handle registry. It must be operated as shared financial infrastructure, not as a demo service.

## Recommended low-cost starting layout

- 1 API VM, 2 vCPU / 4 GB RAM minimum.
- Managed PostgreSQL or a dedicated encrypted database VM.
- Reverse proxy with TLS, HSTS, and request-size limits.
- Strict firewall rules: public access only to API/UI ports and health checks; database private only.
- Daily encrypted database backups with restore testing.

Hetzner Cloud is the cheapest practical VPS option for the first production launch. DigitalOcean is a good fallback when operational simplicity matters more than minimum cost. Avoid serverless-only hosting for the registry because the service needs stable database connectivity, predictable audit logging, and explicit operational control.

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

## Operational rule

Register banks and portal users through controlled operator onboarding. Do not seed shared bank keys, sample portal users, or localhost callback URLs into production data.
