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

ow_validate_release_sha "${DEPLOY_REF}"
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

    # Caddy serves the Identity portal from this host directory, outside the
    # container. Validate the exact release bundle and take its rollback backup
    # before replacing the backend; a UI preflight failure must leave production
    # entirely on the previous release.
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
    ui_public_origin="$(python3 - "${PUBLIC_UI_URL}" <<'PY'
import sys
from urllib.parse import urlsplit

url = urlsplit(sys.argv[1])
if url.scheme not in {'http', 'https'} or not url.netloc or url.username or url.password:
    raise SystemExit('invalid public portal URL')
print(f'{url.scheme}://{url.netloc}')
PY
)"
    if ! command -v rsync >/dev/null 2>&1; then
        log "ERROR: rsync is required to publish the host-mounted Identity portal."
        exit 1
    fi
    case "${IDENTITY_UI_DIR}" in
        /opt/openwave/ui/identity) ;;
        *)
            log "ERROR: refusing an unexpected Identity UI target: ${IDENTITY_UI_DIR}"
            exit 1
            ;;
    esac
    mkdir -p "${IDENTITY_UI_DIR}"
    ui_backup_dir="$(mktemp -d "${IDENTITY_UI_DIR}.rollback-${ROLLBACK_SHA}.XXXXXX")"
    if ! rsync -a --delete "${IDENTITY_UI_DIR}/" "${ui_backup_dir}/"; then
        log "ERROR: could not create a complete portal rollback bundle."
        exit 1
    fi
    log "Previous portal bundle retained at ${ui_backup_dir}."

    restore_ui_bundle() {
        log "Restoring the previous portal bundle."
        if ! rsync -a --delete "${ui_backup_dir}/" "${IDENTITY_UI_DIR}/"; then
            log "CRITICAL: automatic portal restoration failed."
            log "Manual restore: rsync -a --delete ${ui_backup_dir}/ ${IDENTITY_UI_DIR}/"
            return 1
        fi
    }

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
    trap 'clear_git_url_rewrites; rm -f "${pgpass_file}" "${ui_asset_probe:-}"' EXIT
    v18_count="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM flyway_schema_history WHERE version = '18'")"
    v18_meta="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT checksum || '|' || success FROM flyway_schema_history WHERE version = '18'")"
    v18_objects="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM (SELECT 1 FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 1 FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    v18_fingerprint="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT md5(coalesce(string_agg(item, '|' ORDER BY item), '')) FROM (SELECT 'table:' || relname FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 'column:' || column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 'index:' || indexname || ':' || indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 'constraint:' || conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    v18_gate="$(ow_evidence_field "${OW_VERIFIED_EVIDENCE_FILE}" v18_gate)"
    case "${v18_gate}" in
        first_deploy_absent) [ "${v18_count}" = 0 ] && [ "${v18_objects}" = 0 ] || ow_fail "V18 state changed after preflight" ;;
        subsequent_receipted)
            [ "${v18_count}" = 1 ] && [ "${v18_objects}" = 8 ] && [[ "${v18_meta}" =~ ^-?[0-9]+\|t$ ]] || ow_fail "V18 success receipt no longer matches live schema"
            receipt="${EVIDENCE_DIR}/v18-success.receipt"
            ow_verify_evidence_signature "${PREFLIGHT_ATTESTATION_KEY_FILE}" "${receipt}" "${receipt}.sig"
            ow_require_evidence_value "${receipt}" flyway_checksum "${v18_meta%%|*}"
            ow_require_evidence_value "${receipt}" object_fingerprint "${v18_fingerprint}"
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
    post_v18_meta="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT checksum || '|' || success FROM flyway_schema_history WHERE version = '18'")"
    post_v18_objects="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT count(*) FROM (SELECT 1 FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 1 FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 1 FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    post_v18_fingerprint="$(pg_client psql -h "${IDENTITY_DB_HOST}" -p "${IDENTITY_DB_PORT:-5432}" -U "${IDENTITY_DB_USER}" -d "${IDENTITY_DB_NAME}" -Atqc "SELECT md5(coalesce(string_agg(item, '|' ORDER BY item), '')) FROM (SELECT 'table:' || relname FROM pg_class WHERE oid=to_regclass('public.retired_handles') UNION ALL SELECT 'column:' || column_name || ':' || data_type || ':' || is_nullable FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('retired_handles','npt_identities') AND column_name IN ('handle_renamed_at','handle_rename_count','handle','former_identity_id') UNION ALL SELECT 'index:' || indexname || ':' || indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='retired_handles' AND indexname IN ('idx_retired_handles_former_identity','idx_retired_handles_retired_at') UNION ALL SELECT 'constraint:' || conname || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid=to_regclass('public.retired_handles') AND conname='chk_retired_handle_canonical') q")"
    [ "${post_v18_count}" = 1 ] && [ "${post_v18_objects}" = 8 ] && [[ "${post_v18_meta}" =~ ^-?[0-9]+\|t$ ]] \
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
        printf 'v18_success=true\nrelease_sha=%s\nrecorded_at_epoch=%s\nflyway_checksum=%s\nobject_fingerprint=%s\n' "${DEPLOY_REF}" "$(date +%s)" "${flyway_checksum}" "${post_v18_fingerprint}" >"${receipt}"
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

    [[ "${UI_ROLLBACK_KEEP}" =~ ^[1-9][0-9]*$ ]] || ow_fail "UI rollback retention must be a positive integer"
    # Keep a bounded set of complete rollback bundles. Pruning runs only after
    # the public release was proven, and only siblings matching this script's
    # exact generated name under the validated Identity UI parent.
    python3 - "$(dirname "${IDENTITY_UI_DIR}")" "${UI_ROLLBACK_KEEP}" <<'PY'
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

    log "Deployed $(cd "${APP_DIR}" && git rev-parse --short HEAD). Previous was ${ROLLBACK_SHORT_SHA}."
) 9>"${LOCK_FILE}"
