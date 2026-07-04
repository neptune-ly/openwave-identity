<script>
  import { onMount } from 'svelte';
  import { page } from '$app/state';
  import { toast } from 'svelte-sonner';
  import AlertTriangle from 'lucide-svelte/icons/alert-triangle';
  import CheckCircle2 from 'lucide-svelte/icons/check-circle-2';
  import LockKeyhole from 'lucide-svelte/icons/lock-keyhole';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import XCircle from 'lucide-svelte/icons/x-circle';
  import { getApi } from '$lib/api/client';

  let loading = $state(true);
  let deciding = $state(false);
  let request = $state(null);
  let errorText = $state('');

  const requestId = $derived(page.url.searchParams.get('request_id') || page.url.searchParams.get('requestId') || '');
  const scopes = $derived(request?.scopes ?? []);
  const pending = $derived(request?.status === 'PENDING');

  onMount(() => {
    loadRequest();
  });

  function errorMessage(error, fallback) {
    return error?.response?.data?.message || error?.response?.data?.error || error?.message || fallback;
  }

  async function loadRequest() {
    if (!requestId) {
      errorText = 'OAuth request is missing.';
      loading = false;
      return;
    }
    loading = true;
    try {
      const response = await getApi().get(`/oauth/consent-requests/${encodeURIComponent(requestId)}`);
      request = response.data;
      errorText = '';
    } catch (error) {
      errorText = errorMessage(error, 'Could not load OAuth request');
      toast.error(errorText);
    } finally {
      loading = false;
    }
  }

  async function decide(action) {
    if (!requestId) return;
    deciding = true;
    try {
      const response = await getApi().post(`/oauth/consent-requests/${encodeURIComponent(requestId)}/${action}`);
      const redirectUrl = response.data?.redirect_url;
      toast.success(action === 'approve' ? 'Access approved' : 'Access rejected');
      if (redirectUrl) window.location.href = redirectUrl;
      else await loadRequest();
    } catch (error) {
      toast.error(errorMessage(error, `Could not ${action} request`));
    } finally {
      deciding = false;
    }
  }
</script>

<svelte:head><title>OAuth Consent - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-6xl mx-auto space-y-5">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-5 lg:flex-row lg:items-end lg:justify-between">
      <div class="max-w-3xl">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Delegated OAuth</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">Review Access</h1>
        <p class="identity-section-note mt-2 text-sm text-white/55">Approve only the client, scope, and environment shown here. OpenWave Identity never asks for client secrets or bearer tokens on this page.</p>
      </div>
      <button onclick={loadRequest} disabled={loading || deciding} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">
        <RefreshCw class={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
        Refresh
      </button>
    </div>
  </section>

  {#if loading}
    <section class="identity-surface-card p-8 text-sm text-white/40">Loading request...</section>
  {:else if errorText}
    <section class="rounded-2xl border border-red-400/25 bg-red-400/10 p-5">
      <div class="flex items-start gap-3">
        <AlertTriangle class="mt-0.5 h-5 w-5 shrink-0 text-red-200" />
        <div>
          <div class="text-sm font-semibold text-red-100">Request unavailable</div>
          <p class="mt-1 text-sm text-red-100/70">{errorText}</p>
        </div>
      </div>
    </section>
  {:else if request}
    <div class="grid gap-5 xl:grid-cols-[0.72fr_0.28fr]">
      <section class="identity-surface-card overflow-hidden">
        <div class="border-b border-white/[0.06] px-5 py-4">
          <div class="flex items-center gap-2 text-sm font-semibold text-white">
            <ShieldCheck class="h-4 w-4 text-indigo-300" />
            Request
          </div>
          <p class="mt-1 text-sm text-white/45">Verify the requesting client and exact scope before continuing.</p>
        </div>

        <div class="grid gap-4 p-5 md:grid-cols-2">
          <div class="rounded-2xl border border-white/[0.07] bg-white/[0.025] p-4">
            <div class="text-xs text-white/35">Client</div>
            <div class="mt-2 text-lg font-semibold text-white">{request.client_name || request.client_id}</div>
            <div class="mt-1 font-mono text-xs text-white/35">{request.client_id}</div>
          </div>
          <div class="rounded-2xl border border-white/[0.07] bg-white/[0.025] p-4">
            <div class="text-xs text-white/35">Environment</div>
            <div class="mt-2 text-lg font-semibold text-white">{request.environment}</div>
            <div class="mt-1 text-xs text-white/35">Audience {request.audience}</div>
          </div>
          <div class="rounded-2xl border border-white/[0.07] bg-white/[0.025] p-4 md:col-span-2">
            <div class="text-xs text-white/35">Redirect URI</div>
            <div class="mt-2 break-all font-mono text-sm text-white/75">{request.redirect_uri}</div>
          </div>
          <div class="rounded-2xl border border-white/[0.07] bg-white/[0.025] p-4 md:col-span-2">
            <div class="text-xs text-white/35">Requested scopes</div>
            <div class="mt-3 flex flex-wrap gap-2">
              {#each scopes as scope}
                <span class="rounded-full border border-white/[0.08] bg-white/[0.025] px-2.5 py-1 font-mono text-[11px] text-white/65">{scope}</span>
              {/each}
            </div>
          </div>
        </div>
      </section>

      <aside class="identity-surface-card p-5">
        <div class="flex items-center gap-2 text-sm font-semibold text-white">
          <LockKeyhole class="h-4 w-4 text-indigo-300" />
          Decision
        </div>
        <p class="mt-2 text-sm text-white/45">Approval creates a revocable delegated grant and returns a one-time authorization code to the registered redirect URI.</p>

        <div class="mt-5 rounded-2xl border border-white/[0.07] bg-white/[0.025] p-4">
          <div class="text-xs text-white/35">Status</div>
          <div class="mt-2 text-lg font-semibold text-white">{request.status}</div>
          <div class="mt-1 text-xs text-white/35">Request {request.request_id}</div>
        </div>

        {#if pending}
          <div class="mt-5 grid gap-2">
            <button onclick={() => decide('approve')} disabled={deciding} class="inline-flex items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 py-3 text-sm font-semibold text-white transition-all hover:bg-emerald-500 disabled:opacity-40">
              <CheckCircle2 class="h-4 w-4" />
              Approve access
            </button>
            <button onclick={() => decide('reject')} disabled={deciding} class="identity-shell-button inline-flex items-center justify-center gap-2 rounded-xl border px-4 py-3 text-sm font-semibold text-white/70 transition-all hover:text-white disabled:opacity-40">
              <XCircle class="h-4 w-4" />
              Reject
            </button>
          </div>
        {:else}
          <div class="mt-5 rounded-2xl border border-amber-400/20 bg-amber-400/10 p-4 text-sm text-amber-100/80">This request is no longer pending.</div>
        {/if}
      </aside>
    </div>
  {/if}
</div>
