import { et as attr, f as ensure_array_like, nt as escape_html, o as attr_class, p as head, s as attr_style, u as derived, z as get } from "../../../../chunks/index-server.js";
import "../../../../chunks/index-server2.js";
import "../../../../chunks/toast-state.svelte.js";
import { t as goto } from "../../../../chunks/client.js";
import "../../../../chunks/navigation.js";
import "../../../../chunks/auth.js";
import "../../../../chunks/client2.js";
import { t as page } from "../../../../chunks/stores.js";
import { t as Search } from "../../../../chunks/search.js";
import { t as Info } from "../../../../chunks/info.js";
import { t as Refresh_cw } from "../../../../chunks/refresh-cw.js";
import { t as Plus } from "../../../../chunks/plus.js";
//#region src/routes/portal/banks/+page.svelte
function _page($$renderer, $$props) {
	$$renderer.component(($$renderer) => {
		let session = null;
		let banks = [];
		let loading = false;
		let search = "";
		let readinessFilter = "all";
		const isAdmin = derived(() => session?.role === "ADMIN");
		const activeBanks = derived(() => banks.filter((bank) => bank.active).length);
		const brandedBanks = derived(() => banks.filter((bank) => bank.branding?.brand_color || bank.branding?.logo_url || bank.branding?.website).length);
		const readyBanks = derived(() => banks.filter((bank) => readinessRows(bank).every((item) => item.done)).length);
		const filteredBanks = derived(() => filterBanks(banks, search, readinessFilter));
		const myBank = derived(() => !isAdmin() ? banks[0] ?? null : null);
		const myBankPackage = derived(() => myBank()?.operationsPackage ?? null);
		async function syncRouteState() {
			const next = new URL(get(page).url);
			next.searchParams.delete("section");
			if (search.trim());
			else next.searchParams.delete("search");
			if (readinessFilter !== "all") next.searchParams.set("readiness", readinessFilter);
			else next.searchParams.delete("readiness");
			await goto(`${next.pathname}${next.search}`, {
				replaceState: true,
				noScroll: true,
				keepFocus: true
			});
		}
		function readinessRows(bank) {
			if (!bank) return [];
			return [
				{
					title: "Directory identity",
					done: Boolean(bank.branding?.display_name || bank.displayName),
					detail: bank.branding?.display_name || bank.displayName || "Missing display name"
				},
				{
					title: "Operations contact",
					done: Boolean(bank.contactEmail || bank.branding?.support_email),
					detail: bank.branding?.support_email || bank.contactEmail || "Missing support email"
				},
				{
					title: "Core routing profile",
					done: Boolean(bank.coreUrl),
					detail: bank.coreUrl || "Missing core URL"
				},
				{
					title: "Brand signal",
					done: Boolean(bank.branding?.brand_color || bank.branding?.logo_url),
					detail: bank.branding?.logo_url ? "Logo uploaded" : bank.branding?.brand_color ? `Color ${bank.branding.brand_color}` : "No color or logo"
				},
				{
					title: "Public website",
					done: Boolean(bank.branding?.website),
					detail: bank.branding?.website || "Missing website"
				}
			];
		}
		function hintClass() {
			return "inline-flex h-4 w-4 cursor-help text-white/40";
		}
		function bankReady(bank) {
			return readinessRows(bank).every((item) => item.done);
		}
		function filterBanks(allBanks, query, readiness) {
			const normalized = query.trim().toLowerCase();
			return allBanks.filter((bank) => {
				if (readiness === "ready" && !bankReady(bank)) return false;
				if (readiness === "needs-work" && bankReady(bank)) return false;
				if (!normalized) return true;
				return [
					bank.bankHandle,
					bank.handle,
					bank.displayName,
					bank.name,
					bank.branding?.display_name,
					bank.branding?.support_email,
					bank.contactEmail,
					bank.country
				].some((value) => String(value || "").toLowerCase().includes(normalized));
			});
		}
		head("1apja9j", $$renderer, ($$renderer) => {
			$$renderer.title(($$renderer) => {
				$$renderer.push(`<title>${escape_html(isAdmin() ? "Banks" : "My Bank")} - OpenWave Identity</title>`);
			});
		});
		$$renderer.push(`<div class="mx-auto max-w-7xl space-y-6 p-8"><section class="identity-expressive-band p-6"><div class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between"><div><p class="text-[11px] uppercase tracking-[0.18em] text-white/30">${escape_html(isAdmin() ? "Registry banking directory" : "Bank identity workspace")}</p> <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight">${escape_html(isAdmin() ? "Banks" : "My Bank")}</h1> <p class="identity-section-note mt-1 text-sm text-white/50">${escape_html(isAdmin() ? "Keep this page focused on bank discovery and onboarding. Open any bank on its own desk to manage profile and readiness." : "Use this page as the entry surface for your bank record. Profile changes stay on the dedicated bank desk.")}</p> <div class="mt-3 flex flex-wrap gap-2 text-xs text-white/45"><span class="identity-role-accent">Directory-grade profile control</span> <span class="identity-role-accent">Dedicated bank desks</span> <span class="identity-role-accent">${escape_html(isAdmin() ? "Registry-owned onboarding" : "Controlled bank self-service")}</span></div> <div class="mt-4 flex flex-wrap gap-2"><span class="identity-role-accent">Directory profile <span class="tooltip max-w-xs" data-tip="This workspace controls the bank record shown in OpenWave Identity directory and registry tools. It does not change Astro payment routing or settlement execution.">`);
		Info($$renderer, { class: hintClass() });
		$$renderer.push(`<!----></span></span> <span class="identity-role-accent">Dedicated desks <span class="tooltip max-w-xs" data-tip="Open a bank record to manage branding and readiness on its own page instead of overloading the registry screen.">`);
		Info($$renderer, { class: hintClass() });
		$$renderer.push(`<!----></span></span></div></div> <div class="flex flex-wrap gap-2"><button${attr("disabled", loading, true)} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">`);
		Refresh_cw($$renderer, { class: `h-4 w-4 ` });
		$$renderer.push(`<!----> Refresh</button> `);
		if (isAdmin()) {
			$$renderer.push("<!--[0-->");
			$$renderer.push(`<button class="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500">`);
			Plus($$renderer, { class: "h-4 w-4" });
			$$renderer.push(`<!----> Register bank</button>`);
		} else $$renderer.push("<!--[-1-->");
		$$renderer.push(`<!--]--></div></div> <div class="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-3">`);
		if (isAdmin()) {
			$$renderer.push("<!--[0-->");
			$$renderer.push(`<div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Directory posture</div> <div class="mt-2 text-lg font-semibold text-white">${escape_html(banks.length)} bank record(s)</div> <div class="mt-1 text-[12px] text-white/45">${escape_html(activeBanks())} active · ${escape_html(readyBanks())} ready for profile-quality checks</div></div> <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Brand signal</div> <div class="mt-2 text-lg font-semibold text-white">${escape_html(brandedBanks())} branded</div> <div class="mt-1 text-[12px] text-white/45">Directory branding stays separate from payment routing and execution</div></div> <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Primary job</div> <div class="mt-2 text-lg font-semibold text-white">Search, filter, then open desk</div> <div class="mt-1 text-[12px] text-white/45">Registration stays here; edits and readiness review belong on the bank page</div></div>`);
		} else {
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Published profile</div> <div class="mt-2 text-lg font-semibold text-white">${escape_html(myBank()?.active ? "Active" : "Inactive")}</div> <div class="mt-1 text-[12px] text-white/45">${escape_html(myBank()?.bankHandle || "Bank handle loading")} published directory record</div></div> <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Customer scope</div> <div class="mt-2 text-lg font-semibold text-white">${escape_html(myBankPackage()?.customer_registry?.linked_customer_count ?? 0)} linked customers</div> <div class="mt-1 text-[12px] text-white/45">${escape_html(myBankPackage()?.customer_registry?.linked_account_count ?? 0)} linked account route(s)</div></div> <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Needs attention</div> <div class="mt-2 text-lg font-semibold text-white">${escape_html(myBankPackage()?.login_approvals?.pending ?? 0)} pending approval(s)</div> <div class="mt-1 text-[12px] text-white/45">${escape_html(myBankPackage()?.portal_access?.active_portal_user_count ?? 0)} active portal user(s)</div></div>`);
		}
		$$renderer.push(`<!--]--></div></section> <div class="space-y-6"><section class="identity-surface-card overflow-hidden"><div class="border-b border-white/[0.06] px-5 py-4"><div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between"><div><p class="text-[11px] uppercase tracking-[0.18em] text-white/30">${escape_html(isAdmin() ? "Directory registry" : "Published bank record")}</p> <h2 class="mt-1 text-lg font-semibold">${escape_html(isAdmin() ? "Bank directory" : "Current directory profile")}</h2> <p class="mt-1 text-[12px] text-white/35">${escape_html(isAdmin() ? "Open a dedicated bank desk for profile and readiness management." : "Use the button below to open your dedicated bank desk.")}</p></div> <div class="identity-role-accent">${escape_html(isAdmin() ? `${filteredBanks().length} matching record${filteredBanks().length === 1 ? "" : "s"}` : myBank() ? `${myBank().bankHandle} current record` : "Record loading")}</div></div></div> `);
		if (!filteredBanks().length) {
			$$renderer.push("<!--[2-->");
			$$renderer.push(`<div class="px-5 py-16 text-center text-sm text-white/40">No banks are registered yet.</div>`);
		} else if (isAdmin()) {
			$$renderer.push("<!--[3-->");
			$$renderer.push(`<div class="grid gap-3 border-b border-white/[0.06] px-5 py-4 lg:grid-cols-[minmax(0,1fr)_180px]"><label class="relative block">`);
			Search($$renderer, { class: "pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-white/25" });
			$$renderer.push(`<!----> <input${attr("value", search)} placeholder="Search bank, handle, contact" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] py-2 pl-9 pr-3 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none"/></label> `);
			$$renderer.select({
				value: readinessFilter,
				onchange: () => syncRouteState(),
				class: "rounded-xl border border-white/[0.1] bg-white/[0.04] px-3 py-2 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none"
			}, ($$renderer) => {
				$$renderer.option({ value: "all" }, ($$renderer) => {
					$$renderer.push(`All readiness`);
				});
				$$renderer.option({ value: "ready" }, ($$renderer) => {
					$$renderer.push(`Ready`);
				});
				$$renderer.option({ value: "needs-work" }, ($$renderer) => {
					$$renderer.push(`Needs work`);
				});
			});
			$$renderer.push(`</div> <div class="divide-y divide-white/[0.05]"><!--[-->`);
			const each_array_1 = ensure_array_like(filteredBanks());
			for (let $$index_1 = 0, $$length = each_array_1.length; $$index_1 < $$length; $$index_1++) {
				let bank = each_array_1[$$index_1];
				$$renderer.push(`<button type="button" class="grid w-full gap-4 px-5 py-4 text-left transition-colors hover:bg-white/[0.03] md:grid-cols-[minmax(0,1fr)_120px_130px]"><div class="flex min-w-0 items-center gap-3"><div class="flex h-11 w-11 items-center justify-center overflow-hidden rounded-2xl border border-white/[0.08] bg-white/[0.05] text-xs font-semibold text-indigo-300">`);
				if (bank.branding?.logo_url) {
					$$renderer.push("<!--[0-->");
					$$renderer.push(`<img${attr("src", bank.branding.logo_url)}${attr("alt", bank.displayName)} class="h-full w-full object-cover"/>`);
				} else {
					$$renderer.push("<!--[-1-->");
					$$renderer.push(`${escape_html(bank.bankHandle?.slice(0, 2).toUpperCase())}`);
				}
				$$renderer.push(`<!--]--></div> <div class="min-w-0"><div class="flex items-center gap-2"><p class="truncate text-sm font-semibold text-white">${escape_html(bank.branding?.display_name || bank.displayName)}</p> `);
				if (bank.branding?.brand_color) {
					$$renderer.push("<!--[0-->");
					$$renderer.push(`<span class="h-2.5 w-2.5 rounded-full border border-white/25"${attr_style(`background:${bank.branding.brand_color}`)}></span>`);
				} else $$renderer.push("<!--[-1-->");
				$$renderer.push(`<!--]--></div> <p class="mt-1 truncate text-[12px] text-white/35">${escape_html(bank.bankHandle)} · ${escape_html(bank.branding?.support_email || bank.contactEmail || "Support email missing")}</p></div></div> <div class="text-sm text-white/55"><p>${escape_html(bank.country)}</p> <p class="mt-1 text-[12px] text-white/30">${escape_html(bank.registeredAt ? new Date(bank.registeredAt).toLocaleDateString() : "—")}</p></div> <div class="flex items-center justify-start md:justify-end"><span${attr_class(`rounded-full border px-2.5 py-1 text-[11px] font-medium ${bankReady(bank) ? "border-emerald-500/25 bg-emerald-500/10 text-emerald-300" : "border-amber-500/20 bg-amber-500/10 text-amber-300"}`)}>${escape_html(bankReady(bank) ? "Ready" : "Needs work")}</span></div></button>`);
			}
			$$renderer.push(`<!--]--></div>`);
		} else if (myBank()) {
			$$renderer.push("<!--[4-->");
			$$renderer.push(`<div class="space-y-5 p-5"><div class="flex items-start gap-4"><div class="flex h-14 w-14 items-center justify-center overflow-hidden rounded-2xl border border-white/[0.08] bg-white/[0.05] text-sm font-semibold text-indigo-300">`);
			if (myBank().branding?.logo_url) {
				$$renderer.push("<!--[0-->");
				$$renderer.push(`<img${attr("src", myBank().branding.logo_url)}${attr("alt", myBank().displayName)} class="h-full w-full object-cover"/>`);
			} else {
				$$renderer.push("<!--[-1-->");
				$$renderer.push(`${escape_html(myBank().bankHandle?.slice(0, 2).toUpperCase())}`);
			}
			$$renderer.push(`<!--]--></div> <div class="min-w-0"><p class="text-[11px] uppercase tracking-[0.16em] text-white/30">Directory identity</p> <h3 class="mt-1 truncate text-xl font-semibold">${escape_html(myBank().branding?.display_name || myBank().displayName)}</h3> <p class="mt-1 text-sm text-white/40">${escape_html(myBank().bankHandle)} · ${escape_html(myBank().country)}</p></div></div> <button class="w-full rounded-xl bg-indigo-600 py-3 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500">Open my bank desk</button></div>`);
		} else $$renderer.push("<!--[-1-->");
		$$renderer.push(`<!--]--></section></div></div>`);
	});
}
//#endregion
export { _page as default };
