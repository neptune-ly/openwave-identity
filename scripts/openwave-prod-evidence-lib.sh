#!/usr/bin/env bash

# Shared, secret-safe primitives for OpenWave production preflight and deploy.
# This file is sourced by scripts that already enable `set -euo pipefail`.

ow_log() {
    printf '[openwave-evidence] %s\n' "$*"
}

ow_fail() {
    printf '[openwave-evidence] ERROR: %s\n' "$*" >&2
    exit 1
}

ow_require_command() {
    command -v "$1" >/dev/null 2>&1 || ow_fail "required command is unavailable: $1"
}

ow_validate_release_sha() {
    [[ "$1" =~ ^[0-9a-f]{40}$ ]] \
        || ow_fail "release SHA must be exactly 40 lowercase hexadecimal characters"
}

ow_sha256_file() {
    sha256sum "$1" | awk '{print $1}'
}

ow_require_secret_file() {
    local path="$1"
    local label="$2"
    local mode owner current

    [ -f "${path}" ] || ow_fail "${label} is required at ${path}; create a dedicated secret and never reuse an application encryption/JWT/database key"
    [ ! -L "${path}" ] || ow_fail "${label} must not be a symlink"
    [ -r "${path}" ] || ow_fail "${label} is not readable by the runner account"
    mode="$(stat -c '%a' "${path}")"
    case "${mode}" in
        400|600) ;;
        *) ow_fail "${label} must have mode 400 or 600" ;;
    esac
    owner="$(stat -c '%u' "${path}")"
    current="$(id -u)"
    [ "${owner}" = "${current}" ] || ow_fail "${label} must be owned by the runner account"
}

ow_prepare_runtime_dir() {
    local runtime_dir="$1"
    local expected parent owner current mode

    expected="/opt/openwave/neptune-astro/deploy/hetzner/.env.openwave-release"
    [ "${runtime_dir}" = "${expected}" ] \
        || ow_fail "production runtime data must use the shared ignored compose namespace"
    parent="$(dirname "${runtime_dir}")"
    [ -d "${parent}" ] && [ ! -L "${parent}" ] && [ -w "${parent}" ] \
        || ow_fail "runner-owned compose directory is missing or unsafe"
    [ ! -L "${runtime_dir}" ] || ow_fail "production runtime directory must not be a symlink"

    if [ ! -e "${runtime_dir}" ]; then
        umask 077
        mkdir "${runtime_dir}"
    fi
    [ -d "${runtime_dir}" ] && [ ! -L "${runtime_dir}" ] \
        || ow_fail "production runtime path is not a safe directory"
    owner="$(stat -c '%u' "${runtime_dir}")"
    current="$(id -u)"
    [ "${owner}" = "${current}" ] \
        || ow_fail "production runtime directory must be owned by the runner account"
    chmod 700 "${runtime_dir}"
    mode="$(stat -c '%a' "${runtime_dir}")"
    [ "${mode}" = 700 ] && [ -r "${runtime_dir}" ] && [ -w "${runtime_dir}" ] && [ -x "${runtime_dir}" ] \
        || ow_fail "production runtime directory must be private and writable"
}

ow_prepare_lock_file() {
    local lock_file="$1"
    local lock_dir file_mode

    [ "${lock_file}" = "/opt/openwave/neptune-astro/deploy/hetzner/.openwave-prod-deploy.lock" ] \
        || ow_fail "production lock must use the shared persistent compose namespace"
    lock_dir="$(dirname "${lock_file}")"
    [ -d "${lock_dir}" ] && [ ! -L "${lock_dir}" ] \
        || ow_fail "production lock directory is missing or unsafe"
    [ -w "${lock_dir}" ] || ow_fail "runner cannot write the shared production lock directory"
    [ ! -L "${lock_file}" ] || ow_fail "production lock file must not be a symlink"

    if [ ! -e "${lock_file}" ]; then
        umask 007
        : >>"${lock_file}"
        chmod 660 "${lock_file}"
    fi
    [ -f "${lock_file}" ] || ow_fail "production lock path is not a regular file"
    [ ! -L "${lock_file}" ] || ow_fail "production lock file became a symlink"
    file_mode="$(stat -c '%a' "${lock_file}")"
    case "${file_mode}" in
        600|660) ;;
        *) ow_fail "production lock file must have mode 600 or 660" ;;
    esac
    [ -r "${lock_file}" ] && [ -w "${lock_file}" ] \
        || ow_fail "runner cannot share the persistent production lock; align runner user/group permissions"
}

ow_verify_dedicated_secret_material() {
    local backup_key_file="$1"
    local attestation_key_file="$2"
    local compose_json="$3"

    python3 - "${backup_key_file}" "${attestation_key_file}" "${compose_json}" <<'PY'
import json
import pathlib
import sys

backup_path, attestation_path, compose_path = map(pathlib.Path, sys.argv[1:])

def one_line_secret(path: pathlib.Path) -> str:
    raw = path.read_text(encoding="utf-8")
    lines = raw.splitlines()
    if len(lines) != 1 or len(lines[0]) < 32 or lines[0].strip() != lines[0]:
        raise SystemExit("dedicated secret files must contain exactly one trimmed line of at least 32 characters")
    return lines[0]

backup = one_line_secret(backup_path)
attestation = one_line_secret(attestation_path)
if backup == attestation:
    raise SystemExit("backup encryption and preflight attestation secrets must be distinct")

config = json.loads(compose_path.read_text(encoding="utf-8"))
application_secrets = set()
for service in config.get("services", {}).values():
    environment = service.get("environment", {}) or {}
    for name, value in environment.items():
        if value is None:
            continue
        upper = name.upper()
        if any(marker in upper for marker in ("PASSWORD", "SECRET", "_KEY", "TOKEN")):
            text = str(value)
            if text:
                application_secrets.add(text)
        if name == "IDENTITY_REGISTRY_BANK_KEYS_JSON":
            try:
                parsed = json.loads(str(value))
                if isinstance(parsed, dict):
                    application_secrets.update(str(item) for item in parsed.values() if str(item))
            except json.JSONDecodeError:
                pass

if backup in application_secrets or attestation in application_secrets:
    raise SystemExit("dedicated preflight secret material matches an application/database credential")
PY
}

ow_sign_evidence() {
    local key_file="$1"
    local evidence_file="$2"
    local signature_file="$3"

    python3 - "${key_file}" "${evidence_file}" "${signature_file}" <<'PY'
import hashlib
import hmac
import pathlib
import sys

key_path, evidence_path, signature_path = map(pathlib.Path, sys.argv[1:])
key = key_path.read_bytes().rstrip(b"\r\n")
message = evidence_path.read_bytes()
signature_path.write_text(hmac.new(key, message, hashlib.sha256).hexdigest() + "\n", encoding="ascii")
signature_path.chmod(0o600)
PY
}

ow_verify_evidence_signature() {
    local key_file="$1"
    local evidence_file="$2"
    local signature_file="$3"

    python3 - "${key_file}" "${evidence_file}" "${signature_file}" <<'PY'
import hashlib
import hmac
import pathlib
import sys

key_path, evidence_path, signature_path = map(pathlib.Path, sys.argv[1:])
key = key_path.read_bytes().rstrip(b"\r\n")
expected = hmac.new(key, evidence_path.read_bytes(), hashlib.sha256).hexdigest()
presented = signature_path.read_text(encoding="ascii").strip()
if not hmac.compare_digest(expected, presented):
    raise SystemExit("preflight evidence signature mismatch")
PY
}

ow_evidence_field() {
    local evidence_file="$1"
    local field="$2"

    awk -F= -v wanted="${field}" '
        $1 == wanted {
            count += 1
            value = substr($0, length($1) + 2)
        }
        END {
            if (count != 1) exit 1
            print value
        }
    ' "${evidence_file}"
}

ow_verify_common_evidence() {
    local service="$1"
    local release_sha="$2"
    local evidence_dir="$3"
    local backup_dir="$4"
    local attestation_key_file="$5"
    local production_env_file="$6"
    local compose_file="$7"
    local max_age_seconds="$8"
    local evidence_file signature_file created now age backup_file backup_path
    local expected actual

    ow_validate_release_sha "${release_sha}"
    ow_require_secret_file "${attestation_key_file}" "preflight attestation key"
    [[ "${max_age_seconds}" =~ ^[0-9]+$ ]] || ow_fail "preflight evidence TTL must be numeric"

    evidence_file="${evidence_dir}/${release_sha}.attestation"
    signature_file="${evidence_file}.sig"
    [ -f "${evidence_file}" ] && [ ! -L "${evidence_file}" ] \
        || ow_fail "signed preflight evidence is missing for release ${release_sha}"
    [ -f "${signature_file}" ] && [ ! -L "${signature_file}" ] \
        || ow_fail "preflight evidence signature is missing for release ${release_sha}"
    ow_verify_evidence_signature "${attestation_key_file}" "${evidence_file}" "${signature_file}" \
        || ow_fail "preflight evidence signature is invalid"

    [ "$(ow_evidence_field "${evidence_file}" evidence_version)" = "1" ] \
        || ow_fail "unsupported preflight evidence version"
    [ "$(ow_evidence_field "${evidence_file}" service)" = "${service}" ] \
        || ow_fail "preflight evidence service mismatch"
    [ "$(ow_evidence_field "${evidence_file}" release_sha)" = "${release_sha}" ] \
        || ow_fail "preflight evidence release SHA mismatch"
    [ "$(ow_evidence_field "${evidence_file}" backup_restore_verified)" = "true" ] \
        || ow_fail "preflight evidence does not prove a restored backup"
    [ "$(ow_evidence_field "${evidence_file}" production_checkout_clean)" = "true" ] \
        || ow_fail "preflight evidence does not prove a clean production checkout"
    [ "$(ow_evidence_field "${evidence_file}" lock_namespace_verified)" = "persistent_compose_path" ] \
        || ow_fail "preflight evidence does not prove the shared lock namespace"
    [ "$(ow_evidence_field "${evidence_file}" caddy_running)" = "true" ] \
        || ow_fail "preflight evidence does not prove Caddy was serving"

    created="$(ow_evidence_field "${evidence_file}" created_at_epoch)"
    [[ "${created}" =~ ^[0-9]+$ ]] || ow_fail "preflight evidence timestamp is invalid"
    now="$(date +%s)"
    [ "${created}" -le "$((now + 60))" ] || ow_fail "preflight evidence timestamp is in the future"
    age="$((now - created))"
    [ "${age}" -le "${max_age_seconds}" ] || ow_fail "preflight evidence is stale; run production preflight again"

    [ -f "${production_env_file}" ] && [ ! -L "${production_env_file}" ] \
        || ow_fail "production environment file is unavailable"
    [ -f "${compose_file}" ] && [ ! -L "${compose_file}" ] \
        || ow_fail "production compose file is unavailable"
    expected="$(ow_evidence_field "${evidence_file}" production_env_sha256)"
    actual="$(ow_sha256_file "${production_env_file}")"
    [ "${expected}" = "${actual}" ] || ow_fail "production environment changed after preflight"
    expected="$(ow_evidence_field "${evidence_file}" compose_sha256)"
    actual="$(ow_sha256_file "${compose_file}")"
    [ "${expected}" = "${actual}" ] || ow_fail "production compose topology changed after preflight"

    backup_file="$(ow_evidence_field "${evidence_file}" backup_file)"
    [[ "${backup_file}" =~ ^${service}-${release_sha}-[0-9]+\.(dump|sql)\.enc$ ]] \
        || ow_fail "preflight backup filename is invalid"
    backup_path="${backup_dir}/${backup_file}"
    [ -f "${backup_path}" ] && [ ! -L "${backup_path}" ] \
        || ow_fail "encrypted preflight backup is missing"
    expected="$(ow_evidence_field "${evidence_file}" backup_sha256)"
    actual="$(ow_sha256_file "${backup_path}")"
    [ "${expected}" = "${actual}" ] || ow_fail "encrypted preflight backup digest mismatch"

    OW_VERIFIED_EVIDENCE_FILE="${evidence_file}"
    export OW_VERIFIED_EVIDENCE_FILE
}

ow_require_evidence_value() {
    local evidence_file="$1" field="$2" expected="$3"
    [ "$(ow_evidence_field "${evidence_file}" "${field}")" = "${expected}" ] \
        || ow_fail "preflight evidence field ${field} does not match the current production assertion"
}
