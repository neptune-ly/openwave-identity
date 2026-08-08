<script>
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { onMount, onDestroy } from 'svelte';
  import axios from 'axios';
  import { toast } from 'svelte-sonner';
  import { isTimeoutError, PORTAL_REQUEST_TIMEOUT_MS } from '$lib/api/client';
  import { configuredRegistryUrl } from '$lib/config';
  import { theme } from '$lib/stores/theme';
  import { Button } from '$lib/components/ui/button/index.js';
  import { Card, CardContent, CardHeader, CardTitle } from '$lib/components/ui/card/index.js';
  import { Input } from '$lib/components/ui/input/index.js';
  import { Label } from '$lib/components/ui/label/index.js';
  import AlertTriangle from 'lucide-svelte/icons/alert-triangle';

  let baseUrl = $state(configuredRegistryUrl());
  let newPassword = $state('');
  let confirmPassword = $state('');
  let loading = $state(false);
  let errorMsg = $state('');
  let done = $state(false);
  let currentTheme = $state('light');

  const unsubTheme = theme.subscribe((value) => (currentTheme = value));
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
      }, { timeout: PORTAL_REQUEST_TIMEOUT_MS });
      done = true;
      toast.success('Password changed. Sign in again to continue.');
    } catch (error) {
      errorMsg = isTimeoutError(error)
        ? 'The identity service took too long to respond. Your password was not confirmed; retry safely.'
        : error.response?.data?.message || error.response?.data?.error || 'Invalid or expired reset link.';
    } finally {
      loading = false;
    }
  }
</script>

<svelte:head>
  <title>Reset Password - OpenWave Identity</title>
</svelte:head>

<div class="identity-auth-shell ow-theme-root" data-theme={currentTheme}>
  <main class="identity-auth-frame mx-auto w-full max-w-md">
    <Card class="identity-auth-card w-full border">
      <CardHeader class="space-y-2">
        <CardTitle class="identity-auth-title">{done ? 'Password updated' : 'Set new password'}</CardTitle>
      </CardHeader>

      <CardContent class="identity-auth-form">
        {#if errorMsg}
          <div class="identity-auth-alert identity-auth-alert--warn identity-auth-compact">
            <span class="inline-flex items-center gap-2"><AlertTriangle class="h-4 w-4" />{errorMsg}</span>
          </div>
        {/if}

        {#if done}
          <Button class="w-full" on:click={() => goto('/login')}>Go to sign in</Button>
        {:else if !login || !token}
          <p class="identity-auth-alert identity-auth-alert--warn identity-auth-compact">
            This reset link is invalid or incomplete. Request a new link from the sign-in page.
          </p>
          <Button class="w-full" on:click={() => goto('/login')}>Back to sign in</Button>
        {:else}
          <div class="space-y-4">
            <div class="space-y-1.5">
              <Label for="identity-new-password">New password</Label>
              <Input
                id="identity-new-password"
                type="password"
                bind:value={newPassword}
                autocomplete="new-password"
                placeholder="At least 10 characters"
              />
            </div>
            <div class="space-y-1.5">
              <Label for="identity-confirm-password">Confirm password</Label>
              <Input
                id="identity-confirm-password"
                type="password"
                bind:value={confirmPassword}
                autocomplete="new-password"
                placeholder="Repeat new password"
              />
            </div>

            <Button class="w-full" on:click={submit} disabled={!canSubmit}>
              {loading ? 'Resetting...' : 'Reset password'}
            </Button>
            <Button class="w-full" variant="outline" on:click={() => goto('/login')} disabled={loading}>
              Back to sign in
            </Button>
          </div>
        {/if}

      </CardContent>
    </Card>
  </main>
</div>
