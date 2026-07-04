export { matchers } from './matchers.js';

export const nodes = [
	() => import('./nodes/0'),
	() => import('./nodes/1'),
	() => import('./nodes/2'),
	() => import('./nodes/3'),
	() => import('./nodes/4'),
	() => import('./nodes/5'),
	() => import('./nodes/6'),
	() => import('./nodes/7'),
	() => import('./nodes/8'),
	() => import('./nodes/9'),
	() => import('./nodes/10'),
	() => import('./nodes/11'),
	() => import('./nodes/12'),
	() => import('./nodes/13'),
	() => import('./nodes/14'),
	() => import('./nodes/15'),
	() => import('./nodes/16'),
	() => import('./nodes/17'),
	() => import('./nodes/18'),
	() => import('./nodes/19'),
	() => import('./nodes/20'),
	() => import('./nodes/21'),
	() => import('./nodes/22'),
	() => import('./nodes/23'),
	() => import('./nodes/24'),
	() => import('./nodes/25'),
	() => import('./nodes/26'),
	() => import('./nodes/27'),
	() => import('./nodes/28')
];

export const server_loads = [];

export const dictionary = {
		"/": [3],
		"/login": [4],
		"/portal": [5,[2]],
		"/portal/audit": [6,[2]],
		"/portal/audit/[id]": [7,[2]],
		"/portal/banks": [8,[2]],
		"/portal/banks/register": [10,[2]],
		"/portal/banks/[handle]": [9,[2]],
		"/portal/customer": [11,[2]],
		"/portal/customer/login-approvals": [12,[2]],
		"/portal/customer/login-approvals/[challengeId]": [13,[2]],
		"/portal/identity": [14,[2]],
		"/portal/identity/[flow]": [15,[2]],
		"/portal/login-approvals": [16,[2]],
		"/portal/login-approvals/[challengeId]": [17,[2]],
		"/portal/manage": [18,[2]],
		"/portal/my-bank": [19,[2]],
		"/portal/oauth-consent": [21,[2]],
		"/portal/oauth": [20,[2]],
		"/portal/reports": [22,[2]],
		"/portal/reports/[alias]": [23,[2]],
		"/portal/security": [24,[2]],
		"/portal/users": [25,[2]],
		"/portal/users/create": [27,[2]],
		"/portal/users/[id]": [26,[2]],
		"/reset-password": [28]
	};

export const hooks = {
	handleError: (({ error }) => { console.error(error) }),
	
	reroute: (() => {}),
	transport: {}
};

export const decoders = Object.fromEntries(Object.entries(hooks.transport).map(([k, v]) => [k, v.decode]));
export const encoders = Object.fromEntries(Object.entries(hooks.transport).map(([k, v]) => [k, v.encode]));

export const hash = false;

export const decode = (type, value) => decoders[type](value);

export { default as root } from '../root.js';