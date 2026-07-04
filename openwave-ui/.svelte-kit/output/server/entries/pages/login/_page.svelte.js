import { et as attr, f as ensure_array_like, n as onDestroy, nt as escape_html, p as head } from "../../../chunks/index-server.js";
import "../../../chunks/index-server2.js";
import { t as theme } from "../../../chunks/theme.js";
import "../../../chunks/client.js";
import "../../../chunks/navigation.js";
import { t as configuredRegistryUrl } from "../../../chunks/config.js";
import "../../../chunks/auth.js";
import { t as Key_round } from "../../../chunks/key-round.js";
import { n as Moon, t as Sun } from "../../../chunks/sun.js";
import { a as Card_header, i as Card_title, n as Input, o as Card, r as Card_content, s as Button, t as Label } from "../../../chunks/label.js";
import "axios";
//#region src/routes/login/+page.svelte
function _page($$renderer, $$props) {
	$$renderer.component(($$renderer) => {
		configuredRegistryUrl();
		let username = "";
		let password = "";
		let loading = false;
		let mode = "";
		let currentTheme = "light";
		let bankApprovalTimer = null;
		const portalModes = [
			{
				value: "admin",
				label: "Registry Admin",
				hint: "Directory and system controls"
			},
			{
				value: "bank",
				label: "Bank Portal",
				hint: "Enrollment and bank-scoped identity ops"
			},
			{
				value: "customer",
				label: "Customer",
				hint: "Self-service identity dashboard"
			}
		];
		const unsubTheme = theme.subscribe((t) => currentTheme = t);
		onDestroy(() => {
			unsubTheme();
			stopBankApprovalPolling();
		});
		function stopBankApprovalPolling() {
			if (bankApprovalTimer) {
				clearInterval(bankApprovalTimer);
				bankApprovalTimer = null;
			}
		}
		let $$settled = true;
		let $$inner_renderer;
		function $$render_inner($$renderer) {
			head("1x05zx6", $$renderer, ($$renderer) => {
				$$renderer.title(($$renderer) => {
					$$renderer.push(`<title>Sign In - OpenWave Identity Registry</title>`);
				});
			});
			$$renderer.push(`<div class="identity-auth-shell ow-theme-root"${attr("data-theme", currentTheme)}><main class="identity-auth-frame mx-auto w-full max-w-[30rem]">`);
			Card($$renderer, {
				class: "identity-auth-card border shadow-xl",
				children: ($$renderer) => {
					Card_header($$renderer, {
						class: "space-y-2 pb-4",
						children: ($$renderer) => {
							$$renderer.push(`<div class="flex items-center justify-between gap-3"><div>`);
							Card_title($$renderer, {
								class: "identity-auth-title",
								children: ($$renderer) => {
									$$renderer.push(`<!---->OpenWave Identity`);
								},
								$$slots: { default: true }
							});
							$$renderer.push(`<!----></div> `);
							Button($$renderer, {
								type: "button",
								variant: "ghost",
								size: "icon",
								"aria-label": "Toggle theme",
								children: ($$renderer) => {
									if (currentTheme === "dark") {
										$$renderer.push("<!--[0-->");
										Sun($$renderer, { class: "h-4 w-4" });
									} else {
										$$renderer.push("<!--[-1-->");
										Moon($$renderer, { class: "h-4 w-4" });
									}
									$$renderer.push(`<!--]-->`);
								},
								$$slots: { default: true }
							});
							$$renderer.push(`<!----></div>`);
						},
						$$slots: { default: true }
					});
					$$renderer.push(`<!----> `);
					Card_content($$renderer, {
						class: "identity-auth-form",
						children: ($$renderer) => {
							{
								$$renderer.push("<!--[0-->");
								$$renderer.push(`<div class="grid grid-cols-1 gap-2 sm:grid-cols-3"><!--[-->`);
								const each_array = ensure_array_like(portalModes);
								for (let $$index = 0, $$length = each_array.length; $$index < $$length; $$index++) {
									let p = each_array[$$index];
									Button($$renderer, {
										type: "button",
										variant: mode === p.value ? "default" : "outline",
										class: "h-auto px-3 py-2 text-left",
										children: ($$renderer) => {
											$$renderer.push(`<div class="text-sm font-semibold">${escape_html(p.label)}</div>`);
										},
										$$slots: { default: true }
									});
								}
								$$renderer.push(`<!--]--></div>`);
							}
							$$renderer.push(`<!--]--> `);
							$$renderer.push("<!--[-1-->");
							$$renderer.push(`<form class="identity-auth-form">`);
							$$renderer.push("<!--[-1-->");
							$$renderer.push(`<div class="space-y-2">`);
							Label($$renderer, {
								for: "identity-username",
								children: ($$renderer) => {
									$$renderer.push(`<!---->${escape_html(mode === "customer" ? "Username / Email / Phone / National ID" : "Username")}`);
								},
								$$slots: { default: true }
							});
							$$renderer.push(`<!----> `);
							Input($$renderer, {
								id: "identity-username",
								disabled: loading,
								placeholder: mode === "customer" ? "username, email, phone, or national ID" : "portal username",
								get value() {
									return username;
								},
								set value($$value) {
									username = $$value;
									$$settled = false;
								}
							});
							$$renderer.push(`<!----></div> `);
							$$renderer.push("<!--[-1-->");
							$$renderer.push(`<!--]--> <div class="space-y-2"><div class="flex items-center justify-between gap-3">`);
							Label($$renderer, {
								for: "identity-password",
								children: ($$renderer) => {
									$$renderer.push(`<!---->Password`);
								},
								$$slots: { default: true }
							});
							$$renderer.push(`<!----> `);
							Button($$renderer, {
								type: "button",
								variant: "ghost",
								size: "sm",
								children: ($$renderer) => {
									$$renderer.push(`<!---->Forgot password?`);
								},
								$$slots: { default: true }
							});
							$$renderer.push(`<!----></div> `);
							Input($$renderer, {
								id: "identity-password",
								type: "password",
								disabled: loading,
								placeholder: "Portal password",
								get value() {
									return password;
								},
								set value($$value) {
									password = $$value;
									$$settled = false;
								}
							});
							$$renderer.push(`<!----></div> <div class="grid gap-2">`);
							Button($$renderer, {
								class: "w-full",
								disabled: true,
								children: ($$renderer) => {
									$$renderer.push("<!--[-1-->");
									$$renderer.push(`Sign in as ${escape_html("Portal user")}`);
									$$renderer.push(`<!--]-->`);
								},
								$$slots: { default: true }
							});
							$$renderer.push(`<!----> `);
							Button($$renderer, {
								type: "button",
								variant: "outline",
								disabled: true,
								class: "w-full",
								children: ($$renderer) => {
									$$renderer.push(`<span class="inline-flex items-center gap-2">`);
									Key_round($$renderer, { class: "h-4 w-4" });
									$$renderer.push(`<!---->Sign in with passkey</span>`);
								},
								$$slots: { default: true }
							});
							$$renderer.push(`<!----></div>`);
							$$renderer.push(`<!--]--></form>`);
							$$renderer.push(`<!--]-->`);
						},
						$$slots: { default: true }
					});
					$$renderer.push(`<!---->`);
				},
				$$slots: { default: true }
			});
			$$renderer.push(`<!----></main></div>`);
		}
		do {
			$$settled = true;
			$$inner_renderer = $$renderer.copy();
			$$render_inner($$inner_renderer);
		} while (!$$settled);
		$$renderer.subsume($$inner_renderer);
	});
}
//#endregion
export { _page as default };
