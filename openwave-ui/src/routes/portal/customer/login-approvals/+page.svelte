<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { apiCall } from '$lib/api/client';
  import { toast } from 'svelte-sonner';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import Info from 'lucide-svelte/icons/info';

  let loading = $state(true);
  let rows = $state([]);
  let summary = $state({ total: 0, pending: 0, approved: 0, rejected: 0, expired: 0 });
  let status = $state('');
  const limit = 25;

  onMount(() => {
    hydrateFromQuery();
    loadHistory();
  });

  function hydrateFromQuery() {
    status = appPage.url.searchParams.get('status') ?? '';
  }

  async function syncQuery() {
    const params = new URLSearchParams();
    if (status) params.set('status', status);
    const query = params.toString();
    await goto(query ? `${appPage.url.pathname}?${query}` : appPage.url.pathname, {
      replaceState: true,
      noScroll: true,
      keepFocus: true
    });
  }

  async function loadHistory() {
    loading = true;
    const params = new URLSearchParams({ limit: String(limit) });
    if (status) params.set('status', status);
    const response = await apiCall('get', `/customer/login-approvals?${params.toString()}`);
    if (response.ok) {
      rows = response.data.items || [];
      summary = response.data.summary || summary;
    } else {
      rows = [];
      toast.error(response.error || 'Could not load sign-in history');
    }
    loading = false;
  }

  async function applyFilters() {
    await syncQuery();
    await loadHistory();
  }

  async function openDetail(row) {
    const params = new URLSearchParams();
    if (status) params.set('status', status);
    const query = params.toString();
    await goto(`/portal/customer/login-approvals/${row.challenge_id}${query ? `?${query}` : ''}`);
  }

  function badgeClass(rowStatus) {
    if (rowStatus === 'APPROVED') return 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300';
    if (rowStatus === 'REJECTED') return 'border-rose-500/25 bg-rose-500/10 text-rose-300';
    if (rowStatus === 'EXPIRED') return 'border-amber-500/25 bg-amber-500/10 text-amber-300';
    return 'border-sky-500/25 bg-sky-500/10 text-sky-300';
  }

  function when(value) {
    return value ? new Date(value).toLocaleString() : '-';
  }

  function hintClass() {
    return 'inline-flex h-4 w-4 cursor-help text-white/40';
  }
</script>

<svelte:head><title>Customer Sign-in Activity - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-7xl mx-auto space-y-5">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
      <div class="max-w-3xl">
        <div class="flex flex-wrap gap-2">
          <a href="/portal/customer" class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition hover:text-white">
            <ArrowLeft class="h-4 w-4" />
            Back to accounts
          </a>
        </div>
        <p class="mt-4 text-[11px] uppercase tracking-[0.18em] text-white/30">Customer sign-in history</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">Bank-approved sign-in activity</h1>
        <p class="identity-section-note mt-2 text-sm text-white/55">Review every customer sign-in that started from phone number or national ID and required linked-bank approval before the portal session was issued.</p>
        <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">
            Full owner visibility
            <span class="tooltip max-w-xs" data-tip="This is the customer’s own sign-in history desk. It shows approval outcomes and route context without support-side masking that belongs only in operator or merchant tools.">
              <Info class={hintClass()} />
            </span>
          </span>
          <span class="identity-role-accent">Linked-bank approval trail</span>
        </div>
      </div>
      <div class="flex flex-wrap gap-2">
        <a href="/portal/security?section=overview" class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition hover:text-white">
          <ShieldCheck class="h-4 w-4" />
          Security desk
        </a>
        <button onclick={loadHistory} disabled={loading} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition hover:text-white disabled:opacity-40">
          <RefreshCw class={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>
    </div>
  </section>

  <div class="grid gap-3 md:grid-cols-4">
    <div class="identity-kpi-card p-5"><p class="text-xs text-white/35">Visible requests</p><p class="mt-2 text-2xl font-semibold text-white">{summary.total}</p></div>
    <div class="identity-kpi-card p-5"><p class="text-xs text-white/35">Pending</p><p class="mt-2 text-2xl font-semibold text-white">{summary.pending}</p></div>
    <div class="identity-kpi-card p-5"><p class="text-xs text-white/35">Approved</p><p class="mt-2 text-2xl font-semibold text-white">{summary.approved}</p></div>
    <div class="identity-kpi-card p-5"><p class="text-xs text-white/35">Rejected or expired</p><p class="mt-2 text-2xl font-semibold text-white">{summary.rejected + summary.expired}</p></div>
  </div>

  <section class="identity-surface-card p-4">
    <div class="grid gap-3 md:grid-cols-[220px_auto]">
      <select bind:value={status} class="rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-sm text-white outline-none focus:border-indigo-400/60">
        <option value="">All statuses</option>
        <option value="PENDING">Pending</option>
        <option value="APPROVED">Approved</option>
        <option value="REJECTED">Rejected</option>
        <option value="EXPIRED">Expired</option>
      </select>
      <div class="flex justify-end">
        <button onclick={applyFilters} disabled={loading} class="rounded-xl bg-white/[0.08] px-4 py-2.5 text-sm font-medium text-white/75 hover:bg-white/[0.12] disabled:opacity-40">Apply</button>
      </div>
    </div>
  </section>

  <section class="identity-surface-card overflow-hidden">
    <div class="border-b border-white/[0.06] px-5 py-4">
      <h2 class="text-lg font-semibold text-white">Approval history</h2>
      <p class="mt-1 text-sm text-white/45">Open a row to inspect the exact bank approval result and route context that was used for that sign-in.</p>
    </div>
    <div class="overflow-x-auto">
      <div class="grid min-w-[1020px] grid-cols-[170px_110px_1fr_160px_170px_170px] gap-4 border-b border-white/[0.06] px-5 py-3 text-[11px] uppercase tracking-wider text-white/25">
        <span>Status</span>
        <span>Type</span>
        <span>Alias</span>
        <span>Approved bank</span>
        <span>Started</span>
        <span>Last action</span>
      </div>
      {#if loading}
        <div class="p-8 text-center text-white/40">Loading...</div>
      {:else if rows.length}
        {#each rows as row}
          <div
            class="grid min-w-[1020px] cursor-pointer grid-cols-[170px_110px_1fr_160px_170px_170px] gap-4 border-b border-white/[0.04] px-5 py-4 text-sm transition-all hover:bg-white/[0.02]"
            role="button"
            tabindex="0"
            onclick={() => openDetail(row)}
            onkeydown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                openDetail(row);
              }
            }}
          >
            <div>
              <span class={`inline-flex rounded-full border px-2.5 py-1 text-[11px] font-medium ${badgeClass(row.status)}`}>{row.status}</span>
              <div class="mt-2 text-[11px] text-white/35">Expires {when(row.expires_at)}</div>
            </div>
            <div class="text-white/75">{row.identifier_type}</div>
            <div>
              <div class="font-mono text-white">{row.requested_alias}</div>
              <div class="mt-1 text-[12px] text-white/45">Identifier hint {row.identifier_hint}</div>
            </div>
            <div class="font-mono text-white/65">{row.approved_bank_handle || row.default_bank_handle || '-'}</div>
            <div class="text-white/55">{when(row.created_at)}</div>
            <div class="text-white/55">{row.actioned_at ? when(row.actioned_at) : 'Pending approval'}</div>
          </div>
        {/each}
      {:else}
        <div class="px-5 py-10 text-center text-sm text-white/35">No matching sign-in history is recorded for this customer identity yet.</div>
      {/if}
    </div>
  </section>
</div>
