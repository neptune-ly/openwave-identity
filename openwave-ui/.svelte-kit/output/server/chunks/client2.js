import { z as get } from "./index-server.js";
import "./index-server2.js";
import { t as auth } from "./auth.js";
import axios from "axios";
//#region src/lib/api/client.js
var PORTAL_REQUEST_TIMEOUT_MS = 15e3;
function isTimeoutError(error) {
	return error?.code === "ECONNABORTED" || error?.code === "ETIMEDOUT";
}
function requestErrorMessage(error, fallback) {
	if (isTimeoutError(error)) return "The identity service took too long to respond. Please retry.";
	return error.response?.data?.message || error.response?.data?.error || error.message || fallback;
}
function getApi() {
	const s = get(auth);
	const baseURL = s?.baseUrl || "/v1";
	const headers = {};
	if (s?.sessionToken) headers["X-OpenWave-Portal-Session"] = s.sessionToken;
	if (s?.role === "ADMIN" && s.adminKey) headers["X-OpenWave-Registry-Key"] = s.adminKey;
	if (s?.role === "BANK" && s.bankKey) headers["X-OpenWave-Bank-Key"] = s.bankKey;
	return axios.create({
		baseURL,
		headers,
		timeout: PORTAL_REQUEST_TIMEOUT_MS
	});
}
async function apiCall(method, path, data) {
	try {
		return {
			ok: true,
			data: (await getApi()({
				method,
				url: path,
				data
			})).data
		};
	} catch (e) {
		return {
			ok: false,
			error: requestErrorMessage(e, "Identity request failed"),
			status: e.response?.status,
			code: e.response?.data?.code,
			data: e.response?.data,
			timedOut: isTimeoutError(e)
		};
	}
}
async function apiPublic(path) {
	const baseURL = get(auth)?.baseUrl || "/v1";
	try {
		return {
			ok: true,
			data: (await axios.get(baseURL + path, { timeout: PORTAL_REQUEST_TIMEOUT_MS })).data
		};
	} catch (e) {
		return {
			ok: false,
			error: requestErrorMessage(e, "Identity request failed"),
			status: e.response?.status,
			code: e.response?.data?.code,
			data: e.response?.data,
			timedOut: isTimeoutError(e)
		};
	}
}
//#endregion
export { isTimeoutError as a, getApi as i, apiCall as n, apiPublic as r, PORTAL_REQUEST_TIMEOUT_MS as t };
