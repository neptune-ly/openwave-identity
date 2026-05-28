<div align="center">

<img src="./docs/neptune-logo.png" alt="Neptune. Financial Technology And Solutions" width="520">

# OpenWave Identity Registry

### Global National Payment Tag (NPT) Identity & Alias Resolution

**Open Source · Bank-Vouched · Governance-First**

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)
[![Spec](https://img.shields.io/badge/OpenWave%20Identity-v1.0-brightgreen)](https://github.com/neptune-ly/openwave-spec)
[![Built with](https://img.shields.io/badge/Kotlin-Spring%20Boot%203-orange)](https://spring.io/projects/spring-boot)

*Operated by [Neptune Fintech](https://www.neptune.ly) — future stewardship: Central Bank of Libya*

**Docs:** https://neptune-ly.github.io/openwave-identity/

</div>

---

## What is this?

The **OpenWave Identity Registry** is the open-source implementation of the
[OpenWave Identity API v1.0](https://github.com/neptune-ly/openwave-spec).

It is the global service that maps **NPT (National Payment Tag) handles** to bank accounts,
enabling universal payment routing across the Libyan banking network.

A person owns a username. They can link accounts from multiple banks to it:

| You type | Money goes to |
|:---|:---|
| `mtellesy` | The owner's **default** bank account |
| `mtellesy@andalus` | Specifically their Andalus account |
| `mtellesy@nub` | Specifically their NUB account |

No IBAN required at the point of payment. One identity, any bank.

## Developer mental model

OpenWave Identity is not a wallet and not a payment gateway. It is the routing authority for NPT aliases:

| Actor | What they can do | Boundary |
|:---|:---|:---|
| Customer | Own the global username and choose the default account | No bank should rename a global handle on the customer's behalf |
| Bank | Claim/link/unlink accounts it has KYC-vouched for | Bank-scoped to its own accounts only |
| Gateway | Resolve aliases before routing payments or Open Banking handoff | Read-only public resolution |
| Registry admin | Register banks, rotate credentials, audit registry operations | Governance and operational control |

For a full implementation, read the OpenWave spec pages:

- [NPT guide](https://neptune-ly.github.io/openwave-spec/guide/npt.html)
- [Identity API reference](https://neptune-ly.github.io/openwave-spec/api/overview.html)
- [Presented payments](https://neptune-ly.github.io/openwave-spec/guide/presented-payments.html)
- [Gateway Interconnect](https://neptune-ly.github.io/openwave-spec/guide/gateway-interconnect.html)

The bundled UI now includes a public registry/developer landing page plus a credential-based admin portal. API keys remain integration credentials; human portal access uses usernames, passwords, roles, and bank scoping.

---

## How it works

**The registry stores routing only** — no KYC data, no balances, no transaction history.
Just: `username → { bank, iban, is_default }`.

**Banks vouch for users.** A person claims their handle through their bank, which has
already KYC-verified them. The bank calls the registry API with a signed assertion.

**Resolution is public.** Any gateway or app can call `GET /v1/identity/resolve?alias=mtellesy`
with no authentication. It returns the IBAN and bank handle. This endpoint is designed to be
fast and cached (60-second TTL).

---

## Role in Gateway Interconnect

OpenWave Identity remains the source of truth for **who owns an NPT handle**. In a multi-gateway ecosystem, the registry is used before OW-GIP routing:

1. Gateway A receives a payment to `mtellesy`.
2. Gateway A resolves the handle through OpenWave Identity.
3. The response identifies the bank handle and account routing metadata.
4. If the owning bank is served by another gateway, Gateway A uses the
   [OpenWave Gateway Interconnect Protocol](https://github.com/neptune-ly/openwave-spec/blob/main/openwave-gateway-interconnect-v1.yaml)
   to call Gateway B through `resolve-alias-remote` and `route-payment`.

The registry does **not** execute payments, hold funds, or replace gateway-to-gateway settlement. It provides identity and routing facts; OW-GIP handles gateway discovery, remote routing, payment status, health, and interconnect settlement.

The same boundary applies to presented payments. QR and NFC presentments may resolve an alias through OpenWave Identity, but the registry must not own presentment creation, claim state, session status, or customer authorization state.

For EMV/NUMO-compatible OpenWave QR, Identity is not the QR parser and does not allocate QR tags. Banks, wallets, and gateways detect `LY.OPENWAVE` in the OpenWave QR template, claim the presentment with the operator, and only use Identity if an NPT alias needs to be resolved as part of the payment or mandate flow.

The same boundary also applies to Credit & Finance. Identity may help a finance provider, gateway, bank, or wallet resolve an alias for account selection or repayment routing, but the registry must not store credit assessments, risk scores, finance offers, contracts, repayment schedules, or customer affordability data.

Recommended production topology:

```text
Merchant → Gateway A → OpenWave Identity
                    └→ Gateway B (OW-GIP) → Bank Core
```

## Bank callback topology

OpenWave Identity does not call bank callback endpoints, Nexus, Andalus, CBS, OTP, push, payment execution, or webhook delivery services.

Those calls belong to payment gateways and bank middleware. In a private-bank deployment, a gateway such as Neptune Astro may be configured like this:

```text
Astro gateway → Andalus public edge → Nexus middleware → Bank core
```

Identity remains outside that callback path:

```text
Gateway / wallet / app → OpenWave Identity → routing answer
Gateway / bank stack   → payment, consent, mandate, webhook, settlement lifecycle
```

This separation is intentional. The registry owns NPT handle truth and bank-scoped account links. It does not hold funds, execute transfers, collect OTP, host Open Banking consent, or proxy callbacks to bank systems.

---

## API Overview

| Method | Path | Auth | Purpose |
|:---|:---|:---|:---|
| `GET` | `/v1/identity/resolve` | None (public) | Resolve alias → IBAN |
| `POST` | `/v1/identity/claim` | Bank key | Claim a new handle |
| `GET` | `/v1/identity/{handle}` | None (public) | Get public profile |
| `GET` | `/v1/identity/{handle}/accounts` | Bank key | List linked accounts |
| `POST` | `/v1/identity/{handle}/accounts` | Bank key | Link additional bank |
| `PATCH` | `/v1/identity/{handle}/accounts/{bank}` | Bank key | Update linked IBAN |
| `DELETE` | `/v1/identity/{handle}/accounts/{bank}` | Bank key | Unlink account |
| `PATCH` | `/v1/identity/{handle}/default` | Bank key | Set default account |
| `DELETE` | `/v1/identity/{handle}` | Bank key | Delete identity |
| `GET` | `/v1/banks` | None (public) | Bank phonebook |
| `POST` | `/v1/banks` | Admin key | Register new bank |
| `PATCH` | `/v1/banks/{handle}` | Admin key | Update bank |
| `GET` | `/v1/registry/info` | None (public) | Registry metadata |

Full spec: [`openwave-identity-v1.0.yaml`](https://github.com/neptune-ly/openwave-spec/blob/main/openwave-identity-v1.0.yaml)

---

## Running locally

### Prerequisites
- JDK 21+
- PostgreSQL 14+
- Gradle

### Setup

```bash
# Create the database
createdb openwave_identity
createuser openwave
psql -c "ALTER USER openwave WITH PASSWORD 'openwave';"
psql -c "GRANT ALL PRIVILEGES ON DATABASE openwave_identity TO openwave;"

# Clone and run
git clone https://github.com/neptune-ly/openwave-identity.git
cd openwave-identity

cp .env.example .env
# Edit .env with your values

./gradlew bootRun
```

The registry API starts on `http://localhost:8095` by default in the local OpenWave stack.
The UI is served from `openwave-ui` during development.

### Quick test

```bash
# Register a bank (admin)
curl -X POST http://localhost:8095/v1/banks \
  -H "X-OpenWave-Registry-Key: your-admin-key" \
  -H "Content-Type: application/json" \
  -d '{"bank_handle":"andalus","display_name":"Andalus Bank","country":"LY","core_url":"https://api.andalus.ly","contact_email":"openwave@andalus.ly"}'

# Claim a handle (bank-initiated)
curl -X POST http://localhost:8095/v1/identity/claim \
  -H "X-OpenWave-Bank-Key: owbk_andalus_..." \
  -H "Content-Type: application/json" \
  -d '{"npt_handle":"mtellesy","iban":"LY83002700100099900001","customer_display_name":"Mohamed T.","bank_customer_ref":"CUST-001"}'

# Resolve (public)
curl http://localhost:8095/v1/identity/resolve?alias=mtellesy
```

---

## Authentication

| Header | Role | Used for |
|:---|:---|:---|
| `X-OpenWave-Registry-Key` | Admin | Bank registration, admin operations |
| `X-OpenWave-Bank-Key` | Bank | Identity claims, account linking |
| *(none)* | Public | Resolution, bank list, registry info |

Bank API keys are issued when a bank is registered via `POST /v1/banks`.
Keys are shown **once** and stored as SHA-256 hashes only.

---

## Governance

This registry is operated by **Neptune Fintech** under an open governance model:

- All source code is open source (Apache 2.0)
- No bank is denied registration without published justification
- Stewardship transfer to the **Central Bank of Libya** or a bank consortium
  is an explicit stated goal
- The `GET /v1/registry/info` endpoint publicly advertises the current operator,
  source code URL, and future operator — it's a commitment in the API itself

See [GOVERNANCE.md](./GOVERNANCE.md) for the full charter.

---

## Tech Stack

- **Kotlin** + **Spring Boot 3**
- **PostgreSQL** + **Flyway** migrations
- **Spring Security** — stateless API key auth
- **Spring Data JPA** — repositories

---

## License

Apache License 2.0 — see [LICENSE](./LICENSE).

---

<div align="center">

Developed and operated by **[Neptune Fintech](https://www.neptune.ly)**  
Part of the [OpenWave Standard](https://github.com/neptune-ly/openwave-spec)

</div>
