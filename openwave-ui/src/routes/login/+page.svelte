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
  let mode      = $state('');
  let currentTheme = $state('light');
  let recoveryMode = $state(false);
  let recoverySent = $state(false);
  let passkeySupported = $state(false);
  let totpRequired = $state(false);
  let totpCode = $state('');
  let totpChallengeId = $state('');
  let bankApprovalRequired = $state(false);
  let bankApprovalStatus = $state('PENDING');
  let bankApprovalChallengeId = $state('');
  let bankApprovalStatusToken = $state('');
  let bankApprovalBanks = $state([]);
  let bankApprovalMessage = $state('');
  let bankApprovalIdentifierHint = $state('');
  let pendingSession = $state(null);
  let roleMismatch = $state(null);
  let bankApprovalTimer = null;
  let bankApprovalExpiresIn = $state(0);

  const unsubTheme = theme.subscribe(t => currentTheme = t);
  onDestroy(() => {
    unsubTheme();
    stopBankApprovalPolling();
  });

  onMount(() => {
    theme.init();
    if (browser) baseUrl = configuredRegistryUrl();
    passkeySupported = browser && !!window.PublicKeyCredential && window.isSecureContext;
    if (browser) {
      const requestedRole = new URL(window.location.href).searchParams.get('role');
      if (requestedRole === 'admin' || requestedRole === 'bank' || requestedRole === 'customer') mode = requestedRole;
    }
    const s = get(auth);
    if (s?.role) goto('/portal');
  });

  async function connect() {
    if (loading) return;
    if (!mode) { toast.error('Choose the portal lane that matches this account'); return; }
    if (!username.trim() || !password) { toast.error(mode === 'customer' ? 'Enter your username, email, phone, or national ID and password' : 'Enter your username and password'); return; }

    loading = true;
    roleMismatch = null;
    try {
      const r = await axios.post(baseUrl + '/auth/login', {
        username: username.trim(),
        password,
        role: mode === 'admin' ? 'ADMIN' : mode === 'customer' ? 'CUSTOMER' : 'BANK'
      });
      if (r.status === 202 && r.data?.mfa_required) {
        if (r.data?.mfa_method === 'BANK_APP') {
          bankApprovalRequired = true;
          bankApprovalStatus = 'PENDING';
          bankApprovalChallengeId = r.data.challenge_id;
          bankApprovalStatusToken = r.data.status_token || '';
          bankApprovalBanks = r.data.banks || [];
          bankApprovalMessage = r.data.message || 'Approve this sign-in from one of your linked bank apps.';
          bankApprovalIdentifierHint = r.data.identifier_hint || username.trim();
          bankApprovalExpiresIn = Number(r.data.expires_in || 0);
          pendingSession = r.data;
          startBankApprovalPolling();
          toast.success('Open one of your linked bank apps and approve this sign-in.');
          return;
        }
        totpRequired = true;
        totpCode = '';
        totpChallengeId = r.data.challenge_id;
        pendingSession = r.data;
        toast.success('Enter the 6-digit authenticator code to finish sign in.');
        return;
      }
      finishSessionLogin(r.data, 'Connected');
    } catch (e) {
      const status = e.response?.status;
      if (status === 409 && e.response?.data?.code === 'ROLE_MISMATCH') {
        const expectedRole = (e.response?.data?.expectedRole || '').toLowerCase();
        roleMismatch = {
          expectedRole,
          portalRole: e.response?.data?.portalRole,
          username: e.response?.data?.username || username.trim()
        };
        if (expectedRole === 'admin' || expectedRole === 'bank' || expectedRole === 'customer') mode = expectedRole;
        toast.error(`This account belongs to the ${roleLabel(expectedRole)} lane.`);
      } else if (status === 401 || status === 403) {
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

  async function verifyTotp() {
    if (loading) return;
    if (!totpChallengeId || totpCode.trim().length < 6) {
      toast.error('Enter the 6-digit authenticator code');
      return;
    }
    loading = true;
    try {
      const r = await axios.post(baseUrl + '/auth/login/totp/verify', {
        challengeId: totpChallengeId,
        code: totpCode.trim(),
      });
      if (r.status === 202 && r.data?.mfa_required && r.data?.mfa_method === 'BANK_APP') {
        clearTotpPrompt();
        bankApprovalRequired = true;
        bankApprovalStatus = 'PENDING';
        bankApprovalChallengeId = r.data.challenge_id;
        bankApprovalStatusToken = r.data.status_token || '';
        bankApprovalBanks = r.data.banks || [];
        bankApprovalMessage = r.data.message || 'Approve this sign-in from one of your linked bank apps.';
        bankApprovalIdentifierHint = r.data.identifier_hint || username.trim();
        bankApprovalExpiresIn = Number(r.data.expires_in || 0);
        pendingSession = r.data;
        startBankApprovalPolling();
        toast.success('Authenticator accepted. Finish the sign-in from one of your linked bank apps.');
        return;
      }
      finishSessionLogin(r.data, 'Connected');
      clearTotpPrompt();
    } catch (e) {
      toast.error(e.response?.data?.message || e.response?.data?.error || 'Invalid authenticator code');
    } finally {
      loading = false;
    }
  }

  function finishSessionLogin(session, label) {
    if (session.role === 'ADMIN') {
      auth.loginAdmin(null, baseUrl, session.username, session.sessionToken, session.portalRole);
      toast.success(`${label} as Registry Admin`);
    } else if (session.role === 'CUSTOMER') {
      auth.loginCustomer(baseUrl, session.username, session.sessionToken, session.portalRole);
      toast.success(`${label} as Customer`);
    } else {
      auth.loginBank(null, session.bankHandle || '', baseUrl, session.username, session.sessionToken, session.portalRole);
      toast.success(`${label} as Bank`);
    }
    goto('/portal');
  }

  function clearTotpPrompt() {
    totpRequired = false;
    totpCode = '';
    totpChallengeId = '';
    pendingSession = null;
  }

  function clearBankApprovalPrompt() {
    bankApprovalRequired = false;
    bankApprovalStatus = 'PENDING';
    bankApprovalChallengeId = '';
    bankApprovalStatusToken = '';
    bankApprovalBanks = [];
    bankApprovalMessage = '';
    bankApprovalIdentifierHint = '';
    bankApprovalExpiresIn = 0;
    pendingSession = null;
    stopBankApprovalPolling();
  }

  function startBankApprovalPolling() {
    stopBankApprovalPolling();
    if (!bankApprovalChallengeId) return;
    bankApprovalTimer = window.setInterval(checkBankApprovalStatus, 3000);
    void checkBankApprovalStatus();
  }

  function stopBankApprovalPolling() {
    if (bankApprovalTimer) {
      clearInterval(bankApprovalTimer);
      bankApprovalTimer = null;
    }
  }

  async function checkBankApprovalStatus() {
    if (!bankApprovalChallengeId || !bankApprovalStatusToken) return;
    try {
      const r = await axios.get(baseUrl + `/auth/login/bank-approval/${bankApprovalChallengeId}`, {
        headers: {
          'X-OpenWave-Login-Status-Token': bankApprovalStatusToken
        }
      });
      bankApprovalStatus = r.data?.status || 'PENDING';
      bankApprovalExpiresIn = Number(r.data?.expires_in || 0);
      if (bankApprovalStatus === 'APPROVED' && r.data?.session?.sessionToken) {
        stopBankApprovalPolling();
        finishSessionLogin(r.data.session, 'Connected through bank approval');
        clearBankApprovalPrompt();
        return;
      }
      if (bankApprovalStatus === 'REJECTED' || bankApprovalStatus === 'EXPIRED') {
        stopBankApprovalPolling();
      }
    } catch (e) {
      stopBankApprovalPolling();
      toast.error(e.response?.data?.message || e.response?.data?.error || 'Could not verify the bank approval status');
    }
  }

  function onKey(e) {
    if (e.key === 'Enter') {
      if (totpRequired) verifyTotp();
      else connect();
    }
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
      finishSessionLogin(r.data, 'Connected with passkey');
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

  function roleLabel(value) {
    if (value === 'admin') return 'Registry Admin';
    if (value === 'bank') return 'Bank Portal';
    if (value === 'customer') return 'Customer';
    return 'selected';
  }

  function bankApprovalStatusTone(status) {
    if (status === 'APPROVED') return 'border-emerald-400/20 bg-emerald-400/[0.08] text-emerald-100/85';
    if (status === 'REJECTED' || status === 'EXPIRED') return 'border-amber-400/20 bg-amber-400/[0.08] text-amber-100/90';
    return 'border-sky-400/20 bg-sky-400/[0.08] text-sky-100/85';
  }

  function bankApprovalStatusLabel(status) {
    if (status === 'APPROVED') return 'Approved';
    if (status === 'REJECTED') return 'Rejected';
    if (status === 'EXPIRED') return 'Expired';
    return 'Waiting for bank approval';
  }

  function fmtCountdown(seconds) {
    const safe = Math.max(0, Number(seconds || 0));
    const minutes = Math.floor(safe / 60);
    const remainder = safe % 60;
    return `${minutes}:${String(remainder).padStart(2, '0')}`;
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
        Libya-scale digital identity<br/>for OpenWave customers
      </h2>
      <p class="mt-4 text-white/40 text-[14px] leading-relaxed">
        Manage bank-vouched NPT identity, linked payment routes, customer access, and bank-scoped enrollment for Libya’s interoperable payment network.
      </p>

      <!-- Feature pills -->
      <div class="mt-8 space-y-2.5">
        {#each [
          { label: 'Bank-vouched identity claims', dotClass: 'bg-indigo-400/60' },
          { label: 'Linked multi-bank payment routes', dotClass: 'bg-violet-400/60' },
          { label: 'Public alias resolution with gated access', dotClass: 'bg-emerald-400/60' },
          { label: 'Customer, bank, and registry lanes', dotClass: 'bg-sky-400/60' },
        ] as f}
          <div class="flex items-center gap-3">
            <div class={`w-1.5 h-1.5 rounded-full ${f.dotClass}`}></div>
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
            <p class="text-white/40 text-[13px] mt-1">{recoveryMode ? 'Send a secure email link to reset your password' : mode === 'customer' ? 'Use your NPT username, email, phone, or national ID with your password' : 'Use your portal username and password'}</p>
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
      {#if !recoveryMode && !totpRequired && !bankApprovalRequired}
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
      <p class="mb-6 text-[12px] text-white/35">Choose the portal lane first. The same username can only sign into the lane it belongs to.</p>
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
        {#if !totpRequired && !bankApprovalRequired}
          <div class="grid gap-2">
            {#if mode === 'customer'}
              <div class="rounded-xl border border-sky-400/15 bg-sky-400/[0.06] px-4 py-3">
                <div class="text-[11px] font-semibold uppercase tracking-wider text-sky-200/70">Customer sign-in paths</div>
                <div class="mt-2 grid gap-2 sm:grid-cols-2">
                  <div class="rounded-lg border border-white/[0.06] bg-black/20 px-3 py-2 text-[12px] text-white/70">
                    <div class="font-semibold text-white/85">Direct portal access</div>
                    <div class="mt-1">Use NPT username or email with password, then finish passkey or authenticator if enabled.</div>
                  </div>
                  <div class="rounded-lg border border-white/[0.06] bg-black/20 px-3 py-2 text-[12px] text-white/70">
                    <div class="font-semibold text-white/85">Public-identifier access</div>
                    <div class="mt-1">Phone number or national ID can start login, but one linked bank app still has to approve the request.</div>
                  </div>
                </div>
              </div>
            {:else if mode === 'bank'}
              <div class="rounded-xl border border-emerald-400/15 bg-emerald-400/[0.06] px-4 py-3 text-[12px] text-emerald-100/80">
                Bank users are scoped to one bank. Use this lane for customer enrollment, linked-account operations, approval queues, and bank reports.
              </div>
            {:else if mode === 'admin'}
              <div class="rounded-xl border border-indigo-400/15 bg-indigo-400/[0.06] px-4 py-3 text-[12px] text-indigo-100/80">
                Registry admins can manage banks, identities, audit trails, and cross-bank directory operations from one controlled portal lane.
              </div>
            {/if}
          </div>
        {/if}
        <div>
          <label for="identity-username" class="block text-[11px] font-medium text-white/40 mb-1.5 uppercase tracking-wider">{mode === 'customer' ? 'Username, email, phone, or national ID' : 'Username'}</label>
          <input
            id="identity-username"
            bind:value={username}
            onkeydown={onKey}
            disabled={totpRequired || bankApprovalRequired}
            class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-4 py-3 text-[13px] text-white placeholder-white/20 focus:outline-none focus:border-indigo-500/60 focus:bg-white/[0.07] transition-all"
            placeholder={mode === 'customer' ? 'NPT username, email, phone, or national ID' : 'Portal username'}
          />
        </div>
        {#if roleMismatch}
          <div class="rounded-xl border border-amber-400/20 bg-amber-400/[0.08] px-4 py-3 text-[13px] leading-relaxed text-amber-100/90">
            <div class="font-semibold">This account belongs to {roleLabel(roleMismatch.expectedRole)}.</div>
            <div class="mt-1 text-amber-100/70">The lane selector has been switched. Sign in again with the same username and password.</div>
          </div>
        {/if}
        {#if bankApprovalRequired}
          <div class={`rounded-xl border px-4 py-3 text-[13px] leading-relaxed ${bankApprovalStatusTone(bankApprovalStatus)}`}>
            {bankApprovalMessage} <span class="font-semibold">{bankApprovalIdentifierHint}</span>.
          </div>
          <div class="rounded-xl border border-white/[0.08] bg-white/[0.03] px-4 py-3">
            <div class="text-[11px] uppercase tracking-wider text-white/35">What happens next</div>
            <div class="mt-3 space-y-2 text-[12px] text-white/65">
              <div>1. Open one of the linked bank apps below.</div>
              <div>2. Review the OpenWave Identity sign-in request.</div>
              <div>3. Confirm it with the bank app’s normal security step.</div>
              <div>4. Return here. This page will keep checking for approval.</div>
            </div>
            <div class="mt-3 text-[11px] text-white/35">
              Username or email sign-in can finish directly after password. Phone and national-ID sign-in require linked-bank approval so the registry does not issue identity access from a reusable public identifier alone.
            </div>
          </div>
          <div class="rounded-xl border border-white/[0.08] bg-white/[0.03] px-4 py-3">
            <div class="text-[11px] uppercase tracking-wider text-white/35">Linked bank apps</div>
            <div class="mt-3 space-y-2">
              {#each bankApprovalBanks as bank}
                <div class="flex items-center justify-between rounded-lg border border-white/[0.06] bg-black/20 px-3 py-2 text-[13px] text-white/75">
                  <div>{bank.alias}</div>
                  <div class="text-white/35">{bank.isDefault ? 'Default route' : bank.bankHandle}</div>
                </div>
              {/each}
            </div>
            <div class="mt-3 flex items-center justify-between gap-3 text-[11px] text-white/35">
              <span>Status: {bankApprovalStatusLabel(bankApprovalStatus)}</span>
              <span>Expires in {fmtCountdown(bankApprovalExpiresIn)}</span>
            </div>
          </div>
          <button
            onclick={checkBankApprovalStatus}
            disabled={loading}
            class="w-full py-3 text-[14px] font-semibold text-white rounded-xl transition-all disabled:opacity-30 disabled:cursor-not-allowed mt-2 bg-sky-600 hover:bg-sky-500 shadow-[0_0_24px_rgba(14,165,233,0.25)]">
            Check approval status
          </button>
          <button onclick={clearBankApprovalPrompt} class="w-full py-2 text-[12px] font-semibold text-white/45 hover:text-white transition-all">
            Cancel and use another sign-in method
          </button>
        {:else if totpRequired}
          <div class="rounded-xl border border-indigo-400/20 bg-indigo-400/[0.08] px-4 py-3 text-[13px] leading-relaxed text-indigo-100/85">
            Authenticator verification is required for <span class="font-semibold">{pendingSession?.username || username}</span>.
          </div>
          <div>
            <label for="identity-totp" class="block text-[11px] font-medium text-white/40 uppercase tracking-wider mb-1.5">Authenticator code</label>
            <input
              id="identity-totp"
              bind:value={totpCode}
              onkeydown={onKey}
              inputmode="numeric"
              maxlength="6"
              class="w-full bg-white/[0.05] border border-white/[0.1] rounded-xl px-4 py-3 text-[13px] text-white placeholder-white/20 focus:outline-none focus:border-indigo-500/60 focus:bg-white/[0.07] transition-all font-mono tracking-[0.2em]"
              placeholder="123456"
            />
            <p class="text-[11px] text-white/25 mt-1.5">Use the 6-digit code from your authenticator app.</p>
          </div>
          <button
            onclick={verifyTotp}
            disabled={loading || totpCode.trim().length < 6}
            class="w-full py-3 text-[14px] font-semibold text-white rounded-xl transition-all disabled:opacity-30 disabled:cursor-not-allowed mt-2 bg-indigo-600 hover:bg-indigo-500 shadow-[0_0_24px_rgba(99,102,241,0.3)]">
            {#if loading}
              <span class="inline-block w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin mr-2 align-middle"></span>
              Verifying...
            {:else}
              Verify authenticator
            {/if}
          </button>
          <button onclick={clearTotpPrompt} class="w-full py-2 text-[12px] font-semibold text-white/45 hover:text-white transition-all">
            Use another sign-in method
          </button>
        {:else}
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
            disabled={loading || !mode || !username.trim() || !password}
            class="w-full py-3 text-[14px] font-semibold text-white rounded-xl transition-all disabled:opacity-30 disabled:cursor-not-allowed mt-2
              {mode === 'admin'
                ? 'bg-indigo-600 hover:bg-indigo-500 shadow-[0_0_24px_rgba(99,102,241,0.3)]'
                : mode === 'customer'
                  ? 'bg-sky-600 hover:bg-sky-500 shadow-[0_0_24px_rgba(14,165,233,0.25)]'
                  : mode === 'bank'
                    ? 'bg-emerald-600 hover:bg-emerald-500 shadow-[0_0_24px_rgba(16,185,129,0.25)]'
                    : 'bg-white/[0.08] text-white/45 shadow-none'}">
            {#if loading}
              <span class="inline-block w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin mr-2 align-middle"></span>
              Connecting...
            {:else}
              {mode ? `Connect to ${roleLabel(mode)}` : 'Choose a portal lane'}
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
        {/if}
      </div>
      {/if}

      <!-- Role description -->
      <div class="mt-6 rounded-xl border border-white/[0.06] bg-white/[0.02] px-4 py-3">
        {#if mode === 'admin'}
          <div class="text-[11px] text-white/30 leading-relaxed">
            <span class="text-indigo-400 font-medium">Admin access</span> - registry-wide control for banks, identities, audit, and digital-identity operations.
          </div>
        {:else if mode === 'customer'}
          <div class="text-[11px] text-white/30 leading-relaxed">
            <span class="text-sky-400 font-medium">Customer access</span> - manage your OpenWave identity, linked bank routes, and portal protection with passkeys or authenticator codes.
          </div>
        {:else if mode === 'bank'}
          <div class="text-[11px] text-white/30 leading-relaxed">
            <span class="text-emerald-400 font-medium">Bank access</span> - scoped to one bank for customer enrollment, linked-account maintenance, and bank-vouched identity approvals.
          </div>
        {:else}
          <div class="text-[11px] text-white/30 leading-relaxed">
            <span class="text-white/70 font-medium">Choose a portal lane</span> - customer for self-service, bank for scoped bank operations, registry admin for full directory control.
          </div>
        {/if}
      </div>

      <p class="mt-4 text-[11px] text-white/25 leading-relaxed">
        Password reset links are short-lived, single-use, and sent only to the email on the account.
      </p>
    </div>
  </div>
</div>
