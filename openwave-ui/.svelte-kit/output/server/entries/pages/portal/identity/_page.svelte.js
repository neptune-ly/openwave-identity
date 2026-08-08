import { et as attr, f as ensure_array_like, nt as escape_html, o as attr_class, p as head, u as derived } from "../../../../chunks/index-server.js";
import "../../../../chunks/index-server2.js";
import "../../../../chunks/client.js";
import "../../../../chunks/state.js";
import "../../../../chunks/navigation.js";
import "../../../../chunks/auth.js";
import { t as Building_2 } from "../../../../chunks/building-2.js";
import { t as Clipboard_list } from "../../../../chunks/clipboard-list.js";
import { t as Route } from "../../../../chunks/route.js";
import { t as Info } from "../../../../chunks/info.js";
import { t as Arrow_right } from "../../../../chunks/arrow-right.js";
import { t as Refresh_cw } from "../../../../chunks/refresh-cw.js";
import { t as User_plus } from "../../../../chunks/user-plus.js";
import { n as Unlink_2, r as Link_2, t as Pencil_line } from "../../../../chunks/pencil-line.js";
//#region src/routes/portal/identity/+page.svelte
function _page($$renderer, $$props) {
	$$renderer.component(($$renderer) => {
		let session = null;
		const actionCards = [
			{
				key: "claim",
				title: "Claim handle",
				description: "Create a customer identity and establish the first bank-backed account route.",
				icon: User_plus,
				tone: "text-indigo-300"
			},
			{
				key: "rename",
				title: "Rename NPT handle",
				description: "Authenticate a customer-requested rename, preflight availability, and permanently retire the old payment address.",
				icon: Pencil_line,
				tone: "text-cyan-300"
			},
			{
				key: "link",
				title: "Link account",
				description: "Attach another IBAN for the same customer identity within the current bank scope.",
				icon: Link_2,
				tone: "text-emerald-300"
			},
			{
				key: "unlink",
				title: "Unlink account",
				description: "Remove an outdated or invalid route from the selected customer alias.",
				icon: Unlink_2,
				tone: "text-rose-300"
			},
			{
				key: "default-account",
				title: "Default IBAN",
				description: "Choose which IBAN resolves when the payer selects a bank-specific alias.",
				icon: Route,
				tone: "text-amber-300"
			},
			{
				key: "default-bank",
				title: "Default bank",
				description: "Set which bank answers a bare NPT handle without an explicit bank suffix.",
				icon: Building_2,
				tone: "text-sky-300"
			}
		];
		const isBank = derived(() => session?.role === "BANK");
		function hintClass() {
			return "inline-flex h-4 w-4 cursor-help text-white/40";
		}
		head("uqsks6", $$renderer, ($$renderer) => {
			$$renderer.title(($$renderer) => {
				$$renderer.push(`<title>Identity Operations - OpenWave Identity</title>`);
			});
		});
		$$renderer.push(`<div class="p-8 max-w-7xl mx-auto space-y-6"><section class="identity-expressive-band p-6"><div class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between"><div class="max-w-3xl"><p class="text-[11px] uppercase tracking-[0.18em] text-white/30">${escape_html(isBank() ? "Bank identity desk" : "Registry identity desk")}</p> <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight">Identity Operations</h1> <p class="identity-section-note mt-2 text-sm text-white/55">Keep this page focused on choosing the right workflow. Open a dedicated route desk for claim, customer-directed rename, linking, routing, or removal instead of stacking every operator action into one screen.</p> <div class="mt-3 flex flex-wrap gap-2 text-xs text-white/45"><span class="identity-role-accent">Dedicated flow desks <span class="tooltip max-w-xs" data-tip="Each identity workflow now has its own route so operators can deep-link into one task at a time instead of sharing one overloaded page.">`);
		Info($$renderer, { class: hintClass() });
		$$renderer.push(`<!----></span></span> <span class="identity-role-accent">Bank-vouched routing</span> <span class="identity-role-accent">Preflight-first actions</span></div></div> <div class="flex flex-wrap gap-2"><a href="/portal/reports" class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white">`);
		Clipboard_list($$renderer, { class: "w-4 h-4" });
		$$renderer.push(`<!----> Reports</a> <a href="/portal/identity/claim" class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white">`);
		Refresh_cw($$renderer, { class: "w-4 h-4" });
		$$renderer.push(`<!----> Start claim</a></div></div> <div class="mt-4 grid gap-3 md:grid-cols-3"><div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Operator scope</div> <div class="mt-2 text-lg font-semibold text-white">${escape_html(isBank() ? session?.bankHandle || "Bank scope" : "Global registry")}</div> <div class="mt-1 text-[12px] text-white/45">${escape_html(isBank() ? "Write actions remain bank-scoped unless the flow explicitly says otherwise." : "Admin actions can correct routing across the full registry.")}</div></div> <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Workflow count</div> <div class="mt-2 text-lg font-semibold text-white">${escape_html(actionCards.length)} focused desks</div> <div class="mt-1 text-[12px] text-white/45">Each route owns one identity change instead of combining every action on one page.</div></div> <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Primary job</div> <div class="mt-2 text-lg font-semibold text-white">Choose one flow, then operate</div> <div class="mt-1 text-[12px] text-white/45">Discovery stays here; form work, preflight, and result context move to the dedicated flow page.</div></div></div></section> <section class="identity-surface-card p-6"><div class="flex flex-col gap-2 lg:flex-row lg:items-end lg:justify-between"><div><p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Identity workflows</p> <h2 class="mt-2 text-lg font-semibold text-white">Open one focused desk at a time.</h2> <p class="mt-2 max-w-3xl text-sm text-white/45">Claims, permanent handle renames, route linking, unlinking, default-account changes, and default-bank changes have different risk. They should not compete for attention on one page.</p></div> <div class="identity-role-accent">No mixed workflow page</div></div> <div class="mt-5 grid gap-3 lg:grid-cols-2 xl:grid-cols-3"><!--[-->`);
		const each_array = ensure_array_like(actionCards);
		for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
			let card = each_array[$$index];
			$$renderer.push(`<a${attr("href", `/portal/identity/${card.key}`)} class="identity-workspace-card p-5 transition-all hover:bg-white/[0.045]"><div class="flex items-start justify-between gap-3"><div${attr_class(`flex h-11 w-11 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.04] ${card.tone}`)}>`);
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
			$$renderer.push(`<!----></div> <div class="mt-4 text-sm font-semibold text-white">${escape_html(card.title)}</div> <div class="mt-2 text-[13px] leading-5 text-white/45">${escape_html(card.description)}</div></a>`);
		}
		$$renderer.push(`<!--]--></div></section></div>`);
	});
}
//#endregion
export { _page as default };
