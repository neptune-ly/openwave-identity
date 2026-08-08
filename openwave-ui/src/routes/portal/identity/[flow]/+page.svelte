<script>
  import { browser } from '$app/environment';
  import { goto, replaceState } from '$app/navigation';
  import { page as appPage } from '$app/state';
  import { onDestroy, onMount, tick } from 'svelte';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import { apiCall, apiPublic } from '$lib/api/client';
  import { auth } from '$lib/stores/auth';
  import ArrowLeft from 'lucide-svelte/icons/arrow-left';
  import ArrowRight from 'lucide-svelte/icons/arrow-right';
  import BadgeInfo from 'lucide-svelte/icons/badge-info';
  import Building2 from 'lucide-svelte/icons/building-2';
  import CircleAlert from 'lucide-svelte/icons/circle-alert';
  import Link2 from 'lucide-svelte/icons/link-2';
  import Route from 'lucide-svelte/icons/route';
  import Unlink2 from 'lucide-svelte/icons/unlink-2';
  import UserPlus from 'lucide-svelte/icons/user-plus';
  import PencilLine from 'lucide-svelte/icons/pencil-line';
  import CheckCircle2 from 'lucide-svelte/icons/circle-check-big';
  import LoaderCircle from 'lucide-svelte/icons/loader-circle';

  const flowConfig = {
    claim: {
      title: 'Claim Handle',
      eyebrow: 'Customer enrollment desk',
      description: 'Create a bank-vouched identity record and establish the first active route.',
      submitLabel: 'Claim handle',
      icon: UserPlus,
      accent: 'text-indigo-300',
      queryKey: 'claim_handle'
    },
    rename: {
      title: 'Rename NPT Handle',
      eyebrow: 'Customer-directed recovery desk',
      description: 'Verify a customer-requested name change and permanently retire the previous payment address.',
      submitLabel: 'Review permanent rename',
      icon: PencilLine,
      accent: 'text-cyan-300',
      queryKey: 'rename_current'
    },
    link: {
      title: 'Link Account',
      eyebrow: 'Account linking desk',
      description: 'Attach another IBAN to the selected identity without mixing in other routing actions.',
      submitLabel: 'Link IBAN',
      icon: Link2,
      accent: 'text-emerald-300',
      queryKey: 'link_handle'
    },
    unlink: {
      title: 'Unlink Account',
      eyebrow: 'Route removal desk',
      description: 'Remove an outdated or invalid route from the selected identity record.',
      submitLabel: 'Unlink IBAN',
      icon: Unlink2,
      accent: 'text-rose-300',
      queryKey: 'unlink_handle'
    },
    'default-account': {
      title: 'Default IBAN',
      eyebrow: 'Route priority desk',
      description: 'Choose which IBAN resolves for a bank-specific alias route.',
      submitLabel: 'Set default IBAN',
      icon: Route,
      accent: 'text-amber-300',
      queryKey: 'default_handle'
    },
    'default-bank': {
      title: 'Default Bank',
      eyebrow: 'Network routing desk',
      description: 'Choose which bank resolves a bare NPT handle when no bank suffix is provided.',
      submitLabel: 'Set default bank',
      icon: Building2,
      accent: 'text-sky-300',
      queryKey: 'default_bank_handle'
    }
  };

  const flowOrder = ['claim', 'rename', 'link', 'unlink', 'default-account', 'default-bank'];

  let session = $state(null);
  let banks = $state([]);
  let flow = $state(null);
  let loadingBanks = $state(false);
  let hydrated = $state(false);
  let routerReady = $state(false);
  let routerReadyFrame;

  let enroll = $state({
    nptHandle: '',
    iban: '',
    customerDisplayName: '',
    bankCustomerRef: '',
    nationalId: '',
    phone: '',
    customerEmail: '',
    setAsDefault: true
  });
  let enrollResult = $state(null);
  let enrollLoading = $state(false);

  let renameCurrentHandle = $state('');
  let renameNewHandle = $state('');
  let renameNationalId = $state('');
  let renameAvailability = $state('idle');
  let renameAvailabilityMessage = $state('Enter a new handle to check it.');
  let renameChecking = $state(false);
  let renameConfirming = $state(false);
  let renameCustomerConfirmed = $state(false);
  let renameSubmitting = $state(false);
  let renameError = $state('');
  let renameResult = $state(null);
  let renameStatusRegion = $state(null);
  let renameConfirmationHeading = $state(null);
  let renameAvailabilityTimer;
  let renameAvailabilityRequest = 0;

  let linkHandle = $state('');
  let linkIban = $state('');
  let linkBankCustomerRef = $state('');
  let linkDefault = $state(false);
  let linkLoading = $state(false);

  let unlinkHandle = $state('');
  let unlinkIban = $state('');
  let unlinkBankHandle = $state('');
  let unlinkLoading = $state(false);

  let defHandle = $state('');
  let defIban = $state('');
  let defAccountBankHandle = $state('');
  let defLoading = $state(false);

  let defBankHandle = $state('');
  let defBankSelected = $state('');
  let defBankLoading = $state(false);

  const isBank = $derived(session?.role === 'BANK');
  const current = $derived(flow ? flowConfig[flow] : null);
  const currentBankHandle = $derived(session?.bankHandle || '');
  const canonicalRenameHandle = $derived(renameNewHandle.trim().toLowerCase());
  const renameAddressPreview = $derived(
    canonicalRenameHandle && currentBankHandle
      ? `${canonicalRenameHandle}@${currentBankHandle}`
      : canonicalRenameHandle
  );

  function resolveFlow() {
    const value = appPage.params.flow;
    return value && flowConfig[value] ? value : null;
  }

  async function loadBanks() {
    if (banks.length || loadingBanks) return;
    loadingBanks = true;
    const response = await apiPublic('/banks');
    if (response.ok) banks = response.data.banks || response.data || [];
    loadingBanks = false;
  }

  function applyPrefillsFromQuery() {
    const params = appPage.url.searchParams;
    enroll.nptHandle = params.get('claim_handle') || enroll.nptHandle;
    renameCurrentHandle = params.get('rename_current') || renameCurrentHandle;
    renameNewHandle = params.get('rename_new') || renameNewHandle;
    linkHandle = params.get('link_handle') || linkHandle;
    unlinkHandle = params.get('unlink_handle') || unlinkHandle;
    defHandle = params.get('default_handle') || defHandle;
    defBankHandle = params.get('default_bank_handle') || defBankHandle;
    linkBankCustomerRef = params.get('link_customer_ref') || linkBankCustomerRef;
    unlinkBankHandle = params.get('unlink_bank_handle') || unlinkBankHandle || currentBankHandle;
    defAccountBankHandle = params.get('default_account_bank_handle') || defAccountBankHandle || currentBankHandle;
  }

  function syncQuery() {
    if (!browser || !hydrated || !routerReady || !flow) return;
    const params = new URLSearchParams(window.location.search);
    ['claim_handle', 'rename_current', 'rename_new', 'link_handle', 'unlink_handle', 'default_handle', 'default_bank_handle', 'link_customer_ref', 'unlink_bank_handle', 'default_account_bank_handle'].forEach((key) => params.delete(key));

    if (flow === 'claim' && enroll.nptHandle.trim()) params.set('claim_handle', enroll.nptHandle.trim());
    if (flow === 'rename' && renameCurrentHandle.trim()) params.set('rename_current', renameCurrentHandle.trim());
    if (flow === 'rename' && canonicalRenameHandle) params.set('rename_new', canonicalRenameHandle);
    if (flow === 'link' && linkHandle.trim()) params.set('link_handle', linkHandle.trim());
    if (flow === 'link' && linkBankCustomerRef.trim()) params.set('link_customer_ref', linkBankCustomerRef.trim());
    if (flow === 'unlink' && unlinkHandle.trim()) params.set('unlink_handle', unlinkHandle.trim());
    if (flow === 'unlink' && unlinkBankHandle.trim()) params.set('unlink_bank_handle', unlinkBankHandle.trim());
    if (flow === 'default-account' && defHandle.trim()) params.set('default_handle', defHandle.trim());
    if (flow === 'default-account' && defAccountBankHandle.trim()) params.set('default_account_bank_handle', defAccountBankHandle.trim());
    if (flow === 'default-bank' && defBankHandle.trim()) params.set('default_bank_handle', defBankHandle.trim());

    const query = params.toString();
    const nextUrl = `${window.location.pathname}${query ? `?${query}` : ''}`;
    replaceState(nextUrl, appPage.state);
  }

  async function openFlow(nextFlow) {
    const params = new URLSearchParams();
    if (nextFlow === 'claim' && enroll.nptHandle.trim()) params.set('claim_handle', enroll.nptHandle.trim());
    if (nextFlow === 'rename' && (renameCurrentHandle.trim() || enroll.nptHandle.trim())) params.set('rename_current', renameCurrentHandle.trim() || enroll.nptHandle.trim());
    if (nextFlow === 'rename' && canonicalRenameHandle) params.set('rename_new', canonicalRenameHandle);
    if (nextFlow === 'link' && (linkHandle.trim() || enroll.nptHandle.trim())) params.set('link_handle', linkHandle.trim() || enroll.nptHandle.trim());
    if (nextFlow === 'link' && linkBankCustomerRef.trim()) params.set('link_customer_ref', linkBankCustomerRef.trim());
    if (nextFlow === 'unlink' && (unlinkHandle.trim() || linkHandle.trim() || enroll.nptHandle.trim())) params.set('unlink_handle', unlinkHandle.trim() || linkHandle.trim() || enroll.nptHandle.trim());
    if (nextFlow === 'unlink' && unlinkBankHandle.trim()) params.set('unlink_bank_handle', unlinkBankHandle.trim());
    if (nextFlow === 'default-account' && (defHandle.trim() || unlinkHandle.trim() || linkHandle.trim() || enroll.nptHandle.trim())) params.set('default_handle', defHandle.trim() || unlinkHandle.trim() || linkHandle.trim() || enroll.nptHandle.trim());
    if (nextFlow === 'default-account' && defAccountBankHandle.trim()) params.set('default_account_bank_handle', defAccountBankHandle.trim());
    if (nextFlow === 'default-bank' && (defBankHandle.trim() || defHandle.trim() || enroll.nptHandle.trim())) params.set('default_bank_handle', defBankHandle.trim() || defHandle.trim() || enroll.nptHandle.trim());
    const query = params.toString();
    await goto(`/portal/identity/${nextFlow}${query ? `?${query}` : ''}`);
  }

  async function doEnroll() {
    if (!isBank) {
      toast.error('Customer enrollment is bank-scoped. Sign in with a bank portal account to continue.');
      return;
    }
    enrollLoading = true;
    enrollResult = null;
    const response = await apiCall('post', '/identity/claim', enroll);
    enrollLoading = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }
    enrollResult = response.data;
    const claimedHandle = enroll.nptHandle;
    const claimedCustomerRef = enroll.bankCustomerRef;
    enroll = { nptHandle: '', iban: '', customerDisplayName: '', bankCustomerRef: '', nationalId: '', phone: '', customerEmail: '', setAsDefault: true };
    toast.success('Handle claimed');
    if (claimedHandle) {
      await goto(`/portal/identity/link?link_handle=${encodeURIComponent(claimedHandle)}&link_customer_ref=${encodeURIComponent(claimedCustomerRef || '')}`);
    }
  }

  function renameStateCopy(state, candidate) {
    if (state === 'checking') return `Checking ${candidate || 'this handle'} with the registry…`;
    if (state === 'available') return `${candidate} is available. You can review the permanent rename.`;
    if (state === 'taken') return `${candidate} is already in use. Ask the customer to choose another name.`;
    if (state === 'retired') return `${candidate} belonged to an identity before and can never be reused.`;
    if (state === 'invalid') return 'Use 3–32 lowercase letters, numbers, dots, underscores, or hyphens.';
    if (state === 'unknown') return 'Availability could not be checked. Nothing changed; retry before continuing.';
    return 'Enter a new handle to check it.';
  }

  function renameStatusClass(state) {
    if (state === 'available') return 'identity-rename-status identity-rename-status--available';
    if (state === 'taken' || state === 'retired' || state === 'invalid') return 'identity-rename-status identity-rename-status--blocked';
    if (state === 'unknown') return 'identity-rename-status identity-rename-status--unknown';
    return 'identity-rename-status';
  }

  function scheduleRenameAvailability() {
    if (renameAvailabilityTimer) clearTimeout(renameAvailabilityTimer);
    const candidate = canonicalRenameHandle;
    renameAvailabilityRequest += 1;
    renameConfirming = false;
    renameCustomerConfirmed = false;
    renameResult = null;
    renameError = '';

    if (!candidate) {
      renameAvailability = 'idle';
      renameChecking = false;
      renameAvailabilityMessage = renameStateCopy('idle', candidate);
      return;
    }
    if (!/^[a-z0-9_.-]{3,32}$/.test(candidate) || candidate === renameCurrentHandle.trim().toLowerCase()) {
      renameAvailability = 'invalid';
      renameChecking = false;
      renameAvailabilityMessage = candidate === renameCurrentHandle.trim().toLowerCase()
        ? 'The new handle must be different from the current handle.'
        : renameStateCopy('invalid', candidate);
      return;
    }

    renameAvailability = 'checking';
    renameChecking = true;
    renameAvailabilityMessage = renameStateCopy('checking', candidate);
    const requestId = renameAvailabilityRequest;
    renameAvailabilityTimer = window.setTimeout(() => checkRenameAvailability(candidate, requestId), 350);
  }

  async function checkRenameAvailability(candidate = canonicalRenameHandle, requestId = ++renameAvailabilityRequest) {
    if (renameAvailabilityTimer) {
      clearTimeout(renameAvailabilityTimer);
      renameAvailabilityTimer = null;
    }
    if (!candidate || !/^[a-z0-9_.-]{3,32}$/.test(candidate)) {
      scheduleRenameAvailability();
      return;
    }
    renameChecking = true;
    renameAvailability = 'checking';
    renameAvailabilityMessage = renameStateCopy('checking', candidate);
    const response = await apiCall('get', `/identity/handles/${encodeURIComponent(candidate)}/availability`);
    if (requestId !== renameAvailabilityRequest || candidate !== canonicalRenameHandle) return;
    renameChecking = false;
    if (!response.ok) {
      renameAvailability = 'unknown';
      renameAvailabilityMessage = renameStateCopy('unknown', candidate);
      return;
    }
    const state = String(response.data?.status || '').toLowerCase();
    renameAvailability = ['available', 'taken', 'retired', 'invalid'].includes(state) ? state : 'unknown';
    renameAvailabilityMessage = renameStateCopy(renameAvailability, candidate);
  }

  async function reviewRename() {
    if (renameAvailability !== 'available' || !renameCurrentHandle.trim() || !renameNationalId) return;
    renameConfirming = true;
    renameCustomerConfirmed = false;
    renameError = '';
    await tick();
    renameConfirmationHeading?.focus();
  }

  function renameErrorCopy(response) {
    if (response.code === 'HANDLE_TAKEN' || response.status === 409) return 'That handle was claimed before the rename completed. Check another name.';
    if (response.code === 'HANDLE_RETIRED' || response.status === 410) return 'That handle is permanently retired and can never be reused.';
    if (response.code === 'HANDLE_RENAME_NOT_PERMITTED' || response.status === 403) return 'This bank cannot verify this rename. Confirm the linked customer record and national ID.';
    if (response.code === 'HANDLE_INVALID_FORMAT' || response.status === 422) return renameStateCopy('invalid', canonicalRenameHandle);
    if (response.code === 'HANDLE_RENAME_TOO_SOON' || response.status === 429) return response.error || 'This identity is not yet eligible for another rename.';
    return response.error || 'The rename could not be completed. Nothing changed; retry when the registry is reachable.';
  }

  async function submitRename() {
    if (!isBank || !renameCustomerConfirmed || renameAvailability !== 'available') return;
    renameSubmitting = true;
    renameError = '';
    const previousHandle = renameCurrentHandle.trim().toLowerCase();
    const desiredHandle = canonicalRenameHandle;
    const response = await apiCall(
      'patch',
      `/identity/${encodeURIComponent(previousHandle)}/handle`,
      { newHandle: desiredHandle, nationalId: renameNationalId.trim() }
    );
    renameSubmitting = false;
    if (!response.ok || response.data?.nptHandle !== desiredHandle || response.data?.reauthenticationRequired !== true) {
      renameError = response.ok
        ? 'The registry returned an unexpected rename result. Refresh the identity before retrying.'
        : renameErrorCopy(response);
      if (response.code === 'HANDLE_TAKEN') renameAvailability = 'taken';
      if (response.code === 'HANDLE_RETIRED') renameAvailability = 'retired';
      if (response.code === 'HANDLE_INVALID_FORMAT') renameAvailability = 'invalid';
      await tick();
      renameStatusRegion?.focus();
      return;
    }

    renameResult = {
      previousHandle,
      newHandle: desiredHandle,
      address: renameAddressPreview
    };
    renameConfirming = false;
    renameCustomerConfirmed = false;
    renameAvailability = 'idle';
    renameAvailabilityMessage = 'Rename completed.';
    toast.success('NPT handle renamed. Customer re-authentication is required.');
    await tick();
    renameStatusRegion?.focus();
  }

  async function doLink() {
    if (!isBank) {
      toast.error('Account linking is bank-scoped. Sign in with a bank portal account to continue.');
      return;
    }
    linkLoading = true;
    const response = await apiCall('post', `/identity/${linkHandle}/accounts`, {
      iban: linkIban,
      bankCustomerRef: linkBankCustomerRef,
      setAsDefault: linkDefault
    });
    linkLoading = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }
    const linkedHandle = linkHandle;
    linkIban = '';
    linkDefault = false;
    toast.success('IBAN linked');
    await goto(`/portal/identity/default-account?default_handle=${encodeURIComponent(linkedHandle)}&default_account_bank_handle=${encodeURIComponent(currentBankHandle || '')}`);
  }

  async function doUnlink() {
    if (!isBank) {
      toast.error('Route removal is bank-scoped. Sign in with a bank portal account to continue.');
      return;
    }
    unlinkLoading = true;
    const response = await apiCall('delete', `/identity/${unlinkHandle}/accounts/iban/${encodeURIComponent(unlinkIban)}?bankHandle=${encodeURIComponent(unlinkBankHandle)}`);
    unlinkLoading = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }
    unlinkIban = '';
    toast.success('IBAN unlinked');
  }

  async function doSetDefaultIban() {
    if (!isBank) {
      toast.error('Default IBAN updates are bank-scoped. Sign in with a bank portal account to continue.');
      return;
    }
    defLoading = true;
    const response = await apiCall('patch', `/identity/${defHandle}/accounts/iban/${encodeURIComponent(defIban)}/set-default?bankHandle=${encodeURIComponent(defAccountBankHandle)}`);
    defLoading = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }
    toast.success('Default IBAN updated');
  }

  async function doSetDefaultBank() {
    if (!isBank) {
      toast.error('Default bank routing is bank-scoped. Sign in with a bank portal account to continue.');
      return;
    }
    defBankLoading = true;
    const response = await apiCall('patch', `/identity/${defBankHandle}/default-bank`, { bankHandle: defBankSelected });
    defBankLoading = false;
    if (!response.ok) {
      toast.error(response.error);
      return;
    }
    toast.success('Default bank updated');
  }

  onMount(async () => {
    session = get(auth);
    flow = resolveFlow();
    if (!flow) {
      await goto('/portal/identity', { replaceState: true });
      return;
    }
    applyPrefillsFromQuery();
    if (flow === 'default-bank') {
      await loadBanks();
    }
    if (currentBankHandle) {
      unlinkBankHandle ||= currentBankHandle;
      defAccountBankHandle ||= currentBankHandle;
      defBankSelected ||= currentBankHandle;
    }
    hydrated = true;
    routerReadyFrame = requestAnimationFrame(() => {
      routerReady = true;
    });
    if (flow === 'rename' && renameNewHandle.trim()) scheduleRenameAvailability();
  });

  onDestroy(() => {
    if (renameAvailabilityTimer) clearTimeout(renameAvailabilityTimer);
    if (routerReadyFrame) cancelAnimationFrame(routerReadyFrame);
  });

  $effect(() => {
    syncQuery();
  });
</script>

<svelte:head><title>{current ? `${current.title} - OpenWave Identity` : 'Identity Operations - OpenWave Identity'}</title></svelte:head>

{#if current}
  <div class="mx-auto max-w-7xl space-y-6 p-4 sm:p-8">
    <section class="identity-expressive-band p-6">
      <div class="flex flex-col gap-5 xl:flex-row xl:items-start xl:justify-between">
        <div class="max-w-3xl">
          <a href="/portal/identity" class="inline-flex min-h-12 items-center gap-2 text-[12px] font-medium text-white/45 transition-colors hover:text-white/75">
            <ArrowLeft class="h-4 w-4" />
            Back to workflow launcher
          </a>
          <p class="mt-4 text-[11px] uppercase tracking-[0.18em] text-white/30">{current.eyebrow}</p>
          <div class="mt-3 flex items-center gap-3">
            <div class={`flex h-12 w-12 items-center justify-center rounded-2xl border border-white/[0.08] bg-white/[0.04] ${current.accent}`}>
              <current.icon class="h-5 w-5" />
            </div>
            <div>
              <h1 class="identity-page-title text-3xl font-semibold tracking-tight">{current.title}</h1>
              <p class="mt-1 text-sm text-white/50">{current.description}</p>
            </div>
          </div>
          <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
            <span class="identity-role-accent">{isBank ? session?.bankHandle || 'Bank scoped' : 'Registry scoped'}</span>
            <span class="identity-role-accent">Dedicated task route</span>
            <span class="identity-role-accent">Deep-link ready</span>
          </div>
        </div>
        <div class="identity-kpi-card min-w-[280px] px-5 py-4">
          <p class="text-[11px] uppercase tracking-[0.16em] text-white/30">Use this desk for</p>
          <p class="mt-2 text-base font-semibold text-white">{current.title}</p>
          <p class="mt-1 text-sm text-white/45">
            Keep the operator in one action path. Move to another route only after this task is finished.
          </p>
        </div>
      </div>
    </section>

    <section class="grid gap-4 xl:grid-cols-[260px,minmax(0,1fr)]">
      <aside class="identity-surface-card p-4">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">Workflow map</p>
        <div class="mt-3 space-y-2">
          {#each flowOrder as item}
            {@const config = flowConfig[item]}
            <button
              type="button"
              onclick={() => openFlow(item)}
              class={`flex w-full items-center justify-between rounded-2xl border px-4 py-3 text-left transition-all ${
                item === flow
                  ? 'border-white/[0.16] bg-white/[0.06] text-white'
                  : 'border-white/[0.06] bg-white/[0.02] text-white/60 hover:border-white/[0.12] hover:bg-white/[0.04] hover:text-white'
              }`}
            >
              <span>
                <span class="block text-[13px] font-semibold">{config.title}</span>
                <span class="mt-1 block text-[11px] text-white/40">{config.eyebrow}</span>
              </span>
              <ArrowRight class="h-4 w-4 shrink-0" />
            </button>
          {/each}
        </div>
      </aside>

      <div class="space-y-4">
        {#if flow === 'claim'}
          <section class="identity-surface-card p-6">
            <div class="flex flex-col gap-2 md:flex-row md:items-start md:justify-between">
              <div>
                <h2 class="text-lg font-semibold text-white">Customer enrollment</h2>
                <p class="mt-1 text-sm text-white/45">Bank-vouched handle claim with the first route attached at creation time.</p>
              </div>
              <div class={`rounded-xl border px-3 py-1.5 text-[11px] ${
                isBank
                  ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-300'
                  : 'border-amber-500/20 bg-amber-500/10 text-amber-300'
              }`}>
                {isBank ? 'Bank access active' : 'Admin mode'}
              </div>
            </div>

            <div class="mt-5 grid gap-3 md:grid-cols-2">
              {#each [
                ['nptHandle', 'NPT Handle', 'e.g. mtellesy'],
                ['customerDisplayName', 'Display Name', 'Full name'],
                ['iban', 'IBAN', 'LY83002700...'],
                ['bankCustomerRef', 'Customer Ref', 'Internal bank ID'],
                ['nationalId', 'National ID', '12-digit Libyan national ID'],
                ['phone', 'Phone', '0911091195 or +218911091195'],
                ['customerEmail', 'Customer Email', 'customer@example.ly']
              ] as [field, label, placeholder]}
                <div>
                  <label for={`claim-${field}`} class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">{label}</label>
                  <input
                    id={`claim-${field}`}
                    bind:value={enroll[field]}
                    placeholder={placeholder}
                    class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-indigo-400/60 focus:outline-none"
                  />
                </div>
              {/each}
            </div>

            <label class="mt-4 flex items-center gap-2 text-[13px] text-white/55">
              <input type="checkbox" bind:checked={enroll.setAsDefault} class="h-4 w-4 accent-indigo-500" />
              Set this bank as the default responder for the handle.
            </label>
            <div class="mt-3 rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4 text-[12px] text-white/50">
              National ID and customer email are mandatory for the bank-vouched Libya identity flow. Phone is optional but strongly recommended for continuity and public-identifier sign-in.
            </div>

            {#if enrollResult}
              <div class="mt-5 rounded-2xl border border-emerald-500/20 bg-emerald-500/[0.07] p-4">
                <div class="text-[11px] uppercase tracking-[0.16em] text-emerald-300">Claim created</div>
                <div class="mt-3 grid gap-3 md:grid-cols-2">
                  {#each Object.entries(enrollResult) as [key, value]}
                    {#if typeof value !== 'object'}
                      <div>
                        <div class="text-[10px] uppercase tracking-[0.14em] text-white/30">{key}</div>
                        <div class="mt-1 text-[13px] font-medium text-white">{value}</div>
                      </div>
                    {/if}
                  {/each}
                </div>
              </div>
            {/if}

            <div class="mt-5 flex flex-wrap gap-3">
              <button
                type="button"
                onclick={doEnroll}
                disabled={enrollLoading || !isBank || !enroll.nptHandle || !enroll.iban || !enroll.customerDisplayName || !enroll.bankCustomerRef || !enroll.nationalId || !enroll.customerEmail}
                class="rounded-2xl bg-indigo-500 px-5 py-3 text-[13px] font-semibold text-white transition-all hover:bg-indigo-400 disabled:opacity-40"
              >
                {enrollLoading ? 'Claiming...' : current.submitLabel}
              </button>
              <button type="button" onclick={() => openFlow('link')} class="identity-shell-button rounded-2xl border px-5 py-3 text-[13px] font-medium">
                Go to link account
              </button>
            </div>
          </section>
        {/if}

        {#if flow === 'rename'}
          <section class="identity-surface-card p-6" aria-busy={renameSubmitting || renameChecking}>
            <div class="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
              <div class="max-w-3xl">
                <h2 class="text-lg font-semibold text-white">Customer-directed NPT rename</h2>
                <p class="mt-1 text-sm leading-6 text-white/50">
                  The customer owns the name decision. A linked bank authenticates the request against its KYC record and submits it to the registry.
                </p>
              </div>
              <span class={`inline-flex min-h-9 items-center rounded-xl border px-3 py-1.5 text-[11px] ${
                isBank
                  ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-300'
                  : 'border-amber-500/20 bg-amber-500/10 text-amber-300'
              }`}>
                {isBank ? 'Bank authentication active' : 'Bank authentication required'}
              </span>
            </div>

            <div class="mt-6 grid gap-4 lg:grid-cols-2">
              <div>
                <label for="rename-current-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/40">Current NPT handle</label>
                <input
                  id="rename-current-handle"
                  bind:value={renameCurrentHandle}
                  oninput={() => {
                    renameConfirming = false;
                    renameResult = null;
                    renameError = '';
                    scheduleRenameAvailability();
                  }}
                  dir="ltr"
                  autocapitalize="none"
                  autocomplete="off"
                  spellcheck="false"
                  placeholder="current-name"
                  class="identity-form-control min-h-12 w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 text-[14px] text-white placeholder:text-white/25"
                />
              </div>
              <div>
                <label for="rename-new-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/40">New NPT handle</label>
                <div class="flex gap-2">
                  <input
                    id="rename-new-handle"
                    bind:value={renameNewHandle}
                    oninput={scheduleRenameAvailability}
                    aria-describedby="rename-availability-status rename-address-preview"
                    aria-invalid={renameAvailability === 'invalid' || renameAvailability === 'taken' || renameAvailability === 'retired'}
                    dir="ltr"
                    autocapitalize="none"
                    autocomplete="off"
                    spellcheck="false"
                    placeholder="new-name"
                    class="identity-form-control min-h-12 min-w-0 flex-1 rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 text-[14px] text-white placeholder:text-white/25"
                  />
                  <button
                    type="button"
                    onclick={() => checkRenameAvailability()}
                    disabled={renameChecking || !canonicalRenameHandle}
                    class="identity-shell-button inline-flex min-h-12 min-w-12 items-center justify-center rounded-2xl border px-4 text-[13px] font-semibold disabled:opacity-40"
                    aria-label="Check new handle availability"
                  >
                    {#if renameChecking}
                      <LoaderCircle class="h-5 w-5 animate-spin" aria-hidden="true" />
                    {:else}
                      Check
                    {/if}
                  </button>
                </div>
              </div>
              <div>
                <label for="rename-national-id" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/40">Customer national ID</label>
                <input
                  id="rename-national-id"
                  bind:value={renameNationalId}
                  oninput={() => {
                    renameNationalId = renameNationalId.replace(/\D/g, '').slice(0, 12);
                    renameConfirming = false;
                    renameError = '';
                  }}
                  type="password"
                  inputmode="numeric"
                  autocomplete="off"
                  maxlength="12"
                  placeholder="12 digits"
                  class="identity-form-control min-h-12 w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 font-mono text-[14px] tracking-[0.18em] text-white placeholder:font-sans placeholder:tracking-normal placeholder:text-white/25"
                />
                <p class="mt-1.5 text-[12px] text-white/40">Used only to verify the customer record; it is never shown in availability results.</p>
              </div>
              <div id="rename-address-preview" class="identity-surface-soft flex min-h-12 flex-col justify-center px-4 py-3">
                <span class="text-[10px] uppercase tracking-[0.15em] text-white/35">New payment address</span>
                <strong class="mt-1 break-all font-mono text-sm text-white" dir="ltr">{renameAddressPreview || '—'}</strong>
              </div>
            </div>

            <div
              id="rename-availability-status"
              class={`mt-4 ${renameStatusClass(renameAvailability)}`}
              role={renameAvailability === 'unknown' ? 'alert' : 'status'}
              aria-live="polite"
              aria-atomic="true"
            >
              <div class="flex min-w-0 items-start gap-3">
                {#if renameChecking}
                  <LoaderCircle class="mt-0.5 h-5 w-5 shrink-0 animate-spin" aria-hidden="true" />
                {:else if renameAvailability === 'available'}
                  <CheckCircle2 class="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
                {:else if renameAvailability === 'taken' || renameAvailability === 'retired' || renameAvailability === 'invalid' || renameAvailability === 'unknown'}
                  <CircleAlert class="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
                {/if}
                <span>{renameAvailabilityMessage}</span>
              </div>
              {#if renameAvailability === 'unknown'}
                <button type="button" onclick={() => checkRenameAvailability()} class="identity-inline-action mt-3 min-h-12 rounded-xl px-4 text-[13px] font-semibold">Retry availability check</button>
              {/if}
            </div>

            {#if renameConfirming}
              <section class="identity-rename-confirm mt-5 p-5" aria-labelledby="rename-confirm-heading" aria-describedby="rename-confirm-description">
                <h3 id="rename-confirm-heading" bind:this={renameConfirmationHeading} tabindex="-1" class="text-lg font-semibold text-white outline-none">Confirm a permanent payment-address change</h3>
                <p id="rename-confirm-description" class="mt-2 text-sm leading-6 text-white/55">
                  <strong class="font-mono text-white" dir="ltr">{renameCurrentHandle.trim().toLowerCase()}</strong>
                  will be permanently retired. It will not redirect to
                  <strong class="font-mono text-white" dir="ltr">{canonicalRenameHandle}</strong>, cannot be reclaimed, and saved payees using the old address will be refused.
                </p>
                <div class="mt-4 rounded-2xl border border-amber-500/20 bg-amber-500/[0.08] p-4 text-[13px] leading-5 text-amber-100">
                  This rename forces customer re-authentication and revokes delegated-app consent. The customer must sign in with the new handle and authorize delegated apps again.
                </div>
                <label class="mt-4 flex min-h-12 cursor-pointer items-start gap-3 rounded-2xl border border-white/[0.1] bg-white/[0.03] p-3 text-[13px] leading-5 text-white/65">
                  <input type="checkbox" bind:checked={renameCustomerConfirmed} class="mt-0.5 h-5 w-5 shrink-0 accent-cyan-500" />
                  <span>I confirmed that the customer requested this exact new handle and understands that the old payment address is never reusable and does not redirect.</span>
                </label>
                <div class="mt-4 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
                  <button type="button" onclick={() => { renameConfirming = false; renameCustomerConfirmed = false; }} disabled={renameSubmitting} class="identity-shell-button min-h-12 rounded-2xl border px-5 text-[13px] font-semibold disabled:opacity-40">Go back</button>
                  <button type="button" onclick={submitRename} disabled={renameSubmitting || !renameCustomerConfirmed} class="identity-primary-action min-h-12 rounded-2xl bg-cyan-500 px-5 text-[13px] font-bold text-slate-950 transition hover:bg-cyan-400 disabled:opacity-40">
                    {renameSubmitting ? 'Renaming…' : 'Rename and retire old handle'}
                  </button>
                </div>
              </section>
            {/if}

            <div bind:this={renameStatusRegion} tabindex="-1" class="mt-5 outline-none" aria-live="polite" aria-atomic="true">
              {#if renameError}
                <div class="identity-rename-status identity-rename-status--blocked" role="alert">
                  <CircleAlert class="h-5 w-5 shrink-0" aria-hidden="true" />
                  <div>
                    <strong class="block">Rename not completed</strong>
                    <span class="mt-1 block">{renameError}</span>
                  </div>
                </div>
              {:else if renameResult}
                <div class="identity-rename-success" role="status">
                  <CheckCircle2 class="h-6 w-6 shrink-0" aria-hidden="true" />
                  <div>
                    <h3 class="font-semibold">Rename completed; customer re-authentication required</h3>
                    <p class="mt-1 text-[13px] leading-5">
                      <span class="font-mono" dir="ltr">{renameResult.previousHandle}</span> is permanently retired with no redirect.
                      The new NPT address is <strong class="font-mono" dir="ltr">{renameResult.address}</strong>.
                    </p>
                    <p class="mt-2 text-[13px] leading-5">Ask the customer to end any existing portal session, sign in again with <strong class="font-mono" dir="ltr">{renameResult.newHandle}</strong>, and re-authorize any delegated apps.</p>
                  </div>
                </div>
              {/if}
            </div>

            <div class="mt-5 flex flex-wrap gap-3">
              <button
                type="button"
                onclick={reviewRename}
                disabled={!isBank || renameSubmitting || renameChecking || renameAvailability !== 'available' || !renameCurrentHandle.trim() || renameNationalId.length !== 12}
                class="identity-primary-action min-h-12 rounded-2xl bg-cyan-500 px-5 text-[13px] font-bold text-slate-950 transition hover:bg-cyan-400 disabled:opacity-40"
              >
                {current.submitLabel}
              </button>
              <a href="/portal/identity" class="identity-shell-button inline-flex min-h-12 items-center rounded-2xl border px-5 text-[13px] font-semibold">Cancel safely</a>
            </div>
          </section>
        {/if}

        {#if flow === 'link'}
          <section class="identity-surface-card p-6">
            <div class="flex items-start gap-3">
              <BadgeInfo class="mt-0.5 h-5 w-5 text-emerald-300" />
              <div>
                <h2 class="text-lg font-semibold text-white">Attach an additional route</h2>
                <p class="mt-1 text-sm text-white/45">Use this after the handle already exists. Default routing is optional here.</p>
              </div>
            </div>
            <div class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-[1fr,1fr,1fr,220px] xl:items-end">
              <div>
                <label for="link-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">NPT Handle</label>
                <input id="link-handle" bind:value={linkHandle} placeholder="mtellesy" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-emerald-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="link-iban" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">IBAN</label>
                <input id="link-iban" bind:value={linkIban} placeholder="LY92010500..." class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-emerald-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="link-customer-ref" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Customer Ref</label>
                <input id="link-customer-ref" bind:value={linkBankCustomerRef} placeholder="Internal bank ID" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-emerald-400/60 focus:outline-none" />
              </div>
              <div class="space-y-3">
                <label class="flex items-center gap-2 text-[13px] text-white/55">
                  <input type="checkbox" bind:checked={linkDefault} class="h-4 w-4 accent-emerald-500" />
                  Set as default
                </label>
                <button type="button" onclick={doLink} disabled={linkLoading || !isBank || !linkHandle || !linkIban || !linkBankCustomerRef} class="w-full rounded-2xl bg-emerald-500 px-5 py-3 text-[13px] font-semibold text-slate-950 transition-all hover:bg-emerald-400 disabled:opacity-40">
                  {linkLoading ? 'Linking...' : current.submitLabel}
                </button>
              </div>
            </div>
            <div class="mt-3 rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4 text-[12px] text-white/50">
              The bank customer reference is required so the linked IBAN stays auditable against the bank’s own customer record.
            </div>
          </section>
        {/if}

        {#if flow === 'unlink'}
          <section class="identity-surface-card p-6">
            <div class="flex items-start gap-3">
              <CircleAlert class="mt-0.5 h-5 w-5 text-rose-300" />
              <div>
                <h2 class="text-lg font-semibold text-white">Remove a route</h2>
                <p class="mt-1 text-sm text-white/45">Unlinking is permanent for the selected IBAN association. Confirm the alias and route carefully.</p>
              </div>
            </div>
            <div class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-[1fr,1fr,1fr,220px] xl:items-end">
              <div>
                <label for="unlink-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">NPT Handle</label>
                <input id="unlink-handle" bind:value={unlinkHandle} placeholder="mtellesy" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-rose-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="unlink-iban" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">IBAN</label>
                <input id="unlink-iban" bind:value={unlinkIban} placeholder="LY83002700..." class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-rose-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="unlink-bank-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Bank Handle</label>
                <input id="unlink-bank-handle" bind:value={unlinkBankHandle} placeholder="andalus" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-rose-400/60 focus:outline-none" />
              </div>
              <button type="button" onclick={doUnlink} disabled={unlinkLoading || !isBank || !unlinkHandle || !unlinkIban || !unlinkBankHandle} class="rounded-2xl bg-rose-500 px-5 py-3 text-[13px] font-semibold text-white transition-all hover:bg-rose-400 disabled:opacity-40">
                {unlinkLoading ? 'Unlinking...' : current.submitLabel}
              </button>
            </div>
          </section>
        {/if}

        {#if flow === 'default-account'}
          <section class="identity-surface-card p-6">
            <h2 class="text-lg font-semibold text-white">Default IBAN routing</h2>
            <p class="mt-1 text-sm text-white/45">This defines the resolved account for a bank-specific alias. Keep the handle and target IBAN on the same verified identity record.</p>
            <div class="mt-5 grid gap-3 md:grid-cols-2 xl:grid-cols-[1fr,1fr,1fr,220px] xl:items-end">
              <div>
                <label for="default-account-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">NPT Handle</label>
                <input id="default-account-handle" bind:value={defHandle} placeholder="mtellesy" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-amber-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="default-account-iban" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">IBAN</label>
                <input id="default-account-iban" bind:value={defIban} placeholder="LY83002700..." class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-amber-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="default-account-bank-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Bank Handle</label>
                <input id="default-account-bank-handle" bind:value={defAccountBankHandle} placeholder="andalus" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-amber-400/60 focus:outline-none" />
              </div>
              <button type="button" onclick={doSetDefaultIban} disabled={defLoading || !isBank || !defHandle || !defIban || !defAccountBankHandle} class="rounded-2xl bg-amber-400 px-5 py-3 text-[13px] font-semibold text-slate-950 transition-all hover:bg-amber-300 disabled:opacity-40">
                {defLoading ? 'Saving...' : current.submitLabel}
              </button>
            </div>
          </section>
        {/if}

        {#if flow === 'default-bank'}
          <section class="identity-surface-card p-6">
            <h2 class="text-lg font-semibold text-white">Default bank routing</h2>
            <p class="mt-1 text-sm text-white/45">Set which bank answers the bare NPT handle at the network level. Use this only when the customer has valid multi-bank presence.</p>
            <div class="mt-5 grid gap-3 md:grid-cols-[1fr,1fr,220px] md:items-end">
              <div>
                <label for="default-bank-handle" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">NPT Handle</label>
                <input id="default-bank-handle" bind:value={defBankHandle} placeholder="mtellesy" class="w-full rounded-2xl border border-white/[0.1] bg-white/[0.05] px-4 py-3 text-[13px] text-white placeholder:text-white/20 focus:border-sky-400/60 focus:outline-none" />
              </div>
              <div>
                <label for="default-bank-select" class="mb-1.5 block text-[11px] uppercase tracking-[0.16em] text-white/35">Bank</label>
                <select id="default-bank-select" bind:value={defBankSelected} class="w-full rounded-2xl border border-white/[0.1] bg-[#0d0d18] px-4 py-3 text-[13px] text-white focus:border-sky-400/60 focus:outline-none">
                  <option value="">Select bank</option>
                  {#each banks as bank}
                    <option value={bank.bankHandle}>{bank.displayName || bank.bankHandle}</option>
                  {/each}
                </select>
              </div>
              <button type="button" onclick={doSetDefaultBank} disabled={defBankLoading || !isBank || !defBankHandle || !defBankSelected} class="rounded-2xl bg-sky-400 px-5 py-3 text-[13px] font-semibold text-slate-950 transition-all hover:bg-sky-300 disabled:opacity-40">
                {defBankLoading ? 'Saving...' : current.submitLabel}
              </button>
            </div>
            {#if loadingBanks}
              <p class="mt-3 text-[12px] text-white/40">Loading bank registry...</p>
            {/if}
            <div class="mt-3 rounded-2xl border border-white/[0.08] bg-white/[0.03] p-4 text-[12px] text-white/50">
              Default bank routing changes are bank-vouched actions. Registry admins can review identity state, but they should not impersonate a bank-scoped routing decision from this desk.
            </div>
          </section>
        {/if}
      </div>
    </section>
  </div>
{/if}
