<script>
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { onMount, onDestroy } from 'svelte';
  import axios from 'axios';
  import { toast } from 'svelte-sonner';
  import { configuredRegistryUrl } from '$lib/config';
  import { theme } from '$lib/stores/theme';
  import Lock from 'lucide-svelte/icons/lock';
  import MailCheck from 'lucide-svelte/icons/mail-check';

  let baseUrl = $state(configuredRegistryUrl());
  let newPassword = $state('');
  let confirmPassword = $state('');
  let loading = $state(false);
  let errorMsg = $state('');
  let done = $state(false);
  let currentTheme = $state('light');

  const unsubTheme = theme.subscribe(t => currentTheme = t);
  onDestroy(unsubTheme);

  onMount(() => {
    theme.init();
    baseUrl = configuredRegistryUrl();
  });

  const login = $derived(page.url.searchParams.get('login') || '');
  const token = $derived(page.url.searchParams.get('token') || '');
  const canSubmit = $derived(
    !!login && !!token && !loading && newPassword.length >= 10 && newPassword === confirmPassword
  );

  async function submit() {
    errorMsg = '';
    if (!login || !token) {
      errorMsg = 'This reset link is missing required security data.';
      return;
    }
    if (newPassword.length < 10) {
      errorMsg = 'Use a password of at least 10 characters.';
      return;
    }
    if (newPassword !== confirmPassword) {
      errorMsg = 'Passwords do not match.';
      return;
    }
    loading = true;
    try {
      await axios.post(baseUrl + '/auth/password-reset/confirm', {
        usernameOrEmail: login,
        resetToken: token,
        newPassword,
      });
      done = true;
      toast.success('Password changed. Sign in with your new password.');
    } catch (e) {
      errorMsg = e.response?.data?.message || e.response?.data?.error || 'Invalid or expired reset link.';
    } finally {
      loading = false;
    }
  }
</script>

<svelte:head>
  <title>Reset Password - OpenWave Identity</title>
</svelte:head>

<div class="identity-reset ow-theme-root min-h-screen flex items-center justify-center p-8" data-theme={currentTheme}>
  <div class="w-full max-w-[400px]">
    <div class="mb-8">
      <div class="ow-logo-lockup mb-8">
        <div class="ow-logo-mark"><span>OW</span></div>
        <div>
          <div class="ow-logo-word">OW Identity</div>
          <div class="ow-logo-sub">NPT handle registry</div>
        </div>
      </div>
      <div class="flex items-center gap-3">
        {#if done}<MailCheck class="w-5 h-5" style="color:#10b981" />{:else}<Lock class="w-5 h-5" style="color:var(--reset-accent)" />{/if}
        <div>
          <h1 class="text-2xl font-semibold reset-title tracking-tight">{done ? 'Password changed' : 'Set new password'}</h1>
          <p class="reset-muted text-[13px] mt-1">
            {done ? 'You can now sign in with your new password.' : 'Use this one-time secure link to reset your Identity password.'}
          </p>
        </div>
      </div>
    </div>

    {#if errorMsg}
      <div class="mb-4 rounded-xl reset-error px-4 py-3 text-[13px]">{errorMsg}</div>
    {/if}

    {#if !login || !token}
      <div class="mb-4 rounded-xl reset-error px-4 py-3 text-[13px]">
        This reset link is invalid or incomplete. Request a new reset link from the sign-in page.
      </div>
      <button onclick={() => goto('/login')} class="reset-primary w-full py-3 text-[14px] font-semibold rounded-xl">Back to sign in</button>
    {:else if done}
      <button onclick={() => goto('/login')} class="reset-primary w-full py-3 text-[14px] font-semibold rounded-xl">Go to sign in</button>
    {:else}
      <div class="space-y-4">
        <div>
          <label for="identity-new-password" class="block text-[11px] font-medium reset-label mb-1.5 uppercase tracking-wider">New password</label>
          <input id="identity-new-password" type="password" bind:value={newPassword} class="reset-input w-full rounded-xl px-4 py-3 text-[13px] focus:outline-none transition-all" autocomplete="new-password"/>
        </div>
        <div>
          <label for="identity-confirm-password" class="block text-[11px] font-medium reset-label mb-1.5 uppercase tracking-wider">Confirm password</label>
          <input id="identity-confirm-password" type="password" bind:value={confirmPassword} class="reset-input w-full rounded-xl px-4 py-3 text-[13px] focus:outline-none transition-all" autocomplete="new-password"/>
        </div>
        <button onclick={submit} disabled={!canSubmit} class="reset-primary w-full py-3 text-[14px] font-semibold rounded-xl transition-all disabled:opacity-45 disabled:cursor-not-allowed">
          {loading ? 'Resetting...' : 'Reset password'}
        </button>
        <button onclick={() => goto('/login')} disabled={loading} class="reset-link w-full py-3 text-[13px] font-semibold rounded-xl transition-all">Back to sign in</button>
      </div>
    {/if}

    <p class="mt-5 text-[11px] reset-muted leading-relaxed">
      Reset links expire after 10 minutes and can be used only once.
    </p>
  </div>
</div>

<style>
  .identity-reset {
    min-height: 100dvh;
    background: var(--reset-bg);
    color: var(--reset-text);
    font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', sans-serif;
  }

  .identity-reset[data-theme='light'] {
    --reset-bg: #f5f7f8;
    --reset-text: #172033;
    --reset-muted: rgba(23, 32, 51, 0.62);
    --reset-soft: rgba(255, 255, 255, 0.92);
    --reset-border: rgba(15, 23, 42, 0.14);
    --reset-accent: #07315f;
    --reset-accent-hover: #0b447f;
    --reset-error-bg: #fff1f2;
    --reset-error-border: #fecdd3;
    --reset-error-text: #991b1b;
  }

  .identity-reset[data-theme='dark'] {
    --reset-bg: #050508;
    --reset-text: #f8fafc;
    --reset-muted: rgba(248, 250, 252, 0.58);
    --reset-soft: rgba(255, 255, 255, 0.055);
    --reset-border: rgba(255, 255, 255, 0.14);
    --reset-accent: #4f46e5;
    --reset-accent-hover: #6366f1;
    --reset-error-bg: rgba(239, 68, 68, 0.12);
    --reset-error-border: rgba(248, 113, 113, 0.36);
    --reset-error-text: #fecaca;
  }

  .reset-title { color: var(--reset-text); }
  .reset-muted { color: var(--reset-muted); }
  .reset-label { color: var(--reset-muted); }
  .reset-input {
    color: var(--reset-text);
    background: var(--reset-soft);
    border: 1px solid var(--reset-border);
  }

  .reset-input:focus {
    border-color: color-mix(in srgb, var(--reset-accent) 70%, transparent);
    box-shadow: 0 0 0 3px color-mix(in srgb, var(--reset-accent) 16%, transparent);
  }

  .reset-primary {
    color: #fff;
    background: var(--reset-accent);
  }

  .reset-primary:hover:not(:disabled) {
    background: var(--reset-accent-hover);
  }

  .reset-link {
    color: var(--reset-muted);
  }

  .reset-link:hover {
    color: var(--reset-text);
  }

  .reset-error {
    color: var(--reset-error-text);
    background: var(--reset-error-bg);
    border: 1px solid var(--reset-error-border);
  }
</style>
