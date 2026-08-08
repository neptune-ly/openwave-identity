#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
preflight="${repo_root}/scripts/production-preflight.sh"
deploy="${repo_root}/scripts/deploy-prod-self-hosted.sh"
client="${repo_root}/openwave-ui/src/lib/api/client.js"
customer="${repo_root}/openwave-ui/src/routes/portal/customer/+page.svelte"
banks="${repo_root}/openwave-ui/src/routes/portal/banks/+page.svelte"
reset_password="${repo_root}/openwave-ui/src/routes/reset-password/+page.svelte"
login_page="${repo_root}/openwave-ui/src/routes/login/+page.svelte"
asset_parser="${repo_root}/scripts/list-static-entry-assets.py"
v18_recovery="${repo_root}/scripts/recover-v18-success-receipt.sh"
v18_recovery_workflow="${repo_root}/.github/workflows/recover-v18-receipt.yml"
preflight_workflow="${repo_root}/.github/workflows/production-preflight.yml"
deploy_workflow="${repo_root}/.github/workflows/deploy-service.yml"
full_bank_issue_workflow="${repo_root}/.github/workflows/issue-full-bank-credential.yml"

require_literal() {
    local file="$1" literal="$2" message="$3"
    grep -Fq -- "${literal}" "${file}" || {
        printf 'release contract failed: %s\n' "${message}" >&2
        exit 1
    }
}

reject_literal() {
    local file="$1" literal="$2" message="$3"
    if grep -Fq -- "${literal}" "${file}"; then
        printf 'release contract failed: %s\n' "${message}" >&2
        exit 1
    fi
}

require_literal "${preflight}" '--clean --if-exists --no-owner --no-privileges --exit-on-error' \
    'the disposable PostgreSQL restore must fail on schema/data errors without requiring production roles or ACLs'
# These are literal workflow expressions, not values to expand in this checker.
# shellcheck disable=SC2016
for workflow in "${preflight_workflow}" "${deploy_workflow}"; do
    require_literal "${workflow}" 'test "${{ github.ref }}" = refs/heads/main' \
        'production workflows must be dispatched from reviewed main'
    require_literal "${workflow}" 'fetch-depth: 0' \
        'production workflow checkout must include origin/main provenance'
    require_literal "${workflow}" 'test "$(git rev-parse HEAD)" = "$RELEASE_SHA"' \
        'production workflow must bind checkout HEAD to the requested SHA'
    require_literal "${workflow}" 'test "$(git rev-parse refs/remotes/origin/main)" = "$RELEASE_SHA"' \
        'production workflow must bind requested SHA to current origin/main'
done
require_literal "${preflight}" 'set -Eeuo pipefail' \
    'the production preflight must inherit its secret-safe ERR diagnostic into functions and command substitutions'
require_literal "${preflight}" 'ERROR: failed phase=%s status=%s; no deployment was attempted' \
    'the production preflight must report a safe phase and numeric status on otherwise silent failures'
# This is a literal preflight expression, not a value to expand in this checker.
# shellcheck disable=SC2016
require_literal "${preflight}" 'if ! docker exec "${restore_name}" pg_isready -U postgres >/dev/null 2>&1; then' \
    'the isolated restore readiness failure must have an explicit terminal error path'
require_literal "${preflight}" 'cat /proc/1/comm' \
    'isolated PostgreSQL readiness must distinguish the final PID-1 server from its temporary init server'
require_literal "${preflight}" 'isolated PostgreSQL final server lost PID 1 after stability delay' \
    'isolated PostgreSQL readiness must recheck the final server after a stability delay'
require_literal "${preflight}" 'isolated PostgreSQL restore lost readiness after stability delay' \
    'isolated PostgreSQL readiness must recheck authenticated readiness after the PID-1 stability delay'
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
[ "${canonical_v18_meta_count}" -eq 9 ] || {
    printf 'release contract failed: all V18, V19, and V20 normal gates must use the canonical t/f success projection\n' >&2
    exit 1
}
if grep -Fq -- "checksum || '|' || success" "${preflight}" "${deploy}" "${v18_recovery}"; then
    printf 'release contract failed: V18 gates must not rely on ambiguous implicit boolean concatenation\n' >&2
    exit 1
fi
require_literal "${preflight}" 'V19 state is partial, drifted, or not an exact successful reviewed migration' \
    'preflight must reject partial or drifted V19 state'
require_literal "${preflight}" 'v19_gate=first_deploy_absent' \
    'preflight must accept an entirely absent V19 row and schema before first deploy'
require_literal "${preflight}" 'v19_gate=subsequent_complete' \
    'preflight must accept exactly one completed, source-checksum-matching V19 migration on retry'
require_literal "${deploy}" 'post-start Identity V19 state is not one successful complete migration' \
    'deploy must prove V19 history, checksum, and schema shape after health before publishing'
require_literal "${deploy}" 'V19 state changed after preflight' \
    'deploy must recheck an absent V19 state under the deploy lock'
if grep -Fq -- 'v19-success.receipt' "${preflight}" "${deploy}"; then
    printf 'release contract failed: V19 must not introduce a one-shot receipt gate\n' >&2
    exit 1
fi
require_literal "${preflight}" 'V20 state is partial, drifted, or not an exact successful reviewed migration' \
    'preflight must reject partial or drifted V20 state'
require_literal "${deploy}" 'post-start Identity V20 state is not one successful complete migration' \
    'deploy must prove V20 checksum and schema shape after health before publishing'
if grep -Fq -- 'v20-success.receipt' "${preflight}" "${deploy}"; then
    printf 'release contract failed: V20 must not introduce a one-shot receipt gate\n' >&2
    exit 1
fi
require_literal "${full_bank_issue_workflow}" 'scope FULL_BANK' \
    'full-bank rotation issuance must request an explicit full-bank scope'
# Literal shell target guard; do not expand it in this checker.
# shellcheck disable=SC2016
require_literal "${full_bank_issue_workflow}" 'test "$BANK_HANDLE" = andalus' \
    'full-bank rotation issuance must enforce the fixed Andalus target in shell, not only the UI choice'
require_literal "${full_bank_issue_workflow}" '.bankHandle == "andalus"' \
    'full-bank rotation issuance must bind the raw response to Andalus before encryption'
require_literal "${full_bank_issue_workflow}" 'openssl pkeyutl -encrypt' \
    'full-bank rotation issuance must encrypt the one-time raw credential'
require_literal "${full_bank_issue_workflow}" 'rsa_padding_mode:oaep' \
    'full-bank rotation issuance must use OAEP padding'
# Literal workflow cleanup expression; do not expand it in this checker.
# shellcheck disable=SC2016
require_literal "${full_bank_issue_workflow}" 'rm -f "$workdir/raw-key" "$workdir/response.json"' \
    'full-bank rotation issuance must remove raw key material before artifact publication'
require_literal "${full_bank_issue_workflow}" 'full-bank-credential-ciphertext.base64' \
    'full-bank rotation issuance must publish only encrypted credential material'
# Literal JSON expression; do not expand it in this checker.
# shellcheck disable=SC2016
require_literal "${full_bank_issue_workflow}" 'fingerprintSha256:$fingerprintSha256' \
    'full-bank rotation metadata must bind the exact replacement credential fingerprint'
require_literal "${full_bank_issue_workflow}" "jq -jr '.bankApiKey'" \
    'full-bank rotation must extract raw key bytes without a JSON newline'
# Literal shell fingerprint expression; do not expand it in this checker.
# shellcheck disable=SC2016
require_literal "${full_bank_issue_workflow}" 'key_fingerprint="$(sha256sum "$workdir/raw-key"' \
    'full-bank rotation fingerprint must be calculated from exact raw key bytes'
require_literal "${full_bank_issue_workflow}" 'full-bank-credential-metadata.base64' \
    'full-bank rotation must publish deterministic base64 metadata for Nexus verification'
require_literal "${full_bank_issue_workflow}" 'secrets.NEXUS_IDENTITY_CUTOVER_RSA_PUBLIC_KEY_B64' \
    'full-bank rotation issuance must use the administrator-pinned Nexus recipient key'
require_literal "${full_bank_issue_workflow}" 'vars.NEXUS_IDENTITY_CUTOVER_RSA_PUBLIC_KEY_SHA256' \
    'full-bank rotation issuance must verify the pinned Nexus recipient key fingerprint'
require_literal "${full_bank_issue_workflow}" 'OPENWAVE_NEXUS_CUTOVER_ATTESTATION_KEY' \
    'full-bank rotation metadata must use the shared Nexus cutover attestation key'
# Literal workflow invocation; do not expand it in this checker.
# shellcheck disable=SC2016
require_literal "${full_bank_issue_workflow}" 'curl --config "$workdir/curl.conf"' \
    'full-bank rotation issuance must keep the registry key out of command arguments'
require_literal "${full_bank_issue_workflow}" 'hmac.new(key, metadata, hashlib.sha256)' \
    'full-bank rotation metadata must calculate HMAC from a key file, not command arguments'
reject_literal "${full_bank_issue_workflow}" 'recipient_rsa_public_key_base64' \
    'full-bank rotation issuance must not accept a caller-supplied recipient key'
if grep -Fq -- 'legacy-credential/deactivate' "${repo_root}/.github/workflows"/*.yml; then
    printf 'release contract failed: legacy deactivation requires a verifiable Nexus receipt workflow, not a self-asserted dispatch input\n' >&2
    exit 1
fi
if rg -q 'legacy-credential/deactivate|deactivateLegacyCredential' "${repo_root}/src/main/kotlin"; then
    printf 'release contract failed: V20 must not expose legacy credential deactivation before Nexus receipt verification exists\n' >&2
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
# These are literal deploy expressions that constrain the one-time ownership
# repair to the exact pre-provisioned portal tree and the live immutable image.
# shellcheck disable=SC2016
require_literal "${deploy}" '[ "${canonical_ui_dir}" = "${IDENTITY_UI_DIR}" ]' \
    'portal ownership repair must reject a symlinked target or ancestor'
require_literal "${deploy}" "with open('/proc/self/mountinfo', encoding='utf-8') as mountinfo:" \
    'portal ownership repair must reject nested host mount points'
# shellcheck disable=SC2016
require_literal "${deploy}" 'find "${IDENTITY_UI_DIR}" -xdev ! -type d ! -type f -print -quit' \
    'portal ownership repair must reject symlinks and special filesystem objects'
# shellcheck disable=SC2016
require_literal "${deploy}" 'find "${IDENTITY_UI_DIR}" -xdev -type f -links +1 -print -quit' \
    'portal ownership repair must reject hard links that could affect an inode outside the target'
# shellcheck disable=SC2016
require_literal "${deploy}" '[[ "${identity_image_id}" =~ ^sha256:[0-9a-f]{64}$ ]]' \
    'portal ownership repair must use an immutable live image ID, never a tag'
require_literal "${deploy}" '--pull=never --network none --read-only' \
    'portal ownership helper must not pull, use the network, or write its root filesystem'
require_literal "${deploy}" '--cap-drop ALL --cap-add CHOWN' \
    'portal ownership helper must retain only the ownership capability'
require_literal "${deploy}" '--security-opt no-new-privileges=true --user 0:0' \
    'portal ownership helper must prohibit privilege escalation and explicitly select its user'
# shellcheck disable=SC2016
require_literal "${deploy}" '--mount "type=bind,src=${IDENTITY_UI_DIR},dst=/target,bind-propagation=rprivate"' \
    'portal ownership helper must bind only the exact validated target'
# shellcheck disable=SC2016
require_literal "${deploy}" '--entrypoint /usr/bin/chown "${identity_image_id}"' \
    'portal ownership helper must override the service Java entrypoint with the absolute chown binary'
# shellcheck disable=SC2016
require_literal "${deploy}" '-hR -- "${runner_uid}:${runner_gid}" /target' \
    'portal ownership helper must not dereference the pre-validated symlink-free target'
if grep -Fq -- '--one-file-system' "${deploy}"; then
    printf 'release contract failed: the production image chown does not support --one-file-system\n' >&2
    exit 1
fi
# shellcheck disable=SC2016
require_literal "${deploy}" '\( ! -uid "${runner_uid}" -o ! -gid "${runner_gid}" \) -print -quit' \
    'portal ownership repair must verify every target entry after the helper exits'
# shellcheck disable=SC2016
if grep -Fq -- 'mkdir -p "${IDENTITY_UI_DIR}"' "${deploy}"; then
    printf 'release contract failed: Docker ownership repair must require a pre-provisioned portal directory\n' >&2
    exit 1
fi
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
ui_ownership_line="$(grep -nFx -- '    ensure_identity_ui_runner_ownership' "${deploy}" | cut -d: -f1)"
# shellcheck disable=SC2016
ui_backup_finalize_line="$(grep -nF -- 'mv -- "${ui_backup_pending}" "${ui_backup_dir}"' "${deploy}" | cut -d: -f1)"
# shellcheck disable=SC2016
checkout_line="$(grep -nF -- 'git checkout --detach "${RESOLVED_REF}"' "${deploy}" | cut -d: -f1)"
[[ "${ui_ownership_line}" =~ ^[0-9]+$ && "${ui_backup_line}" =~ ^[0-9]+$ \
    && "${ui_backup_finalize_line}" =~ ^[0-9]+$ && "${checkout_line}" =~ ^[0-9]+$ \
    && "${ui_ownership_line}" -lt "${ui_backup_line}" \
    && "${ui_backup_line}" -lt "${ui_backup_finalize_line}" \
    && "${ui_backup_finalize_line}" -lt "${checkout_line}" ]] || {
    printf 'release contract failed: portal ownership and complete rollback must finish before host checkout mutation\n' >&2
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
require_literal "${login_page}" "let mode      = \$state('customer');" \
    'customer login must have a usable default lane instead of an inert unselected state'
require_literal "${login_page}" 'const formData = new FormData(form);' \
    'login submission must read browser/password-manager autofill from the native form'
require_literal "${login_page}" 'name="username"' \
    'the login identifier must be a form-associated autofill field'
require_literal "${login_page}" 'name="password"' \
    'the login password must be a form-associated autofill field'
require_literal "${login_page}" 'autocomplete="current-password"' \
    'the login password must advertise the current-password autofill contract'
require_literal "${login_page}" 'autocomplete="one-time-code"' \
    'the authenticator field must support native one-time-code autofill'
require_literal "${login_page}" 'name="totp"' \
    'the authenticator code must remain form-associated for password-manager autofill'
require_literal "${login_page}" 'on:keydown={onTotpKey}' \
    'the authenticator field must retain its explicit Enter-key verification path'
require_literal "${login_page}" '<Button type="submit" class="w-full" disabled={loading || !mode}>' \
    'the sign-in action must submit visibly autofilled values instead of depending on stale component state'
reject_literal "${login_page}" 'on:click={connect}' \
    'the sign-in button must not double-dispatch click and form-submit handlers'

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
