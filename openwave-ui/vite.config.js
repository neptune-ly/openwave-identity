import { sveltekit } from '@sveltejs/kit/vite';
import { defineConfig } from 'vite';

const registryTarget = process.env.OPENWAVE_REGISTRY_PROXY_TARGET || 'http://127.0.0.1:8095';

export default defineConfig({
  plugins: [sveltekit()],
  server: {
    port: 5174,
    strictPort: false,
    proxy: {
      '/v1': {
        target: registryTarget,
        changeOrigin: true
      }
    }
  }
});
