import axios from 'axios';
import { get } from 'svelte/store';
import { auth } from '$lib/stores/auth';

export const PORTAL_REQUEST_TIMEOUT_MS = 15_000;

export function isTimeoutError(error) {
  return error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT';
}

function requestErrorMessage(error, fallback) {
  if (isTimeoutError(error)) return 'The identity service took too long to respond. Please retry.';
  return error.response?.data?.message || error.response?.data?.error || error.message || fallback;
}

export function getApi() {
  const s = get(auth);
  const baseURL = s?.baseUrl || '/v1';

  const headers = {};
  if (s?.sessionToken) headers['X-OpenWave-Portal-Session'] = s.sessionToken;
  if (s?.role === 'ADMIN' && s.adminKey) headers['X-OpenWave-Registry-Key'] = s.adminKey;
  if (s?.role === 'BANK' && s.bankKey)   headers['X-OpenWave-Bank-Key'] = s.bankKey;

  return axios.create({ baseURL, headers, timeout: PORTAL_REQUEST_TIMEOUT_MS });
}

export async function apiCall(method, path, data) {
  try {
    const r = await getApi()({ method, url: path, data });
    return { ok: true, data: r.data };
  } catch (e) {
    return {
      ok: false,
      error: requestErrorMessage(e, 'Identity request failed'),
      status: e.response?.status,
      code: e.response?.data?.code,
      data: e.response?.data,
      timedOut: isTimeoutError(e)
    };
  }
}

export async function apiPublic(path) {
  const s = get(auth);
  const baseURL = s?.baseUrl || '/v1';
  try {
    const r = await axios.get(baseURL + path, { timeout: PORTAL_REQUEST_TIMEOUT_MS });
    return { ok: true, data: r.data };
  } catch (e) {
    return {
      ok: false,
      error: requestErrorMessage(e, 'Identity request failed'),
      status: e.response?.status,
      code: e.response?.data?.code,
      data: e.response?.data,
      timedOut: isTimeoutError(e)
    };
  }
}

export async function validateAdminKey(baseUrl, adminKey) {
  try {
    await axios.get(baseUrl + '/registry/info', {
      headers: { 'X-OpenWave-Registry-Key': adminKey },
      timeout: PORTAL_REQUEST_TIMEOUT_MS
    });
    return { ok: true };
  } catch (e) {
    if (e.response?.status === 401 || e.response?.status === 403) return { ok: false, error: 'Invalid admin key' };
    return { ok: true };
  }
}

export async function validateBankKey(baseUrl, bankKey) {
  try {
    const r = await axios.get(baseUrl + '/registry/info', { timeout: PORTAL_REQUEST_TIMEOUT_MS });
    return { ok: true };
  } catch { return { ok: true }; }
}
