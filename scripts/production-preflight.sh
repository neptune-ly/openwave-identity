#!/usr/bin/env bash
set -euo pipefail

# Creates evidence that a particular immutable Identity release can be deployed.
# It intentionally runs on the production runner: neither database credentials
# nor a database backup ever leave the private network or the host.

SERVICE=identity
APP_DIR="${APP_DIR:-/opt/openwave/openwave-identity}"
COMPOSE_DIR="${COMPOSE_DIR:-/opt/openwave/neptune-astro/deploy/hetzner}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.app.yml}"
PRODUCTION_ENV_FILE="${PRODUCTION_ENV_FILE:-${COMPOSE_DIR}/.env}"
RELEASE_SHA="${RELEASE_SHA:?RELEASE_SHA is required}"
RELEASE_WORKSPACE="${GITHUB_WORKSPACE:-$(pwd)}"
RUNTIME_DIR="${RUNTIME_DIR:-${COMPOSE_DIR}/.env.openwave-release}"
EVIDENCE_DIR="${EVIDENCE_DIR:-${RUNTIME_DIR}/release-evidence/identity}"
BACKUP_DIR="${BACKUP_DIR:-${RUNTIME_DIR}/backups/identity}"
BACKUP_ENCRYPTION_KEY_FILE="${BACKUP_ENCRYPTION_KEY_FILE:-/opt/openwave/secrets/prod-backup-encryption.key}"
PREFLIGHT_ATTESTATION_KEY_FILE="${PREFLIGHT_ATTESTATION_KEY_FILE:-/opt/openwave/secrets/preflight-attestation.key}"
LOCK_FILE="${LOCK_FILE:-${COMPOSE_DIR}/.openwave-prod-deploy.lock}"

# shellcheck disable=SC1091
source "$(dirname "$0")/openwave-prod-evidence-lib.sh"

require_regular_mode_600() {
    local file="$1" label="$2" mode
    [ -f "${file}" ] && [ ! -L "${file}" ] || ow_fail "${label} must be a regular file"
    mode="$(stat -c '%a' "${file}")"
    [ "${mode}" = 600 ] || ow_fail "${label} must have mode 600"
}

pg_client() {
    local anchor
    local -a prefix=()
    if [ "${1:-}" = --bounded ]; then
        prefix=(timeout 300)
        shift
    fi
    anchor="$(ow_live_compose_service_id "${COMPOSE_DIR}" "${COMPOSE_FILE}" "${PRODUCTION_ENV_FILE}" identity)"
    "${prefix[@]}" docker run --rm --network "container:${anchor}" \
        -v "${pgpass_file}:/tmp/pgpass:ro" -e PGPASSFILE=/tmp/pgpass \
        postgres:16-alpine "$@"
}

pg_source_inventory_digest() {
    local tables table count rows=""
    tables="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")"
    while IFS= read -r table; do
        [[ "${table}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || ow_fail "unexpected public table name"
        count="$(pg_client --bounded psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM public.\"${table}\"")" || ow_fail "source row-count inventory exceeded its bound"
        rows+="${table}:${count}"$'\n'
    done <<<"${tables}"
    printf '%s' "${rows}" | sha256sum | awk '{print $1}'
}

pg_restore_inventory_digest() {
    local container="$1" database="$2" tables table count rows=""
    tables="$(docker exec "${container}" psql -U postgres -d "${database}" -Atqc "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")"
    while IFS= read -r table; do
        [[ "${table}" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || ow_fail "unexpected restored public table name"
        count="$(timeout 300 docker exec "${container}" psql -U postgres -d "${database}" -Atqc "SELECT count(*) FROM public.\"${table}\"")" || ow_fail "restored row-count inventory exceeded its bound"
        rows+="${table}:${count}"$'\n'
    done <<<"${tables}"
    printf '%s' "${rows}" | sha256sum | awk '{print $1}'
}

load_production_env() {
    # Python emits shell-quoted assignments; it accepts only conventional dotenv
    # entries and never logs a value. Do not source a production env file.
    eval "$(python3 - "${PRODUCTION_ENV_FILE}" <<'PY'
import pathlib, shlex, re, sys
for raw in pathlib.Path(sys.argv[1]).read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    if line.startswith('export '): line = line[7:].lstrip()
    key, sep, value = line.partition('=')
    if not sep or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key):
        raise SystemExit('unsafe production dotenv syntax')
    print(f'export {key}={shlex.quote(value)}')
PY
)"
}

ow_validate_release_sha "${RELEASE_SHA}"
ow_require_command docker
ow_require_command openssl
ow_require_command flock
ow_require_command python3
ow_prepare_runtime_dir "${RUNTIME_DIR}"
ow_prepare_lock_file "${LOCK_FILE}"
require_regular_mode_600 "${PRODUCTION_ENV_FILE}" "production environment file"
ow_require_secret_file "${BACKUP_ENCRYPTION_KEY_FILE}" "backup encryption key"
ow_require_secret_file "${PREFLIGHT_ATTESTATION_KEY_FILE}" "preflight attestation key"
[ "${BACKUP_ENCRYPTION_KEY_FILE}" != "${PREFLIGHT_ATTESTATION_KEY_FILE}" ] || ow_fail "backup and attestation key files must be distinct"

(
    flock -n 9 || ow_fail "another OpenWave production operation is running"
    load_production_env
    pgpass_file="$(mktemp)"
    chmod 600 "${pgpass_file}"
    printf '%s:%s:*:%s:%s\n' "${IDENTITY_DB_HOST}" "${IDENTITY_DB_PORT:-5432}" "${IDENTITY_DB_USER}" "${IDENTITY_DB_PASSWORD}" >"${pgpass_file}"
    # shellcheck disable=SC2329
    cleanup_credentials() { rm -f "${pgpass_file}"; }
    trap cleanup_credentials EXIT
    [ -d "${RELEASE_WORKSPACE}/.git" ] || ow_fail "Actions release workspace is not a git checkout"
    [ "$(git -C "${RELEASE_WORKSPACE}" rev-parse HEAD)" = "${RELEASE_SHA}" ] || ow_fail "Actions checkout does not equal requested release SHA"
    cd "${APP_DIR}"
    [ -d .git ] || ow_fail "production app directory is not a git checkout"
    [ -z "$(git status --porcelain)" ] || ow_fail "production app checkout is dirty"
    production_checkout_sha="$(git rev-parse HEAD)"

    cd "${COMPOSE_DIR}"
    [ -f "${COMPOSE_FILE}" ] && [ ! -L "${COMPOSE_FILE}" ] || ow_fail "compose file is unavailable"
    docker compose --env-file "${PRODUCTION_ENV_FILE}" -f "${COMPOSE_FILE}" config -q
    compose_json="$(mktemp)"
    trap 'rm -f "${compose_json}"; cleanup_credentials' EXIT
    docker compose --env-file "${PRODUCTION_ENV_FILE}" -f "${COMPOSE_FILE}" config --format json >"${compose_json}"
    ow_verify_dedicated_secret_material "${BACKUP_ENCRYPTION_KEY_FILE}" "${PREFLIGHT_ATTESTATION_KEY_FILE}" "${compose_json}"
    rm -f "${compose_json}"
    trap cleanup_credentials EXIT
    caddy_id="$(docker compose --env-file "${PRODUCTION_ENV_FILE}" -f "${COMPOSE_FILE}" ps -q caddy)"
    [ -n "${caddy_id}" ] || ow_fail "Caddy is not running; refuse a release with no serving path"
    [ "$(docker inspect -f '{{.State.Running}}' "${caddy_id}")" = true ] || ow_fail "Caddy container is not running"

    stamp="$(date +%s)"
    install -d -m 700 "${BACKUP_DIR}" "${EVIDENCE_DIR}"
    backup_file="${SERVICE}-${RELEASE_SHA}-${stamp}.dump.enc"
    backup_path="${BACKUP_DIR}/${backup_file}"
    umask 077
    source_inventory_sha256="$(pg_source_inventory_digest)"
    # Custom format permits deterministic structural verification without
    # exposing SQL. The pipe is the only copy of unencrypted production data.
    pg_client pg_dump -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -Fc "${IDENTITY_DB_NAME}" \
        | openssl enc -aes-256-cbc -pbkdf2 -salt -pass "file:${BACKUP_ENCRYPTION_KEY_FILE}" -out "${backup_path}"
    [ -s "${backup_path}" ] || ow_fail "encrypted PostgreSQL backup is empty"

    restore_name="ow-identity-restore-${stamp}-$$"
    docker run -d --rm --name "${restore_name}" -e POSTGRES_PASSWORD=restore-only postgres:16-alpine >/dev/null
    cleanup_restore() { docker rm -f "${restore_name}" >/dev/null 2>&1 || true; }
    trap 'cleanup_restore; cleanup_credentials' EXIT
    for _ in $(seq 1 30); do docker exec "${restore_name}" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
    docker exec "${restore_name}" pg_isready -U postgres >/dev/null
    restore_database="identity_restore_ci_${stamp}"
    docker exec "${restore_name}" createdb -U postgres "${restore_database}"
    # This gate proves that the encrypted dump can recover logical schema and
    # data into a clean cluster. Production roles and grants are provisioned by
    # database operations, so the disposable cluster deliberately restores as
    # its local superuser while still failing on every schema/data restore error.
    openssl enc -d -aes-256-cbc -pbkdf2 -pass "file:${BACKUP_ENCRYPTION_KEY_FILE}" -in "${backup_path}" \
        | docker exec -i "${restore_name}" pg_restore -U postgres -d "${restore_database}" \
            --clean --if-exists --no-owner --no-privileges --exit-on-error
    table_count="$(docker exec "${restore_name}" psql -U postgres -d "${restore_database}" -Atqc "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")"
    [[ "${table_count}" =~ ^[1-9][0-9]*$ ]] || ow_fail "isolated PostgreSQL restore has no public tables"
    docker exec "${restore_name}" psql -U postgres -d "${restore_database}" -Atqc "SELECT 1 FROM flyway_schema_history LIMIT 1" >/dev/null
    restored_inventory_sha256="$(pg_restore_inventory_digest "${restore_name}" "${restore_database}")"
    [ "${source_inventory_sha256}" = "${restored_inventory_sha256}" ] || ow_fail "restored PostgreSQL table/row-count inventory differs from source"
    cleanup_restore
    trap cleanup_credentials EXIT

    v18_count="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM flyway_schema_history WHERE version = '18'")"
    v18_meta="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT checksum || '|' || success FROM flyway_schema_history WHERE version = '18'")"
    v18_fingerprint="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT md5(coalesce(string_agg(item, '|' ORDER BY item), '')) FROM (SELECT 'table:' || relname FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 'column:' || column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 'index:' || indexname || ':' || indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 'constraint:' || conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    v18_objects="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM (SELECT 1 FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 1 FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    receipt="${EVIDENCE_DIR}/v18-success.receipt"
    receipt_sig="${receipt}.sig"
    if [ -e "${receipt}" ] || [ -e "${receipt_sig}" ]; then
        [ -f "${receipt}" ] && [ -f "${receipt_sig}" ] || ow_fail "Identity V18 success receipt is incomplete"
        ow_verify_evidence_signature "${PREFLIGHT_ATTESTATION_KEY_FILE}" "${receipt}" "${receipt_sig}"
        [ "$(ow_evidence_field "${receipt}" v18_success)" = true ] || ow_fail "Identity V18 receipt is malformed"
        [ "${v18_count}" = 1 ] && [ "${v18_objects}" = 8 ] && [[ "${v18_meta}" =~ ^-?[0-9]+\|t$ ]] || ow_fail "V18 receipt exists but live V18 state is not a successful complete migration"
        ow_require_evidence_value "${receipt}" flyway_checksum "${v18_meta%%|*}"
        ow_require_evidence_value "${receipt}" object_fingerprint "${v18_fingerprint}"
        v18_gate=subsequent_receipted
    else
        [ "${v18_count}" = 0 ] && [ "${v18_objects}" = 0 ] || ow_fail "first V18 deploy requires no V18 history row and no V18 objects; repair deliberately"
        v18_gate=first_deploy_absent
    fi

    evidence_tmp="$(mktemp "${EVIDENCE_DIR}/.${RELEASE_SHA}.XXXXXX")"
    cat >"${evidence_tmp}" <<EOF
evidence_version=1
service=${SERVICE}
release_sha=${RELEASE_SHA}
created_at_epoch=${stamp}
backup_restore_verified=true
production_checkout_clean=true
production_checkout_sha=${production_checkout_sha}
lock_namespace_verified=persistent_compose_path
production_env_sha256=$(ow_sha256_file "${PRODUCTION_ENV_FILE}")
compose_sha256=$(ow_sha256_file "${COMPOSE_FILE}")
backup_file=${backup_file}
backup_sha256=$(ow_sha256_file "${backup_path}")
restore_inventory_sha256=${restored_inventory_sha256}
caddy_running=true
v18_gate=${v18_gate}
v18_object_fingerprint=${v18_fingerprint}
EOF
    ow_sign_evidence "${PREFLIGHT_ATTESTATION_KEY_FILE}" "${evidence_tmp}" "${evidence_tmp}.sig"
    mv -f "${evidence_tmp}" "${EVIDENCE_DIR}/${RELEASE_SHA}.attestation"
    mv -f "${evidence_tmp}.sig" "${EVIDENCE_DIR}/${RELEASE_SHA}.attestation.sig"
    ow_log "signed Identity preflight evidence created for ${RELEASE_SHA}; no secret values were emitted"
) 9>"${LOCK_FILE}"
