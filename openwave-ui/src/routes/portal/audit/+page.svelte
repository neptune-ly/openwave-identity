<script>
  import { onMount } from 'svelte';
  import { toast } from 'svelte-sonner';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import ListFilter from 'lucide-svelte/icons/list-filter';
  import ClipboardList from 'lucide-svelte/icons/clipboard-list';
  import { getApi } from '$lib/api/client';

  let loading = $state(true);
  let events = $state([]);
  let entityType = $state('');
  let entityId = $state('');
  let limit = $state(100);

  onMount(loadEvents);

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
    } catch (error) {
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
</script>

<svelte:head><title>Audit Events - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-7xl mx-auto">
  <div class="flex flex-col lg:flex-row lg:items-start lg:justify-between gap-4 mb-8">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">Audit Events</h1>
      <p class="text-white/40 text-sm mt-1">Review registry portal actions for bank onboarding, profile updates, credential changes, and branding updates.</p>
    </div>
    <button onclick={loadEvents} disabled={loading} class="px-4 py-2 rounded-xl border border-white/[0.09] bg-white/[0.035] hover:bg-white/[0.06] disabled:opacity-40 text-[13px] text-white/70 flex items-center gap-2">
      <RefreshCw class="w-3.5 h-3.5" />
      Refresh
    </button>
  </div>

  <section class="rounded-2xl border border-white/[0.07] bg-white/[0.025] p-5 mb-6">
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
      <button onclick={loadEvents} class="px-4 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-[13px] font-semibold">Apply</button>
    </div>
  </section>

  {#if loading}
    <div class="rounded-2xl border border-white/[0.07] bg-white/[0.025] p-8 text-sm text-white/40">Loading audit events...</div>
  {:else if events.length === 0}
    <div class="rounded-2xl border border-white/[0.07] bg-white/[0.025] p-10 text-center">
      <ClipboardList class="w-8 h-8 text-white/25 mx-auto mb-3" />
      <div class="font-semibold text-white">No audit events found</div>
      <div class="text-sm text-white/35 mt-1">Try a wider filter or refresh after a portal action.</div>
    </div>
  {:else}
    <div class="rounded-2xl border border-white/[0.07] bg-white/[0.025] overflow-hidden">
      <div class="overflow-x-auto">
        <table class="w-full text-left">
          <thead class="bg-white/[0.035] text-[11px] uppercase tracking-[0.16em] text-white/30">
            <tr>
              <th class="px-4 py-3 font-medium">Time</th>
              <th class="px-4 py-3 font-medium">Action</th>
              <th class="px-4 py-3 font-medium">Entity</th>
              <th class="px-4 py-3 font-medium">Actor</th>
              <th class="px-4 py-3 font-medium">Details</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-white/[0.06]">
            {#each events as event}
              <tr class="align-top">
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
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    </div>
  {/if}
</div>
