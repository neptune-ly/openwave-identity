<script>
  import { onMount } from 'svelte';
  import { auth } from '$lib/stores/auth';
  import { apiCall, apiPublic, getApi } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';

  let session  = $state(null);
  let banks    = $state([]);
  let loading  = $state(false);
  let showForm = $state(false);
  let newBankKey = $state('');
  let selectedBank = $state(null);
  let editForm = $state({ displayName: '', brandColor: '', supportEmail: '', website: '', logoUrl: '' });
  let editLoading = $state(false);

  let form = $state({ bankHandle: '', displayName: '', country: 'LY', coreUrl: '', contactEmail: '', brandColor: '', supportEmail: '', website: '' });
  let formLoading = $state(false);

  const isAdmin = $derived(session?.role === 'ADMIN');

  onMount(async () => {
    session = get(auth);
    await loadBanks();
  });

  async function loadBanks() {
    loading = true;
    const r = isAdmin ? await apiPublic('/banks') : await apiCall('get', '/banks/me');
    if (r.ok) banks = isAdmin ? (r.data.banks || r.data || []) : [r.data];
    else toast.error(r.error || 'Could not load bank profile');
    loading = false;
  }

  async function registerBank() {
    formLoading = true; newBankKey = '';
    const r = await apiCall('post', '/banks', form);
    formLoading = false;
    if (r.ok) {
      newBankKey = r.data.bankApiKey || r.data.apiKey || '';
      form = { bankHandle: '', displayName: '', country: 'LY', coreUrl: '', contactEmail: '', brandColor: '', supportEmail: '', website: '' };
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

  function openBranding(bank) {
    selectedBank = bank;
    editForm = {
      displayName: bank.branding?.display_name || bank.displayName || '',
      brandColor: bank.branding?.brand_color || '',
      supportEmail: bank.branding?.support_email || '',
      website: bank.branding?.website || '',
      logoUrl: bank.branding?.logo_url || '',
    };
  }

  async function saveBranding() {
    if (!selectedBank) return;
    editLoading = true;
    const endpoint = isAdmin ? `/banks/${selectedBank.bankHandle}/branding` : '/banks/me/branding';
    const r = await apiCall('patch', endpoint, editForm);
    editLoading = false;
    if (r.ok) {
      toast.success('Bank branding updated');
      selectedBank = null;
      await loadBanks();
    } else {
      toast.error(r.error);
    }
  }

  async function uploadLogo(event) {
    if (!selectedBank) return;
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file);
    editLoading = true;
    try {
      const endpoint = isAdmin ? `/banks/${selectedBank.bankHandle}/branding/logo` : '/banks/me/branding/logo';
      await getApi().post(endpoint, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      toast.success('Bank logo uploaded');
      selectedBank = null;
      await loadBanks();
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || error?.message || 'Could not upload logo');
    } finally {
      editLoading = false;
    }
  }
</script>

<svelte:head><title>Banks — OpenWave</title></svelte:head>

<div class="p-8 max-w-4xl mx-auto">

  <div class="mb-8 flex items-end justify-between">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">{isAdmin ? 'Banks' : 'My Bank'}</h1>
      <p class="text-white/40 text-sm mt-1">{isAdmin ? `${banks.length} registered member bank${banks.length !== 1 ? 's' : ''}` : 'Bank profile, branding, and public directory visibility'}</p>
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
          ['bankHandle',   'Handle',         'e.g. nub'],
          ['displayName',  'Display Name',   'NUB Bank'],
          ['country',      'Country Code',   'LY'],
          ['coreUrl',      'Core URL',       'https://...'],
          ['contactEmail', 'Contact Email',  'ops@bank.ly'],
          ['brandColor',   'Brand Color',    '#07315F'],
          ['supportEmail', 'Support Email',  'support@bank.ly'],
          ['website',      'Website',        'https://bank.ly'],
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
    {#if !isAdmin && banks[0]}
      {@const bank = banks[0]}
      <div class="grid grid-cols-3 gap-3 mb-5">
        {#each [
          ['Handle', bank.bankHandle],
          ['Core URL', bank.coreUrl || 'not visible'],
          ['Contact', bank.contactEmail || bank.branding?.support_email || 'not set'],
          ['Support', bank.branding?.support_email || 'not set'],
          ['Website', bank.branding?.website || 'not set'],
          ['Status', bank.active ? 'active' : 'inactive'],
        ] as [label, value]}
          <div class="rounded-2xl border border-white/[0.07] bg-white/[0.03] px-4 py-3">
            <div class="text-[10px] text-white/25 uppercase tracking-wider">{label}</div>
            <div class="mt-1 truncate text-[13px] text-white/70">{value}</div>
          </div>
        {/each}
      </div>
    {/if}

    <div class="rounded-2xl bg-white/[0.03] border border-white/[0.07] overflow-hidden">
      <div class="grid grid-cols-[44px_1fr_60px_80px_100px_74px] gap-x-4 px-5 py-3 border-b border-white/[0.05]
        text-[11px] text-white/20 uppercase tracking-wider font-medium">
        <span></span><span>Bank</span><span>Country</span><span>Status</span><span>Registered</span><span></span>
      </div>
      <div class="divide-y divide-white/[0.04]">
        {#each banks as bank}
          <div class="grid grid-cols-[44px_1fr_60px_80px_100px_74px] gap-x-4 items-center px-5 py-3.5 hover:bg-white/[0.02] transition-colors">
            <div class="w-9 h-9 rounded-xl bg-indigo-600/15 border border-indigo-500/20 flex items-center justify-center overflow-hidden text-[11px] font-bold text-indigo-400">
              {#if bank.branding?.logo_url}
                <img src={bank.branding.logo_url} alt={bank.displayName} class="h-full w-full object-cover" />
              {:else}
                {bank.bankHandle?.slice(0,2).toUpperCase()}
              {/if}
            </div>
            <div class="min-w-0">
              <div class="flex items-center gap-2">
                <div class="text-[13px] font-medium text-white truncate">{bank.branding?.display_name || bank.displayName}</div>
                {#if bank.branding?.brand_color}
                  <span class="h-2.5 w-2.5 rounded-full border border-white/20" style={`background:${bank.branding.brand_color}`}></span>
                {/if}
              </div>
              <div class="text-[11px] text-white/25 font-mono truncate">{bank.bankHandle} · {bank.branding?.support_email || 'support not set'}</div>
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
            {#if isAdmin || bank.bankHandle === session?.bankHandle}
              <button onclick={() => openBranding(bank)} class="text-[11px] px-2.5 py-1.5 rounded-lg border border-white/[0.08] text-white/40 hover:text-white hover:border-white/20 transition-all">
                {isAdmin ? 'Brand' : 'Manage'}
              </button>
            {/if}
          </div>
        {/each}
      </div>
    </div>
  {/if}
</div>

{#if selectedBank}
  <div class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 px-4 backdrop-blur-sm">
    <div class="w-full max-w-lg rounded-3xl border border-white/[0.09] bg-[#0d0d14] p-6 shadow-2xl">
      <div class="mb-5 flex items-start gap-3">
        <div class="flex h-12 w-12 items-center justify-center overflow-hidden rounded-2xl border border-white/[0.1] bg-white/[0.05] text-[11px] font-bold text-indigo-300">
          {#if editForm.logoUrl}
            <img src={editForm.logoUrl} alt={editForm.displayName} class="h-full w-full object-cover" />
          {:else}
            {selectedBank.bankHandle?.slice(0,2).toUpperCase()}
          {/if}
        </div>
        <div class="min-w-0">
          <h2 class="text-lg font-semibold tracking-tight">{isAdmin ? 'Bank branding' : 'My bank profile'}</h2>
          <p class="mt-1 text-[12px] text-white/35">Shown in the public directory and bank-scoped account management surfaces. Core routing and keys remain controlled by registry admins.</p>
        </div>
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div class="col-span-2">
          <label for="identity-bank-display-name" class="mb-1.5 block text-[11px] uppercase tracking-wider text-white/35">Display name</label>
          <input id="identity-bank-display-name" bind:value={editForm.displayName} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white outline-none focus:border-indigo-500/60" />
        </div>
        <div>
          <label for="identity-bank-brand-color" class="mb-1.5 block text-[11px] uppercase tracking-wider text-white/35">Brand color</label>
          <input id="identity-bank-brand-color" bind:value={editForm.brandColor} placeholder="#07315F" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white outline-none focus:border-indigo-500/60" />
        </div>
        <div>
          <label for="identity-bank-support-email" class="mb-1.5 block text-[11px] uppercase tracking-wider text-white/35">Support email</label>
          <input id="identity-bank-support-email" bind:value={editForm.supportEmail} placeholder="support@bank.ly" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white outline-none focus:border-indigo-500/60" />
        </div>
        <div class="col-span-2">
          <label for="identity-bank-website" class="mb-1.5 block text-[11px] uppercase tracking-wider text-white/35">Website</label>
          <input id="identity-bank-website" bind:value={editForm.website} placeholder="https://bank.ly" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white outline-none focus:border-indigo-500/60" />
        </div>
      </div>

      <div class="mt-5 flex flex-wrap items-center gap-2">
        <label class="cursor-pointer rounded-xl border border-white/[0.1] px-4 py-2.5 text-[13px] font-semibold text-white/50 transition-all hover:border-white/20 hover:text-white">
          Upload logo
          <input type="file" accept="image/png,image/jpeg,image/webp" class="hidden" onchange={uploadLogo} />
        </label>
        <button onclick={saveBranding} disabled={editLoading} class="rounded-xl bg-indigo-600 px-5 py-2.5 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-30">
          {editLoading ? 'Saving...' : 'Save branding'}
        </button>
        <button onclick={() => selectedBank = null} class="rounded-xl border border-white/[0.1] px-5 py-2.5 text-[13px] font-semibold text-white/45 transition-all hover:border-white/20 hover:text-white">
          Cancel
        </button>
      </div>
    </div>
  </div>
{/if}
