<script>
  import { onMount, onDestroy } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { auth } from '$lib/stores/auth';
  import { get } from 'svelte/store';
  import LogOut from 'lucide-svelte/icons/log-out';
  import LayoutDashboard from 'lucide-svelte/icons/layout-dashboard';
  import Users from 'lucide-svelte/icons/users';
  import Building2 from 'lucide-svelte/icons/building-2';
  import Settings from 'lucide-svelte/icons/settings';
  import UserCog from 'lucide-svelte/icons/user-cog';
  import KeyRound from 'lucide-svelte/icons/key-round';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import ClipboardList from 'lucide-svelte/icons/clipboard-list';
  import BellRing from 'lucide-svelte/icons/bell-ring';
  import Moon from 'lucide-svelte/icons/moon';
  import Sun from 'lucide-svelte/icons/sun';
  import Menu from 'lucide-svelte/icons/menu';
  import X from 'lucide-svelte/icons/x';
  import { theme } from '$lib/stores/theme';
  import { toast } from 'svelte-sonner';
  import { getApi } from '$lib/api/client';
  import { passkeysSupported, registrationCredentialToJson, toPublicKeyCreateOptions } from '$lib/utils/passkeys';
  let { children } = $props();
  let session = $state(null);
  let ready   = $state(false);
  let currentTheme = $state('light');
  let profile = $state(null);
  let passkeyCount = $state(null);
  let passkeyPromptDismissed = $state(false);
  let passkeyRegistering = $state(false);
  let mobileNavOpen = $state(false);

  const unsub = auth.subscribe(s => session = s);
  const unsubTheme = theme.subscribe(t => currentTheme = t);
  onDestroy(() => { unsub(); unsubTheme(); });

  onMount(async () => {
    theme.init();
    const s = get(auth);
    if (!s?.role) { goto('/login'); return; }
    session = s;
    ready = true;
    passkeyPromptDismissed = localStorage.getItem('identity_passkey_prompt_dismissed') === 'true';
    await loadSecurityPrompt();
  });

  function logout() {
    auth.logout();
    goto('/login');
  }

  function closeMobileNav() {
    mobileNavOpen = false;
  }

  const allNav = [
    { href: '/portal',              label: 'Dashboard',         icon: LayoutDashboard, exact: true,  roles: ['ADMIN', 'BANK', 'CUSTOMER'] },
    { href: '/portal/identity',     label: 'Identity',          icon: Users,           exact: false, roles: ['ADMIN'] },
    { href: '/portal/identity',     label: 'Customers',         icon: Users,           exact: false, roles: ['BANK'] },
    { href: '/portal/customer',     label: 'My Accounts',       icon: Users,           exact: false, roles: ['CUSTOMER'] },
    { href: '/portal/banks',        label: 'Banks Directory',   icon: Building2,       exact: false, roles: ['ADMIN'] },
    { href: '/portal/my-bank',      label: 'My Bank Desk',      icon: Building2,       exact: false, roles: ['BANK'] },
    { href: '/portal/customer/login-approvals', label: 'Sign-in Activity', icon: BellRing, exact: false, roles: ['CUSTOMER'] },
    { href: '/portal/login-approvals', label: 'Login Approvals', icon: BellRing,     exact: false, roles: ['BANK'] },
    { href: '/portal/reports',      label: 'Reports',           icon: ClipboardList,   exact: false, roles: ['ADMIN', 'BANK'] },
    { href: '/portal/users',        label: 'Users',             icon: UserCog,         exact: false, roles: ['ADMIN', 'BANK'] },
    { href: '/portal/oauth',        label: 'OAuth Control',     icon: ShieldCheck,     exact: false, roles: ['ADMIN'] },
    { href: '/portal/manage',       label: 'Manage',            icon: Settings,        exact: false, roles: ['ADMIN'] },
    { href: '/portal/audit',        label: 'Audit',             icon: ClipboardList,   exact: false, roles: ['ADMIN'] },
    { href: '/portal/security',     label: 'Security',          icon: KeyRound,        exact: false, roles: ['ADMIN', 'BANK', 'CUSTOMER'] },
  ];

  const nav = $derived(allNav.filter(n => !session?.role || n.roles.includes(session.role)));
  const mobileNav = $derived(nav.slice(0, Math.min(nav.length, 5)));

  function isActive(href, exact = false) {
    const path = page.url.pathname;
    return exact ? path === href : path.startsWith(href);
  }

  async function loadSecurityPrompt() {
    try {
      const response = await getApi().get('/auth/profile');
      profile = response.data || null;
      passkeyCount = response.data?.passkeyCount ?? 0;
      const currentPath = `${page.url.pathname}${page.url.search}`;
      if (session?.role === 'CUSTOMER' && response.data?.securitySetupRequired && page.url.pathname !== '/portal/security') {
        await goto(`/portal/security?return=${encodeURIComponent(currentPath)}`);
      }
    } catch {
      profile = null;
      passkeyCount = null;
    }
  }

  function dismissPasskeyPrompt() {
    passkeyPromptDismissed = true;
    localStorage.setItem('identity_passkey_prompt_dismissed', 'true');
  }

  async function addPasskeyFromPrompt() {
    if (!passkeysSupported()) {
      toast.error('Passkeys require a secure browser with WebAuthn support.');
      return;
    }
    passkeyRegistering = true;
    try {
      const api = getApi();
      const optionsResponse = await api.post('/auth/passkey/options/register', {});
      const optionsJson = optionsResponse.data.options;
      const publicKey = toPublicKeyCreateOptions(optionsJson);
      const credential = await navigator.credentials.create({ publicKey });
      if (!credential) throw new Error('Passkey setup was cancelled.');
      await api.post('/auth/passkey/register', {
        challenge: JSON.parse(optionsJson).publicKey.challenge,
        credential: registrationCredentialToJson(credential),
        friendlyName: 'Primary device',
      });
      toast.success('Passkey added');
      passkeyCount = 1;
      dismissPasskeyPrompt();
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || error?.message || 'Could not add passkey');
    } finally {
      passkeyRegistering = false;
    }
  }
</script>

{#if !ready}
  <div class="ow-theme-root min-h-screen bg-[#050508] flex items-center justify-center" data-theme={currentTheme}>
    <div class="w-6 h-6 border-2 border-white/20 border-t-indigo-500 rounded-full animate-spin"></div>
  </div>
{:else}
<div class="ow-theme-root min-h-screen bg-[#050508] text-white flex" data-theme={currentTheme} style="font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', sans-serif;">

  {#if mobileNavOpen}
    <button
      type="button"
      class="identity-shell-overlay fixed inset-0 z-30 bg-black/45 md:hidden"
      aria-label="Close navigation"
      onclick={closeMobileNav}
    ></button>
  {/if}

  <!-- Sidebar -->
  <aside class={`identity-shell-sidebar identity-shell-sidebar-frame fixed inset-y-0 left-0 z-40 flex w-[18rem] shrink-0 flex-col border-r border-white/[0.07] bg-white/[0.025] transition-transform duration-200 md:w-72 xl:w-80 ${
    mobileNavOpen ? 'translate-x-0' : '-translate-x-full md:translate-x-0'
  }`}>

    <!-- Logo -->
    <div class="identity-shell-border px-5 py-5 flex items-center gap-3 border-b border-white/[0.06]">
      <div class="ow-logo-mark shrink-0 !w-8 !h-8 !rounded-xl"><span>OW</span></div>
      <div class="min-w-0">
        <div class="ow-logo-word truncate !text-[13px]">OpenWave Identity</div>
        <div class="ow-logo-sub truncate !text-[10px]">Registry and customer access</div>
      </div>
    </div>

    <!-- Role badge -->
    <div class="px-4 pt-3">
      {#if session?.role === 'ADMIN'}
        <div class="flex items-center gap-2 px-3 py-2 rounded-xl bg-indigo-500/10 border border-indigo-500/20">
          <div class="w-1.5 h-1.5 rounded-full bg-indigo-400 shrink-0"></div>
          <span class="text-[11px] text-indigo-300 font-medium">Registry Admin</span>
        </div>
      {:else if session?.role === 'CUSTOMER'}
        <div class="flex items-center gap-2 px-3 py-2 rounded-xl bg-sky-500/10 border border-sky-500/20">
          <div class="w-1.5 h-1.5 rounded-full bg-sky-400 shrink-0"></div>
          <span class="text-[11px] text-sky-300 font-medium truncate">Customer Identity</span>
        </div>
      {:else}
        <div class="flex items-center gap-2 px-3 py-2 rounded-xl bg-emerald-500/10 border border-emerald-500/20">
          <div class="w-1.5 h-1.5 rounded-full bg-emerald-400 shrink-0"></div>
          <span class="text-[11px] text-emerald-300 font-medium truncate">
            Bank: {session?.bankHandle || 'Portal'}
          </span>
        </div>
      {/if}
    </div>

    <!-- Nav -->
    <nav class="flex-1 px-3 pt-3 space-y-0.5 overflow-y-auto">
      {#each nav as item}
        {@const active = isActive(item.href, item.exact)}
        {@const Icon = item.icon}
        <a
          href={item.href}
          onclick={closeMobileNav}
          class={`identity-nav-link flex min-h-12 items-center gap-3 px-3 py-2.5 rounded-xl text-[13px] font-medium transition-all ${active ? 'is-active bg-white/[0.09] text-white' : 'text-white/55 hover:text-white/85 hover:bg-white/[0.04]'}`}
        >
          <Icon class="w-4 h-4 shrink-0" />
          {item.label}
        </a>
      {/each}
    </nav>

    <!-- Bottom -->
    <div class="identity-shell-border px-4 pb-5 pt-3 border-t border-white/[0.06] space-y-3">
      <div class="identity-shell-faint text-[10px] font-mono truncate">{session?.baseUrl}</div>
      <button
        onclick={logout}
        class="flex min-h-12 items-center gap-2.5 w-full text-[12px] text-red-400/75 hover:text-red-400 transition-colors"
      >
        <LogOut class="w-3.5 h-3.5" />
        Sign out
      </button>
    </div>
  </aside>

  <!-- Main content -->
  <div class="identity-shell-main flex-1 min-h-screen flex flex-col md:ml-72 xl:ml-80">
    <!-- Top bar -->
    <header class="identity-shell-header sticky top-0 z-30 flex items-center justify-between gap-2 border-b border-white/[0.06] bg-white/[0.015] px-4 py-3 backdrop-blur-xl md:px-8">
      <div class="flex min-w-0 items-center gap-2 sm:gap-3">
        <button
          type="button"
          class="identity-shell-button inline-flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.035] text-white/70 transition-all hover:bg-white/[0.06] hover:text-white md:hidden"
          aria-label={mobileNavOpen ? 'Close navigation' : 'Open navigation'}
          onclick={() => (mobileNavOpen = !mobileNavOpen)}
        >
          {#if mobileNavOpen}
            <X class="w-4 h-4" />
          {:else}
            <Menu class="w-4 h-4" />
          {/if}
        </button>
        <div class="identity-shell-muted min-w-0 truncate text-[12px]">
        {#if session?.role === 'ADMIN'}
          <span class="text-indigo-400 font-medium">Registry Admin</span>
          <span class="hidden sm:inline">- {session?.portalRole || 'Registry operations'}</span>
        {:else if session?.role === 'CUSTOMER'}
          <span class="text-sky-400 font-medium">Customer Portal</span>
          <span class="hidden sm:inline">- {session?.portalRole || 'Identity access'}</span>
        {:else}
          <span class="text-emerald-400 font-medium">Bank Portal</span>
          <span class="hidden sm:inline">- {session?.portalRole || 'Scoped operations'}</span>
        {/if}
        </div>
        <div class="identity-role-accent hidden lg:inline-flex">
          {#if session?.role === 'ADMIN'}
            <span class="h-2 w-2 rounded-full bg-indigo-400"></span>
            Registry control lane
          {:else if session?.role === 'CUSTOMER'}
            <span class="h-2 w-2 rounded-full bg-sky-400"></span>
            Personal identity lane
          {:else}
            <span class="h-2 w-2 rounded-full bg-emerald-400"></span>
            Bank-scoped operations
          {/if}
        </div>
      </div>
      <div class="flex shrink-0 items-center gap-2 sm:gap-3">
        <button
          onclick={() => theme.toggle()}
          class="identity-shell-button flex h-12 w-12 shrink-0 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.035] text-white/65 transition-all hover:bg-white/[0.06] hover:text-white"
          title="Toggle theme"
        >
          {#if currentTheme === 'dark'}<Sun class="w-4 h-4" />{:else}<Moon class="w-4 h-4" />{/if}
        </button>
        <div class="shrink-0 rounded-full border px-2.5 py-1 text-[11px]
          {session?.role === 'ADMIN'
            ? 'bg-indigo-500/10 text-indigo-300 border-indigo-500/20'
            : session?.role === 'CUSTOMER'
            ? 'bg-sky-500/10 text-sky-300 border-sky-500/20'
            : 'bg-emerald-500/10 text-emerald-300 border-emerald-500/20'}">
          {session?.role}
        </div>
      </div>
    </header>

    {#if session?.role === 'CUSTOMER' && profile?.securitySetupRequired}
      <div class="mx-8 mt-4 rounded-2xl border border-amber-500/20 bg-amber-500/10 px-4 py-3 flex items-center gap-3">
        <KeyRound class="w-5 h-5 text-amber-300 shrink-0" />
        <div class="min-w-0 flex-1">
          <div class="text-sm font-semibold text-white">Finish customer security setup</div>
          <div class="text-[12px] text-white/50">{profile?.securitySetupReason || 'Add a passkey or confirm an authenticator before using the customer portal.'}</div>
        </div>
        {#if page.url.pathname !== '/portal/security'}
          <a href="/portal/security" class="px-3 py-2 rounded-xl bg-amber-500 hover:bg-amber-400 text-slate-950 text-[12px] font-semibold">
            Open security
          </a>
        {/if}
      </div>
    {:else if passkeyCount === 0 && !passkeyPromptDismissed && passkeysSupported()}
      <div class="mx-8 mt-4 rounded-2xl border border-indigo-500/20 bg-indigo-500/10 px-4 py-3 flex items-center gap-3">
        <KeyRound class="w-5 h-5 text-indigo-300 shrink-0" />
        <div class="min-w-0 flex-1">
          <div class="text-sm font-semibold text-white">Add a passkey for this portal account</div>
          <div class="text-[12px] text-white/40">Use your device biometrics or screen lock for faster, safer future sign-ins.</div>
        </div>
        <button onclick={addPasskeyFromPrompt} disabled={passkeyRegistering} class="px-3 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-30 text-white text-[12px] font-semibold">
          {passkeyRegistering ? 'Adding...' : 'Add passkey'}
        </button>
        <button onclick={dismissPasskeyPrompt} class="px-3 py-2 rounded-xl border border-white/[0.08] text-white/50 hover:text-white/80 text-[12px]">
          Later
        </button>
      </div>
    {/if}

    <!-- Page -->
    <main class="flex-1 overflow-auto pb-24 md:pb-0">
      {@render children()}
    </main>

    <nav class="identity-mobile-nav fixed inset-x-0 bottom-0 z-30 border-t border-white/[0.08] bg-[#080b12]/92 px-2 py-2 backdrop-blur-xl md:hidden" aria-label="Primary navigation">
      <div class="grid grid-cols-5 gap-1">
        {#each mobileNav as item}
          {@const active = isActive(item.href, item.exact)}
          {@const Icon = item.icon}
          <a
            href={item.href}
            onclick={closeMobileNav}
            class={`identity-mobile-nav-link flex min-h-[60px] flex-col items-center justify-center gap-1 rounded-2xl px-2 py-2 text-center transition-all ${
              active ? 'is-active text-white' : 'text-white/55'
            }`}
          >
            <Icon class="w-4 h-4 shrink-0" />
            <span class="line-clamp-2 text-[10px] font-medium leading-tight">{item.label}</span>
          </a>
        {/each}
      </div>
    </nav>
  </div>
</div>
{/if}
