<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { apiCall, getApi } from '$lib/api/client';
  import { toast } from 'svelte-sonner';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';

  let loading = $state(true);
  let exporting = $state(false);
  let aliases = $state([]);
  let total = $state(0);
  let page = $state(0);
  let totalPages = $state(1);
  let search = $state('');
  let activeOnly = $state(false);
  let approvalRows = $state([]);
  let approvalSummary = $state({ total: 0, pending: 0, approved: 0, rejected: 0, expired: 0 });
  let section = $state('ledger');
  const limit = 25;

  onMount(() => {
    hydrateFromQuery();
    loadReports();
  });

  function hydrateFromQuery() {
    search = appPage.url.searchParams.get('search') ?? '';
    activeOnly = (appPage.url.searchParams.get('active_only') ?? 'false') === 'true';
    const nextPage = Number(appPage.url.searchParams.get('page') ?? '0');
    page = Number.isFinite(nextPage) && nextPage >= 0 ? nextPage : 0;
    section = appPage.url.searchParams.get('section') === 'approvals' ? 'approvals' : 'ledger';
  }

  function aliasKey(alias) {
    return alias.fullAlias || alias.alias || alias.customerId || '';
  }

  async function syncQuery() {
    const params = new URLSearchParams();
    if (search.trim()) params.set('search', search.trim());
    if (activeOnly) params.set('active_only', 'true');
    if (page > 0) params.set('page', String(page));
    if (section === 'approvals') params.set('section', 'approvals');
    const query = params.toString();
    await goto(query ? `${appPage.url.pathname}?${query}` : appPage.url.pathname, {
      replaceState: true,
      noScroll: true,
      keepFocus: true
    });
  }

  async function loadReports() {
    loading = true;
    const params = new URLSearchParams({
      activeOnly: String(activeOnly),
      page: String(page),
      limit: String(limit)
    });
    if (search.trim()) params.set('search', search.trim());
    const [ledgerResponse, approvalsResponse] = await Promise.all([
      apiCall('get', `/identity/accounts?${params.toString()}`),
      apiCall('get', '/identity/login-approvals?limit=5')
    ]);
    if (ledgerResponse.ok) {
      aliases = ledgerResponse.data.aliases || [];
      total = ledgerResponse.data.total || aliases.length;
      totalPages = ledgerResponse.data.totalPages || 1;
      page = ledgerResponse.data.page || 0;
    } else {
      aliases = [];
      toast.error(ledgerResponse.error || 'Could not load bank reports');
    }
    if (approvalsResponse.ok) {
      approvalRows = approvalsResponse.data.items || [];
      approvalSummary = approvalsResponse.data.summary || approvalSummary;
    } else {
      approvalRows = [];
    }
    loading = false;
  }

  async function applyFilters() {
    page = 0;
    await syncQuery();
    await loadReports();
  }

  async function setSection(nextSection) {
    if (loading || section === nextSection) return;
    section = nextSection;
    await syncQuery();
  }

  async function exportCsv() {
    if (exporting) return;
    exporting = true;
    try {
      const params = new URLSearchParams({
        activeOnly: String(activeOnly),
        page: '0',
        limit: '1000',
        format: 'csv'
      });
      if (search.trim()) params.set('search', search.trim());
      const response = await getApi().get(`/identity/accounts?${params.toString()}`, { responseType: 'blob' });
      const blob = new Blob([response.data], { type: 'text/csv;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `openwave-identity-bank-report-${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      toast.success('Bank report CSV exported');
    } catch (e) {
      toast.error(e?.response?.data?.message || 'Could not export bank report');
    } finally {
      exporting = false;
    }
  }

  async function nextPage() {
    if (loading || page + 1 >= totalPages) return;
    page += 1;
    await syncQuery();
    await loadReports();
  }

  async function previousPage() {
    if (loading || page <= 0) return;
    page -= 1;
    await syncQuery();
    await loadReports();
  }

  const activeCount = $derived(aliases.filter((a) => a.isActive !== false).length);
  const accountCount = $derived(aliases.reduce((sum, a) => sum + (a.accounts?.length || 0), 0));

  function approvalBadgeClass(rowStatus) {
    if (rowStatus === 'APPROVED') return 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300';
    if (rowStatus === 'REJECTED') return 'border-rose-500/25 bg-rose-500/10 text-rose-300';
    if (rowStatus === 'EXPIRED') return 'border-amber-500/25 bg-amber-500/10 text-amber-300';
    return 'border-sky-500/25 bg-sky-500/10 text-sky-300';
  }

  function when(value) {
    return value ? new Date(value).toLocaleString() : '-';
  }

  async function openApprovalDesk(row) {
    if (loading) return;
    await goto(`/portal/login-approvals/${row.challenge_id}`);
  }

  async function openAliasDesk(alias) {
    if (loading) return;
    const carry = new URLSearchParams();
    if (search.trim()) carry.set('search', search.trim());
    if (activeOnly) carry.set('active_only', 'true');
    if (page > 0) carry.set('page', String(page));
    const query = carry.toString();
    await goto(`/portal/reports/${encodeURIComponent(aliasKey(alias))}${query ? `?${query}` : ''}`);
  }

</script>

<svelte:head><title>Reports - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-6xl mx-auto space-y-5">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
      <div class="max-w-3xl">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Bank reporting desk</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">Reports</h1>
        <p class="identity-section-note text-sm mt-2 text-white/55">One desk for the bank customer ledger, export-safe reporting, and current sign-in approval load.</p>
        <div class="mt-3 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">Support-safe export</span>
          <span class="identity-role-accent">Masked customer routing view</span>
          <span class="identity-role-accent">Bank-scoped identity ledger</span>
        </div>
      </div>
      <div class="grid gap-3 sm:grid-cols-2">
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Scope</div>
          <div class="mt-2 text-sm font-medium text-white">Only identities registered under this bank.</div>
          <div class="mt-1 text-[12px] text-white/45">Other-bank aliases and recovery data remain outside this workspace.</div>
        </div>
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Use</div>
          <div class="mt-2 text-sm font-medium text-white">Support, audit triage, and enrollment review.</div>
          <div class="mt-1 text-[12px] text-white/45">CSV exports remain masked and support-safe.</div>
        </div>
      </div>
    </div>
  </section>

  <div class="flex flex-wrap gap-2 justify-end">
      <button onclick={loadReports} disabled={loading} class="px-4 py-2 text-[13px] font-medium text-white/45 hover:text-white border border-white/[0.1] rounded-xl transition-all">Refresh</button>
      <button onclick={exportCsv} disabled={exporting || loading} class="px-4 py-2 text-[13px] font-medium text-white bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 rounded-xl transition-all">{exporting ? 'Exporting...' : 'Export CSV'}</button>
  </div>

  <section class="identity-surface-card p-4">
    <div class="grid gap-3 md:grid-cols-[1fr_auto_auto]">
      <input
        bind:value={search}
        onkeydown={(event) => event.key === 'Enter' && applyFilters()}
        placeholder="Search alias, customer ref, account label, or IBAN"
        class="rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-sm text-white placeholder-white/25 outline-none focus:border-indigo-400/60"
      />
      <label class="flex items-center gap-2 rounded-xl border border-white/[0.08] px-3.5 py-2.5 text-sm text-white/60">
        <input type="checkbox" bind:checked={activeOnly} onchange={applyFilters} />
        Active only
      </label>
      <button onclick={applyFilters} disabled={loading} class="rounded-xl bg-white/[0.08] px-4 py-2.5 text-sm font-medium text-white/75 hover:bg-white/[0.12] disabled:opacity-40">Apply</button>
    </div>
  </section>

  <div class="grid gap-3 md:grid-cols-5">
    <div class="identity-kpi-card p-5"><p class="text-xs text-white/35">Registered customers</p><p class="mt-2 text-2xl font-semibold text-white">{total}</p></div>
    <div class="identity-kpi-card p-5"><p class="text-xs text-white/35">Active aliases</p><p class="mt-2 text-2xl font-semibold text-white">{activeCount}</p></div>
    <div class="identity-kpi-card p-5"><p class="text-xs text-white/35">Linked accounts</p><p class="mt-2 text-2xl font-semibold text-white">{accountCount}</p></div>
    <div class="identity-kpi-card p-5"><p class="text-xs text-white/35">Pending sign-in approvals</p><p class="mt-2 text-2xl font-semibold text-white">{approvalSummary.pending ?? 0}</p></div>
    <div class="identity-kpi-card p-5"><p class="text-xs text-white/35">Recent approval events</p><p class="mt-2 text-2xl font-semibold text-white">{approvalSummary.total ?? 0}</p></div>
  </div>

  <div class="grid gap-4 xl:grid-cols-[260px_minmax(0,1fr)]">
    <aside class="identity-surface-card p-4">
      <div class="text-sm font-semibold text-white">Reports desk</div>
      <p class="mt-2 text-sm text-white/45">Use one scoped reporting page, then open the specific identity or approval desk only when follow-up is needed.</p>
      <div class="mt-4 space-y-2">
        <button
          type="button"
          class={`w-full rounded-xl border px-3 py-3 text-left transition ${section === 'ledger' ? 'border-white/[0.16] bg-white/[0.08]' : 'border-white/[0.08] bg-white/[0.03] hover:bg-white/[0.05]'}`}
          onclick={() => setSection('ledger')}
        >
          <div class="text-sm font-medium text-white">Customer ledger</div>
          <div class="mt-1 text-xs text-white/45">Filter, export, and open the identity record desk.</div>
        </button>
        <button
          type="button"
          class={`w-full rounded-xl border px-3 py-3 text-left transition ${section === 'approvals' ? 'border-white/[0.16] bg-white/[0.08]' : 'border-white/[0.08] bg-white/[0.03] hover:bg-white/[0.05]'}`}
          onclick={() => setSection('approvals')}
        >
          <div class="text-sm font-medium text-white">Sign-in approvals</div>
          <div class="mt-1 text-xs text-white/45">Current approval load and quick handoff into the approval queue.</div>
        </button>
      </div>
      <div class="mt-4 rounded-xl border border-white/[0.08] bg-black/15 px-4 py-3 text-left text-[12px] text-white/45">
        Exports stay masked and support-safe. Full IBANs, raw phone values, and recovery data are not exposed here.
      </div>
    </aside>

    <div class="space-y-4">
      <section class="identity-surface-card p-5">
        <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
          <div>
            <div class="flex flex-wrap gap-2 text-[11px] uppercase tracking-[0.16em] text-white/30">
              <span>{section === 'ledger' ? 'Customer ledger' : 'Approval activity'}</span>
              <span class="identity-role-accent normal-case tracking-normal text-[11px]">{section === 'ledger' ? `${total} registered customer(s)` : `${approvalSummary.pending ?? 0} pending approval(s)`}</span>
            </div>
            <h2 class="mt-2 text-lg font-semibold text-white">{section === 'ledger' ? 'Registered customer ledger' : 'Recent sign-in approvals'}</h2>
            <p class="mt-2 text-sm text-white/45">
              {section === 'ledger'
                ? 'Masked alias and linked-account visibility for the current bank scope.'
                : 'Current approval activity and quick handoff into the dedicated bank approval queue.'}
            </p>
          </div>
          <div class="flex flex-wrap gap-2">
            {#if section === 'ledger' && aliases.length}
              <button onclick={() => openAliasDesk(aliases[0])} class="inline-flex items-center justify-center gap-2 rounded-xl border border-white/[0.08] px-4 py-2.5 text-[13px] font-medium text-white/70 transition-all hover:border-white/[0.18] hover:text-white">
                Open first record
                <ArrowRight class="h-4 w-4" />
              </button>
            {/if}
            <a href="/portal/login-approvals" class="inline-flex items-center justify-center gap-2 rounded-xl border border-white/[0.08] px-4 py-2.5 text-[13px] font-medium text-white/70 transition-all hover:border-white/[0.18] hover:text-white">
              Open approval queue
              <ArrowRight class="h-4 w-4" />
            </a>
          </div>
        </div>
      </section>

      {#if section === 'ledger'}
        <section class="identity-surface-card overflow-hidden">
          <div class="overflow-x-auto">
            <div class="grid min-w-[760px] grid-cols-[1fr_150px_120px_100px_130px] gap-4 border-b border-white/[0.06] px-5 py-3 text-[11px] uppercase tracking-wider text-white/25">
              <span>Alias</span><span>Customer ref</span><span>Accounts</span><span>Status</span><span>Enrolled</span>
            </div>
            {#if loading}
              <div class="p-8 text-center text-white/40">Loading...</div>
            {:else}
              {#each aliases as alias}
                <div
                  class="grid min-w-[760px] cursor-pointer grid-cols-[1fr_150px_120px_100px_130px] gap-4 border-b border-white/[0.04] px-5 py-3.5 text-sm transition-colors hover:bg-white/[0.03]"
                  role="button"
                  tabindex="0"
                  onclick={() => openAliasDesk(alias)}
                  onkeydown={(event) => (event.key === 'Enter' || event.key === ' ') && openAliasDesk(alias)}
                >
                  <span class="font-mono">
                    {alias.fullAlias || alias.alias}
                    <span class="block text-[11px] text-white/35">{alias.accounts?.map((a) => a.ibanMasked).join(', ') || 'No linked accounts'}</span>
                  </span>
                  <span class="font-mono text-white/55 truncate">{alias.customerId || '-'}</span>
                  <span>{alias.accounts?.length || 0}</span>
                  <span>{alias.isActive === false ? 'Inactive' : 'Active'}</span>
                  <span class="text-white/45">{alias.enrolledAt ? new Date(alias.enrolledAt).toLocaleDateString() : '-'}</span>
                </div>
              {:else}
                <div class="p-8 text-center text-white/40">No registered customers for this bank.</div>
              {/each}
            {/if}
            <div class="flex items-center justify-between border-t border-white/[0.06] px-5 py-3 text-sm text-white/45">
              <span>Page {page + 1} of {Math.max(totalPages, 1)}</span>
              <div class="flex gap-2">
                <button onclick={previousPage} disabled={loading || page <= 0} class="rounded-lg border border-white/[0.08] px-3 py-1.5 disabled:opacity-30">Previous</button>
                <button onclick={nextPage} disabled={loading || page + 1 >= totalPages} class="rounded-lg border border-white/[0.08] px-3 py-1.5 disabled:opacity-30">Next</button>
              </div>
            </div>
          </div>
        </section>
      {:else}
        <section class="identity-surface-card p-5">
          <div class="grid gap-3 md:grid-cols-5">
            <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Pending</div><div class="mt-2 text-xl font-semibold text-white">{approvalSummary.pending ?? 0}</div></div>
            <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Approved</div><div class="mt-2 text-xl font-semibold text-white">{approvalSummary.approved ?? 0}</div></div>
            <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Rejected</div><div class="mt-2 text-xl font-semibold text-white">{approvalSummary.rejected ?? 0}</div></div>
            <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Expired</div><div class="mt-2 text-xl font-semibold text-white">{approvalSummary.expired ?? 0}</div></div>
            <div class="identity-surface-soft px-4 py-3"><div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Total events</div><div class="mt-2 text-xl font-semibold text-white">{approvalSummary.total ?? 0}</div></div>
          </div>
          <div class="mt-4 space-y-2">
            {#if approvalRows.length}
              {#each approvalRows as row}
                <button
                  type="button"
                  onclick={() => openApprovalDesk(row)}
                  class="w-full rounded-xl border border-white/[0.08] bg-white/[0.03] px-3 py-3 text-left transition hover:bg-white/[0.05]"
                >
                  <div class="flex items-start justify-between gap-3">
                    <div>
                      <div class="font-mono text-[12px] text-white">{row.requested_alias}</div>
                      <div class="mt-1 text-[11px] text-white/45">{row.identifier_type} · {row.identifier_hint}</div>
                      <div class="mt-1 text-[11px] text-white/35">{row.actioned_at ? `Actioned ${when(row.actioned_at)}` : `Started ${when(row.created_at)}`}</div>
                    </div>
                    <span class={`inline-flex rounded-full border px-2 py-0.5 text-[10px] font-medium ${approvalBadgeClass(row.status)}`}>{row.status}</span>
                  </div>
                </button>
              {/each}
            {:else}
              <div class="rounded-xl border border-dashed border-white/[0.12] px-3 py-5 text-sm text-white/35">
                No recent bank approval activity is loaded for this scope.
              </div>
            {/if}
          </div>
        </section>
      {/if}
    </div>
  </div>
</div>
