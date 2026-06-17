<script>
  import { browser } from '$app/environment';
  import { goto } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { onMount } from 'svelte';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import { apiCall, apiPublic } from '$lib/api/client';
  import { auth } from '$lib/stores/auth';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';
  import BadgeInfo from 'lucide-svelte/icons/badge-info';
  import Building2 from 'lucide-svelte/icons/building-2';
  import CircleAlert from 'lucide-svelte/icons/circle-alert';
  import Link2 from 'lucide-svelte/icons/link-2';
  import Route from 'lucide-svelte/icons/route';
  import Unlink2 from 'lucide-svelte/icons/unlink-2';
  import UserPlus from 'lucide-svelte/icons/user-plus';

  const flowConfig = {
    claim: {
      title: 'Claim Handle',
      eyebrow: 'Customer enrollment desk',
      description: 'Create a bank-vouched identity record and establish the first active route.',
      submitLabel: 'Claim handle',
      icon: UserPlus,
      accent: 'text-indigo-300',
      queryKey: 'claim_handle'
    },
    link: {
      title: 'Link Account',
      eyebrow: 'Account linking desk',
      description: 'Attach another IBAN to the selected identity without mixing in other routing actions.',
      submitLabel: 'Link IBAN',
      icon: Link2,
      accent: 'text-emerald-300',
      queryKey: 'link_handle'
    },
    unlink: {
      title: 'Unlink Account',
      eyebrow: 'Route removal desk',
      description: 'Remove an outdated or invalid route from the selected identity record.',
      submitLabel: 'Unlink IBAN',
      icon: Unlink2,
      accent: 'text-rose-300',
      queryKey: 'unlink_handle'
    },
    'default-account': {
      title: 'Default IBAN',
      eyebrow: 'Route priority desk',
      description: 'Choose which IBAN resolves for a bank-specific alias route.',
      submitLabel: 'Set default IBAN',
      icon: Route,
      accent: 'text-amber-300',
      queryKey: 'default_handle'
    },
    'default-bank': {
      title: 'Default Bank',
      eyebrow: 'Network routing desk',
      description: 'Choose which bank resolves a bare NPT handle when no bank suffix is provided.',
      submitLabel: 'Set default bank',
      icon: Building2,
      accent: 'text-sky-300',
      queryKey: 'default_bank_handle'
    }
  };

  const flowOrder = ['claim', 'link', 'unlink', 'default-account', 'default-bank'];

  let session = $state(null);
  let banks = $state([]);
  let flow = $state(null);
  let loadingBanks = $state(false);
  let hydrated = $state(false);

  let enroll = $state({
    nptHandle: '',
    iban: '',
    customerDisplayName: '',
    bankCustomerRef: '',
    nationalId: '',
    phone: '',
    customerEmail: '',
    setAsDefault: true
  });
  let enrollResult = $state(null);
  let enrollLoading = $state(false);

  let linkHandle = $state('');
  let linkIban = $state('');
  let linkBankCustomerRef = $state('');
  let linkDefault = $state(false);
  let linkLoading = $state(false);

  let unlinkHandle = $state('');
  let unlinkIban = $state('');
  let unlinkBankHandle = $state('');
  let unlinkLoading = $state(false);

  let defHandle = $state('');
  let defIban = $state('');
  let defAccountBankHandle = $state('');
  let defLoading = $state(false);

  let defBankHandle = $state('');
  let defBankSelected = $state('');
  let defBankLoading = $state(false);

  const isBank = $derived(session?.role === 'BANK');
  const current = $derived(flow ? flowConfig[flow] : null);
  const currentBankHandle = $derived(session?.bankHandle || '');

  function resolveFlow() {
    const value = appPage.params.flow;
    return value && flowConfig[value] ? value : null;
  }

  async function loadBanks() {
    if (banks.length || loadingBanks) return;
    loadingBanks = true;
    const response = await apiPublic('/banks');
    if (response.ok) banks = response.data.banks || response.data || [];
    loadingBanks = false;
  }

  function applyPrefillsFromQuery() {
    const params = appPage.url.searchParams;
    enroll.nptHandle = params.get('claim_handle') || enroll.nptHandle;
    linkHandle = params.get('link_handle') || linkHandle;
    unlinkHandle = params.get('unlink_handle') || unlinkHandle;
    defHandle = params.get('default_handle') || defHandle;
    defBankHandle = params.get('default_bank_handle') || defBankHandle;
    linkBankCustomerRef = params.get('link_customer_ref') || linkBankCustomerRef;
    unlinkBankHandle = params.get('unlink_bank_handle') || unlinkBankHandle || currentBankHandle;
    defAccountBankHandle = params.get('default_account_bank_handle') || defAccountBankHandle || currentBankHandle;
  }

  function syncQuery() {
    if (!browser || !hydrated || !flow) return;
    const params = new URLSearchParams(window.location.search);
    ['claim_handle', 'link_handle', 'unlink_handle', 'default_handle', 'default_bank_handle', 'link_customer_ref', 'unlink_bank_handle', 'default_account_bank_handle'].forEach((key) => params.delete(key));

    if (flow === 'claim' && enroll.nptHandle.trim()) params.set('claim_handle', enroll.nptHandle.trim());
    if (flow === 'link' && linkHandle.trim()) params.set('link_handle', linkHandle.trim());
    if (flow === 'link' && linkBankCustomerRef.trim()) params.set('link_customer_ref', linkBankCustomerRef.trim());
    if (flow === 'unlink' && unlinkHandle.trim()) params.set('unlink_handle', unlinkHandle.trim());
    if (flow === 'unlink' && unlinkBankHandle.trim()) params.set('unlink_bank_handle', unlinkBankHandle.trim());
    if (flow === 'default-account' && defHandle.trim()) params.set('default_handle', defHandle.trim());
    if (flow === 'default-account' && defAccountBankHandle.trim()) params.set('default_account_bank_handle', defAccountBankHandle.trim());
    if (flow === 'default-bank' && defBankHandle.trim()) params.set('default_bank_handle', defBankHandle.trim());

    const query = params.toString();
    const nextUrl = `${window.location.pathname}${query ? `?${query}` : ''}`;
    window.history.replaceState(window.history.state, '', nextUrl);
  }

  async function openFlow(nextFlow) {
    const params = new URLSearchParams();
    if (nextFlow === 'claim' && enroll.nptHandle.trim()) params.set('claim_handle', enroll.nptHandle.trim());
    if (nextFlow === 'link' && (linkHandle.trim() || enroll.nptHandle.trim())) params.set('link_handle', linkHandle.trim() || enroll.nptHandle.trim());
    if (nextFlow === 'link' && linkBankCustomerRef.trim()) params.set('link_customer_ref', linkBankCustomerRef.trim());
    if (nextFlow === 'unlink' && (unlinkHandle.trim() || linkHandle.trim() || enroll.nptHandle.trim())) params.set('unlink_handle', unlinkHandle.trim() || linkHandle.trim() || enroll.nptHandle.trim());
    if (nextFlow === 'unlink' && unlinkBankHandle.trim()) params.set('unlink_bank_handle', unlinkBankHandle.trim());
    if (nextFlow === 'default-account' && (defHandle.trim() || unlinkHandle.trim() || linkHandle.trim() || enroll.nptHandle.trim())) params.set('default_handle', defHandle.trim() || unlinkHandle.trim() || linkHandle.trim() || enroll.nptHandle.trim());
    if (nextFlow === 'default-account' && defAccountBankHandle.trim()) params.set('default_account_bank_handle', defAccountBankHandle.trim());
    if (nextFlow === 'default-bank' && (defBankHandle.trim() || defHandle.trim() || enroll.nptHandle.trim())) params.set('default_bank_handle', defBankHandle.trim() || defHandle.trim() || enroll.nptHandle.trim());
    const query = params.toString();
    await goto(`/portal/identity/${nextFlow}${query ? `?${query}` : ''}`);
  }

  async function doEnroll() {
    if (!isBank) {
      toast.error('Customer enrollment is bank-scoped. Sign in with a bank portal account to continue.');
      return;
    }
    enrollLoading = true;
    enrollResult = null;
    const response = await apiCall('post', '/identity/claim', enroll);
    enrollLoading = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }
    enrollResult = response.data;
    const claimedHandle = enroll.nptHandle;
    const claimedCustomerRef = enroll.bankCustomerRef;
    enroll = { nptHandle: '', iban: '', customerDisplayName: '', bankCustomerRef: '', nationalId: '', phone: '', customerEmail: '', setAsDefault: true };
    toast.success('Handle claimed');
    if (claimedHandle) {
      await goto(`/portal/identity/link?link_handle=${encodeURIComponent(claimedHandle)}&link_customer_ref=${encodeURIComponent(claimedCustomerRef || '')}`);
    }
  }

  async function doLink() {
    if (!isBank) {
      toast.error('Account linking is bank-scoped. Sign in with a bank portal account to continue.');
      return;
    }
    linkLoading = true;
    const response = await apiCall('post', `/identity/${linkHandle}/accounts`, {
      iban: linkIban,
      bankCustomerRef: linkBankCustomerRef,
      setAsDefault: linkDefault
    });
    linkLoading = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }
    const linkedHandle = linkHandle;
    linkIban = '';
    linkDefault = false;
    toast.success('IBAN linked');
    await goto(`/portal/identity/default-account?default_handle=${encodeURIComponent(linkedHandle)}&default_account_bank_handle=${encodeURIComponent(currentBankHandle || '')}`);
  }

  async function doUnlink() {
    if (!isBank) {
      toast.error('Route removal is bank-scoped. Sign in with a bank portal account to continue.');
      return;
    }
    unlinkLoading = true;
    const response = await apiCall('delete', `/identity/${unlinkHandle}/accounts/iban/${encodeURIComponent(unlinkIban)}?bankHandle=${encodeURIComponent(unlinkBankHandle)}`);
    unlinkLoading = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }
    unlinkIban = '';
    toast.success('IBAN unlinked');
  }

  async function doSetDefaultIban() {
    if (!isBank) {
      toast.error('Default IBAN updates are bank-scoped. Sign in with a bank portal account to continue.');
      return;
    }
    defLoading = true;
    const response = await apiCall('patch', `/identity/${defHandle}/accounts/iban/${encodeURIComponent(defIban)}/set-default?bankHandle=${encodeURIComponent(defAccountBankHandle)}`);
    defLoading = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }
    toast.success('Default IBAN updated');
  }

  async function doSetDefaultBank() {
    if (!isBank) {
      toast.error('Default bank routing is bank-scoped. Sign in with a bank portal account to continue.');
      return;
    }
    defBankLoading = true;
    const response = await apiCall('patch', `/identity/${defBankHandle}/default-bank`, { bankHandle: defBankSelected });
    defBankLoading = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }
    toast.success('Default bank updated');
  }

  onMount(async () => {
    session = get(auth);
    flow = resolveFlow();
    if (!flow) {
      await goto('/portal/identity', { replaceState: true });
      return;
    }
    applyPrefillsFromQuery();
    if (flow === 'default-bank') {
      await loadBanks();
    }
    if (currentBankHandle) {
      unlinkBankHandle ||= currentBankHandle;
      defAccountBankHandle ||= currentBankHandle;
      defBankSelected ||= currentBankHandle;
    }
    hydrated = true;
  });

  $effect(() => {
    syncQuery();
  });
</script>

<svelte:head><title>{current ? `${current.title} - OpenWave Identity` : 'Identity Operations - OpenWave Identity'}</title></svelte:head>

{#if current}
  <div class="p-8 max-w-7xl mx-auto space-y-6">
    <section class="identity-expressive-band p-6">
      <div class="flex flex-col gap-5 xl:flex-row xl:items-start xl:justify-between">
        <div class="max-w-3xl">
          <a href="/portal/identity" class="inline-flex items-center gap-2 text-[12px] font-medium text-white/45 transition-colors hover:text-white/75">
            <ArrowLeft class="h-4 w-4" />
            Back to workflow launcher
          </a>
          <p class="mt-4 text-[11px] uppercase tracking-[0.18em] text-white/30">{current.eyebrow}</p>
          <div class="mt-3 flex items-center gap-3">
            <div class={`flex h-12 w-12 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.04] ${current.accent}`}>
              <current.icon class="h-5 w-5" />
            </div>
            <div>
              <h1 class="identity-page-title text-3xl font-semibold tracking-tight">{current.title}</h1>
              <p class="mt-1 text-sm text-white/50">{current.description}</p>
            </div>
          </div>
          <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
            <span class="identity-role-accent">{isBank ? session?.bankHandle || 'Bank scoped' : 'Registry scoped'}</span>
            <span class="identity-role-accent">Dedicated task route</span>
            <span class="identity-role-accent">Deep-link ready</span>
          </div>
        </div>
        <div class="identity-kpi-card min-w-[280px] px-5 py-4">
          <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">Use this desk for</p>
          <p class="mt-2 text-base font-semibold text-white">{current.title}</p>
          <p class="mt-1 text-sm text-white/45">
            Keep the operator in one action path. Move to another route only after this task is finished.
          </p>
        </div>
      </div>
    </section>

    <section class="grid gap-4 xl:grid-cols-[260px,minmax(0,1fr)]">
      <aside class="identity-surface-card p-4">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Workflow map</p>
        <div class="mt-3 space-y-2">
          {#each flowOrder as item}
            {@const config = flowConfig[item]}
            <button
              type="button"
              onclick={() => openFlow(item)}
              class={`flex w-full items-center justify-between rounded-2xl border px-4 py-3 text-left transition-all ${
                item === flow
                  ? 'border-white/[0.16] bg-white/[0.06] text-white'
                  : 'border-white/[0.06] bg-white/[0.02] text-white/60 hover:border-white/[0.12] hover:bg-white/[0.04] hover:text-white'
              }`}
            >
              <span>
                <span class="block text-[13px] font-semibold">{config.title}</span>
                <span class="mt-1 block text-[11px] text-white/40">{config.eyebrow}</span>
              </span>
              <ArrowRight class="h-4 w-4 shrink-0" />
            </button>
          {/each}
        </div>
      </aside>

      <div class="space-y-4">
        {#if flow === 'claim'}
          <section class="identity-surface-card p-6">
            <div class="flex flex-col gap-2 md:flex-row md:items-start md:justify-between">
              <div>
                <h2 class="text-lg font-semibold text-white">Customer enrollment</h2>
                <p class="mt-1 text-sm text-white/45">Bank-vouched handle claim with the first route attached at creation time.</p>
              </div>
              <div class={`rounded-xl border px-3 py-1.5 text-[11px] ${
                isBank
                  ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-300'
                  : 'border-amber-500/20 bg-amber-500/10 text-amber-300'
              }`}>
                {isBank ? 'Bank access active' : 'Admin mode'}
              </div>
            </div>

            <div class="mt-5 grid gap-3 md:grid-cols-2">
              {#each [
                ['nptHandle', 'NPT Handle', 'e.g. mtellesy'],
                ['customerDisplayName', 'Display Name', 'Full name'],
                ['iban', 'IBAN', 'LY83002700...'],
                ['bankCustomerRef', 'Customer Ref', 'Internal bank ID'],
                ['nationalId', 'National ID', '12-digit Libyan national ID'],
                ['phone', 'Phone', '0911091195 or +218911091195'],
                ['customerEmail', 'Customer Email', 'customer@example.ly']
              ] as [field, label, placeholder]}
                <div>
                  <label for={`claim-${field}`} class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">{label}</label>
                  <input
                    id={`claim-${field}`}
                    bind:value={enroll[field]}
                    placeholder={placeholder}
                    class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-indigo-400/60 focus:outline-none"
                  />
                </div>
              {/each}
            </div>

            <label class="mt-4 flex items-center gap-2 text-[13px] text-white/55">
              <input type="checkbox" bind:checked={enroll.setAsDefault} class="h-4 w-4 accent-indigo-500" />
              Set this bank as the default responder for the handle.
            </label>
            <div class="mt-3 rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4 text-[12px] text-white/50">
              National ID and customer email are mandatory for the bank-vouched Libya identity flow. Phone is optional but strongly recommended for continuity and public-identifier sign-in.
            </div>

            {#if enrollResult}
              <div class="mt-5 rounded-2xl border border-emerald-500/20 bg-emerald-500/[0.07] p-4">
                <div class="text-[11px] uppercase tracking-[0.16em] text-emerald-300">Claim created</div>
                <div class="mt-3 grid gap-3 md:grid-cols-2">
                  {#each Object.entries(enrollResult) as [key, value]}
                    {#if typeof value !== 'object'}
                      <div>
                        <div class="text-[10px] uppercase tracking-[0.14em] text-white/30">{key}</div>
                        <div class="mt-1 text-[13px] font-medium text-white">{value}</div>
                      </div>
                    {/if}
                  {/each}
                </div>
              </div>
            {/if}

            <div class="mt-5 flex flex-wrap gap-3">
              <button
                type="button"
                onclick={doEnroll}
                disabled={enrollLoading || !isBank || !enroll.nptHandle || !enroll.iban || !enroll.customerDisplayName || !enroll.bankCustomerRef || !enroll.nationalId || !enroll.customerEmail}
                class="rounded-2xl bg-indigo-500 px-5 py-3 text-[13px] font-semibold text-white transition-all hover:bg-indigo-400 disabled:opacity-40"
              >
                {enrollLoading ? 'Claiming...' : current.submitLabel}
              </button>
              <button type="button" onclick={() => openFlow('link')} class="identity-shell-button rounded-2xl border px-5 py-3 text-[13px] font-medium">
                Go to link account
              </button>
            </div>
          </section>
        {/if}

        {#if flow === 'link'}
          <section class="identity-surface-card p-6">
            <div class="flex items-start gap-3">
              <BadgeInfo class="mt-0.5 h-5 w-5 text-emerald-300" />
              <div>
                <h2 class="text-lg font-semibold text-white">Attach an additional route</h2>
                <p class="mt-1 text-sm text-white/45">Use this after the handle already exists. Default routing is optional here.</p>
              </div>
            </div>
            <div class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-[1fr,1fr,1fr,220px] xl:items-end">
              <div>
                <label for="link-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">NPT Handle</label>
                <input id="link-handle" bind:value={linkHandle} placeholder="mtellesy" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-emerald-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="link-iban" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">IBAN</label>
                <input id="link-iban" bind:value={linkIban} placeholder="LY92010500..." class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-emerald-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="link-customer-ref" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Customer Ref</label>
                <input id="link-customer-ref" bind:value={linkBankCustomerRef} placeholder="Internal bank ID" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-emerald-400/60 focus:outline-none" />
              </div>
              <div class="space-y-3">
                <label class="flex items-center gap-2 text-[13px] text-white/55">
                  <input type="checkbox" bind:checked={linkDefault} class="h-4 w-4 accent-emerald-500" />
                  Set as default
                </label>
                <button type="button" onclick={doLink} disabled={linkLoading || !isBank || !linkHandle || !linkIban || !linkBankCustomerRef} class="w-full rounded-2xl bg-emerald-500 px-5 py-3 text-[13px] font-semibold text-slate-950 transition-all hover:bg-emerald-400 disabled:opacity-40">
                  {linkLoading ? 'Linking...' : current.submitLabel}
                </button>
              </div>
            </div>
            <div class="mt-3 rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4 text-[12px] text-white/50">
              The bank customer reference is required so the linked IBAN stays auditable against the bank’s own customer record.
            </div>
          </section>
        {/if}

        {#if flow === 'unlink'}
          <section class="identity-surface-card p-6">
            <div class="flex items-start gap-3">
              <CircleAlert class="mt-0.5 h-5 w-5 text-rose-300" />
              <div>
                <h2 class="text-lg font-semibold text-white">Remove a route</h2>
                <p class="mt-1 text-sm text-white/45">Unlinking is permanent for the selected IBAN association. Confirm the alias and route carefully.</p>
              </div>
            </div>
            <div class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-[1fr,1fr,1fr,220px] xl:items-end">
              <div>
                <label for="unlink-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">NPT Handle</label>
                <input id="unlink-handle" bind:value={unlinkHandle} placeholder="mtellesy" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-rose-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="unlink-iban" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">IBAN</label>
                <input id="unlink-iban" bind:value={unlinkIban} placeholder="LY83002700..." class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-rose-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="unlink-bank-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Bank Handle</label>
                <input id="unlink-bank-handle" bind:value={unlinkBankHandle} placeholder="andalus" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-rose-400/60 focus:outline-none" />
              </div>
              <button type="button" onclick={doUnlink} disabled={unlinkLoading || !isBank || !unlinkHandle || !unlinkIban || !unlinkBankHandle} class="rounded-2xl bg-rose-500 px-5 py-3 text-[13px] font-semibold text-white transition-all hover:bg-rose-400 disabled:opacity-40">
                {unlinkLoading ? 'Unlinking...' : current.submitLabel}
              </button>
            </div>
          </section>
        {/if}

        {#if flow === 'default-account'}
          <section class="identity-surface-card p-6">
            <h2 class="text-lg font-semibold text-white">Default IBAN routing</h2>
            <p class="mt-1 text-sm text-white/45">This defines the resolved account for a bank-specific alias. Keep the handle and target IBAN on the same verified identity record.</p>
            <div class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-[1fr,1fr,1fr,220px] xl:items-end">
              <div>
                <label for="default-account-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">NPT Handle</label>
                <input id="default-account-handle" bind:value={defHandle} placeholder="mtellesy" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-amber-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="default-account-iban" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">IBAN</label>
                <input id="default-account-iban" bind:value={defIban} placeholder="LY83002700..." class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-amber-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="default-account-bank-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Bank Handle</label>
                <input id="default-account-bank-handle" bind:value={defAccountBankHandle} placeholder="andalus" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-amber-400/60 focus:outline-none" />
              </div>
              <button type="button" onclick={doSetDefaultIban} disabled={defLoading || !isBank || !defHandle || !defIban || !defAccountBankHandle} class="rounded-2xl bg-amber-400 px-5 py-3 text-[13px] font-semibold text-slate-950 transition-all hover:bg-amber-300 disabled:opacity-40">
                {defLoading ? 'Saving...' : current.submitLabel}
              </button>
            </div>
          </section>
        {/if}

        {#if flow === 'default-bank'}
          <section class="identity-surface-card p-6">
            <h2 class="text-lg font-semibold text-white">Default bank routing</h2>
            <p class="mt-1 text-sm text-white/45">Set which bank answers the bare NPT handle at the network level. Use this only when the customer has valid multi-bank presence.</p>
            <div class="mt-5 grid gap-3 md:grid-cols-[1fr,1fr,220px] md:items-end">
              <div>
                <label for="default-bank-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">NPT Handle</label>
                <input id="default-bank-handle" bind:value={defBankHandle} placeholder="mtellesy" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-sky-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="default-bank-select" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Bank</label>
                <select id="default-bank-select" bind:value={defBankSelected} class="w-full rounded-2xl border border-white/[0.1] bg-[#0d0d18] px-4 py-3 text-[13px] text-white focus:border-sky-400/60 focus:outline-none">
                  <option value="">Select bank</option>
                  {#each banks as bank}
                    <option value={bank.bankHandle}>{bank.displayName || bank.bankHandle}</option>
                  {/each}
                </select>
              </div>
              <button type="button" onclick={doSetDefaultBank} disabled={defBankLoading || !isBank || !defBankHandle || !defBankSelected} class="rounded-2xl bg-sky-400 px-5 py-3 text-[13px] font-semibold text-slate-950 transition-all hover:bg-sky-300 disabled:opacity-40">
                {defBankLoading ? 'Saving...' : current.submitLabel}
              </button>
            </div>
            {#if loadingBanks}
              <p class="mt-3 text-[12px] text-white/40">Loading bank registry...</p>
            {/if}
            <div class="mt-3 rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4 text-[12px] text-white/50">
              Default bank routing changes are bank-vouched actions. Registry admins can review identity state, but they should not impersonate a bank-scoped routing decision from this desk.
            </div>
          </section>
        {/if}
      </div>
    </section>
  </div>
{/if}
