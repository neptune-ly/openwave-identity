<script>
  import { onMount, onDestroy } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/state';
  import { get } from 'svelte/store';
  import { auth } from '$lib/stores/auth';
  import { theme } from '$lib/stores/theme';
  import { Button } from '$lib/components/ui/button/index.js';
  import { Card, CardContent, CardHeader, CardTitle } from '$lib/components/ui/card/index.js';
  import Moon from 'lucide-svelte/icons/moon';
  import Sun from 'lucide-svelte/icons/sun';

  let currentTheme = $state('light');
  const unsubTheme = theme.subscribe((value) => (currentTheme = value));

  onDestroy(unsubTheme);

  onMount(() => {
    theme.init();
    if (get(auth)?.role) {
      void goto('/portal', { replaceState: true });
      return;
    }
    const role = page.url.searchParams.get('role');
    if (role === 'customer' || role === 'bank' || role === 'admin') {
      const params = new URLSearchParams();
      const reason = page.url.searchParams.get('reason');
      if (reason) params.set('reason', reason);
      void goto(`/login/${role}${params.size ? `?${params}` : ''}`, { replaceState: true });
    }
  });

  const lanes = [
    { href: '/login/customer', title: 'Customer', copy: 'Manage your identity profile, linked accounts, and sign-in security.' },
    { href: '/login/bank', title: 'Bank portal', copy: 'Manage bank-vouched identity operations and login approvals.' },
    { href: '/login/admin', title: 'Registry admin', copy: 'Administer the OpenWave Identity registry and participating banks.' }
  ];
</script>

<svelte:head>
  <title>Choose sign in - OpenWave Identity</title>
  <meta name="description" content="Choose the OpenWave Identity sign-in that matches your account." />
</svelte:head>

<div class="identity-auth-shell ow-theme-root" data-theme={currentTheme}>
  <main class="identity-auth-frame mx-auto w-full max-w-[34rem]">
    <Card class="identity-auth-card border shadow-xl">
      <CardHeader class="space-y-2 pb-4">
        <div class="flex items-center justify-between gap-3">
          <div>
            <CardTitle class="identity-auth-title">OpenWave Identity</CardTitle>
            <p class="identity-auth-description mt-1 text-sm">Choose the sign-in for your account.</p>
          </div>
          <Button type="button" variant="ghost" size="icon" aria-label="Toggle theme" onclick={() => theme.toggle()}>
            {#if currentTheme === 'dark'}<Sun class="h-4 w-4" />{:else}<Moon class="h-4 w-4" />{/if}
          </Button>
        </div>
      </CardHeader>
      <CardContent class="identity-auth-form">
        <nav class="identity-auth-lanes" aria-label="Choose sign-in type">
          {#each lanes as lane}
            <a class="identity-auth-lane" href={lane.href}>
              <span>{lane.title}</span>
              <small>{lane.copy}</small>
            </a>
          {/each}
        </nav>
        <p class="identity-auth-description text-sm">Identity verifies the account role before any session is saved. If you choose the wrong page, you will be directed to the correct sign-in.</p>
      </CardContent>
    </Card>
  </main>
</div>
