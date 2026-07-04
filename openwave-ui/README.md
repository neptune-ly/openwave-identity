# OpenWave Identity UI

Admin portal for the OpenWave Identity Registry.

## UI Baseline

OpenWave portal work follows the shared OpenWave/Neptune intentional-UI rule set:

- one screen has one clear job;
- every visible component must support a real operator decision, status, or action;
- no filler cards, fake metrics, decorative icons, or purposeless hero copy;
- portal rebuild direction is Svelte plus `shadcn-svelte`, with owned open-code primitives and local tokens;
- AI-assisted UI work should extend the checked-in component layer rather than generating disconnected page-local fragments;
- RTL/LTR should use framework-native direction behavior unless a field explicitly requires LTR for safety or readability.

## Registry Endpoint

Production builds use same-origin `/v1` by default, so the UI can be served by the registry service without exposing an environment-specific URL in the login form.

Deployment options:

- Build-time override: `VITE_OPENWAVE_REGISTRY_URL=https://identity.example.com/v1 npm run build`
- Runtime override before the app loads: `window.OPENWAVE_REGISTRY_URL = 'https://identity.example.com/v1'`
- Local dev proxy target: set `OPENWAVE_REGISTRY_PROXY_TARGET` to your local registry URL before running `npm run dev`.

The login screen does not expose endpoint editing; the deployment controls the registry URL.
