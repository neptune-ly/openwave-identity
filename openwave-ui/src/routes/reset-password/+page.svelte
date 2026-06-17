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
  import ShieldCheck from 'lucide-svelte/icons/shield-check';

  let baseUrl = $state(configuredRegistryUrl());
  let newPassword = $state('');
  let confirmPassword = $state('');
  let loading = $state(false);
  let errorMsg = $state('');
  let done = $state(false);
  let currentTheme = $state('light');

  const unsubTheme = theme.subscribe((value) => currentTheme = value);
  onDestroy(unsubTheme);

  onMount(() => {
    theme.init();
    baseUrl = configuredRegistryUrl();
  });

  const login = $derived(page.url.searchParams.get('login') || '');
  const token = $derived(page.url.searchParams.get('token') || '');
  const canSubmit = $derived(Boolean(login) && Boolean(token) && !loading && newPassword.length >= 10 && newPassword === confirmPassword);

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
        newPassword
      });
      done = true;
      toast.success('Password changed. Sign in and finish your security setup.');
    } catch (error) {
      errorMsg = error.response?.data?.message || error.response?.data?.error || 'Invalid or expired reset link.';
    } finally {
      loading = false;
    }
  }

  function guidanceRows() {
    return [
      'Reset links are single-use and expire after 10 minutes.',
      'Portal credentials are separate from API keys and bank integration secrets.',
      'After reset, sign in again to add a passkey or enable authenticator codes.'
    ];
  }
</script>

<svelte:head>
  <title>Reset Password - OpenWave Identity</title>
</svelte:head>

<div class="identity-reset ow-theme-root min-h-screen" data-theme={currentTheme}>
  <div class="mx-auto grid min-h-screen max-w-6xl gap-0 lg:grid-cols-[minmax(0,0.95fr)_minmax(380px,0.75fr)]">
    <section class="reset-hero hidden lg:flex lg:flex-col lg:justify-between">
      <div>
        <div class="ow-logo-lockup">
          <div class="ow-logo-mark"><span>OW</span></div>
          <div>
            <div class="ow-logo-word">OW Identity</div>
            <div class="ow-logo-sub">NPT handle registry</div>
          </div>
        </div>
        <div class="mt-16 max-w-xl">
          <p class="text-[11px] uppercase tracking-[0.18em] reset-muted">Secure access recovery</p>
          <h1 class="mt-3 text-4xl font-semibold tracking-tight reset-title">Reset portal access without exposing identity data.</h1>
          <p class="mt-5 max-w-lg text-[15px] leading-7 reset-muted">
            Password recovery stays scoped to the user account on the signed link. The registry never reveals whether another identity, alias, or bank relationship exists.
          </p>
        </div>
      </div>

      <div class="space-y-3">
        {#each guidanceRows() as row}
          <div class="reset-guidance flex items-start gap-3 rounded-2xl px-4 py-4">
            <div class="mt-0.5 flex h-8 w-8 items-center justify-center rounded-xl reset-guidance-icon">
              <ShieldCheck class="w-4 h-4" />
            </div>
            <p class="text-sm leading-6 reset-muted">{row}</p>
          </div>
        {/each}
      </div>
    </section>

    <section class="flex items-center justify-center px-6 py-10 sm:px-8 lg:px-12">
      <div class="reset-card w-full max-w-[460px] rounded-[28px] border px-7 py-8 shadow-[0_28px_80px_rgba(0,0,0,0.18)] sm:px-8">
        <div class="mb-8 lg:hidden">
          <div class="ow-logo-lockup">
            <div class="ow-logo-mark"><span>OW</span></div>
            <div>
              <div class="ow-logo-word">OW Identity</div>
              <div class="ow-logo-sub">NPT handle registry</div>
            </div>
          </div>
        </div>

        <div class="flex items-start gap-3">
          <div class="reset-icon flex h-11 w-11 items-center justify-center rounded-2xl">
            {#if done}
              <MailCheck class="w-5 h-5" />
            {:else}
              <Lock class="w-5 h-5" />
            {/if}
          </div>
          <div>
            <p class="text-[11px] uppercase tracking-[0.18em] reset-muted">{done ? 'Password updated' : 'Password reset'}</p>
            <h2 class="mt-1 text-2xl font-semibold tracking-tight reset-title">{done ? 'Password changed' : 'Set new password'}</h2>
            <p class="mt-2 text-sm leading-6 reset-muted">
              {done
                ? 'You can now sign in with the new password for this Identity portal account and complete passkey or authenticator setup.'
                : 'Use the one-time secure link to define a new password for the current Identity portal account.'}
            </p>
          </div>
        </div>

        {#if errorMsg}
          <div class="reset-error mt-6 rounded-2xl px-4 py-3 text-[13px] leading-6">{errorMsg}</div>
        {/if}

        {#if !login || !token}
          <div class="reset-error mt-6 rounded-2xl px-4 py-3 text-[13px] leading-6">
            This reset link is invalid or incomplete. Request a new reset link from the sign-in page.
          </div>
          <button onclick={() => goto('/login')} class="reset-primary mt-6 w-full rounded-2xl py-3 text-[14px] font-semibold">Back to sign in</button>
        {:else if done}
          <button onclick={() => goto('/login')} class="reset-primary mt-6 w-full rounded-2xl py-3 text-[14px] font-semibold">Sign in and secure account</button>
        {:else}
          <div class="mt-6 space-y-4">
            <label class="block">
              <span class="mb-1.5 block text-[11px] font-medium uppercase tracking-[0.16em] reset-label">New password</span>
              <input id="identity-new-password" type="password" bind:value={newPassword} class="reset-input w-full rounded-2xl px-4 py-3 text-[14px] focus:outline-none transition-all" autocomplete="new-password" />
            </label>
            <label class="block">
              <span class="mb-1.5 block text-[11px] font-medium uppercase tracking-[0.16em] reset-label">Confirm password</span>
              <input id="identity-confirm-password" type="password" bind:value={confirmPassword} class="reset-input w-full rounded-2xl px-4 py-3 text-[14px] focus:outline-none transition-all" autocomplete="new-password" />
            </label>
            <button onclick={submit} disabled={!canSubmit} class="reset-primary w-full rounded-2xl py-3 text-[14px] font-semibold transition-all disabled:cursor-not-allowed disabled:opacity-45">
              {loading ? 'Resetting...' : 'Reset password'}
            </button>
            <button onclick={() => goto('/login')} disabled={loading} class="reset-link w-full rounded-2xl py-3 text-[13px] font-semibold transition-all">
              Back to sign in
            </button>
          </div>
        {/if}

        <div class="reset-foot mt-6 rounded-2xl px-4 py-3 text-[12px] leading-6">
          Reset links expire after 10 minutes and can be used only once.
        </div>
      </div>
    </section>
  </div>
</div>

<style>
  .identity-reset {
    background:
      radial-gradient(circle at top left, color-mix(in srgb, var(--reset-accent) 12%, transparent) 0, transparent 36%),
      var(--reset-bg);
    color: var(--reset-text);
    font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', sans-serif;
  }

  .identity-reset[data-theme='light'] {
    --reset-bg: #eff3f8;
    --reset-panel: rgba(255, 255, 255, 0.96);
    --reset-panel-2: rgba(255, 255, 255, 0.84);
    --reset-text: #162033;
    --reset-muted: rgba(22, 32, 51, 0.66);
    --reset-border: rgba(15, 23, 42, 0.12);
    --reset-input-bg: rgba(248, 250, 252, 0.96);
    --reset-accent: #07315f;
    --reset-accent-hover: #0b447f;
    --reset-accent-soft: rgba(7, 49, 95, 0.08);
    --reset-error-bg: #fff3f4;
    --reset-error-border: #fecdd3;
    --reset-error-text: #9f1239;
    --reset-foot-bg: rgba(15, 23, 42, 0.03);
  }

  .identity-reset[data-theme='dark'] {
    --reset-bg: #050508;
    --reset-panel: rgba(12, 14, 21, 0.92);
    --reset-panel-2: rgba(255, 255, 255, 0.035);
    --reset-text: #f8fafc;
    --reset-muted: rgba(248, 250, 252, 0.62);
    --reset-border: rgba(255, 255, 255, 0.1);
    --reset-input-bg: rgba(255, 255, 255, 0.05);
    --reset-accent: #4f46e5;
    --reset-accent-hover: #6366f1;
    --reset-accent-soft: rgba(99, 102, 241, 0.12);
    --reset-error-bg: rgba(239, 68, 68, 0.12);
    --reset-error-border: rgba(248, 113, 113, 0.36);
    --reset-error-text: #fecaca;
    --reset-foot-bg: rgba(255, 255, 255, 0.035);
  }

  .reset-hero {
    padding: 3rem 2.75rem;
    border-right: 1px solid var(--reset-border);
    background: linear-gradient(180deg, color-mix(in srgb, var(--reset-panel) 20%, transparent), transparent);
  }

  .reset-card {
    background: var(--reset-panel);
    border-color: var(--reset-border);
    backdrop-filter: blur(18px);
  }

  .reset-title {
    color: var(--reset-text);
  }

  .reset-muted,
  .reset-label {
    color: var(--reset-muted);
  }

  .reset-icon {
    background: var(--reset-accent-soft);
    color: var(--reset-accent);
    border: 1px solid color-mix(in srgb, var(--reset-accent) 18%, transparent);
  }

  .reset-input {
    color: var(--reset-text);
    background: var(--reset-input-bg);
    border: 1px solid var(--reset-border);
  }

  .reset-input:focus {
    border-color: color-mix(in srgb, var(--reset-accent) 72%, transparent);
    box-shadow: 0 0 0 4px color-mix(in srgb, var(--reset-accent) 16%, transparent);
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
    border: 1px solid var(--reset-border);
    background: var(--reset-panel-2);
  }

  .reset-link:hover:not(:disabled) {
    color: var(--reset-text);
  }

  .reset-error {
    color: var(--reset-error-text);
    background: var(--reset-error-bg);
    border: 1px solid var(--reset-error-border);
  }

  .reset-foot {
    color: var(--reset-muted);
    background: var(--reset-foot-bg);
    border: 1px solid var(--reset-border);
  }

  .reset-guidance {
    border: 1px solid var(--reset-border);
    background: var(--reset-panel-2);
  }

  .reset-guidance-icon {
    background: var(--reset-accent-soft);
    color: var(--reset-accent);
    border: 1px solid color-mix(in srgb, var(--reset-accent) 18%, transparent);
  }
</style>
