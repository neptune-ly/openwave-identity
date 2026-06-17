<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import { auth } from '$lib/stores/auth';
  import { apiCall } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import UserCog from 'lucide-svelte/icons/user-cog';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import Building2 from 'lucide-svelte/icons/building-2';
  import Mail from 'lucide-svelte/icons/mail';
  import Clock3 from 'lucide-svelte/icons/clock-3';
  import KeyRound from 'lucide-svelte/icons/key-round';
  import Copy from 'lucide-svelte/icons/copy';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import CircleAlert from 'lucide-svelte/icons/circle-alert';
  import CircleCheckBig from 'lucide-svelte/icons/circle-check-big';
  import Info from 'lucide-svelte/icons/info';

  let session = $state(null);
  let loading = $state(true);
  let saving = $state(false);
  let resetting = $state(false);
  let user = $state(null);
  let banks = $state([]);
  let tempPassword = $state('');
  let credentialNotice = $state(null);
  const currentSection = $derived(readSection());

  let draft = $state({
    displayName: '',
    email: '',
    role: 'BANK_OPERATOR',
    bankHandle: ''
  });

  const registryRoles = ['REGISTRY_ADMIN', 'REGISTRY_OPERATOR'];
  const bankRoles = ['BANK_ADMIN', 'BANK_OPERATOR', 'BANK_VIEWER'];
  const roleLabels = {
    REGISTRY_ADMIN: 'Registry Admin',
    REGISTRY_OPERATOR: 'Registry Operator',
    BANK_ADMIN: 'Bank Admin',
    BANK_OPERATOR: 'Bank Operator',
    BANK_VIEWER: 'Bank Viewer'
  };

  const userId = $derived(Number(get(page).params.id));
  const visibleRoles = $derived(session?.role === 'ADMIN' ? [...registryRoles, ...bankRoles] : bankRoles);

  onMount(async () => {
    session = get(auth);
    await Promise.all([loadUser(), loadBanks()]);
  });

  async function loadUser() {
    loading = true;
    const response = await apiCall('get', '/portal-users');
    loading = false;
    if (!response.ok) {
      toast.error(response.error || 'Could not load portal users');
      return;
    }
    const users = response.data.users || [];
    user = users.find((item) => item.id === userId) || null;
    if (!user) {
      toast.error('Portal user not found');
      await goto('/portal/users');
      return;
    }
    seedDraft();
  }

  async function loadBanks() {
    const response = await apiCall('get', '/banks');
    if (response.ok) {
      banks = response.data.banks || [];
    }
  }

  function seedDraft() {
    if (!user) return;
    draft = {
      displayName: user.displayName || '',
      email: user.email || '',
      role: user.role,
      bankHandle: user.bankHandle || session?.bankHandle || ''
    };
  }

  async function saveUser() {
    if (!user) return;
    saving = true;
    const payload = {
      displayName: draft.displayName.trim() || user.username,
      email: draft.email.trim() || null,
      role: draft.role,
      bankHandle: draft.role.startsWith('BANK_') ? (draft.bankHandle || session?.bankHandle || null) : null
    };
    const response = await apiCall('patch', `/portal-users/${user.id}`, payload);
    saving = false;
    if (!response.ok) {
      toast.error(response.error || 'Could not update portal user');
      return;
    }
    toast.success('Portal user updated');
    await loadUser();
  }

  async function toggleActive() {
    if (!user) return;
    saving = true;
    const response = await apiCall('patch', `/portal-users/${user.id}`, { active: !user.active });
    saving = false;
    if (!response.ok) {
      toast.error(response.error || 'Could not update portal user');
      return;
    }
    toast.success(user.active ? 'Portal user suspended' : 'Portal user activated');
    await loadUser();
  }

  async function resetPassword() {
    if (!user) return;
    resetting = true;
    const response = await apiCall('post', `/portal-users/${user.id}/reset-password`);
    resetting = false;
    if (!response.ok) {
      toast.error(response.error || 'Could not reset password');
      return;
    }
    tempPassword = response.data.temporaryPassword;
    credentialNotice = response.data.notification || null;
    toast.success('Temporary password generated');
    await loadUser();
  }

  async function copyCredentials() {
    if (!user || !tempPassword) return;
    await navigator.clipboard.writeText(`${user.username}\n${tempPassword}`);
    toast.success('Credentials copied');
  }

  function formatDate(value) {
    return value ? new Date(value).toLocaleString() : 'Never';
  }

  function noticeTone(status) {
    if (status === 'SENT') return 'good';
    if (status === 'DELIVERY_FAILED') return 'warn';
    return 'soft';
  }

  function summaryCards() {
    if (!user) return [];
    return [
      { label: 'Access state', value: user.active ? 'Active' : 'Suspended', icon: user.active ? CircleCheckBig : CircleAlert, tone: user.active ? 'emerald' : 'red' },
      { label: 'Role scope', value: roleLabels[user.role] || user.role, icon: ShieldCheck, tone: 'indigo' },
      { label: 'Bank scope', value: user.bankHandle || 'Registry', icon: Building2, tone: 'sky' },
      { label: 'Last login', value: user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleDateString() : 'Never', icon: Clock3, tone: 'amber' }
    ];
  }

  function toneClass(tone) {
    if (tone === 'emerald') return 'text-emerald-300';
    if (tone === 'sky') return 'text-sky-300';
    if (tone === 'amber') return 'text-amber-300';
    if (tone === 'red') return 'text-red-300';
    return 'text-indigo-300';
  }

  function readSection() {
    const section = get(page).url.searchParams.get('section');
    return ['access', 'credentials', 'support'].includes(section) ? section : 'access';
  }

  async function setSectionRoute(section) {
    const next = new URL(get(page).url);
    if (section === 'access') next.searchParams.delete('section');
    else next.searchParams.set('section', section);
    await goto(`${next.pathname}${next.search}`, { replaceState: true, noScroll: true, keepFocus: true });
  }
</script>

<svelte:head><title>Portal User Desk - OpenWave Identity</title></svelte:head>

<div class="mx-auto max-w-6xl space-y-6 p-8">
  <div class="flex flex-wrap items-center justify-between gap-3">
    <a href="/portal/users" class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white">
      <ArrowLeft class="h-4 w-4" />
      Back to registry
    </a>
    <button onclick={loadUser} disabled={loading || saving || resetting} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">
      <RefreshCw class={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
      Refresh
    </button>
  </div>

  {#if loading || !user}
    <section class="identity-surface-card p-8 text-center text-sm text-white/45">Loading portal user desk...</section>
  {:else}
    <section class="identity-expressive-band p-6">
      <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
        <div class="max-w-3xl">
          <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Portal user desk</p>
          <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">{user.displayName}</h1>
          <p class="mt-2 font-mono text-sm text-white/40">{user.username}</p>
          <p class="identity-section-note mt-2 text-sm text-white/55">
            Manage operator scope, recovery contact, suspension state, and controlled credential reset from one dedicated record page.
          </p>
          <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
            <span class="identity-role-accent">{roleLabels[user.role] || user.role}</span>
            <span class="identity-role-accent">{user.bankHandle || 'Registry scope'}</span>
            <span class="identity-role-accent">{user.active ? 'Active access' : 'Suspended access'}</span>
          </div>
        </div>
        <div class="grid gap-3 sm:grid-cols-2">
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Credential posture</div>
            <div class="mt-2 text-sm font-medium text-white">One-time credential handoff only.</div>
            <div class="mt-1 text-[12px] text-white/45">Temporary passwords should move through approved recovery channels only.</div>
          </div>
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Scope rule</div>
            <div class="mt-2 text-sm font-medium text-white">Keep the narrowest practical role.</div>
            <div class="mt-1 text-[12px] text-white/45">Bank roles stay bank-scoped. Registry roles remain tightly controlled.</div>
          </div>
        </div>
      </div>
    </section>

    {#if tempPassword}
      <section class="rounded-2xl border border-amber-400/20 bg-amber-400/[0.08] p-5">
        <div class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
          <div class="min-w-0 flex-1">
            <p class="text-[11px] font-semibold uppercase tracking-[0.18em] text-amber-200">One-time credential reveal</p>
            <div class="mt-3 grid gap-3 md:grid-cols-2">
              <code class="block truncate rounded-xl border border-white/[0.06] bg-black/30 px-4 py-3 text-sm text-amber-100">{user.username}</code>
              <code class="block truncate rounded-xl border border-white/[0.06] bg-black/30 px-4 py-3 text-sm text-amber-100">{tempPassword}</code>
            </div>
            {#if credentialNotice}
              <div class={`mt-3 rounded-xl border px-4 py-3 text-[12px] ${
                noticeTone(credentialNotice.status) === 'good'
                  ? 'border-emerald-400/20 bg-emerald-400/10 text-emerald-100'
                  : noticeTone(credentialNotice.status) === 'warn'
                  ? 'border-amber-300/25 bg-amber-300/10 text-amber-100'
                  : 'border-white/[0.08] bg-white/[0.04] text-white/70'
              }`}>
                <div class="font-semibold">
                  {credentialNotice.status === 'SENT'
                    ? 'Credential email sent'
                    : credentialNotice.status === 'DELIVERY_FAILED'
                    ? 'Credential email failed'
                    : 'No notification channel configured'}
                </div>
                <div class="mt-1">{credentialNotice.message}</div>
              </div>
            {/if}
          </div>
          <button onclick={copyCredentials} class="inline-flex items-center gap-2 rounded-xl border border-amber-300/25 px-3.5 py-2 text-[12px] font-medium text-amber-100 transition-all hover:border-amber-300/45 hover:text-white">
            <Copy class="h-4 w-4" />
            Copy credentials
          </button>
        </div>
      </section>
    {/if}

    <div class="grid gap-3 md:grid-cols-4">
      {#each summaryCards() as item}
        <section class="identity-kpi-card px-5 py-4">
          <div class="flex items-center gap-3">
            <div class={`flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] ${toneClass(item.tone)}`}>
              <item.icon class="h-5 w-5" />
            </div>
            <div>
              <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">{item.label}</p>
              <p class="mt-1 text-lg font-semibold">{item.value}</p>
            </div>
          </div>
        </section>
      {/each}
    </div>

    <section class="identity-surface-card p-3">
      <div class="flex flex-wrap gap-2">
        <button onclick={() => setSectionRoute('access')} class={`rounded-xl px-3.5 py-2 text-[13px] font-medium transition-all ${currentSection === 'access' ? 'bg-white text-slate-950' : 'border border-white/[0.08] text-white/65 hover:text-white'}`}>
          Access profile
        </button>
        <button onclick={() => setSectionRoute('credentials')} class={`rounded-xl px-3.5 py-2 text-[13px] font-medium transition-all ${currentSection === 'credentials' ? 'bg-white text-slate-950' : 'border border-white/[0.08] text-white/65 hover:text-white'}`}>
          Credentials
        </button>
        <button onclick={() => setSectionRoute('support')} class={`rounded-xl px-3.5 py-2 text-[13px] font-medium transition-all ${currentSection === 'support' ? 'bg-white text-slate-950' : 'border border-white/[0.08] text-white/65 hover:text-white'}`}>
          Support-safe facts
        </button>
      </div>
    </section>

    <div class="grid gap-6 xl:grid-cols-[minmax(0,1fr)_340px]">
      {#if currentSection === 'access'}
      <section class="identity-surface-card p-6">
        <div class="flex items-start justify-between gap-4">
          <div>
            <div class="flex items-center gap-2">
              <div class="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-indigo-300">
                <UserCog class="h-4 w-4" />
              </div>
              <div>
                <div class="text-sm font-semibold">Access profile</div>
                <div class="mt-1 text-[12px] text-white/35">Edit the scoped operator profile without leaving the dedicated record page.</div>
              </div>
            </div>
          </div>
          <button onclick={toggleActive} disabled={saving || resetting} class={`rounded-xl px-3.5 py-2 text-[12px] font-semibold transition-all disabled:opacity-40 ${user.active ? 'border border-red-400/20 bg-red-400/10 text-red-200 hover:border-red-400/35' : 'border border-emerald-400/20 bg-emerald-400/10 text-emerald-200 hover:border-emerald-400/35'}`}>
            {user.active ? 'Suspend access' : 'Activate access'}
          </button>
        </div>

        <div class="mt-6 grid gap-4 md:grid-cols-2">
          <label class="block">
            <span class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Display name</span>
            <input bind:value={draft.displayName} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none" />
          </label>
          <label class="block">
            <span class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Recovery email</span>
            <div class="relative">
              <Mail class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-white/25" />
              <input bind:value={draft.email} placeholder="email@example.com" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] py-2.5 pl-9 pr-3 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none" />
            </div>
          </label>
          <label class="block">
            <span class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Role</span>
            <select bind:value={draft.role} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none">
              {#each visibleRoles as role}
                <option value={role}>{roleLabels[role]}</option>
              {/each}
            </select>
          </label>
          <label class="block">
            <span class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Bank scope</span>
            {#if draft.role.startsWith('BANK_') && session?.role === 'ADMIN'}
              <select bind:value={draft.bankHandle} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none">
                <option value="">Select bank</option>
                {#each banks as bank}
                  <option value={bank.bankHandle}>{bank.displayName} ({bank.bankHandle})</option>
                {/each}
              </select>
            {:else if draft.role.startsWith('BANK_')}
              <div class="rounded-xl border border-emerald-400/15 bg-emerald-400/[0.08] px-3.5 py-2.5 text-[13px] text-emerald-200">{session?.bankHandle}</div>
            {:else}
              <div class="rounded-xl border border-white/[0.08] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white/55">Registry scope</div>
            {/if}
          </label>
        </div>

        <div class="mt-6 flex flex-wrap gap-2">
          <button onclick={saveUser} disabled={saving || resetting} class="rounded-xl bg-indigo-600 px-4 py-2.5 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-40">
            {saving ? 'Saving...' : 'Save access profile'}
          </button>
          <button onclick={resetPassword} disabled={saving || resetting} class="inline-flex items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2.5 text-[13px] font-semibold text-white/65 transition-all hover:border-white/[0.18] hover:text-white disabled:opacity-40">
            <KeyRound class="h-4 w-4" />
            {resetting ? 'Resetting...' : 'Generate temporary password'}
          </button>
        </div>
      </section>
      {/if}

      <section class="space-y-6">
        {#if currentSection !== 'support'}
        <div class="identity-surface-card p-5">
          <div class="flex items-center gap-2">
            <div class="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-sky-300">
              <Info class="h-4 w-4" />
            </div>
            <div>
              <div class="text-sm font-semibold">Support-safe details</div>
              <div class="mt-1 text-[12px] text-white/35">Operational facts for audits and support follow-up.</div>
            </div>
          </div>
          <div class="mt-5 space-y-3">
            <div class="identity-surface-soft px-4 py-3">
              <p class="text-[11px] uppercase tracking-[0.14em] text-white/28">Created</p>
              <p class="mt-1 text-sm text-white/75">{formatDate(user.createdAt)}</p>
            </div>
            <div class="identity-surface-soft px-4 py-3">
              <p class="text-[11px] uppercase tracking-[0.14em] text-white/28">Last updated</p>
              <p class="mt-1 text-sm text-white/75">{formatDate(user.updatedAt)}</p>
            </div>
            <div class="identity-surface-soft px-4 py-3">
              <p class="text-[11px] uppercase tracking-[0.14em] text-white/28">Last login</p>
              <p class="mt-1 text-sm text-white/75">{formatDate(user.lastLoginAt)}</p>
            </div>
          </div>
        </div>
        {/if}

        {#if currentSection !== 'access'}
        <div class="identity-surface-card p-5">
          <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Operator guidance</p>
          <div class="mt-4 space-y-3 text-[13px] leading-5 text-white/50">
            <div class="identity-surface-soft px-4 py-3">Use bank roles only for the bank desks they actually operate. Registry roles should stay exceptional.</div>
            <div class="identity-surface-soft px-4 py-3">Recovery email improves credential delivery, but the temporary password remains a controlled one-time reveal.</div>
            <div class="identity-surface-soft px-4 py-3">Suspension is the safer default when an operator changes scope or leaves a bank team.</div>
          </div>
        </div>
        {/if}

        {#if currentSection === 'credentials'}
        <div class="identity-surface-card p-5">
          <div class="flex items-center gap-2">
            <div class="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-amber-300">
              <KeyRound class="h-4 w-4" />
            </div>
            <div>
              <div class="text-sm font-semibold">Credential actions</div>
              <div class="mt-1 text-[12px] text-white/35">Temporary password generation and delivery posture.</div>
            </div>
          </div>
          <div class="mt-5 space-y-3">
            <div class="identity-surface-soft px-4 py-3">
              <p class="text-[11px] uppercase tracking-[0.14em] text-white/28">Recovery email</p>
              <p class="mt-1 text-sm text-white/75">{draft.email || 'No recovery email configured'}</p>
            </div>
            <div class="identity-surface-soft px-4 py-3">
              <p class="text-[11px] uppercase tracking-[0.14em] text-white/28">Credential delivery</p>
              <p class="mt-1 text-sm text-white/75">{credentialNotice?.message || 'No recent temporary password handoff recorded in this session.'}</p>
            </div>
          </div>
          <div class="mt-5">
            <button onclick={resetPassword} disabled={saving || resetting} class="inline-flex items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2.5 text-[13px] font-semibold text-white/65 transition-all hover:border-white/[0.18] hover:text-white disabled:opacity-40">
              <KeyRound class="h-4 w-4" />
              {resetting ? 'Resetting...' : 'Generate temporary password'}
            </button>
          </div>
        </div>
        {/if}
      </section>
    </div>
  {/if}
</div>
