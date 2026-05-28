# OpenWave Identity UI

Admin portal for the OpenWave Identity Registry.

## Registry Endpoint

Production builds use same-origin `/v1` by default, so the UI can be served by the registry service without exposing an environment-specific URL in the login form.

Deployment options:

- Build-time override: `VITE_OPENWAVE_REGISTRY_URL=https://identity.example.com/v1 npm run build`
- Runtime override before the app loads: `window.OPENWAVE_REGISTRY_URL = 'https://identity.example.com/v1'`
- Local dev proxy target: set `OPENWAVE_REGISTRY_PROXY_TARGET` to your local registry URL before running `npm run dev`.

The login screen does not expose endpoint editing; the deployment controls the registry URL.
