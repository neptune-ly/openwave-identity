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
| Customer | Own the global username, direct any rename, and choose the default account | A rename is never a bank-owned product or an operator-selected name |
| Bank | Claim/link/unlink accounts it has KYC-vouched for and authenticate an explicit customer rename request | Rename requires a linked account plus exact KYC national-ID match; a bank cannot rename an unrelated identity |
| Gateway | Resolve aliases before routing payments or Open Banking handoff | Read-only public resolution |
| Registry admin | Register banks, rotate credentials, audit registry operations | Governance and operational control |

For a full implementation, read the OpenWave spec pages:

- [NPT guide](https://neptune-ly.github.io/openwave-spec/guide/npt.html)
- [Identity API reference](https://neptune-ly.github.io/openwave-spec/api/overview.html)
- [Presented payments](https://neptune-ly.github.io/openwave-spec/guide/presented-payments.html)
- [Gateway Interconnect](https://neptune-ly.github.io/openwave-spec/guide/gateway-interconnect.html)

The bundled UI now includes a public registry/developer landing page plus a credential-based admin portal. API keys remain integration credentials; human portal access uses usernames, passwords, roles, and bank scoping.

The production admin portal also manages bank branding for the public bank directory: approved display names, logo URLs, brand colors, support email, and website details. Branding helps gateways, wallets, and operators identify the bank behind a route; it does not change NPT ownership, account authority, SCA, payment execution, settlement, QR/NFC claim state, or merchant lifecycle state. Portal actions such as bank registration and branding changes are recorded in protected audit events.

---

## How it works

**The registry stores routing only** — no KYC data, no balances, no transaction history.
Just: `username → { bank, iban, is_default }`.

**Banks vouch for users.** A person claims their handle through their bank, which has
already KYC-verified them. The bank calls the registry API with a signed assertion.

**Customers direct renames; linked banks authenticate them.** The customer chooses
the new handle. A bank serving that identity verifies the request against its KYC
record and calls the authenticated rename endpoint. The registry never exposes a
customer self-service rename route that bypasses the linked-bank check.

### Handle lifecycle and rename safety

- Check a candidate with the authenticated availability endpoint before submitting.
  `TAKEN`, `RETIRED`, `INVALID`, and `AVAILABLE` are different states. A transport
  failure is unknown and must never be treated as availability.
- A successful rename permanently reserves the old handle. It cannot be claimed by
  the same customer or anyone else, and resolving it returns `410 HANDLE_RETIRED`.
- Retirement is not a redirect. The registry never reveals the successor through
  the old payment address.
- Renames are limited to one every 30 days and three over the identity lifetime.
- A rename changes the customer portal username. Existing customer portal sessions
  are rejected once their old subject no longer names the active customer user;
  OAuth access/refresh tokens and grants under that subject are revoked in the same
  transaction. The customer must sign in again with the new handle and re-authorize
  any delegated apps. A changed rename response includes `retiredHandle`,
  `reauthenticationRequired: true`, and a customer-facing `nextStep`.

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
| `GET` | `/v1/identity/handles/{handle}/availability` | Authenticated portal/bank | Distinguish available, taken, retired, and invalid handles |
| `PATCH` | `/v1/identity/{handle}/handle` | Bank key | Authenticate a customer-directed rename and retire the old handle |
| `GET` | `/v1/identity/{handle}` | Authenticated portal/bank | Get identity profile |
| `GET` | `/v1/identity/{handle}/accounts` | Bank key | List linked accounts |
| `POST` | `/v1/identity/{handle}/accounts` | Bank key | Link additional bank |
| `PATCH` | `/v1/identity/{handle}/accounts/{bank}` | Bank key | Update linked IBAN |
| `DELETE` | `/v1/identity/{handle}/accounts/{bank}` | Bank key | Unlink account |
| `PATCH` | `/v1/identity/{handle}/default` | Bank key | Set default account |
| `DELETE` | `/v1/identity/{handle}` | Bank key | Delete identity |
| `GET` | `/v1/banks` | None (public) | Bank phonebook |
| `POST` | `/v1/banks` | Admin key | Register new bank |
| `PATCH` | `/v1/banks/{handle}` | Admin key | Update bank |
| `PATCH` | `/v1/banks/{handle}/branding` | Admin portal | Update public bank branding |
| `POST` | `/v1/banks/{handle}/branding/logo` | Admin portal | Upload public bank logo |
| `GET` | `/v1/portal/audit-events` | Admin portal | Review registry portal audit events |
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

Set `SERVER_PORT` and `REGISTRY_ADMIN_KEY` explicitly for every environment. The UI is served from `openwave-ui` during development and can also be embedded in the registry service.

### Quick test

```bash
# Register a bank (admin)
curl -X POST https://identity.example.com/v1/banks \
  -H "X-OpenWave-Registry-Key: your-admin-key" \
  -H "Content-Type: application/json" \
  -d '{"bank_handle":"andalus","display_name":"Andalus Bank","country":"LY","core_url":"https://api.andalus.ly","contact_email":"openwave@andalus.ly"}'

# Claim a handle (bank-initiated)
curl -X POST https://identity.example.com/v1/identity/claim \
  -H "X-OpenWave-Bank-Key: owbk_andalus_..." \
  -H "Content-Type: application/json" \
  -d '{"npt_handle":"mtellesy","iban":"LY83002700100099900001","customer_display_name":"Mohamed T.","bank_customer_ref":"CUST-001"}'

# Preflight a customer-selected replacement (authenticated; never assume a
# network error means AVAILABLE)
curl https://identity.example.com/v1/identity/handles/mtellesy.ly/availability \
  -H "X-OpenWave-Bank-Key: owbk_andalus_..."

# Submit the customer-directed rename after KYC verification. The old handle is
# permanently retired and the customer must re-authenticate with the new handle.
curl -X PATCH https://identity.example.com/v1/identity/mtellesy/handle \
  -H "X-OpenWave-Bank-Key: owbk_andalus_..." \
  -H "Content-Type: application/json" \
  -d '{"new_handle":"mtellesy.ly","national_id":"123456789012"}'

# Resolve (public)
curl https://identity.example.com/v1/identity/resolve?alias=mtellesy
```

---

## Authentication

| Header | Role | Used for |
|:---|:---|:---|
| `X-OpenWave-Registry-Key` | Admin | Bank registration, admin operations |
| `X-OpenWave-Bank-Key` | Bank | Identity claims, linked-account operations, and KYC-authenticated customer-directed rename |
| *(none)* | Public | Resolution, bank list, registry info |

Bank API keys are issued when a bank is registered via `POST /v1/banks`.
Keys are shown **once** and stored as SHA-256 hashes only.

### Migration verification

The ordinary test suite does not require Docker or a local PostgreSQL process.
It always runs a dialect guard over every Flyway SQL file. To execute the complete
migration chain against a real isolated PostgreSQL schema, point the opt-in test at
a database whose name contains `test` or `ci`:

```bash
OPENWAVE_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1/openwave_identity_test \
OPENWAVE_TEST_POSTGRES_USER=openwave \
OPENWAVE_TEST_POSTGRES_PASSWORD=openwave \
./gradlew test --tests 'ly.openwave.identity.migration.PostgresMigrationTest'
```

The test creates and drops one randomly named schema; it refuses cleanup against a
database name that does not contain `test` or `ci`.

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
