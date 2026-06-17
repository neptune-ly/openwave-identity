<script>
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { auth } from '$lib/stores/auth';
  import { get } from 'svelte/store';
  import neptuneLogo from '../assets/neptune-logo.png';

  let signedIn = $state(false);

  onMount(() => {
    signedIn = Boolean(get(auth)?.role);
  });

  const endpoints = [
    ['GET', '/v1/identity/resolve', 'Public NPT alias resolution for gateways, apps, and merchants.'],
    ['POST', '/v1/identity/claim', 'Bank-vouched handle claim using X-OpenWave-Bank-Key.'],
    ['POST', '/v1/identity/{handle}/accounts', 'Link a bank-owned account to an existing handle.'],
    ['PATCH', '/v1/identity/{handle}/default', 'Set the default receiving account for the customer.'],
    ['GET', '/v1/banks', 'Public participating bank directory and routing phonebook.'],
    ['GET', '/v1/registry/info', 'Registry operator, governance, and source metadata.']
  ];

  const rules = [
    'A customer owns the global username, for example tellesy.',
    'A bank can only manage accounts it vouched for, for example tellesy@andalus.',
    'Resolution is public, but administration is bank-scoped or registry-admin scoped.',
    'The registry stores routing facts, not balances, transactions, or broad KYC records.'
  ];

  const boundaryRows = [
    ['OpenWave Identity', 'NPT handle ownership, bank-scoped account links, public resolution, bank directory.'],
    ['Gateway or wallet', 'Checkout, presented payment claim, Open Banking consent handoff, remote gateway routing.'],
    ['Bank stack', 'OTP, push approval, account validation, CBS execution, credit notification, webhook-originating bank events.']
  ];
</script>

<svelte:head>
  <title>OpenWave Identity Registry - NPT Developer Portal</title>
  <meta name="description" content="Neptune-built OpenWave Identity Registry for NPT aliases, bank-vouched account links, and gateway identity resolution." />
</svelte:head>

<main class="identity-public">
  <nav class="identity-nav">
    <a class="identity-logo" href="/">
      <span class="identity-mark">OW</span>
      <span>
        <b>OW Identity</b>
        <small>Neptune-built NPT registry</small>
      </span>
    </a>
    <div class="identity-actions">
      <a href="https://neptune-ly.github.io/openwave-spec/" target="_blank" rel="noreferrer">OpenWave Spec</a>
      <button onclick={() => goto(signedIn ? '/portal' : '/login')}>
        {signedIn ? 'Open portal' : 'Sign in'}
      </button>
    </div>
  </nav>

  <section class="identity-hero">
    <div>
      <img class="neptune-public-logo" src={neptuneLogo} alt="Neptune. Financial Technology And Solutions" />
      <p class="identity-kicker">Bank-vouched digital identity for Libya</p>
      <h1>One username. Multiple banks. Public routing, bank-scoped trust.</h1>
      <p class="identity-lede">
        OpenWave Identity is the source of truth for NPT handles such as <code>tellesy</code> and
        bank-qualified aliases such as <code>tellesy@andalus</code>. It lets any compliant gateway
        resolve where money should go, while preserving a bank-vouched customer payment identity
        layer that works across different Libyan bank stacks.
      </p>
      <div class="identity-hero-actions">
        <a class="primary" href="/login">Open admin portal</a>
        <a href="https://github.com/neptune-ly/openwave-identity" target="_blank" rel="noreferrer">View source</a>
      </div>
    </div>

    <div class="identity-panel" aria-label="NPT routing example">
      <div class="identity-panel-head">
        <span>Resolution example</span>
        <b>Public API</b>
      </div>
      <div class="alias-card">
        <small>Customer handle</small>
        <strong>tellesy</strong>
        <span>Default account selected by customer</span>
      </div>
      <div class="route-row">
        <span>tellesy@andalus</span>
        <b>Andalus account</b>
      </div>
      <div class="route-row">
        <span>tellesy@nub</span>
        <b>NUB account</b>
      </div>
      <pre>GET /v1/identity/resolve?alias=tellesy</pre>
    </div>
  </section>

  <section class="identity-grid">
    <article>
      <b>For banks</b>
      <p>Claim handles, link customer accounts, set bank-owned account metadata, and manage only the records your bank vouched for.</p>
      <code>X-OpenWave-Bank-Key: owbk_...</code>
    </article>
    <article>
      <b>For gateways</b>
      <p>Resolve aliases before payment routing, decide whether the debtor or creditor bank is local, and hand off to OW-GIP when another gateway owns the route.</p>
      <code>GET /v1/identity/resolve</code>
    </article>
    <article>
      <b>For registry admins</b>
      <p>Register banks, rotate bank credentials, audit handle activity, and publish operator governance through registry metadata.</p>
      <code>X-OpenWave-Registry-Key</code>
    </article>
  </section>

  <section class="identity-docs">
    <div>
      <p class="identity-kicker">Endpoint map</p>
      <h2>Read the API by operation</h2>
      <p>Identity is intentionally narrow in scope. It resolves handles, records bank-vouched account links, and publishes the bank phonebook. It supports digital identity continuity, but it does not execute payments or replace bank-side KYC and authentication.</p>
    </div>
    <div class="endpoint-list">
      {#each endpoints as endpoint}
        <div class="endpoint-row">
          <span>{endpoint[0]}</span>
          <code>{endpoint[1]}</code>
          <p>{endpoint[2]}</p>
        </div>
      {/each}
    </div>
  </section>

  <section class="identity-topology">
    <div>
      <p class="identity-kicker">Deployment boundary</p>
      <h2>Identity resolves aliases. It does not proxy bank callbacks.</h2>
      <p>
        Some bank deployments expose a public bank edge before private middleware, for example
        <code>Astro -> Andalus -> Nexus</code>. That path is payment infrastructure. OpenWave
        Identity stays outside it and only returns routing facts.
      </p>
    </div>
    <div class="identity-flow-list">
      <div class="identity-flow-item">
        <span>1</span>
        <p><b>Resolve</b> Gateway, wallet, or app asks Identity where an NPT alias routes.</p>
      </div>
      <div class="identity-flow-item">
        <span>2</span>
        <p><b>Route</b> Gateway decides whether the bank is local, remote, or reached through an edge.</p>
      </div>
      <div class="identity-flow-item">
        <span>3</span>
        <p><b>Authorize</b> OTP, push, consent, mandate approval, execution, and webhooks remain in the gateway and bank stack, whether the bank uses Neptune products or its own internal implementation.</p>
      </div>
    </div>
  </section>

  <section class="identity-boundary-table">
    {#each boundaryRows as row}
      <article>
        <b>{row[0]}</b>
        <p>{row[1]}</p>
      </article>
    {/each}
  </section>

  <section class="identity-rules">
    <p class="identity-kicker">Governance rules</p>
    <div>
      {#each rules as rule}
        <p>{rule}</p>
      {/each}
    </div>
  </section>

  <footer>
    <span>Neptune Fintech</span>
    <span>OpenWave Identity Registry</span>
    <a href="https://neptune-ly.github.io/openwave-spec/guide/npt.html" target="_blank" rel="noreferrer">NPT guide</a>
  </footer>
</main>
