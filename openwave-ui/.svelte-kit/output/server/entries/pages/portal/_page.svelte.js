import { et as attr, f as ensure_array_like, nt as escape_html, o as attr_class, p as head, u as derived } from "../../../chunks/index-server.js";
import "../../../chunks/index-server2.js";
import "../../../chunks/client.js";
import "../../../chunks/navigation.js";
import "../../../chunks/auth.js";
import { t as Users } from "../../../chunks/users.js";
import { t as Building_2 } from "../../../chunks/building-2.js";
import { t as Settings } from "../../../chunks/settings.js";
import { t as Shield_check } from "../../../chunks/shield-check.js";
import { t as Clipboard_list } from "../../../chunks/clipboard-list.js";
import "../../../chunks/client2.js";
import "../../../chunks/stores.js";
import { t as Search } from "../../../chunks/search.js";
import { t as Activity } from "../../../chunks/activity.js";
import { t as Route } from "../../../chunks/route.js";
import { t as Info } from "../../../chunks/info.js";
import { t as Arrow_right } from "../../../chunks/arrow-right.js";
//#region src/routes/portal/+page.svelte
function _page($$renderer, $$props) {
	$$renderer.component(($$renderer) => {
		let info = null;
		let overview = null;
		let banks = [];
		let searchQ = "";
		let session = null;
		let section = "overview";
		derived(() => [
			session?.role === "ADMIN" ? {
				label: "Active banks",
				value: overview?.package?.registry?.active_banks ?? info?.registered_banks ?? "—",
				icon: Building_2
			} : {
				label: "Registered banks",
				value: info?.registered_banks ?? "—",
				icon: Building_2
			},
			session?.role === "ADMIN" ? {
				label: "Active identities",
				value: overview?.package?.registry?.active_identities ?? info?.active_identities ?? "—",
				icon: Shield_check
			} : {
				label: "Active identities",
				value: info?.active_identities ?? "—",
				icon: Shield_check
			},
			session?.role === "ADMIN" ? {
				label: "Pending approvals",
				value: overview?.package?.queues?.pending_bank_login_approvals ?? "—",
				icon: Activity
			} : {
				label: "Spec version",
				value: info?.spec_version ?? "—",
				icon: Activity
			},
			session?.role === "ADMIN" ? {
				label: "Route gaps",
				value: overview?.package?.registry?.active_identities_missing_default_bank ?? "—",
				icon: Route
			} : {
				label: "Uptime target",
				value: info?.uptime_sla ?? "—",
				icon: Route
			}
		]);
		const bankPreview = derived(() => (banks || []).slice(0, 6));
		const workflowCards = derived(() => [
			{
				title: "Identity operations",
				detail: "Claim handles, link or unlink accounts, and manage default routing from the dedicated identity desk.",
				href: "/portal/identity",
				icon: Route,
				tone: "text-indigo-300"
			},
			{
				title: "Portal users",
				detail: "Provision registry and bank-scoped operators, then open each user on its own access desk.",
				href: "/portal/users",
				icon: Users,
				tone: "text-sky-300"
			},
			{
				title: "Bank directory",
				detail: "Review bank profile readiness and open each bank on its dedicated record page.",
				href: "/portal/banks",
				icon: Building_2,
				tone: "text-emerald-300"
			},
			{
				title: "Reports",
				detail: "Use support-safe bank and registry reporting without exposing raw customer data.",
				href: "/portal/reports",
				icon: Clipboard_list,
				tone: "text-amber-300"
			},
			{
				title: "Registry corrections",
				detail: "Reserve irreversible fixes and identity deletion for the controlled correction desk only.",
				href: "/portal/manage",
				icon: Settings,
				tone: "text-rose-300"
			}
		]);
		function adminPackage() {
			return overview?.package ?? null;
		}
		function adminReadiness() {
			return adminPackage()?.readiness ?? {
				done: 0,
				total: 0,
				checks: []
			};
		}
		function adminNextSteps() {
			return adminPackage()?.next_steps ?? [];
		}
		function deskSections() {
			return [
				{
					id: "overview",
					title: "Overview",
					detail: session?.role === "ADMIN" ? "Registry posture, readiness, and operator lanes." : "Registry posture and operator lanes."
				},
				{
					id: "lookup",
					title: "Lookup",
					detail: "Resolve aliases and inspect routed identity profiles."
				},
				{
					id: "banks",
					title: "Banks",
					detail: `${bankPreview().length} bank preview row(s) loaded.`
				}
			];
		}
		head("8l8a07", $$renderer, ($$renderer) => {
			$$renderer.title(($$renderer) => {
				$$renderer.push(`<title>Dashboard — OpenWave Identity</title>`);
			});
		});
		$$renderer.push(`<div class="mx-auto max-w-7xl space-y-6 p-8"><section class="identity-expressive-band p-6"><div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between"><div class="max-w-3xl"><p class="text-[11px] uppercase tracking-[0.18em] text-white/35">Libya digital identity command</p> <h1 class="identity-page-title mt-2 text-3xl font-semibold text-white">OpenWave Identity overview</h1> <p class="identity-section-note mt-3 max-w-2xl text-sm text-white/55">${escape_html(session?.role === "ADMIN" ? "Review registry posture, queue pressure, and bank/customer readiness from one scoped operations overview before you move into dedicated desks." : "Review registry posture, inspect bank-vouched alias routing, and verify what public checkout, bank desks, or customer login flows will resolve before you touch identity records.")}</p> <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45"><span class="identity-role-accent">Bank-vouched identity</span> <span class="identity-role-accent">Alias and route verification</span> <span class="identity-role-accent">Support-safe operator lookup</span></div></div> <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-3"><div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Registry posture</div> <div class="mt-2 text-sm font-medium text-white">${escape_html(session?.role === "ADMIN" ? `${overview?.package?.registry?.active_identities ?? info?.active_identities ?? "—"} active identities` : `${info?.active_identities ?? "—"} active identities`)}</div> <div class="mt-1 text-[12px] text-white/40">${escape_html(session?.role === "ADMIN" ? `${overview?.package?.registry?.active_banks ?? info?.registered_banks ?? "—"} active bank participant(s)` : `${info?.registered_banks ?? "—"} registered bank participant(s)`)}</div></div> <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Routing rule</div> <div class="mt-2 text-sm font-medium text-white">Bare handles follow default bank routing.</div> <div class="mt-1 text-[12px] text-white/40">Use \`handle@bank\` when support, checkout, or approval flows need an explicit bank route.</div></div> <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Primary job</div> <div class="mt-2 text-sm font-medium text-white">${escape_html(section === "overview" ? "Choose an operator lane" : section === "lookup" ? "Resolve and inspect alias routing" : "Preview banks, then open desk")}</div> <div class="mt-1 text-[12px] text-white/40">${escape_html(info?.operator || "OpenWave Identity")} · ${escape_html(info?.country_scope || "National registry scope")}</div></div></div></div></section> `);
		$$renderer.push("<!--[-1-->");
		$$renderer.push(`<!--]--> <div class="grid gap-6 xl:grid-cols-[260px_minmax(0,1fr)]"><aside class="identity-surface-card p-4"><div class="text-sm font-semibold text-white">Landing desk</div> <p class="mt-2 text-sm text-white/45">Keep discovery here. Open dedicated desks for actual changes, investigations, or approvals.</p> <div class="mt-4 space-y-2"><!--[-->`);
		const each_array = ensure_array_like(deskSections());
		for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
			let item = each_array[$$index];
			$$renderer.push(`<button type="button"${attr_class(`w-full rounded-xl border px-3 py-3 text-left transition ${section === item.id ? "border-white/[0.16] bg-white/[0.08]" : "border-white/[0.08] bg-white/[0.03] hover:bg-white/[0.05]"}`)}><div class="text-sm font-medium text-white">${escape_html(item.title)}</div> <div class="mt-1 text-xs text-white/45">${escape_html(item.detail)}</div></button>`);
		}
		$$renderer.push(`<!--]--></div> <div class="mt-4 rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3 text-sm text-white/45">Bare handles follow default-bank routing. Use \`handle@bank\` when a support or approval flow must remove ambiguity.</div></aside> <div class="space-y-5"><section class="identity-surface-card p-5"><div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between"><div><div class="flex flex-wrap gap-2 text-[11px] uppercase tracking-[0.16em] text-white/30"><span>${escape_html(section === "overview" ? "Overview" : section === "lookup" ? "Lookup" : "Bank preview")}</span> <span class="identity-role-accent normal-case tracking-normal text-[11px]">${escape_html(section === "overview" ? "Choose an operator lane" : section === "lookup" ? (searchQ.trim(), "Resolve alias routing") : `${bankPreview().length} bank row(s) loaded`)}</span></div> <h2 class="mt-2 text-lg font-semibold text-white">${escape_html(section === "overview" ? "Registry overview" : section === "lookup" ? "Resolve and inspect" : "Connected banks")}</h2> <p class="mt-2 max-w-3xl text-sm text-white/45">${escape_html(section === "overview" ? "Use the landing desk to judge registry posture, queue pressure, and where to route the operator next." : section === "lookup" ? "Search a bare handle, bank-qualified alias, or routed identity profile without leaving the landing desk." : "Preview connected banks here, then open the dedicated bank desk for profile or readiness work.")}</p></div> `);
		if (section === "banks") {
			$$renderer.push("<!--[0-->");
			$$renderer.push(`<a href="/portal/banks" class="inline-flex items-center gap-1 text-[12px] text-indigo-200 transition-all hover:text-white">Open directory `);
			Arrow_right($$renderer, { class: "h-3.5 w-3.5" });
			$$renderer.push(`<!----></a>`);
		} else $$renderer.push("<!--[-1-->");
		$$renderer.push(`<!--]--></div></section> `);
		if (section === "overview") {
			$$renderer.push("<!--[0-->");
			if (session?.role === "ADMIN" && adminPackage()) {
				$$renderer.push("<!--[0-->");
				$$renderer.push(`<section class="identity-surface-card p-6"><div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between"><div><p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Registry operations package</p> <h2 class="mt-2 text-lg font-semibold text-white">Control-plane readiness and queue pressure</h2> <p class="mt-2 max-w-3xl text-sm text-white/45">Customer-identity posture gaps, bank-readiness gaps, and pending login approvals that can block digital identity access.</p></div> <div class="identity-role-accent">${escape_html(adminReadiness().done)}/${escape_html(adminReadiness().total)} checks ready</div></div> <div class="mt-5 grid gap-3 md:grid-cols-3"><div class="identity-workspace-card p-5"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Portal users</div> <div class="mt-2 text-2xl font-semibold text-white">${escape_html(adminPackage()?.portal_access?.active_portal_users ?? 0)}</div> <div class="mt-1 text-[12px] text-white/40">Active of ${escape_html(adminPackage()?.portal_access?.total_portal_users ?? 0)} total portal users</div></div> <div class="identity-workspace-card p-5"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Customer access</div> <div class="mt-2 text-2xl font-semibold text-white">${escape_html(adminPackage()?.portal_access?.active_customer_users ?? 0)}</div> <div class="mt-1 text-[12px] text-white/40">Active customer portal users of ${escape_html(adminPackage()?.portal_access?.customer_users ?? 0)}</div></div> <div class="identity-workspace-card p-5"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Pending bank approvals</div> <div class="mt-2 text-2xl font-semibold text-white">${escape_html(adminPackage()?.queues?.pending_bank_login_approvals ?? 0)}</div> <div class="mt-1 text-[12px] text-white/40">Phone or national-ID sign-ins waiting on bank approval</div></div></div> <div class="mt-5 grid gap-3 xl:grid-cols-[minmax(0,1fr)_340px]"><div class="rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Readiness checks</div> <div class="mt-4 space-y-3"><!--[-->`);
				const each_array_1 = ensure_array_like(adminReadiness().checks);
				for (let $$index_1 = 0, $$length = each_array_1.length; $$index_1 < $$length; $$index_1++) {
					let check = each_array_1[$$index_1];
					$$renderer.push(`<div class="flex items-start justify-between gap-3 rounded-2xl border border-white/[0.06] bg-white/[0.02] px-4 py-3"><div><div class="text-sm font-medium text-white">${escape_html(check.label)}</div> <div class="mt-1 text-[12px] text-white/45">${escape_html(check.detail)}</div></div> <div${attr_class(`rounded-full px-2.5 py-1 text-[11px] font-medium ${check.done ? "bg-emerald-500/10 text-emerald-300" : "bg-amber-500/10 text-amber-300"}`)}>${escape_html(check.done ? "Ready" : "Needs work")}</div></div>`);
				}
				$$renderer.push(`<!--]--></div></div> <div class="rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Next steps</div> <div class="mt-4 space-y-2"><!--[-->`);
				const each_array_2 = ensure_array_like(adminNextSteps());
				for (let $$index_2 = 0, $$length = each_array_2.length; $$index_2 < $$length; $$index_2++) {
					let step = each_array_2[$$index_2];
					$$renderer.push(`<div class="rounded-2xl border border-white/[0.06] bg-white/[0.02] px-4 py-3 text-sm text-white/70">${escape_html(step)}</div>`);
				}
				$$renderer.push(`<!--]--></div></div></div></section>`);
			} else $$renderer.push("<!--[-1-->");
			$$renderer.push(`<!--]--> <section class="identity-surface-card p-6"><div class="flex flex-col gap-2 lg:flex-row lg:items-end lg:justify-between"><div><p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Operate by desk</p> <h2 class="mt-2 text-lg font-semibold text-white">Keep discovery here, manage changes on dedicated pages.</h2> <p class="mt-2 max-w-3xl text-sm text-white/45">Use the landing page to choose the right operator lane, then move into the dedicated bank, user, identity, report, or correction desk.</p></div> <div class="identity-role-accent">Dedicated desks over overloaded dashboards</div></div> <div class="mt-5 grid gap-3 lg:grid-cols-2 xl:grid-cols-3"><!--[-->`);
			const each_array_3 = ensure_array_like(workflowCards());
			for (let $$index_3 = 0, $$length = each_array_3.length; $$index_3 < $$length; $$index_3++) {
				let card = each_array_3[$$index_3];
				$$renderer.push(`<a${attr("href", card.href)} class="identity-workspace-card p-5 transition-all hover:bg-white/[0.045]"><div class="flex items-start justify-between gap-3"><div${attr_class(`flex h-11 w-11 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.04] ${card.tone}`)}>`);
				if (card.icon) {
					$$renderer.push("<!--[-->");
					card.icon($$renderer, { class: "h-5 w-5" });
					$$renderer.push("<!--]-->");
				} else {
					$$renderer.push("<!--[!-->");
					$$renderer.push("<!--]-->");
				}
				$$renderer.push(`</div> `);
				Arrow_right($$renderer, { class: "mt-1 h-4 w-4 text-white/30" });
				$$renderer.push(`<!----></div> <div class="mt-4 text-sm font-semibold text-white">${escape_html(card.title)}</div> <div class="mt-2 text-[13px] leading-5 text-white/45">${escape_html(card.detail)}</div></a>`);
			}
			$$renderer.push(`<!--]--></div></section>`);
		} else if (section === "lookup") {
			$$renderer.push("<!--[1-->");
			$$renderer.push(`<section class="identity-surface-card p-6"><div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between"><div class="max-w-2xl"><div class="flex items-center gap-2"><h2 class="text-lg font-semibold text-white">Resolve and inspect</h2> <span class="inline-flex items-center gap-1 rounded-full border border-white/[0.08] bg-white/[0.04] px-2 py-1 text-[11px] text-white/45">`);
			Info($$renderer, { class: "h-3.5 w-3.5" });
			$$renderer.push(`<!----> Public routing check</span></div> <p class="mt-2 text-sm text-white/45">Search an alias, a bank-qualified alias, or a raw identity handle. Public resolution returns routing facts only. Internal profile lookup can also show linked bank routes.</p></div> <div class="identity-surface-soft px-4 py-3 text-[12px] text-white/50">\`mtellesy\` -> default bank<br/> \`mtellesy@andalus\` -> explicit bank route</div></div> <div class="mt-5 flex flex-col gap-3 lg:flex-row"><label class="relative flex-1">`);
			Search($$renderer, { class: "pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-white/25" });
			$$renderer.push(`<!----> <input${attr("value", searchQ)} placeholder="Enter NPT handle or handle@bank" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.04] py-3 pl-10 pr-4 text-sm text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none"/></label> <button${attr("disabled", !searchQ.trim(), true)} class="rounded-2xl bg-indigo-600 px-5 py-3 text-sm font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-40">${escape_html("Run lookup")}</button> <button${attr("disabled", (searchQ.trim(), true), true)} class="rounded-2xl border border-white/[0.1] px-5 py-3 text-sm font-medium text-white/65 transition-all hover:border-white/[0.18] hover:text-white disabled:opacity-30">Clear</button></div> `);
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<!--]--> `);
			if (searchQ.trim());
			else $$renderer.push("<!--[-1-->");
			$$renderer.push(`<!--]--> <div class="mt-4 grid gap-2 md:grid-cols-3"><div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3 text-sm text-white/60">Use bare handles only when the customer agreed on a default bank route.</div> <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3 text-sm text-white/60">Use bank-qualified aliases during investigation to eliminate ambiguity.</div> <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3 text-sm text-white/60">Keep this view support-safe. It is for routing and registry control, not full customer data recovery.</div></div> `);
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<!--]--></section>`);
		} else {
			$$renderer.push("<!--[-1-->");
			$$renderer.push(`<section class="identity-surface-card p-6">`);
			if (bankPreview().length) {
				$$renderer.push("<!--[0-->");
				$$renderer.push(`<div class="space-y-3"><!--[-->`);
				const each_array_6 = ensure_array_like(bankPreview());
				for (let $$index_6 = 0, $$length = each_array_6.length; $$index_6 < $$length; $$index_6++) {
					let bank = each_array_6[$$index_6];
					$$renderer.push(`<div class="identity-surface-soft px-4 py-3"><div class="flex items-start justify-between gap-3"><div class="min-w-0"><div class="truncate text-sm font-medium text-white">${escape_html(bank.displayName || bank.name || bank.bankHandle)}</div> <div class="mt-1 truncate font-mono text-[12px] text-white/35">${escape_html(bank.bankHandle || "bank")}</div></div> <span${attr_class(`inline-flex rounded-full border px-2.5 py-1 text-[11px] ${bank.active === false ? "border-red-400/20 bg-red-400/10 text-red-300" : "border-emerald-400/20 bg-emerald-400/10 text-emerald-300"}`)}>${escape_html(bank.active === false ? "Inactive" : "Active")}</span></div> <div class="mt-3 flex items-center justify-between gap-3 border-t border-white/[0.06] pt-3"><div class="text-[12px] text-white/35">${escape_html(bank.branding?.display_name || bank.displayName ? "Directory profile available" : "Profile still needs directory details")}</div> <a${attr("href", `/portal/banks/${bank.bankHandle || bank.handle}`)} class="inline-flex items-center gap-1 text-[12px] text-indigo-200 transition-all hover:text-white">Open bank desk `);
					Arrow_right($$renderer, { class: "h-3.5 w-3.5" });
					$$renderer.push(`<!----></a></div></div>`);
				}
				$$renderer.push(`<!--]--></div>`);
			} else {
				$$renderer.push("<!--[-1-->");
				$$renderer.push(`<div class="text-sm text-white/40">${escape_html(loading ? "Loading banks..." : "No registered banks found.")}</div>`);
			}
			$$renderer.push(`<!--]--></section>`);
		}
		$$renderer.push(`<!--]--></div></div></div>`);
	});
}
//#endregion
export { _page as default };
