<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { apiCall } from '$lib/api/client';
  import { toast } from 'svelte-sonner';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import Copy from 'lucide-svelte/icons/copy';
  import Info from 'lucide-svelte/icons/info';

  let loading = $state(true);
  let approval = $state(null);
  let loadError = $state('');
  const challengeId = $derived(appPage.params.challengeId);

  onMount(loadApproval);

  async function loadApproval() {
    loading = true;
    loadError = '';
    try {
      const response = await apiCall('get', `/customer/login-approvals/${challengeId}`);
      if (!response.ok) {
        loadError = response.error || 'Could not load sign-in record.';
        return;
      }
      approval = response.data;
    } finally {
      loading = false;
    }
  }

  function backHref() {
    const query = appPage.url.searchParams.toString();
    return query ? `/portal/customer/login-approvals?${query}` : '/portal/customer/login-approvals';
  }

  function when(value) {
    return value ? new Date(value).toLocaleString() : '-';
  }

  function badgeClass(rowStatus) {
    if (rowStatus === 'APPROVED') return 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300';
    if (rowStatus === 'REJECTED') return 'border-rose-500/25 bg-rose-500/10 text-rose-300';
    if (rowStatus === 'EXPIRED') return 'border-amber-500/25 bg-amber-500/10 text-amber-300';
    return 'border-sky-500/25 bg-sky-500/10 text-sky-300';
  }

  async function copySummary() {
    if (!approval) return;
    const lines = [
      'Portal: OpenWave Identity Customer Sign-in Record',
      `Challenge: ${approval.challenge_id}`,
      `Status: ${approval.status}`,
      `Type: ${approval.identifier_type}`,
      `Alias: ${approval.requested_alias}`,
      `Identifier hint: ${approval.identifier_hint || '-'}`,
      `Default bank: ${approval.default_bank_handle || '-'}`,
      `Approved bank: ${approval.approved_bank_handle || '-'}`,
      `Created: ${when(approval.created_at)}`,
      `Expires: ${when(approval.expires_at)}`,
      `Actioned: ${when(approval.actioned_at)}`
    ];
    await navigator.clipboard.writeText(lines.join('\n'));
    toast.success('Sign-in record copied');
  }

  function hintClass() {
    return 'inline-flex h-4 w-4 cursor-help text-white/40';
  }
</script>

<svelte:head><title>Customer Sign-in Record - OpenWave Identity</title></svelte:head>

<div class="mx-auto max-w-5xl space-y-6 p-4 sm:p-8">
  <div class="flex flex-wrap items-center justify-between gap-3">
    <a href={backHref()} class="identity-shell-button inline-flex min-h-12 items-center gap-2 rounded-xl border px-4 text-[13px] font-medium transition-all hover:text-white">
      <ArrowLeft class="h-4 w-4" />
      Back to sign-in history
    </a>
    <div class="flex flex-wrap gap-2">
      <button onclick={loadApproval} disabled={loading} class="identity-shell-button inline-flex min-h-12 items-center gap-2 rounded-xl border px-4 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">
        <RefreshCw class={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
        Refresh
      </button>
      <button onclick={copySummary} disabled={loading || !approval} class="inline-flex min-h-12 items-center gap-2 rounded-xl border border-white/[0.1] px-4 text-[13px] font-medium text-white/70 transition-all hover:border-white/[0.18] hover:text-white disabled:opacity-40">
        <Copy class="h-4 w-4" />
        Copy summary
      </button>
    </div>
  </div>

  {#if loading}
    <section class="identity-surface-card p-8 text-center text-sm text-white/40" role="status" aria-live="polite" aria-busy="true">Loading sign-in record...</section>
  {:else if loadError}
    <section class="identity-surface-card p-6" role="alert">
      <h1 class="text-lg font-semibold text-white">Sign-in record unavailable</h1>
      <p class="mt-2 text-sm text-white/55">{loadError}</p>
      <button type="button" onclick={loadApproval} class="identity-shell-button mt-4 min-h-12 rounded-xl border px-4 text-[13px] font-semibold">Retry</button>
    </section>
  {:else if approval}
    <section class="identity-expressive-band p-6">
      <div class="max-w-3xl">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Customer sign-in record</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">{approval.requested_alias}</h1>
        <p class="identity-section-note mt-2 text-sm text-white/55">This desk shows one phone- or national-ID-based portal sign-in challenge and which linked bank route ultimately approved or rejected it.</p>
        <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">
            Owner-visible trust trail
            <span class="tooltip max-w-xs" data-tip="Customers can inspect their own sign-in outcomes and linked-bank approval context here. This is intentionally more complete than support-safe operator queues.">
              <Info class={hintClass()} aria-hidden="true" />
            </span>
          </span>
        </div>
      </div>
    </section>

    <div class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
      <section class="identity-surface-card p-5">
        <div class="text-sm font-semibold text-white">Sign-in facts</div>
        <div class="mt-4 grid gap-2">
          {#each [
            ['Status', approval.status],
            ['Identifier type', approval.identifier_type],
            ['Alias', approval.requested_alias],
            ['Identifier hint', approval.identifier_hint || '-'],
            ['Default bank', approval.default_bank_handle || '-'],
            ['Approved bank', approval.approved_bank_handle || '-'],
            ['Created', when(approval.created_at)],
            ['Expires', when(approval.expires_at)],
            ['Actioned', when(approval.actioned_at)]
          ] as pair}
            <div class="rounded-xl border border-white/[0.08] bg-white/[0.03] px-4 py-3">
              <div class="text-[11px] uppercase tracking-[0.16em] text-white/28">{pair[0]}</div>
              <div class="mt-2 break-all text-sm text-white">{pair[1]}</div>
            </div>
          {/each}
        </div>
      </section>

      <aside class="space-y-5">
        <section class="identity-surface-card p-5">
          <div class="text-sm font-semibold text-white">Challenge posture</div>
          <div class="mt-4 flex flex-wrap gap-2">
            <span class={`inline-flex rounded-full border px-2.5 py-1 text-[11px] font-medium ${badgeClass(approval.status)}`}>
              {approval.status}
            </span>
            <span class="inline-flex rounded-full border border-white/[0.08] bg-white/[0.04] px-2.5 py-1 text-[11px] text-white/45">
              {approval.identifier_type}
            </span>
          </div>
          <div class="mt-4 text-[12px] leading-5 text-white/45">
            If sign-in started from a public identifier, access was released only after the linked-bank approval step completed.
          </div>
        </section>

        <section class="identity-surface-card p-5">
          <div class="text-sm font-semibold text-white">Bank options at that time</div>
          <div class="mt-4 space-y-2">
            {#each approval.bank_options ?? [] as option}
              <div class="rounded-xl border border-white/[0.08] bg-white/[0.03] px-4 py-3">
                <div class="flex items-center justify-between gap-3">
                  <div class="font-mono text-sm text-white">{option.alias}</div>
                  <div class="flex gap-2">
                    {#if option.is_default}
                      <span class="rounded-full border border-white/[0.08] px-2 py-0.5 text-[10px] text-white/45">Default</span>
                    {/if}
                    {#if option.approved}
                      <span class="rounded-full border border-emerald-500/20 bg-emerald-500/10 px-2 py-0.5 text-[10px] text-emerald-200">Approved bank</span>
                    {/if}
                  </div>
                </div>
                <div class="mt-1 text-[12px] text-white/45">{option.bank_handle}</div>
              </div>
            {/each}
          </div>
        </section>
      </aside>
    </div>
  {/if}
</div>
