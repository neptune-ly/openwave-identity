<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { apiCall, apiPublic } from '$lib/api/client';
  import { toast } from 'svelte-sonner';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import Copy from 'lucide-svelte/icons/copy';
  import Info from 'lucide-svelte/icons/info';
  import Building2 from 'lucide-svelte/icons/building-2';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import Route from 'lucide-svelte/icons/route';

  let loading = $state(true);
  let profile = $state(null);
  let accounts = $state([]);

  const alias = $derived(appPage.params.alias ? decodeURIComponent(appPage.params.alias) : '');

  onMount(loadRecord);

  async function loadRecord() {
    loading = true;
    try {
      const [profileResponse, accountsResponse] = await Promise.all([
        apiPublic(`/identity/${encodeURIComponent(alias)}`),
        apiCall('get', `/identity/${encodeURIComponent(alias)}/accounts`)
      ]);
      if (!profileResponse.ok) {
        toast.error(profileResponse.error || 'Could not load identity record');
        await goto('/portal/reports');
        return;
      }
      if (!accountsResponse.ok) {
        toast.error(accountsResponse.error || 'Could not load linked accounts');
        await goto('/portal/reports');
        return;
      }
      profile = profileResponse.data;
      accounts = accountsResponse.data.accounts || accountsResponse.data || [];
    } finally {
      loading = false;
    }
  }

  function backHref() {
    const query = appPage.url.searchParams.toString();
    return query ? `/portal/reports?${query}` : '/portal/reports';
  }

  async function copySummary() {
    const lines = [
      'Portal: OpenWave Identity Report Desk',
      `Alias: ${profile?.nptHandle || profile?.fullAlias || alias || '-'}`,
      `Display name: ${profile?.customerDisplayName || profile?.displayName || '-'}`,
      `Default bank: ${profile?.defaultBankHandle || '-'}`,
      `Linked accounts: ${accounts.length}`,
      `Accounts: ${accounts.map((account) => `${account.bankName || account.bankHandle || 'Bank'} ${account.ibanMasked || account.iban_masked || account.iban || '-'}`).join('; ') || 'None'}`
    ];
    await navigator.clipboard.writeText(lines.join('\n'));
    toast.success('Report desk summary copied');
  }

  function kpiCards() {
    return [
      {
        label: 'Alias status',
        value: profile?.isActive === false ? 'Inactive' : 'Active',
        detail: profile?.enrolledAt ? `Enrolled ${new Date(profile.enrolledAt).toLocaleDateString()}` : 'Enrollment date unavailable',
        icon: ShieldCheck
      },
      {
        label: 'Default route',
        value: profile?.defaultBankHandle || 'Not set',
        detail: 'Bare-handle routing bank',
        icon: Building2
      },
      {
        label: 'Linked accounts',
        value: String(accounts.length),
        detail: 'Bank-scoped linked route records',
        icon: Route
      }
    ];
  }

  function detailRows() {
    return [
      ['Alias', profile?.fullAlias || profile?.nptHandle || alias],
      ['Customer display name', profile?.customerDisplayName || profile?.displayName || '-'],
      ['Default bank', profile?.defaultBankHandle || '-'],
      ['Registered at', profile?.registeredAt ? new Date(profile.registeredAt).toLocaleString() : '-'],
      ['Linked account count', String(profile?.linkedAccountCount ?? accounts.length)]
    ];
  }

  function hintClass() {
    return 'inline-flex h-4 w-4 cursor-help text-white/40';
  }
</script>

<svelte:head><title>Report Desk - OpenWave Identity</title></svelte:head>

<div class="mx-auto max-w-6xl space-y-6 p-8">
  <div class="flex flex-wrap items-center justify-between gap-3">
    <a href={backHref()} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white">
      <ArrowLeft class="h-4 w-4" />
      Back to reports
    </a>
    <div class="flex flex-wrap gap-2">
      <button onclick={loadRecord} disabled={loading} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">
        <RefreshCw class={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
        Refresh
      </button>
      <button onclick={copySummary} disabled={loading || !profile} class="inline-flex items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2 text-[13px] font-medium text-white/70 transition-all hover:border-white/[0.18] hover:text-white disabled:opacity-40">
        <Copy class="h-4 w-4" />
        Copy summary
      </button>
    </div>
  </div>

  {#if loading}
    <section class="identity-surface-card p-8 text-center text-sm text-white/40">Loading report desk...</section>
  {:else if profile}
    <section class="identity-expressive-band p-6">
      <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
        <div class="max-w-3xl">
          <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Bank-scoped identity report desk</p>
          <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">{profile.fullAlias || profile.nptHandle || alias}</h1>
          <p class="identity-section-note mt-2 text-sm text-white/55">
            Use this dedicated record page for support-safe review of alias routing, default bank posture, and linked account coverage without overloading the report ledger.
          </p>
          <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
            <span class="identity-role-accent">Dedicated report desk</span>
            <span class="identity-role-accent">Masked linked-account review</span>
            <span class="identity-role-accent">Bank-scoped identity record</span>
          </div>
        </div>
        <div class="grid gap-3 sm:grid-cols-2">
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Data policy</div>
            <div class="mt-2 text-sm font-medium text-white">Support-safe review only.</div>
            <div class="mt-1 text-[12px] text-white/45">Full IBANs, raw phones, recovery tokens, and sensitive credentials do not belong in this desk.</div>
          </div>
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Route use</div>
            <div class="mt-2 text-sm font-medium text-white">Inspect before correction.</div>
            <div class="mt-1 text-[12px] text-white/45">Open Identity Operations only when a route or default-bank change is actually required.</div>
          </div>
        </div>
      </div>
    </section>

    <div class="grid gap-3 md:grid-cols-3">
      {#each kpiCards() as item}
        <section class="identity-kpi-card px-5 py-4">
          <div class="flex items-center gap-3">
            <div class="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-indigo-300">
              <item.icon class="h-5 w-5" />
            </div>
            <div>
              <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">{item.label}</p>
              <p class="mt-1 text-lg font-semibold">{item.value}</p>
              <p class="mt-1 text-[12px] text-white/45">{item.detail}</p>
            </div>
          </div>
        </section>
      {/each}
    </div>

    <div class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
      <section class="identity-surface-card p-5">
        <div class="flex items-center gap-2">
          <div class="text-sm font-semibold text-white">Linked accounts</div>
          <span class="tooltip max-w-xs" data-tip="The list stays bank-scoped and masked. Use it to verify route count, bank labels, and default posture before making corrections.">
            <Info class={hintClass()} />
          </span>
        </div>
        {#if accounts.length}
          <div class="mt-4 space-y-3">
            {#each accounts as account}
              <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-4">
                <div class="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <div class="text-sm font-semibold text-white">{account.bankName || account.bankHandle || 'Bank route'}</div>
                    <div class="mt-1 text-[12px] text-white/55">{account.ibanMasked || account.iban_masked || account.iban || 'No IBAN'}</div>
                    <div class="mt-1 text-[12px] text-white/35">{account.accountLabel || account.accountNumberMasked || 'No account label'}</div>
                  </div>
                  <div class="flex flex-wrap gap-2">
                    <span class={`rounded-full border px-2.5 py-1 text-[11px] ${account.isDefault ? 'border-emerald-400/20 bg-emerald-400/10 text-emerald-300' : 'border-white/[0.08] bg-white/[0.04] text-white/45'}`}>
                      {account.isDefault ? 'Default' : 'Linked'}
                    </span>
                    <span class={`rounded-full border px-2.5 py-1 text-[11px] ${account.isActive === false ? 'border-red-400/20 bg-red-400/10 text-red-300' : 'border-emerald-400/20 bg-emerald-400/10 text-emerald-300'}`}>
                      {account.isActive === false ? 'Inactive' : 'Active'}
                    </span>
                  </div>
                </div>
              </div>
            {/each}
          </div>
        {:else}
          <div class="mt-4 rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-6 text-sm text-white/40">
            No linked accounts were returned for this bank scope.
          </div>
        {/if}
      </section>

      <aside class="space-y-5">
        <section class="identity-surface-card p-5">
          <div class="text-sm font-semibold text-white">Identity facts</div>
          <div class="mt-4 grid gap-2">
            {#each detailRows() as row}
              <div class="rounded-xl border border-white/[0.08] bg-white/[0.03] px-4 py-3">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/28">{row[0]}</div>
                <div class="mt-2 break-all text-sm text-white">{row[1]}</div>
              </div>
            {/each}
          </div>
        </section>

        <section class="identity-surface-card p-5">
          <div class="text-sm font-semibold text-white">Next desk</div>
          <div class="mt-2 text-[13px] leading-5 text-white/45">
            If this record needs routing correction, default-bank change, or linked-account maintenance, continue in Identity Operations with the same alias context.
          </div>
          <a href={`/portal/identity/default-account?default_handle=${encodeURIComponent(profile.fullAlias || profile.nptHandle || alias)}`} class="mt-4 inline-flex items-center gap-2 rounded-xl border border-white/[0.08] px-4 py-2.5 text-[13px] font-medium text-white/70 transition-all hover:border-white/[0.18] hover:text-white">
            Open Identity Operations
          </a>
        </section>
      </aside>
    </div>
  {/if}
</div>
