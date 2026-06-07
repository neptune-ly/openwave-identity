<script>
  import { onMount } from 'svelte';
  import { auth } from '$lib/stores/auth';
  import { apiCall, apiPublic } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { goto } from '$app/navigation';

  let info     = $state(null);
  let banks    = $state([]);
  let infoErr  = $state('');
  let searchQ  = $state('');
  let searchResult = $state(null);
  let searchAccounts = $state(null);
  let searchErr  = $state('');
  let searchLoading = $state(false);
  let session  = $state(null);

  onMount(async () => {
    session = get(auth);
    if (session?.role === 'CUSTOMER') {
      goto('/portal/customer');
      return;
    }
    if (session?.role === 'BANK') {
      goto('/portal/reports');
      return;
    }
    const [r1, r2] = await Promise.all([
      apiPublic('/registry/info'),
      apiPublic('/banks'),
    ]);
    if (r1.ok) info = r1.data;
    else infoErr = r1.error;
    if (r2.ok) banks = r2.data.banks || r2.data || [];
  });

  async function search() {
    if (!searchQ.trim()) return;
    searchErr = ''; searchResult = null; searchAccounts = null; searchLoading = true;
    const q = searchQ.trim();

    const resolveR = await apiPublic(`/identity/resolve?alias=${encodeURIComponent(q)}`);
    if (resolveR.ok) {
      searchResult = { type: 'resolve', data: resolveR.data };
    } else {
      const idR = await apiPublic(`/identity/${encodeURIComponent(q)}`);
      if (idR.ok) {
        searchResult = { type: 'identity', data: idR.data };
        const accR = await apiCall('get', `/identity/${encodeURIComponent(q)}/accounts`);
        if (accR.ok) searchAccounts = accR.data.accounts || accR.data || [];
      } else {
        searchErr = resolveR.status === 404 || idR.status === 404 ? 'Handle not found' : (resolveR.error || idR.error);
      }
    }
    searchLoading = false;
  }
</script>

<svelte:head><title>Dashboard — OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-4xl mx-auto">

  <div class="mb-8">
    <h1 class="text-2xl font-semibold tracking-tight">Dashboard</h1>
    <p class="text-white/40 text-sm mt-1">OpenWave Identity Registry — global overview</p>
  </div>

  <!-- Stats -->
  {#if infoErr}
    <div class="rounded-2xl border border-red-500/20 bg-red-500/[0.06] px-5 py-4 text-sm text-red-400 mb-8">{infoErr}</div>
  {:else if info}
    <div class="grid grid-cols-3 gap-4 mb-8">
      {#each [
        { label: 'Registered Banks',   value: info.registered_banks,  accent: 'text-indigo-400' },
        { label: 'Active Identities',  value: info.active_identities, accent: 'text-violet-400' },
        { label: 'Spec Version',       value: info.spec_version,      accent: 'text-white' },
        { label: 'Operator',           value: info.operator,          accent: 'text-white/60' },
        { label: 'Country Scope',      value: info.country_scope,     accent: 'text-white/60' },
        { label: 'Uptime SLA',         value: info.uptime_sla,        accent: 'text-emerald-400' },
      ] as card}
        <div class="rounded-2xl bg-white/[0.03] border border-white/[0.07] px-5 py-4 hover:bg-white/[0.05] transition-colors">
          <div class="text-[11px] text-white/25 uppercase tracking-wider mb-2">{card.label}</div>
          <div class="text-lg font-semibold {card.accent}">{card.value ?? '—'}</div>
        </div>
      {/each}
    </div>
  {:else}
    <div class="grid grid-cols-3 gap-4 mb-8">
      {#each Array(6) as _}
        <div class="h-20 rounded-2xl bg-white/[0.02] border border-white/[0.05] animate-pulse"></div>
      {/each}
    </div>
  {/if}

  <!-- Resolve box -->
  <div class="rounded-2xl bg-white/[0.03] border border-white/[0.07] p-6">
    <div class="text-sm font-semibold mb-1">Resolve NPT Handle</div>
    <div class="text-[12px] text-white/30 mb-4">Public lookup — resolves alias to IBAN</div>
    <div class="flex gap-2">
      <input
        bind:value={searchQ}
        onkeydown={e => e.key === 'Enter' && search()}
        placeholder="mtellesy  or  mtellesy@andalus"
        class="flex-1 bg-white/[0.05] border border-white/[0.1] rounded-xl px-4 py-2.5 text-sm text-white font-mono placeholder-white/20 focus:outline-none focus:border-indigo-500/60 transition-all"
      />
      <button
        onclick={search}
        disabled={searchLoading || !searchQ.trim()}
        class="px-5 py-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-30 text-white text-sm font-semibold rounded-xl transition-all"
      >
        {searchLoading ? '…' : 'Resolve'}
      </button>
    </div>

    {#if searchErr}
      <p class="mt-3 text-sm text-red-400">{searchErr}</p>
    {/if}

    {#if searchResult}
      <div class="mt-4 rounded-xl bg-black/40 border border-white/[0.07] p-4">
        {#if searchResult.type === 'resolve'}
          <div class="text-[10px] text-indigo-400 uppercase tracking-wider mb-3 font-medium">Resolved</div>
          <div class="grid grid-cols-2 gap-3">
            {#each Object.entries(searchResult.data) as [k, v]}
              <div>
                <div class="text-[10px] text-white/25 uppercase tracking-wider">{k}</div>
                <div class="text-[13px] text-white font-mono mt-0.5">{v}</div>
              </div>
            {/each}
          </div>
        {:else}
          <div class="text-[10px] text-violet-400 uppercase tracking-wider mb-3 font-medium">Identity Profile</div>
          <div class="grid grid-cols-2 gap-3 mb-4">
            <div><div class="text-[10px] text-white/25 uppercase">Handle</div><div class="text-sm text-white font-mono mt-0.5">{searchResult.data.nptHandle}</div></div>
            <div><div class="text-[10px] text-white/25 uppercase">Display Name</div><div class="text-sm text-white mt-0.5">{searchResult.data.customerDisplayName || '—'}</div></div>
            <div><div class="text-[10px] text-white/25 uppercase">Default Bank</div><div class="text-sm text-white mt-0.5">{searchResult.data.defaultBankHandle || '—'}</div></div>
            <div><div class="text-[10px] text-white/25 uppercase">Registered</div><div class="text-sm text-white mt-0.5">{searchResult.data.registeredAt ? new Date(searchResult.data.registeredAt).toLocaleDateString() : '—'}</div></div>
          </div>
          {#if searchAccounts?.length}
            <div class="border-t border-white/[0.06] pt-3">
              <div class="text-[10px] text-white/25 uppercase tracking-wider mb-2">Linked Accounts ({searchAccounts.length})</div>
              <div class="space-y-1.5">
                {#each searchAccounts as acc}
                  <div class="flex items-center gap-3 text-[12px] font-mono">
                    <span class="text-indigo-400 w-20 truncate">{acc.bankHandle}</span>
                    <span class="text-white/50 flex-1">{acc.iban}</span>
                    {#if acc.isDefault}<span class="text-[10px] px-1.5 py-0.5 rounded bg-indigo-500/20 text-indigo-300 font-sans">default</span>{/if}
                  </div>
                {/each}
              </div>
            </div>
          {/if}
        {/if}
      </div>
    {/if}
  </div>
</div>
