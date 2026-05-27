<script>
  import { onMount, onDestroy } from 'svelte';
  import { goto } from '$app/navigation';
  import { browser } from '$app/environment';
  import { auth } from '$lib/stores/auth';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import axios from 'axios';
  import { configuredRegistryUrl, savedRegistryUrl, saveRegistryOverride } from '$lib/config';
  import { theme } from '$lib/stores/theme';
  import Moon from 'lucide-svelte/icons/moon';
  import Sun from 'lucide-svelte/icons/sun';

  let baseUrl   = $state(browser ? savedRegistryUrl() : configuredRegistryUrl());
  let username  = $state('');
  let password  = $state('');
  let loading   = $state(false);
  let mode      = $state('admin');
  let showEndpoint = $state(false);
  let currentTheme = $state('light');

  const unsubTheme = theme.subscribe(t => currentTheme = t);
  onDestroy(unsubTheme);

  $effect(() => { if (browser) baseUrl = saveRegistryOverride(baseUrl); });

  onMount(() => {
    theme.init();
    const s = get(auth);
    if (s?.role) goto('/portal');
  });

  async function connect() {
    if (loading) return;
    if (!username.trim() || !password) { toast.error('Enter your username and password'); return; }

    loading = true;
    try {
      const r = await axios.post(baseUrl + '/auth/login', {
        username: username.trim(),
        password,
        role: mode === 'admin' ? 'ADMIN' : 'BANK'
      });
      const session = r.data;
      if (session.role === 'ADMIN') {
        auth.loginAdmin(null, baseUrl, session.username, session.sessionToken, session.portalRole);
        toast.success('Connected as Registry Admin');
        goto('/portal');
      } else {
        auth.loginBank(null, session.bankHandle || '', baseUrl, session.username, session.sessionToken, session.portalRole);
        toast.success('Connected as Bank');
        goto('/portal');
      }
    } catch (e) {
      const status = e.response?.status;
      if (status === 401 || status === 403) {
        toast.error('Invalid credential — access denied');
      } else if (!e.response) {
        toast.error('Cannot reach registry endpoint');
      } else {
        toast.error(e.response?.data?.message || 'Sign in failed');
      }
    } finally {
      loading = false;
    }
  }

  function onKey(e) {
    if (e.key === 'Enter') connect();
  }
</script>

<svelte:head>
  <title>Sign In - OpenWave Identity Registry</title>
</svelte:head>

<div class="ow-theme-root min-h-screen bg-[#050508] flex relative overflow-hidden" data-theme={currentTheme} style="font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', sans-serif;">
  <!-- Ambient glow -->
  <div class="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[800px] h-[800px] rounded-full bg-indigo-600/[0.04] blur-[140px] pointer-events-none"></div>

  <!-- Left brand panel -->
  <div class="hidden lg:flex flex-col w-[440px] shrink-0 border-r border-white/[0.06] p-12 justify-between relative">
    <div class="absolute inset-0 bg-gradient-to-br from-indigo-950/30 via-transparent to-violet-950/20 pointer-events-none"></div>

    <!-- Logo -->
    <div class="ow-logo-lockup relative">
      <div class="ow-logo-mark"><span>OW</span></div>
      <div>
        <div class="ow-logo-word">OW Identity</div>
        <div class="ow-logo-sub">NPT handle registry</div>
      </div>
    </div>

    <!-- Hero text -->
    <div class="relative">
      <h2 class="text-3xl font-semibold text-white leading-tight tracking-tight">
        Global NPT Identity<br/>Registry
      </h2>
      <p class="mt-4 text-white/40 text-[14px] leading-relaxed">
        Manage NPT handle ownership, bank enrollment, and cross-gateway alias resolution for the OpenWave payment protocol.
      </p>

      <!-- Feature pills -->
      <div class="mt-8 space-y-2.5">
        {#each [
          { label: 'Bank-vouched identity claims', color: 'indigo' },
          { label: 'Multi-IBAN per handle', color: 'violet' },
          { label: 'Public alias resolution', color: 'emerald' },
          { label: 'Cross-gateway federation', color: 'sky' },
        ] as f}
          <div class="flex items-center gap-3">
            <div class="w-1.5 h-1.5 rounded-full bg-{f.color}-400/60"></div>
            <span class="text-[13px] text-white/40">{f.label}</span>
          </div>
        {/each}
      </div>
    </div>

    <div class="text-[11px] text-white/20 relative">
      © {new Date().getFullYear()} Neptune Fintech · OpenWave v1.0
    </div>
  </div>

  <!-- Right: login form -->
  <div class="flex-1 flex items-center justify-center p-8">
    <div class="w-full max-w-[380px]">

      <!-- Mobile logo -->
      <div class="lg:hidden ow-logo-lockup mb-10">
        <div class="ow-logo-mark"><span>OW</span></div>
        <div>
          <div class="ow-logo-word">OW Identity</div>
          <div class="ow-logo-sub">NPT handle registry</div>
        </div>
      </div>

      <div class="mb-8">
        <div class="flex items-start justify-between gap-4">
          <div>
            <h1 class="text-2xl font-semibold text-white tracking-tight">Sign in</h1>
            <p class="text-white/40 text-[13px] mt-1">Use your portal username and password</p>
          </div>
          <button
            onclick={() => theme.toggle()}
            class="w-9 h-9 rounded-xl border border-white/[0.08] bg-white/[0.04] hover:bg-white/[0.07] text-white/60 hover:text-white flex items-center justify-center transition-all"
            title="Toggle theme"
          >
            {#if currentTheme === 'dark'}<Sun class="w-4 h-4" />{:else}<Moon class="w-4 h-4" />{/if}
          </button>
        </div>
      </div>

      <!-- Mode toggle -->
      <div class="flex rounded-xl bg-white/[0.04] border border-white/[0.08] p-1 mb-6">
        <button
          onclick={() => mode = 'admin'}
          class="flex-1 py-2 rounded-lg text-[13px] font-medium transition-all
            {mode === 'admin' ? 'bg-indigo-600 text-white shadow-sm' : 'text-white/40 hover:text-white/70'}">
          Registry Admin
        </button>
        <button
          onclick={() => mode = 'bank'}
          class="flex-1 py-2 rounded-lg text-[13px] font-medium transition-all
            {mode === 'bank' ? 'bg-emerald-600 text-white shadow-sm' : 'text-white/40 hover:text-white/70'}">
          Bank Portal
        </button>
      </div>

      <!-- Form -->
      <div class="space-y-4">
        <div>
          <label for="identity-username" class="block text-[11px] font-medium text-white/40 mb-1.5 uppercase tracking-wider">Username</label>
          <input
            id="identity-username"
            bind:value={username}
            onkeydown={onKey}
            class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-4 py-3 text-[13px] text-white placeholder-white/20 focus:outline-none focus:border-indigo-500/60 focus:bg-white/[0.07] transition-all"
            placeholder={mode === 'admin' ? 'ow_admin' : 'andalus_admin'}
          />
        </div>
        <div>
          <label for="identity-password" class="block text-[11px] font-medium text-white/40 mb-1.5 uppercase tracking-wider">Password</label>
          <input
            id="identity-password"
            type="password"
            bind:value={password}
            onkeydown={onKey}
            class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-4 py-3 text-[13px] text-white placeholder-white/20 focus:outline-none focus:border-indigo-500/60 focus:bg-white/[0.07] transition-all"
            placeholder="Portal password"
          />
          <p class="text-[11px] text-white/25 mt-1.5">System API keys remain available for integrations, but portal access uses user credentials.</p>
        </div>

        <button
          onclick={connect}
          disabled={loading || !username.trim() || !password}
          class="w-full py-3 text-[14px] font-semibold text-white rounded-xl transition-all disabled:opacity-30 disabled:cursor-not-allowed mt-2
            {mode === 'admin'
              ? 'bg-indigo-600 hover:bg-indigo-500 shadow-[0_0_24px_rgba(99,102,241,0.3)]'
              : 'bg-emerald-600 hover:bg-emerald-500 shadow-[0_0_24px_rgba(16,185,129,0.25)]'}">
          {#if loading}
            <span class="inline-block w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin mr-2 align-middle"></span>
            Connecting...
          {:else}
            Connect to Registry
          {/if}
        </button>
      </div>

      <!-- Role description -->
      <div class="mt-6 rounded-xl border border-white/[0.06] bg-white/[0.02] px-4 py-3">
        {#if mode === 'admin'}
          <div class="text-[11px] text-white/30 leading-relaxed">
            <span class="text-indigo-400 font-medium">Admin access</span> - full registry control: register banks, manage identities, view all accounts, delete handles.
          </div>
        {:else}
          <div class="text-[11px] text-white/30 leading-relaxed">
            <span class="text-emerald-400 font-medium">Bank access</span> - scoped to your bank: claim handles for customers, link/unlink IBANs, manage your bank's accounts.
          </div>
        {/if}
      </div>

      <div class="mt-4 rounded-xl border border-white/[0.06] bg-white/[0.015]">
        <button
          onclick={() => showEndpoint = !showEndpoint}
          class="w-full flex items-center justify-between px-4 py-3 text-[11px] text-white/30 hover:text-white/50 transition-colors"
        >
          <span>Registry endpoint</span>
          <span class="font-mono">{showEndpoint ? 'Hide' : 'Advanced'}</span>
        </button>
        {#if showEndpoint}
          <div class="px-4 pb-4">
            <input
              bind:value={baseUrl}
              onkeydown={onKey}
              class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-4 py-3 text-[13px] text-white font-mono placeholder-white/20 focus:outline-none focus:border-indigo-500/60 focus:bg-white/[0.07] transition-all"
              placeholder="/v1"
            />
            <p class="mt-2 text-[11px] text-white/25">
              Default is deployment-configured. Use this only for local development or support diagnostics.
            </p>
          </div>
        {/if}
      </div>
    </div>
  </div>
</div>
