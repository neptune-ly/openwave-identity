<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import { auth } from '$lib/stores/auth';
  import { apiCall } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import UserPlus from 'lucide-svelte/icons/user-plus';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import Building2 from 'lucide-svelte/icons/building-2';
  import UserCog from 'lucide-svelte/icons/user-cog';
  import Search from 'lucide-svelte/icons/search';
  import CircleAlert from 'lucide-svelte/icons/circle-alert';
  import CircleCheckBig from 'lucide-svelte/icons/circle-check-big';
  import Clock3 from 'lucide-svelte/icons/clock-3';
  import Info from 'lucide-svelte/icons/info';

  let session = $state(null);
  let users = $state([]);
  let banks = $state([]);
  let loading = $state(true);
  let search = $state('');
  let roleFilter = $state('all');
  let currentSection = $state('registry');

  const registryRoles = ['REGISTRY_ADMIN', 'REGISTRY_OPERATOR'];
  const bankRoles = ['BANK_ADMIN', 'BANK_OPERATOR', 'BANK_VIEWER'];
  const roleLabels = {
    REGISTRY_ADMIN: 'Registry Admin',
    REGISTRY_OPERATOR: 'Registry Operator',
    BANK_ADMIN: 'Bank Admin',
    BANK_OPERATOR: 'Bank Operator',
    BANK_VIEWER: 'Bank Viewer'
  };

  const visibleRoles = $derived(session?.role === 'ADMIN' ? [...registryRoles, ...bankRoles] : bankRoles);
  const filteredUsers = $derived(filterUsers(users, search, roleFilter));
  const bankScopedUsers = $derived(users.filter((user) => user.bankHandle).length);
  const suspendedUsers = $derived(users.filter((user) => !user.active).length);
  const recentLoginCount = $derived(users.filter((user) => user.lastLoginAt).length);

  onMount(async () => {
    session = get(auth);
    hydrateRouteState();
    await Promise.all([loadUsers(), loadBanks()]);
    redirectLegacyCreateRoute();
  });

  function hydrateRouteState() {
    const current = get(page).url.searchParams;
    search = current.get('search') ?? '';
    roleFilter = current.get('role') ?? 'all';
    currentSection = ['provisioning'].includes(current.get('section') ?? '') ? current.get('section') : 'registry';
  }

  async function loadUsers() {
    loading = true;
    const response = await apiCall('get', '/portal-users');
    loading = false;
    if (!response.ok) {
      toast.error(response.error);
      users = [];
      return;
    }
    users = response.data.users || [];
  }

  async function loadBanks() {
    const response = await apiCall('get', '/banks');
    if (response.ok) banks = response.data.banks || [];
  }

  async function redirectLegacyCreateRoute() {
    const current = get(page).url.searchParams;
    if (current.get('section') !== 'create') return;
    const next = new URLSearchParams(current);
    next.delete('section');
    const query = next.toString();
    await goto(query ? `/portal/users/create?${query}` : '/portal/users/create', { replaceState: true, noScroll: true, keepFocus: true });
  }

  async function syncRouteState() {
    const next = new URL(get(page).url);
    if (search.trim()) next.searchParams.set('search', search.trim());
    else next.searchParams.delete('search');
    if (roleFilter !== 'all') next.searchParams.set('role', roleFilter);
    else next.searchParams.delete('role');
    if (currentSection !== 'registry') next.searchParams.set('section', currentSection);
    else next.searchParams.delete('section');
    await goto(`${next.pathname}${next.search}`, { replaceState: true, noScroll: true, keepFocus: true });
  }

  async function syncSection(section) {
    currentSection = section;
    await syncRouteState();
  }

  function filterUsers(allUsers, query, role) {
    const normalized = query.trim().toLowerCase();
    return allUsers.filter((user) => {
      if (role !== 'all' && user.role !== role) return false;
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
        label: 'Active users',
        value: String(users.filter((user) => user.active).length),
        icon: CircleCheckBig
      },
      {
        label: 'Suspended',
        value: String(suspendedUsers),
        icon: CircleAlert
      },
      {
        label: 'Bank scoped',
        value: String(bankScopedUsers),
        icon: Building2
      },
      {
        label: 'Logged in before',
        value: String(recentLoginCount),
        icon: Clock3
      }
    ];
  }

  const sectionMeta = {
    registry: {
      label: 'Access registry',
      hint: 'Search users and open the correct desk',
      purpose: 'Use this section for discovery only. Open a dedicated user desk for edits, scope changes, and reset work.',
      action: 'Open a user desk'
    },
    provisioning: {
      label: 'Provisioning',
      hint: 'Create new portal access safely',
      purpose: 'Use the dedicated create flow for one-time credentials and role assignment. Keep the registry page clean.',
      action: 'Open create flow'
    }
  };

  function hintClass() {
    return 'inline-flex h-4 w-4 cursor-help text-white/40';
  }
</script>

<svelte:head><title>Portal Users - OpenWave Identity</title></svelte:head>

<div class="mx-auto max-w-7xl space-y-6 p-8">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
      <div class="max-w-3xl">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">
          {session?.role === 'ADMIN' ? 'Registry access control desk' : 'Bank operator access desk'}
        </p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">Portal Users</h1>
        <p class="identity-section-note mt-2 text-sm text-white/55">
          Keep this page focused on access discovery and provisioning. Open any operator on its own desk to manage scope, suspension, and credential recovery.
        </p>
        <div class="mt-3 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">{session?.role === 'ADMIN' ? 'Registry-wide provisioning' : 'Bank-scoped provisioning'}</span>
          <span class="identity-role-accent">Dedicated user desks</span>
          <span class="identity-role-accent">One-time credential handoff</span>
        </div>
        <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="inline-flex items-center gap-1 rounded-full border border-white/[0.08] px-2.5 py-1">
            Scoped access
            <span class="tooltip max-w-xs" data-tip="Bank roles stay bank-scoped. Registry roles stay global and should be provisioned sparingly.">
              <Info class={hintClass()} />
            </span>
          </span>
          <span class="inline-flex items-center gap-1 rounded-full border border-white/[0.08] px-2.5 py-1">
            Dedicated desks
            <span class="tooltip max-w-xs" data-tip="Use the user record page for edits and resets. This registry screen should stay clean and searchable.">
              <Info class={hintClass()} />
            </span>
          </span>
        </div>
      </div>
      <div class="grid gap-3 sm:grid-cols-2">
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Provisioning rule</div>
          <div class="mt-2 text-sm font-medium text-white">Grant only the minimum role and scope needed.</div>
          <div class="mt-1 text-[12px] text-white/45">Use bank roles for bank desks and keep registry roles tightly controlled.</div>
        </div>
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Recovery posture</div>
          <div class="mt-2 text-sm font-medium text-white">Use secure email handoff when available.</div>
          <div class="mt-1 text-[12px] text-white/45">Temporary passwords are shown once and should not linger in the portal.</div>
        </div>
      </div>
    </div>
  </section>

  <div class="flex flex-wrap justify-end gap-2">
    <button onclick={loadUsers} disabled={loading} class="inline-flex items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2 text-[13px] font-medium text-white/60 transition-all hover:border-white/[0.18] hover:text-white disabled:opacity-40">
      <RefreshCw class={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
      Refresh
    </button>
    <button onclick={() => goto('/portal/users/create')} class="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500">
      <UserPlus class="h-4 w-4" />
      Create access
    </button>
  </div>

  <section class="identity-desk-grid">
    <aside class="identity-desk-rail">
      <p class="identity-desk-rail-title">User desk</p>
      <div class="rounded-2xl border border-white/[0.08] bg-black/10 px-4 py-3 text-sm text-white/45">
        This page is for discovery and provisioning only. Open a dedicated user desk for edits, suspension, and credential recovery.
      </div>
      <div class="identity-desk-nav" role="tablist" aria-label="Portal user sections">
        {#each Object.entries(sectionMeta) as [key, item]}
          <button
            type="button"
            onclick={() => syncSection(key)}
            class={`identity-desk-nav-item ${currentSection === key ? 'is-active' : ''}`}
            title={`${item.label} · ${item.hint}`}
          >
            <div class="identity-desk-nav-copy">
              <div class="identity-desk-nav-label">{item.label}</div>
              <div class="identity-desk-nav-hint">{item.hint}</div>
            </div>
          </button>
        {/each}
      </div>
    </aside>

    <div class="identity-desk-panel">
      <section class="identity-desk-header">
        <div class="identity-desk-header-grid">
          <div>
            <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Current section</p>
            <h2 class="mt-1 text-lg font-semibold text-white">{sectionMeta[currentSection].label}</h2>
            <p class="mt-2 text-sm text-white/45">{sectionMeta[currentSection].purpose}</p>
          </div>
          <div class="identity-desk-header-stats">
            <div class="identity-desk-header-stat">
              <div class="identity-desk-header-stat-label">Active users</div>
              <div class="identity-desk-header-stat-value">{users.filter((user) => user.active).length}</div>
            </div>
            <div class="identity-desk-header-stat">
              <div class="identity-desk-header-stat-label">Bank scoped</div>
              <div class="identity-desk-header-stat-value">{bankScopedUsers}</div>
            </div>
            <div class="identity-desk-header-stat">
              <div class="identity-desk-header-stat-label">Next action</div>
              <div class="identity-desk-header-stat-value">{sectionMeta[currentSection].action}</div>
            </div>
          </div>
        </div>
      </section>

      {#if currentSection === 'registry'}
    <section class="identity-surface-card overflow-hidden">
      <div class="border-b border-white/[0.06] px-5 py-4">
        <div class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <div>
            <div class="text-sm font-semibold">Access registry</div>
            <div class="mt-1 text-[12px] text-white/35">Search portal users and open a dedicated desk for each record.</div>
          </div>
          <div class="flex flex-col gap-2 sm:flex-row">
            <label class="relative block">
              <Search class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-white/25" />
              <input bind:value={search} oninput={() => syncRouteState()} placeholder="Search users, scope, email" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] py-2 pl-9 pr-3 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none sm:w-64" />
            </label>
            <select bind:value={roleFilter} onchange={() => syncRouteState()} class="rounded-xl border border-white/[0.1] bg-white/[0.04] px-3 py-2 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none">
              <option value="all">All roles</option>
              {#each visibleRoles as role}
                <option value={role}>{roleLabels[role]}</option>
              {/each}
            </select>
          </div>
        </div>
      </div>

      <div class="overflow-x-auto">
        <div class="grid min-w-[940px] grid-cols-[minmax(0,1.2fr)_160px_140px_120px_110px] gap-4 border-b border-white/[0.06] px-5 py-3 text-[11px] uppercase tracking-[0.16em] text-white/30">
          <span>User</span>
          <span>Role</span>
          <span>Scope</span>
          <span>Status</span>
          <span class="text-right">Action</span>
        </div>

        {#if loading}
          <div class="p-10 text-center text-sm text-white/35">Loading portal users...</div>
        {:else if filteredUsers.length === 0}
          <div class="p-10 text-center text-sm text-white/35">No portal users matched the current filters.</div>
        {:else}
          <div class="divide-y divide-white/[0.05]">
            {#each filteredUsers as user}
              <button type="button" onclick={() => goto(`/portal/users/${user.id}`)} class="grid min-w-[940px] w-full grid-cols-[minmax(0,1.2fr)_160px_140px_120px_110px] gap-4 px-5 py-4 text-left transition-colors hover:bg-white/[0.03]">
                <div class="min-w-0">
                  <div class="truncate text-sm font-medium text-white">{user.displayName}</div>
                  <div class="mt-1 truncate font-mono text-[12px] text-white/35">{user.username}</div>
                  <div class="mt-1 truncate text-[12px] text-white/28">{user.email || 'No recovery email configured'}</div>
                </div>
                <div class="flex items-center gap-2 text-[12px] text-white/65">
                  <ShieldCheck class="h-3.5 w-3.5 text-indigo-300" />
                  {roleLabels[user.role] || user.role}
                </div>
                <div class="flex items-center gap-2 text-[12px] text-white/45">
                  <Building2 class="h-3.5 w-3.5 text-white/25" />
                  {user.bankHandle || 'Registry'}
                </div>
                <div>
                  <span class={`inline-flex items-center rounded-full border px-2.5 py-1 text-[11px] ${user.active ? 'border-emerald-400/20 bg-emerald-400/10 text-emerald-300' : 'border-red-400/20 bg-red-400/10 text-red-300'}`}>
                    {user.active ? 'Active' : 'Suspended'}
                  </span>
                </div>
                <div class="flex justify-end">
                  <span class="inline-flex items-center rounded-full border border-white/[0.08] bg-white/[0.04] px-2.5 py-1 text-[11px] text-white/55">Open desk</span>
                </div>
              </button>
            {/each}
          </div>
        {/if}
      </div>
    </section>

    <section class="identity-surface-card p-5">
      <div class="grid gap-3 md:grid-cols-4">
        {#each summaryItems() as item}
          <section class="identity-surface-soft px-4 py-4">
            <div class="flex items-center gap-3">
              <div class="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-indigo-300">
                <item.icon class="h-5 w-5" />
              </div>
              <div>
                <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">{item.label}</p>
                <p class="mt-1 text-xl font-semibold">{item.value}</p>
              </div>
            </div>
          </section>
        {/each}
      </div>
    </section>
      {:else}
    <section class="identity-surface-card p-5">
      <div class="flex h-full min-h-[420px] flex-col justify-between gap-5">
        <div class="space-y-4">
          <div class="flex h-14 w-14 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.04] text-indigo-300">
            <UserCog class="h-5 w-5" />
          </div>
          <div>
            <div class="text-base font-semibold">Dedicated user flows</div>
            <div class="mt-2 max-w-sm text-[13px] leading-5 text-white/45">
              Create access on its own page. Open an existing portal user from the registry to adjust scope, suspend access, or reset credentials on the dedicated desk.
            </div>
          </div>
          <div class="rounded-xl border border-white/[0.08] bg-black/10 p-4 text-[12px] text-white/55">
            Keep this registry page clean. One-time credentials and provisioning details now stay on the dedicated create flow only.
          </div>
        </div>

        <div class="flex justify-end">
          <button onclick={() => goto('/portal/users/create')} class="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500">
            <UserPlus class="h-4 w-4" />
            Open create page
          </button>
        </div>
      </div>
    </section>
      {/if}
    </div>
  </section>
</div>
