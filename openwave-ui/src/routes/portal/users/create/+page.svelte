<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import { auth } from '$lib/stores/auth';
  import { apiCall } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import Building2 from 'lucide-svelte/icons/building-2';
  import Copy from 'lucide-svelte/icons/copy';
  import Plus from 'lucide-svelte/icons/plus';

  let session = $state(null);
  let creating = $state(false);
  let banks = $state([]);
  let tempPassword = $state('');
  let createdUsername = $state('');
  let createdUserId = $state(null);
  let credentialNotice = $state(null);
  let createForm = $state({
    username: '',
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

  const visibleRoles = $derived(session?.role === 'ADMIN' ? [...registryRoles, ...bankRoles] : bankRoles);

  onMount(async () => {
    session = get(auth);
    if (session?.role === 'BANK') {
      createForm.bankHandle = session.bankHandle || '';
    }
    hydrateRouteState();
    await loadBanks();
  });

  function hydrateRouteState() {
    const current = get(page).url.searchParams;
    const role = current.get('role');
    const bankHandle = current.get('bankHandle') ?? current.get('bank_handle');
    const username = current.get('username');
    if (role && [...registryRoles, ...bankRoles].includes(role)) createForm.role = role;
    if (bankHandle) createForm.bankHandle = bankHandle;
    if (username) createForm.username = username;
  }

  async function loadBanks() {
    const response = await apiCall('get', '/banks');
    if (response.ok) banks = response.data.banks || [];
  }

  async function createUser() {
    if (!createForm.username.trim()) {
      toast.error('Username is required');
      return;
    }
    creating = true;
    const payload = {
      username: createForm.username.trim(),
      displayName: createForm.displayName.trim() || createForm.username.trim(),
      email: createForm.email.trim() || null,
      role: createForm.role,
      bankHandle: createForm.role.startsWith('BANK_') ? (createForm.bankHandle || session?.bankHandle) : null
    };
    const response = await apiCall('post', '/portal-users', payload);
    creating = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }

    tempPassword = response.data.temporaryPassword;
    createdUsername = response.data.user.username;
    createdUserId = response.data.user.id;
    credentialNotice = response.data.notification || null;
    toast.success('Portal user created');
  }

  async function copyTemp() {
    await navigator.clipboard.writeText(`${createdUsername}\n${tempPassword}`);
    toast.success('Credentials copied');
  }
</script>

<svelte:head><title>Create Portal User - OpenWave Identity</title></svelte:head>

<div class="mx-auto max-w-7xl space-y-6 p-8">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
      <div class="max-w-3xl">
        <button onclick={() => goto('/portal/users')} class="inline-flex items-center gap-2 text-[12px] font-medium text-white/45 transition-colors hover:text-white/75">
          <ArrowLeft class="h-4 w-4" />
          Back to users
        </button>
        <p class="mt-4 text-[11px] uppercase tracking-[0.18em] text-white/30">
          {session?.role === 'ADMIN' ? 'Registry access operations' : 'Bank access operations'}
        </p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">Create Portal User</h1>
        <p class="mt-2 text-sm text-white/50">
          Provision access from a dedicated page. Keep the registry screen focused on search, filters, and opening existing operator desks.
        </p>
      </div>
      <button onclick={createUser} disabled={creating || !createForm.username} class="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-40">
        <Plus class="h-4 w-4" />
        {creating ? 'Creating...' : 'Create portal user'}
      </button>
    </div>
  </section>

  {#if tempPassword}
    <section class="rounded-2xl border border-amber-400/20 bg-amber-400/[0.08] p-5">
      <div class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div class="min-w-0 flex-1">
          <p class="text-[11px] font-semibold uppercase tracking-[0.18em] text-amber-200">One-time credential reveal</p>
          <p class="mt-1 text-sm text-amber-100/85">Temporary credentials are shown once for the authorized operator. Deliver them only through approved channels.</p>
          <div class="mt-3 grid gap-3 md:grid-cols-2">
            <code class="block truncate rounded-xl border border-white/[0.06] bg-black/30 px-4 py-3 text-sm text-amber-100">{createdUsername}</code>
            <code class="block truncate rounded-xl border border-white/[0.06] bg-black/30 px-4 py-3 text-sm text-amber-100">{tempPassword}</code>
          </div>
          {#if credentialNotice}
            <div class="mt-3 rounded-xl border border-white/[0.08] bg-white/[0.04] px-4 py-3 text-[12px] text-white/70">
              <div class="font-semibold">{credentialNotice.status || 'Notification'}</div>
              <div class="mt-1">{credentialNotice.message}</div>
            </div>
          {/if}
        </div>
        <div class="flex flex-wrap gap-2">
          <button onclick={copyTemp} class="inline-flex items-center gap-2 rounded-xl border border-amber-300/25 px-3.5 py-2 text-[12px] font-medium text-amber-100 transition-all hover:border-amber-300/45 hover:text-white">
            <Copy class="h-4 w-4" />
            Copy credentials
          </button>
          <button onclick={() => createdUserId && goto(`/portal/users/${createdUserId}`)} disabled={!createdUserId} class="rounded-xl bg-indigo-600 px-4 py-2 text-[12px] font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-40">
            Open user desk
          </button>
        </div>
      </div>
    </section>
  {/if}

  <section class="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(360px,0.85fr)]">
    <div class="identity-surface-card p-6">
      <h2 class="text-lg font-semibold text-white">Access record</h2>
      <p class="mt-2 text-sm text-white/40">Create only the minimum role and scope needed for registry or bank operations.</p>
      <div class="mt-5 grid gap-3">
        <input bind:value={createForm.username} placeholder="username" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none" />
        <input bind:value={createForm.displayName} placeholder="Display name" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none" />
        <input bind:value={createForm.email} placeholder="email@example.com" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none" />
        <select bind:value={createForm.role} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none">
          {#each visibleRoles as role}
            <option value={role}>{roleLabels[role]}</option>
          {/each}
        </select>
        {#if createForm.role.startsWith('BANK_')}
          {#if session?.role === 'ADMIN'}
            <select bind:value={createForm.bankHandle} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none">
              <option value="">Select bank</option>
              {#each banks as bank}
                <option value={bank.bankHandle}>{bank.displayName} ({bank.bankHandle})</option>
              {/each}
            </select>
          {:else}
            <div class="rounded-xl border border-emerald-400/15 bg-emerald-400/[0.08] px-3.5 py-2.5 text-[13px] text-emerald-200">
              Bank scope: {session?.bankHandle}
            </div>
          {/if}
        {/if}
      </div>

      <div class="mt-5 flex flex-wrap gap-2">
        <button onclick={createUser} disabled={creating || !createForm.username} class="rounded-xl bg-indigo-600 px-5 py-2.5 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-40">
          {creating ? 'Creating...' : 'Create portal user'}
        </button>
        <button onclick={() => goto('/portal/users')} class="rounded-xl border border-white/[0.1] px-5 py-2.5 text-[13px] font-semibold text-white/55 transition-all hover:border-white/[0.18] hover:text-white">
          Cancel
        </button>
      </div>
    </div>

    <div class="identity-surface-card p-6">
      <h2 class="text-lg font-semibold text-white">Provisioning guide</h2>
      <div class="identity-surface-soft mt-4 p-4 text-[12px] text-white/55">
        Create access here, then move to the dedicated user desk for scope updates, suspension, and credential recovery handling.
      </div>
      <div class="identity-surface-soft mt-4 p-4 text-[12px] text-white/55">
        {#if createForm.role.startsWith('BANK_')}
          <div class="flex items-start gap-2">
            <Building2 class="mt-0.5 h-4 w-4 shrink-0 text-indigo-300" />
            <span>Bank roles stay scoped to the selected bank and should not be provisioned without an explicit owner.</span>
          </div>
        {:else}
          <span>Registry roles are global. Keep them tightly controlled and limited to explicit operations ownership.</span>
        {/if}
      </div>
    </div>
  </section>
</div>
