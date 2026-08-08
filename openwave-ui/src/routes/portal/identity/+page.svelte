<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { auth } from '$lib/stores/auth';
  import { get } from 'svelte/store';
  import UserPlus from 'lucide-svelte/icons/user-plus';
  import Link2 from 'lucide-svelte/icons/link-2';
  import Unlink2 from 'lucide-svelte/icons/unlink-2';
  import Route from 'lucide-svelte/icons/route';
  import Building2 from 'lucide-svelte/icons/building-2';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';
  import ClipboardList from 'lucide-svelte/icons/clipboard-list';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import Info from 'lucide-svelte/icons/info';
  import PencilLine from 'lucide-svelte/icons/pencil-line';

  let session = $state(null);
  let loading = $state(false);

  const actionCards = [
    {
      key: 'claim',
      title: 'Claim handle',
      description: 'Create a customer identity and establish the first bank-backed account route.',
      icon: UserPlus,
      tone: 'text-indigo-300'
    },
    {
      key: 'rename',
      title: 'Rename NPT handle',
      description: 'Authenticate a customer-requested rename, preflight availability, and permanently retire the old payment address.',
      icon: PencilLine,
      tone: 'text-cyan-300'
    },
    {
      key: 'link',
      title: 'Link account',
      description: 'Attach another IBAN for the same customer identity within the current bank scope.',
      icon: Link2,
      tone: 'text-emerald-300'
    },
    {
      key: 'unlink',
      title: 'Unlink account',
      description: 'Remove an outdated or invalid route from the selected customer alias.',
      icon: Unlink2,
      tone: 'text-rose-300'
    },
    {
      key: 'default-account',
      title: 'Default IBAN',
      description: 'Choose which IBAN resolves when the payer selects a bank-specific alias.',
      icon: Route,
      tone: 'text-amber-300'
    },
    {
      key: 'default-bank',
      title: 'Default bank',
      description: 'Set which bank answers a bare NPT handle without an explicit bank suffix.',
      icon: Building2,
      tone: 'text-sky-300'
    }
  ];

  const isBank = $derived(session?.role === 'BANK');

  onMount(async () => {
    session = get(auth);
    const flow = appPage.url.searchParams.get('flow');
    if (flow && actionCards.some((item) => item.key === flow)) {
      const params = new URLSearchParams(appPage.url.searchParams);
      params.delete('flow');
      const query = params.toString();
      await goto(`/portal/identity/${flow}${query ? `?${query}` : ''}`, { replaceState: true });
    }
  });

  function hintClass() {
    return 'inline-flex h-4 w-4 cursor-help text-white/40';
  }
</script>

<svelte:head><title>Identity Operations - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-7xl mx-auto space-y-6">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
      <div class="max-w-3xl">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">{isBank ? 'Bank identity desk' : 'Registry identity desk'}</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight">Identity Operations</h1>
        <p class="identity-section-note mt-2 text-sm text-white/55">
          Keep this page focused on choosing the right workflow. Open a dedicated route desk for claim, customer-directed rename, linking, routing, or removal instead of stacking every operator action into one screen.
        </p>
        <div class="mt-3 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">
            Dedicated flow desks
            <span class="tooltip max-w-xs" data-tip="Each identity workflow now has its own route so operators can deep-link into one task at a time instead of sharing one overloaded page.">
              <Info class={hintClass()} />
            </span>
          </span>
          <span class="identity-role-accent">Bank-vouched routing</span>
          <span class="identity-role-accent">Preflight-first actions</span>
        </div>
      </div>
      <div class="flex flex-wrap gap-2">
        <a href="/portal/reports" class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white">
          <ClipboardList class="w-4 h-4" />
          Reports
        </a>
        <a href="/portal/identity/claim" class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white">
          <RefreshCw class="w-4 h-4" />
          Start claim
        </a>
      </div>
    </div>
    <div class="mt-4 grid gap-3 md:grid-cols-3">
      <div class="identity-surface-soft px-4 py-3">
        <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Operator scope</div>
        <div class="mt-2 text-lg font-semibold text-white">{isBank ? session?.bankHandle || 'Bank scope' : 'Global registry'}</div>
        <div class="mt-1 text-[12px] text-white/45">{isBank ? 'Write actions remain bank-scoped unless the flow explicitly says otherwise.' : 'Admin actions can correct routing across the full registry.'}</div>
      </div>
      <div class="identity-surface-soft px-4 py-3">
        <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Workflow count</div>
        <div class="mt-2 text-lg font-semibold text-white">{actionCards.length} focused desks</div>
        <div class="mt-1 text-[12px] text-white/45">Each route owns one identity change instead of combining every action on one page.</div>
      </div>
      <div class="identity-surface-soft px-4 py-3">
        <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Primary job</div>
        <div class="mt-2 text-lg font-semibold text-white">Choose one flow, then operate</div>
        <div class="mt-1 text-[12px] text-white/45">Discovery stays here; form work, preflight, and result context move to the dedicated flow page.</div>
      </div>
    </div>
  </section>

  <section class="identity-surface-card p-6">
    <div class="flex flex-col gap-2 lg:flex-row lg:items-end lg:justify-between">
      <div>
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Identity workflows</p>
        <h2 class="mt-2 text-lg font-semibold text-white">Open one focused desk at a time.</h2>
        <p class="mt-2 max-w-3xl text-sm text-white/45">
          Claims, permanent handle renames, route linking, unlinking, default-account changes, and default-bank changes have different risk. They should not compete for attention on one page.
        </p>
      </div>
      <div class="identity-role-accent">No mixed workflow page</div>
    </div>
    <div class="mt-5 grid gap-3 lg:grid-cols-2 xl:grid-cols-3">
      {#each actionCards as card}
        <a href={`/portal/identity/${card.key}`} class="identity-workspace-card p-5 transition-all hover:bg-white/[0.045]">
          <div class="flex items-start justify-between gap-3">
            <div class={`flex h-11 w-11 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.04] ${card.tone}`}>
              <card.icon class="h-5 w-5" />
            </div>
            <ArrowRight class="mt-1 h-4 w-4 text-white/30" />
          </div>
          <div class="mt-4 text-sm font-semibold text-white">{card.title}</div>
          <div class="mt-2 text-[13px] leading-5 text-white/45">{card.description}</div>
        </a>
      {/each}
    </div>
  </section>
</div>
