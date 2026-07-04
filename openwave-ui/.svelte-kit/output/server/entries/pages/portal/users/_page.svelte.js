import { et as attr, f as ensure_array_like, nt as escape_html, o as attr_class, p as head, u as derived, z as get } from "../../../../chunks/index-server.js";
import "../../../../chunks/index-server2.js";
import "../../../../chunks/toast-state.svelte.js";
import { t as goto } from "../../../../chunks/client.js";
import "../../../../chunks/navigation.js";
import "../../../../chunks/auth.js";
import { t as Building_2 } from "../../../../chunks/building-2.js";
import { t as User_cog } from "../../../../chunks/user-cog.js";
import "../../../../chunks/shield-check.js";
import "../../../../chunks/client2.js";
import { t as page } from "../../../../chunks/stores.js";
import { t as Search } from "../../../../chunks/search.js";
import { t as Info } from "../../../../chunks/info.js";
import { t as Refresh_cw } from "../../../../chunks/refresh-cw.js";
import { t as Circle_check_big } from "../../../../chunks/circle-check-big.js";
import { t as User_plus } from "../../../../chunks/user-plus.js";
import { t as Circle_alert } from "../../../../chunks/circle-alert.js";
import { t as Clock_3 } from "../../../../chunks/clock-3.js";
//#region src/routes/portal/users/+page.svelte
function _page($$renderer, $$props) {
	$$renderer.component(($$renderer) => {
		let session = null;
		let users = [];
		let loading = true;
		let search = "";
		let roleFilter = "all";
		let currentSection = "registry";
		const registryRoles = ["REGISTRY_ADMIN", "REGISTRY_OPERATOR"];
		const bankRoles = [
			"BANK_ADMIN",
			"BANK_OPERATOR",
			"BANK_VIEWER"
		];
		const roleLabels = {
			REGISTRY_ADMIN: "Registry Admin",
			REGISTRY_OPERATOR: "Registry Operator",
			BANK_ADMIN: "Bank Admin",
			BANK_OPERATOR: "Bank Operator",
			BANK_VIEWER: "Bank Viewer"
		};
		const visibleRoles = derived(() => session?.role === "ADMIN" ? [...registryRoles, ...bankRoles] : bankRoles);
		derived(() => filterUsers(users, search, roleFilter));
		const bankScopedUsers = derived(() => users.filter((user) => user.bankHandle).length);
		const suspendedUsers = derived(() => users.filter((user) => !user.active).length);
		const recentLoginCount = derived(() => users.filter((user) => user.lastLoginAt).length);
		async function syncRouteState() {
			const next = new URL(get(page).url);
			if (search.trim());
			else next.searchParams.delete("search");
			if (roleFilter !== "all") next.searchParams.set("role", roleFilter);
			else next.searchParams.delete("role");
			if (currentSection !== "registry") next.searchParams.set("section", currentSection);
			else next.searchParams.delete("section");
			await goto(`${next.pathname}${next.search}`, {
				replaceState: true,
				noScroll: true,
				keepFocus: true
			});
		}
		function filterUsers(allUsers, query, role) {
			const normalized = query.trim().toLowerCase();
			return allUsers.filter((user) => {
				if (role !== "all" && user.role !== role) return false;
				if (!normalized) return true;
				return [
					user.username,
					user.displayName,
					user.email,
					user.bankHandle,
					roleLabels[user.role] || user.role
				].some((value) => value?.toLowerCase().includes(normalized));
			});
		}
		function summaryItems() {
			return [
				{
					label: "Active users",
					value: String(users.filter((user) => user.active).length),
					icon: Circle_check_big
				},
				{
					label: "Suspended",
					value: String(suspendedUsers()),
					icon: Circle_alert
				},
				{
					label: "Bank scoped",
					value: String(bankScopedUsers()),
					icon: Building_2
				},
				{
					label: "Logged in before",
					value: String(recentLoginCount()),
					icon: Clock_3
				}
			];
		}
		const sectionMeta = {
			registry: {
				label: "Access registry",
				hint: "Search users and open the correct desk",
				purpose: "Use this section for discovery only. Open a dedicated user desk for edits, scope changes, and reset work.",
				action: "Open a user desk"
			},
			provisioning: {
				label: "Provisioning",
				hint: "Create new portal access safely",
				purpose: "Use the dedicated create flow for one-time credentials and role assignment. Keep the registry page clean.",
				action: "Open create flow"
			}
		};
		function hintClass() {
			return "inline-flex h-4 w-4 cursor-help text-white/40";
		}
		head("m648oq", $$renderer, ($$renderer) => {
			$$renderer.title(($$renderer) => {
				$$renderer.push(`<title>Portal Users - OpenWave Identity</title>`);
			});
		});
		$$renderer.push(`<div class="mx-auto max-w-7xl space-y-6 p-8"><section class="identity-expressive-band p-6"><div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between"><div class="max-w-3xl"><p class="text-[11px] uppercase tracking-[0.18em] text-white/30">${escape_html(session?.role === "ADMIN" ? "Registry access control desk" : "Bank operator access desk")}</p> <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">Portal Users</h1> <p class="identity-section-note mt-2 text-sm text-white/55">Keep this page focused on access discovery and provisioning. Open any operator on its own desk to manage scope, suspension, and credential recovery.</p> <div class="mt-3 flex flex-wrap gap-2 text-xs text-white/45"><span class="identity-role-accent">${escape_html(session?.role === "ADMIN" ? "Registry-wide provisioning" : "Bank-scoped provisioning")}</span> <span class="identity-role-accent">Dedicated user desks</span> <span class="identity-role-accent">One-time credential handoff</span></div> <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45"><span class="inline-flex items-center gap-1 rounded-full border border-white/[0.08] px-2.5 py-1">Scoped access <span class="tooltip max-w-xs" data-tip="Bank roles stay bank-scoped. Registry roles stay global and should be provisioned sparingly.">`);
		Info($$renderer, { class: hintClass() });
		$$renderer.push(`<!----></span></span> <span class="inline-flex items-center gap-1 rounded-full border border-white/[0.08] px-2.5 py-1">Dedicated desks <span class="tooltip max-w-xs" data-tip="Use the user record page for edits and resets. This registry screen should stay clean and searchable.">`);
		Info($$renderer, { class: hintClass() });
		$$renderer.push(`<!----></span></span></div></div> <div class="grid gap-3 sm:grid-cols-2"><div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Provisioning rule</div> <div class="mt-2 text-sm font-medium text-white">Grant only the minimum role and scope needed.</div> <div class="mt-1 text-[12px] text-white/45">Use bank roles for bank desks and keep registry roles tightly controlled.</div></div> <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Recovery posture</div> <div class="mt-2 text-sm font-medium text-white">Use secure email handoff when available.</div> <div class="mt-1 text-[12px] text-white/45">Temporary passwords are shown once and should not linger in the portal.</div></div></div></div></section> <div class="flex flex-wrap justify-end gap-2"><button${attr("disabled", loading, true)} class="inline-flex items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2 text-[13px] font-medium text-white/60 transition-all hover:border-white/[0.18] hover:text-white disabled:opacity-40">`);
		Refresh_cw($$renderer, { class: `h-4 w-4 animate-spin` });
		$$renderer.push(`<!----> Refresh</button> <button class="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500">`);
		User_plus($$renderer, { class: "h-4 w-4" });
		$$renderer.push(`<!----> Create access</button></div> <section class="identity-desk-grid"><aside class="identity-desk-rail"><p class="identity-desk-rail-title">User desk</p> <div class="rounded-2xl border border-white/[0.08] bg-black/10 px-4 py-3 text-sm text-white/45">This page is for discovery and provisioning only. Open a dedicated user desk for edits, suspension, and credential recovery.</div> <div class="identity-desk-nav" role="tablist" aria-label="Portal user sections"><!--[-->`);
		const each_array = ensure_array_like(Object.entries(sectionMeta));
		for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
			let [key, item] = each_array[$$index];
			$$renderer.push(`<button type="button"${attr_class(`identity-desk-nav-item ${currentSection === key ? "is-active" : ""}`)}${attr("title", `${item.label} · ${item.hint}`)}><div class="identity-desk-nav-copy"><div class="identity-desk-nav-label">${escape_html(item.label)}</div> <div class="identity-desk-nav-hint">${escape_html(item.hint)}</div></div></button>`);
		}
		$$renderer.push(`<!--]--></div></aside> <div class="identity-desk-panel"><section class="identity-desk-header"><div class="identity-desk-header-grid"><div><p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Current section</p> <h2 class="mt-1 text-lg font-semibold text-white">${escape_html(sectionMeta[currentSection].label)}</h2> <p class="mt-2 text-sm text-white/45">${escape_html(sectionMeta[currentSection].purpose)}</p></div> <div class="identity-desk-header-stats"><div class="identity-desk-header-stat"><div class="identity-desk-header-stat-label">Active users</div> <div class="identity-desk-header-stat-value">${escape_html(users.filter((user) => user.active).length)}</div></div> <div class="identity-desk-header-stat"><div class="identity-desk-header-stat-label">Bank scoped</div> <div class="identity-desk-header-stat-value">${escape_html(bankScopedUsers())}</div></div> <div class="identity-desk-header-stat"><div class="identity-desk-header-stat-label">Next action</div> <div class="identity-desk-header-stat-value">${escape_html(sectionMeta[currentSection].action)}</div></div></div></div></section> `);
		if (currentSection === "registry") {
			$$renderer.push("<!--[0-->");
			$$renderer.push(`<section class="identity-surface-card overflow-hidden"><div class="border-b border-white/[0.06] px-5 py-4"><div class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between"><div><div class="text-sm font-semibold">Access registry</div> <div class="mt-1 text-[12px] text-white/35">Search portal users and open a dedicated desk for each record.</div></div> <div class="flex flex-col gap-2 sm:flex-row"><label class="relative block">`);
			Search($$renderer, { class: "pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-white/25" });
			$$renderer.push(`<!----> <input${attr("value", search)} placeholder="Search users, scope, email" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] py-2 pl-9 pr-3 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none sm:w-64"/></label> `);
			$$renderer.select({
				value: roleFilter,
				onchange: () => syncRouteState(),
				class: "rounded-xl border border-white/[0.1] bg-white/[0.04] px-3 py-2 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none"
			}, ($$renderer) => {
				$$renderer.option({ value: "all" }, ($$renderer) => {
					$$renderer.push(`All roles`);
				});
				$$renderer.push(`<!--[-->`);
				const each_array_1 = ensure_array_like(visibleRoles());
				for (let $$index_1 = 0, $$length = each_array_1.length; $$index_1 < $$length; $$index_1++) {
					let role = each_array_1[$$index_1];
					$$renderer.option({ value: role }, ($$renderer) => {
						$$renderer.push(`${escape_html(roleLabels[role])}`);
					});
				}
				$$renderer.push(`<!--]-->`);
			});
			$$renderer.push(`</div></div></div> <div class="overflow-x-auto"><div class="grid min-w-[940px] grid-cols-[minmax(0,1.2fr)_160px_140px_120px_110px] gap-4 border-b border-white/[0.06] px-5 py-3 text-[11px] uppercase tracking-[0.16em] text-white/30"><span>User</span> <span>Role</span> <span>Scope</span> <span>Status</span> <span class="text-right">Action</span></div> `);
			$$renderer.push("<!--[0-->");
			$$renderer.push(`<div class="p-10 text-center text-sm text-white/35">Loading portal users...</div>`);
			$$renderer.push(`<!--]--></div></section> <section class="identity-surface-card p-5"><div class="grid gap-3 md:grid-cols-4"><!--[-->`);
			const each_array_3 = ensure_array_like(summaryItems());
			for (let $$index_3 = 0, $$length = each_array_3.length; $$index_3 < $$length; $$index_3++) {
				let item = each_array_3[$$index_3];
				$$renderer.push(`<section class="identity-surface-soft px-4 py-4"><div class="flex items-center gap-3"><div class="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-indigo-300">`);
				if (item.icon) {
					$$renderer.push("<!--[-->");
					item.icon($$renderer, { class: "h-5 w-5" });
					$$renderer.push("<!--]-->");
				} else {
					$$renderer.push("<!--[!-->");
					$$renderer.push("<!--]-->");
				}
				$$renderer.push(`</div> <div><p class="text-[11px] uppercase tracking-[0.16em] text-white/30">${escape_html(item.label)}</p> <p class="mt-1 text-xl font-semibold">${escape_html(item.value)}</p></div></div></section>`);
			}
			$$renderer.push(`<!--]--></div></section>`);
		} else {
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<section class="identity-surface-card p-5"><div class="flex h-full min-h-[420px] flex-col justify-between gap-5"><div class="space-y-4"><div class="flex h-14 w-14 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.04] text-indigo-300">`);
			User_cog($$renderer, { class: "h-5 w-5" });
			$$renderer.push(`<!----></div> <div><div class="text-base font-semibold">Dedicated user flows</div> <div class="mt-2 max-w-sm text-[13px] leading-5 text-white/45">Create access on its own page. Open an existing portal user from the registry to adjust scope, suspend access, or reset credentials on the dedicated desk.</div></div> <div class="rounded-xl border border-white/[0.08] bg-black/10 p-4 text-[12px] text-white/55">Keep this registry page clean. One-time credentials and provisioning details now stay on the dedicated create flow only.</div></div> <div class="flex justify-end"><button class="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500">`);
			User_plus($$renderer, { class: "h-4 w-4" });
			$$renderer.push(`<!----> Open create page</button></div></div></section>`);
		}
		$$renderer.push(`<!--]--></div></section></div>`);
	});
}
//#endregion
export { _page as default };
