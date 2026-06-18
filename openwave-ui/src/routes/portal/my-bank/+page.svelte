<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { apiCall } from '$lib/api/client';
  import { toast } from 'svelte-sonner';

  onMount(async () => {
    try {
      const response = await apiCall('get', '/banks/me');
      if (!response.ok || !response.data?.bankHandle) {
        const fallback = response.error || 'Could not load your bank desk.';
        toast.error(fallback);
        await goto('/portal/banks');
        return;
      }
      await goto(`/portal/banks/${encodeURIComponent(response.data.bankHandle)}`, { replaceState: true });
    } catch {
      toast.error('Could not resolve your bank identity. Opening bank directory.');
      await goto('/portal/banks');
    }
  });
</script>

<div class="mx-auto flex min-h-[45vh] max-w-7xl items-center justify-center p-12 text-sm text-white/65">
  <div class="identity-surface-soft space-y-3 rounded-3xl p-6 text-center">
    <div class="text-sm font-semibold">Opening your bank desk</div>
    <p class="text-white/60">Loading secure bank workspace and directory profile.</p>
  </div>
</div>
