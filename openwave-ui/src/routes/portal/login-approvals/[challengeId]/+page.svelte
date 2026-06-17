<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { apiCall } from '$lib/api/client';
  import { toast } from 'svelte-sonner';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import Copy from 'lucide-svelte/icons/copy';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import CircleX from 'lucide-svelte/icons/circle-x';
  import Info from 'lucide-svelte/icons/info';

  let loading = $state(true);
  let acting = $state(false);
  let approval = $state(null);

  const challengeId = $derived(appPage.params.challengeId);

  onMount(loadApproval);

  async function loadApproval() {
    loading = true;
    try {
      const response = await apiCall('get', `/identity/login-approvals/${challengeId}`);
      if (!response.ok) {
        toast.error(response.error || 'Could not load approval desk');
        await goto('/portal/login-approvals');
        return;
      }
      approval = response.data;
    } finally {
      loading = false;
    }
  }

  function backHref() {
    const query = appPage.url.searchParams.toString();
    return query ? `/portal/login-approvals?${query}` : '/portal/login-approvals';
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
      'Portal: OpenWave Identity Login Approval Desk',
      `Challenge: ${approval.challenge_id}`,
      `Status: ${approval.status}`,
      `Type: ${approval.identifier_type}`,
      `Alias: ${approval.requested_alias}`,
      `Default bank: ${approval.default_bank_handle || '-'}`,
      `Customer ref: ${approval.bank_customer_ref || '-'}`,
      `Identifier hint: ${approval.identifier_hint || '-'}`,
      `Created: ${when(approval.created_at)}`,
      `Expires: ${when(approval.expires_at)}`,
      `Actioned: ${when(approval.actioned_at)}`
    ];
    await navigator.clipboard.writeText(lines.join('\n'));
    toast.success('Approval summary copied');
  }

  async function act(action) {
    if (!approval?.bank_customer_ref) {
      toast.error('This approval row is missing a bank customer reference.');
      return;
    }
    acting = true;
    const response = await apiCall('post', `/identity/login-approvals/${approval.challenge_id}/${action}`, {
      customerRef: approval.bank_customer_ref
    });
    acting = false;
    if (!response.ok) {
      toast.error(response.error || `Could not ${action} approval`);
      return;
    }
    toast.success(action === 'approve' ? 'Login approval confirmed' : 'Login approval rejected');
    await loadApproval();
  }

  function hintClass() {
    return 'inline-flex h-4 w-4 cursor-help text-white/40';
  }
</script>

<svelte:head><title>Login Approval Desk - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-5xl mx-auto space-y-6">
  <div class="flex flex-wrap items-center justify-between gap-3">
    <a href={backHref()} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white">
      <ArrowLeft class="h-4 w-4" />
      Back to approvals
    </a>
    <div class="flex flex-wrap gap-2">
      <button onclick={loadApproval} disabled={loading || acting} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">
        <RefreshCw class={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
        Refresh
      </button>
      <button onclick={copySummary} disabled={loading || !approval} class="inline-flex items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2 text-[13px] font-medium text-white/70 transition-all hover:border-white/[0.18] hover:text-white disabled:opacity-40">
        <Copy class="h-4 w-4" />
        Copy summary
      </button>
    </div>
  </div>

  {#if loading}
    <section class="identity-surface-card p-8 text-center text-sm text-white/40">Loading approval desk...</section>
  {:else if approval}
    <section class="identity-expressive-band p-6">
      <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
        <div class="max-w-3xl">
          <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Bank-vouched login approval desk</p>
          <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">{approval.requested_alias}</h1>
          <p class="identity-section-note mt-2 text-sm text-white/55">
            Use this dedicated desk to inspect one approval challenge, verify the masked public identifier context, and take a bank-scoped approve or reject action.
          </p>
          <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
            <span class="identity-role-accent">Dedicated approval review</span>
            <span class="identity-role-accent">Bank-scoped action lane</span>
            <span class="identity-role-accent">Support-safe customer hint</span>
          </div>
        </div>
        <div class="grid gap-3 sm:grid-cols-2">
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Approval rule</div>
            <div class="mt-2 text-sm font-medium text-white">Only approve after customer verification.</div>
            <div class="mt-1 text-[12px] text-white/45">Public-identifier sign-in should not issue access until a linked bank confirms it.</div>
          </div>
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Data policy</div>
            <div class="mt-2 text-sm font-medium text-white">Support-safe queue data only.</div>
            <div class="mt-1 text-[12px] text-white/45">This desk uses masked identifier hints and bank customer references, not raw public identifiers.</div>
          </div>
        </div>
      </div>
    </section>

    <div class="grid gap-5 xl:grid-cols-[minmax(0,1fr)_340px]">
      <section class="identity-surface-card p-5">
        <div class="flex items-center gap-2">
          <div class="text-sm font-semibold text-white">Approval facts</div>
          <span class="tooltip max-w-xs" data-tip="Use these fields to verify that the challenge belongs to the correct bank customer and alias before actioning it.">
            <Info class={hintClass()} />
          </span>
        </div>
        <div class="mt-4 grid gap-2">
          {#each [
            ['Status', approval.status],
            ['Identifier type', approval.identifier_type],
            ['Alias', approval.requested_alias],
            ['Default bank', approval.default_bank_handle || '-'],
            ['Customer ref', approval.bank_customer_ref || '-'],
            ['Identifier hint', approval.identifier_hint || '-'],
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
            Approve only after confirming the customer through the bank context. Reject anything that cannot be confidently matched to the bank customer reference shown here.
          </div>
        </section>

        <section class="identity-surface-card p-5">
          <div class="text-sm font-semibold text-white">Available actions</div>
          <div class="mt-4 flex flex-col gap-3">
            <button
              onclick={() => act('approve')}
              disabled={acting || approval.status !== 'PENDING'}
              class="inline-flex items-center justify-center gap-2 rounded-xl border border-emerald-500/25 bg-emerald-500/10 px-4 py-2.5 text-[13px] font-semibold text-emerald-200 transition-all hover:border-emerald-400/40 hover:text-white disabled:opacity-35"
            >
              <ShieldCheck class="h-4 w-4" />
              {acting ? 'Processing...' : 'Approve sign-in'}
            </button>
            <button
              onclick={() => act('reject')}
              disabled={acting || approval.status !== 'PENDING'}
              class="inline-flex items-center justify-center gap-2 rounded-xl border border-rose-500/25 bg-rose-500/10 px-4 py-2.5 text-[13px] font-semibold text-rose-200 transition-all hover:border-rose-400/40 hover:text-white disabled:opacity-35"
            >
              <CircleX class="h-4 w-4" />
              {acting ? 'Processing...' : 'Reject sign-in'}
            </button>
          </div>
          {#if approval.status !== 'PENDING'}
            <div class="mt-4 rounded-xl border border-white/[0.08] bg-black/15 px-4 py-3 text-[12px] text-white/45">
              This challenge is already {approval.status.toLowerCase()} and no longer needs a new bank action.
            </div>
          {/if}
        </section>
      </aside>
    </div>
  {/if}
</div>
