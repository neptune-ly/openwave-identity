<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import { auth } from '$lib/stores/auth';
  import { apiCall, apiPublic, getApi } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import Upload from 'lucide-svelte/icons/upload';
  import Building2 from 'lucide-svelte/icons/building-2';
  import Palette from 'lucide-svelte/icons/palette';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import Mail from 'lucide-svelte/icons/mail';
  import Globe from 'lucide-svelte/icons/globe';
  import Landmark from 'lucide-svelte/icons/landmark';
  import CircleCheckBig from 'lucide-svelte/icons/circle-check-big';

  let session = $state(null);
  let loading = $state(true);
  let saving = $state(false);
  let bank = $state(null);
  let form = $state({ displayName: '', brandColor: '', supportEmail: '', website: '', logoUrl: '' });
  const currentSection = $derived(readSection());
  const operationsPackage = $derived(bank?.operationsPackage ?? null);

  const isAdmin = $derived(session?.role === 'ADMIN');
  const bankHandle = $derived(get(page).params.handle);
  const sectionMeta = {
    profile: {
      label: 'Profile',
      purpose: 'Edit the public bank directory record, trust signals, and operator-facing contact details.',
      action: 'Edit public bank profile'
    },
    readiness: {
      label: 'Readiness',
      purpose: 'Review enrollment, approval, and directory readiness posture for this bank-vouched identity participant.',
      action: 'Review trust and readiness'
    },
    preview: {
      label: 'Preview',
      purpose: 'See how the bank profile appears in Identity-facing surfaces before operators or partners rely on it.',
      action: 'Review public appearance'
    }
  };
  const sectionItems = [
    { key: 'profile', label: 'Profile', action: 'Edit public bank profile', hint: 'Public bank record, contact, and website' },
    { key: 'readiness', label: 'Readiness', action: 'Review trust and readiness', hint: 'Enrollment, approval, and public-profile posture' },
    { key: 'preview', label: 'Preview', action: 'Review public appearance', hint: 'How the bank record reads in Identity surfaces' }
  ];

  onMount(async () => {
    session = get(auth);
    await loadBank();
  });

  async function loadBank() {
    loading = true;
    const response = isAdmin ? await apiPublic(`/banks/${bankHandle}`) : await apiCall('get', '/banks/me');
    loading = false;
    if (!response.ok) {
      toast.error(response.error || 'Could not load bank desk');
      return;
    }
    const payload = response.data;
    if (!isAdmin && payload.bankHandle !== bankHandle) {
      await goto('/portal/banks');
      return;
    }
    bank = payload;
    form = {
      displayName: bank.branding?.display_name || bank.displayName || '',
      brandColor: bank.branding?.brand_color || '',
      supportEmail: bank.branding?.support_email || '',
      website: bank.branding?.website || '',
      logoUrl: bank.branding?.logo_url || ''
    };
  }

  async function saveBranding() {
    if (!bank) return;
    saving = true;
    const endpoint = isAdmin ? `/banks/${bank.bankHandle}/branding` : '/banks/me/branding';
    const response = await apiCall('patch', endpoint, form);
    saving = false;
    if (!response.ok) {
      toast.error(response.error || 'Could not update bank profile');
      return;
    }
    toast.success('Bank profile updated');
    await loadBank();
  }

  async function uploadLogo(event) {
    if (!bank) return;
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    if (!file) return;
    const formData = new FormData();
    formData.append('file', file);
    saving = true;
    try {
      const endpoint = isAdmin ? `/banks/${bank.bankHandle}/branding/logo` : '/banks/me/branding/logo';
      await getApi().post(endpoint, formData, { headers: { 'Content-Type': 'multipart/form-data' } });
      toast.success('Bank logo uploaded');
      await loadBank();
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || error?.message || 'Could not upload logo');
    } finally {
      saving = false;
    }
  }

  function readinessRows() {
    if (!bank) return [];
    return [
      { title: 'Directory identity', done: Boolean(form.displayName || bank.displayName), detail: form.displayName || bank.displayName || 'Missing display name' },
      { title: 'Operations contact', done: Boolean(form.supportEmail || bank.contactEmail || bank.branding?.support_email), detail: form.supportEmail || bank.branding?.support_email || bank.contactEmail || 'Missing support email' },
      { title: 'Core routing profile', done: Boolean(bank.coreUrl), detail: bank.coreUrl || 'Missing core URL' },
      { title: 'Brand signal', done: Boolean(form.brandColor || form.logoUrl || bank.branding?.logo_url), detail: form.logoUrl || bank.branding?.logo_url ? 'Logo uploaded' : form.brandColor || bank.branding?.brand_color ? `Color ${form.brandColor || bank.branding?.brand_color}` : 'No color or logo' },
      { title: 'Public website', done: Boolean(form.website || bank.branding?.website), detail: form.website || bank.branding?.website || 'Missing website' }
    ];
  }

  function summaryCards() {
    if (!bank) return [];
    return [
      { label: 'Directory scope', value: bank.bankHandle, icon: Landmark, tone: 'indigo' },
      { label: 'Profile state', value: bank.active ? 'Active' : 'Inactive', icon: ShieldCheck, tone: 'emerald' },
      { label: 'Readiness', value: `${operationsPackage?.readiness?.done ?? readinessRows().filter((item) => item.done).length}/${operationsPackage?.readiness?.total ?? readinessRows().length}`, icon: CircleCheckBig, tone: 'sky' },
      { label: 'Linked customers', value: String(operationsPackage?.customer_registry?.linked_customer_count ?? 0), icon: Palette, tone: 'amber' }
    ];
  }

  function toneClass(tone) {
    if (tone === 'emerald') return 'text-emerald-300';
    if (tone === 'sky') return 'text-sky-300';
    if (tone === 'amber') return 'text-amber-300';
    return 'text-indigo-300';
  }

  function readSection() {
    const section = get(page).url.searchParams.get('section');
    return ['profile', 'readiness', 'preview'].includes(section) ? section : 'profile';
  }

  function sectionHref(section) {
    return section === 'profile' ? `/portal/banks/${bankHandle}` : `/portal/banks/${bankHandle}?section=${section}`;
  }

  async function setSectionRoute(section) {
    const next = new URL(get(page).url);
    if (section === 'profile') next.searchParams.delete('section');
    else next.searchParams.set('section', section);
    await goto(`${next.pathname}${next.search}`, { replaceState: true, noScroll: true, keepFocus: true });
  }

  function readinessSummary() {
    const rows = readinessRows();
    if (!rows.length) return 'Pending';
    return `${rows.filter((item) => item.done).length}/${rows.length} complete`;
  }

  function trustSummary() {
    if (!bank) return 'Pending';
    if (bank.active && (form.displayName || bank.displayName) && (form.supportEmail || bank.contactEmail || bank.branding?.support_email)) return 'Publishable';
    if (bank.active) return 'Needs public profile work';
    return 'Inactive record';
  }
</script>

<svelte:head><title>Bank Desk - OpenWave Identity</title></svelte:head>

<div class="mx-auto max-w-6xl space-y-6 p-8">
  <div class="flex flex-wrap items-center justify-between gap-3">
    <a href="/portal/banks" class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white">
      <ArrowLeft class="h-4 w-4" />
      Back to bank registry
    </a>
    <button onclick={loadBank} disabled={loading || saving} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">
      <RefreshCw class={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
      Refresh
    </button>
  </div>

  {#if loading || !bank}
    <section class="identity-surface-card p-8 text-center text-sm text-white/45">Loading bank desk...</section>
  {:else}
    <section class="identity-expressive-band p-6">
      <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
        <div class="max-w-3xl">
          <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">{isAdmin ? 'Member-bank desk' : 'My bank desk'}</p>
          <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">{form.displayName || bank.displayName}</h1>
          <p class="mt-2 text-sm text-white/45">{bank.bankHandle} · {bank.country}</p>
          <p class="identity-section-note mt-2 text-sm text-white/55">
            Manage the public Identity-facing bank profile, readiness posture, and trust signals from one dedicated bank record page.
          </p>
          <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
            <span class="identity-role-accent">Directory profile</span>
            <span class="identity-role-accent">{bank.active ? 'Active bank record' : 'Inactive bank record'}</span>
            <span class="identity-role-accent">{isAdmin ? 'Registry-owned onboarding' : 'Controlled bank self-service'}</span>
          </div>
          {#if operationsPackage}
            <div class="mt-4 grid gap-3 sm:grid-cols-3">
              <div class="identity-surface-soft px-4 py-3">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Linked customers</div>
                <div class="mt-2 text-lg font-semibold text-white">{operationsPackage.customer_registry?.linked_customer_count ?? 0}</div>
                <div class="mt-1 text-[12px] text-white/45">{operationsPackage.customer_registry?.linked_account_count ?? 0} linked account route(s)</div>
              </div>
              <div class="identity-surface-soft px-4 py-3">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Approval queue</div>
                <div class="mt-2 text-lg font-semibold text-white">{operationsPackage.login_approvals?.pending ?? 0}</div>
                <div class="mt-1 text-[12px] text-white/45">{operationsPackage.login_approvals?.approved ?? 0} approved request(s)</div>
              </div>
              <div class="identity-surface-soft px-4 py-3">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Portal ownership</div>
                <div class="mt-2 text-lg font-semibold text-white">{operationsPackage.portal_access?.active_portal_user_count ?? 0}</div>
                <div class="mt-1 text-[12px] text-white/45">active bank portal user(s)</div>
              </div>
            </div>
          {/if}
        </div>
        <div class="grid gap-3 sm:grid-cols-2">
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Scope</div>
            <div class="mt-2 text-sm font-medium text-white">Public directory and trust signals only.</div>
            <div class="mt-1 text-[12px] text-white/45">Sensitive payment routing, internal keys, and bank execution controls stay outside this desk.</div>
          </div>
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Current state</div>
            <div class="mt-2 text-sm font-medium text-white">{readinessSummary()}</div>
            <div class="mt-1 text-[12px] text-white/45">{trustSummary()}</div>
          </div>
        </div>
      </div>
    </section>

    <div class="grid gap-3 md:grid-cols-4">
      {#each summaryCards() as item}
        <section class="identity-kpi-card px-5 py-4">
          <div class="flex items-center gap-3">
            <div class={`flex h-10 w-10 items-center justify-center rounded-xl border border-white/[0.08] bg-white/[0.04] ${toneClass(item.tone)}`}>
              <item.icon class="h-5 w-5" />
            </div>
            <div>
              <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">{item.label}</p>
              <p class="mt-1 text-lg font-semibold">{item.value}</p>
            </div>
          </div>
        </section>
      {/each}
    </div>

    <section class="identity-desk-grid">
      <aside class="identity-desk-rail">
        <p class="identity-desk-rail-title">Bank desk</p>
        <div class="identity-desk-nav" role="tablist" aria-label="Bank desk sections">
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
            <span class="identity-desk-meta-chip">Bank {bank.bankHandle}</span>
            <span class="identity-desk-meta-chip">Readiness {readinessSummary()}</span>
            <span class="identity-desk-meta-chip">Trust {trustSummary()}</span>
            <span class="identity-desk-meta-chip">Next {sectionMeta[currentSection].action}</span>
          </div>
        </section>

    {#if operationsPackage && currentSection === 'readiness'}
      <section class="identity-surface-card p-6">
        <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
          <div>
            <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Bank-vouched identity operations</p>
            <h2 class="mt-1 text-xl font-semibold">Trust and readiness desk</h2>
            <p class="mt-1 text-sm text-white/50">One bank-scoped package for customer enrollment visibility, sign-in approval operations, portal ownership, and public directory readiness.</p>
          </div>
          <div class={`rounded-full border px-3 py-1 text-[11px] font-medium ${(operationsPackage.readiness?.done === operationsPackage.readiness?.total) ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-200' : 'border-amber-500/20 bg-amber-500/10 text-amber-200'}`}>
            {operationsPackage.readiness?.done}/{operationsPackage.readiness?.total} checks
          </div>
        </div>
        <div class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Linked customers</div>
            <div class="mt-2 text-2xl font-semibold text-white">{operationsPackage.customer_registry?.linked_customer_count ?? 0}</div>
            <div class="mt-1 text-[12px] text-white/45">{operationsPackage.customer_registry?.active_customer_count ?? 0} active identity profile(s)</div>
          </div>
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Default routes</div>
            <div class="mt-2 text-2xl font-semibold text-white">{operationsPackage.customer_registry?.default_bank_route_count ?? 0}</div>
            <div class="mt-1 text-[12px] text-white/45">identity profile(s) defaulting to this bank</div>
          </div>
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Pending approvals</div>
            <div class="mt-2 text-2xl font-semibold text-white">{operationsPackage.login_approvals?.pending ?? 0}</div>
            <div class="mt-1 text-[12px] text-white/45">{operationsPackage.login_approvals?.approved ?? 0} approved · {operationsPackage.login_approvals?.rejected ?? 0} rejected</div>
          </div>
          <div class="identity-surface-soft px-4 py-3">
            <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Portal users</div>
            <div class="mt-2 text-2xl font-semibold text-white">{operationsPackage.portal_access?.active_portal_user_count ?? 0}</div>
            <div class="mt-1 text-[12px] text-white/45">{operationsPackage.portal_access?.admin_portal_user_count ?? 0} bank admin user(s)</div>
          </div>
        </div>
        <div class="mt-5 grid gap-3 xl:grid-cols-[minmax(0,0.95fr)_minmax(320px,1.05fr)]">
          <div class="space-y-3">
            {#each operationsPackage.readiness?.checks ?? [] as item}
              <div class="rounded-2xl border border-white/[0.08] bg-white/[0.03] px-4 py-3">
                <div class="flex items-center justify-between gap-3">
                  <div class="text-sm font-medium text-white">{item.label}</div>
                  <div class={`rounded-full border px-2.5 py-1 text-[11px] font-medium ${item.done ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-200' : 'border-amber-500/20 bg-amber-500/10 text-amber-200'}`}>{item.done ? 'Ready' : 'Needs work'}</div>
                </div>
                <div class="mt-1 text-[12px] text-white/45">{item.detail}</div>
              </div>
            {/each}
          </div>
          <div class="rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4">
            <div class="text-[11px] uppercase tracking-[0.18em] text-white/30">Next steps</div>
            <div class="mt-3 space-y-2">
              {#each operationsPackage.next_steps ?? [] as step}
                <div class="rounded-xl border border-white/[0.08] bg-black/10 px-3 py-2 text-sm text-white/70">{step}</div>
              {/each}
            </div>
          </div>
        </div>
      </section>
    {:else if currentSection === 'readiness'}
      <section class="identity-section-card">
        <div class="identity-section-card-header">
          <div>
            <div class="identity-section-card-title">Readiness review</div>
            <div class="mt-1 text-[12px] text-white/35">Operational package data is unavailable, so this view is using the local public-profile checks.</div>
          </div>
        </div>
        <div class="space-y-3">
          {#each readinessRows() as item}
            <div class="identity-surface-soft flex items-start justify-between gap-3 px-4 py-3">
              <div>
                <p class="text-sm font-medium text-white">{item.title}</p>
                <p class="mt-1 text-[12px] text-white/40">{item.detail}</p>
              </div>
              <span class={`rounded-full border px-2.5 py-1 text-[11px] font-medium ${item.done ? 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300' : 'border-amber-500/20 bg-amber-500/10 text-amber-300'}`}>
                {item.done ? 'Ready' : 'Needs work'}
              </span>
            </div>
          {/each}
        </div>
      </section>
    {/if}

    <div class="identity-section-stack">
      {#if currentSection === 'profile'}
        <section class="identity-section-card">
          <div class="identity-section-card-header">
            <div class="flex items-start gap-4">
              <div class="flex h-12 w-12 items-center justify-center overflow-hidden rounded-2xl border border-white/[0.08] bg-white/[0.05] text-sm font-semibold text-indigo-300">
                {#if form.logoUrl || bank.branding?.logo_url}
                  <img src={form.logoUrl || bank.branding?.logo_url} alt={form.displayName || bank.displayName} class="h-full w-full object-cover" />
                {:else}
                  {bank.bankHandle.slice(0, 2).toUpperCase()}
                {/if}
              </div>
              <div class="min-w-0">
                <div class="identity-section-card-title">Bank profile controls</div>
                <div class="mt-1 text-[12px] text-white/35">Edit the public record visible to Identity operators and partner lookups.</div>
              </div>
            </div>
          </div>

          <div class="grid gap-4 md:grid-cols-2">
            <label class="block">
              <span class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Display name</span>
              <input bind:value={form.displayName} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none" />
            </label>
            <label class="block">
              <span class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Brand color</span>
              <input bind:value={form.brandColor} placeholder="#07315F" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] px-3.5 py-2.5 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none" />
            </label>
            <label class="block">
              <span class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Support email</span>
              <div class="relative">
                <Mail class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-white/25" />
                <input bind:value={form.supportEmail} placeholder="support@bank.ly" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] py-2.5 pl-9 pr-3 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none" />
              </div>
            </label>
            <label class="block">
              <span class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Website</span>
              <div class="relative">
                <Globe class="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-white/25" />
                <input bind:value={form.website} placeholder="https://bank.ly" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.04] py-2.5 pl-9 pr-3 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none" />
              </div>
            </label>
          </div>

          <div class="mt-6 grid gap-3 md:grid-cols-2">
            <div class="identity-surface-soft px-4 py-3">
              <p class="text-[11px] uppercase tracking-[0.14em] text-white/28">Core URL</p>
              <p class="mt-1 break-all text-sm text-white/75">{bank.coreUrl || 'Not set'}</p>
            </div>
            <div class="identity-surface-soft px-4 py-3">
              <p class="text-[11px] uppercase tracking-[0.14em] text-white/28">Operations contact</p>
              <p class="mt-1 break-all text-sm text-white/75">{bank.contactEmail || 'Not set'}</p>
            </div>
          </div>

          <div class="mt-6 flex flex-wrap gap-2">
            <label class="inline-flex cursor-pointer items-center gap-2 rounded-xl border border-white/[0.1] px-3.5 py-2 text-[12px] font-medium text-white/60 transition-all hover:border-white/[0.18] hover:text-white">
              <Upload class="h-4 w-4" />
              Upload logo
              <input type="file" accept="image/png,image/jpeg,image/webp" class="hidden" onchange={uploadLogo} />
            </label>
            <button onclick={saveBranding} disabled={saving} class="rounded-xl bg-indigo-600 px-4 py-2 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-40">
              {saving ? 'Saving...' : 'Save profile'}
            </button>
          </div>
        </section>
      {/if}

      {#if currentSection !== 'readiness'}
        <section class="identity-section-card">
          <div class="identity-section-card-header">
            <div>
              <div class="identity-section-card-title">Readiness review</div>
              <div class="mt-1 text-[12px] text-white/35">Public-profile quality checks for the current bank record.</div>
            </div>
          </div>
          <div class="space-y-3">
            {#each readinessRows() as item}
              <div class="identity-surface-soft flex items-start justify-between gap-3 px-4 py-3">
                <div>
                  <p class="text-sm font-medium text-white">{item.title}</p>
                  <p class="mt-1 text-[12px] text-white/40">{item.detail}</p>
                </div>
                <span class={`rounded-full border px-2.5 py-1 text-[11px] font-medium ${item.done ? 'border-emerald-500/25 bg-emerald-500/10 text-emerald-300' : 'border-amber-500/20 bg-amber-500/10 text-amber-300'}`}>
                  {item.done ? 'Ready' : 'Needs work'}
                </span>
              </div>
            {/each}
          </div>
        </section>
      {/if}

      {#if currentSection === 'preview'}
        <section class="identity-section-card">
          <div class="identity-section-card-header">
            <div>
              <div class="identity-section-card-title">Directory preview</div>
              <div class="mt-1 text-[12px] text-white/35">How this bank record appears in Identity-facing surfaces.</div>
            </div>
          </div>
          <div class="identity-surface-soft p-5">
            <div class="flex items-start gap-4">
              <div class="flex h-14 w-14 items-center justify-center overflow-hidden rounded-2xl border border-white/[0.08] bg-white/[0.05] text-sm font-semibold text-indigo-300">
                {#if form.logoUrl || bank.branding?.logo_url}
                  <img src={form.logoUrl || bank.branding?.logo_url} alt={form.displayName || bank.displayName} class="h-full w-full object-cover" />
                {:else}
                  {bank.bankHandle.slice(0, 2).toUpperCase()}
                {/if}
              </div>
              <div class="min-w-0">
                <div class="flex items-center gap-2">
                  <h4 class="truncate text-lg font-semibold text-white">{form.displayName || bank.displayName}</h4>
                  {#if form.brandColor}
                    <span class="h-3 w-3 rounded-full border border-white/25" style={`background:${form.brandColor}`}></span>
                  {/if}
                </div>
                <p class="mt-1 text-sm text-white/40">{bank.bankHandle} · {bank.country}</p>
              </div>
            </div>
            <div class="mt-4 grid gap-3">
              <div class="identity-surface-soft px-4 py-3">
                <div class="flex items-center gap-2 text-[11px] uppercase tracking-[0.14em] text-white/28"><Mail class="h-3.5 w-3.5" /> Support</div>
                <p class="mt-1 break-all text-sm text-white/75">{form.supportEmail || bank.contactEmail || 'Not set'}</p>
              </div>
              <div class="identity-surface-soft px-4 py-3">
                <div class="flex items-center gap-2 text-[11px] uppercase tracking-[0.14em] text-white/28"><Globe class="h-3.5 w-3.5" /> Website</div>
                <p class="mt-1 break-all text-sm text-white/75">{form.website || 'Not set'}</p>
              </div>
            </div>
          </div>
        </section>
      {/if}
    </div>
      </div>
    </section>
  {/if}
</div>
