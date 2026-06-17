<script>
  import { onMount } from 'svelte';
  import { auth } from '$lib/stores/auth';
  import { apiCall, apiPublic } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import Search from 'lucide-svelte/icons/search';
  import Building2 from 'lucide-svelte/icons/building-2';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import Activity from 'lucide-svelte/icons/activity';
  import Route from 'lucide-svelte/icons/route';
  import Info from 'lucide-svelte/icons/info';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';
  import ClipboardList from 'lucide-svelte/icons/clipboard-list';
  import Settings from 'lucide-svelte/icons/settings';
  import Users from 'lucide-svelte/icons/users';

  let info = $state(null);
  let overview = $state(null);
  let banks = $state([]);
  let infoErr = $state('');
  let searchQ = $state('');
  let searchResult = $state(null);
  let searchAccounts = $state(null);
  let searchErr = $state('');
  let searchLoading = $state(false);
  let session = $state(null);
  let section = $state('overview');

  const summaryCards = $derived([
    session?.role === 'ADMIN'
      ? { label: 'Active banks', value: overview?.package?.registry?.active_banks ?? info?.registered_banks ?? '—', icon: Building2 }
      : { label: 'Registered banks', value: info?.registered_banks ?? '—', icon: Building2 },
    session?.role === 'ADMIN'
      ? { label: 'Active identities', value: overview?.package?.registry?.active_identities ?? info?.active_identities ?? '—', icon: ShieldCheck }
      : { label: 'Active identities', value: info?.active_identities ?? '—', icon: ShieldCheck },
    session?.role === 'ADMIN'
      ? { label: 'Pending approvals', value: overview?.package?.queues?.pending_bank_login_approvals ?? '—', icon: Activity }
      : { label: 'Spec version', value: info?.spec_version ?? '—', icon: Activity },
    session?.role === 'ADMIN'
      ? { label: 'Route gaps', value: overview?.package?.registry?.active_identities_missing_default_bank ?? '—', icon: Route }
      : { label: 'Uptime target', value: info?.uptime_sla ?? '—', icon: Route }
  ]);

  const bankPreview = $derived((banks || []).slice(0, 6));
  const workflowCards = $derived([
    {
      title: 'Identity operations',
      detail: 'Claim handles, link or unlink accounts, and manage default routing from the dedicated identity desk.',
      href: '/portal/identity',
      icon: Route,
      tone: 'text-indigo-300'
    },
    {
      title: 'Portal users',
      detail: 'Provision registry and bank-scoped operators, then open each user on its own access desk.',
      href: '/portal/users',
      icon: Users,
      tone: 'text-sky-300'
    },
    {
      title: 'Bank directory',
      detail: 'Review bank profile readiness and open each bank on its dedicated record page.',
      href: '/portal/banks',
      icon: Building2,
      tone: 'text-emerald-300'
    },
    {
      title: 'Reports',
      detail: 'Use support-safe bank and registry reporting without exposing raw customer data.',
      href: '/portal/reports',
      icon: ClipboardList,
      tone: 'text-amber-300'
    },
    {
      title: 'Registry corrections',
      detail: 'Reserve irreversible fixes and identity deletion for the controlled correction desk only.',
      href: '/portal/manage',
      icon: Settings,
      tone: 'text-rose-300'
    }
  ]);

  function readLookupQuery() {
    searchQ = get(page).url.searchParams.get('q') ?? '';
    section = get(page).url.searchParams.get('section') === 'lookup'
      ? 'lookup'
      : get(page).url.searchParams.get('section') === 'banks'
        ? 'banks'
        : 'overview';
  }

  async function syncLookupQuery() {
    const next = new URL(get(page).url);
    const trimmed = searchQ.trim();
    if (trimmed) next.searchParams.set('q', trimmed);
    else next.searchParams.delete('q');
    if (section === 'overview') next.searchParams.delete('section');
    else next.searchParams.set('section', section);
    await goto(`${next.pathname}${next.search}`, { replaceState: true, noScroll: true, keepFocus: true });
  }

  async function setSection(nextSection) {
    if (section === nextSection) return;
    section = nextSection;
    await syncLookupQuery();
  }

  onMount(async () => {
    session = get(auth);
    if (session?.role === 'CUSTOMER') {
      goto('/portal/customer');
      return;
    }
    if (session?.role === 'BANK') {
      goto('/portal/banks');
      return;
    }
    readLookupQuery();
    const [registryResponse, banksResponse] = await Promise.all([
      apiPublic('/registry/info'),
      apiPublic('/banks')
    ]);
    if (session?.role === 'ADMIN') {
      const overviewResponse = await apiCall('get', '/portal/overview');
      if (overviewResponse.ok) overview = overviewResponse.data;
    }
    if (registryResponse.ok) info = registryResponse.data;
    else infoErr = registryResponse.error || 'Could not load registry overview';
    if (banksResponse.ok) banks = banksResponse.data.banks || banksResponse.data || [];
    if (searchQ.trim()) await search(true);
  });

  async function search(skipQuerySync = false) {
    if (!searchQ.trim()) return;
    if (!skipQuerySync) await syncLookupQuery();
    searchErr = '';
    searchResult = null;
    searchAccounts = null;
    searchLoading = true;
    const q = searchQ.trim();

    const resolveResponse = await apiPublic(`/identity/resolve?alias=${encodeURIComponent(q)}`);
    if (resolveResponse.ok) {
      searchResult = { type: 'resolve', data: resolveResponse.data };
      searchLoading = false;
      return;
    }

    const identityResponse = await apiPublic(`/identity/${encodeURIComponent(q)}`);
    if (identityResponse.ok) {
      searchResult = { type: 'identity', data: identityResponse.data };
      const accountsResponse = await apiCall('get', `/identity/${encodeURIComponent(q)}/accounts`);
      if (accountsResponse.ok) searchAccounts = accountsResponse.data.accounts || accountsResponse.data || [];
      searchLoading = false;
      return;
    }

    searchErr = resolveResponse.status === 404 || identityResponse.status === 404
      ? 'No handle or routed alias matched that value.'
      : resolveResponse.error || identityResponse.error || 'Lookup failed';
    searchLoading = false;
  }

  function formatResolvedField(key) {
    return key.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').replace(/^\w/, (char) => char.toUpperCase());
  }

  function adminPackage() {
    return overview?.package ?? null;
  }

  function adminReadiness() {
    return adminPackage()?.readiness ?? { done: 0, total: 0, checks: [] };
  }

  function adminNextSteps() {
    return adminPackage()?.next_steps ?? [];
  }

  async function clearLookup() {
    searchQ = '';
    searchResult = null;
    searchAccounts = null;
    searchErr = '';
    await syncLookupQuery();
  }

  function deskSections() {
    return [
      {
        id: 'overview',
        title: 'Overview',
        detail: session?.role === 'ADMIN'
          ? 'Registry posture, readiness, and operator lanes.'
          : 'Registry posture and operator lanes.'
      },
      {
        id: 'lookup',
        title: 'Lookup',
        detail: 'Resolve aliases and inspect routed identity profiles.'
      },
      {
        id: 'banks',
        title: 'Banks',
        detail: `${bankPreview.length} bank preview row(s) loaded.`
      }
    ];
  }
</script>

<svelte:head><title>Dashboard — OpenWave Identity</title></svelte:head>

<div class="mx-auto max-w-7xl space-y-6 p-8">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
      <div class="max-w-3xl">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/35">Libya digital identity command</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold text-white">OpenWave Identity overview</h1>
        <p class="identity-section-note mt-3 max-w-2xl text-sm text-white/55">
          {session?.role === 'ADMIN'
            ? 'Review registry posture, queue pressure, and bank/customer readiness from one scoped operations overview before you move into dedicated desks.'
            : 'Review registry posture, inspect bank-vouched alias routing, and verify what public checkout, bank desks, or customer login flows will resolve before you touch identity records.'}
        </p>
        <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">Bank-vouched identity</span>
          <span class="identity-role-accent">Alias and route verification</span>
          <span class="identity-role-accent">Support-safe operator lookup</span>
        </div>
      </div>
      <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Registry posture</div>
          <div class="mt-2 text-sm font-medium text-white">{session?.role === 'ADMIN' ? `${overview?.package?.registry?.active_identities ?? info?.active_identities ?? '—'} active identities` : `${info?.active_identities ?? '—'} active identities`}</div>
          <div class="mt-1 text-[12px] text-white/40">{session?.role === 'ADMIN' ? `${overview?.package?.registry?.active_banks ?? info?.registered_banks ?? '—'} active bank participant(s)` : `${info?.registered_banks ?? '—'} registered bank participant(s)`}</div>
        </div>
        <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Routing rule</div>
          <div class="mt-2 text-sm font-medium text-white">Bare handles follow default bank routing.</div>
          <div class="mt-1 text-[12px] text-white/40">Use `handle@bank` when support, checkout, or approval flows need an explicit bank route.</div>
        </div>
        <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Primary job</div>
          <div class="mt-2 text-sm font-medium text-white">{section === 'overview' ? 'Choose an operator lane' : section === 'lookup' ? 'Resolve and inspect alias routing' : 'Preview banks, then open desk'}</div>
          <div class="mt-1 text-[12px] text-white/40">{info?.operator || 'OpenWave Identity'} · {info?.country_scope || 'National registry scope'}</div>
        </div>
      </div>
    </div>
  </section>

  {#if infoErr}
    <section class="rounded-2xl border border-red-500/20 bg-red-500/[0.08] px-5 py-4 text-sm text-red-200">
      {infoErr}
    </section>
  {/if}

  <div class="grid gap-6 xl:grid-cols-[260px_minmax(0,1fr)]">
    <aside class="identity-surface-card p-4">
      <div class="text-sm font-semibold text-white">Landing desk</div>
      <p class="mt-2 text-sm text-white/45">Keep discovery here. Open dedicated desks for actual changes, investigations, or approvals.</p>
      <div class="mt-4 space-y-2">
        {#each deskSections() as item}
          <button
            type="button"
            class={`w-full rounded-xl border px-3 py-3 text-left transition ${section === item.id ? 'border-white/[0.16] bg-white/[0.08]' : 'border-white/[0.08] bg-white/[0.03] hover:bg-white/[0.05]'}`}
            onclick={() => setSection(item.id)}
          >
            <div class="text-sm font-medium text-white">{item.title}</div>
            <div class="mt-1 text-xs text-white/45">{item.detail}</div>
          </button>
        {/each}
      </div>
      <div class="mt-4 rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3 text-sm text-white/45">
        Bare handles follow default-bank routing. Use `handle@bank` when a support or approval flow must remove ambiguity.
      </div>
    </aside>

    <div class="space-y-5">
      <section class="identity-surface-card p-5">
        <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
          <div>
            <div class="flex flex-wrap gap-2 text-[11px] uppercase tracking-[0.16em] text-white/30">
              <span>{section === 'overview' ? 'Overview' : section === 'lookup' ? 'Lookup' : 'Bank preview'}</span>
              <span class="identity-role-accent normal-case tracking-normal text-[11px]">
                {section === 'overview'
                  ? 'Choose an operator lane'
                  : section === 'lookup'
                    ? (searchQ.trim() ? 'Lookup state retained in URL' : 'Resolve alias routing')
                    : `${bankPreview.length} bank row(s) loaded`}
              </span>
            </div>
            <h2 class="mt-2 text-lg font-semibold text-white">
              {section === 'overview' ? 'Registry overview' : section === 'lookup' ? 'Resolve and inspect' : 'Connected banks'}
            </h2>
            <p class="mt-2 max-w-3xl text-sm text-white/45">
              {section === 'overview'
                ? 'Use the landing desk to judge registry posture, queue pressure, and where to route the operator next.'
                : section === 'lookup'
                  ? 'Search a bare handle, bank-qualified alias, or routed identity profile without leaving the landing desk.'
                  : 'Preview connected banks here, then open the dedicated bank desk for profile or readiness work.'}
            </p>
          </div>
          {#if section === 'banks'}
            <a href="/portal/banks" class="inline-flex items-center gap-1 text-[12px] text-indigo-200 transition-all hover:text-white">
              Open directory
              <ArrowRight class="h-3.5 w-3.5" />
            </a>
          {/if}
        </div>
      </section>

      {#if section === 'overview'}
        {#if session?.role === 'ADMIN' && adminPackage()}
          <section class="identity-surface-card p-6">
            <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
              <div>
                <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Registry operations package</p>
                <h2 class="mt-2 text-lg font-semibold text-white">Control-plane readiness and queue pressure</h2>
                <p class="mt-2 max-w-3xl text-sm text-white/45">
                  Customer-identity posture gaps, bank-readiness gaps, and pending login approvals that can block digital identity access.
                </p>
              </div>
              <div class="identity-role-accent">{adminReadiness().done}/{adminReadiness().total} checks ready</div>
            </div>
            <div class="mt-5 grid gap-3 md:grid-cols-3">
              <div class="identity-workspace-card p-5">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Portal users</div>
                <div class="mt-2 text-2xl font-semibold text-white">{adminPackage()?.portal_access?.active_portal_users ?? 0}</div>
                <div class="mt-1 text-[12px] text-white/40">Active of {adminPackage()?.portal_access?.total_portal_users ?? 0} total portal users</div>
              </div>
              <div class="identity-workspace-card p-5">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Customer access</div>
                <div class="mt-2 text-2xl font-semibold text-white">{adminPackage()?.portal_access?.active_customer_users ?? 0}</div>
                <div class="mt-1 text-[12px] text-white/40">Active customer portal users of {adminPackage()?.portal_access?.customer_users ?? 0}</div>
              </div>
              <div class="identity-workspace-card p-5">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Pending bank approvals</div>
                <div class="mt-2 text-2xl font-semibold text-white">{adminPackage()?.queues?.pending_bank_login_approvals ?? 0}</div>
                <div class="mt-1 text-[12px] text-white/40">Phone or national-ID sign-ins waiting on bank approval</div>
              </div>
            </div>
            <div class="mt-5 grid gap-3 xl:grid-cols-[minmax(0,1fr)_340px]">
              <div class="rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Readiness checks</div>
                <div class="mt-4 space-y-3">
                  {#each adminReadiness().checks as check}
                    <div class="flex items-start justify-between gap-3 rounded-2xl border border-white/[0.06] bg-white/[0.02] px-4 py-3">
                      <div>
                        <div class="text-sm font-medium text-white">{check.label}</div>
                        <div class="mt-1 text-[12px] text-white/45">{check.detail}</div>
                      </div>
                      <div class={`rounded-full px-2.5 py-1 text-[11px] font-medium ${check.done ? 'bg-emerald-500/10 text-emerald-300' : 'bg-amber-500/10 text-amber-300'}`}>
                        {check.done ? 'Ready' : 'Needs work'}
                      </div>
                    </div>
                  {/each}
                </div>
              </div>
              <div class="rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Next steps</div>
                <div class="mt-4 space-y-2">
                  {#each adminNextSteps() as step}
                    <div class="rounded-2xl border border-white/[0.06] bg-white/[0.02] px-4 py-3 text-sm text-white/70">{step}</div>
                  {/each}
                </div>
              </div>
            </div>
          </section>
        {/if}

        <section class="identity-surface-card p-6">
          <div class="flex flex-col gap-2 lg:flex-row lg:items-end lg:justify-between">
            <div>
              <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Operate by desk</p>
              <h2 class="mt-2 text-lg font-semibold text-white">Keep discovery here, manage changes on dedicated pages.</h2>
              <p class="mt-2 max-w-3xl text-sm text-white/45">
                Use the landing page to choose the right operator lane, then move into the dedicated bank, user, identity, report, or correction desk.
              </p>
            </div>
            <div class="identity-role-accent">Dedicated desks over overloaded dashboards</div>
          </div>
          <div class="mt-5 grid gap-3 lg:grid-cols-2 xl:grid-cols-3">
            {#each workflowCards as card}
              <a href={card.href} class="identity-workspace-card p-5 transition-all hover:bg-white/[0.045]">
                <div class="flex items-start justify-between gap-3">
                  <div class={`flex h-11 w-11 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.04] ${card.tone}`}>
                    <card.icon class="h-5 w-5" />
                  </div>
                  <ArrowRight class="mt-1 h-4 w-4 text-white/30" />
                </div>
                <div class="mt-4 text-sm font-semibold text-white">{card.title}</div>
                <div class="mt-2 text-[13px] leading-5 text-white/45">{card.detail}</div>
              </a>
            {/each}
          </div>
        </section>
      {:else if section === 'lookup'}
        <section class="identity-surface-card p-6">
          <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
        <div class="max-w-2xl">
          <div class="flex items-center gap-2">
            <h2 class="text-lg font-semibold text-white">Resolve and inspect</h2>
            <span class="inline-flex items-center gap-1 rounded-full border border-white/[0.08] bg-white/[0.04] px-2 py-1 text-[11px] text-white/45">
              <Info class="h-3.5 w-3.5" />
              Public routing check
            </span>
          </div>
          <p class="mt-2 text-sm text-white/45">
            Search an alias, a bank-qualified alias, or a raw identity handle. Public resolution returns routing facts only. Internal profile lookup can also show linked bank routes.
          </p>
          </div>
      <div class="identity-surface-soft px-4 py-3 text-[12px] text-white/50">
          `mtellesy` -> default bank<br />
          `mtellesy@andalus` -> explicit bank route
        </div>
      </div>

      <div class="mt-5 flex flex-col gap-3 lg:flex-row">
        <label class="relative flex-1">
          <Search class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-white/25" />
          <input
            bind:value={searchQ}
            onkeydown={(event) => event.key === 'Enter' && search()}
            placeholder="Enter NPT handle or handle@bank"
            class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.04] py-3 pl-10 pr-4 text-sm text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none"
          />
        </label>
        <button
          onclick={search}
          disabled={searchLoading || !searchQ.trim()}
          class="rounded-2xl bg-indigo-600 px-5 py-3 text-sm font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-40"
        >
          {searchLoading ? 'Checking...' : 'Run lookup'}
        </button>
        <button
          onclick={clearLookup}
          disabled={searchLoading || (!searchQ.trim() && !searchResult && !searchErr)}
          class="rounded-2xl border border-white/[0.1] px-5 py-3 text-sm font-medium text-white/65 transition-all hover:border-white/[0.18] hover:text-white disabled:opacity-30"
        >
          Clear
        </button>
      </div>

      {#if searchErr}
        <p class="mt-4 text-sm text-red-300">{searchErr}</p>
      {/if}

      {#if searchQ.trim()}
        <div class="mt-4 flex flex-wrap items-center gap-2 border-t border-white/[0.06] pt-4 text-[12px] text-white/45">
          <span class="identity-role-accent">Route-backed lookup</span>
          <span>Reloading this page keeps the current lookup query.</span>
        </div>
      {/if}

      <div class="mt-4 grid gap-2 md:grid-cols-3">
        <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3 text-sm text-white/60">
          Use bare handles only when the customer agreed on a default bank route.
        </div>
        <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3 text-sm text-white/60">
          Use bank-qualified aliases during investigation to eliminate ambiguity.
        </div>
        <div class="rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3 text-sm text-white/60">
          Keep this view support-safe. It is for routing and registry control, not full customer data recovery.
        </div>
      </div>

      {#if searchResult}
        <div class="identity-surface-soft mt-5 p-5">
          {#if searchResult.type === 'resolve'}
            <div class="text-[11px] uppercase tracking-[0.18em] text-indigo-300">Resolved route</div>
            <div class="mt-4 grid gap-4 md:grid-cols-2 xl:grid-cols-3">
              {#each Object.entries(searchResult.data) as [key, value]}
              <div class="identity-surface-soft px-4 py-3">
                  <div class="text-[11px] uppercase tracking-[0.16em] text-white/28">{formatResolvedField(key)}</div>
                  <div class="mt-2 break-words font-mono text-[13px] text-white">{value ?? '—'}</div>
                </div>
              {/each}
            </div>
          {:else}
            <div class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
              <div>
                <div class="text-[11px] uppercase tracking-[0.18em] text-indigo-300">Identity profile</div>
                <div class="mt-2 text-lg font-semibold text-white">{searchResult.data.customerDisplayName || searchResult.data.nptHandle}</div>
                <div class="mt-1 font-mono text-[12px] text-white/40">{searchResult.data.nptHandle}</div>
              </div>
              <div class="grid gap-2 text-[12px] text-white/50">
                <div>Default bank: <span class="text-white/75">{searchResult.data.defaultBankHandle || 'Not set'}</span></div>
                <div>Registered: <span class="text-white/75">{searchResult.data.registeredAt ? new Date(searchResult.data.registeredAt).toLocaleString() : '—'}</span></div>
              </div>
            </div>

            <div class="identity-surface-soft mt-5">
              <div class="grid grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)_120px] gap-4 border-b border-white/[0.06] px-4 py-3 text-[11px] uppercase tracking-[0.16em] text-white/28">
                <span>Bank route</span>
                <span>IBAN</span>
                <span>Status</span>
              </div>
              {#if searchAccounts?.length}
                <div class="divide-y divide-white/[0.05]">
                  {#each searchAccounts as account}
                    <div class="grid grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)_120px] gap-4 px-4 py-3">
                      <div class="min-w-0">
                        <div class="truncate text-sm font-medium text-white">{account.bankHandle || 'Unknown bank'}</div>
                        <div class="mt-1 truncate text-[12px] text-white/35">{account.displayName || account.bankCustomerRef || 'Linked account route'}</div>
                      </div>
                      <div class="truncate font-mono text-[13px] text-white/80">{account.ibanMasked || account.iban_masked || account.iban || '—'}</div>
                      <div>
                        <span class={`inline-flex rounded-full border px-2.5 py-1 text-[11px] ${
                          account.isDefault
                            ? 'border-emerald-400/20 bg-emerald-400/10 text-emerald-300'
                            : 'border-white/[0.08] bg-white/[0.04] text-white/45'
                        }`}>
                          {account.isDefault ? 'Default' : 'Linked'}
                        </span>
                      </div>
                    </div>
                  {/each}
                </div>
              {:else}
                <div class="px-4 py-5 text-sm text-white/40">No linked accounts were returned for this identity.</div>
              {/if}
            </div>
          {/if}
        </div>
      {/if}
        </section>
      {:else}
        <section class="identity-surface-card p-6">
          {#if bankPreview.length}
            <div class="space-y-3">
              {#each bankPreview as bank}
                <div class="identity-surface-soft px-4 py-3">
                  <div class="flex items-start justify-between gap-3">
                    <div class="min-w-0">
                      <div class="truncate text-sm font-medium text-white">{bank.displayName || bank.name || bank.bankHandle}</div>
                      <div class="mt-1 truncate font-mono text-[12px] text-white/35">{bank.bankHandle || 'bank'}</div>
                    </div>
                    <span class={`inline-flex rounded-full border px-2.5 py-1 text-[11px] ${
                      bank.active === false
                        ? 'border-red-400/20 bg-red-400/10 text-red-300'
                        : 'border-emerald-400/20 bg-emerald-400/10 text-emerald-300'
                    }`}>
                      {bank.active === false ? 'Inactive' : 'Active'}
                    </span>
                  </div>
                  <div class="mt-3 flex items-center justify-between gap-3 border-t border-white/[0.06] pt-3">
                    <div class="text-[12px] text-white/35">
                      {(bank.branding?.display_name || bank.displayName) ? 'Directory profile available' : 'Profile still needs directory details'}
                    </div>
                    <a href={`/portal/banks/${bank.bankHandle || bank.handle}`} class="inline-flex items-center gap-1 text-[12px] text-indigo-200 transition-all hover:text-white">
                      Open bank desk
                      <ArrowRight class="h-3.5 w-3.5" />
                    </a>
                  </div>
                </div>
              {/each}
            </div>
          {:else}
            <div class="text-sm text-white/40">{loading ? 'Loading banks...' : 'No registered banks found.'}</div>
          {/if}
        </section>
      {/if}
    </div>
  </div>
</div>
