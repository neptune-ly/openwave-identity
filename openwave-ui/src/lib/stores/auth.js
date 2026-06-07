import { writable, get } from 'svelte/store';
import { configuredRegistryUrl } from '$lib/config';

const STORAGE_KEY = 'ow_session';
const isBrowser = typeof window !== 'undefined' && typeof localStorage !== 'undefined';

function createAuthStore() {
  const initial = loadSession();
  const { subscribe, set, update } = writable(initial);

  function loadSession() {
    if (!isBrowser) return null;
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null'); } catch { return null; }
  }

  function saveSession(s) {
    if (!isBrowser) return;
    if (s) localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
    else localStorage.removeItem(STORAGE_KEY);
  }

  return {
    subscribe,
    loginAdmin(adminKey, baseUrl, username = 'registry-admin', sessionToken = null, portalRole = 'REGISTRY_ADMIN') {
      const s = { role: 'ADMIN', portalRole, adminKey, username, sessionToken, baseUrl: baseUrl || configuredRegistryUrl() };
      set(s); saveSession(s);
    },
    loginBank(bankKey, bankHandle, baseUrl, username = bankHandle, sessionToken = null, portalRole = 'BANK_ADMIN') {
      const s = { role: 'BANK', portalRole, bankKey, bankHandle, username, sessionToken, baseUrl: baseUrl || configuredRegistryUrl() };
      set(s); saveSession(s);
    },
    loginCustomer(baseUrl, username, sessionToken = null, portalRole = 'CUSTOMER') {
      const s = { role: 'CUSTOMER', portalRole, username, sessionToken, baseUrl: baseUrl || configuredRegistryUrl() };
      set(s); saveSession(s);
    },
    logout() { set(null); saveSession(null); },
    get current() { return get({ subscribe }); }
  };
}

export const auth = createAuthStore();
