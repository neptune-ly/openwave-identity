<script>
  import { onMount } from 'svelte';
  import { auth } from '$lib/stores/auth';
  import { apiCall, apiPublic } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';

  let session = $state(null);
  let banks   = $state([]);

  let enroll      = $state({ nptHandle: '', iban: '', customerDisplayName: '', bankCustomerRef: '', setAsDefault: true });
  let enrollResult = $state(null);
  let enrollLoading = $state(false);

  let linkHandle  = $state('');
  let linkIban    = $state('');
  let linkDefault = $state(false);
  let linkLoading = $state(false);

  let unlinkHandle  = $state('');
  let unlinkIban    = $state('');
  let unlinkLoading = $state(false);

  let defHandle  = $state('');
  let defIban    = $state('');
  let defLoading = $state(false);

  let defBankHandle   = $state('');
  let defBankSelected = $state('');
  let defBankLoading  = $state(false);

  onMount(async () => {
    session = get(auth);
    const r = await apiPublic('/banks');
    if (r.ok) banks = r.data.banks || r.data || [];
  });

  const isBank = $derived(session?.role === 'BANK');

  async function doEnroll() {
    enrollLoading = true; enrollResult = null;
    const r = await apiCall('post', '/identity/claim', enroll);
    enrollLoading = false;
    if (r.ok) {
      enrollResult = r.data;
      enroll = { nptHandle: '', iban: '', customerDisplayName: '', bankCustomerRef: '', setAsDefault: true };
      toast.success('Handle claimed');
    } else toast.error(r.error);
  }

  async function doLink() {
    linkLoading = true;
    const r = await apiCall('post', `/identity/${linkHandle}/accounts`, { iban: linkIban, setAsDefault: linkDefault });
    linkLoading = false;
    if (r.ok) { linkHandle = ''; linkIban = ''; toast.success('IBAN linked'); }
    else toast.error(r.error);
  }

  async function doUnlink() {
    unlinkLoading = true;
    const r = await apiCall('delete', `/identity/${unlinkHandle}/accounts/iban/${encodeURIComponent(unlinkIban)}`);
    unlinkLoading = false;
    if (r.ok) { unlinkHandle = ''; unlinkIban = ''; toast.success('IBAN unlinked'); }
    else toast.error(r.error);
  }

  async function doSetDefaultIban() {
    defLoading = true;
    const r = await apiCall('patch', `/identity/${defHandle}/accounts/iban/${encodeURIComponent(defIban)}/set-default`);
    defLoading = false;
    if (r.ok) toast.success('Default IBAN updated');
    else toast.error(r.error);
  }

  async function doSetDefaultBank() {
    defBankLoading = true;
    const r = await apiCall('patch', `/identity/${defBankHandle}/default-bank`, { bankHandle: defBankSelected });
    defBankLoading = false;
    if (r.ok) toast.success('Default bank updated');
    else toast.error(r.error);
  }
</script>

<svelte:head><title>Identity — OpenWave</title></svelte:head>

<div class="p-8 max-w-4xl mx-auto space-y-5">

  <div class="mb-8">
    <h1 class="text-2xl font-semibold tracking-tight">Identity</h1>
    <p class="text-white/40 text-sm mt-1">
      {#if isBank}Claim handles and manage your bank's linked accounts{:else}Manage all identity handles and linked accounts{/if}
    </p>
  </div>

  <!-- ── Claim Handle ─────────────────────────────────────────────────────── -->
  <section class="rounded-2xl bg-white/[0.03] border border-white/[0.07] p-6">
    <div class="flex items-start justify-between mb-5">
      <div>
        <div class="text-sm font-semibold">Claim NPT Handle</div>
        <div class="text-[12px] text-white/30 mt-0.5">Bank vouches for the customer's identity</div>
      </div>
      <div class="px-2.5 py-1 rounded-lg border text-[11px]
        {isBank ? 'bg-emerald-500/10 border-emerald-500/20 text-emerald-400' : 'bg-amber-500/10 border-amber-500/20 text-amber-400'}">
        {isBank ? 'Bank Access Active' : 'Admin Mode'}
      </div>
    </div>

    <div class="grid grid-cols-2 gap-3">
      {#each [
        ['nptHandle',           'NPT Handle',     'e.g. mtellesy'],
        ['customerDisplayName', 'Display Name',   'Full name'],
        ['iban',                'IBAN',            'LY83002700…'],
        ['bankCustomerRef',     'Customer Ref',   'Internal bank ID'],
      ] as [field, label, ph]}
        <div>
          <label for={`enroll-${field}`} class="block text-[11px] text-white/35 mb-1.5 uppercase tracking-wider">{label}</label>
          <input
            id={`enroll-${field}`}
            bind:value={enroll[field]}
            placeholder={ph}
            class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white font-mono placeholder-white/20 focus:outline-none focus:border-indigo-500/60 transition-all"
          />
        </div>
      {/each}
      <div class="col-span-2 flex items-center gap-2.5">
        <input type="checkbox" bind:checked={enroll.setAsDefault} id="sd" class="w-4 h-4 accent-indigo-500"/>
        <label for="sd" class="text-[13px] text-white/40">Set as default bank for this handle</label>
      </div>
    </div>

    {#if enrollResult}
      <div class="mt-4 rounded-xl bg-emerald-500/[0.06] border border-emerald-500/20 p-4">
        <div class="text-[11px] text-emerald-400 font-medium uppercase tracking-wider mb-2">Handle claimed</div>
        <div class="grid grid-cols-2 gap-2">
          {#each Object.entries(enrollResult) as [k, v]}
            {#if typeof v !== 'object'}
              <div><div class="text-[10px] text-white/25">{k}</div><div class="text-[12px] text-white font-mono mt-0.5">{v}</div></div>
            {/if}
          {/each}
        </div>
      </div>
    {/if}

    <button
      onclick={doEnroll}
      disabled={enrollLoading || !enroll.nptHandle || !enroll.iban}
      class="mt-4 px-5 py-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-30 text-white text-[13px] font-semibold rounded-xl transition-all"
    >
      {enrollLoading ? 'Claiming…' : 'Claim Handle'}
    </button>
  </section>

  <!-- ── Link IBAN ─────────────────────────────────────────────────────────── -->
  <section class="rounded-2xl bg-white/[0.03] border border-white/[0.07] p-6">
    <div class="text-sm font-semibold mb-1">Link Additional IBAN</div>
    <div class="text-[12px] text-white/30 mb-4">Add another account to an existing identity</div>
    <div class="grid grid-cols-3 gap-3 items-end">
      <div>
        <label for="link-handle" class="block text-[11px] text-white/35 mb-1.5 uppercase tracking-wider">NPT Handle</label>
        <input id="link-handle" bind:value={linkHandle} placeholder="mtellesy"
          class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white font-mono placeholder-white/20 focus:outline-none focus:border-indigo-500/60 transition-all"/>
      </div>
      <div>
        <label for="link-iban" class="block text-[11px] text-white/35 mb-1.5 uppercase tracking-wider">IBAN</label>
        <input id="link-iban" bind:value={linkIban} placeholder="LY92010500…"
          class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white font-mono placeholder-white/20 focus:outline-none focus:border-indigo-500/60 transition-all"/>
      </div>
      <div class="space-y-2">
        <div class="flex items-center gap-2">
          <input type="checkbox" bind:checked={linkDefault} id="ld" class="w-4 h-4 accent-indigo-500"/>
          <label for="ld" class="text-[12px] text-white/35">Set as default</label>
        </div>
        <button onclick={doLink} disabled={linkLoading || !linkHandle || !linkIban}
          class="w-full px-4 py-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-30 text-white text-[13px] font-semibold rounded-xl transition-all">
          {linkLoading ? '…' : 'Link IBAN'}
        </button>
      </div>
    </div>
  </section>

  <!-- ── Unlink IBAN ───────────────────────────────────────────────────────── -->
  <section class="rounded-2xl bg-white/[0.03] border border-white/[0.07] p-6">
    <div class="text-sm font-semibold mb-1">Unlink IBAN</div>
    <div class="text-[12px] text-white/30 mb-4">Remove an account from an identity</div>
    <div class="grid grid-cols-3 gap-3 items-end">
      <div>
        <label for="unlink-handle" class="block text-[11px] text-white/35 mb-1.5 uppercase tracking-wider">NPT Handle</label>
        <input id="unlink-handle" bind:value={unlinkHandle} placeholder="mtellesy"
          class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white font-mono placeholder-white/20 focus:outline-none focus:border-red-500/60 transition-all"/>
      </div>
      <div>
        <label for="unlink-iban" class="block text-[11px] text-white/35 mb-1.5 uppercase tracking-wider">IBAN</label>
        <input id="unlink-iban" bind:value={unlinkIban} placeholder="LY83002700…"
          class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white font-mono placeholder-white/20 focus:outline-none focus:border-red-500/60 transition-all"/>
      </div>
      <button onclick={doUnlink} disabled={unlinkLoading || !unlinkHandle || !unlinkIban}
        class="px-4 py-2.5 bg-red-600/70 hover:bg-red-600 disabled:opacity-30 text-white text-[13px] font-semibold rounded-xl transition-all">
        {unlinkLoading ? '…' : 'Unlink'}
      </button>
    </div>
  </section>

  <!-- ── Set Default IBAN ──────────────────────────────────────────────────── -->
  <section class="rounded-2xl bg-white/[0.03] border border-white/[0.07] p-6">
    <div class="text-sm font-semibold mb-1">Set Default IBAN</div>
    <div class="text-[12px] text-white/30 mb-4">Which IBAN resolves for <code class="text-white/40">handle@bank</code></div>
    <div class="grid grid-cols-3 gap-3 items-end">
      <div>
        <label for="default-iban-handle" class="block text-[11px] text-white/35 mb-1.5 uppercase tracking-wider">NPT Handle</label>
        <input id="default-iban-handle" bind:value={defHandle} placeholder="mtellesy"
          class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white font-mono placeholder-white/20 focus:outline-none focus:border-indigo-500/60 transition-all"/>
      </div>
      <div>
        <label for="default-iban" class="block text-[11px] text-white/35 mb-1.5 uppercase tracking-wider">IBAN</label>
        <input id="default-iban" bind:value={defIban} placeholder="LY83002700…"
          class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white font-mono placeholder-white/20 focus:outline-none focus:border-indigo-500/60 transition-all"/>
      </div>
      <button onclick={doSetDefaultIban} disabled={defLoading || !defHandle || !defIban}
        class="px-4 py-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-30 text-white text-[13px] font-semibold rounded-xl transition-all">
        {defLoading ? '…' : 'Set Default'}
      </button>
    </div>
  </section>

  <!-- ── Set Default Bank ──────────────────────────────────────────────────── -->
  <section class="rounded-2xl bg-white/[0.03] border border-white/[0.07] p-6">
    <div class="text-sm font-semibold mb-1">Set Default Bank</div>
    <div class="text-[12px] text-white/30 mb-4">Which bank resolves for bare <code class="text-white/40">handle</code></div>
    <div class="grid grid-cols-3 gap-3 items-end">
      <div>
        <label for="default-bank-handle" class="block text-[11px] text-white/35 mb-1.5 uppercase tracking-wider">NPT Handle</label>
        <input id="default-bank-handle" bind:value={defBankHandle} placeholder="mtellesy"
          class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white font-mono placeholder-white/20 focus:outline-none focus:border-indigo-500/60 transition-all"/>
      </div>
      <div>
        <label for="default-bank" class="block text-[11px] text-white/35 mb-1.5 uppercase tracking-wider">Bank</label>
        <select id="default-bank" bind:value={defBankSelected}
          class="w-full bg-[#0d0d18] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white focus:outline-none focus:border-indigo-500/60 transition-all">
          <option value="">Select bank</option>
          {#each banks as b}
            <option value={b.bankHandle}>{b.displayName || b.bankHandle}</option>
          {/each}
        </select>
      </div>
      <button onclick={doSetDefaultBank} disabled={defBankLoading || !defBankHandle || !defBankSelected}
        class="px-4 py-2.5 bg-indigo-600 hover:bg-indigo-500 disabled:opacity-30 text-white text-[13px] font-semibold rounded-xl transition-all">
        {defBankLoading ? '…' : 'Set Default'}
      </button>
    </div>
  </section>
</div>
