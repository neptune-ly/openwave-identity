# OpenWave Identity handoff

Updated: 2026-08-08

## Done

The local portal login has dedicated Customer, Bank, and Registry Admin routes behind a role chooser. Native forms, password-manager-safe `FormData` capture, passkey role binding, bounded requests, inline terminal errors, TOTP autofill/Enter behavior, 48px controls, visible focus, RTL-safe fields, dark mode, safe-area spacing, and reduced motion are enforced by source contracts. Java 21 backend evidence is 88 tests with zero failures/errors and one intentional skip; Svelte check is 0 errors/warnings. The generated static portal bundle is current.

## In progress

Source, contracts, backend passkey tests, and the regenerated hashed static bundle are implemented locally. They still require an intentional commit, review on current `main`, signed production preflight, and exact-byte portal deployment verification.

## Next

Stage every static deletion and every new hashed asset with `index.html` and `version.json`, rerun the release contract against the staged tree, then use the signed preflight/deploy workflow. A real production-user login remains an operator smoke test; never put credentials in logs or handoffs.

## Guardrails

Never commit an index that references untracked assets. Preserve the signed database/migration evidence and atomic portal backup/public-byte verification gates.
