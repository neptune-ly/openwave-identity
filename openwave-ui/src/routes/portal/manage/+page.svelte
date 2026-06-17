<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import { auth } from '$lib/stores/auth';
  import { apiCall } from '$lib/api/client';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import AlertTriangle from 'lucide-svelte/icons/alert-triangle';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';
  import CircleCheckBig from 'lucide-svelte/icons/circle-check-big';
  import Route from 'lucide-svelte/icons/route';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';

  let session = $state(null);
  let delHandle = $state('');
  let delConfirm = $state('');
  let delLoading = $state(false);
  let reviewChecks = $state({
    routing: false,
    support: false,
    audit: false
  });

  function readDeleteFlow() {
    const current = get(page).url.searchParams;
    delHandle = current.get('handle') ?? '';
    delConfirm = '';
  }

  function deletePhase() {
    return get(page).url.searchParams.get('phase') === 'confirm' ? 'confirm' : 'draft';
  }

  async function syncDeleteFlow(phase = deletePhase()) {
    const next = new URL(get(page).url);
    if (delHandle.trim()) next.searchParams.set('handle', delHandle.trim());
    else next.searchParams.delete('handle');
    if (phase === 'confirm' && delHandle.trim()) next.searchParams.set('phase', 'confirm');
    else next.searchParams.delete('phase');
    await goto(`${next.pathname}${next.search}`, { replaceState: true, noScroll: true, keepFocus: true });
  }

  onMount(() => {
    session = get(auth);
    if (session?.role !== 'ADMIN') goto('/portal');
    readDeleteFlow();
  });

  function checksComplete() {
    return Object.values(reviewChecks).every(Boolean);
  }

  async function doDelete() {
    if (!checksComplete()) {
      toast.error('Complete the pre-delete review first');
      return;
    }
    if (delConfirm !== delHandle) {
      toast.error('Handle does not match');
      return;
    }
    delLoading = true;
    const r = await apiCall('delete', `/identity/${delHandle}`);
    delLoading = false;
    if (r.ok) {
      toast.success(`${delHandle} deleted`);
      delHandle = '';
      delConfirm = '';
      reviewChecks = { routing: false, support: false, audit: false };
      await syncDeleteFlow('draft');
    } else {
      toast.error(r.error);
    }
  }

  async function openDeleteConfirmation() {
    if (!delHandle.trim()) return;
    delConfirm = '';
    await syncDeleteFlow('confirm');
  }

  async function cancelDeleteFlow() {
    delConfirm = '';
    await syncDeleteFlow('draft');
  }
</script>

<svelte:head><title>Registry Corrections — OpenWave</title></svelte:head>

<div class="mx-auto max-w-5xl space-y-6 p-8">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
      <div>
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Registry Control</p>
        <h1 class="identity-page-title mt-1 text-2xl font-semibold tracking-tight">Registry Corrections</h1>
        <p class="identity-section-note mt-1 text-sm text-white/50">Reserved for irreversible registry actions. Keep normal customer routing work in Identity Operations and use this desk only for final correction steps.</p>
      </div>
      <div class="identity-role-accent w-fit">Last-resort registry actions only</div>
    </div>
  </section>

  <section class="grid gap-3 md:grid-cols-3">
    <a href="/portal/identity" class="identity-surface-card p-5 transition-all hover:bg-white/[0.04]">
      <div class="flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.05] text-indigo-300">
          <Route class="h-5 w-5" />
        </div>
        <div>
          <div class="text-sm font-semibold text-white">Identity Operations</div>
          <div class="mt-1 text-[12px] text-white/40">Use this when the issue is routing, default bank, or linked-account state.</div>
        </div>
      </div>
      <div class="mt-4 inline-flex items-center gap-1 text-[12px] text-indigo-200">
        Open identity desk
        <ArrowRight class="h-3.5 w-3.5" />
      </div>
    </a>

    <a href="/portal/banks" class="identity-surface-card p-5 transition-all hover:bg-white/[0.04]">
      <div class="flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.05] text-sky-300">
          <ShieldCheck class="h-5 w-5" />
        </div>
        <div>
          <div class="text-sm font-semibold text-white">Bank Directory</div>
          <div class="mt-1 text-[12px] text-white/40">Use this when the issue is bank profile quality, branding, or support contact.</div>
        </div>
      </div>
      <div class="mt-4 inline-flex items-center gap-1 text-[12px] text-sky-200">
        Open bank desk
        <ArrowRight class="h-3.5 w-3.5" />
      </div>
    </a>

    <a href="/portal/audit" class="identity-surface-card p-5 transition-all hover:bg-white/[0.04]">
      <div class="flex items-center gap-3">
        <div class="flex h-10 w-10 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.05] text-amber-300">
          <CircleCheckBig class="h-5 w-5" />
        </div>
        <div>
          <div class="text-sm font-semibold text-white">Audit Ledger</div>
          <div class="mt-1 text-[12px] text-white/40">Use this before deletion so the correction trail stays support-safe and reviewable.</div>
        </div>
      </div>
      <div class="mt-4 inline-flex items-center gap-1 text-[12px] text-amber-200">
        Open audit desk
        <ArrowRight class="h-3.5 w-3.5" />
      </div>
    </a>
  </section>

  <section class="identity-surface-card p-6">
    <div class="flex items-start gap-4">
      <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl border border-amber-500/20 bg-amber-500/10 text-amber-300">
        <AlertTriangle class="h-5 w-5" />
      </div>
      <div>
        <div class="text-sm font-semibold text-white">Deletion preflight</div>
        <div class="mt-1 text-[12px] text-white/45">Deleting a handle is not the normal way to fix routing or profile issues. Review the correction lanes below before you remove an identity.</div>
      </div>
    </div>

    <div class="mt-5 grid gap-3 md:grid-cols-3">
      <label class="identity-surface-soft flex gap-3 px-4 py-3 text-sm text-white/75">
        <input type="checkbox" class="checkbox checkbox-sm mt-0.5" bind:checked={reviewChecks.routing} />
        <span>I confirmed this is not just a default-route, linked-IBAN, or bank-assignment issue.</span>
      </label>
      <label class="identity-surface-soft flex gap-3 px-4 py-3 text-sm text-white/75">
        <input type="checkbox" class="checkbox checkbox-sm mt-0.5" bind:checked={reviewChecks.support} />
        <span>I confirmed no bank or support team still needs this handle for customer access or follow-up.</span>
      </label>
      <label class="identity-surface-soft flex gap-3 px-4 py-3 text-sm text-white/75">
        <input type="checkbox" class="checkbox checkbox-sm mt-0.5" bind:checked={reviewChecks.audit} />
        <span>I reviewed the audit trail or otherwise confirmed the deletion is still traceable.</span>
      </label>
    </div>
  </section>

  <section class="identity-surface-card border border-red-500/10 p-6">
    <div class="flex items-start gap-4 mb-6">
      <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl border border-red-500/20 bg-red-500/10 text-red-300">
        <AlertTriangle class="h-5 w-5" />
      </div>
      <div>
        <div class="text-sm font-semibold text-white">Danger zone</div>
        <div class="mt-0.5 text-[12px] text-white/45">This removes the registry identity and linked accounts together. Use it only when the identity record itself must disappear.</div>
      </div>
    </div>

    <div class="mb-6 grid gap-3 md:grid-cols-3">
      <div class="identity-surface-soft px-4 py-3 text-sm text-white/75">Delete only after confirming the handle is no longer needed for any bank route, customer access path, or audit follow-up.</div>
      <div class="identity-surface-soft px-4 py-3 text-sm text-white/75">If the problem is only wrong default routing or a bad linked IBAN, fix it from Identity Operations instead of deleting the whole identity.</div>
      <div class="identity-surface-soft px-4 py-3 text-sm text-white/75">Deleting a handle removes the registry identity and linked accounts together. Treat this as a last-resort correction.</div>
    </div>

    <div class="border-t border-white/[0.06] pt-5">
      <div class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <div class="text-[13px] font-medium text-white">Delete identity</div>
          <div class="mt-0.5 text-[12px] text-white/45">Type the handle exactly. This should happen only after the preflight review above is complete.</div>
        </div>
        <div class="flex flex-col gap-2 lg:items-end">
          <input
            bind:value={delHandle}
            placeholder="NPT handle"
            onblur={() => syncDeleteFlow(deletePhase())}
            class="w-48 rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2 text-[13px] text-white font-mono placeholder-white/20 transition-all focus:border-red-500/50 focus:outline-none"
          />
          <div class="text-[11px] text-white/30">Example: handle or `name@bank` route.</div>
        </div>
      </div>

      <div class="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-white/[0.06] bg-black/15 px-4 py-3">
        <div class="text-[12px] text-white/45">
          Preflight status:
          <span class={checksComplete() ? 'text-emerald-300' : 'text-amber-300'}>
            {Object.values(reviewChecks).filter(Boolean).length}/3 confirmed
          </span>
        </div>
        <button
          onclick={openDeleteConfirmation}
          disabled={!delHandle || !checksComplete()}
          class="rounded-xl bg-red-600/70 px-4 py-2 text-[13px] font-semibold text-white transition-all hover:bg-red-600 disabled:opacity-30"
        >
          Delete handle
        </button>
      </div>

      <div class="mt-3 text-[11px] text-white/30">This desk is intentionally narrow. All normal fixes should stay on the route, bank, or audit desks above.</div>

      {#if deletePhase() === 'confirm' && delHandle}
        <div class="mt-5 rounded-2xl border border-red-500/20 bg-red-500/10 p-5">
          <div class="flex items-start gap-4">
            <div class="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-red-500/20 bg-red-500/10 text-red-300">
              <AlertTriangle class="h-5 w-5" />
            </div>
            <div class="min-w-0 flex-1">
              <div class="text-sm font-semibold text-white">Confirm deletion for "{delHandle}"</div>
              <div class="mt-1 text-[13px] text-white/55">This stays on the same dedicated page so the operator can review the context without an overlay. Type the handle exactly to continue.</div>
              <input
                bind:value={delConfirm}
                placeholder={delHandle}
                class="mt-4 w-full max-w-sm rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] font-mono text-white placeholder-white/20 transition-all focus:border-red-500/50 focus:outline-none"
              />
              <div class="mt-4 flex flex-wrap gap-2">
                <button
                  onclick={doDelete}
                  disabled={delLoading || delConfirm !== delHandle || !checksComplete()}
                  class="rounded-xl bg-red-600 px-4 py-2.5 text-[13px] font-semibold text-white transition-all hover:bg-red-500 disabled:opacity-30"
                >
                  {delLoading ? 'Deleting…' : 'Confirm Delete'}
                </button>
                <button
                  onclick={cancelDeleteFlow}
                  class="rounded-xl border border-white/[0.1] px-4 py-2.5 text-[13px] font-semibold text-white/50 transition-all hover:border-white/[0.2] hover:text-white"
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      {/if}
    </div>
  </section>
</div>
