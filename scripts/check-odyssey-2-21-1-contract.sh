#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
app_html="${repo_root}/openwave-ui/src/app.html"
app_css="${repo_root}/openwave-ui/src/app.css"
login="${repo_root}/openwave-ui/src/lib/components/auth/PortalLogin.svelte"
login_chooser="${repo_root}/openwave-ui/src/routes/login/+page.svelte"
customer="${repo_root}/openwave-ui/src/routes/portal/customer/+page.svelte"
rename="${repo_root}/openwave-ui/src/routes/portal/identity/[flow]/+page.svelte"
static_root="${repo_root}/src/main/resources/static"

require_literal() {
    grep -Fq -- "$2" "$1" || {
        printf 'Odyssey 2.21.1 contract failed: %s\n' "$3" >&2
        exit 1
    }
}

require_literal "${app_html}" 'name="openwave-design-contract" content="Odyssey 2.21.1"' 'portal document must declare the audited design contract'
require_literal "${app_css}" '--ow-odyssey-version: "2.21.1"' 'CSS must retain the audited design version token'
require_literal "${app_css}" '--ow-odyssey-target-min: 48px' 'primary actions must have a 48px target token'
require_literal "${app_css}" '.identity-auth-shell :is(button, input, select, textarea)' 'every authentication control must consume the touch-target contract'
require_literal "${app_css}" '.identity-auth-shell button' 'authentication icon buttons must retain a touch-safe width'
require_literal "${app_css}" ':focus-visible' 'keyboard focus must be visibly styled'
require_literal "${app_css}" '[dir='"'"'rtl'"'"'] .identity-direction-icon' 'RTL direction behavior must remain explicit'
require_literal "${app_css}" "[dir='rtl'] .identity-auth-shell :is(input[type='password'], input[inputmode='numeric'], input[autocomplete='one-time-code'])" 'RTL authentication must keep only secret and numeric authentication fields left-to-right'
require_literal "${app_css}" '@media (prefers-reduced-motion: reduce)' 'reduced-motion behavior must remain explicit'
require_literal "${rename}" 'identity-primary-action' 'rename primary actions must consume the 48px target token'
require_literal "${login}" 'class="identity-primary-action w-full"' 'login primary action must consume the 48px target token'
require_literal "${login}" 'aria-live="polite"' 'login approval progress must announce live status'
require_literal "${login}" 'Retry approval status' 'login approval failures must expose a retry action'
require_literal "${login}" 'role="alert"' 'login failures must be visible inline'
require_literal "${login_chooser}" "href: '/login/customer'" 'customer sign-in must have a dedicated entry point'
require_literal "${login_chooser}" "href: '/login/bank'" 'bank sign-in must have a dedicated entry point'
require_literal "${login_chooser}" "href: '/login/admin'" 'admin sign-in must have a dedicated entry point'
require_literal "${customer}" 'role="alert"' 'customer workspace must expose terminal load errors'
require_literal "${customer}" 'Retry workspace' 'customer workspace must expose a recovery action'

static_index="${static_root}/index.html"
[ -f "${static_index}" ] || { printf '%s\n' 'Odyssey 2.21.1 contract failed: generated portal index is missing' >&2; exit 1; }
require_literal "${static_index}" 'name="openwave-design-contract" content="Odyssey 2.21.1"' 'generated portal index must retain the design contract marker'

css_asset="$(sed -n 's/.*href="\([^"?]*\.css\)".*/\1/p' "${static_index}" | head -n 1)"
[[ "${css_asset}" =~ ^/_app/immutable/assets/[A-Za-z0-9._-]+\.css$ ]] || {
    printf '%s\n' 'Odyssey 2.21.1 contract failed: generated portal index has no safe CSS entry asset' >&2
    exit 1
}
require_literal "${static_root}${css_asset}" '--ow-odyssey-target-min:48px' 'generated CSS must retain the 48px target token'
require_literal "${static_root}${css_asset}" ':focus-visible' 'generated CSS must retain focus-visible styling'
require_literal "${static_root}${css_asset}" 'prefers-reduced-motion:reduce' 'generated CSS must retain reduced-motion styling'
if ! grep -R -Fq --include='*.js' 'Retry workspace' "${static_root}/_app/immutable" \
    || ! grep -R -Fq --include='*.js' 'Retry approval status' "${static_root}/_app/immutable"; then
    printf '%s\n' 'Odyssey 2.21.1 contract failed: generated portal JS lacks required recovery actions' >&2
    exit 1
fi

printf '%s\n' 'Odyssey 2.21.1 contract: PASS'
