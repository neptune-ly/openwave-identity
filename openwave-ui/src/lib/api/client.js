import axios from 'axios';
import { get } from 'svelte/store';
import { auth } from '$lib/stores/auth';

export function getApi() {
  const s = get(auth);
  const baseURL = s?.baseUrl || '/v1';

  const headers = {};
  if (s?.sessionToken) headers['X-OpenWave-Portal-Session'] = s.sessionToken;
  if (s?.role === 'ADMIN' && s.adminKey) headers['X-OpenWave-Registry-Key'] = s.adminKey;
  if (s?.role === 'BANK' && s.bankKey)   headers['X-OpenWave-Bank-Key'] = s.bankKey;

  return axios.create({ baseURL, headers });
}

export async function apiCall(method, path, data) {
  try {
    const r = await getApi()({ method, url: path, data });
    return { ok: true, data: r.data };
  } catch (e) {
    const msg = e.response?.data?.message || e.response?.data?.error || e.message;
    return {
      ok: false,
      error: msg,
      status: e.response?.status,
      code: e.response?.data?.code,
      data: e.response?.data
    };
  }
}

export async function apiPublic(path) {
  const s = get(auth);
  const baseURL = s?.baseUrl || '/v1';
  try {
    const r = await axios.get(baseURL + path);
    return { ok: true, data: r.data };
  } catch (e) {
    return { ok: false, error: e.response?.data?.message || e.message };
  }
}

export async function validateAdminKey(baseUrl, adminKey) {
  try {
    await axios.get(baseUrl + '/registry/info', {
      headers: { 'X-OpenWave-Registry-Key': adminKey }
    });
    return { ok: true };
  } catch (e) {
    if (e.response?.status === 401 || e.response?.status === 403) return { ok: false, error: 'Invalid admin key' };
    return { ok: true };
  }
}

export async function validateBankKey(baseUrl, bankKey) {
  try {
    const r = await axios.get(baseUrl + '/registry/info');
    return { ok: true };
  } catch { return { ok: true }; }
}
