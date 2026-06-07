<script>
  import { onMount } from 'svelte';
  import { getApi } from '$lib/api/client';
  import { toast } from 'svelte-sonner';

  let loading = $state(true);
  let profile = $state(null);

  onMount(loadCustomer);

  async function loadCustomer() {
    loading = true;
    try {
      const response = await getApi().get('/customer/aliases');
      profile = response.data;
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not load customer accounts');
    } finally {
      loading = false;
    }
  }
</script>

<svelte:head><title>My Accounts - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-5xl mx-auto space-y-5">
  <div class="flex items-end justify-between gap-4">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">My Accounts</h1>
      <p class="text-white/40 text-sm mt-1">Your NPT Identity aliases and linked bank accounts.</p>
    </div>
    <button onclick={loadCustomer} disabled={loading} class="px-4 py-2 text-[13px] font-medium text-white/45 hover:text-white border border-white/[0.1] rounded-xl transition-all">
      Refresh
    </button>
  </div>

  {#if loading}
    <div class="h-32 rounded-2xl bg-white/[0.03] animate-pulse"></div>
  {:else if profile}
    <section class="rounded-2xl border border-white/[0.07] bg-white/[0.03] p-6">
      <div class="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p class="text-[11px] uppercase tracking-wider text-white/30">NPT handle</p>
          <h2 class="mt-1 text-xl font-semibold">{profile.npt_handle}</h2>
          <p class="mt-1 text-sm text-white/45">{profile.display_name}</p>
        </div>
        <div class="rounded-xl border border-sky-500/20 bg-sky-500/10 px-3 py-2 text-xs text-sky-300">
          {profile.status}
        </div>
      </div>
      <div class="mt-5 grid gap-3 md:grid-cols-3">
        <div class="rounded-xl border border-white/[0.06] bg-white/[0.025] p-4">
          <p class="text-[11px] text-white/30">Default bank</p>
          <p class="mt-1 font-semibold">{profile.default_bank_handle || '-'}</p>
        </div>
        <div class="rounded-xl border border-white/[0.06] bg-white/[0.025] p-4">
          <p class="text-[11px] text-white/30">Phone</p>
          <p class="mt-1 font-mono text-sm">{profile.phone_masked || '-'}</p>
        </div>
        <div class="rounded-xl border border-white/[0.06] bg-white/[0.025] p-4">
          <p class="text-[11px] text-white/30">Email</p>
          <p class="mt-1 font-mono text-sm">{profile.email_masked || '-'}</p>
        </div>
      </div>
    </section>

    <section class="rounded-2xl border border-white/[0.07] bg-white/[0.03] overflow-hidden">
      <div class="grid grid-cols-[1fr_130px_150px_80px] gap-4 border-b border-white/[0.06] px-5 py-3 text-[11px] uppercase tracking-wider text-white/25">
        <span>Alias</span><span>Bank</span><span>Account</span><span>Default</span>
      </div>
      {#each profile.accounts || [] as account}
        <div class="grid grid-cols-[1fr_130px_150px_80px] gap-4 px-5 py-3.5 border-b border-white/[0.04] text-sm">
          <span class="font-mono">{account.alias}</span>
          <span>{account.bank_handle}</span>
          <span class="font-mono text-white/60">{account.iban_masked}</span>
          <span>{account.default ? 'Yes' : 'No'}</span>
        </div>
      {:else}
        <div class="p-8 text-center text-white/40">No linked accounts.</div>
      {/each}
    </section>
  {/if}
</div>
