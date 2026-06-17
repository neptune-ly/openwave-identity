<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { toast } from 'svelte-sonner';
  import AlertTriangle from 'lucide-svelte/icons/alert-triangle';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import CalendarDays from 'lucide-svelte/icons/calendar-days';
  import ClipboardList from 'lucide-svelte/icons/clipboard-list';
  import Copy from 'lucide-svelte/icons/copy';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import { getApi } from '$lib/api/client';

  let loading = $state(true);
  let event = $state(null);
  let warning = $state('');

  const auditId = $derived(page.params.id ? Number(page.params.id) : null);

  onMount(() => {
    loadEvent();
  });

  async function loadEvent() {
    if (!auditId) return;
    loading = true;
    warning = '';
    try {
      const response = await getApi().get(`/portal/audit-events/${auditId}`);
      event = response.data?.event || null;
    } catch (error) {
      event = null;
      warning = error?.response?.data?.message || error?.response?.data?.error || 'Could not load audit event';
      toast.error(warning);
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

  async function copySummary() {
    if (!event) return;
    const lines = [
      'Portal: OpenWave Identity Audit Event',
      `Event ID: ${event.id || '-'}`,
      `Action: ${event.action || '-'}`,
      `Entity: ${event.entity_type || '-'} / ${event.entity_id || '-'}`,
      `Actor: ${event.actor || '-'}`,
      `Created: ${fmt(event.created_at)}`
    ];
    const eventDetails = details(event.details);
    if (eventDetails) lines.push(`Details: ${eventDetails}`);
    await navigator.clipboard.writeText(lines.join('\n'));
    toast.success('Audit event summary copied');
  }
</script>

<svelte:head><title>Audit Event Desk - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-6xl mx-auto space-y-5">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
      <div class="max-w-3xl">
        <button onclick={() => goto('/portal/audit')} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white">
          <ArrowLeft class="w-4 h-4" />
          Back to audit ledger
        </button>
        <p class="mt-4 text-[11px] uppercase tracking-[0.18em] text-white/30">Audit Event Desk</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">{auditId ?? 'Audit Event'}</h1>
        <p class="identity-section-note mt-2 text-sm text-white/55">Dedicated support-safe event desk for one registry audit row. Keep the ledger page focused on filtering and discovery only.</p>
      </div>
      <div class="flex flex-wrap gap-2">
        <button onclick={copySummary} disabled={!event} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">
          <Copy class="w-4 h-4" />
          Copy summary
        </button>
        <button onclick={loadEvent} disabled={loading} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">
          <RefreshCw class={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>
    </div>
  </section>

  {#if warning}
    <section class="rounded-2xl border border-red-500/20 bg-red-500/[0.08] px-5 py-4 text-sm text-red-200">
      {warning}
    </section>
  {/if}

  {#if loading}
    <section class="identity-surface-card p-8 text-sm text-white/40">Loading audit event…</section>
  {:else if event}
    <section class="grid gap-3 md:grid-cols-4">
      <div class="identity-kpi-card p-5">
        <p class="text-xs text-white/35">Action</p>
        <p class="mt-2 text-sm font-semibold text-white">{event.action}</p>
      </div>
      <div class="identity-kpi-card p-5">
        <p class="text-xs text-white/35">Entity</p>
        <p class="mt-2 text-sm font-semibold text-white">{event.entity_type}</p>
      </div>
      <div class="identity-kpi-card p-5">
        <p class="text-xs text-white/35">Actor</p>
        <p class="mt-2 text-sm font-semibold text-white">{event.actor}</p>
      </div>
      <div class="identity-kpi-card p-5">
        <p class="text-xs text-white/35">Created</p>
        <p class="mt-2 text-sm font-semibold text-white">{fmt(event.created_at)}</p>
      </div>
    </section>

    <div class="grid gap-5 xl:grid-cols-[minmax(0,0.9fr)_minmax(320px,1.1fr)]">
      <section class="identity-surface-card p-5">
        <div class="flex items-center gap-2 text-sm font-semibold text-white">
          <ClipboardList class="w-4 h-4 text-indigo-300" />
          Event context
        </div>
        <div class="mt-4 grid gap-2">
          {#each [
            ['Event ID', event.id],
            ['Action', event.action],
            ['Entity type', event.entity_type],
            ['Entity id', event.entity_id],
            ['Actor', event.actor],
            ['Created', fmt(event.created_at)]
          ] as pair}
            <div class="rounded-xl border border-white/[0.08] bg-white/[0.03] px-4 py-3">
              <div class="text-[11px] uppercase tracking-[0.16em] text-white/28">{pair[0]}</div>
              <div class="mt-2 break-all text-sm text-white">{pair[1] || '-'}</div>
            </div>
          {/each}
        </div>
      </section>

      <section class="identity-surface-card p-5">
        <div class="flex items-center gap-2 text-sm font-semibold text-white">
          <CalendarDays class="w-4 h-4 text-indigo-300" />
          Support-safe detail
        </div>
        {#if details(event.details)}
          <pre class="mt-4 max-h-[32rem] overflow-auto rounded-xl border border-white/[0.07] bg-black/25 p-4 text-[12px] text-white/70">{details(event.details)}</pre>
        {:else}
          <div class="mt-4 rounded-xl border border-white/[0.08] bg-white/[0.03] px-4 py-10 text-center text-sm text-white/40">
            No structured detail was recorded for this event.
          </div>
        {/if}
      </section>
    </div>

    <section class="identity-surface-card p-5">
      <div class="flex items-start gap-3">
        <AlertTriangle class="mt-0.5 h-5 w-5 text-amber-300" />
        <div>
          <div class="text-sm font-semibold text-white">Audit posture</div>
          <div class="mt-1 text-[13px] text-white/45">This desk is for operator traceability only. It is not a raw customer-data dump and should remain support-safe.</div>
        </div>
      </div>
    </section>
  {:else}
    <section class="identity-surface-card p-10 text-center">
      <ClipboardList class="w-8 h-8 text-white/25 mx-auto mb-3" />
      <div class="font-semibold text-white">Audit event unavailable</div>
      <div class="text-sm text-white/35 mt-1">Return to the ledger and reopen the event once the service recovers.</div>
    </section>
  {/if}
</div>
