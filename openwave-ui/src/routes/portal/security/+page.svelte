<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { page } from '$app/stores';
  import { get } from 'svelte/store';
  import { toast } from 'svelte-sonner';
  import Activity from 'lucide-svelte/icons/activity';
  import CheckCircle2 from 'lucide-svelte/icons/check-circle-2';
  import Clock from 'lucide-svelte/icons/clock';
  import KeyRound from 'lucide-svelte/icons/key-round';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import ShieldAlert from 'lucide-svelte/icons/shield-alert';
  import Info from 'lucide-svelte/icons/info';
  import Trash2 from 'lucide-svelte/icons/trash-2';
  import TriangleAlert from 'lucide-svelte/icons/triangle-alert';
  import { getApi } from '$lib/api/client';
  import { passkeysSupported, registrationCredentialToJson, toPublicKeyCreateOptions } from '$lib/utils/passkeys';

  let loading = $state(true);
  let registering = $state(false);
  let profile = $state(null);
  let passkeys = $state([]);
  let loginApprovals = $state([]);
  let loginApprovalSummary = $state({ total: 0, pending: 0, approved: 0, rejected: 0, expired: 0 });
  let friendlyName = $state('');
  let totpSetup = $state(null);
  let totpCode = $state('');
  let totpBusy = $state(false);
  let profileSaving = $state(false);
  let displayNameDraft = $state('');
  let emailDraft = $state('');
  let passkeyPendingRemovalId = $state(null);
  const currentPanel = $derived(readSection());

  onMount(loadSecurity);

  async function loadSecurity() {
    loading = true;
    try {
      const api = getApi();
      const [profileResponse, passkeyResponse, approvalResponse] = await Promise.all([
        api.get('/auth/profile'),
        api.get('/auth/passkeys'),
        api.get('/customer/login-approvals?limit=4').catch(() => ({ data: { items: [], summary: { total: 0, pending: 0, approved: 0, rejected: 0, expired: 0 } } }))
      ]);
      profile = profileResponse.data;
      passkeys = passkeyResponse.data?.passkeys || [];
      loginApprovals = approvalResponse.data?.items || [];
      loginApprovalSummary = approvalResponse.data?.summary || loginApprovalSummary;
      displayNameDraft = profile?.displayName || profile?.username || '';
      emailDraft = profile?.email || '';
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not load account security');
    } finally {
      loading = false;
    }
  }

  async function saveProfile() {
    if (profileSaving) return;
    profileSaving = true;
    try {
      await getApi().patch('/auth/profile', {
        displayName: displayNameDraft,
        email: emailDraft
      });
      toast.success('Profile and recovery details updated');
      await loadSecurity();
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not update recovery details');
    } finally {
      profileSaving = false;
    }
  }

  async function registerPasskey() {
    if (!passkeysSupported()) {
      toast.error('Passkeys require a secure browser with WebAuthn support.');
      return;
    }
    registering = true;
    try {
      const api = getApi();
      const optionsResponse = await api.post('/auth/passkey/options/register', {});
      const optionsJson = optionsResponse.data.options;
      const publicKey = toPublicKeyCreateOptions(optionsJson);
      const credential = await navigator.credentials.create({ publicKey });
      if (!credential) throw new Error('Passkey setup was cancelled.');
      await api.post('/auth/passkey/register', {
        challenge: JSON.parse(optionsJson).publicKey.challenge,
        credential: registrationCredentialToJson(credential),
        friendlyName: friendlyName.trim() || undefined,
      });
      friendlyName = '';
      toast.success('Passkey added');
      await loadSecurity();
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || error?.message || 'Could not add passkey');
    } finally {
      registering = false;
    }
  }

  async function removePasskey(passkey) {
    passkeyPendingRemovalId = passkey.id;
  }

  async function confirmRemovePasskey(passkey) {
    try {
      await getApi().delete(`/auth/passkeys/${passkey.id}`);
      toast.success('Passkey removed');
      passkeyPendingRemovalId = null;
      await loadSecurity();
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not remove passkey');
    }
  }

  function cancelRemovePasskey() {
    passkeyPendingRemovalId = null;
  }

  async function startTotpSetup() {
    totpBusy = true;
    try {
      const response = await getApi().post('/auth/totp/setup', {});
      totpSetup = response.data;
      totpCode = '';
      await setPanelRoute('authenticator');
      toast.success('Authenticator setup started');
      await loadSecurity();
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not start TOTP setup');
    } finally {
      totpBusy = false;
    }
  }

  async function confirmTotpSetup() {
    if (totpCode.trim().length < 6) {
      toast.error('Enter the 6-digit authenticator code');
      return;
    }
    totpBusy = true;
    try {
      await getApi().post('/auth/totp/confirm', { code: totpCode.trim() });
      totpSetup = null;
      totpCode = '';
      toast.success('Authenticator protection enabled');
      await loadSecurity();
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not verify authenticator code');
    } finally {
      totpBusy = false;
    }
  }

  async function disableTotp() {
    if (totpCode.trim().length < 6) {
      toast.error('Enter the current 6-digit authenticator code');
      return;
    }
    totpBusy = true;
    try {
      await getApi().post('/auth/totp/disable', { code: totpCode.trim() });
      totpSetup = null;
      totpCode = '';
      toast.success('Authenticator protection disabled');
      await loadSecurity();
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not disable authenticator protection');
    } finally {
      totpBusy = false;
    }
  }

  function fmt(value) {
    return value ? new Date(value).toLocaleString() : 'Never';
  }

  function postureChecks() {
    return [
      {
        title: 'Email recovery',
        done: Boolean(profile?.email),
        detail: profile?.email ? profile.email : 'Add customer or operator email so secure reset links can be delivered.',
        action: 'Recovery risk'
      },
      {
        title: 'Authenticator',
        done: Boolean(profile?.totpEnabled),
        detail: profile?.totpEnabled
          ? `Enabled${profile?.totpEnabledAt ? ` since ${fmt(profile.totpEnabledAt)}` : ''}`
          : profile?.totpPending
            ? 'Setup started but not confirmed.'
            : 'Password sign-in is not protected by an authenticator code yet.',
        action: profile?.totpPending ? 'Confirm code' : 'Set up'
      },
      {
        title: 'Passkeys',
        done: passkeys.length > 0,
        detail: passkeys.length > 0
          ? `${passkeys.length} passkey(s) registered for faster phishing-resistant login.`
          : 'No passkey is registered for this portal account.',
        action: 'Add passkey'
      },
      {
        title: 'Recent access',
        done: Boolean(profile?.lastLoginAt),
        detail: profile?.lastLoginAt ? `Last successful login ${fmt(profile.lastLoginAt)}` : 'No successful login recorded yet.',
        action: 'Review access'
      }
    ];
  }

  function postureReadyCount() {
    return postureChecks().filter((item) => item.done).length;
  }

  function postureTone() {
    if (profile?.totpEnabled && passkeys.length > 0 && profile?.email) return 'good';
    if (profile?.totpPending || passkeys.length > 0 || profile?.email) return 'watch';
    return 'risk';
  }

  function postureSummary() {
    const tone = postureTone();
    if (tone === 'good') return 'Strong portal protection is in place.';
    if (tone === 'watch') return 'Portal protection is partially configured. Finish the missing factors.';
    return 'This account still depends heavily on password recovery. Add stronger factors now.';
  }

  function passkeySupportLabel() {
    return passkeysSupported() ? 'Supported on this browser' : 'Unsupported on this browser';
  }

  function approvalTone(status) {
    if (status === 'APPROVED') return 'border-emerald-500/20 bg-emerald-500/10 text-emerald-200';
    if (status === 'REJECTED') return 'border-rose-500/20 bg-rose-500/10 text-rose-200';
    if (status === 'EXPIRED') return 'border-amber-500/20 bg-amber-500/10 text-amber-200';
    return 'border-sky-500/20 bg-sky-500/10 text-sky-200';
  }

  function readSection() {
    const section = $page.url.searchParams.get('section');
    return ['overview', 'recovery', 'authenticator', 'passkeys'].includes(section) ? section : 'overview';
  }

  async function setPanelRoute(section) {
    const next = new URL(get(page).url);
    if (section === 'overview') next.searchParams.delete('section');
    else next.searchParams.set('section', section);
    await goto(`${next.pathname}${next.search}`, { replaceState: true, noScroll: true, keepFocus: true });
  }

  function workspaceCards() {
    return [
      {
        id: 'overview',
        title: 'Security overview',
        detail: `${postureReadyCount()}/${postureChecks().length} controls configured for this portal account.`
      },
      {
        id: 'recovery',
        title: 'Recovery',
        detail: profile?.email ? 'Recovery email and profile details are available.' : 'Recovery email still needs attention.'
      },
      {
        id: 'authenticator',
        title: 'Authenticator',
        detail: profile?.totpEnabled ? 'TOTP is enabled for password sign-in.' : profile?.totpPending ? 'Setup is waiting for code confirmation.' : 'TOTP is not enabled yet.'
      },
      {
        id: 'passkeys',
        title: 'Passkeys',
        detail: passkeys.length > 0 ? `${passkeys.length} passkey(s) registered on trusted devices.` : 'No passkeys registered yet.'
      }
    ];
  }

  function hintClass() {
    return 'inline-flex h-4 w-4 cursor-help text-white/40';
  }
</script>

<svelte:head><title>Account Security - OpenWave Identity</title></svelte:head>

<div class="max-w-7xl space-y-5 p-6">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-4 xl:flex-row xl:items-end xl:justify-between">
      <div>
        <p class="text-xs font-semibold uppercase tracking-[0.16em] text-white/35">Identity security desk</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">Account Security</h1>
        <p class="identity-section-note mt-2 max-w-3xl text-sm text-white/55">Manage sign-in factors, recovery details, and trusted devices for this OpenWave Identity portal account with a strong-factor-first posture.</p>
        <div class="mt-3 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">
            Passkeys first
            <span class="tooltip max-w-xs" data-tip="Passkeys are the strongest primary sign-in factor here. Prefer adding a passkey on a trusted main device before relying on password-only recovery.">
              <Info class={hintClass()} />
            </span>
          </span>
          <span class="identity-role-accent">
            Recovery matters
            <span class="tooltip max-w-xs" data-tip="Recovery email and authenticator setup are operational controls, not just convenience features. They reduce account dead-ends during incidents and device loss.">
              <Info class={hintClass()} />
            </span>
          </span>
          <span class="identity-role-accent">
            Public-ID login stays gated
            <span class="tooltip max-w-xs" data-tip="Phone number or national ID can help start an identity login path, but strong approval still has to complete before portal access is issued. Public identifiers alone are not enough.">
              <Info class={hintClass()} />
            </span>
          </span>
        </div>
        <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">Passkey-first posture</span>
          <span class="identity-role-accent">Recovery continuity</span>
          <span class="identity-role-accent">High-trust access gating</span>
        </div>
      </div>
      <div class="flex flex-wrap gap-2">
        <button onclick={loadSecurity} disabled={loading} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition hover:text-white disabled:opacity-40">
          <RefreshCw class="h-4 w-4 {loading ? 'animate-spin' : ''}" />
          Refresh
        </button>
      </div>
    </div>
  </section>

  {#if loading}
    <div class="grid grid-cols-1 gap-4 lg:grid-cols-4">
      <div class="identity-kpi-card h-28 animate-pulse"></div>
      <div class="identity-kpi-card h-28 animate-pulse"></div>
      <div class="identity-kpi-card h-28 animate-pulse"></div>
      <div class="identity-kpi-card h-28 animate-pulse"></div>
    </div>
  {:else}
    {#if profile?.securitySetupRequired}
      <section class="identity-surface-card border-amber-500/20 bg-amber-500/10 p-4">
        <div class="flex items-start gap-3">
          <TriangleAlert class="mt-0.5 h-5 w-5 text-amber-300 shrink-0" />
          <div>
            <h2 class="font-semibold text-white">Security setup required</h2>
            <p class="mt-1 text-sm text-white/70">{profile?.securitySetupReason || 'Add a passkey or enable an authenticator code before using the customer portal.'}</p>
            <p class="mt-2 text-xs text-white/50">Recommended order: add a passkey on your main device, then enable authenticator codes for recovery and cross-device access.</p>
            {#if !profile?.email}
              <p class="mt-2 text-xs text-amber-200/80">A recovery email is still missing. Add one below so password reset and operator follow-up do not dead-end.</p>
            {/if}
          </div>
        </div>
      </section>
    {/if}

    <section class="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <div class="identity-kpi-card p-4">
        <p class="text-xs uppercase text-white/35">Security checks</p>
        <p class="mt-2 text-2xl font-semibold text-white">{postureReadyCount()}/{postureChecks().length}</p>
        <p class="text-xs text-white/50">configured</p>
      </div>
      <div class="identity-kpi-card p-4">
        <p class="text-xs uppercase text-white/35">Passkeys</p>
        <p class="mt-2 text-2xl font-semibold text-white">{passkeys.length}</p>
        <p class="text-xs text-white/50">{passkeySupportLabel()}</p>
      </div>
      <div class="identity-kpi-card p-4">
        <p class="text-xs uppercase text-white/35">Authenticator</p>
        <p class="mt-2 text-2xl font-semibold text-white">{profile?.totpEnabled ? 'On' : profile?.totpPending ? 'Pending' : 'Off'}</p>
        <p class="text-xs text-white/50">{profile?.totpEnabledAt ? fmt(profile.totpEnabledAt) : 'No activation recorded'}</p>
      </div>
      <div class="identity-kpi-card p-4">
        <p class="text-xs uppercase text-white/35">Last login</p>
        <p class="mt-2 text-sm font-semibold text-white">{fmt(profile?.lastLoginAt)}</p>
        <p class="text-xs text-white/50">{profile?.portalRole || profile?.role || 'Portal account'}</p>
      </div>
    </section>

    <div class="grid grid-cols-1 gap-5 xl:grid-cols-[260px_minmax(0,1fr)]">
      <aside class="identity-surface-card p-4">
        <div class="text-sm font-semibold text-white">Security desk</div>
        <p class="mt-2 text-sm text-white/45">Use one active security workflow at a time: overview, recovery, authenticator, or passkeys.</p>
        <div class="mt-4 space-y-2">
          {#each workspaceCards() as card}
            <button
              type="button"
              onclick={() => setPanelRoute(card.id)}
              class={`w-full rounded-xl border px-3 py-3 text-left transition ${
                currentPanel === card.id
                  ? 'border-white/[0.16] bg-white/[0.08]'
                  : 'border-white/[0.08] bg-white/[0.03] hover:bg-white/[0.05]'
              }`}
            >
              <div class="text-sm font-medium text-white">{card.title}</div>
              <div class="mt-1 text-xs text-white/45">{card.detail}</div>
            </button>
          {/each}
        </div>
        <div class="mt-4 rounded-2xl border border-white/[0.08] bg-black/15 px-4 py-3 text-sm text-white/45">
          Prefer passkeys on trusted devices, keep a recovery email current, and use authenticator codes for password-based sign-in.
        </div>
      </aside>

      <div class="space-y-4">
        <section class="identity-surface-card p-5">
          <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
            <div class="flex items-start gap-3">
              {#if postureTone() === 'good'}
                <CheckCircle2 class="mt-0.5 h-5 w-5 text-emerald-300" />
              {:else if postureTone() === 'watch'}
                <Clock class="mt-0.5 h-5 w-5 text-amber-300" />
              {:else}
                <ShieldAlert class="mt-0.5 h-5 w-5 text-red-300" />
              {/if}
              <div>
                <div class="flex flex-wrap gap-2 text-[11px] uppercase tracking-[0.16em] text-white/30">
                  <span>{currentPanel === 'overview' ? 'Security overview' : currentPanel === 'recovery' ? 'Recovery' : currentPanel === 'authenticator' ? 'Authenticator' : 'Passkeys'}</span>
                  <span class="identity-role-accent normal-case tracking-normal text-[11px]">{postureReadyCount()}/{postureChecks().length} controls configured</span>
                </div>
                <h2 class="mt-2 text-lg font-semibold text-white">{profile?.displayName || profile?.username}</h2>
                <p class="mt-1 text-sm text-white/45">{postureSummary()}</p>
              </div>
            </div>
            <span class="inline-flex items-center rounded-full border px-2.5 py-1 text-[11px] font-medium
              {postureTone() === 'good'
                ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-300'
                : postureTone() === 'watch'
                  ? 'border-amber-500/20 bg-amber-500/10 text-amber-300'
                  : 'border-red-500/20 bg-red-500/10 text-red-300'}">
              {postureTone() === 'good' ? 'Strong' : postureTone() === 'watch' ? 'Partial' : 'At risk'}
            </span>
          </div>
          <div class="mt-4 grid grid-cols-1 gap-2 xl:grid-cols-2">
            {#each postureChecks() as item}
              <button class="identity-surface-soft flex items-start justify-between gap-3 px-3 py-3 text-left transition hover:bg-white/[0.04]" onclick={() => setPanelRoute(item.title === 'Passkeys' ? 'passkeys' : item.title === 'Authenticator' ? 'authenticator' : item.title === 'Email recovery' ? 'recovery' : 'overview')}>
                <div class="min-w-0">
                  <p class="text-sm font-medium text-white">{item.title}</p>
                  <p class="text-xs leading-relaxed text-white/40">{item.detail}</p>
                </div>
                <span class="shrink-0 rounded-full px-2 py-1 text-[11px] font-medium {item.done ? 'bg-emerald-500/10 text-emerald-300' : 'bg-amber-500/10 text-amber-300'}">{item.done ? 'Ready' : item.action}</span>
              </button>
            {/each}
          </div>
        </section>

        <section class="identity-surface-card min-w-0">
        {#if currentPanel === 'overview'}
          <div class="border-b border-white/[0.08] px-5 py-4">
            <p class="text-xs font-semibold uppercase tracking-wide text-white/35">Security Overview</p>
            <h3 class="mt-1 text-lg font-semibold text-white">Current protection state</h3>
            <p class="mt-1 text-sm text-white/45">Review which factors protect this portal account and where follow-up is still required.</p>
          </div>
          <div class="space-y-4 p-5">
            <div class="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
              <div class="identity-surface-soft p-4">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Username</div>
                <div class="mt-2 font-mono text-sm text-white">{profile?.username}</div>
              </div>
              <div class="identity-surface-soft p-4">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Role</div>
                <div class="mt-2 text-sm text-white">{profile?.portalRole}</div>
              </div>
              <div class="identity-surface-soft p-4">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Created</div>
                <div class="mt-2 text-sm text-white">{fmt(profile?.createdAt)}</div>
              </div>
              <div class="identity-surface-soft p-4">
                <div class="text-[11px] uppercase tracking-[0.16em] text-white/30">Recovery posture</div>
                <div class="mt-2 text-sm text-white">{profile?.email ? 'Email recovery available' : 'No recovery email configured'}</div>
              </div>
            </div>
            <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
              <div class="identity-surface-soft p-4">
                <div class="flex items-start justify-between gap-3">
                  <div>
                    <p class="text-sm font-semibold text-white">Password and recovery</p>
                    <p class="mt-1 text-xs text-white/45">Password reset and account recovery depend on a valid Identity email path.</p>
                  </div>
                  <span class="rounded-full px-2 py-1 text-[11px] font-medium {profile?.email ? 'bg-emerald-500/10 text-emerald-300' : 'bg-red-500/10 text-red-300'}">{profile?.email ? 'Ready' : 'Missing'}</span>
                </div>
                <div class="mt-3 text-sm text-white/70">{profile?.email || 'No recovery email configured for this portal account.'}</div>
              </div>
              <div class="identity-surface-soft p-4">
                <div class="flex items-start justify-between gap-3">
                  <div>
                    <p class="text-sm font-semibold text-white">Multi-factor posture</p>
                    <p class="mt-1 text-xs text-white/45">Use both authenticator and passkeys where possible for operational accounts.</p>
                  </div>
                  <span class="rounded-full px-2 py-1 text-[11px] font-medium {(profile?.totpEnabled && passkeys.length > 0) ? 'bg-emerald-500/10 text-emerald-300' : 'bg-amber-500/10 text-amber-300'}">{(profile?.totpEnabled && passkeys.length > 0) ? 'Strong' : 'Partial'}</span>
                </div>
                <div class="mt-3 text-sm text-white/70">Authenticator {profile?.totpEnabled ? 'enabled' : profile?.totpPending ? 'pending confirmation' : 'not enabled'} · {passkeys.length} passkey(s)</div>
              </div>
            </div>
            <div class="identity-surface-soft p-4">
              <p class="text-sm font-semibold text-white">Operational guidance</p>
              <div class="mt-3 grid grid-cols-1 gap-2 md:grid-cols-3">
                <div class="identity-surface-soft px-3 py-2 text-sm text-white/70">Keep at least one passkey on a primary device.</div>
                <div class="identity-surface-soft px-3 py-2 text-sm text-white/70">Use authenticator codes for operator and bank accounts that can change registry data.</div>
                <div class="identity-surface-soft px-3 py-2 text-sm text-white/70">Verify recovery email before relying on password reset during incidents.</div>
              </div>
            </div>
            {#if profile?.role === 'CUSTOMER'}
              <div class="identity-surface-soft p-4">
                <div class="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                  <div>
                    <p class="text-sm font-semibold text-white">Bank-approved public-identifier sign-in</p>
                    <p class="mt-1 text-xs text-white/45">Phone number and national ID can start sign-in, but a linked bank still has to approve the request before a customer session is issued.</p>
                  </div>
                  <div class="flex flex-wrap gap-2">
                    <div class="grid grid-cols-2 gap-2 text-[11px] text-white/60">
                      <div class="rounded-lg border border-white/[0.08] px-3 py-2">Pending {loginApprovalSummary.pending ?? 0}</div>
                      <div class="rounded-lg border border-white/[0.08] px-3 py-2">Approved {loginApprovalSummary.approved ?? 0}</div>
                    </div>
                    <a href="/portal/customer/login-approvals" class="inline-flex items-center rounded-lg border border-white/[0.08] px-3 py-2 text-[11px] font-medium text-white/65 transition hover:border-white/[0.18] hover:text-white">
                      Full sign-in history
                    </a>
                  </div>
                </div>
                <div class="mt-4 space-y-2">
                  {#if loginApprovals.length}
                    {#each loginApprovals as approval}
                      <div class="rounded-xl border border-white/[0.08] bg-black/20 px-3 py-3">
                        <div class="flex flex-col gap-2 lg:flex-row lg:items-start lg:justify-between">
                          <div>
                            <div class={`inline-flex rounded-full border px-2.5 py-1 text-[11px] font-medium ${approvalTone(approval.status)}`}>{approval.status}</div>
                            <div class="mt-2 text-sm text-white/75">{approval.identifier_type} sign-in via {approval.identifier_hint}</div>
                            <div class="mt-1 text-[12px] text-white/40">{approval.actioned_at ? `Actioned ${fmt(approval.actioned_at)}` : `Started ${fmt(approval.created_at)}`}</div>
                          </div>
                          <div class="text-[12px] text-white/45">{approval.approved_bank_handle ? `Approved by ${approval.approved_bank_handle}` : `Default route ${approval.default_bank_handle || 'not set'}`}</div>
                        </div>
                      </div>
                    {/each}
                  {:else}
                    <div class="rounded-xl border border-dashed border-white/[0.12] px-3 py-5 text-sm text-white/35">
                      No recent bank-app approval activity is recorded for this customer account.
                    </div>
                  {/if}
                </div>
              </div>
            {/if}
          </div>
        {:else if currentPanel === 'recovery'}
          <div class="border-b border-white/[0.08] px-5 py-4">
            <p class="text-xs font-semibold uppercase tracking-wide text-white/35">Recovery Desk</p>
            <h3 class="mt-1 text-lg font-semibold text-white">Recovery details and continuity</h3>
            <p class="mt-1 text-sm text-white/45">Keep recovery email and display details current before an incident, device loss, or operator handoff.</p>
          </div>
          <div class="space-y-4 p-5">
            <div class="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
              <div class="space-y-3">
                <div>
                  <label for="security-display-name" class="block text-[11px] font-medium uppercase tracking-wider text-white/35">Display name</label>
                  <input id="security-display-name" bind:value={displayNameDraft} class="mt-1 w-full rounded-xl border border-white/[0.08] bg-black/20 px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 outline-none focus:border-indigo-500/50" placeholder="Customer or operator display name" />
                </div>
                <div>
                  <label for="security-email" class="block text-[11px] font-medium uppercase tracking-wider text-white/35">Recovery email</label>
                  <input id="security-email" bind:value={emailDraft} class="mt-1 w-full rounded-xl border border-white/[0.08] bg-black/20 px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 outline-none focus:border-indigo-500/50" placeholder="name@example.com" />
                  <p class="mt-1 text-[11px] text-white/35">Password reset and customer-access notices use this email.</p>
                </div>
                <button onclick={saveProfile} disabled={profileSaving} class="inline-flex items-center justify-center rounded-xl bg-indigo-600 px-4 py-2.5 text-[13px] font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-30">
                  {profileSaving ? 'Saving...' : 'Save recovery details'}
                </button>
              </div>
              <div class="border border-white/[0.08] bg-black/20 p-4">
                <p class="text-sm font-semibold text-white">Continuity note</p>
                <div class="mt-3 space-y-2 text-sm text-white/60">
                  <div class="identity-surface-soft px-3 py-2">Recovery email should be verified before relying on password reset during incidents.</div>
                  <div class="identity-surface-soft px-3 py-2">Display name helps support and bank operators verify the correct portal identity during recovery.</div>
                  <div class="identity-surface-soft px-3 py-2">Public-ID login paths still need strong approval even when recovery details are present.</div>
                </div>
              </div>
            </div>
          </div>
        {:else if currentPanel === 'authenticator'}
          <div class="border-b border-white/[0.08] px-5 py-4">
            <div class="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
              <div>
                <p class="text-xs font-semibold uppercase tracking-wide text-white/35">Authenticator Desk</p>
                <h3 class="mt-1 text-lg font-semibold text-white">TOTP protection</h3>
                <p class="mt-1 text-sm text-white/45">Require a 6-digit authenticator code after password sign-in.</p>
              </div>
              <span class="rounded-full border px-2.5 py-1 text-[11px] font-medium {profile?.totpEnabled ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-300' : profile?.totpPending ? 'border-amber-500/20 bg-amber-500/10 text-amber-300' : 'border-white/[0.08] text-white/55'}">
                {profile?.totpEnabled ? 'Enabled' : profile?.totpPending ? 'Pending' : 'Off'}
              </span>
            </div>
          </div>
          <div class="space-y-4 p-5">
            {#if totpSetup}
              <div class="border border-sky-500/20 bg-sky-500/10 p-4">
                <div class="flex items-start gap-3">
                  <TriangleAlert class="mt-0.5 h-4 w-4 text-sky-300" />
                  <div class="min-w-0">
                    <p class="text-sm font-semibold text-white">Setup in progress</p>
                    <p class="mt-1 text-xs text-white/55">Add this secret to your authenticator app, then confirm the current 6-digit code.</p>
                  </div>
                </div>
                <div class="mt-3 grid grid-cols-1 gap-3">
                  <div class="rounded border border-sky-500/20 bg-black/20 px-3 py-2">
                    <div class="text-[11px] uppercase text-white/35">Setup secret</div>
                    <div class="mt-1 break-all font-mono text-sm text-white">{totpSetup.secret}</div>
                  </div>
                  <div class="rounded border border-sky-500/20 bg-black/20 px-3 py-2">
                    <div class="text-[11px] uppercase text-white/35">Manual URI</div>
                    <div class="mt-1 break-all font-mono text-[11px] text-white/70">{totpSetup.otpauthUri}</div>
                  </div>
                </div>
              </div>
            {:else if profile?.totpEnabled}
              <div class="border border-emerald-500/20 bg-emerald-500/10 px-4 py-3 text-sm text-white/75">
                Authenticator protection is enabled. Password sign-in now requires a valid TOTP code.
              </div>
            {:else}
              <div class="border border-amber-500/20 bg-amber-500/10 px-4 py-3 text-sm text-white/75">
                Add authenticator protection so portal access does not rely on password reset alone.
              </div>
            {/if}

            <div class="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_240px]">
              <div class="space-y-3">
                <label for="totp-code" class="block text-[11px] font-medium uppercase tracking-wider text-white/35">Authenticator code</label>
                <input id="totp-code" bind:value={totpCode} inputmode="numeric" maxlength="6" placeholder="123456" class="w-full border border-white/[0.08] bg-black/20 px-3.5 py-2.5 font-mono text-[13px] tracking-[0.2em] text-white placeholder-white/20 outline-none focus:border-sky-500/50" />
                <div class="grid grid-cols-1 gap-2 sm:grid-cols-3">
                  {#if !profile?.totpEnabled && !totpSetup}
                    <button onclick={startTotpSetup} disabled={totpBusy} class="inline-flex items-center justify-center rounded-xl bg-sky-600 px-4 py-2.5 text-[13px] font-semibold text-white transition hover:bg-sky-500 disabled:opacity-30">
                      {totpBusy ? 'Starting...' : 'Start setup'}
                    </button>
                  {/if}
                  {#if totpSetup || profile?.totpPending}
                    <button onclick={confirmTotpSetup} disabled={totpBusy || totpCode.trim().length < 6} class="inline-flex items-center justify-center rounded-xl bg-indigo-600 px-4 py-2.5 text-[13px] font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-30">
                      {totpBusy ? 'Verifying...' : 'Confirm code'}
                    </button>
                  {/if}
                  {#if profile?.totpEnabled}
                    <button onclick={disableTotp} disabled={totpBusy || totpCode.trim().length < 6} class="inline-flex items-center justify-center rounded-xl border border-red-500/20 px-4 py-2.5 text-[13px] font-semibold text-red-200 transition hover:bg-red-500/10 disabled:opacity-30">
                      {totpBusy ? 'Disabling...' : 'Disable authenticator'}
                    </button>
                  {/if}
                </div>
              </div>
              <div class="border border-white/[0.08] bg-black/20 p-4">
                <p class="text-sm font-semibold text-white">Auth factor note</p>
                <p class="mt-2 text-sm text-white/60">Passkeys stay available on registered devices even when authenticator protection is enabled for password sign-in.</p>
              </div>
            </div>
          </div>
        {:else}
          <div class="border-b border-white/[0.08] px-5 py-4">
            <div class="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
              <div>
                <p class="text-xs font-semibold uppercase tracking-wide text-white/35">Passkey Inventory</p>
                <h3 class="mt-1 text-lg font-semibold text-white">Device-based sign-in</h3>
                <p class="mt-1 text-sm text-white/45">Register passkeys for phishing-resistant portal access with biometrics or screen lock.</p>
              </div>
              <span class="rounded-full border px-2.5 py-1 text-[11px] font-medium {passkeys.length ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-300' : 'border-amber-500/20 bg-amber-500/10 text-amber-300'}">
                {passkeys.length ? `${passkeys.length} registered` : 'No passkeys'}
              </span>
            </div>
          </div>
          <div class="space-y-4 p-5">
            {#if !passkeys.length}
              <div class="border border-amber-500/20 bg-amber-500/10 px-4 py-3 text-sm text-white/75">
                No passkey is registered yet. Add one so access does not depend only on password reset and TOTP.
              </div>
            {/if}

            <div class="grid grid-cols-1 gap-4 lg:grid-cols-[minmax(0,1fr)_220px]">
              <div class="space-y-3">
                <label for="passkey-name" class="block text-[11px] font-medium uppercase tracking-wider text-white/35">Passkey label</label>
                <input id="passkey-name" bind:value={friendlyName} placeholder="MacBook, iPhone, Work laptop" class="w-full border border-white/[0.08] bg-black/20 px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 outline-none focus:border-indigo-500/50" />
                <button onclick={registerPasskey} disabled={registering || !passkeysSupported()} class="inline-flex items-center justify-center rounded-xl bg-indigo-600 px-4 py-2.5 text-[13px] font-semibold text-white transition hover:bg-indigo-500 disabled:opacity-30">
                  {registering ? 'Adding...' : 'Add passkey'}
                </button>
                <p class="text-xs text-white/45">{passkeySupportLabel()}</p>
              </div>
              <div class="border border-white/[0.08] bg-black/20 p-4">
                <p class="text-sm font-semibold text-white">Deployment note</p>
                <p class="mt-2 text-sm text-white/60">Register at least one passkey on a primary device before enabling strict operator routines that depend on fast secure re-authentication.</p>
              </div>
            </div>

            <div class="space-y-2">
              {#each passkeys as passkey}
                <div class="flex items-center gap-3 border border-white/[0.08] bg-black/20 px-4 py-3">
                  <KeyRound class="h-4 w-4 shrink-0 text-indigo-300" />
                  <div class="min-w-0 flex-1">
                    <div class="truncate text-sm font-medium text-white">{passkey.friendlyName || 'Unnamed passkey'}</div>
                    <div class="text-[12px] text-white/35">Created {fmt(passkey.createdAt)} · Last used {fmt(passkey.lastUsedAt)}</div>
                  </div>
                  {#if passkeyPendingRemovalId === passkey.id}
                    <div class="flex items-center gap-2">
                      <button onclick={() => confirmRemovePasskey(passkey)} class="inline-flex items-center gap-1 rounded-lg border border-red-500/20 bg-red-500/10 px-2.5 py-2 text-[12px] text-red-200 transition hover:bg-red-500/20">
                        Confirm remove
                      </button>
                      <button onclick={cancelRemovePasskey} class="inline-flex items-center gap-1 rounded-lg px-2 py-2 text-white/55 transition hover:bg-white/[0.06] hover:text-white" title="Cancel">
                        Cancel
                      </button>
                    </div>
                  {:else}
                    <button onclick={() => removePasskey(passkey)} class="inline-flex items-center gap-1 rounded-lg px-2 py-2 text-red-300 transition hover:bg-red-500/10" title="Remove passkey">
                      <Trash2 class="h-4 w-4" />
                    </button>
                  {/if}
                </div>
              {/each}
            </div>
          </div>
        {/if}
        </section>
      </div>
    </div>
  {/if}
</div>
