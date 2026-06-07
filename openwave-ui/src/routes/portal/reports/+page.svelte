<script>
  import { onMount } from 'svelte';
  import { apiCall, getApi } from '$lib/api/client';
  import { toast } from 'svelte-sonner';

  let loading = $state(true);
  let exporting = $state(false);
  let aliases = $state([]);
  let total = $state(0);
  let page = $state(0);
  let totalPages = $state(1);
  let search = $state('');
  let activeOnly = $state(false);
  const limit = 25;

  onMount(loadReports);

  async function loadReports() {
    loading = true;
    const params = new URLSearchParams({
      activeOnly: String(activeOnly),
      page: String(page),
      limit: String(limit)
    });
    if (search.trim()) params.set('search', search.trim());
    const response = await apiCall('get', `/identity/accounts?${params.toString()}`);
    if (response.ok) {
      aliases = response.data.aliases || [];
      total = response.data.total || aliases.length;
      totalPages = response.data.totalPages || 1;
      page = response.data.page || 0;
    }
    else toast.error(response.error || 'Could not load bank reports');
    loading = false;
  }

  async function applyFilters() {
    page = 0;
    await loadReports();
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
    await loadReports();
  }

  async function previousPage() {
    if (loading || page <= 0) return;
    page -= 1;
    await loadReports();
  }

  const activeCount = $derived(aliases.filter((a) => a.isActive !== false).length);
  const accountCount = $derived(aliases.reduce((sum, a) => sum + (a.accounts?.length || 0), 0));
</script>

<svelte:head><title>Reports - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-6xl mx-auto space-y-5">
  <div class="flex flex-col lg:flex-row lg:items-end justify-between gap-4">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">Reports</h1>
      <p class="text-white/40 text-sm mt-1">Bank-scoped NPT registrations and linked-account visibility.</p>
    </div>
    <div class="flex flex-wrap gap-2">
      <button onclick={loadReports} disabled={loading} class="px-4 py-2 text-[13px] font-medium text-white/45 hover:text-white border border-white/[0.1] rounded-xl transition-all">Refresh</button>
      <button onclick={exportCsv} disabled={exporting || loading} class="px-4 py-2 text-[13px] font-medium text-white bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 rounded-xl transition-all">{exporting ? 'Exporting...' : 'Export CSV'}</button>
    </div>
  </div>

  <section class="rounded-2xl border border-white/[0.07] bg-white/[0.03] p-4">
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

  <div class="grid gap-3 md:grid-cols-3">
    <div class="rounded-2xl border border-white/[0.07] bg-white/[0.03] p-5"><p class="text-xs text-white/35">Registered customers</p><p class="mt-2 text-2xl font-semibold">{total}</p></div>
    <div class="rounded-2xl border border-white/[0.07] bg-white/[0.03] p-5"><p class="text-xs text-white/35">Active aliases</p><p class="mt-2 text-2xl font-semibold">{activeCount}</p></div>
    <div class="rounded-2xl border border-white/[0.07] bg-white/[0.03] p-5"><p class="text-xs text-white/35">Linked accounts</p><p class="mt-2 text-2xl font-semibold">{accountCount}</p></div>
  </div>

  <section class="rounded-2xl border border-white/[0.07] bg-white/[0.03] overflow-hidden">
    <div class="grid grid-cols-[1fr_150px_120px_100px_130px] gap-4 border-b border-white/[0.06] px-5 py-3 text-[11px] uppercase tracking-wider text-white/25">
      <span>Alias</span><span>Customer ref</span><span>Accounts</span><span>Status</span><span>Enrolled</span>
    </div>
    {#if loading}
      <div class="p-8 text-center text-white/40">Loading...</div>
    {:else}
      {#each aliases as alias}
        <div class="grid grid-cols-[1fr_150px_120px_100px_130px] gap-4 border-b border-white/[0.04] px-5 py-3.5 text-sm">
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
  </section>
</div>
