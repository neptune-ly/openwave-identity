<script>
  import '../app.css';
  import { onMount } from 'svelte';
  import { Toaster } from 'svelte-sonner';
  import { theme } from '$lib/stores/theme';
  let { children } = $props();
  let booting = $state(true);

  onMount(() => {
    theme.init();
    booting = false;
  });
</script>

<Toaster richColors position="top-right" />
{#if booting}
  <div class="neptune-splash fixed inset-0 z-[9999]" aria-label="OW Identity loading">
    <div class="neptune-signal-mark" aria-hidden="true">
      <span class="neptune-signal-core">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor">
          <path d="M12 3l7 5v8l-7 5-7-5V8l7-5z" />
          <path d="M8 9l4 3 4-3" />
          <path d="M8 15l4-3 4 3" />
        </svg>
      </span>
      <span class="neptune-signal-dot"></span>
    </div>
    <div class="neptune-splash-copy">
      <b>OW Identity</b>
      <span>Neptune-built NPT registry</span>
    </div>
  </div>
{/if}
{@render children()}
