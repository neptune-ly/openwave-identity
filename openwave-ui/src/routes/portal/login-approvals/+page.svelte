<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { apiCall } from '$lib/api/client';
  import { toast } from 'svelte-sonner';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import Info from 'lucide-svelte/icons/info';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import CircleX from 'lucide-svelte/icons/circle-x';
  import Clock3 from 'lucide-svelte/icons/clock-3';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';

  let loading = $state(true);
  let actingId = $state('');
  let rows = $state([]);
  let summary = $state({ total: 0, pending: 0, approved: 0, rejected: 0, expired: 0 });
  let search = $state('');
  let status = $state('PENDING');
  const limit = 25;

  onMount(() => {
    hydrateFromQuery();
    loadQueue();
  });

  function hydrateFromQuery() {
    search = appPage.url.searchParams.get('search') ?? '';
    status = appPage.url.searchParams.get('status') ?? 'PENDING';
  }

  async function syncQuery() {
    const params = new URLSearchParams(appPage.url.searchParams);
    if (status) params.set('status', status);
    else params.delete('status');
    if (search.trim()) params.set('search', search.trim());
    else params.delete('search');
    const query = params.toString();
    await goto(query ? `${appPage.url.pathname}?${query}` : appPage.url.pathname, {
      replaceState: true,
      noScroll: true,
      keepFocus: true
    });
  }

  async function loadQueue() {
    loading = true;
    const params = new URLSearchParams({ limit: String(limit) });
    if (status) params.set('status', status);
    if (search.trim()) params.set('search', search.trim());
    const response = await apiCall('get', `/identity/login-approvals?${params.toString()}`);
    if (response.ok) {
      rows = response.data.items || [];
      summary = response.data.summary || summary;
    } else {
      rows = [];
      toast.error(response.error || 'Could not load bank login approvals');
    }
    loading = false;
  }

  async function applyFilters() {
    await syncQuery();
    await loadQueue();
  }

  async function act(challengeId, customerRef, action) {
    if (!customerRef) {
      toast.error('This approval row is missing a bank customer reference.');
      return;
    }
    actingId = challengeId;
    const response = await apiCall('post', `/identity/login-approvals/${challengeId}/${action}`, {
      customerRef
    });
    actingId = '';
    if (!response.ok) {
      toast.error(response.error || `Could not ${action} approval`);
      return;
    }
    toast.success(action === 'approve' ? 'Login approval confirmed' : 'Login approval rejected');
    await loadQueue();
    await syncQuery();
  }

  function badgeClass(rowStatus) {
    if (rowStatus === 'APPROVED') return 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300';
    if (rowStatus === 'REJECTED') return 'border-rose-500/25 bg-rose-500/10 text-rose-300';
    if (rowStatus === 'EXPIRED') return 'border-amber-500/25 bg-amber-500/10 text-amber-300';
    return 'border-sky-500/25 bg-sky-500/10 text-sky-300';
  }

  function hintClass() {
    return 'inline-flex h-4 w-4 cursor-help text-white/40';
  }

  function when(value) {
    return value ? new Date(value).toLocaleString() : '-';
  }

  async function openApprovalDesk(row) {
    if (loading || actingId) return;
    const params = new URLSearchParams();
    if (status) params.set('status', status);
    if (search.trim()) params.set('search', search.trim());
    const query = params.toString();
    await goto(`/portal/login-approvals/${row.challenge_id}${query ? `?${query}` : ''}`);
  }
</script>

<svelte:head><title>Login Approvals - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-7xl mx-auto space-y-5">
  <section class="identity-expressive-band p-6">
      <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
        <div class="max-w-3xl">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Bank Identity Approvals</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">Login Approvals</h1>
        <p class="identity-section-note mt-2 text-sm text-white/55">Review customer phone and national-ID sign-in requests that still require bank-backed approval before the Identity portal issues access.</p>
        <div class="mt-3 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">
            Bank-vouched access
          </span>
          <span class="identity-role-accent">
            Customer session stays blocked until approval
          </span>
        </div>
        <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
        <span class="inline-flex items-center gap-1 rounded-full border border-white/[0.08] px-2.5 py-1">
          Linked-bank approval
          <span class="tooltip max-w-xs" data-tip="Phone and national-ID sign-in can start from a public identifier, but the final customer session is issued only after one linked bank approves the request.">
            <Info class={hintClass()} />
          </span>
        </span>
        <span class="inline-flex items-center gap-1 rounded-full border border-white/[0.08] px-2.5 py-1">
          Support-safe queue
          <span class="tooltip max-w-xs" data-tip="This queue shows masked identifier hints and bank customer references only. It is designed for bank-side customer verification and approval handling.">
            <Info class={hintClass()} />
          </span>
        </span>
        </div>
      </div>
      <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Queue posture</div>
          <div class="mt-2 text-sm font-medium text-white">{summary.total} visible request(s)</div>
          <div class="mt-1 text-[12px] text-white/45">{summary.pending} pending · {summary.rejected + summary.expired} rejected or expired</div>
        </div>
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Approval rule</div>
          <div class="mt-2 text-sm font-medium text-white">Bank must confirm public-identifier sign-in.</div>
          <div class="mt-1 text-[12px] text-white/45">This preserves the bank-vouched identity model for Libya-scale access.</div>
        </div>
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Primary job</div>
          <div class="mt-2 text-sm font-medium text-white">Open a pending challenge and decide from the dedicated approval desk.</div>
          <div class="mt-1 text-[12px] text-white/45">Reject anything that cannot be confirmed from the bank context.</div>
        </div>
      </div>
    </div>
  </section>

  <div class="flex justify-end">
    <button onclick={loadQueue} disabled={loading} class="inline-flex items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2 text-[13px] font-medium text-white/60 transition-all hover:border-white/[0.18] hover:text-white disabled:opacity-40">
      <RefreshCw class={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
      Refresh
    </button>
  </div>

  <section class="identity-surface-card p-4">
    <div class="grid gap-3 md:grid-cols-[1fr_180px_auto]">
      <input
        bind:value={search}
        onkeydown={(event) => event.key === 'Enter' && applyFilters()}
        placeholder="Search alias, customer reference, or masked identifier"
        class="rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-sm text-white placeholder-white/25 outline-none focus:border-indigo-400/60"
      />
      <select bind:value={status} class="rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-sm text-white outline-none focus:border-indigo-400/60">
        <option value="PENDING">Pending</option>
        <option value="APPROVED">Approved</option>
        <option value="REJECTED">Rejected</option>
        <option value="EXPIRED">Expired</option>
        <option value="">All statuses</option>
      </select>
      <button onclick={applyFilters} disabled={loading} class="rounded-xl bg-white/[0.08] px-4 py-2.5 text-sm font-medium text-white/75 hover:bg-white/[0.12] disabled:opacity-40">Apply</button>
    </div>
  </section>

  <div class="grid gap-4 xl:grid-cols-[minmax(0,1fr)_340px]">
  <section class="identity-surface-card overflow-hidden">
    <div class="border-b border-white/[0.06] px-5 py-4">
      <div class="flex items-center justify-between gap-3">
        <div>
          <h2 class="text-lg font-semibold text-white">Approval queue</h2>
          <p class="mt-1 text-sm text-white/45">Each row is a bank-scoped approval challenge for phone or national-ID sign-in.</p>
        </div>
        <span class="identity-role-accent">Dedicated approval desks</span>
      </div>
    </div>
    <div class="overflow-x-auto">
    <div class="grid min-w-[1180px] grid-cols-[170px_110px_1fr_150px_170px_170px_220px] gap-4 border-b border-white/[0.06] px-5 py-3 text-[11px] uppercase tracking-wider text-white/25">
      <span>Status</span>
      <span>Type</span>
      <span>Alias</span>
      <span>Customer ref</span>
      <span>Identifier</span>
      <span>Created</span>
      <span>Actions</span>
    </div>
    {#if loading}
      <div class="p-8 text-center text-white/40">Loading...</div>
    {:else}
      {#each rows as row}
        <div
          class="grid min-w-[1180px] grid-cols-[170px_110px_1fr_150px_170px_170px_220px] gap-4 border-b border-white/[0.04] px-5 py-4 text-sm cursor-pointer transition-all hover:bg-white/[0.02]"
          role="button"
          tabindex="0"
          onclick={() => openApprovalDesk(row)}
          onkeydown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              openApprovalDesk(row);
            }
          }}
        >
          <div>
            <span class={`inline-flex rounded-full border px-2.5 py-1 text-[11px] font-medium ${badgeClass(row.status)}`}>{row.status}</span>
            <div class="mt-2 text-[11px] text-white/35">Expires {when(row.expires_at)}</div>
          </div>
          <div class="text-white/75">{row.identifier_type}</div>
          <div class="min-w-0">
            <div class="truncate font-mono text-white">{row.requested_alias}</div>
            <div class="mt-1 text-[11px] text-white/35">Default bank: {row.default_bank_handle || 'Not set'}</div>
          </div>
          <div class="font-mono text-white/65">{row.bank_customer_ref || '-'}</div>
          <div class="font-mono text-white/65">{row.identifier_hint}</div>
          <div class="text-white/55">
            <div>{when(row.created_at)}</div>
            <div class="mt-1 text-[11px] text-white/35">{row.actioned_at ? `Actioned ${when(row.actioned_at)}` : 'Awaiting action'}</div>
          </div>
          <div class="flex flex-wrap gap-2">
            <span class="inline-flex items-center gap-1 rounded-xl border border-white/[0.08] px-3 py-2 text-[11px] text-white/55">
              Open dedicated approval desk
            </span>
            {#if row.status === 'PENDING'}
              <span class="inline-flex items-center gap-1 rounded-xl border border-white/[0.08] px-3 py-2 text-[11px] text-white/45">
                <Clock3 class="h-3.5 w-3.5" />
                Customer bank-app confirmation
              </span>
            {/if}
          </div>
        </div>
      {:else}
        <div class="p-8 text-center text-white/40">No login approvals matched the current bank filter.</div>
      {/each}
    {/if}
    </div>
  </section>

  <aside class="identity-surface-card min-h-[320px] p-5">
    <div class="flex h-full flex-col justify-center gap-4 text-center">
      <div class="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.04] text-indigo-300">
        <ShieldCheck class="h-5 w-5" />
      </div>
      <div>
        <div class="text-base font-semibold text-white">Dedicated approval desks</div>
        <div class="mt-2 text-[13px] leading-5 text-white/45">
          Open a queue row to inspect the bank-vouched login challenge, copy the support-safe summary, and approve or reject sign-in on its own page.
        </div>
      </div>
      {#if rows.length}
        <button onclick={() => openApprovalDesk(rows[0])} class="inline-flex items-center justify-center gap-2 rounded-xl border border-white/[0.08] px-4 py-2.5 text-[13px] font-medium text-white/70 transition-all hover:border-white/[0.18] hover:text-white">
          Open first approval
          <ArrowRight class="h-4 w-4" />
        </button>
      {/if}
      <div class="rounded-xl border border-white/[0.08] bg-black/15 px-4 py-3 text-left text-[12px] text-white/45">
        Keep this queue page focused on filters, status, and queue monitoring. Per-challenge action review belongs on the dedicated approval desk.
      </div>
    </div>
  </aside>
  </div>
</div>
