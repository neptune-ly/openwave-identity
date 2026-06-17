<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { getApi } from '$lib/api/client';
  import { toast } from 'svelte-sonner';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import UserRound from 'lucide-svelte/icons/user-round';
  import Building2 from 'lucide-svelte/icons/building-2';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import Landmark from 'lucide-svelte/icons/landmark';
  import Info from 'lucide-svelte/icons/info';

  let loading = $state(true);
  let profile = $state(null);
  let selectedAccount = $state(null);
  let loginApprovals = $state([]);
  let loginApprovalSummary = $state({ total: 0, pending: 0, approved: 0, rejected: 0, expired: 0 });
  const currentSection = $derived(readSection());
  const sectionMeta = {
    access: {
      label: 'Access',
      purpose: 'Review direct sign-in posture, strong factors, and public-identifier access rules.',
      action: 'Review security and recovery'
    },
    approvals: {
      label: 'Approvals',
      purpose: 'Inspect public-identifier sign-in requests that still depend on linked-bank approval.',
      action: 'Review sign-in approvals'
    },
    routing: {
      label: 'Routes',
      purpose: 'Review how your bare handle resolves, inspect qualified routes, and inspect linked bank-account mappings.',
      action: 'Inspect linked routes'
    },
    profile: {
      label: 'Profile',
      purpose: 'Review the owner-facing identity record, continuity identifiers, and portal-login posture.',
      action: 'Review profile details'
    }
  };
  const sectionItems = [
    { key: 'access', label: 'Access', action: 'Security and recovery', hint: 'Sign-in posture and strong factors' },
    { key: 'approvals', label: 'Approvals', action: 'Bank approval history', hint: 'Phone and national-ID sign-in release' },
    { key: 'routing', label: 'Routes', action: 'Linked routes and accounts', hint: 'Bare handle, qualified aliases, and account detail' },
    { key: 'profile', label: 'Profile', action: 'Owner identity record', hint: 'Continuity identifiers and portal profile' }
  ];

  onMount(() => {
    loadCustomer();
  });

  function accountKey(account) {
    return [account?.alias || '', account?.bank_handle || '', account?.iban || account?.iban_masked || ''].join('|');
  }

  async function syncQuery(item = selectedAccount ? accountKey(selectedAccount) : '') {
    const params = new URLSearchParams(appPage.url.searchParams);
    const section = readSection();
    if (section === 'access') params.delete('section');
    else params.set('section', section);
    if (item) params.set('item', item);
    else params.delete('item');
    const query = params.toString();
    await goto(query ? `${appPage.url.pathname}?${query}` : appPage.url.pathname, {
      replaceState: true,
      noScroll: true,
      keepFocus: true
    });
  }

  function syncSelectionFromQuery(accounts) {
    const requested = appPage.url.searchParams.get('item');
    if (!requested || !accounts.length) return null;
    return accounts.find((account) => accountKey(account) === requested) || null;
  }

  async function loadCustomer() {
    loading = true;
    try {
      const api = getApi();
      const [profileResponse, approvalsResponse] = await Promise.all([
        api.get('/customer/aliases'),
        api.get('/customer/login-approvals?limit=6')
      ]);
      profile = profileResponse.data;
      loginApprovals = approvalsResponse.data?.items ?? [];
      loginApprovalSummary = approvalsResponse.data?.summary ?? loginApprovalSummary;
      const accounts = profileResponse.data?.accounts ?? [];
      selectedAccount = syncSelectionFromQuery(accounts) ?? accounts.find((account) => account.default) ?? accounts[0] ?? null;
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not load customer accounts');
      profile = null;
      selectedAccount = null;
      loginApprovals = [];
    } finally {
      loading = false;
    }
  }

  function accountStatus(account) {
    if (!account) return 'Inactive';
    return account.default ? 'Default route' : 'Linked';
  }

  function accounts() {
    return profile?.accounts ?? [];
  }

  function identityPackage() {
    return profile?.package ?? null;
  }

  function accessPackage() {
    return identityPackage()?.access ?? {};
  }

  function securityPackage() {
    return identityPackage()?.security ?? {};
  }

  function routingPackage() {
    return identityPackage()?.routing ?? {};
  }

  function readinessPackage() {
    return identityPackage()?.readiness ?? { done: 0, total: 0, checks: [] };
  }

  function nextSteps() {
    return identityPackage()?.next_steps ?? [];
  }

  function linkedBankCount() {
    return new Set(accounts().map((account) => account.bank_handle)).size;
  }

  function qualifiedAliases() {
    return accounts().map((account) => ({
      alias: account.alias,
      bank: account.bank_display_name || account.bank_handle,
      bankHandle: account.bank_handle,
      iban: account.iban || account.iban_masked,
      accountNumber: account.account_number || account.account_number_masked || null,
      customerRef: account.bank_customer_ref || null,
      default: account.default
    }));
  }

  function bareHandleRoute() {
    return routingPackage()?.bare_handle_route
      ? routingPackage().bare_handle_route
      : 'Default bank is not assigned yet';
  }

  function fullIban(account) {
    return account?.iban || account?.iban_masked || 'Not set';
  }

  function fullAccountNumber(account) {
    return account?.account_number || account?.account_number_masked || 'Not stored';
  }

  function selectAccount(account) {
    selectedAccount = account;
    void syncQuery(accountKey(account));
  }

  function approvalTone(status) {
    if (status === 'APPROVED') return 'border-emerald-500/20 bg-emerald-500/10 text-emerald-200';
    if (status === 'REJECTED') return 'border-rose-500/20 bg-rose-500/10 text-rose-200';
    if (status === 'EXPIRED') return 'border-amber-500/20 bg-amber-500/10 text-amber-200';
    return 'border-sky-500/20 bg-sky-500/10 text-sky-200';
  }

  function fmt(value) {
    return value ? new Date(value).toLocaleString() : '—';
  }

  function readSection() {
    const section = appPage.url.searchParams.get('section');
    return ['access', 'approvals', 'routing', 'profile'].includes(section) ? section : 'access';
  }

  function sectionHref(section) {
    const params = new URLSearchParams(appPage.url.searchParams);
    if (section === 'access') params.delete('section');
    else params.set('section', section);
    const query = params.toString();
    return query ? `${appPage.url.pathname}?${query}` : appPage.url.pathname;
  }
</script>

<svelte:head><title>My Accounts - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-7xl mx-auto space-y-6">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
      <div class="max-w-3xl">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Customer identity desk</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight">My Accounts</h1>
        <p class="identity-section-note mt-2 text-sm text-white/55">Review your identity access, linked bank routes, and default resolution path from one owner-facing desk. Your own route data stays visible here instead of being support-masked.</p>
        <div class="mt-3 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">
            Full self view
            <span class="tooltip max-w-xs" data-tip="Customers should see their own registered identity and route data in full, while support-safe masking still applies to operator and merchant reports.">
              <Info class="inline-flex h-4 w-4 cursor-help text-white/40" />
            </span>
          </span>
          <span class="identity-role-accent">
            Bank-vouched routes
            <span class="tooltip max-w-xs" data-tip="Each linked route comes from a bank-vouched enrollment or update path. This page explains the result but does not replace bank-controlled route ownership.">
              <Info class="inline-flex h-4 w-4 cursor-help text-white/40" />
            </span>
          </span>
          <span class="identity-role-accent">
            Default resolution path
            <span class="tooltip max-w-xs" data-tip="A bare NPT handle can resolve through a default-bank route. Qualified aliases with explicit bank handles remain available for clearer support and payment routing.">
              <Info class="inline-flex h-4 w-4 cursor-help text-white/40" />
            </span>
          </span>
        </div>
      </div>
      <div class="flex flex-wrap gap-2">
        <a href="/portal/security" class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition hover:text-white">
          <ShieldCheck class="w-4 h-4" />
          Security and recovery
        </a>
        <button onclick={loadCustomer} disabled={loading} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition hover:text-white">
          <RefreshCw class={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>
      <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Identity posture</div>
          <div class="mt-2 text-sm font-medium text-white">{profile?.npt_handle || 'NPT handle loading'}</div>
          <div class="mt-1 text-[12px] text-white/45">{accounts().length} linked account route(s) · {identityPackage()?.profile?.linked_bank_count ?? linkedBankCount()} linked bank(s)</div>
        </div>
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Access posture</div>
          <div class="mt-2 text-sm font-medium text-white">{accessPackage()?.primary_identifier || 'Portal access active'}</div>
          <div class="mt-1 text-[12px] text-white/45">Readiness {readinessPackage().done}/{readinessPackage().total} · security setup {profile?.securitySetupRequired ? 'needs attention' : 'ready'}</div>
        </div>
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Primary job</div>
          <div class="mt-2 text-sm font-medium text-white">{sectionMeta[currentSection].action}</div>
          <div class="mt-1 text-[12px] text-white/45">{currentSection === 'routing' ? bareHandleRoute() : nextSteps()[0] || 'Open the section that matches what you need to verify.'}</div>
        </div>
      </div>
    </div>
  </section>

  {#if loading}
    <div class="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
      <div class="space-y-4">
        <div class="h-44 animate-pulse rounded-2xl bg-white/[0.04]"></div>
        <div class="h-80 animate-pulse rounded-2xl bg-white/[0.04]"></div>
      </div>
      <div class="h-[32rem] animate-pulse rounded-2xl bg-white/[0.04]"></div>
    </div>
  {:else if !profile}
    <div class="rounded-2xl border border-dashed border-white/[0.12] bg-white/[0.02] px-5 py-16 text-center text-sm text-white/40">
      Your customer identity profile is not available yet.
    </div>
  {:else}
    <div class="grid gap-3 md:grid-cols-4">
      <section class="identity-kpi-card px-5 py-4">
        <div class="flex items-center gap-3">
          <div class="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-indigo-300">
            <UserRound class="w-5 h-5" />
          </div>
          <div>
            <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">NPT handle</p>
            <p class="mt-1 text-lg font-semibold">{profile.npt_handle}</p>
          </div>
        </div>
      </section>
      <section class="identity-kpi-card px-5 py-4">
        <div class="flex items-center gap-3">
          <div class="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-emerald-300">
            <Landmark class="w-5 h-5" />
          </div>
          <div>
            <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">Linked accounts</p>
            <p class="mt-1 text-2xl font-semibold">{accounts().length}</p>
          </div>
        </div>
      </section>
      <section class="identity-kpi-card px-5 py-4">
        <div class="flex items-center gap-3">
          <div class="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-sky-300">
            <Building2 class="w-5 h-5" />
          </div>
          <div>
            <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">Linked banks</p>
            <p class="mt-1 text-2xl font-semibold">{identityPackage()?.profile?.linked_bank_count ?? linkedBankCount()}</p>
          </div>
        </div>
      </section>
      <section class="identity-kpi-card px-5 py-4">
        <div class="flex items-center gap-3">
          <div class="flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] text-amber-300">
            <ShieldCheck class="w-5 h-5" />
          </div>
          <div>
            <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">Readiness</p>
            <p class="mt-1 text-lg font-semibold">{readinessPackage().done}/{readinessPackage().total}</p>
          </div>
        </div>
      </section>
    </div>

    <section class="identity-desk-grid">
      <aside class="identity-desk-rail">
        <p class="identity-desk-rail-title">Customer desk</p>
        <div class="identity-desk-nav" role="tablist" aria-label="Customer identity sections">
          {#each sectionItems as item}
            <a
              href={sectionHref(item.key)}
              role="tab"
              aria-current={currentSection === item.key ? 'page' : undefined}
              class={`identity-desk-nav-item ${currentSection === item.key ? 'is-active' : ''}`}
              title={`${item.label} · ${item.hint}`}
            >
              <div class="identity-desk-nav-copy">
                <div class="identity-desk-nav-label">{item.label}</div>
                <div class="identity-desk-nav-hint">{item.hint}</div>
              </div>
            </a>
          {/each}
        </div>
      </aside>

      <div class="identity-desk-panel">
        <section class="identity-desk-header">
          <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Current section</p>
          <h2 class="mt-1 text-lg font-semibold text-white">{sectionMeta[currentSection].label}</h2>
          <p class="mt-2 text-sm text-white/45">{sectionMeta[currentSection].purpose}</p>
          <div class="identity-desk-meta">
            <span class="identity-desk-meta-chip">Handle {profile.npt_handle}</span>
            <span class="identity-desk-meta-chip">Readiness {readinessPackage().done}/{readinessPackage().total}</span>
            <span class="identity-desk-meta-chip">Linked banks {identityPackage()?.profile?.linked_bank_count ?? linkedBankCount()}</span>
            <span class="identity-desk-meta-chip">Next {sectionMeta[currentSection].action}</span>
          </div>
        </section>

    {#if currentSection === 'access'}
    <section class="identity-section-stack">
      <div class="grid gap-3 xl:grid-cols-3">
      <div class="identity-workspace-card p-5">
        <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">Access</p>
        <h2 class="mt-2 text-lg font-semibold text-white">{accessPackage()?.direct_portal_sign_in?.enabled ? 'Portal access is active' : 'Portal access needs attention'}</h2>
        <p class="mt-2 text-sm leading-6 text-white/55">{accessPackage()?.direct_portal_sign_in?.enabled ? 'Direct customer portal sign-in is available for this identity.' : 'This identity does not have a healthy direct sign-in posture yet.'}</p>
      </div>
      <div class="identity-workspace-card p-5">
        <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">Routing</p>
        <h2 class="mt-2 text-lg font-semibold text-white">{routingPackage()?.default_bank_handle ? 'Default bank route is assigned' : 'Default bank route is missing'}</h2>
        <p class="mt-2 text-sm leading-6 text-white/55">If a linked account, bank handle, or bare-handle default route is wrong, the enrolled bank or registry operator still owns the correction flow.</p>
      </div>
      <div class="identity-workspace-card p-5">
        <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">Security</p>
        <h2 class="mt-2 text-lg font-semibold text-white">{securityPackage()?.passkey_count > 0 || securityPackage()?.totp_enabled ? 'Strong factors are present' : 'Strong factors still need setup'}</h2>
        <p class="mt-2 text-sm leading-6 text-white/55">{securityPackage()?.security_setup_reason || 'Review recovery email, passkeys, and authenticator setup in the security desk.'}</p>
      </div>
      </div>

    <section class="identity-section-card">
      <div class="identity-section-card-header">
        <div>
          <div class="identity-section-card-title">Sign-in methods</div>
          <p class="identity-section-card-copy mt-1">OpenWave Identity supports more than one sign-in path, but public identifiers still require stronger approval before access is issued.</p>
        </div>
        <a href="/portal/security" class="inline-flex w-fit items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2 text-[13px] font-medium text-white/65 transition hover:border-white/[0.18] hover:text-white">
          <ShieldCheck class="w-4 h-4" />
          Review security
        </a>
      </div>
      <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
      </div>
      <div class="grid gap-3 xl:grid-cols-2">
        <div class="identity-surface-soft p-4">
          <p class="text-[11px] uppercase tracking-[0.16em] text-white/28">Direct portal sign-in</p>
          <p class="mt-2 text-sm text-white/75">Username: {accessPackage()?.direct_portal_sign_in?.username || profile.npt_handle}{accessPackage()?.direct_portal_sign_in?.email_login ? ` · email login also available` : ''}.</p>
          <p class="mt-3 text-xs text-white/40">{securityPackage()?.last_login_at ? `Last successful login ${new Date(securityPackage().last_login_at).toLocaleString()}` : 'No successful direct portal login recorded yet.'}</p>
        </div>
        <div class="identity-surface-soft p-4">
          <p class="text-[11px] uppercase tracking-[0.16em] text-white/28">Public-identifier sign-in</p>
          <p class="mt-2 text-sm text-white/75">Phone {accessPackage()?.public_identifier_sign_in?.phone_supported ? 'is available' : 'is not available'} · National ID {accessPackage()?.public_identifier_sign_in?.national_id_supported ? 'is available' : 'is not available'}.</p>
          <p class="mt-3 text-xs text-white/40">{accessPackage()?.public_identifier_sign_in?.linked_bank_approval_required ? 'Bank-app approval is required before access is issued from a public identifier.' : 'Public identifier approval is not configured.'}</p>
        </div>
      </div>
    </section>
    </section>
    {/if}

    {#if currentSection === 'approvals'}
    <section class="identity-section-card">
      <div class="identity-section-card-header">
        <div>
          <div class="identity-section-card-title">Public identifiers still depend on linked-bank approval</div>
          <p class="identity-section-card-copy mt-1">Phone number and national ID can start a customer sign-in, but the portal session is still released only after one of your linked banks approves the request.</p>
        </div>
        <div class="flex flex-wrap gap-2">
          <a href="/portal/security?section=overview" class="inline-flex w-fit items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2 text-[13px] font-medium text-white/65 transition hover:border-white/[0.18] hover:text-white">
            <ShieldCheck class="w-4 h-4" />
            Review trust posture
          </a>
          <a href="/portal/customer/login-approvals" class="inline-flex w-fit items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2 text-[13px] font-medium text-white/65 transition hover:border-white/[0.18] hover:text-white">
            <ShieldCheck class="w-4 h-4" />
            Full sign-in history
          </a>
        </div>
      </div>
      <div class="grid gap-3 md:grid-cols-4">
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Recent requests</div>
          <div class="mt-2 text-2xl font-semibold text-white">{loginApprovalSummary.total ?? 0}</div>
        </div>
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Pending</div>
          <div class="mt-2 text-2xl font-semibold text-white">{loginApprovalSummary.pending ?? 0}</div>
        </div>
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Approved</div>
          <div class="mt-2 text-2xl font-semibold text-white">{loginApprovalSummary.approved ?? 0}</div>
        </div>
        <div class="identity-surface-soft px-4 py-3">
          <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Rejected or expired</div>
          <div class="mt-2 text-2xl font-semibold text-white">{(loginApprovalSummary.rejected ?? 0) + (loginApprovalSummary.expired ?? 0)}</div>
        </div>
      </div>
      <div class="mt-5 space-y-3">
        {#if loginApprovals.length}
          {#each loginApprovals as approval}
            <div class="rounded-2xl border border-white/[0.08] bg-white/[0.03] px-4 py-4">
              <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                <div>
                  <div class={`inline-flex rounded-full border px-2.5 py-1 text-[11px] font-medium ${approvalTone(approval.status)}`}>{approval.status}</div>
                  <div class="mt-3 text-sm text-white/80">{approval.identifier_type} sign-in for <span class="font-mono text-white">{approval.requested_alias}</span></div>
                  <div class="mt-1 text-[12px] text-white/45">Identifier hint {approval.identifier_hint} · Started {fmt(approval.created_at)}</div>
                  <div class="mt-1 text-[12px] text-white/35">{approval.actioned_at ? `Actioned ${fmt(approval.actioned_at)}` : `Expires ${fmt(approval.expires_at)}`}</div>
                </div>
                <div class="identity-surface-soft min-w-[260px] px-4 py-3">
                  <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Bank options</div>
                  <div class="mt-2 space-y-2">
                    {#each approval.bank_options ?? [] as option}
                      <div class="flex items-center justify-between gap-3 text-[12px] text-white/70">
                        <div class="font-mono">{option.alias}</div>
                        <div class="flex gap-2">
                          {#if option.is_default}
                            <span class="rounded-full border border-white/[0.08] px-2 py-0.5 text-[10px] text-white/45">Default</span>
                          {/if}
                          {#if option.approved}
                            <span class="rounded-full border border-emerald-500/20 bg-emerald-500/10 px-2 py-0.5 text-[10px] text-emerald-200">Approved bank</span>
                          {/if}
                        </div>
                      </div>
                    {/each}
                  </div>
                </div>
              </div>
            </div>
          {/each}
        {:else}
          <div class="rounded-2xl border border-dashed border-white/[0.12] bg-white/[0.02] px-4 py-8 text-center text-sm text-white/35">
            No recent public-identifier approval activity is recorded for this identity yet.
          </div>
        {/if}
      </div>
    </section>
    {/if}

    {#if currentSection === 'routing'}
    <div class="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
      <div class="space-y-6">
        <section class="identity-section-card">
          <div class="identity-section-card-header">
            <div>
              <div class="identity-section-card-title">Route corrections and access recovery</div>
              <p class="identity-section-card-copy mt-1">Review your linked routes here. Changes to linked routes stay controlled by the enrolled bank or the registry operator.</p>
            </div>
            <div class="flex flex-wrap gap-2">
              <a href="/portal/security" class="inline-flex items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2 text-[13px] font-medium text-white/65 transition hover:border-white/[0.18] hover:text-white">
                <ShieldCheck class="w-4 h-4" />
                Security and recovery
              </a>
            </div>
          </div>
          <div class="grid gap-3 md:grid-cols-3">
            {#each nextSteps().slice(0, 3) as step}
              <div class="identity-surface-soft px-4 py-3 text-sm text-white/75">{step}</div>
            {/each}
          </div>
        </section>

        <section class="identity-section-card">
          <div class="identity-section-card-header">
            <div>
              <div class="identity-section-card-title">How this identity is protected</div>
              <p class="identity-section-card-copy mt-1">OpenWave Identity supports more than one sign-in path, but public identifiers still require stronger approval before access is issued.</p>
            </div>
            <a href="/portal/security" class="inline-flex w-fit items-center gap-2 rounded-xl border border-white/[0.1] px-4 py-2 text-[13px] font-medium text-white/65 transition hover:border-white/[0.18] hover:text-white">
              <ShieldCheck class="w-4 h-4" />
              Review security
            </a>
          </div>

          <div class="grid gap-3 xl:grid-cols-2">
            <div class="identity-surface-soft p-4">
              <p class="text-[11px] uppercase tracking-[0.16em] text-white/28">Direct portal sign-in</p>
              <p class="mt-2 text-sm text-white/75">Username: {accessPackage()?.direct_portal_sign_in?.username || profile.npt_handle}{accessPackage()?.direct_portal_sign_in?.email_login ? ` · email login also available` : ''}.</p>
              <p class="mt-3 text-xs text-white/40">{securityPackage()?.last_login_at ? `Last successful login ${new Date(securityPackage().last_login_at).toLocaleString()}` : 'No successful direct portal login recorded yet.'}</p>
            </div>
            <div class="identity-surface-soft p-4">
              <p class="text-[11px] uppercase tracking-[0.16em] text-white/28">Public-identifier sign-in</p>
              <p class="mt-2 text-sm text-white/75">Phone {accessPackage()?.public_identifier_sign_in?.phone_supported ? 'is available' : 'is not available'} · National ID {accessPackage()?.public_identifier_sign_in?.national_id_supported ? 'is available' : 'is not available'}.</p>
              <p class="mt-3 text-xs text-white/40">{accessPackage()?.public_identifier_sign_in?.linked_bank_approval_required ? 'Bank-app approval is required before access is issued from a public identifier.' : 'Public identifier approval is not configured.'}</p>
            </div>
          </div>
        </section>

        <section class="identity-section-card">
          <div class="identity-section-card-header">
            <div>
              <div class="identity-section-card-title">How your alias resolves</div>
              <p class="identity-section-card-copy mt-1">Use this desk to understand what a bare handle does and which bank-qualified aliases remain available for routing and support.</p>
            </div>
            <div class="identity-surface-soft px-4 py-3 text-sm text-white/65">
              Customers can review their routes here. Route edits remain bank- or registry-controlled.
            </div>
          </div>

          <div class="grid gap-3 xl:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
            <div class="identity-surface-soft p-4">
              <p class="text-[11px] uppercase tracking-[0.16em] text-white/28">Bare NPT handle</p>
              <p class="mt-2 font-mono text-sm text-white">{profile.npt_handle}</p>
              <p class="mt-3 text-sm text-white/65">{bareHandleRoute()}</p>
              <p class="mt-3 text-xs text-white/40">When someone uses your handle without a bank suffix, this default-bank route decides which bank responds first.</p>
            </div>

            <div class="identity-surface-soft p-4">
              <div class="flex items-center justify-between">
                <p class="text-[11px] uppercase tracking-[0.16em] text-white/28">Bank-qualified aliases</p>
                <span class="text-[11px] text-white/35">{routingPackage()?.qualified_alias_count ?? qualifiedAliases().length} route{qualifiedAliases().length === 1 ? '' : 's'}</span>
              </div>
              <div class="mt-4 space-y-3">
                {#each qualifiedAliases() as route}
                  <div class="identity-surface-soft px-4 py-3">
                    <div class="flex flex-col gap-2 lg:flex-row lg:items-center lg:justify-between">
                      <div class="min-w-0">
                        <p class="truncate font-mono text-sm text-white">{route.alias}</p>
                        <p class="mt-1 text-[12px] text-white/45">{route.bank} ({route.bankHandle}) · {route.iban}{route.accountNumber ? ` · ${route.accountNumber}` : ''}{route.customerRef ? ` · Ref ${route.customerRef}` : ''}</p>
                      </div>
                      <span class={`inline-flex w-fit items-center rounded-full border px-2.5 py-1 text-[11px] font-medium ${route.default ? 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300' : 'border-white/[0.08] bg-white/[0.03] text-white/45'}`}>
                        {route.default ? 'Default account route' : 'Qualified route'}
                      </span>
                    </div>
                  </div>
                {/each}
              </div>
            </div>
          </div>
        </section>

        <section class="identity-section-card overflow-hidden">
          <div class="flex items-center justify-between border-b border-white/[0.06] px-5 py-4">
            <div>
              <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Linked Accounts</p>
              <h2 class="mt-1 text-lg font-semibold">Resolution routes</h2>
            </div>
            <p class="text-sm text-white/35">Select a route to inspect the account mapping.</p>
          </div>
          <div class="hidden grid-cols-[minmax(0,1fr)_130px_160px_120px] gap-4 border-b border-white/[0.06] px-5 py-3 text-[11px] uppercase tracking-[0.16em] text-white/25 md:grid">
            <span>Alias</span>
            <span>Bank</span>
            <span>Account</span>
            <span>Status</span>
          </div>
          <div class="divide-y divide-white/[0.05]">
            {#each accounts() as account}
              <button type="button" onclick={() => selectAccount(account)} class={`grid w-full gap-2 px-5 py-4 text-left transition-colors hover:bg-white/[0.03] md:grid-cols-[minmax(0,1fr)_130px_160px_120px] md:gap-4 ${accountKey(selectedAccount) === accountKey(account) ? 'bg-white/[0.04]' : ''}`}>
                <div class="min-w-0">
                  <p class="truncate font-mono text-sm text-white">{account.alias}</p>
                  <p class="mt-1 truncate text-[12px] text-white/35">{account.account_name || 'Linked settlement account'}</p>
                </div>
                <p class="text-sm text-white/65">{account.bank_display_name || account.bank_handle}</p>
                <p class="font-mono text-sm text-white/65">{fullIban(account)}</p>
                <div>
                  <span class={`rounded-full border px-2.5 py-1 text-[11px] font-medium ${account.default ? 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300' : 'border-white/[0.08] bg-white/[0.03] text-white/45'}`}>
                    {account.default ? 'Default' : 'Linked'}
                  </span>
                </div>
              </button>
            {:else}
              <div class="px-5 py-12 text-center text-sm text-white/40">No linked accounts are available.</div>
            {/each}
          </div>
        </section>
      </div>

      <section class="identity-section-card">
        <div class="identity-section-card-header">
          <div>
            <div class="identity-section-card-title">Linked account detail</div>
            <p class="identity-section-card-copy mt-1">Inspect the exact bank mapping used for this route, including provenance and route timestamps.</p>
          </div>
          <span class="identity-role-accent">Customer-owned visibility</span>
        </div>
        {#if selectedAccount}
          <div class="mt-3 space-y-5">
            <div>
              <h3 class="text-lg font-semibold">{selectedAccount.alias}</h3>
              <p class="mt-1 text-sm text-white/40">This is the route used when your selected bank account is resolved by the registry.</p>
            </div>

            <div class="grid gap-3">
              {#each [
                ['Bank', selectedAccount.bank_display_name || selectedAccount.bank_handle],
                ['Bank handle', selectedAccount.bank_handle],
                ['Bank customer ref', selectedAccount.bank_customer_ref || 'Not set'],
                ['IBAN', fullIban(selectedAccount)],
                ['Account number', fullAccountNumber(selectedAccount)],
                ['Account label', selectedAccount.account_name || 'Not set'],
                ['Currency', selectedAccount.currency || '—'],
                ['Status', accountStatus(selectedAccount)],
                ['Linked at', selectedAccount.linked_at ? new Date(selectedAccount.linked_at).toLocaleString() : '—'],
                ['Last route update', selectedAccount.updated_at ? new Date(selectedAccount.updated_at).toLocaleString() : '—']
              ] as [label, value]}
                <div class="identity-surface-soft px-4 py-3">
                  <p class="text-[11px] uppercase tracking-[0.14em] text-white/28">{label}</p>
                  <p class="mt-1 text-sm text-white/75 break-all">{value}</p>
                </div>
              {/each}
            </div>

            <div class="identity-surface-soft p-4">
              <p class="text-[11px] uppercase tracking-[0.14em] text-white/30">Routing note</p>
              <p class="mt-2 text-sm text-white/45">
                {selectedAccount.default
                  ? 'This account is the current default resolution path for the selected bank mapping.'
                  : 'This account remains linked and available, but another route currently resolves as the default for that bank.'}
              </p>
            </div>
          </div>
        {:else}
          <div class="mt-4 rounded-2xl border border-dashed border-white/[0.12] bg-white/[0.02] px-4 py-12 text-center text-sm text-white/40">
            Select an account route to inspect its details.
          </div>
        {/if}
      </section>
    </div>
    {/if}

    {#if currentSection === 'profile'}
    <section class="identity-section-card">
      <div class="identity-section-card-header">
        <div>
          <div class="identity-section-card-title">{profile.display_name}</div>
          <p class="identity-section-card-copy mt-1">Authenticated owner view for your registered identity, contact channels, and continuity identifiers.</p>
        </div>
        <span class={`rounded-full border px-3 py-1 text-[11px] font-medium ${profile.active ? 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300' : 'border-amber-500/20 bg-amber-500/10 text-amber-300'}`}>
          {profile.active ? 'Active' : 'Inactive'}
        </span>
      </div>

      <div class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
        {#each [
          ['Default bank', profile.default_bank_handle || 'Not set'],
          ['National ID', profile.national_id || 'Not set'],
          ['Phone', profile.phone || 'Not set'],
          ['Email', profile.email || 'Not set'],
          ['Customer status', profile.status],
          ['Passkeys', String(securityPackage()?.passkey_count ?? 0)],
          ['Authenticator', securityPackage()?.totp_enabled ? 'Enabled' : securityPackage()?.totp_pending ? 'Pending' : 'Off'],
          ['Portal login', accessPackage()?.direct_portal_sign_in?.enabled ? 'Available' : 'Unavailable']
        ] as [label, value]}
          <div class="identity-surface-soft px-4 py-3">
            <p class="text-[11px] uppercase tracking-[0.14em] text-white/28">{label}</p>
            <p class="mt-1 text-sm text-white/75">{value}</p>
          </div>
        {/each}
      </div>
    </section>
    {/if}
      </div>
    </section>
  {/if}
</div>
