#!/usr/bin/env bash
set -Eeuo pipefail

# Recover only the signed operational receipt after a proven post-start gate bug.
# This script never checks out code, builds an image, starts a service, runs
# Flyway, or publishes the portal. It attests an already-running, exact release
# only when the live database and container prove the completed V18 transition.

APP_DIR="${APP_DIR:-/opt/openwave/openwave-identity}"
COMPOSE_DIR="${COMPOSE_DIR:-/opt/openwave/neptune-astro/deploy/hetzner}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.app.yml}"
PRODUCTION_ENV_FILE="${PRODUCTION_ENV_FILE:-${COMPOSE_DIR}/.env}"
FAILED_DEPLOY_SHA="${FAILED_DEPLOY_SHA:?FAILED_DEPLOY_SHA is required}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-https://identity.neptune.ly/v1}"
PROBE_HANDLE="${PROBE_HANDLE:-deploy-probe-does-not-exist}"
RUNTIME_DIR="${RUNTIME_DIR:-${COMPOSE_DIR}/.env.openwave-release}"
EVIDENCE_DIR="${EVIDENCE_DIR:-${RUNTIME_DIR}/release-evidence/identity}"
PREFLIGHT_ATTESTATION_KEY_FILE="${PREFLIGHT_ATTESTATION_KEY_FILE:-/opt/openwave/secrets/preflight-attestation.key}"
LOCK_FILE="${LOCK_FILE:-${COMPOSE_DIR}/.openwave-prod-deploy.lock}"

# shellcheck disable=SC1091
source "$(dirname "$0")/openwave-prod-evidence-lib.sh"

recovery_phase=bootstrap
pgpass_file=""
health_file=""
receipt_tmp=""
signature_tmp=""
receipt_created=0
receipt_finalized=0
receipt="${EVIDENCE_DIR}/v18-success.receipt"
signature="${receipt}.sig"

log() {
    printf '[identity-v18-recovery] %s\n' "$*"
}

cleanup() {
    local status=$?
    trap - EXIT
    local temporary
    for temporary in "${pgpass_file:-}" "${health_file:-}" "${receipt_tmp:-}" "${signature_tmp:-}"; do
        [ -z "${temporary}" ] || rm -f -- "${temporary}"
    done
    if [ "${receipt_created}" = 1 ] && [ "${receipt_finalized}" != 1 ]; then
        rm -f -- "${receipt}" "${signature}"
    fi
    if [ "${status}" -ne 0 ]; then
        printf '[identity-v18-recovery] ERROR: failed phase=%s status=%s; no service or database mutation was attempted\n' \
            "${recovery_phase}" "${status}" >&2
    fi
    exit "${status}"
}

require_regular_mode_600() {
    local file="$1" label="$2" mode owner
    [ -f "${file}" ] && [ ! -L "${file}" ] || ow_fail "${label} must be a regular file"
    mode="$(stat -c '%a' "${file}")"
    owner="$(stat -c '%u' "${file}")"
    [ "${mode}" = 600 ] || ow_fail "${label} must have mode 600"
    [ "${owner}" = "$(id -u)" ] || ow_fail "${label} must be owned by the runner account"
}

load_production_env() {
    local env_exports
    if ! env_exports="$(python3 - "${PRODUCTION_ENV_FILE}" <<'PY'
import pathlib, re, shlex, sys

allowed = {
    'IDENTITY_DB_HOST', 'IDENTITY_DB_PORT', 'IDENTITY_DB_NAME',
    'IDENTITY_DB_USER', 'IDENTITY_DB_PASSWORD'
}
for raw in pathlib.Path(sys.argv[1]).read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    if line.startswith('export '):
        line = line[7:].lstrip()
    key, sep, value = line.partition('=')
    if not sep or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key):
        raise SystemExit('unsafe production dotenv syntax')
    if key in allowed:
        print(f'export {key}={shlex.quote(value)}')
PY
)"; then
        ow_fail "production environment parsing failed"
    fi
    eval "${env_exports}"
}

pg_client() {
    local anchor
    anchor="$(ow_live_compose_service_id "${COMPOSE_DIR}" "${COMPOSE_FILE}" "${PRODUCTION_ENV_FILE}" identity)"
    docker run --rm --network "container:${anchor}" \
        -v "${pgpass_file}:/tmp/pgpass:ro" -e PGPASSFILE=/tmp/pgpass \
        postgres:16-alpine "$@"
}

ow_validate_release_sha "${FAILED_DEPLOY_SHA}"
[[ "${PROBE_HANDLE}" =~ ^[a-z0-9][a-z0-9-]{2,63}$ ]] || ow_fail "recovery probe handle is invalid"
[ "${PUBLIC_BASE_URL}" = "https://identity.neptune.ly/v1" ] || ow_fail "receipt recovery is pinned to the Identity production origin"
ow_require_command curl
ow_require_command docker
ow_require_command flock
ow_require_command python3
ow_require_command sha256sum
ow_prepare_runtime_dir "${RUNTIME_DIR}"
ow_prepare_lock_file "${LOCK_FILE}"
require_regular_mode_600 "${PRODUCTION_ENV_FILE}" "production environment file"
ow_require_secret_file "${PREFLIGHT_ATTESTATION_KEY_FILE}" "preflight attestation key"

(
    trap cleanup EXIT
    flock -n 9 || ow_fail "another OpenWave production operation is running"
    recovery_phase=host-release-proof
    [ "${EVIDENCE_DIR}" = "${RUNTIME_DIR}/release-evidence/identity" ] \
        || ow_fail "V18 evidence must use the exact Identity runtime namespace"
    for evidence_path in "${RUNTIME_DIR}/release-evidence" "${EVIDENCE_DIR}"; do
        [ ! -L "${evidence_path}" ] || ow_fail "V18 evidence directory must not be a symlink"
        if [ ! -e "${evidence_path}" ]; then
            umask 077
            mkdir "${evidence_path}"
        fi
        [ -d "${evidence_path}" ] && [ ! -L "${evidence_path}" ] \
            || ow_fail "V18 evidence path is not a safe directory"
        [ "$(stat -c '%u' "${evidence_path}")" = "$(id -u)" ] \
            || ow_fail "V18 evidence directory must be owned by the runner account"
        chmod 700 "${evidence_path}"
    done
    [ ! -e "${receipt}" ] && [ ! -L "${receipt}" ] && [ ! -e "${signature}" ] && [ ! -L "${signature}" ] \
        || ow_fail "V18 receipt recovery requires both receipt and signature to be absent"

    cd "${APP_DIR}"
    [ -d .git ] || ow_fail "production app directory is not a git checkout"
    [ -z "$(git status --porcelain)" ] || ow_fail "production app checkout is dirty"
    production_checkout_sha="$(git rev-parse HEAD)"
    [ "${production_checkout_sha}" = "${FAILED_DEPLOY_SHA}" ] \
        || ow_fail "production checkout does not equal the failed deploy SHA"
    v18_source="${APP_DIR}/src/main/resources/db/migration/V18__handle_rename_and_retirement.sql"
    v18_checksum_tool="$(dirname "$0")/flyway-sql-checksum.py"
    [ -f "${v18_source}" ] && [ ! -L "${v18_source}" ] \
        || ow_fail "failed release V18 migration source is missing or unsafe"
    [ -f "${v18_checksum_tool}" ] && [ ! -L "${v18_checksum_tool}" ] \
        || ow_fail "reviewed Flyway checksum tool is missing or unsafe"
    source_migration_sha256="$(ow_sha256_file "${v18_source}")"
    [[ "${source_migration_sha256}" =~ ^[0-9a-f]{64}$ ]] || ow_fail "V18 migration source digest is invalid"
    expected_v18_checksum="$(python3 "${v18_checksum_tool}" "${v18_source}")"
    [[ "${expected_v18_checksum}" =~ ^-?[0-9]+$ ]] || ow_fail "reviewed V18 Flyway checksum is invalid"

    recovery_phase=live-container-proof
    cd "${COMPOSE_DIR}"
    [ -f "${COMPOSE_FILE}" ] && [ ! -L "${COMPOSE_FILE}" ] || ow_fail "compose file is unavailable"
    docker compose --env-file "${PRODUCTION_ENV_FILE}" -f "${COMPOSE_FILE}" config -q
    identity_cid="$(ow_live_compose_service_id "${COMPOSE_DIR}" "${COMPOSE_FILE}" "${PRODUCTION_ENV_FILE}" identity)"
    [ "$(docker inspect -f '{{.State.Health.Status}}' "${identity_cid}")" = healthy ] \
        || ow_fail "live Identity container is not healthy"
    oci_revision="$(docker inspect -f '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "${identity_cid}")"
    [ "${oci_revision}" = "${FAILED_DEPLOY_SHA}" ] || ow_fail "live Identity OCI revision does not equal the failed deploy SHA"
    caddy_cid="$(docker compose --env-file "${PRODUCTION_ENV_FILE}" -f "${COMPOSE_FILE}" ps -q caddy)"
    [[ "${caddy_cid}" =~ ^[0-9a-f]{12,64}$ ]] || ow_fail "live Caddy service did not resolve to exactly one container"
    [ "$(docker inspect -f '{{.State.Running}}' "${caddy_cid}")" = true ] || ow_fail "live Caddy container is not running"

    recovery_phase=live-v18-proof
    load_production_env
    pgpass_file="$(mktemp)"
    chmod 600 "${pgpass_file}"
    printf '%s:%s:*:%s:%s\n' "${IDENTITY_DB_HOST}" "${IDENTITY_DB_PORT:-5432}" "${IDENTITY_DB_USER}" "${IDENTITY_DB_PASSWORD}" >"${pgpass_file}"
    v18_count="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM flyway_schema_history WHERE version = '18'")"
    v18_script="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT script FROM flyway_schema_history WHERE version = '18'")"
    v18_meta="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT checksum::text || '|' || CASE WHEN success THEN 't' ELSE 'f' END FROM flyway_schema_history WHERE version = '18'")"
    v18_objects="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM (SELECT 1 FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 1 FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    object_fingerprint="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT md5(coalesce(string_agg(item, '|' ORDER BY item), '')) FROM (SELECT 'table:' || relname AS item FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 'column:' || column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 'index:' || indexname || ':' || indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 'constraint:' || conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    [ "${v18_count}" = 1 ] && [ "${v18_script}" = V18__handle_rename_and_retirement.sql ] \
        && [ "${v18_objects}" = 8 ] && [[ "${v18_meta}" =~ ^-?[0-9]+\|t$ ]] \
        || ow_fail "live V18 row or object proof is not exactly one successful complete migration"
    [ "${v18_meta%%|*}" = "${expected_v18_checksum}" ] \
        || ow_fail "live V18 Flyway checksum does not match the exact failed-release migration source"
    [[ "${object_fingerprint}" =~ ^[0-9a-f]{32}$ ]] || ow_fail "live V18 object fingerprint is invalid"
    v18_row_fingerprint="$(printf '%s' "${v18_meta}" | sha256sum | awk '{print $1}')"
    [[ "${v18_row_fingerprint}" =~ ^[0-9a-f]{64}$ ]] || ow_fail "live V18 row fingerprint is invalid"

    recovery_phase=public-functional-proof
    health_file="$(mktemp)"
    curl -fsS --max-time 20 "${PUBLIC_BASE_URL%/}/actuator/health" -o "${health_file}"
    python3 - "${health_file}" <<'PY'
import json, pathlib, sys
payload = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding='utf-8'))
if payload != {'status': 'UP'}:
    raise SystemExit('public Identity health is not exactly UP')
PY
    probe_code="$(curl -sS --max-time 20 -o /dev/null -w '%{http_code}' "${PUBLIC_BASE_URL%/}/identity/resolve?alias=${PROBE_HANDLE}&purpose=payment")" \
        || ow_fail "public Identity resolution probe did not complete"
    case "${probe_code}" in
        200|404) ;;
        *) ow_fail "public Identity resolution probe did not return 200 or 404" ;;
    esac

    recovery_phase=signed-receipt
    [ ! -e "${receipt}" ] && [ ! -L "${receipt}" ] && [ ! -e "${signature}" ] && [ ! -L "${signature}" ] \
        || ow_fail "V18 receipt state changed while recovery held the production lock"
    umask 077
    receipt_tmp="$(mktemp "${EVIDENCE_DIR}/.v18-recovery-receipt.XXXXXX")"
    signature_tmp="${receipt_tmp}.sig"
    printf 'v18_success=true\nrelease_sha=%s\nrecovered_from_failed_deploy=true\nrecovery_reason=post_start_boolean_serialization_gate\nrecorded_at_epoch=%s\nproduction_checkout_sha=%s\noci_revision=%s\nflyway_checksum=%s\nv18_row_fingerprint=%s\nsource_migration_sha256=%s\nobject_fingerprint=%s\n' \
        "${FAILED_DEPLOY_SHA}" "$(date +%s)" "${production_checkout_sha}" "${oci_revision}" "${v18_meta%%|*}" \
        "${v18_row_fingerprint}" "${source_migration_sha256}" "${object_fingerprint}" >"${receipt_tmp}"
    chmod 600 "${receipt_tmp}"
    ow_sign_evidence "${PREFLIGHT_ATTESTATION_KEY_FILE}" "${receipt_tmp}" "${signature_tmp}"
    ow_verify_evidence_signature "${PREFLIGHT_ATTESTATION_KEY_FILE}" "${receipt_tmp}" "${signature_tmp}"
    receipt_created=1
    mv -- "${receipt_tmp}" "${receipt}"
    receipt_tmp=""
    mv -- "${signature_tmp}" "${signature}"
    signature_tmp=""
    ow_verify_evidence_signature "${PREFLIGHT_ATTESTATION_KEY_FILE}" "${receipt}" "${signature}"
    receipt_finalized=1
    log "Recovered signed V18 success receipt for immutable release ${FAILED_DEPLOY_SHA}; no service or database mutation was performed."
) 9>"${LOCK_FILE}"
