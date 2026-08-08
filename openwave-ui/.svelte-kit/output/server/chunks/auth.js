import { V as writable, z as get } from "./index-server.js";
import "./index-server2.js";
//#region src/lib/config.js
var envUrl = void 0;
function normalizeUrl(value) {
	if (!value || typeof value !== "string") return null;
	const trimmed = value.trim().replace(/\/+$/, "");
	if (!trimmed) return null;
	if (trimmed.startsWith("/")) return trimmed;
	try {
		return new URL(trimmed).toString().replace(/\/+$/, "");
	} catch {
		return null;
	}
}
function configuredRegistryUrl() {
	return normalizeUrl(typeof window !== "undefined" ? window.OPENWAVE_REGISTRY_URL : null) || normalizeUrl(envUrl) || "/v1";
}
//#endregion
//#region src/lib/stores/auth.js
var STORAGE_KEY = "ow_session";
var isBrowser = typeof window !== "undefined" && typeof localStorage !== "undefined";
function createAuthStore() {
	const { subscribe, set, update } = writable(loadSession());
	function loadSession() {
		if (!isBrowser) return null;
		try {
			return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "null");
		} catch {
			return null;
		}
	}
	function saveSession(s) {
		if (!isBrowser) return;
		if (s) localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
		else localStorage.removeItem(STORAGE_KEY);
	}
	return {
		subscribe,
		loginAdmin(adminKey, baseUrl, username = "registry-admin", sessionToken = null, portalRole = "REGISTRY_ADMIN") {
			const s = {
				role: "ADMIN",
				portalRole,
				adminKey,
				username,
				sessionToken,
				baseUrl: baseUrl || configuredRegistryUrl()
			};
			set(s);
			saveSession(s);
		},
		loginBank(bankKey, bankHandle, baseUrl, username = bankHandle, sessionToken = null, portalRole = "BANK_ADMIN") {
			const s = {
				role: "BANK",
				portalRole,
				bankKey,
				bankHandle,
				username,
				sessionToken,
				baseUrl: baseUrl || configuredRegistryUrl()
			};
			set(s);
			saveSession(s);
		},
		loginCustomer(baseUrl, username, sessionToken = null, portalRole = "CUSTOMER") {
			const s = {
				role: "CUSTOMER",
				portalRole,
				username,
				sessionToken,
				baseUrl: baseUrl || configuredRegistryUrl()
			};
			set(s);
			saveSession(s);
		},
		logout() {
			set(null);
			saveSession(null);
		},
		get current() {
			return get({ subscribe });
		}
	};
}
var auth = createAuthStore();
//#endregion
export { configuredRegistryUrl as n, auth as t };
