#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
preflight="${repo_root}/scripts/production-preflight.sh"
deploy="${repo_root}/scripts/deploy-prod-self-hosted.sh"
client="${repo_root}/openwave-ui/src/lib/api/client.js"
customer="${repo_root}/openwave-ui/src/routes/portal/customer/+page.svelte"
banks="${repo_root}/openwave-ui/src/routes/portal/banks/+page.svelte"
reset_password="${repo_root}/openwave-ui/src/routes/reset-password/+page.svelte"
asset_parser="${repo_root}/scripts/list-static-entry-assets.py"

require_literal() {
    local file="$1" literal="$2" message="$3"
    grep -Fq -- "${literal}" "${file}" || {
        printf 'release contract failed: %s\n' "${message}" >&2
        exit 1
    }
}

require_literal "${preflight}" '--clean --if-exists --no-owner --no-privileges --exit-on-error' \
    'the disposable PostgreSQL restore must fail on schema/data errors without requiring production roles or ACLs'
preflight_fingerprint_alias_count="$(grep -Foc -- "SELECT 'table:' || relname AS item" "${preflight}" || true)"
deploy_fingerprint_alias_count="$(grep -Foc -- "SELECT 'table:' || relname AS item" "${deploy}" || true)"
fingerprint_alias_count="$((preflight_fingerprint_alias_count + deploy_fingerprint_alias_count))"
[ "${fingerprint_alias_count}" -eq 3 ] || {
    printf 'release contract failed: every V18 fingerprint query must name its aggregate column\n' >&2
    exit 1
}
if grep -Fq -- '--network host' "${preflight}" "${deploy}" "${repo_root}/scripts/openwave-prod-evidence-lib.sh"; then
    printf 'release contract failed: production DB proofs must use the live app network namespace\n' >&2
    exit 1
fi

require_literal "${deploy}" 'mapfile -t ui_asset_paths' \
    'the deploy must enumerate release entry assets before publishing'
# This is the literal deploy expression to enforce.
# shellcheck disable=SC2016
require_literal "${deploy}" 'cmp -s "${ui_source}${asset_path}" "${ui_asset_probe}"' \
    'the deploy must compare public entry assets with the exact release bytes'
require_literal "${client}" 'timeout: PORTAL_REQUEST_TIMEOUT_MS' \
    'authenticated portal requests must have a bounded timeout'
require_literal "${customer}" 'role="alert"' \
    'the customer workspace must expose a terminal retry state'
require_literal "${banks}" 'role="alert"' \
    'the bank workspace must expose a terminal retry state'
require_literal "${reset_password}" 'timeout: PORTAL_REQUEST_TIMEOUT_MS' \
    'password reset confirmation must have a bounded timeout'

fixture_dir="$(mktemp -d)"
trap 'rm -rf "${fixture_dir}"' EXIT
printf '%s\n' '<link href="/_app/immutable/entry/app.safe.js">' >"${fixture_dir}/valid.html"
python3 "${asset_parser}" "${fixture_dir}/valid.html" >"${fixture_dir}/valid.out"
grep -Fxq '/_app/immutable/entry/app.safe.js' "${fixture_dir}/valid.out" || {
    printf 'release contract failed: the static asset parser rejected a valid entry asset\n' >&2
    exit 1
}
printf '%s\n' \
    '<link href="/_app/immutable/entry/app.safe.js">' \
    '<script src="/_app/immutable/../unsafe.js"></script>' >"${fixture_dir}/mixed-unsafe.html"
if python3 "${asset_parser}" "${fixture_dir}/mixed-unsafe.html" >"${fixture_dir}/mixed-unsafe.out" 2>/dev/null; then
    printf 'release contract failed: a mixed valid/unsafe portal index was accepted\n' >&2
    exit 1
fi
[ ! -s "${fixture_dir}/mixed-unsafe.out" ] || {
    printf 'release contract failed: the asset parser emitted a partial list before rejecting unsafe input\n' >&2
    exit 1
}

printf 'production release contract: PASS\n'
