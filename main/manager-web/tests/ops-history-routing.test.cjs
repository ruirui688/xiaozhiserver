const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const Vue = require('vue');
const VueRouter = require('vue-router');
const { loadModule } = require('./helpers/loadVueComponent.cjs');
const { legacyOperationsTarget } = loadModule('src/ops/routeLocation.js');
const webRoot = path.resolve(__dirname, '..');
const browserTestContexts = new WeakSet();

test('old operations bookmarks retain the route, query and anchor under the history base', () => {
  const cases = [
    ['#/login', '/ops/login'],
    ['#/model-billing?account=example#usage', '/ops/model-billing?account=example#usage'],
    ['#/recover?ticket=example%2Ftoken', '/ops/recover?ticket=example%2Ftoken'],
    ['#/enroll', '/ops/enroll'],
    ['#/', '/ops/'],
    ['', '/ops/'],
  ];
  for (const [hash, target] of cases) assert.equal(legacyOperationsTarget({ pathname: '/ops.html', hash }), target);
  for (const pathname of ['/ops/login', '/ops/services', '/', '/index.html', '/xiaozhi/ops/auth/me']) {
    assert.equal(legacyOperationsTarget({ pathname, hash: '#/login' }), null);
  }
});

test('legacy fragments cannot redirect outside operations or smuggle path traversal', () => {
  for (const hash of [
    '#https://example.com', '#//example.com', '#/\\example.com', '#/../login', '#/services/../../login',
    '#/%2e%2e/login', '#/%252e%252e/login', '#/%2f%2fexample.com', '#/%5cexample.com',
    '#/login\n//example.com', '#/login?value=\\evil', '#/login//extra', '#javascript:alert(1)',
  ]) {
    assert.equal(legacyOperationsTarget({ pathname: '/ops.html', hash }), '/ops/', hash);
  }
});

function routerHarness(t, { url = 'https://example.test/ops/services', token = null, profile, failure } = {}) {
  if (!browserTestContexts.has(t)) {
    const previousWindow = global.window;
    t.after(() => { if (previousWindow === undefined) delete global.window; else global.window = previousWindow; });
    browserTestContexts.add(t);
  }
  const browser = { location: new URL(url), history: { state: { preserved: true } } };
  const migrations = [], commits = [], initialPaths = [];
  browser.history.replaceState = (state, title, target) => {
    migrations.push({ state, target });
    browser.location = new URL(target, browser.location);
  };
  global.window = browser;
  class ObservedRouter extends VueRouter {
    constructor(options) { initialPaths.push(browser.location.pathname); super(options); }
  }
  const router = loadModule('src/ops/router.js', {
    vue: Vue,
    'vue-router': ObservedRouter,
    '@/store': { commit: (...args) => commits.push(args) },
    '@/security/session': { readSession: () => token, hasPermission: (user, permission) => user?.audience === 'OPS' && user.permissions?.includes(permission) },
    '@/apis/platform': { operations: { me: async () => { if (failure) throw failure; return profile; } } },
  }).default;
  return { router, migrations, commits, initialPaths, browser };
}

async function guardResult(router, path) {
  const decisions = [];
  await router.beforeHooks[0]({ path }, {}, decision => decisions.push(decision));
  assert.equal(decisions.length, 1);
  return decisions[0];
}

test('legacy location migrates before router initialization and direct refresh keeps its history location', t => {
  const legacy = routerHarness(t, { url: 'https://example.test/ops.html#/model-billing?account=example' });
  assert.deepEqual(legacy.initialPaths, ['/ops/model-billing']);
  assert.deepEqual(legacy.migrations, [{ state: { preserved: true }, target: '/ops/model-billing?account=example' }]);
  assert.equal(legacy.router.options.mode, 'history');
  assert.equal(legacy.router.options.base, '/ops/');
  assert.equal(legacy.router.resolve('/login').href, '/ops/login');
  const direct = routerHarness(t, { url: 'https://example.test/ops/audit?page=2' });
  assert.deepEqual(direct.initialPaths, ['/ops/audit']);
  assert.deepEqual(direct.migrations, []);
  assert.equal(direct.browser.location.search, '?page=2');
});

test('anonymous and expired operations sessions redirect within the operations history base', async t => {
  const anonymous = routerHarness(t);
  const anonymousTarget = await guardResult(anonymous.router, '/services');
  assert.equal(anonymous.router.resolve(anonymousTarget).href, '/ops/login');
  for (const route of ['/login', '/enroll', '/recover']) assert.equal(await guardResult(anonymous.router, route), undefined);
  const expired = routerHarness(t, { token: 'test-only-token', failure: new Error('expired') });
  const expiredTarget = await guardResult(expired.router, '/services');
  assert.equal(expired.router.resolve(expiredTarget).href, '/ops/login');
  assert.deepEqual(expired.commits, [['clearAuth']]);
});

test('history routes retain OPS audience and permission enforcement', async t => {
  const employee = routerHarness(t, { token: 'test-only-token', profile: { audience: 'OPS', permissions: ['supplier.bill.read'] } });
  assert.equal(await guardResult(employee.router, '/model-billing'), undefined);
  assert.deepEqual(await guardResult(employee.router, '/iam'), { path: '/home', query: { denied: '1' } });
  const tenant = routerHarness(t, { token: 'test-only-token', profile: { audience: 'USER', permissions: ['supplier.bill.read'] } });
  assert.equal(await guardResult(tenant.router, '/model-billing'), '/login');
  assert.deepEqual(tenant.commits, [['clearAuth']]);
});

function serviceWorkerHarness() {
  const routes = [], cacheRequests = [];
  let catchHandler;
  class Strategy { constructor(options) { this.options = options; } }
  class NetworkOnly extends Strategy {}
  const offline = { page: 'offline' }, networkError = { error: true };
  const sandbox = {
    self: { __WB_MANIFEST: [], location: { origin: 'https://example.test' }, addEventListener() {} },
    importScripts() {}, console: { log() {} }, URL,
    caches: { match: async key => { cacheRequests.push(key); return key === '/offline.html' ? offline : { page: 'user' }; } },
    Response: { error: () => networkError },
    workbox: {
      setConfig() {}, core: { skipWaiting() {}, clientsClaim() {} },
      precaching: { precacheAndRoute() {} },
      routing: { registerRoute: (match, strategy) => routes.push({ match, strategy }), setCatchHandler: handler => { catchHandler = handler; } },
      strategies: { NetworkOnly, StaleWhileRevalidate: Strategy, NetworkFirst: Strategy },
      expiration: { ExpirationPlugin: Strategy },
    },
  };
  vm.runInNewContext(fs.readFileSync(path.join(webRoot, 'src/service-worker.js'), 'utf8'), sandbox);
  function firstRoute(url, mode = 'navigate') {
    const parsed = new URL(url, sandbox.self.location.origin);
    return routes.find(({ match }) => typeof match === 'function'
      ? match({ request: { mode }, url: parsed }) : match.test(parsed.href));
  }
  return { firstRoute, NetworkOnly, catchHandler, offline, networkError, cacheRequests, sandbox };
}

test('service worker sends OPS navigation to the network before any static or HTML cache', () => {
  const worker = serviceWorkerHarness();
  for (const pathname of ['/ops', '/ops/', '/ops/login', '/ops/model-billing?account=example', '/ops.html', '/ops/unknown.js']) {
    assert.ok(worker.firstRoute(pathname).strategy instanceof worker.NetworkOnly, pathname);
  }
  for (const pathname of ['/', '/login', '/xiaozhi/ops/auth/me', '/ops-other', 'https://external.test/ops/login']) {
    assert.equal(worker.firstRoute(pathname), undefined, pathname);
  }
  assert.equal(worker.firstRoute('/ops/login', 'cors'), undefined);
  assert.equal(worker.firstRoute('/index.html').strategy.options.cacheName, 'html-cache');
});

test('offline operations navigation never receives a cached user entry', async () => {
  const worker = serviceWorkerHarness();
  assert.equal(await worker.catchHandler({ event: { request: { destination: 'document', url: 'https://example.test/ops/login' } } }), worker.offline);
  assert.deepEqual(worker.cacheRequests, ['/offline.html']);
  worker.sandbox.caches.match = async () => undefined;
  assert.equal(await worker.catchHandler({ event: { request: { destination: 'document' } } }), worker.networkError);
});

test('development history fallback serves OPS and preserves API and static requests', () => {
  const config = require('../vue.config.js');
  const fallback = require('connect-history-api-fallback')(config.devServer.historyApiFallback);
  function rewrite(url, accept = 'text/html') {
    const request = { method: 'GET', url, headers: { accept } };
    fallback(request, {}, () => {});
    return request.url;
  }
  for (const url of ['/ops', '/ops/', '/ops/login', '/ops/recover?ticket=example', '/ops/unknown.path']) assert.equal(rewrite(url), '/ops.html');
  assert.equal(rewrite('/login'), '/index.html');
  assert.equal(rewrite('/xiaozhi/ops/auth/me', 'application/json'), '/xiaozhi/ops/auth/me');
  assert.equal(rewrite('/js/ops.js', '*/*'), '/js/ops.js');
  assert.equal(config.devServer.proxy['/xiaozhi'].target, 'http://127.0.0.1:8002');
});
