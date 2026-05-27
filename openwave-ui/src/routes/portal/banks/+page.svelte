<script>
  import { onMount } from 'svelte';
  import { auth } from '$lib/stores/auth';
  import { apiCall, apiPublic } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';

  let session  = $state(null);
  let banks    = $state([]);
  let loading  = $state(false);
  let showForm = $state(false);
  let newBankKey = $state('');

  let form = $state({ bankHandle: '', displayName: '', country: 'LY', coreUrl: '', contactEmail: '' });
  let formLoading = $state(false);

  const isAdmin = $derived(session?.role === 'ADMIN');

  onMount(async () => {
    session = get(auth);
    await loadBanks();
  });

  async function loadBanks() {
    loading = true;
    const r = await apiPublic('/banks');
    if (r.ok) banks = r.data.banks || r.data || [];
    loading = false;
  }

  async function registerBank() {
    formLoading = true; newBankKey = '';
    const r = await apiCall('post', '/banks', form);
    formLoading = false;
    if (r.ok) {
      newBankKey = r.data.bankApiKey || r.data.apiKey || '';
      form = { bankHandle: '', displayName: '', country: 'LY', coreUrl: '', contactEmail: '' };
      showForm = false;
      await loadBanks();
      toast.success('Bank registered');
    } else {
      toast.error(r.error);
    }
  }

  function copyKey() {
    navigator.clipboard.writeText(newBankKey);
    toast.success('Copied to clipboard');
  }
</script>

<svelte:head><title>Banks — OpenWave</title></svelte:head>

<div class="p-8 max-w-4xl mx-auto">

  <div class="mb-8 flex items-end justify-between">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">Banks</h1>
      <p class="text-white/40 text-sm mt-1">{banks.length} registered member bank{banks.length !== 1 ? 's' : ''}</p>
    </div>
    <div class="flex gap-2">
      <button
        onclick={loadBanks}
        class="px-4 py-2 text-[13px] font-medium text-white/40 hover:text-white border border-white/[0.1] rounded-xl transition-all hover:border-white/20"
      >
        Refresh
      </button>
      {#if isAdmin}
        <button
          onclick={() => showForm = !showForm}
          class="px-4 py-2 text-[13px] font-semibold bg-indigo-600 hover:bg-indigo-500 text-white rounded-xl transition-all"
        >
          Register Bank
        </button>
      {/if}
    </div>
  </div>

  <!-- New bank key reveal -->
  {#if newBankKey}
    <div class="rounded-2xl bg-amber-950/30 border border-amber-500/30 p-5 mb-6">
      <div class="flex items-center gap-2 mb-3">
        <div class="w-1.5 h-1.5 rounded-full bg-amber-400"></div>
        <span class="text-[13px] font-semibold text-amber-300">Save this Bank Access Credential — shown only once</span>
      </div>
      <code class="block font-mono text-sm text-amber-200 bg-black/30 rounded-xl px-4 py-3 break-all">{newBankKey}</code>
      <button onclick={copyKey} class="mt-3 text-[12px] text-amber-400/60 hover:text-amber-400 transition-colors">
        Copy to clipboard
      </button>
    </div>
  {/if}

  <!-- Register form -->
  {#if showForm && isAdmin}
    <div class="rounded-2xl bg-white/[0.03] border border-white/[0.07] p-6 mb-6">
      <div class="text-sm font-semibold mb-5">Register New Bank</div>
      <div class="grid grid-cols-2 gap-3">
        {#each [
          ['bankHandle',   'Handle',        'e.g. nub'],
          ['displayName',  'Display Name',  'NUB Bank'],
          ['country',      'Country Code',  'LY'],
          ['coreUrl',      'Core URL',      'https://…'],
          ['contactEmail', 'Contact Email', 'ops@bank.ly'],
        ] as [field, label, ph]}
          <div>
            <label for={`bank-${field}`} class="block text-[11px] text-white/35 mb-1.5 uppercase tracking-wider">{label}</label>
            <input
              id={`bank-${field}`}
              bind:value={form[field]}
              placeholder={ph}
              class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white font-mono placeholder-white/20 focus:outline-none focus:border-indigo-500/60 transition-all"
            />
          </div>
        {/each}
      </div>
      <div class="flex gap-2 mt-4">
        <button
          onclick={registerBank}
          disabled={formLoading || !form.bankHandle || !form.displayName}
          class="px-5 py-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-30 text-white text-[13px] font-semibold rounded-xl transition-all"
        >
          {formLoading ? 'Registering…' : 'Register'}
        </button>
        <button
          onclick={() => showForm = false}
          class="px-5 py-2.5 border border-white/[0.1] hover:border-white/20 text-white/40 hover:text-white text-[13px] font-semibold rounded-xl transition-all"
        >
          Cancel
        </button>
      </div>
    </div>
  {/if}

  <!-- Bank list -->
  {#if loading}
    <div class="space-y-2">
      {#each Array(4) as _}
        <div class="h-16 rounded-2xl bg-white/[0.02] animate-pulse"></div>
      {/each}
    </div>
  {:else if banks.length === 0}
    <div class="rounded-2xl bg-white/[0.02] border border-white/[0.05] py-16 text-center">
      <div class="text-4xl mb-3 opacity-20">◻</div>
      <div class="text-white/30 text-sm">No banks registered yet</div>
    </div>
  {:else}
    <div class="rounded-2xl bg-white/[0.03] border border-white/[0.07] overflow-hidden">
      <div class="grid grid-cols-[40px_1fr_60px_80px_100px] gap-x-4 px-5 py-3 border-b border-white/[0.05]
        text-[11px] text-white/20 uppercase tracking-wider font-medium">
        <span></span><span>Bank</span><span>Country</span><span>Status</span><span>Registered</span>
      </div>
      <div class="divide-y divide-white/[0.04]">
        {#each banks as bank}
          <div class="grid grid-cols-[40px_1fr_60px_80px_100px] gap-x-4 items-center px-5 py-3.5 hover:bg-white/[0.02] transition-colors">
            <div class="w-8 h-8 rounded-lg bg-indigo-600/15 border border-indigo-500/20 flex items-center justify-center text-[11px] font-bold text-indigo-400">
              {bank.bankHandle?.slice(0,2).toUpperCase()}
            </div>
            <div class="min-w-0">
              <div class="text-[13px] font-medium text-white truncate">{bank.displayName}</div>
              <div class="text-[11px] text-white/25 font-mono truncate">{bank.bankHandle} · {bank.coreUrl}</div>
            </div>
            <span class="text-[12px] text-white/35">{bank.country}</span>
            <span class="text-[11px] px-2.5 py-1 rounded-full border w-fit
              {bank.active
                ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20'
                : 'bg-white/[0.03] text-white/25 border-white/[0.08]'}">
              {bank.active ? 'active' : 'inactive'}
            </span>
            <span class="text-[11px] text-white/20">
              {bank.registeredAt ? new Date(bank.registeredAt).toLocaleDateString() : '—'}
            </span>
          </div>
        {/each}
      </div>
    </div>
  {/if}
</div>
