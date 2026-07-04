import { z as get } from "./index-server.js";
import "./index-server2.js";
import { t as auth } from "./auth.js";
import axios from "axios";
//#region src/lib/api/client.js
function getApi() {
	const s = get(auth);
	const baseURL = s?.baseUrl || "/v1";
	const headers = {};
	if (s?.sessionToken) headers["X-OpenWave-Portal-Session"] = s.sessionToken;
	if (s?.role === "ADMIN" && s.adminKey) headers["X-OpenWave-Registry-Key"] = s.adminKey;
	if (s?.role === "BANK" && s.bankKey) headers["X-OpenWave-Bank-Key"] = s.bankKey;
	return axios.create({
		baseURL,
		headers
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
			error: e.response?.data?.message || e.response?.data?.error || e.message,
			status: e.response?.status
		};
	}
}
async function apiPublic(path) {
	const baseURL = get(auth)?.baseUrl || "/v1";
	try {
		return {
			ok: true,
			data: (await axios.get(baseURL + path)).data
		};
	} catch (e) {
		return {
			ok: false,
			error: e.response?.data?.message || e.message
		};
	}
}
//#endregion
export { apiPublic as n, getApi as r, apiCall as t };
