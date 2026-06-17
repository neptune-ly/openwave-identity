<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { toast } from 'svelte-sonner';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import ListFilter from 'lucide-svelte/icons/list-filter';
  import ClipboardList from 'lucide-svelte/icons/clipboard-list';
  import Info from 'lucide-svelte/icons/info';
  import Copy from 'lucide-svelte/icons/copy';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';
  import { getApi } from '$lib/api/client';

  let loading = $state(true);
  let events = $state([]);
  let entityType = $state('');
  let entityId = $state('');
  let limit = $state(100);

  onMount(() => {
    hydrateFromQuery();
    loadEvents();
  });

  function hydrateFromQuery() {
    entityType = appPage.url.searchParams.get('entity_type') ?? '';
    entityId = appPage.url.searchParams.get('entity_id') ?? '';
    const nextLimit = Number(appPage.url.searchParams.get('limit') ?? '100');
    limit = Number.isFinite(nextLimit) && nextLimit > 0 ? nextLimit : 100;
  }

  async function syncQuery() {
    const params = new URLSearchParams();
    if (entityType.trim()) params.set('entity_type', entityType.trim().toUpperCase());
    if (entityId.trim()) params.set('entity_id', entityId.trim());
    params.set('limit', String(limit));
    const query = params.toString();
    await goto(query ? `${appPage.url.pathname}?${query}` : appPage.url.pathname, {
      replaceState: true,
      noScroll: true,
      keepFocus: true
    });
  }

  async function loadEvents() {
    loading = true;
    try {
      const params = { limit };
      if (entityType.trim() && entityId.trim()) {
        params.entity_type = entityType.trim().toUpperCase();
        params.entity_id = entityId.trim();
      }
      const response = await getApi().get('/portal/audit-events', { params });
      events = response.data?.events || [];
      const legacyItem = appPage.url.searchParams.get('item');
      if (legacyItem) {
        const matched = events.find((event) =>
          [event.created_at, event.action, event.entity_type, event.entity_id, event.actor].map((value) => value ?? '').join('|') === legacyItem
        );
        if (matched?.id) {
          await goto(`/portal/audit/${matched.id}`, { replaceState: true });
          return;
        }
      }
    } catch (error) {
      events = [];
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not load audit events');
    } finally {
      loading = false;
    }
  }

  function fmt(value) {
    return value ? new Date(value).toLocaleString() : 'Unknown';
  }

  function details(value) {
    if (!value) return null;
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }

  function hintClass() {
    return 'inline-flex h-4 w-4 cursor-help text-white/40';
  }

  async function applyFilters() {
    await syncQuery();
    await loadEvents();
  }

  async function copyLedgerSummary() {
    const lines = [
      'Portal: OpenWave Identity Audit Ledger',
      `Loaded events: ${events.length}`,
      `Entity type filter: ${entityType || 'All'}`,
      `Entity id filter: ${entityId || 'All'}`,
      `Limit: ${limit}`
    ];
    await navigator.clipboard.writeText(lines.join('\n'));
    toast.success('Audit ledger summary copied');
  }
</script>

<svelte:head><title>Audit Events - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-7xl mx-auto space-y-5">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
      <div class="max-w-3xl">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Registry Audit Ledger</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">Audit Events</h1>
        <p class="identity-section-note mt-2 text-sm text-white/55">Keep this page focused on audit discovery and filtering. Open any event on its own desk to inspect the support-safe payload and operator trace.</p>
        <div class="mt-3 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">Support-safe audit trail</span>
          <span class="identity-role-accent">Dedicated audit event desks</span>
        </div>
        <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="inline-flex items-center gap-1 rounded-full border border-white/[0.08] px-2.5 py-1">
            Scoped lookup
            <span class="tooltip max-w-xs" data-tip="Filter by entity type and entity id when you need the action history for one bank record or another managed registry object.">
              <Info class={hintClass()} />
            </span>
          </span>
          <span class="inline-flex items-center gap-1 rounded-full border border-white/[0.08] px-2.5 py-1">
            Dedicated event desk
            <span class="tooltip max-w-xs" data-tip="Use the per-event route to inspect the full support-safe detail without keeping a selected detail pane attached to the ledger.">
              <Info class={hintClass()} />
            </span>
          </span>
        </div>
      </div>
      <div class="flex flex-wrap gap-2">
        <button onclick={copyLedgerSummary} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white">
          <Copy class="w-4 h-4" />
          Copy summary
        </button>
        <button onclick={loadEvents} disabled={loading} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">
          <RefreshCw class={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>
    </div>
  </section>

  <div class="grid gap-3 md:grid-cols-3">
    <div class="identity-kpi-card p-5">
      <p class="text-xs text-white/35">Loaded events</p>
      <p class="mt-2 text-2xl font-semibold text-white">{events.length}</p>
    </div>
    <div class="identity-kpi-card p-5">
      <p class="text-xs text-white/35">Entity filter</p>
      <p class="mt-2 text-sm font-semibold text-white">{entityType || 'All entities'}</p>
    </div>
    <div class="identity-kpi-card p-5">
      <p class="text-xs text-white/35">Row limit</p>
      <p class="mt-2 text-2xl font-semibold text-white">{limit}</p>
    </div>
  </div>

  <section class="identity-surface-card p-5">
    <div class="flex items-center gap-2 mb-4 text-sm font-semibold text-white">
      <ListFilter class="w-4 h-4 text-indigo-300" />
      Filters
    </div>
    <div class="grid grid-cols-1 md:grid-cols-[180px_1fr_140px_auto] gap-3">
      <select bind:value={entityType} class="bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white focus:outline-none focus:border-indigo-500/50">
        <option value="">All entity types</option>
        <option value="BANK">Bank</option>
      </select>
      <input bind:value={entityId} placeholder="Entity ID, required when filtering by type" class="bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:outline-none focus:border-indigo-500/50" />
      <input bind:value={limit} type="number" min="1" max="500" class="bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white focus:outline-none focus:border-indigo-500/50" />
      <button onclick={applyFilters} class="px-4 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-[13px] font-semibold">Apply</button>
    </div>
  </section>

  {#if loading}
    <div class="identity-surface-card p-8 text-sm text-white/40">Loading audit events...</div>
  {:else if events.length === 0}
    <div class="identity-surface-card p-10 text-center">
      <ClipboardList class="w-8 h-8 text-white/25 mx-auto mb-3" />
      <div class="font-semibold text-white">No audit events found</div>
      <div class="text-sm text-white/35 mt-1">Try a wider filter or refresh after a portal action.</div>
    </div>
  {:else}
    <div class="identity-surface-card overflow-hidden">
      <div class="border-b border-white/[0.06] px-5 py-4">
        <div class="flex items-center justify-between gap-3">
          <div>
            <h2 class="text-lg font-semibold text-white">Audit ledger</h2>
            <p class="mt-1 text-sm text-white/45">Recent registry actions with support-safe details and actor traceability.</p>
          </div>
          <span class="identity-role-accent">Open an event for full detail</span>
        </div>
      </div>
      <div class="overflow-x-auto">
        <table class="w-full min-w-[980px] text-left">
          <thead class="bg-white/[0.035] text-[11px] uppercase tracking-[0.16em] text-white/30">
            <tr>
              <th class="px-4 py-3 font-medium">Time</th>
              <th class="px-4 py-3 font-medium">Action</th>
              <th class="px-4 py-3 font-medium">Entity</th>
              <th class="px-4 py-3 font-medium">Actor</th>
              <th class="px-4 py-3 font-medium">Details</th>
              <th class="px-4 py-3 font-medium text-right">Desk</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-white/[0.06]">
            {#each events as event}
              <tr class="align-top transition-colors hover:bg-white/[0.02]">
                <td class="px-4 py-3 whitespace-nowrap text-[12px] text-white/45">{fmt(event.created_at)}</td>
                <td class="px-4 py-3">
                  <span class="px-2 py-1 rounded-full border border-indigo-400/20 bg-indigo-400/10 text-[11px] text-indigo-200">{event.action}</span>
                </td>
                <td class="px-4 py-3">
                  <div class="font-mono text-[12px] text-white">{event.entity_type}</div>
                  <div class="font-mono text-[12px] text-white/35">{event.entity_id}</div>
                </td>
                <td class="px-4 py-3 text-[13px] text-white/70">{event.actor}</td>
                <td class="px-4 py-3 min-w-[280px]">
                  {#if details(event.details)}
                    <pre class="text-[12px] text-white/70 bg-black/25 border border-white/[0.07] rounded-xl p-3 max-h-28 overflow-auto">{details(event.details)}</pre>
                  {:else}
                    <span class="text-[12px] text-white/25">No details</span>
                  {/if}
                </td>
                <td class="px-4 py-3 text-right">
                  <a href={`/portal/audit/${event.id}`} class="inline-flex items-center gap-1 rounded-xl border border-white/[0.08] px-3 py-2 text-[12px] text-white/65 transition-all hover:text-white">
                    Open
                    <ArrowRight class="h-3.5 w-3.5" />
                  </a>
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    </div>
  {/if}
</div>
