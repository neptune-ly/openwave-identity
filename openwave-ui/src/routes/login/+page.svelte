<script>
  import { onMount, onDestroy } from 'svelte';
  import { goto } from '$app/navigation';
  import { browser } from '$app/environment';
  import { auth } from '$lib/stores/auth';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import axios from 'axios';
  import { configuredRegistryUrl } from '$lib/config';
  import { theme } from '$lib/stores/theme';
  import Moon from 'lucide-svelte/icons/moon';
  import Sun from 'lucide-svelte/icons/sun';
  import KeyRound from 'lucide-svelte/icons/key-round';

  let baseUrl   = $state(configuredRegistryUrl());
  let username  = $state('');
  let password  = $state('');
  let loading   = $state(false);
  let mode      = $state('admin');
  let currentTheme = $state('light');
  let recoveryMode = $state(false);
  let recoverySent = $state(false);
  let passkeySupported = $state(false);

  const unsubTheme = theme.subscribe(t => currentTheme = t);
  onDestroy(unsubTheme);

  onMount(() => {
    theme.init();
    if (browser) baseUrl = configuredRegistryUrl();
    passkeySupported = browser && !!window.PublicKeyCredential && window.isSecureContext;
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
        role: mode === 'admin' ? 'ADMIN' : mode === 'customer' ? 'CUSTOMER' : 'BANK'
      });
      const session = r.data;
      if (session.role === 'ADMIN') {
        auth.loginAdmin(null, baseUrl, session.username, session.sessionToken, session.portalRole);
        toast.success('Connected as Registry Admin');
        goto('/portal');
      } else if (session.role === 'CUSTOMER') {
        auth.loginCustomer(baseUrl, session.username, session.sessionToken, session.portalRole);
        toast.success('Connected as Customer');
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

  async function requestReset() {
    if (!username.trim()) { toast.error('Enter your username or email first'); return; }
    loading = true;
    try {
      await axios.post(baseUrl + '/auth/password-reset/request', { usernameOrEmail: username.trim() });
      recoverySent = true;
      toast.success('If the account has email configured, a secure reset link was sent.');
    } catch (e) {
      toast.error(e.response?.data?.message || e.response?.data?.error || 'Could not send reset link');
    } finally {
      loading = false;
    }
  }

  async function loginWithPasskey() {
    if (!passkeySupported) { toast.error('Passkeys are available only on secure browsers that support WebAuthn.'); return; }
    loading = true;
    try {
      const optionsResponse = await axios.post(baseUrl + '/auth/passkey/options/authenticate', {});
      const requestOptions = JSON.parse(optionsResponse.data.options);
      requestOptions.publicKey.challenge = base64UrlToBuffer(requestOptions.publicKey.challenge);
      requestOptions.publicKey.allowCredentials = (requestOptions.publicKey.allowCredentials || []).map((cred) => ({
        ...cred,
        id: base64UrlToBuffer(cred.id)
      }));
      const credential = await navigator.credentials.get({ publicKey: requestOptions.publicKey });
      const response = credential.response;
      const payload = {
        challenge: bufferToBase64Url(requestOptions.publicKey.challenge),
        credential: JSON.stringify({
          id: credential.id,
          rawId: bufferToBase64Url(credential.rawId),
          type: credential.type,
          response: {
            authenticatorData: bufferToBase64Url(response.authenticatorData),
            clientDataJSON: bufferToBase64Url(response.clientDataJSON),
            signature: bufferToBase64Url(response.signature),
            userHandle: response.userHandle ? bufferToBase64Url(response.userHandle) : null
          },
          clientExtensionResults: credential.getClientExtensionResults()
        })
      };
      const r = await axios.post(baseUrl + '/auth/passkey/authenticate', payload);
      const session = r.data;
      if (session.role === 'ADMIN') {
        auth.loginAdmin(null, baseUrl, session.username, session.sessionToken, session.portalRole);
        toast.success('Connected with passkey');
      } else if (session.role === 'CUSTOMER') {
        auth.loginCustomer(baseUrl, session.username, session.sessionToken, session.portalRole);
        toast.success('Connected with passkey');
      } else {
        auth.loginBank(null, session.bankHandle || '', baseUrl, session.username, session.sessionToken, session.portalRole);
        toast.success('Connected with passkey');
      }
      goto('/portal');
    } catch (e) {
      toast.error(e.response?.data?.message || e.response?.data?.error || e.message || 'Passkey sign in failed');
    } finally {
      loading = false;
    }
  }

  function base64UrlToBuffer(value) {
    const base64 = value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=');
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes.buffer;
  }

  function bufferToBase64Url(value) {
    const bytes = new Uint8Array(value);
    let binary = '';
    bytes.forEach((b) => binary += String.fromCharCode(b));
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
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
            <h1 class="text-2xl font-semibold text-white tracking-tight">{recoveryMode ? 'Reset password' : 'Sign in'}</h1>
            <p class="text-white/40 text-[13px] mt-1">{recoveryMode ? 'Send a secure email link to reset your password' : 'Use your portal username and password'}</p>
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
      {#if !recoveryMode}
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
        <button
          onclick={() => mode = 'customer'}
          class="flex-1 py-2 rounded-lg text-[13px] font-medium transition-all
            {mode === 'customer' ? 'bg-sky-600 text-white shadow-sm' : 'text-white/40 hover:text-white/70'}">
          Customer
        </button>
      </div>
      {/if}

      <!-- Form -->
      {#if recoveryMode}
      <div class="space-y-4">
        <div>
          <label for="identity-reset-username" class="block text-[11px] font-medium text-white/40 mb-1.5 uppercase tracking-wider">Username or email</label>
          <input id="identity-reset-username" bind:value={username} class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-4 py-3 text-[13px] text-white placeholder-white/20 focus:outline-none focus:border-indigo-500/60 focus:bg-white/[0.07] transition-all" placeholder="Portal username or email"/>
        </div>
        {#if recoverySent}
          <div class="rounded-xl border border-emerald-400/20 bg-emerald-400/[0.08] px-4 py-3 text-[13px] leading-relaxed text-emerald-100/85">
            If the account exists, a one-time reset link has been emailed. Open the link to choose a new password.
          </div>
        {/if}
        <button onclick={requestReset} disabled={loading || !username.trim() || recoverySent} class="w-full py-3 text-[14px] font-semibold text-white rounded-xl transition-all disabled:opacity-30 disabled:cursor-not-allowed bg-indigo-600 hover:bg-indigo-500">
          {#if loading}<span class="inline-block w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin mr-2 align-middle"></span>{/if}
          {recoverySent ? 'Reset link sent' : 'Send secure reset link'}
        </button>
        <button onclick={() => { recoveryMode = false; recoverySent = false; }} class="w-full py-3 text-[13px] font-semibold text-white/50 hover:text-white rounded-xl transition-all">Back to sign in</button>
      </div>
      {:else}
      <div class="space-y-4">
        <div>
          <label for="identity-username" class="block text-[11px] font-medium text-white/40 mb-1.5 uppercase tracking-wider">Username</label>
          <input
            id="identity-username"
            bind:value={username}
            onkeydown={onKey}
            class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-4 py-3 text-[13px] text-white placeholder-white/20 focus:outline-none focus:border-indigo-500/60 focus:bg-white/[0.07] transition-all"
            placeholder="Portal username"
          />
        </div>
        <div>
          <div class="flex items-center justify-between gap-3 mb-1.5">
            <label for="identity-password" class="block text-[11px] font-medium text-white/40 uppercase tracking-wider">Password</label>
            <button onclick={() => { recoveryMode = true; }} class="text-[11px] font-semibold text-indigo-300 hover:text-indigo-200 transition-colors">
              Forgot password?
            </button>
          </div>
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
        <button
          onclick={loginWithPasskey}
          disabled={loading || !passkeySupported}
          class="w-full py-3 text-[14px] font-semibold rounded-xl transition-all disabled:opacity-30 disabled:cursor-not-allowed border border-white/[0.10] text-white/75 hover:bg-white/[0.06] flex items-center justify-center gap-2">
          <KeyRound class="w-4 h-4" />
          Sign in with passkey
        </button>
        <button onclick={() => { recoveryMode = true; }} class="w-full py-2 text-[12px] font-semibold text-white/45 hover:text-white transition-all">
          Send secure password reset link
        </button>
      </div>
      {/if}

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

      <p class="mt-4 text-[11px] text-white/25 leading-relaxed">
        Password reset links are short-lived, single-use, and sent only to the email on the account.
      </p>
    </div>
  </div>
</div>
