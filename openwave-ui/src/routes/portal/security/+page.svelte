<script>
  import { onMount } from 'svelte';
  import { toast } from 'svelte-sonner';
  import KeyRound from 'lucide-svelte/icons/key-round';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import Trash2 from 'lucide-svelte/icons/trash-2';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import { getApi } from '$lib/api/client';
  import { passkeysSupported, registrationCredentialToJson, toPublicKeyCreateOptions } from '$lib/utils/passkeys';

  let loading = $state(true);
  let registering = $state(false);
  let profile = $state(null);
  let passkeys = $state([]);
  let friendlyName = $state('');

  onMount(loadSecurity);

  async function loadSecurity() {
    loading = true;
    try {
      const api = getApi();
      const [profileResponse, passkeyResponse] = await Promise.all([
        api.get('/auth/profile'),
        api.get('/auth/passkeys'),
      ]);
      profile = profileResponse.data;
      passkeys = passkeyResponse.data?.passkeys || [];
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not load account security');
    } finally {
      loading = false;
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
    if (!confirm('Remove this passkey from your account?')) return;
    try {
      await getApi().delete(`/auth/passkeys/${passkey.id}`);
      toast.success('Passkey removed');
      await loadSecurity();
    } catch (error) {
      toast.error(error?.response?.data?.message || error?.response?.data?.error || 'Could not remove passkey');
    }
  }

  function fmt(value) {
    return value ? new Date(value).toLocaleString() : 'Never';
  }
</script>

<svelte:head><title>Account Security - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-5xl mx-auto">
  <div class="flex items-start justify-between gap-4 mb-8">
    <div>
      <h1 class="text-2xl font-semibold tracking-tight">Account Security</h1>
      <p class="text-white/40 text-sm mt-1">Manage your Identity portal profile, password recovery, and passkeys.</p>
    </div>
    <button onclick={loadSecurity} class="px-4 py-2 rounded-xl border border-white/[0.09] bg-white/[0.035] hover:bg-white/[0.06] text-[13px] text-white/70 flex items-center gap-2">
      <RefreshCw class="w-3.5 h-3.5" />
      Refresh
    </button>
  </div>

  {#if loading}
    <div class="rounded-2xl border border-white/[0.07] bg-white/[0.025] p-8 text-sm text-white/40">Loading account security...</div>
  {:else}
    <div class="grid grid-cols-1 lg:grid-cols-[1fr_1.2fr] gap-6">
      <section class="rounded-2xl border border-white/[0.07] bg-white/[0.025] p-6">
        <div class="flex items-center gap-3 mb-5">
          <div class="w-10 h-10 rounded-xl bg-indigo-500/10 border border-indigo-500/20 text-indigo-300 flex items-center justify-center">
            <ShieldCheck class="w-5 h-5" />
          </div>
          <div>
            <div class="text-sm font-semibold">Profile</div>
            <div class="text-[12px] text-white/30">Used for sign-in and recovery</div>
          </div>
        </div>
        <dl class="space-y-3 text-sm">
          <div><dt class="text-white/30">Username</dt><dd class="font-mono text-white">{profile?.username}</dd></div>
          <div><dt class="text-white/30">Display name</dt><dd class="text-white">{profile?.displayName || '—'}</dd></div>
          <div><dt class="text-white/30">Role</dt><dd class="text-white">{profile?.portalRole}</dd></div>
          <div><dt class="text-white/30">Email</dt><dd class="text-white">{profile?.email || 'Not configured'}</dd></div>
          <div><dt class="text-white/30">Last login</dt><dd class="text-white">{fmt(profile?.lastLoginAt)}</dd></div>
        </dl>
      </section>

      <section class="rounded-2xl border border-white/[0.07] bg-white/[0.025] p-6">
        <div class="flex items-start justify-between gap-3 mb-5">
          <div class="flex items-center gap-3">
            <div class="w-10 h-10 rounded-xl bg-indigo-500/10 border border-indigo-500/20 text-indigo-300 flex items-center justify-center">
              <KeyRound class="w-5 h-5" />
            </div>
            <div>
              <div class="text-sm font-semibold">Passkeys</div>
              <div class="text-[12px] text-white/30">Use device biometrics or screen lock for future sign-ins</div>
            </div>
          </div>
          <span class="px-2 py-1 rounded-full border border-white/[0.08] text-[11px] text-white/50">{passkeys.length}</span>
        </div>

        {#if passkeys.length === 0}
          <div class="rounded-xl border border-amber-400/20 bg-amber-400/[0.08] px-4 py-3 text-sm text-amber-100/80 mb-4">
            No passkey is registered yet. Add one after signing in so access does not depend only on password reset emails.
          </div>
        {/if}

        <div class="flex flex-col sm:flex-row gap-2 mb-4">
          <input bind:value={friendlyName} placeholder="Passkey name, e.g. MacBook or iPhone" class="flex-1 bg-white/[0.05] border border-white/[0.1] rounded-xl px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:outline-none focus:border-indigo-500/50" />
          <button onclick={registerPasskey} disabled={registering || !passkeysSupported()} class="px-4 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 disabled:opacity-30 text-white text-[13px] font-semibold">
            {registering ? 'Adding...' : 'Add passkey'}
          </button>
        </div>

        <div class="space-y-2">
          {#each passkeys as passkey}
            <div class="rounded-xl border border-white/[0.07] bg-black/20 px-4 py-3 flex items-center gap-3">
              <KeyRound class="w-4 h-4 text-indigo-300 shrink-0" />
              <div class="min-w-0 flex-1">
                <div class="text-sm font-medium text-white truncate">{passkey.friendlyName || 'Unnamed passkey'}</div>
                <div class="text-[12px] text-white/30">Created {fmt(passkey.createdAt)} · Last used {fmt(passkey.lastUsedAt)}</div>
              </div>
              <button onclick={() => removePasskey(passkey)} class="p-2 rounded-lg hover:bg-red-500/10 text-red-300">
                <Trash2 class="w-4 h-4" />
              </button>
            </div>
          {/each}
        </div>
      </section>
    </div>
  {/if}
</div>
