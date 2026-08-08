#!/usr/bin/env bash
set -euo pipefail

# OpenWave Identity registry prod deploy, run BY A SELF-HOSTED RUNNER ON THE BOX.
#
# ## Why there is no SSH key in this
#
# The first version of this deploy reached the host over SSH, which meant a
# private key living in GitHub secrets — and it never ran once, because nobody
# pasted one in. The estate already deploys the other way everywhere it deploys
# at all (andalus, nexus, neptune-plus): a runner registered on the machine,
# authenticating with the workflow's own `github.token`. No long-lived
# credential is stored, and nothing off the box can use it.
#
# ## One box, one compose project, two repos
#
# Identity and Astro are built from sibling checkouts under /opt/openwave and
# brought up by the SAME compose file, which lives in the Astro repo. So this
# script checks out THIS repo and then drives compose from over there. That is
# not a layering accident — it is how the host is actually laid out (see
# neptune-astro/deploy/hetzner/README.md).
#
# ## Why it probes resolution and not just health
#
# On 2026-08-01 alias resolution returned HTTP 500 for every input for a full
# day while /actuator/health reported UP throughout, because health only asks
# whether the process is running. A staff tester was the detection mechanism. A
# clean 404 for an unknown handle is a PASS — it proves the registry is
# reachable, authenticating and routing.

APP_DIR="${APP_DIR:-/opt/openwave/openwave-identity}"
RELEASE_WORKSPACE="${RELEASE_WORKSPACE:-${GITHUB_WORKSPACE:-$(pwd)}}"
COMPOSE_DIR="${COMPOSE_DIR:-/opt/openwave/neptune-astro/deploy/hetzner}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.app.yml}"
IDENTITY_UI_DIR="${IDENTITY_UI_DIR:-/opt/openwave/ui/identity}"
DEPLOY_REF="${DEPLOY_REF:-}"
PROBE_HANDLE="${PROBE_HANDLE:-deploy-probe-does-not-exist}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-https://identity.neptune.ly/v1}"
PUBLIC_UI_URL="${PUBLIC_UI_URL:-https://identity.neptune.ly/portal/identity}"
RUNTIME_DIR="${RUNTIME_DIR:-${COMPOSE_DIR}/.env.openwave-release}"
UI_ROLLBACK_DIR="${UI_ROLLBACK_DIR:-${RUNTIME_DIR}/ui-rollbacks/identity}"
# Astro and Identity share one host/compose project. GitHub concurrency is
# repository-scoped, so the same workflow group name cannot serialize the two
# repositories; this shared host lock does.
LOCK_FILE="${LOCK_FILE:-${COMPOSE_DIR}/.openwave-prod-deploy.lock}"
PRODUCTION_ENV_FILE="${PRODUCTION_ENV_FILE:-/opt/openwave/neptune-astro/deploy/hetzner/.env}"
EVIDENCE_DIR="${EVIDENCE_DIR:-${RUNTIME_DIR}/release-evidence/identity}"
BACKUP_DIR="${BACKUP_DIR:-${RUNTIME_DIR}/backups/identity}"
PREFLIGHT_ATTESTATION_KEY_FILE="${PREFLIGHT_ATTESTATION_KEY_FILE:-/opt/openwave/secrets/preflight-attestation.key}"
EVIDENCE_MAX_AGE_SECONDS="${EVIDENCE_MAX_AGE_SECONDS:-14400}"
UI_ROLLBACK_KEEP="${UI_ROLLBACK_KEEP:-5}"
# Set to 1 only after looking at `git status` on the box and deciding the local
# changes are expendable. See the guard below for why this is not the default.
ALLOW_DIRTY_TREE="${ALLOW_DIRTY_TREE:-0}"

log() {
    printf '[identity-deploy] %s\n' "$*"
}

rollback_recipe() {
    # This is deliberately a copy-pasteable, provenance-preserving recovery
    # command. A rollback image must carry the rollback SHA, not the failed
    # release SHA still present in the current shell environment.
    printf 'cd %q && git checkout --detach %q && cd %q && OPENWAVE_IDENTITY_RELEASE_SHA=%q docker compose -f %q build --build-arg %q identity && OPENWAVE_IDENTITY_RELEASE_SHA=%q docker compose -f %q up -d --no-build identity' \
        "${APP_DIR}" "${ROLLBACK_SHA}" "${COMPOSE_DIR}" "${ROLLBACK_SHA}" "${COMPOSE_FILE}" "VCS_REF=${ROLLBACK_SHA}" "${ROLLBACK_SHA}" "${COMPOSE_FILE}"
}

DEPLOY_AUTH_TOKEN="${DEPLOY_GITHUB_TOKEN:-${GITHUB_TOKEN:-}}"
# shellcheck disable=SC1091
source "$(dirname "$0")/openwave-prod-evidence-lib.sh"

pg_client() {
    local anchor
    anchor="$(ow_live_compose_service_id "${COMPOSE_DIR}" "${COMPOSE_FILE}" "${PRODUCTION_ENV_FILE}" identity)"
    docker run --rm --network "container:${anchor}" \
        -v "${pgpass_file}:/tmp/pgpass:ro" -e PGPASSFILE=/tmp/pgpass \
        postgres:16-alpine "$@"
}

ensure_identity_ui_runner_ownership() {
    local runner_uid runner_gid canonical_ui_dir identity_cid identity_image_id identity_revision
    local current_cid current_image unsafe_entry hardlinked_file ownership_mismatch unusable_entry

    runner_uid="$(id -u)"
    runner_gid="$(id -g)"
    [[ "${runner_uid}" =~ ^[0-9]+$ && "${runner_gid}" =~ ^[0-9]+$ ]] \
        || ow_fail "runner UID/GID could not be resolved safely"
    [ "${IDENTITY_UI_DIR}" = /opt/openwave/ui/identity ] \
        || ow_fail "portal ownership repair is restricted to the exact production target"
    [ -d "${IDENTITY_UI_DIR}" ] && [ ! -L "${IDENTITY_UI_DIR}" ] \
        || ow_fail "Identity UI target must be an existing non-symlink directory"
    canonical_ui_dir="$(readlink -f -- "${IDENTITY_UI_DIR}")"
    [ "${canonical_ui_dir}" = "${IDENTITY_UI_DIR}" ] \
        || ow_fail "Identity UI target or one of its ancestors is a symlink"

    # A recursive ownership repair must never cross into a nested bind mount or
    # follow a retained symlink. Validate the host tree before exposing it to
    # the constrained helper container.
    if ! python3 - "${IDENTITY_UI_DIR}" <<'PY'
import os
import sys

root = os.path.realpath(sys.argv[1])
escapes = {r'\040': ' ', r'\011': '\t', r'\012': '\n', r'\134': '\\'}
with open('/proc/self/mountinfo', encoding='utf-8') as mountinfo:
    for line in mountinfo:
        mountpoint = line.split()[4]
        for escaped, value in escapes.items():
            mountpoint = mountpoint.replace(escaped, value)
        if mountpoint == root or mountpoint.startswith(root + os.sep):
            raise SystemExit(1)
PY
    then
        ow_fail "Identity UI target contains an unexpected mount point"
    fi
    unsafe_entry="$(find "${IDENTITY_UI_DIR}" -xdev ! -type d ! -type f -print -quit)" \
        || ow_fail "Identity UI target could not be inspected safely"
    [ -z "${unsafe_entry}" ] \
        || ow_fail "Identity UI target must contain only directories and regular files"
    hardlinked_file="$(find "${IDENTITY_UI_DIR}" -xdev -type f -links +1 -print -quit)" \
        || ow_fail "Identity UI link counts could not be inspected safely"
    [ -z "${hardlinked_file}" ] \
        || ow_fail "Identity UI target must not contain hard-linked files"

    ownership_mismatch="$(find "${IDENTITY_UI_DIR}" -xdev \
        \( ! -uid "${runner_uid}" -o ! -gid "${runner_gid}" \) -print -quit)" \
        || ow_fail "Identity UI ownership could not be inspected"
    if [ -n "${ownership_mismatch}" ]; then
        identity_cid="$(ow_live_compose_service_id "${COMPOSE_DIR}" "${COMPOSE_FILE}" "${PRODUCTION_ENV_FILE}" identity)"
        identity_image_id="$(docker inspect -f '{{.Image}}' "${identity_cid}")"
        [[ "${identity_image_id}" =~ ^sha256:[0-9a-f]{64}$ ]] \
            || ow_fail "live Identity image ID is not immutable"
        identity_revision="$(docker inspect -f '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "${identity_cid}")"
        [ "${identity_revision}" = "${ROLLBACK_SHA}" ] \
            || ow_fail "live Identity image revision does not match the production checkout"

        # Re-resolve immediately before the mount. A manual container swap that
        # races the shared deploy lock must fail closed, never broaden the
        # ownership target or silently trust another image.
        current_cid="$(ow_live_compose_service_id "${COMPOSE_DIR}" "${COMPOSE_FILE}" "${PRODUCTION_ENV_FILE}" identity)"
        current_image="$(docker inspect -f '{{.Image}}' "${current_cid}")"
        [ "${current_cid}" = "${identity_cid}" ] && [ "${current_image}" = "${identity_image_id}" ] \
            || ow_fail "live Identity container changed during portal ownership proof"

        log "Normalizing ownership of the exact Identity portal target for the runner account."
        if ! docker run --rm --pull=never --network none --read-only \
            --cap-drop ALL --cap-add CHOWN \
            --security-opt no-new-privileges=true --user 0:0 \
            --mount "type=bind,src=${IDENTITY_UI_DIR},dst=/target,bind-propagation=rprivate" \
            --entrypoint /usr/bin/chown "${identity_image_id}" \
            -hR -- "${runner_uid}:${runner_gid}" /target; then
            ow_fail "bounded Identity portal ownership repair failed before checkout"
        fi
        current_cid="$(ow_live_compose_service_id "${COMPOSE_DIR}" "${COMPOSE_FILE}" "${PRODUCTION_ENV_FILE}" identity)"
        [ "${current_cid}" = "${identity_cid}" ] \
            || ow_fail "live Identity container changed during portal ownership repair"
    fi

    ownership_mismatch="$(find "${IDENTITY_UI_DIR}" -xdev \
        \( ! -uid "${runner_uid}" -o ! -gid "${runner_gid}" \) -print -quit)" \
        || ow_fail "repaired Identity UI ownership could not be verified"
    [ -z "${ownership_mismatch}" ] \
        || ow_fail "Identity UI ownership repair did not cover the exact target tree"
    unusable_entry="$(find "${IDENTITY_UI_DIR}" -xdev \
        \( ! -readable -o \( -type d \( ! -writable -o ! -executable \) \) \) -print -quit)" \
        || ow_fail "Identity UI access could not be verified"
    [ -z "${unusable_entry}" ] \
        || ow_fail "Identity UI tree is not readable and directory-writable by the runner"
}

ow_validate_release_sha "${DEPLOY_REF}"
ow_require_command sha256sum
ow_require_command readlink
[ -d "${RELEASE_WORKSPACE}/.git" ] || ow_fail "Actions release workspace is not a git checkout"
[ "$(git -C "${RELEASE_WORKSPACE}" rev-parse HEAD)" = "${DEPLOY_REF}" ] \
    || ow_fail "Actions deploy checkout does not equal the immutable release SHA"
ow_prepare_runtime_dir "${RUNTIME_DIR}"
ow_prepare_lock_file "${LOCK_FILE}"

# CLEAR ANY PREVIOUS REWRITE FIRST, and remove ours on the way out.
#
# The config KEY contains the token, so a second run does not replace the first
# entry — it ADDS one, and git matches the earliest. GITHUB_TOKEN is valid only
# for the run that issued it, so run 1 succeeds and run 2 dies with
# "Authentication failed" with nothing in the repo changed. Observed on astro,
# 2026-08-03; fixed here before it fires.
clear_git_url_rewrites() {
    # Remove only workflow-token rewrites. A dedicated runner may legitimately
    # carry unrelated GitHub URL rewrites, and a deploy must not erase them.
    while IFS= read -r key; do
        [ -z "${key}" ] && continue
        git config --global --unset-all "${key}" 2>/dev/null || true
    done < <(
        git config --global --name-only --get-regexp \
            '^url\.https://x-access-token:.*@github\.com/\.insteadof$' 2>/dev/null || true
    )
}

(
    flock -n 9 || { log "ERROR: another OpenWave host deploy is already running."; exit 75; }

    [ "${UI_ROLLBACK_DIR}" = "${RUNTIME_DIR}/ui-rollbacks/identity" ] \
        || ow_fail "portal rollback bundles must use the runner-owned release namespace"
    for ui_rollback_path in "${RUNTIME_DIR}/ui-rollbacks" "${UI_ROLLBACK_DIR}"; do
        [ ! -L "${ui_rollback_path}" ] || ow_fail "portal rollback directory must not be a symlink"
        if [ ! -e "${ui_rollback_path}" ]; then
            umask 077
            mkdir "${ui_rollback_path}"
        fi
        [ -d "${ui_rollback_path}" ] && [ ! -L "${ui_rollback_path}" ] \
            || ow_fail "portal rollback path is not a safe directory"
        [ "$(stat -c '%u' "${ui_rollback_path}")" = "$(id -u)" ] \
            || ow_fail "portal rollback directory must be owned by the runner account"
        chmod 700 "${ui_rollback_path}"
        [ -r "${ui_rollback_path}" ] && [ -w "${ui_rollback_path}" ] && [ -x "${ui_rollback_path}" ] \
            || ow_fail "portal rollback directory must be private and writable"
    done

    # The rewrite is shared process state in the runner user's ~/.gitconfig.
    # Configure and clear it only while holding the cross-repository lock;
    # otherwise a losing Astro/Identity run can clear the winning run's token
    # while that run is fetching.
    clear_git_url_rewrites
    if [ -n "${DEPLOY_AUTH_TOKEN}" ]; then
        trap clear_git_url_rewrites EXIT
        git config --global url."https://x-access-token:${DEPLOY_AUTH_TOKEN}@github.com/".insteadOf "git@github.com:"
        git config --global url."https://x-access-token:${DEPLOY_AUTH_TOKEN}@github.com/".insteadOf "https://github.com/"
    fi

    log "Host:    $(hostname)"
    log "App dir: ${APP_DIR}"
    log "Ref:     ${DEPLOY_REF}"

    if [ ! -d "${APP_DIR}/.git" ]; then
        # neptune-astro/deploy/hetzner/README.md records that
        # /opt/openwave/openwave-identity is NOT a git checkout — it was
        # assembled by rsync, same as Astro was before 2026-08-02. Converting it
        # is a deliberate one-off that reconciles a tree against history for the
        # first time, not something a deploy should do on its own at 3am.
        log "ERROR: ${APP_DIR} is not a git checkout."
        log ""
        log "It was assembled by rsync and has never been reconciled against history."
        log "Converting it is a one-off with a real diff to review — do it by hand:"
        log ""
        log "  cd /opt/openwave && mv openwave-identity openwave-identity.rsync-backup"
        log "  git clone https://github.com/neptune-ly/openwave-identity.git"
        log "  diff -r openwave-identity.rsync-backup openwave-identity   # review before deleting"
        exit 1
    fi

    cd "${APP_DIR}"

    ROLLBACK_SHA="$(git rev-parse HEAD)"
    ROLLBACK_SHORT_SHA="${ROLLBACK_SHA:0:12}"
    log "Currently deployed: ${ROLLBACK_SHORT_SHA} — rollback target if this fails."

    # A dirty tree may hold live changes that exist in no commit anywhere.
    # Checking out over them is a decision for a person, not a script.
    DIRTY="$(git status --porcelain | sed -n '1,40p')"
    if [ -n "${DIRTY}" ] && [ "${ALLOW_DIRTY_TREE}" != "1" ]; then
        DIRTY_COUNT="$(git status --porcelain | wc -l | tr -d ' ')"
        log "REFUSING TO DEPLOY: the working tree has ${DIRTY_COUNT} local change(s)."
        log ""
        log "${DIRTY}"
        [ "${DIRTY_COUNT}" -gt 40 ] && log "  ... and $((DIRTY_COUNT - 40)) more"
        log ""
        log "  Inspect:  cd ${APP_DIR} && git status && git diff"
        log "  Keep:     git stash push -u -m 'pre-deploy $(date +%F)'   (recoverable)"
        log "  Proceed anyway, having looked:  ALLOW_DIRTY_TREE=1 <re-run>"
        exit 1
    fi
    production_checkout_sha="$(git rev-parse HEAD)"
    cd "${COMPOSE_DIR}"
    ow_verify_common_evidence identity "${DEPLOY_REF}" "${EVIDENCE_DIR}" "${BACKUP_DIR}" "${PREFLIGHT_ATTESTATION_KEY_FILE}" "${PRODUCTION_ENV_FILE}" "${COMPOSE_DIR}/${COMPOSE_FILE}" "${EVIDENCE_MAX_AGE_SECONDS}"
    ow_require_evidence_value "${OW_VERIFIED_EVIDENCE_FILE}" production_checkout_sha "${production_checkout_sha}"
    cd "${APP_DIR}"

    # Prove rollback allocation and capture the complete live portal before the
    # production checkout moves. A filesystem or permission failure must leave
    # both the checkout and running release untouched.
    if ! command -v rsync >/dev/null 2>&1; then
        log "ERROR: rsync is required to back up and publish the host-mounted Identity portal."
        exit 1
    fi
    case "${IDENTITY_UI_DIR}" in
        /opt/openwave/ui/identity) ;;
        *)
            log "ERROR: refusing an unexpected Identity UI target: ${IDENTITY_UI_DIR}"
            exit 1
            ;;
    esac
    [ -d "${IDENTITY_UI_DIR}" ] && [ ! -L "${IDENTITY_UI_DIR}" ] \
        || ow_fail "Identity UI target must be provisioned as a real directory"
    ensure_identity_ui_runner_ownership
    ui_public_origin="$(python3 - "${PUBLIC_UI_URL}" <<'PY'
import sys
from urllib.parse import urlsplit

url = urlsplit(sys.argv[1])
if url.scheme not in {'http', 'https'} or not url.netloc or url.username or url.password:
    raise SystemExit('invalid public portal URL')
print(f'{url.scheme}://{url.netloc}')
PY
)"
    [[ "${UI_ROLLBACK_KEEP}" =~ ^[1-9][0-9]*$ ]] || ow_fail "UI rollback retention must be a positive integer"
    ui_backup_pending="$(mktemp -d "${UI_ROLLBACK_DIR}/.identity.rollback-pending-${ROLLBACK_SHA}.XXXXXX")"
    cleanup_pending_ui_backup() {
        [ -n "${ui_backup_pending:-}" ] || return 0
        case "${ui_backup_pending}" in
            "${UI_ROLLBACK_DIR}/.identity.rollback-pending-${ROLLBACK_SHA}."*)
                rm -rf -- "${ui_backup_pending}"
                ui_backup_pending=""
                ;;
            *)
                log "CRITICAL: refusing to remove an unexpected pending portal backup path."
                return 1
                ;;
        esac
    }
    trap 'clear_git_url_rewrites; cleanup_pending_ui_backup' EXIT
    if ! rsync -a --delete "${IDENTITY_UI_DIR}/" "${ui_backup_pending}/"; then
        cleanup_pending_ui_backup || true
        log "ERROR: could not create a complete portal rollback bundle."
        exit 1
    fi
    ui_backup_dir="${UI_ROLLBACK_DIR}/identity.rollback-${ROLLBACK_SHA}.${ui_backup_pending##*.}"
    if ! mv -- "${ui_backup_pending}" "${ui_backup_dir}"; then
        cleanup_pending_ui_backup || true
        log "ERROR: could not finalize the complete portal rollback bundle."
        exit 1
    fi
    ui_backup_pending=""
    log "Previous portal bundle retained at ${ui_backup_dir}."

    restore_ui_bundle() {
        log "Restoring the previous portal bundle."
        if ! rsync -a --delete "${ui_backup_dir}/" "${IDENTITY_UI_DIR}/"; then
            log "CRITICAL: automatic portal restoration failed."
            log "Manual restore: rsync -a --delete ${ui_backup_dir}/ ${IDENTITY_UI_DIR}/"
            return 1
        fi
    }

    # Bound retained bundles even when a later release gate fails. The newest
    # complete live copy exists before any older copy is removed.
    python3 - "${UI_ROLLBACK_DIR}" "${UI_ROLLBACK_KEEP}" <<'PY'
import pathlib, re, shutil, sys
parent = pathlib.Path(sys.argv[1]).resolve()
keep = int(sys.argv[2])
pattern = re.compile(r"identity\.rollback-[0-9a-f]{7,40}\.[A-Za-z0-9]{6}$")
bundles = sorted((p for p in parent.iterdir() if p.is_dir() and not p.is_symlink() and pattern.fullmatch(p.name)), key=lambda p: p.stat().st_mtime, reverse=True)
for bundle in bundles[keep:]:
    if bundle.parent.resolve() != parent:
        raise SystemExit("unsafe rollback bundle parent")
    shutil.rmtree(bundle)
PY

    git fetch --all --prune --tags
    # RESOLVE THE REMOTE REF, NOT A LOCAL BRANCH OF THE SAME NAME.
    #
    # `git fetch` advances `origin/main`; it does NOT advance a local branch
    # called `main`. So on a host that has one, `git checkout --detach main`
    # detaches at whatever that branch pointed to whenever it was last touched —
    # the deploy fetches correctly, rebuilds, passes its health check and ships
    # nothing.
    #
    # Not hypothetical: this exact line did that to the astro deploy on
    # 2026-08-03, twice, before anyone noticed the SHA on the box had not moved.
    # Fixed here before it fires.
    RESOLVED_REF="${DEPLOY_REF}"
    if git rev-parse --verify -q "origin/${DEPLOY_REF}" >/dev/null 2>&1; then
        RESOLVED_REF="origin/${DEPLOY_REF}"
    fi

    if [ "${RESOLVED_REF}" != "${DEPLOY_REF}" ] \
        && [ "$(git rev-parse -q "${DEPLOY_REF}" 2>/dev/null)" != "$(git rev-parse -q "${RESOLVED_REF}")" ]; then
        log "NOTE: local '${DEPLOY_REF}' is behind '${RESOLVED_REF}' — deploying the remote ref."
    fi

    git checkout --detach "${RESOLVED_REF}"
    log "Deploying: $(git rev-parse --short HEAD) ($(git log -1 --pretty='%s'))"

    # Caddy serves the Identity portal from a host directory outside the
    # container. The live bundle is already backed up; now validate that the
    # checked-out replacement is a complete, exact release.
    ui_source="${APP_DIR}/src/main/resources/static"
    if [ ! -f "${ui_source}/index.html" ]; then
        log "ERROR: the release contains no built Identity portal at ${ui_source}."
        exit 1
    fi
    ui_source_drift="$(git -C "${APP_DIR}" status --porcelain --untracked-files=all -- src/main/resources/static)"
    ui_source_ignored="$(git -C "${APP_DIR}" ls-files --others --ignored --exclude-standard -- src/main/resources/static)"
    if [ -n "${ui_source_drift}" ] || [ -n "${ui_source_ignored}" ]; then
        log "ERROR: refusing to publish a portal bundle that differs from the checked-out release."
        log "       Reconcile ${ui_source} and deploy an exact remote ref."
        exit 1
    fi
    ui_asset_list="$(mktemp)"
    if ! python3 "${APP_DIR}/scripts/list-static-entry-assets.py" "${ui_source}/index.html" >"${ui_asset_list}"; then
        rm -f "${ui_asset_list}"
        ow_fail "portal index contains an unsafe release asset path"
    fi
    mapfile -t ui_asset_paths <"${ui_asset_list}"
    rm -f "${ui_asset_list}"
    [ "${#ui_asset_paths[@]}" -gt 0 ] || ow_fail "portal index declares no release assets"
    for asset_path in "${ui_asset_paths[@]}"; do
        [ -f "${ui_source}${asset_path}" ] && [ ! -L "${ui_source}${asset_path}" ] \
            || ow_fail "portal index references a missing or unsafe release asset"
    done
    cd "${COMPOSE_DIR}"
    export OPENWAVE_IDENTITY_RELEASE_SHA="${DEPLOY_REF}"
    # Recheck the migration state while holding the deploy lock. A preflight
    # result alone is not enough: live schema state could change before click.
    eval "$(python3 - "${PRODUCTION_ENV_FILE}" <<'PY'
import pathlib, shlex, re, sys
for raw in pathlib.Path(sys.argv[1]).read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith('#'): continue
    key, sep, value = line.partition('=')
    if not sep or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key): raise SystemExit('unsafe production dotenv syntax')
    if key in {'IDENTITY_DB_HOST','IDENTITY_DB_PORT','IDENTITY_DB_NAME','IDENTITY_DB_USER','IDENTITY_DB_PASSWORD'}: print(f'export {key}={shlex.quote(value)}')
PY
)"
    pgpass_file="$(mktemp)"; chmod 600 "${pgpass_file}"
    printf '%s:%s:*:%s:%s\n' "${IDENTITY_DB_HOST}" "${IDENTITY_DB_PORT:-5432}" "${IDENTITY_DB_USER}" "${IDENTITY_DB_PASSWORD}" >"${pgpass_file}"
    trap 'clear_git_url_rewrites; cleanup_pending_ui_backup; rm -f "${pgpass_file}" "${ui_asset_probe:-}"' EXIT
    v18_count="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM flyway_schema_history WHERE version = '18'")"
    v18_meta="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT checksum::text || '|' || CASE WHEN success THEN 't' ELSE 'f' END FROM flyway_schema_history WHERE version = '18'")"
    v18_objects="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM (SELECT 1 FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 1 FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    v18_fingerprint="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT md5(coalesce(string_agg(item, '|' ORDER BY item), '')) FROM (SELECT 'table:' || relname AS item FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 'column:' || column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 'index:' || indexname || ':' || indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 'constraint:' || conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    v18_row_fingerprint="$(printf '%s' "${v18_meta}" | sha256sum | awk '{print $1}')"
    v18_source="${APP_DIR}/src/main/resources/db/migration/V18__handle_rename_and_retirement.sql"
    v18_checksum_tool="${APP_DIR}/scripts/flyway-sql-checksum.py"
    [ -f "${v18_source}" ] && [ ! -L "${v18_source}" ] || ow_fail "reviewed V18 migration source is missing or unsafe"
    [ -f "${v18_checksum_tool}" ] && [ ! -L "${v18_checksum_tool}" ] || ow_fail "reviewed Flyway checksum tool is missing or unsafe"
    v18_source_sha256="$(ow_sha256_file "${v18_source}")"
    expected_v18_checksum="$(python3 "${v18_checksum_tool}" "${v18_source}")"
    [[ "${expected_v18_checksum}" =~ ^-?[0-9]+$ ]] || ow_fail "reviewed V18 Flyway checksum is invalid"
    v18_gate="$(ow_evidence_field "${OW_VERIFIED_EVIDENCE_FILE}" v18_gate)"
    case "${v18_gate}" in
        first_deploy_absent) [ "${v18_count}" = 0 ] && [ "${v18_objects}" = 0 ] || ow_fail "V18 state changed after preflight" ;;
        subsequent_receipted)
            [ "${v18_count}" = 1 ] && [ "${v18_objects}" = 8 ] && [[ "${v18_meta}" =~ ^-?[0-9]+\|t$ ]] || ow_fail "V18 success receipt no longer matches live schema"
            [ "${v18_meta%%|*}" = "${expected_v18_checksum}" ] || ow_fail "live V18 Flyway checksum does not match the reviewed migration source"
            receipt="${EVIDENCE_DIR}/v18-success.receipt"
            ow_verify_evidence_signature "${PREFLIGHT_ATTESTATION_KEY_FILE}" "${receipt}" "${receipt}.sig"
            ow_require_evidence_value "${receipt}" flyway_checksum "${v18_meta%%|*}"
            ow_require_evidence_value "${receipt}" v18_row_fingerprint "${v18_row_fingerprint}"
            ow_require_evidence_value "${receipt}" object_fingerprint "${v18_fingerprint}"
            ow_require_evidence_value "${receipt}" source_migration_sha256 "${v18_source_sha256}"
            receipt_release_sha="$(ow_evidence_field "${receipt}" release_sha)"
            receipt_checkout_sha="$(ow_evidence_field "${receipt}" production_checkout_sha)"
            receipt_oci_revision="$(ow_evidence_field "${receipt}" oci_revision)"
            ow_validate_release_sha "${receipt_release_sha}"
            [ "${receipt_checkout_sha}" = "${receipt_release_sha}" ] && [ "${receipt_oci_revision}" = "${receipt_release_sha}" ] \
                || ow_fail "V18 receipt release, checkout, and OCI revision do not agree"
            ;;
        *) ow_fail "unknown V18 preflight gate" ;;
    esac

    # Only the identity service. Rebuilding everything would restart the Astro
    # gateway and Caddy for a change that touches neither.
    # Build explicitly with the OCI revision argument. This works with both the
    # current Astro compose file and the older production compose file that did
    # not yet declare `build.args`, so Identity can deploy safely first.
    docker compose -f "${COMPOSE_FILE}" build --build-arg "VCS_REF=${DEPLOY_REF}" identity
    docker compose -f "${COMPOSE_FILE}" up -d identity
    docker compose -f "${COMPOSE_FILE}" ps identity

    log "Waiting for the container health check..."
    cid="$(docker compose -f "${COMPOSE_FILE}" ps -q identity)"
    if [ -z "${cid}" ]; then
        log "ERROR: compose returned no Identity container id."
        docker compose -f "${COMPOSE_FILE}" ps identity
        exit 1
    fi
    healthy=0
    for _ in $(seq 1 60); do
        state="$(docker inspect -f '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo unknown)"
        if [ "${state}" = "healthy" ]; then healthy=1; break; fi
        if [ "${state}" = "unhealthy" ]; then
            log "ERROR: container reported UNHEALTHY."
            docker compose -f "${COMPOSE_FILE}" logs --tail=200 identity
            log "Roll back: $(rollback_recipe)"
            exit 1
        fi
        if [ "${state}" = "unknown" ] || [ "${state}" = "<no value>" ]; then
            log "ERROR: Identity has no readable container health state."
            docker compose -f "${COMPOSE_FILE}" logs --tail=200 identity
            exit 1
        fi
        sleep 5
    done

    if [ "${healthy}" -ne 1 ]; then
        log "ERROR: container never became healthy."
        docker compose -f "${COMPOSE_FILE}" logs --tail=200 identity
        log "Roll back: $(rollback_recipe)"
        exit 1
    fi
    # The first V18 receipt is meaningful only after the new container has
    # started and Flyway has actually applied the migration. Re-query the live
    # database here rather than carrying pre-deploy observations forward.
    post_v18_count="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM flyway_schema_history WHERE version = '18'")"
    post_v18_meta="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT checksum::text || '|' || CASE WHEN success THEN 't' ELSE 'f' END FROM flyway_schema_history WHERE version = '18'")"
    post_v18_objects="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM (SELECT 1 FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 1 FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    post_v18_fingerprint="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT md5(coalesce(string_agg(item, '|' ORDER BY item), '')) FROM (SELECT 'table:' || relname AS item FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 'column:' || column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 'index:' || indexname || ':' || indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 'constraint:' || conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    post_v18_row_fingerprint="$(printf '%s' "${post_v18_meta}" | sha256sum | awk '{print $1}')"
    [ "${post_v18_count}" = 1 ] && [ "${post_v18_objects}" = 8 ] && [[ "${post_v18_meta}" =~ ^-?[0-9]+\|t$ ]] \
        && [ "${post_v18_meta%%|*}" = "${expected_v18_checksum}" ] \
        || ow_fail "post-start Identity V18 state is not one successful complete migration"
    [ "$(docker inspect -f '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "${cid}")" = "${DEPLOY_REF}" ] \
        || ow_fail "Identity container revision label does not equal the signed release SHA"

    # Record the irreversible schema transition as soon as the new container is
    # healthy and its exact revision has been proven.  Waiting until after the
    # portal copy would leave a successfully-applied V18 migration without a
    # receipt if that independent static-file operation failed, permanently
    # blocking the next preflight behind the first-deploy gate.
    if [ "${v18_gate}" = first_deploy_absent ]; then
        receipt="${EVIDENCE_DIR}/v18-success.receipt"
        umask 077
        flyway_checksum="${post_v18_meta%%|*}"
        printf 'v18_success=true\nrelease_sha=%s\nrecovered_from_failed_deploy=false\nrecovery_reason=none\nrecorded_at_epoch=%s\nproduction_checkout_sha=%s\noci_revision=%s\nflyway_checksum=%s\nv18_row_fingerprint=%s\nsource_migration_sha256=%s\nobject_fingerprint=%s\n' \
            "${DEPLOY_REF}" "$(date +%s)" "${DEPLOY_REF}" "${DEPLOY_REF}" "${flyway_checksum}" "${post_v18_row_fingerprint}" "${v18_source_sha256}" "${post_v18_fingerprint}" >"${receipt}"
        ow_sign_evidence "${PREFLIGHT_ATTESTATION_KEY_FILE}" "${receipt}" "${receipt}.sig"
        log "Recorded signed V18 success receipt for subsequent deploy gates."
    fi

    # THE CHECK THAT WOULD HAVE CAUGHT 2026-08-01.
    url="${PUBLIC_BASE_URL%/}/identity/resolve?alias=${PROBE_HANDLE}&purpose=payment"
    log "Probing resolution: ${url}"
    code="$(curl -s -o /tmp/identity-probe.txt -w '%{http_code}' --max-time 25 "${url}" || echo 000)"
    log "HTTP ${code}"
    head -c 400 /tmp/identity-probe.txt 2>/dev/null || true
    echo

    case "${code}" in
        404|200)
            log "Resolution is answering — deploy verified."
            ;;
        5*)
            log "ERROR: resolution returned ${code}. This is the 2026-08-01 signature:"
            log "       the container is up and the surface is broken."
            log "Roll back: $(rollback_recipe)"
            exit 1
            ;;
        *)
            log "ERROR: unexpected response ${code} from the resolution probe."
            exit 1
            ;;
    esac

    # Publish only after the new backend has passed its functional probe so a
    # failed backend deploy cannot advance the visible portal by itself.
    log "Publishing the verified Identity portal to ${IDENTITY_UI_DIR}."
    if ! rsync -a --delete "${ui_source}/" "${IDENTITY_UI_DIR}/"; then
        log "ERROR: static portal sync failed before verification."
        restore_ui_bundle || true
        log "Backend rollback: $(rollback_recipe)"
        exit 1
    fi

    ui_code="$(curl -s -o /tmp/identity-ui-probe.txt -w '%{http_code}' --max-time 25 "${PUBLIC_UI_URL}" || echo 000)"
    log "Portal HTTP ${ui_code}"
    case "${ui_code}" in
        200)
            if cmp -s "${ui_source}/index.html" /tmp/identity-ui-probe.txt; then
                log "Identity portal is serving the exact release bundle — UI deploy verified."
            else
                log "ERROR: Identity portal answered but did not serve the release index."
                restore_ui_bundle || true
                log "Backend rollback: $(rollback_recipe)"
                exit 1
            fi
            ui_asset_probe="$(mktemp)"
            for asset_path in "${ui_asset_paths[@]}"; do
                if ! curl -fsS --max-time 25 -o "${ui_asset_probe}" "${ui_public_origin}${asset_path}" \
                    || ! cmp -s "${ui_source}${asset_path}" "${ui_asset_probe}"; then
                    log "ERROR: Identity portal asset ${asset_path} is missing or differs from the release."
                    restore_ui_bundle || true
                    log "Backend rollback: $(rollback_recipe)"
                    exit 1
                fi
            done
            rm -f "${ui_asset_probe}"
            ui_asset_probe=""
            log "Identity portal entry assets are publicly serving the exact release bytes."
            ;;
        *)
            log "ERROR: Identity portal returned ${ui_code} after static sync."
            restore_ui_bundle || true
            log "Backend rollback: $(rollback_recipe)"
            exit 1
            ;;
    esac

    log "Deployed $(cd "${APP_DIR}" && git rev-parse --short HEAD). Previous was ${ROLLBACK_SHORT_SHA}."
) 9>"${LOCK_FILE}"
