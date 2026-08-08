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
v18_recovery="${repo_root}/scripts/recover-v18-success-receipt.sh"
v18_recovery_workflow="${repo_root}/.github/workflows/recover-v18-receipt.yml"

require_literal() {
    local file="$1" literal="$2" message="$3"
    grep -Fq -- "${literal}" "${file}" || {
        printf 'release contract failed: %s\n' "${message}" >&2
        exit 1
    }
}

require_literal "${preflight}" '--clean --if-exists --no-owner --no-privileges --exit-on-error' \
    'the disposable PostgreSQL restore must fail on schema/data errors without requiring production roles or ACLs'
require_literal "${preflight}" 'set -Eeuo pipefail' \
    'the production preflight must inherit its secret-safe ERR diagnostic into functions and command substitutions'
require_literal "${preflight}" 'ERROR: failed phase=%s status=%s; no deployment was attempted' \
    'the production preflight must report a safe phase and numeric status on otherwise silent failures'
# This is a literal preflight expression, not a value to expand in this checker.
# shellcheck disable=SC2016
require_literal "${preflight}" 'if ! docker exec "${restore_name}" pg_isready -U postgres >/dev/null 2>&1; then' \
    'the isolated restore readiness failure must have an explicit terminal error path'
preflight_phase_count="$(grep -Fc -- 'mark_preflight_phase ' "${preflight}" || true)"
[ "${preflight_phase_count}" -ge 10 ] || {
    printf 'release contract failed: the production preflight must label every major secret-safe proof phase\n' >&2
    exit 1
}
if grep -Fq -- 'BASH_COMMAND' "${preflight}"; then
    printf 'release contract failed: preflight diagnostics must never print the failing command or its arguments\n' >&2
    exit 1
fi
canonical_v18_meta="SELECT checksum::text || '|' || CASE WHEN success THEN 't' ELSE 'f' END FROM flyway_schema_history"
canonical_v18_meta_count="$((
    $(grep -Foc -- "${canonical_v18_meta}" "${preflight}" || true) +
    $(grep -Foc -- "${canonical_v18_meta}" "${deploy}" || true)
))"
[ "${canonical_v18_meta_count}" -eq 3 ] || {
    printf 'release contract failed: all three normal V18 gates must use the canonical t/f success projection\n' >&2
    exit 1
}
if grep -Fq -- "checksum || '|' || success" "${preflight}" "${deploy}" "${v18_recovery}"; then
    printf 'release contract failed: V18 gates must not rely on ambiguous implicit boolean concatenation\n' >&2
    exit 1
fi
require_literal "${v18_recovery}" "${canonical_v18_meta}" \
    'the one-shot receipt recovery must use the same canonical V18 row proof'
# shellcheck disable=SC2016
require_literal "${v18_recovery}" 'SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"' \
    'receipt recovery tools must resolve from the immutable Actions checkout before changing directories'
# shellcheck disable=SC2016
require_literal "${v18_recovery}" 'v18_checksum_tool="${SCRIPT_DIR}/flyway-sql-checksum.py"' \
    'receipt recovery must not resolve its checksum tool from the older production checkout'
# shellcheck disable=SC2016
require_literal "${v18_recovery}" 'expected_v18_checksum="$(python3 "${v18_checksum_tool}" "${v18_source}")"' \
    'receipt recovery must calculate Flyway checksum from the exact failed-release migration source'
# shellcheck disable=SC2016
require_literal "${v18_recovery}" '[ "${v18_meta%%|*}" = "${expected_v18_checksum}" ]' \
    'receipt recovery must bind the live Flyway row to the exact failed-release migration source'
# These are literal recovery expressions, not values to expand in this checker.
# shellcheck disable=SC2016
require_literal "${v18_recovery}" '[ -z "$(git status --porcelain)" ]' \
    'receipt recovery must require a clean production checkout'
# shellcheck disable=SC2016
require_literal "${v18_recovery}" '[ "${oci_revision}" = "${FAILED_DEPLOY_SHA}" ]' \
    'receipt recovery must pin the live OCI revision to the failed deploy SHA'
# shellcheck disable=SC2016
require_literal "${v18_recovery}" '[ "${v18_count}" = 1 ]' \
    'receipt recovery must prove exactly one V18 history row across every script name'
# shellcheck disable=SC2016
require_literal "${v18_recovery}" '[ "${v18_objects}" = 8 ]' \
    'receipt recovery must prove all eight V18 schema objects'
# shellcheck disable=SC2016
require_literal "${v18_recovery}" '[ ! -e "${receipt}" ] && [ ! -L "${receipt}" ] && [ ! -e "${signature}" ] && [ ! -L "${signature}" ]' \
    'receipt recovery must reject existing, partial, or dangling-symlink receipt state'
require_literal "${v18_recovery_workflow}" 'failed_deploy_sha:' \
    'the manual V18 recovery workflow must require an explicit failed release SHA'
if grep -Eq -- '^[[:space:]]*(docker compose .*(build|up|down|restart)|git (checkout|switch|reset|fetch|pull)|pg_restore|pg_dump|rsync )' "${v18_recovery}"; then
    printf 'release contract failed: V18 receipt recovery must never deploy, migrate, publish, or mutate the host checkout\n' >&2
    exit 1
fi
# The cleanup ownership flag must be armed before either final receipt artifact
# can appear, so an interrupted publish is recoverable without manual deletion.
receipt_created_line="$(grep -nF -- 'receipt_created=1' "${v18_recovery}" | tail -1 | cut -d: -f1)"
# shellcheck disable=SC2016
receipt_publish_line="$(grep -nF -- 'mv -- "${receipt_tmp}" "${receipt}"' "${v18_recovery}" | cut -d: -f1)"
[[ "${receipt_created_line}" =~ ^[0-9]+$ && "${receipt_publish_line}" =~ ^[0-9]+$ \
    && "${receipt_created_line}" -lt "${receipt_publish_line}" ]] || {
    printf 'release contract failed: interrupted V18 receipt publication must remain automatically recoverable\n' >&2
    exit 1
}
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
# These are literal deploy expressions, not values to expand in this checker.
# shellcheck disable=SC2016
require_literal "${deploy}" 'UI_ROLLBACK_DIR="${UI_ROLLBACK_DIR:-${RUNTIME_DIR}/ui-rollbacks/identity}"' \
    'portal rollback bundles must live in the runner-owned release namespace'
# shellcheck disable=SC2016
require_literal "${deploy}" 'mktemp -d "${UI_ROLLBACK_DIR}/.identity.rollback-pending-${ROLLBACK_SHA}.XXXXXX"' \
    'portal rollback creation must not require write access to the public UI parent'
# shellcheck disable=SC2016
require_literal "${deploy}" 'mv -- "${ui_backup_pending}" "${ui_backup_dir}"' \
    'only a complete portal backup may enter the retained rollback set'
# These searches compare literal deploy expressions.
# shellcheck disable=SC2016
ui_backup_line="$(grep -nF -- 'ui_backup_pending="$(mktemp -d "${UI_ROLLBACK_DIR}/.identity.rollback-pending-${ROLLBACK_SHA}.XXXXXX")"' "${deploy}" | cut -d: -f1)"
# shellcheck disable=SC2016
ui_backup_finalize_line="$(grep -nF -- 'mv -- "${ui_backup_pending}" "${ui_backup_dir}"' "${deploy}" | cut -d: -f1)"
# shellcheck disable=SC2016
checkout_line="$(grep -nF -- 'git checkout --detach "${RESOLVED_REF}"' "${deploy}" | cut -d: -f1)"
[[ "${ui_backup_line}" =~ ^[0-9]+$ && "${ui_backup_finalize_line}" =~ ^[0-9]+$ && "${checkout_line}" =~ ^[0-9]+$ \
    && "${ui_backup_line}" -lt "${ui_backup_finalize_line}" \
    && "${ui_backup_finalize_line}" -lt "${checkout_line}" ]] || {
    printf 'release contract failed: the complete portal rollback must be allocated and finalized before host checkout mutation\n' >&2
    exit 1
}
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
