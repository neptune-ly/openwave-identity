<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { auth } from '$lib/stores/auth';
  import { apiCall } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import Building2 from 'lucide-svelte/icons/building-2';
  import Copy from 'lucide-svelte/icons/copy';
  import Plus from 'lucide-svelte/icons/plus';

  let session = $state(null);
  let formLoading = $state(false);
  let newBankKey = $state('');
  let createdHandle = $state('');
  let form = $state({
    bankHandle: '',
    displayName: '',
    country: 'LY',
    coreUrl: '',
    contactEmail: '',
    brandColor: '',
    supportEmail: '',
    website: ''
  });

  onMount(async () => {
    session = get(auth);
    if (session?.role !== 'ADMIN') {
      await goto('/portal/banks', { replaceState: true });
    }
  });

  function resetRegistrationForm() {
    form = {
      bankHandle: '',
      displayName: '',
      country: 'LY',
      coreUrl: '',
      contactEmail: '',
      brandColor: '',
      supportEmail: '',
      website: ''
    };
  }

  async function registerBank() {
    formLoading = true;
    newBankKey = '';
    createdHandle = '';
    const response = await apiCall('post', '/banks', form);
    formLoading = false;
    if (!response.ok) {
      toast.error(response.error || 'Could not register bank');
      return;
    }
    newBankKey = response.data.bankApiKey || response.data.apiKey || '';
    createdHandle = response.data.bankHandle || form.bankHandle;
    resetRegistrationForm();
    toast.success('Bank registered');
  }

  async function copyKey() {
    if (!newBankKey) return;
    await navigator.clipboard.writeText(newBankKey);
    toast.success('Copied to clipboard');
  }
</script>

<svelte:head><title>Register Bank - OpenWave Identity</title></svelte:head>

<div class="mx-auto max-w-7xl space-y-6 p-8">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
      <div class="max-w-3xl">
        <button onclick={() => goto('/portal/banks')} class="inline-flex items-center gap-2 text-[12px] font-medium text-white/45 transition-colors hover:text-white/75">
          <ArrowLeft class="h-4 w-4" />
          Back to banks
        </button>
        <p class="mt-4 text-[11px] uppercase tracking-[0.18em] text-white/30">Registry banking directory</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight">Register Bank</h1>
        <p class="mt-2 text-sm text-white/50">
          Create the directory record and reveal the initial bank API credential here. Later readiness and profile work should stay on the dedicated bank desk.
        </p>
      </div>
      <button onclick={registerBank} disabled={formLoading || !form.bankHandle || !form.displayName || !form.contactEmail || !form.coreUrl} class="inline-flex items-center gap-2 rounded-xl bg-indigo-600 px-4 py-2 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-40">
        <Plus class="h-4 w-4" />
        {formLoading ? 'Registering...' : 'Register bank'}
      </button>
    </div>
  </section>

  {#if newBankKey}
    <section class="identity-surface-card border-amber-500/25 bg-amber-500/10 px-5 py-4">
      <div class="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <p class="text-[11px] font-semibold uppercase tracking-[0.18em] text-amber-300">One-time credential reveal</p>
          <p class="mt-1 text-sm text-amber-100/85">Copy this bank API key now, then continue on the dedicated bank desk.</p>
        </div>
        <div class="flex flex-wrap gap-2">
          <button onclick={copyKey} class="inline-flex items-center gap-2 rounded-xl border border-amber-300/25 px-3.5 py-2 text-[12px] font-medium text-amber-200 transition-all hover:border-amber-300/45 hover:text-white">
            <Copy class="h-4 w-4" />
            Copy key
          </button>
          {#if createdHandle}
            <button onclick={() => goto(`/portal/banks/${createdHandle}`)} class="rounded-xl bg-indigo-600 px-4 py-2 text-[12px] font-semibold text-white transition-all hover:bg-indigo-500">
              Open bank desk
            </button>
          {/if}
        </div>
      </div>
      <code class="mt-3 block break-all rounded-xl bg-black/25 px-4 py-3 text-sm text-amber-100">{newBankKey}</code>
    </section>
  {/if}

  <section class="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(360px,0.85fr)]">
    <div class="identity-surface-card p-6">
      <div class="flex items-center gap-2">
        <Building2 class="h-4 w-4 text-indigo-300" />
        <h2 class="text-lg font-semibold text-white">Directory registration</h2>
      </div>
      <p class="mt-2 text-sm text-white/40">Keep registration narrow: handle, display identity, country, core URL, contact, and brand signal.</p>
      <div class="mt-5 grid gap-3 md:grid-cols-2">
        {#each [
          ['bankHandle', 'Handle', 'nub'],
          ['displayName', 'Display name', 'NUB Bank'],
          ['country', 'Country code', 'LY'],
          ['coreUrl', 'Core URL', 'https://bank.example'],
          ['contactEmail', 'Contact email', 'ops@bank.ly'],
          ['brandColor', 'Brand color', '#07315F'],
          ['supportEmail', 'Support email', 'support@bank.ly'],
          ['website', 'Website', 'https://bank.ly']
        ] as [field, label, placeholder]}
          <label class="block">
            <span class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">{label}</span>
            <input bind:value={form[field]} placeholder={placeholder} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white outline-none transition-all focus:border-indigo-500/60" />
          </label>
        {/each}
      </div>
      <div class="mt-5 flex flex-wrap gap-2">
        <button onclick={registerBank} disabled={formLoading || !form.bankHandle || !form.displayName || !form.contactEmail || !form.coreUrl} class="rounded-xl bg-indigo-600 px-5 py-2.5 text-[13px] font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-40">
          {formLoading ? 'Registering...' : 'Register bank'}
        </button>
        <button onclick={resetRegistrationForm} class="rounded-xl border border-white/[0.1] px-5 py-2.5 text-[13px] font-semibold text-white/55 transition-all hover:border-white/[0.18] hover:text-white">
          Reset
        </button>
      </div>
    </div>

    <div class="identity-surface-card p-6">
      <h2 class="text-lg font-semibold text-white">Next steps</h2>
      <div class="identity-surface-soft mt-4 p-4 text-[12px] text-white/55">
        1. Open the dedicated bank desk.<br />
        2. Complete branding, support email, and website quality.<br />
        3. Review readiness and published directory posture.<br />
        4. Keep profile changes out of the registry page.
      </div>
    </div>
  </section>
</div>
