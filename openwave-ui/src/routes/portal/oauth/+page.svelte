<script>
  import { onMount } from 'svelte';
  import { toast } from 'svelte-sonner';
  import Activity from 'lucide-svelte/icons/activity';
  import AlertTriangle from 'lucide-svelte/icons/alert-triangle';
  import CheckCircle2 from 'lucide-svelte/icons/check-circle-2';
  import ClipboardCopy from 'lucide-svelte/icons/clipboard-copy';
  import KeyRound from 'lucide-svelte/icons/key-round';
  import LockKeyhole from 'lucide-svelte/icons/lock-keyhole';
  import Power from 'lucide-svelte/icons/power';
  import RefreshCw from 'lucide-svelte/icons/refresh-cw';
  import RotateCw from 'lucide-svelte/icons/rotate-cw';
  import ShieldCheck from 'lucide-svelte/icons/shield-check';
  import SlidersHorizontal from 'lucide-svelte/icons/sliders-horizontal';
  import UserX from 'lucide-svelte/icons/user-x';
  import { getApi } from '$lib/api/client';

  const supportedScopes = [
    'astro:payments.create',
    'astro:payments.read',
    'astro:merchant.reports.read',
    'astro:merchant.webhooks.manage',
    'astro:bank.reports.read',
    'identity:registry.read',
    'identity:bank.aliases.read',
    'identity:customer.profile.read',
    'openwave:mcp.read',
    'openwave:mcp.write',
    'openwave:tokens.introspect',
    'openwave:owner.ops.read'
  ];

  const switchGroups = [
    {
      title: 'Global gates',
      items: [
        ['oauth.global', 'OAuth token issuance'],
        ['mcp.global', 'Remote MCP access'],
        ['mcp.mutations', 'MCP write tools']
      ]
    },
    {
      title: 'Environment gates',
      items: [
        ['environment.sandbox', 'Sandbox'],
        ['environment.live', 'Live']
      ]
    },
    {
      title: 'Owner gates',
      items: [
        ['owner.NEPTUNE', 'Neptune owner clients'],
        ['owner.MERCHANT', 'Merchant clients'],
        ['owner.BANK', 'Bank clients'],
        ['owner.CUSTOMER', 'Customer clients']
      ]
    }
  ];

  const blankCreateForm = {
    displayName: '',
    clientType: 'CONFIDENTIAL',
    ownerType: 'NEPTUNE',
    ownerId: '',
    ownerHandle: '',
    redirectUrisText: '',
    allowedScopes: ['openwave:mcp.read', 'openwave:owner.ops.read'],
    allowedEnvironments: ['SANDBOX'],
    active: true,
    mcpEnabled: false,
    liveEnabled: false
  };

  let loading = $state(true);
  let saving = $state(false);
  let settings = $state([]);
  let clients = $state([]);
  let grants = $state([]);
  let createForm = $state({ ...blankCreateForm });
  let oneTimeSecret = $state(null);
  let revokeSubject = $state('');

  onMount(() => {
    loadAll();
  });

  const settingMap = $derived(Object.fromEntries(settings.map((setting) => [setting.key, setting])));
  const activeClients = $derived(clients.filter((client) => client.active && !client.revoked_at).length);
  const mcpClients = $derived(clients.filter((client) => client.mcp_enabled).length);
  const liveClients = $derived(clients.filter((client) => client.live_enabled).length);
  const resourceServers = $derived(clients.filter((client) => client.client_type === 'RESOURCE_SERVER').length);
  const activeGrants = $derived(grants.filter((grant) => grant.active).length);
  const globalEnabled = $derived(Boolean(settingMap['oauth.global']?.enabled));
  const mcpEnabled = $derived(Boolean(settingMap['mcp.global']?.enabled));
  const mutationEnabled = $derived(Boolean(settingMap['mcp.mutations']?.enabled));

  async function loadAll() {
    loading = true;
    try {
      const api = getApi();
      const [settingsResponse, clientsResponse, grantsResponse] = await Promise.all([
        api.get('/oauth/admin/settings'),
        api.get('/oauth/admin/clients'),
        api.get('/oauth/admin/grants')
      ]);
      settings = settingsResponse.data?.settings ?? [];
      clients = clientsResponse.data?.clients ?? [];
      grants = grantsResponse.data?.grants ?? [];
    } catch (error) {
      toast.error(errorMessage(error, 'Could not load OAuth controls'));
    } finally {
      loading = false;
    }
  }

  function errorMessage(error, fallback) {
    return error?.response?.data?.message || error?.response?.data?.error || error?.message || fallback;
  }

  function statusTone(enabled) {
    return enabled ? 'border-emerald-400/20 bg-emerald-400/10 text-emerald-200' : 'border-amber-400/20 bg-amber-400/10 text-amber-100';
  }

  function clientTone(client) {
    if (!client.active || client.revoked_at) return 'border-red-400/20 bg-red-400/10 text-red-200';
    if (client.live_enabled) return 'border-amber-400/20 bg-amber-400/10 text-amber-100';
    return 'border-emerald-400/20 bg-emerald-400/10 text-emerald-200';
  }

  async function setSwitch(key, enabled) {
    saving = true;
    try {
      await getApi().patch('/oauth/admin/settings', { [key]: enabled });
      await loadAll();
      toast.success(`${key} ${enabled ? 'enabled' : 'disabled'}`);
    } catch (error) {
      toast.error(errorMessage(error, 'Could not update switch'));
    } finally {
      saving = false;
    }
  }

  function toggleScope(scope) {
    const next = createForm.allowedScopes.includes(scope)
      ? createForm.allowedScopes.filter((item) => item !== scope)
      : [...createForm.allowedScopes, scope];
    createForm = { ...createForm, allowedScopes: next };
  }

  function toggleEnvironment(environment) {
    const next = createForm.allowedEnvironments.includes(environment)
      ? createForm.allowedEnvironments.filter((item) => item !== environment)
      : [...createForm.allowedEnvironments, environment];
    createForm = { ...createForm, allowedEnvironments: next };
  }

  function redirectUris() {
    return createForm.redirectUrisText
      .split('\n')
      .map((item) => item.trim())
      .filter(Boolean);
  }

  async function createClient() {
    if (!createForm.displayName.trim()) {
      toast.error('Client name is required');
      return;
    }
    if (createForm.allowedScopes.length === 0) {
      toast.error('Select at least one scope');
      return;
    }
    if (createForm.allowedEnvironments.length === 0) {
      toast.error('Select at least one environment');
      return;
    }
    saving = true;
    try {
      const response = await getApi().post('/oauth/admin/clients', {
        displayName: createForm.displayName.trim(),
        clientType: createForm.clientType,
        ownerType: createForm.ownerType,
        ownerId: createForm.ownerId.trim() || null,
        ownerHandle: createForm.ownerHandle.trim() || null,
        redirectUris: redirectUris(),
        allowedScopes: createForm.allowedScopes,
        allowedEnvironments: createForm.allowedEnvironments,
        active: createForm.active,
        mcpEnabled: createForm.mcpEnabled,
        liveEnabled: createForm.liveEnabled
      });
      oneTimeSecret = response.data?.client_secret
        ? { clientId: response.data.client_id, secret: response.data.client_secret }
        : null;
      createForm = { ...blankCreateForm };
      await loadAll();
      toast.success('OAuth client created');
    } catch (error) {
      toast.error(errorMessage(error, 'Could not create OAuth client'));
    } finally {
      saving = false;
    }
  }

  async function patchClient(client, patch) {
    saving = true;
    try {
      await getApi().patch(`/oauth/admin/clients/${encodeURIComponent(client.client_id)}`, patch);
      await loadAll();
      toast.success('Client updated');
    } catch (error) {
      toast.error(errorMessage(error, 'Could not update client'));
    } finally {
      saving = false;
    }
  }

  async function rotateSecret(client) {
    if (!window.confirm(`Rotate secret for ${client.display_name || client.client_id}? Existing secret users will stop working.`)) return;
    saving = true;
    try {
      const response = await getApi().post(`/oauth/admin/clients/${encodeURIComponent(client.client_id)}/rotate-secret`);
      oneTimeSecret = response.data?.client_secret
        ? { clientId: response.data.client_id, secret: response.data.client_secret }
        : null;
      await loadAll();
      toast.success('Client secret rotated');
    } catch (error) {
      toast.error(errorMessage(error, 'Could not rotate secret'));
    } finally {
      saving = false;
    }
  }

  async function revokeClientTokens(client) {
    if (!window.confirm(`Revoke all tokens for ${client.display_name || client.client_id}?`)) return;
    saving = true;
    try {
      const response = await getApi().post(`/oauth/admin/clients/${encodeURIComponent(client.client_id)}/revoke-tokens`);
      await loadAll();
      toast.success(`${response.data?.revoked_tokens ?? 0} token(s) revoked`);
    } catch (error) {
      toast.error(errorMessage(error, 'Could not revoke client tokens'));
    } finally {
      saving = false;
    }
  }

  async function revokeUserGrants() {
    const subject = revokeSubject.trim();
    if (!subject) {
      toast.error('Subject is required');
      return;
    }
    if (!window.confirm(`Revoke OAuth grants and tokens for subject ${subject}?`)) return;
    saving = true;
    try {
      const response = await getApi().post(`/oauth/admin/users/${encodeURIComponent(subject)}/revoke-grants`);
      revokeSubject = '';
      toast.success(`${response.data?.revoked_tokens ?? 0} token(s), ${response.data?.revoked_grants ?? 0} grant(s) revoked`);
    } catch (error) {
      toast.error(errorMessage(error, 'Could not revoke user grants'));
    } finally {
      saving = false;
    }
  }

  async function revokeGrant(grant) {
    if (!window.confirm(`Revoke delegated grant ${grant.grant_id} for ${grant.subject}?`)) return;
    saving = true;
    try {
      const response = await getApi().post(`/oauth/admin/grants/${encodeURIComponent(grant.grant_id)}/revoke`);
      await loadAll();
      toast.success(`${response.data?.revoked_tokens ?? 0} token(s) revoked`);
    } catch (error) {
      toast.error(errorMessage(error, 'Could not revoke grant'));
    } finally {
      saving = false;
    }
  }

  async function copySecret() {
    if (!oneTimeSecret?.secret) return;
    await navigator.clipboard.writeText(oneTimeSecret.secret);
    toast.success('Secret copied');
  }
</script>

<svelte:head><title>OAuth Control - OpenWave Identity</title></svelte:head>

<div class="p-8 max-w-7xl mx-auto space-y-5">
  <section class="identity-expressive-band p-6">
    <div class="flex flex-col gap-5 xl:flex-row xl:items-end xl:justify-between">
      <div class="max-w-3xl">
        <p class="text-[11px] uppercase tracking-[0.18em] text-white/30">OAuth And Remote MCP</p>
        <h1 class="identity-page-title mt-2 text-3xl font-semibold tracking-tight text-white">Access Control</h1>
        <p class="identity-section-note mt-2 text-sm text-white/55">Manage ecosystem OAuth clients, Remote MCP gates, environment access, and revocation from one operator surface.</p>
        <div class="mt-4 flex flex-wrap gap-2 text-xs text-white/45">
          <span class="identity-role-accent">Client credentials v1</span>
          <span class="identity-role-accent">Opaque tokens through introspection</span>
          <span class="identity-role-accent">Kill switches before throughput</span>
        </div>
      </div>
      <div class="flex flex-wrap gap-2">
        <button onclick={loadAll} disabled={loading || saving} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium transition-all hover:text-white disabled:opacity-40">
          <RefreshCw class={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>
    </div>
  </section>

  <div class="grid gap-3 md:grid-cols-4">
    <div class="identity-kpi-card p-5">
      <p class="text-xs text-white/35">OAuth global</p>
      <p class={`mt-3 inline-flex rounded-full border px-2.5 py-1 text-xs ${statusTone(globalEnabled)}`}>{globalEnabled ? 'Enabled' : 'Off'}</p>
    </div>
    <div class="identity-kpi-card p-5">
      <p class="text-xs text-white/35">Remote MCP</p>
      <p class={`mt-3 inline-flex rounded-full border px-2.5 py-1 text-xs ${statusTone(mcpEnabled)}`}>{mcpEnabled ? 'Enabled' : 'Off'}</p>
    </div>
    <div class="identity-kpi-card p-5">
      <p class="text-xs text-white/35">Active clients</p>
      <p class="mt-2 text-2xl font-semibold text-white">{activeClients}</p>
    </div>
    <div class="identity-kpi-card p-5">
      <p class="text-xs text-white/35">Live clients</p>
      <p class="mt-2 text-2xl font-semibold text-white">{liveClients}</p>
    </div>
    <div class="identity-kpi-card p-5 md:col-span-2">
      <p class="text-xs text-white/35">Resource servers</p>
      <p class="mt-2 text-2xl font-semibold text-white">{resourceServers}</p>
    </div>
    <div class="identity-kpi-card p-5 md:col-span-2">
      <p class="text-xs text-white/35">Active delegated grants</p>
      <p class="mt-2 text-2xl font-semibold text-white">{activeGrants}</p>
    </div>
  </div>

  {#if oneTimeSecret}
    <section class="rounded-2xl border border-amber-400/25 bg-amber-400/10 p-5">
      <div class="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
        <div>
          <div class="flex items-center gap-2 text-sm font-semibold text-amber-100">
            <KeyRound class="h-4 w-4" />
            One-time client secret
          </div>
          <p class="mt-1 text-xs text-amber-100/70">This value is returned only from create or rotate. Store it in the approved secret manager, not in docs or tickets.</p>
          <p class="mt-3 font-mono text-xs text-amber-50 break-all">{oneTimeSecret.clientId}: {oneTimeSecret.secret}</p>
        </div>
        <div class="flex gap-2">
          <button onclick={copySecret} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-4 py-2 text-[13px] font-medium text-amber-50 transition-all hover:text-white">
            <ClipboardCopy class="h-4 w-4" />
            Copy secret
          </button>
          <button onclick={() => (oneTimeSecret = null)} class="identity-shell-button rounded-xl border px-4 py-2 text-[13px] text-white/65 transition-all hover:text-white">
            Dismiss
          </button>
        </div>
      </div>
    </section>
  {/if}

  <div class="grid gap-5 xl:grid-cols-[0.92fr_1.08fr]">
    <section class="identity-surface-card p-5">
      <div class="flex items-center gap-2 text-sm font-semibold text-white">
        <Power class="h-4 w-4 text-indigo-300" />
        Operator gates
      </div>
      <p class="mt-1 text-sm text-white/45">These switches fail closed at token issuance, introspection, and Remote MCP access.</p>

      {#if loading}
        <div class="mt-5 text-sm text-white/40">Loading switches...</div>
      {:else}
        <div class="mt-5 space-y-5">
          {#each switchGroups as group}
            <div>
              <h2 class="text-[11px] uppercase tracking-[0.16em] text-white/30">{group.title}</h2>
              <div class="mt-3 grid gap-2">
                {#each group.items as item}
                  {@const key = item[0]}
                  {@const label = item[1]}
                  {@const enabled = Boolean(settingMap[key]?.enabled)}
                  <div class="flex items-center justify-between gap-3 rounded-2xl border border-white/[0.07] bg-white/[0.025] px-4 py-3">
                    <div>
                      <div class="text-sm font-medium text-white">{label}</div>
                      <div class="mt-0.5 font-mono text-[11px] text-white/30">{key}</div>
                    </div>
                    <button
                      onclick={() => setSwitch(key, !enabled)}
                      disabled={saving}
                      class={`min-w-[92px] rounded-xl border px-3 py-2 text-xs font-semibold transition-all disabled:opacity-40 ${enabled ? 'border-emerald-400/25 bg-emerald-400/10 text-emerald-100' : 'border-white/[0.08] bg-white/[0.035] text-white/55 hover:text-white'}`}
                    >
                      {enabled ? 'Enabled' : 'Off'}
                    </button>
                  </div>
                {/each}
              </div>
            </div>
          {/each}
        </div>
      {/if}
    </section>

    <section class="identity-surface-card p-5">
      <div class="flex items-center gap-2 text-sm font-semibold text-white">
        <SlidersHorizontal class="h-4 w-4 text-indigo-300" />
        Register client
      </div>
      <p class="mt-1 text-sm text-white/45">Default posture is sandbox-only and read-first. Live and MCP access require explicit controls.</p>

      <div class="mt-5 grid gap-3 md:grid-cols-2">
        <label class="space-y-1">
          <span class="text-xs text-white/40">Client name</span>
          <input bind:value={createForm.displayName} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none" placeholder="Neptune owner MCP client" />
        </label>
        <label class="space-y-1">
          <span class="text-xs text-white/40">Client type</span>
          <select bind:value={createForm.clientType} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none">
            <option value="CONFIDENTIAL">Confidential</option>
            <option value="PUBLIC">Public</option>
            <option value="AGENT">Agent</option>
            <option value="RESOURCE_SERVER">Resource server</option>
          </select>
        </label>
        <label class="space-y-1">
          <span class="text-xs text-white/40">Owner type</span>
          <select bind:value={createForm.ownerType} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white focus:border-indigo-500/50 focus:outline-none">
            <option value="NEPTUNE">Neptune</option>
            <option value="MERCHANT">Merchant</option>
            <option value="BANK">Bank</option>
            <option value="CUSTOMER">Customer</option>
          </select>
        </label>
        <label class="space-y-1">
          <span class="text-xs text-white/40">Owner handle</span>
          <input bind:value={createForm.ownerHandle} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none" placeholder="andalus, merchant handle, or blank" />
        </label>
        <label class="space-y-1 md:col-span-2">
          <span class="text-xs text-white/40">Owner id</span>
          <input bind:value={createForm.ownerId} class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none" placeholder="Optional tenant id" />
        </label>
        <label class="space-y-1 md:col-span-2">
          <span class="text-xs text-white/40">Redirect URIs</span>
          <textarea bind:value={createForm.redirectUrisText} rows="3" class="w-full rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none" placeholder="One URI per line for future PKCE clients"></textarea>
        </label>
      </div>

      <div class="mt-5 grid gap-4 lg:grid-cols-2">
        <div>
          <h3 class="text-[11px] uppercase tracking-[0.16em] text-white/30">Scopes</h3>
          <div class="mt-3 grid gap-2">
            {#each supportedScopes as scope}
              <label class="flex items-center gap-2 rounded-xl border border-white/[0.07] bg-white/[0.025] px-3 py-2 text-xs text-white/70">
                <input type="checkbox" checked={createForm.allowedScopes.includes(scope)} onchange={() => toggleScope(scope)} class="checkbox checkbox-xs" />
                <span class="font-mono">{scope}</span>
              </label>
            {/each}
          </div>
        </div>
        <div class="space-y-4">
          <div>
            <h3 class="text-[11px] uppercase tracking-[0.16em] text-white/30">Environment</h3>
            <div class="mt-3 flex flex-wrap gap-2">
              {#each ['SANDBOX', 'LIVE'] as environment}
                <button onclick={() => toggleEnvironment(environment)} class={`rounded-xl border px-3 py-2 text-xs font-semibold transition-all ${createForm.allowedEnvironments.includes(environment) ? 'border-indigo-400/30 bg-indigo-400/10 text-indigo-100' : 'border-white/[0.08] text-white/45 hover:text-white'}`}>
                  {environment}
                </button>
              {/each}
            </div>
          </div>
          <div>
            <h3 class="text-[11px] uppercase tracking-[0.16em] text-white/30">Client posture</h3>
            <div class="mt-3 grid gap-2">
              <label class="flex items-center justify-between rounded-xl border border-white/[0.07] bg-white/[0.025] px-3 py-2 text-sm text-white/70">
                Active
                <input type="checkbox" bind:checked={createForm.active} class="toggle toggle-sm" />
              </label>
              <label class="flex items-center justify-between rounded-xl border border-white/[0.07] bg-white/[0.025] px-3 py-2 text-sm text-white/70">
                MCP enabled
                <input type="checkbox" bind:checked={createForm.mcpEnabled} class="toggle toggle-sm" />
              </label>
              <label class="flex items-center justify-between rounded-xl border border-white/[0.07] bg-white/[0.025] px-3 py-2 text-sm text-white/70">
                Live enabled
                <input type="checkbox" bind:checked={createForm.liveEnabled} class="toggle toggle-sm" />
              </label>
            </div>
          </div>
          <button onclick={createClient} disabled={saving} class="mt-2 inline-flex w-full items-center justify-center gap-2 rounded-xl bg-indigo-600 px-4 py-3 text-sm font-semibold text-white transition-all hover:bg-indigo-500 disabled:opacity-40">
            <KeyRound class="h-4 w-4" />
            Create client
          </button>
        </div>
      </div>
    </section>
  </div>

  <section class="identity-surface-card overflow-hidden">
    <div class="border-b border-white/[0.06] px-5 py-4">
      <div class="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <div>
          <div class="flex items-center gap-2 text-sm font-semibold text-white">
            <ShieldCheck class="h-4 w-4 text-indigo-300" />
            Registered clients
          </div>
          <p class="mt-1 text-sm text-white/45">{clients.length} client(s), {mcpClients} MCP-enabled, {liveClients} live-enabled.</p>
        </div>
        {#if mutationEnabled}
          <span class="inline-flex items-center gap-2 rounded-full border border-red-400/25 bg-red-400/10 px-3 py-1.5 text-xs text-red-100">
            <AlertTriangle class="h-3.5 w-3.5" />
            MCP mutations enabled
          </span>
        {:else}
          <span class="inline-flex items-center gap-2 rounded-full border border-white/[0.08] bg-white/[0.035] px-3 py-1.5 text-xs text-white/45">
            <LockKeyhole class="h-3.5 w-3.5" />
            MCP write tools off
          </span>
        {/if}
      </div>
    </div>

    {#if loading}
      <div class="p-8 text-sm text-white/40">Loading clients...</div>
    {:else if clients.length === 0}
      <div class="p-10 text-center">
        <Activity class="mx-auto mb-3 h-8 w-8 text-white/25" />
        <div class="font-semibold text-white">No OAuth clients registered</div>
        <div class="mt-1 text-sm text-white/35">Create the first sandbox client before enabling global OAuth.</div>
      </div>
    {:else}
      <div class="overflow-x-auto">
        <table class="w-full min-w-[1120px] text-left">
          <thead class="bg-white/[0.035] text-[11px] uppercase tracking-[0.16em] text-white/30">
            <tr>
              <th class="px-4 py-3 font-medium">Client</th>
              <th class="px-4 py-3 font-medium">Owner</th>
              <th class="px-4 py-3 font-medium">Posture</th>
              <th class="px-4 py-3 font-medium">Scopes</th>
              <th class="px-4 py-3 font-medium">Environments</th>
              <th class="px-4 py-3 font-medium text-right">Actions</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-white/[0.06]">
            {#each clients as client}
              <tr class="align-top transition-colors hover:bg-white/[0.02]">
                <td class="px-4 py-4">
                  <div class="font-semibold text-white">{client.display_name}</div>
                  <div class="mt-1 font-mono text-[12px] text-white/35">{client.client_id}</div>
                  <div class="mt-2 flex flex-wrap gap-1">
                    <span class={`rounded-full border px-2 py-0.5 text-[11px] ${clientTone(client)}`}>{client.active ? 'Active' : 'Disabled'}</span>
                    <span class="rounded-full border border-white/[0.08] px-2 py-0.5 text-[11px] text-white/45">{client.client_type}</span>
                  </div>
                </td>
                <td class="px-4 py-4">
                  <div class="text-sm text-white/80">{client.owner_type}</div>
                  <div class="mt-1 font-mono text-[12px] text-white/35">{client.owner_handle || client.owner_id || 'owner scope only'}</div>
                </td>
                <td class="px-4 py-4">
                  <div class="grid gap-2">
                    <label class="flex items-center justify-between gap-3 rounded-xl border border-white/[0.07] bg-white/[0.025] px-3 py-2 text-xs text-white/65">
                      Active
                      <input type="checkbox" checked={client.active} onchange={() => patchClient(client, { active: !client.active })} class="toggle toggle-xs" disabled={saving} />
                    </label>
                    <label class="flex items-center justify-between gap-3 rounded-xl border border-white/[0.07] bg-white/[0.025] px-3 py-2 text-xs text-white/65">
                      MCP
                      <input type="checkbox" checked={client.mcp_enabled} onchange={() => patchClient(client, { mcpEnabled: !client.mcp_enabled })} class="toggle toggle-xs" disabled={saving} />
                    </label>
                    <label class="flex items-center justify-between gap-3 rounded-xl border border-white/[0.07] bg-white/[0.025] px-3 py-2 text-xs text-white/65">
                      Live
                      <input type="checkbox" checked={client.live_enabled} onchange={() => patchClient(client, { liveEnabled: !client.live_enabled })} class="toggle toggle-xs" disabled={saving} />
                    </label>
                  </div>
                </td>
                <td class="px-4 py-4">
                  <div class="flex max-w-md flex-wrap gap-1.5">
                    {#each client.allowed_scopes ?? [] as scope}
                      <span class="rounded-full border border-white/[0.08] bg-white/[0.025] px-2 py-1 font-mono text-[10px] text-white/55">{scope}</span>
                    {/each}
                  </div>
                </td>
                <td class="px-4 py-4">
                  <div class="flex flex-wrap gap-1.5">
                    {#each client.allowed_environments ?? [] as environment}
                      <span class="rounded-full border border-white/[0.08] px-2 py-1 text-[11px] text-white/55">{environment}</span>
                    {/each}
                  </div>
                </td>
                <td class="px-4 py-4">
                  <div class="flex flex-col items-end gap-2">
                    {#if client.client_type !== 'PUBLIC'}
                      <button onclick={() => rotateSecret(client)} disabled={saving} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-3 py-2 text-xs text-white/65 transition-all hover:text-white disabled:opacity-40">
                        <RotateCw class="h-3.5 w-3.5" />
                        Rotate secret
                      </button>
                    {/if}
                    <button onclick={() => revokeClientTokens(client)} disabled={saving} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-3 py-2 text-xs text-white/65 transition-all hover:text-white disabled:opacity-40">
                      <UserX class="h-3.5 w-3.5" />
                      Revoke tokens
                    </button>
                  </div>
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {/if}
  </section>

  <section class="identity-surface-card p-5">
    <div class="flex flex-col gap-4 md:flex-row md:items-end md:justify-between">
      <div>
        <div class="flex items-center gap-2 text-sm font-semibold text-white">
          <UserX class="h-4 w-4 text-indigo-300" />
          User or subject revocation
        </div>
        <p class="mt-1 text-sm text-white/45">Revoke delegated grants and active tokens for one OAuth subject without disabling their normal portal account.</p>
      </div>
      <div class="flex w-full gap-2 md:max-w-xl">
        <input bind:value={revokeSubject} class="min-w-0 flex-1 rounded-xl border border-white/[0.1] bg-white/[0.05] px-3.5 py-2.5 text-[13px] text-white placeholder-white/20 focus:border-indigo-500/50 focus:outline-none" placeholder="OAuth subject or client id" />
        <button onclick={revokeUserGrants} disabled={saving} class="inline-flex items-center gap-2 rounded-xl bg-red-500 px-4 py-2.5 text-[13px] font-semibold text-white transition-all hover:bg-red-400 disabled:opacity-40">
          <UserX class="h-4 w-4" />
          Revoke
        </button>
      </div>
    </div>
  </section>

  <section class="identity-surface-card overflow-hidden">
    <div class="border-b border-white/[0.06] px-5 py-4">
      <div class="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
        <div>
          <div class="flex items-center gap-2 text-sm font-semibold text-white">
            <LockKeyhole class="h-4 w-4 text-indigo-300" />
            Delegated grants
          </div>
          <p class="mt-1 text-sm text-white/45">Customer, bank, or operator approvals issued through OAuth authorization-code consent.</p>
        </div>
        <span class="rounded-full border border-white/[0.08] px-3 py-1 text-xs text-white/45">{activeGrants} active</span>
      </div>
    </div>

    {#if loading}
      <div class="p-8 text-sm text-white/40">Loading grants...</div>
    {:else if grants.length === 0}
      <div class="p-8 text-sm text-white/40">No delegated grants recorded.</div>
    {:else}
      <div class="overflow-x-auto">
        <table class="w-full min-w-[920px] text-left">
          <thead class="bg-white/[0.035] text-[11px] uppercase tracking-[0.16em] text-white/30">
            <tr>
              <th class="px-4 py-3 font-medium">Subject</th>
              <th class="px-4 py-3 font-medium">Client</th>
              <th class="px-4 py-3 font-medium">Scope</th>
              <th class="px-4 py-3 font-medium">Posture</th>
              <th class="px-4 py-3 font-medium text-right">Action</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-white/[0.06]">
            {#each grants as grant}
              <tr class="align-top transition-colors hover:bg-white/[0.02]">
                <td class="px-4 py-4">
                  <div class="font-mono text-sm text-white">{grant.subject}</div>
                  <div class="mt-1 text-[12px] text-white/35">Grant {grant.grant_id}</div>
                </td>
                <td class="px-4 py-4">
                  <div class="font-mono text-sm text-white/75">{grant.client_id}</div>
                  <div class="mt-1 text-[12px] text-white/35">{grant.owner_type}{grant.owner_handle ? ` / ${grant.owner_handle}` : ''}</div>
                </td>
                <td class="px-4 py-4">
                  <div class="flex max-w-md flex-wrap gap-1.5">
                    {#each grant.scopes ?? [] as scope}
                      <span class="rounded-full border border-white/[0.08] bg-white/[0.025] px-2 py-1 font-mono text-[10px] text-white/55">{scope}</span>
                    {/each}
                  </div>
                </td>
                <td class="px-4 py-4">
                  <span class={`rounded-full border px-2.5 py-1 text-xs ${grant.active ? 'border-emerald-400/20 bg-emerald-400/10 text-emerald-200' : 'border-red-400/20 bg-red-400/10 text-red-200'}`}>
                    {grant.active ? 'Active' : 'Revoked'}
                  </span>
                  <div class="mt-2 text-[12px] text-white/35">{grant.environment} / {grant.audience}</div>
                </td>
                <td class="px-4 py-4 text-right">
                  {#if grant.active}
                    <button onclick={() => revokeGrant(grant)} disabled={saving} class="identity-shell-button inline-flex items-center gap-2 rounded-xl border px-3 py-2 text-xs text-white/65 transition-all hover:text-white disabled:opacity-40">
                      <UserX class="h-3.5 w-3.5" />
                      Revoke grant
                    </button>
                  {:else}
                    <span class="text-xs text-white/30">No action</span>
                  {/if}
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {/if}
  </section>

  <section class="rounded-2xl border border-white/[0.07] bg-white/[0.02] p-5">
    <div class="flex items-start gap-3">
      {#if globalEnabled && mcpEnabled}
        <CheckCircle2 class="mt-0.5 h-5 w-5 shrink-0 text-emerald-300" />
      {:else}
        <AlertTriangle class="mt-0.5 h-5 w-5 shrink-0 text-amber-300" />
      {/if}
      <div>
        <div class="text-sm font-semibold text-white">Operational rule</div>
        <p class="mt-1 text-sm text-white/45">OAuth and MCP are additive to existing portal/API-key flows. Keep global access off until the test client, scopes, environment, Astro resource-server flag, and operator rollback path are all verified.</p>
      </div>
    </div>
  </section>
</div>
