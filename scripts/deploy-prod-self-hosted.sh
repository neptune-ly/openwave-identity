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
COMPOSE_DIR="${COMPOSE_DIR:-/opt/openwave/neptune-astro/deploy/hetzner}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.app.yml}"
DEPLOY_REF="${DEPLOY_REF:-main}"
PROBE_HANDLE="${PROBE_HANDLE:-deploy-probe-does-not-exist}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-https://identity.neptune.ly/v1}"
LOCK_FILE="${LOCK_FILE:-/tmp/openwave-identity-prod-deploy.lock}"
# Set to 1 only after looking at `git status` on the box and deciding the local
# changes are expendable. See the guard below for why this is not the default.
ALLOW_DIRTY_TREE="${ALLOW_DIRTY_TREE:-0}"

log() {
    printf '[identity-deploy] %s\n' "$*"
}

DEPLOY_AUTH_TOKEN="${DEPLOY_GITHUB_TOKEN:-${GITHUB_TOKEN:-}}"
if [ -n "${DEPLOY_AUTH_TOKEN}" ]; then
    git config --global url."https://x-access-token:${DEPLOY_AUTH_TOKEN}@github.com/".insteadOf "git@github.com:"
    git config --global url."https://x-access-token:${DEPLOY_AUTH_TOKEN}@github.com/".insteadOf "https://github.com/"
fi

(
    if command -v flock >/dev/null 2>&1; then
        if ! flock -n 9; then
            log "ERROR: another Identity deploy is already running."
            exit 75
        fi
    else
        log "WARNING: flock unavailable; proceeding without a local lock."
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

    ROLLBACK_SHA="$(git rev-parse --short HEAD)"
    log "Currently deployed: ${ROLLBACK_SHA} — rollback target if this fails."

    # A dirty tree may hold live changes that exist in no commit anywhere.
    # Checking out over them is a decision for a person, not a script.
    DIRTY="$(git status --porcelain | head -40)"
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

    git fetch --all --prune --tags
    git checkout --detach "${DEPLOY_REF}"
    log "Deploying: $(git rev-parse --short HEAD) ($(git log -1 --pretty='%s'))"

    cd "${COMPOSE_DIR}"

    # Only the identity service. Rebuilding everything would restart the Astro
    # gateway and Caddy for a change that touches neither.
    docker compose -f "${COMPOSE_FILE}" up -d --build identity
    docker compose -f "${COMPOSE_FILE}" ps identity

    log "Waiting for the container health check..."
    cid="$(docker compose -f "${COMPOSE_FILE}" ps -q identity)"
    healthy=0
    for _ in $(seq 1 60); do
        state="$(docker inspect -f '{{.State.Health.Status}}' "${cid}" 2>/dev/null || echo unknown)"
        if [ "${state}" = "healthy" ]; then healthy=1; break; fi
        if [ "${state}" = "unhealthy" ]; then
            log "ERROR: container reported UNHEALTHY."
            docker compose -f "${COMPOSE_FILE}" logs --tail=200 identity
            log "Roll back: cd ${APP_DIR} && git checkout --detach ${ROLLBACK_SHA} && cd ${COMPOSE_DIR} && docker compose -f ${COMPOSE_FILE} up -d --build identity"
            exit 1
        fi
        if [ "${state}" = "unknown" ] || [ "${state}" = "<no value>" ]; then healthy=1; break; fi
        sleep 5
    done

    if [ "${healthy}" -ne 1 ]; then
        log "ERROR: container never became healthy."
        docker compose -f "${COMPOSE_FILE}" logs --tail=200 identity
        log "Roll back: cd ${APP_DIR} && git checkout --detach ${ROLLBACK_SHA} && cd ${COMPOSE_DIR} && docker compose -f ${COMPOSE_FILE} up -d --build identity"
        exit 1
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
            log "Roll back: cd ${APP_DIR} && git checkout --detach ${ROLLBACK_SHA} && cd ${COMPOSE_DIR} && docker compose -f ${COMPOSE_FILE} up -d --build identity"
            exit 1
            ;;
        *)
            log "ERROR: unexpected response ${code} from the resolution probe."
            exit 1
            ;;
    esac

    log "Deployed $(cd "${APP_DIR}" && git rev-parse --short HEAD). Previous was ${ROLLBACK_SHA}."
) 9>"${LOCK_FILE}"
