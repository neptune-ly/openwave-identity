
// this file is generated — do not edit it


declare module "svelte/elements" {
	export interface HTMLAttributes<T> {
		'data-sveltekit-keepfocus'?: true | '' | 'off' | undefined | null;
		'data-sveltekit-noscroll'?: true | '' | 'off' | undefined | null;
		'data-sveltekit-preload-code'?:
			| true
			| ''
			| 'eager'
			| 'viewport'
			| 'hover'
			| 'tap'
			| 'off'
			| undefined
			| null;
		'data-sveltekit-preload-data'?: true | '' | 'hover' | 'tap' | 'off' | undefined | null;
		'data-sveltekit-reload'?: true | '' | 'off' | undefined | null;
		'data-sveltekit-replacestate'?: true | '' | 'off' | undefined | null;
	}
}

export {};


declare module "$app/types" {
	type MatcherParam<M> = M extends (param : string) => param is (infer U extends string) ? U : string;

	export interface AppTypes {
		RouteId(): "/" | "/login" | "/portal" | "/portal/audit" | "/portal/audit/[id]" | "/portal/banks" | "/portal/banks/register" | "/portal/banks/[handle]" | "/portal/customer" | "/portal/customer/login-approvals" | "/portal/customer/login-approvals/[challengeId]" | "/portal/identity" | "/portal/identity/[flow]" | "/portal/login-approvals" | "/portal/login-approvals/[challengeId]" | "/portal/manage" | "/portal/my-bank" | "/portal/oauth-consent" | "/portal/oauth" | "/portal/reports" | "/portal/reports/[alias]" | "/portal/security" | "/portal/users" | "/portal/users/create" | "/portal/users/[id]" | "/reset-password";
		RouteParams(): {
			"/portal/audit/[id]": { id: string };
			"/portal/banks/[handle]": { handle: string };
			"/portal/customer/login-approvals/[challengeId]": { challengeId: string };
			"/portal/identity/[flow]": { flow: string };
			"/portal/login-approvals/[challengeId]": { challengeId: string };
			"/portal/reports/[alias]": { alias: string };
			"/portal/users/[id]": { id: string }
		};
		LayoutParams(): {
			"/": { id?: string; handle?: string; challengeId?: string; flow?: string; alias?: string };
			"/login": Record<string, never>;
			"/portal": { id?: string; handle?: string; challengeId?: string; flow?: string; alias?: string };
			"/portal/audit": { id?: string };
			"/portal/audit/[id]": { id: string };
			"/portal/banks": { handle?: string };
			"/portal/banks/register": Record<string, never>;
			"/portal/banks/[handle]": { handle: string };
			"/portal/customer": { challengeId?: string };
			"/portal/customer/login-approvals": { challengeId?: string };
			"/portal/customer/login-approvals/[challengeId]": { challengeId: string };
			"/portal/identity": { flow?: string };
			"/portal/identity/[flow]": { flow: string };
			"/portal/login-approvals": { challengeId?: string };
			"/portal/login-approvals/[challengeId]": { challengeId: string };
			"/portal/manage": Record<string, never>;
			"/portal/my-bank": Record<string, never>;
			"/portal/oauth-consent": Record<string, never>;
			"/portal/oauth": Record<string, never>;
			"/portal/reports": { alias?: string };
			"/portal/reports/[alias]": { alias: string };
			"/portal/security": Record<string, never>;
			"/portal/users": { id?: string };
			"/portal/users/create": Record<string, never>;
			"/portal/users/[id]": { id: string };
			"/reset-password": Record<string, never>
		};
		Pathname(): "/" | "/login" | "/portal" | "/portal/audit" | `/portal/audit/${string}` & {} | "/portal/banks" | "/portal/banks/register" | `/portal/banks/${string}` & {} | "/portal/customer" | "/portal/customer/login-approvals" | `/portal/customer/login-approvals/${string}` & {} | "/portal/identity" | `/portal/identity/${string}` & {} | "/portal/login-approvals" | `/portal/login-approvals/${string}` & {} | "/portal/manage" | "/portal/my-bank" | "/portal/oauth-consent" | "/portal/oauth" | "/portal/reports" | `/portal/reports/${string}` & {} | "/portal/security" | "/portal/users" | "/portal/users/create" | `/portal/users/${string}` & {} | "/reset-password";
		ResolvedPathname(): `${"" | `/${string}`}${ReturnType<AppTypes['Pathname']>}`;
		Asset(): "/favicon.svg" | string & {};
	}
}