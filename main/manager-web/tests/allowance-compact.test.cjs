const assert = require('node:assert/strict');
const { test } = require('node:test');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');

function instance(component, overrides = {}) {
  const vm = { ...component.data.call(overrides), ...overrides };
  for (const [key, method] of Object.entries(component.methods || {})) vm[key] = method.bind(vm);
  for (const [key, getter] of Object.entries(component.computed || {})) {
    if (typeof getter === 'function') Object.defineProperty(vm, key, { get: () => getter.call(vm) });
  }
  return vm;
}
const allowance = api => loadVueComponent('src/views/Allowance.vue', { '@/apis/platform': { userPlatform: api } });
const deferred = () => { let resolve; const promise = new Promise(ok => { resolve = ok; }); return { promise, resolve }; };
const workspace = () => { global.localStorage = { getItem: () => 'ws-a' }; };
const summary = { workspaceId: 'ws-a', canViewBalance: true };
const messages = { error() {}, success() {} };

test('opening usage reads the summary without prefetching limits, ledger or applications', async () => {
  workspace(); const calls = [];
  const component = allowance({
    summary: async () => { calls.push('summary'); return summary; },
    workspaceLimits: async () => { calls.push('limits'); return { workspaceId: 'ws-a', canManage: false }; },
    ledger: async () => { calls.push('ledger'); return { list: [], total: 0 }; },
    trial: async () => { calls.push('trial'); return {}; },
    allowanceRequests: async () => { calls.push('applications'); return { list: [] }; }
  });
  const vm = instance(component);
  await vm.load(); assert.deepEqual(calls, ['summary']);
  await vm.editLimits(); assert.deepEqual(calls, ['summary', 'limits']);
  component.watch.tab.call(vm, 'ledger');
  assert.deepEqual(calls, ['summary', 'limits', 'ledger', 'trial']);
  component.watch.tab.call(vm, 'request');
  assert.deepEqual(calls, ['summary', 'limits', 'ledger', 'trial']);
});

test('slower ledger pages cannot overwrite the latest page and errors stay retryable', async () => {
  const first = deferred(), second = deferred(); let count = 0;
  const component = allowance({ ledger: () => ++count === 1 ? first.promise : second.promise });
  const vm = instance(component);
  const earlier = vm.loadLedger(); vm.page = 2; const later = vm.loadLedger();
  second.resolve({ list: [{ id: 'new' }], total: 21 }); await later;
  first.resolve({ list: [{ id: 'old' }], total: 20 }); await earlier;
  assert.deepEqual(vm.ledger, [{ id: 'new' }]); assert.equal(vm.total, 21);
  const failing = allowance({ ledger: async () => { throw new Error('流水暂不可用'); } });
  await failing.methods.loadLedger.call(vm);
  assert.deepEqual(vm.ledger, []); assert.match(vm.ledgerError, /暂不可用/); assert.equal(vm.ledgerLoading, false);
});

test('limits opened on another workspace or without authoritative manage permission cannot be saved', async () => {
  workspace(); let saves = 0;
  const component = allowance({ workspaceLimits: async () => ({ workspaceId: 'ws-b', canManage: true }), saveWorkspaceLimits: async () => { saves++; } });
  const vm = instance(component, { summary, $message: messages });
  await vm.editLimits(); assert.equal(vm.limits, null); assert.match(vm.limitError, /工作空间/);
  await vm.saveLimits(); assert.equal(saves, 0);
  vm.limitError = ''; vm.limits = { workspaceId: 'ws-a', canManage: 'true' };
  await vm.saveLimits(); assert.equal(saves, 0);
});

test('compact usage retains exact provider and observed quantities without adding cache subsets', () => {
  const component = loadVueComponent('src/components/billing/BillingCompactUsage.vue');
  const usage = Object.freeze([
    Object.freeze({ metric: 'cached_input_tokens', quantity: '500', unit: 'token', source: 'PROVIDER' }),
    Object.freeze({ metric: 'input_tokens', quantity: '9007199254740993.123456', unit: 'token', source: 'PROVIDER' }),
    Object.freeze({ metric: 'input_tokens', quantity: '9007199254740994', unit: 'token', source: 'OBSERVED' })
  ]);
  const visible = component.computed.primary.call({ usage, maxItems: 2 });
  assert.equal(visible.length, 2); assert.equal(visible[0], usage[1]); assert.equal(visible[1], usage[2]);
  assert.equal(component.methods.number(visible[0].quantity), '9,007,199,254,740,993.123456');
  assert.equal(usage.length, 3); assert.equal(usage[0].quantity, '500');
});

test('compact paging requests ten rows and preserves group filters, while operations keep existing limits', () => {
  const component = loadVueComponent('src/components/billing/BillingWorkspacePanel.vue', { '@/apis/api': {} });
  const vm = instance(component, { compact: true, mode: 'usage', routePath: '/allowance', $route: { query: { group: 'devices', deviceId: 'device-a', page: '2', limit: '100' } } });
  vm.readRoute(); assert.equal(vm.limit, 10); assert.equal(vm.page, 2); assert.equal(vm.tab, 'devices');
  let route; vm.$router = { replace: value => { route = value; return Promise.resolve(); } };
  vm.changePage(3);
  assert.equal(route.path, '/allowance'); assert.equal(route.query.limit, '10'); assert.equal(route.query.page, '3'); assert.equal(route.query.deviceId, 'device-a');
  vm.compact = false; vm.readRoute(); assert.equal(vm.limit, 100);
});
