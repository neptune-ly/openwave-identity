<script>
  import { onMount, onDestroy } from 'svelte';
  import { goto } from '$app/navigation';
  import { browser } from '$app/environment';
  import { auth } from '$lib/stores/auth';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import axios from 'axios';
  import { isTimeoutError, PORTAL_REQUEST_TIMEOUT_MS } from '$lib/api/client';
  import { configuredRegistryUrl } from '$lib/config';
  import { theme } from '$lib/stores/theme';
  import { Button } from '$lib/components/ui/button/index.js';
  import { Card, CardContent, CardHeader, CardTitle } from '$lib/components/ui/card/index.js';
  import { Input } from '$lib/components/ui/input/index.js';
  import { Label } from '$lib/components/ui/label/index.js';
  import Moon from 'lucide-svelte/icons/moon';
  import Sun from 'lucide-svelte/icons/sun';
  import KeyRound from 'lucide-svelte/icons/key-round';

  let { mode } = $props();

  let baseUrl   = $state(configuredRegistryUrl());
  let username  = $state('');
  let password  = $state('');
  let loading   = $state(false);
  let currentTheme = $state('light');
  let recoveryMode = $state(false);
  let recoverySent = $state(false);
  let passkeyAvailability = $state('checking');
  let totpRequired = $state(false);
  let totpCode = $state('');
  let totpChallengeId = $state('');
  let pendingSession = $state(null);
  let roleMismatch = $state(null);
  let bankApprovalRequired = $state(false);
  let bankApprovalStatus = $state('PENDING');
  let bankApprovalChallengeId = $state('');
  let bankApprovalStatusToken = $state('');
  let bankApprovalBanks = $state([]);
  let bankApprovalMessage = $state('');
  let bankApprovalIdentifierHint = $state('');
  let bankApprovalExpiresIn = $state(0);
  let bankApprovalChecking = $state(false);
  let bankApprovalPollError = $state('');
  let bankApprovalTimer = $state(null);
  let loginError = $state('');
  const portalHttp = axios.create({ timeout: PORTAL_REQUEST_TIMEOUT_MS });

  const unsubTheme = theme.subscribe((t) => (currentTheme = t));

  onDestroy(() => {
    unsubTheme();
    stopBankApprovalPolling();
  });

  onMount(() => {
    theme.init();
    if (browser) {
      baseUrl = configuredRegistryUrl();
      if (new URL(window.location.href).searchParams.get('reason') === 'session-expired') {
        loginError = 'Your portal session expired. Sign in again.';
      }
      passkeyAvailability = window.PublicKeyCredential && window.isSecureContext ? 'supported' : 'unsupported';
    }

    const s = get(auth);
    if (s?.role) goto('/portal');
  });

  function syncAutofilledCredentials(event) {
    const target = event?.currentTarget;
    const form = target?.tagName === 'FORM' ? target : target?.form;
    if (!form) return;
    const formData = new FormData(form);
    const formUsername = formData.get('username');
    const formPassword = formData.get('password');
    if (typeof formUsername === 'string') username = formUsername;
    if (typeof formPassword === 'string') password = formPassword;
  }

  async function connect(event) {
    if (loading) return;
    // Password managers can populate native input values without dispatching
    // the input events that update Svelte state. Read the submitted form first
    // so a visibly filled production form never leaves the button inert.
    syncAutofilledCredentials(event);
    loginError = '';
    if (!username.trim() || !password) {
      loginError = mode === 'customer'
        ? 'Enter your username, email, phone, or national ID and password.'
        : 'Enter your username and password.';
      return;
    }

    loading = true;
    roleMismatch = null;
    try {
      const r = await portalHttp.post(baseUrl + '/auth/login', {
        username: username.trim(),
        password,
        role: mode.toUpperCase()
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
          bankApprovalPollError = '';
          pendingSession = r.data;
          startBankApprovalPolling();
          toast.success('Open a linked bank app and approve this sign-in request.');
          return;
        }

        totpRequired = true;
        totpCode = '';
        totpChallengeId = r.data.challenge_id;
        pendingSession = r.data;
        toast.success('Enter the 6-digit authenticator code.');
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
        loginError = `This account belongs to the ${roleLabel(expectedRole)} sign-in.`;
      } else if (status === 401 || status === 403) {
        loginError = 'Invalid credentials. Check your details and try again.';
      } else if (isTimeoutError(e)) {
        loginError = 'The identity service took too long to respond. Your sign-in was not completed; retry safely.';
      } else if (!e.response) {
        loginError = 'We could not reach Identity. Check your connection and retry.';
      } else {
        loginError = e.response?.data?.message || 'Sign in failed. Please retry.';
      }
    } finally {
      loading = false;
    }
  }

  async function verifyTotp() {
    if (loading) return;
    if (!totpChallengeId || totpCode.trim().length < 6) {
      loginError = 'Enter the 6-digit authenticator code.';
      return;
    }
    loading = true;
    try {
      const r = await portalHttp.post(baseUrl + '/auth/login/totp/verify', {
        challengeId: totpChallengeId,
        code: totpCode.trim()
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
        bankApprovalPollError = '';
        pendingSession = r.data;
        startBankApprovalPolling();
        toast.success('Authenticator accepted. Finish in your linked bank app.');
        return;
      }

      if (finishSessionLogin(r.data, 'Connected')) clearTotpPrompt();
    } catch (e) {
      loginError = e.response?.data?.message || e.response?.data?.error || 'Invalid authenticator code.';
    } finally {
      loading = false;
    }
  }

  function finishSessionLogin(session, label) {
    if (session?.role !== mode.toUpperCase()) {
      const expectedRole = String(session?.role || '').toLowerCase();
      roleMismatch = { expectedRole, portalRole: session?.portalRole, username: session?.username || username.trim() };
      loginError = `This passkey or session belongs to the ${roleLabel(expectedRole)} sign-in. No session was saved.`;
      return false;
    }
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
    return true;
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
    bankApprovalChecking = false;
    bankApprovalPollError = '';
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

  function onTotpKey(event) {
    if (event.key === 'Enter') {
      event.preventDefault();
      verifyTotp();
    }
  }

  async function checkBankApprovalStatus() {
    if (!bankApprovalChallengeId || !bankApprovalStatusToken || bankApprovalChecking) return;
    bankApprovalChecking = true;
    try {
      const r = await portalHttp.get(baseUrl + `/auth/login/bank-approval/${bankApprovalChallengeId}`, {
        headers: {
          'X-OpenWave-Login-Status-Token': bankApprovalStatusToken
        }
      });
      bankApprovalStatus = r.data?.status || 'PENDING';
      bankApprovalExpiresIn = Number(r.data?.expires_in || 0);
      bankApprovalPollError = '';
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
      const terminalPollingError = e.response?.status === 403 || e.response?.status === 404;
      bankApprovalPollError = e.response?.data?.message || e.response?.data?.error || 'The registry could not be reached. Automatic retry is still active.';
      if (terminalPollingError) stopBankApprovalPolling();
    } finally {
      bankApprovalChecking = false;
    }
  }

  async function requestReset(event) {
    syncAutofilledCredentials(event);
    loginError = '';
    if (!username.trim()) {
      loginError = 'Enter your username or email first.';
      return;
    }
    loading = true;
    try {
      await portalHttp.post(baseUrl + '/auth/password-reset/request', { usernameOrEmail: username.trim() });
      recoverySent = true;
      toast.success('If the account has email configured, a secure reset link was sent.');
    } catch (e) {
      loginError = e.response?.data?.message || e.response?.data?.error || 'Could not send a reset link. Please retry.';
    } finally {
      loading = false;
    }
  }

  async function loginWithPasskey() {
    loginError = '';
    roleMismatch = null;
    if (passkeyAvailability !== 'supported') {
      loginError = 'Passkeys are available only on secure browsers that support WebAuthn.';
      return;
    }
    loading = true;
    try {
      const optionsResponse = await portalHttp.post(baseUrl + '/auth/passkey/options/authenticate', { role: mode.toUpperCase() });
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
      const r = await portalHttp.post(baseUrl + '/auth/passkey/authenticate', { ...payload, role: mode.toUpperCase() });
      finishSessionLogin(r.data, 'Connected with passkey');
    } catch (e) {
      if (e.response?.status === 409 && e.response?.data?.code === 'ROLE_MISMATCH') {
        const expectedRole = String(e.response?.data?.expectedRole || '').toLowerCase();
        roleMismatch = {
          expectedRole,
          portalRole: e.response?.data?.portalRole,
          username: e.response?.data?.username || ''
        };
        loginError = `This passkey belongs to the ${roleLabel(expectedRole)} sign-in. No session was saved.`;
      } else {
        loginError = e.response?.data?.message || e.response?.data?.error || e.message || 'Passkey sign in failed.';
      }
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
    bytes.forEach((b) => (binary += String.fromCharCode(b)));
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }

  function roleLabel(value) {
    if (value === 'admin') return 'Registry Admin';
    if (value === 'bank') return 'Bank Portal';
    if (value === 'customer') return 'Customer';
    return 'selected';
  }

  function bankApprovalStatusTone(status) {
    if (status === 'APPROVED') return 'identity-auth-alert identity-auth-alert--success';
    if (status === 'REJECTED' || status === 'EXPIRED') return 'identity-auth-alert identity-auth-alert--warn';
    return 'identity-auth-alert identity-auth-alert--info';
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
  <title>{roleLabel(mode)} sign in - OpenWave Identity</title>
  <meta name="description" content={`Secure ${roleLabel(mode)} sign in for OpenWave Identity.`} />
</svelte:head>

<div class="identity-auth-shell ow-theme-root" data-theme={currentTheme}>
  <main class="identity-auth-frame mx-auto w-full max-w-[30rem]">
    <Card class="identity-auth-card border shadow-xl">
      <CardHeader class="space-y-2 pb-4">
        <div class="flex items-center justify-between gap-3">
          <div>
            <CardTitle class="identity-auth-title">OpenWave Identity</CardTitle>
            <p class="identity-auth-description mt-1 text-sm">{roleLabel(mode)} sign in</p>
          </div>
          <Button type="button" variant="ghost" size="icon" aria-label="Toggle theme" onclick={() => theme.toggle()}>
            {#if currentTheme === 'dark'}<Sun class="h-4 w-4" />{:else}<Moon class="h-4 w-4" />{/if}
          </Button>
        </div>
      </CardHeader>

      <CardContent class="identity-auth-form">
        {#if loginError}
          <div class="identity-auth-alert identity-auth-alert--warn identity-auth-compact" role="alert">
            {loginError}
          </div>
        {/if}

        {#if recoveryMode}
          <form
            class="space-y-3.5"
            onsubmit={(event) => {
              event.preventDefault();
              void requestReset(event);
            }}
          >
            <Label for="identity-reset-username">Username or email</Label>
            <Input
              id="identity-reset-username"
              name="username"
              bind:value={username}
              autocomplete="username"
              disabled={loading}
              placeholder="Portal username or email"
            />
            {#if recoverySent}
              <div class="identity-auth-alert identity-auth-alert--success identity-auth-compact">
                Reset link has been sent if the account exists.
              </div>
            {/if}
            <Button type="submit" class="identity-primary-action w-full" disabled={loading || recoverySent}>
              {#if loading}Sending...{:else if recoverySent}Reset link sent{:else}Send reset link{/if}
            </Button>
            <Button
              type="button"
              variant="ghost"
              class="w-full"
              onclick={() => {
                recoveryMode = false;
                recoverySent = false;
              }}
            >
              Back to sign in
            </Button>
          </form>
        {:else}
          <form
            class="identity-auth-form"
            autocomplete="on"
            onsubmit={(event) => {
              event.preventDefault();
              if (totpRequired) verifyTotp();
              else connect(event);
            }}
          >
            {#if bankApprovalRequired}
              <div class={bankApprovalStatusTone(bankApprovalStatus)} role="status" aria-live="polite" aria-atomic="true">
                <p class="font-medium">{bankApprovalMessage}</p>
                <p class="mt-1 text-xs opacity-90">For: {bankApprovalIdentifierHint}</p>
              </div>

              <div class="identity-auth-alert identity-auth-alert--info identity-auth-compact space-y-2 text-xs" role="status" aria-live="polite" aria-atomic="true" aria-busy={bankApprovalChecking}>
                <p>Open one linked bank app and approve the request, then this page will continue automatically.</p>
                <p>Status: <span class="font-semibold">{bankApprovalStatusLabel(bankApprovalStatus)}</span> · Expires in {fmtCountdown(bankApprovalExpiresIn)}</p>
                {#if bankApprovalBanks.length}
                  <ul class="space-y-1">
                    {#each bankApprovalBanks as bank}
                      <li class="flex items-center justify-between">
                        <span>{bank.alias}</span>
                        <span class="opacity-70">{bank.isDefault ? 'Default route' : bank.bankHandle || 'Bank'}</span>
                      </li>
                    {/each}
                  </ul>
                {/if}
              </div>

              {#if bankApprovalPollError}
                <div class="identity-auth-alert identity-auth-alert--warn identity-auth-compact space-y-2 text-xs" role="alert">
                  <p>{bankApprovalPollError}</p>
                  <p>{bankApprovalTimer ? 'This page will retry automatically.' : 'Use retry or start a new sign-in request.'}</p>
                </div>
              {/if}

              <div class="grid gap-2">
                <Button type="button" class="min-h-12" onclick={checkBankApprovalStatus} disabled={loading || bankApprovalChecking}>
                  {bankApprovalChecking ? 'Checking...' : bankApprovalPollError ? 'Retry approval status' : 'Refresh approval status'}
                </Button>
                <Button type="button" class="min-h-12" variant="ghost" onclick={clearBankApprovalPrompt}>Use another sign-in method</Button>
              </div>
            {:else if totpRequired}
              <Label for="identity-totp">Authenticator code</Label>
              <Input
                id="identity-totp"
                name="totp"
                bind:value={totpCode}
                on:keydown={onTotpKey}
                autocomplete="one-time-code"
                inputmode="numeric"
                maxlength="6"
                class="font-mono"
                placeholder="123456"
              />
              <div class="grid gap-2">
                <Button type="button" class="identity-primary-action" onclick={verifyTotp} disabled={loading || totpCode.trim().length < 6}>
                  {#if loading}Verifying...{:else}Verify code{/if}
                </Button>
                <Button type="button" variant="ghost" onclick={clearTotpPrompt}>Use another method</Button>
              </div>
            {:else}
              <div class="space-y-2">
                <Label for="identity-username">{mode === 'customer' ? 'Username / Email / Phone / National ID' : 'Username'}</Label>
                <Input
                  id="identity-username"
                  name="username"
                  dir="auto"
                  bind:value={username}
                  autocomplete="username"
                  disabled={loading}
                  placeholder={mode === 'customer' ? 'username, email, phone, or national ID' : 'portal username'}
                />
              </div>

              {#if roleMismatch}
                <div class="identity-auth-alert identity-auth-alert--warn identity-auth-compact">
                  <p class="font-medium">This account belongs to {roleLabel(roleMismatch.expectedRole)}.</p>
                  {#if roleMismatch.expectedRole === 'admin' || roleMismatch.expectedRole === 'bank' || roleMismatch.expectedRole === 'customer'}
                    <a class="identity-auth-lane-link mt-2" href={`/login/${roleMismatch.expectedRole}`}>
                      Continue to {roleLabel(roleMismatch.expectedRole)} sign in
                    </a>
                  {/if}
                </div>
              {/if}

              <div class="space-y-2">
                <div class="flex items-center justify-between gap-3">
                  <Label for="identity-password">Password</Label>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onclick={() => {
                      recoveryMode = true;
                    }}
                  >
                    Forgot password?
                  </Button>
                </div>
                <Input
                  id="identity-password"
                  name="password"
                  type="password"
                  bind:value={password}
                  autocomplete="current-password"
                  disabled={loading}
                  placeholder="Portal password"
                />
              </div>

              <div class="grid gap-2">
                <Button type="submit" class="identity-primary-action w-full" disabled={loading}>
                  {#if loading}Connecting...{:else}Sign in as {roleLabel(mode)}{/if}
                </Button>
                {#if passkeyAvailability === 'supported'}
                  <Button
                    type="button"
                    variant="outline"
                    onclick={loginWithPasskey}
                    disabled={loading}
                    class="w-full"
                  >
                    <span class="inline-flex items-center gap-2"><KeyRound class="h-4 w-4" />Sign in with passkey</span>
                  </Button>
                {:else if passkeyAvailability === 'unsupported'}
                  <p class="identity-auth-method-note" role="status">
                    Passkeys are not available in this browser. Use your password or open this page in a secure passkey-capable browser.
                  </p>
                {/if}
                <a class="identity-auth-lane-link text-center" href="/login">Choose another sign-in</a>
              </div>
            {/if}
          </form>
        {/if}

      </CardContent>
    </Card>
  </main>
</div>
