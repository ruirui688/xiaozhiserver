const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const compiler = require('vue-template-compiler');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const navigation = loadModule('src/utils/billingNavigation.js');
const assets = loadModule('src/utils/billingAssets.js');
const mock = billing => ({ '@/apis/api': { __esModule: true, default: { billing } }, '@/assets/styles/billing.css': {} });
const component = (name, billing = {}) => loadVueComponent(`src/components/billing/${name}.vue`, mock(billing));
function instance(c, data = {}) { const vm = { ...(c.data ? c.data.call({}) : {}), ...data }; Object.entries(c.methods || {}).forEach(([key, method]) => vm[key] = method.bind(vm)); return vm; }
const deferred = () => { let resolve; const promise = new Promise(ok => { resolve = ok; }); return { promise, resolve }; };
function storage(id = 'space') { global.localStorage = { getItem: () => id }; }

test('platform administrators land on account expenses including old default-home URLs', () => {
  assert.deepEqual(navigation.billingRoute({}, true), { view: 'costs', scope: 'account', group: 'agents' });
  assert.deepEqual(navigation.billingRoute({ tab: 'overview', startDate: '2026-10-01', endDate: '2026-10-12' }, true), { view: 'costs', scope: 'account', group: 'agents' });
  assert.equal(navigation.billingRoute({ tab: 'overview', deviceId: 'device' }, true).view, 'usage');
  assert.equal(navigation.billingRoute({}, false).view, 'usage');
});

test('old device and bill links retain explicit scope while member routes never authorize accounts', () => {
  assert.deepEqual(navigation.billingRoute({ tab: 'events', deviceId: 'device' }, true), { view: 'usage', scope: 'workspace', group: 'events' });
  assert.equal(navigation.billingRoute({ tab: 'bills', unattributed: 'true' }, true).scope, 'account');
  assert.equal(navigation.billingRoute({ tab: 'bills' }, true).scope, 'workspace');
  assert.equal(navigation.billingRoute({ view: 'costs', scope: 'account' }, false).scope, 'workspace');
  const q = navigation.billingNavigationQuery({ view: 'usage', scope: 'workspace', deviceId: 'device', startDate: '2026-10-01', accountProvider: 'SECRET', accountRef: 'hidden', workspaceId: 'foreign', payableAmount: '999' }, { view: 'benefits', assetStatus: 'expired' }, false);
  assert.equal(q.deviceId, 'device'); assert.equal(q.startDate, '2026-10-01'); assert.equal(q.assetStatus, 'expired');
  for (const key of ['accountRef', 'accountProvider', 'workspaceId', 'payableAmount']) assert.equal(q[key], undefined);
});

test('moving from account expenses to usage requires an explicit workspace transition', () => {
  const c = loadVueComponent('src/views/ModelBillingManagement.vue', mock({})); let navigated = false;
  const vm = { scope: 'account', workspacePrompt: false, navigate: () => { navigated = true; } };
  c.methods.changeView.call(vm, 'usage'); assert.equal(vm.workspacePrompt, true); assert.equal(navigated, false);
  c.methods.enterUsage.call(vm); assert.equal(vm.workspacePrompt, false); assert.equal(navigated, true);
  assert.equal(c.computed.canManage.call({ options: { canManage: 'true' } }), false);
});

test('account expense reads ignore usage dates and preserve source failures independently', async () => {
  const calls = []; const accounts = [{ provider: 'VOLCENGINE', accountRef: 'v' }, { provider: 'DEEPSEEK', accountRef: 'd' }];
  const c = component('BillingAccountOverview', { accountOverview: async query => { calls.push(query); if (query.accountRef === 'd') throw new Error('来源不可用'); return { ...query, scope: 'ACCOUNT', costs: [{ currency: 'CNY', paidAmount: '9007199254740993.12' }], coverage: { status: 'PARTIAL' } }; } });
  const vm = instance(c, { accounts, period: '2026-10' }); await vm.load();
  assert.deepEqual(calls[0], { provider: 'VOLCENGINE', accountRef: 'v', billPeriod: '2026-10' });
  assert.equal(vm.reports[0].data.costs[0].paidAmount, '9007199254740993.12'); assert.match(vm.reports[1].error, /来源不可用/);
});

test('account results reject another account and obsolete periods cannot overwrite newer reports', async () => {
  const first = deferred(); const account = { provider: 'VOLCENGINE', accountRef: 'v' };
  const c = component('BillingAccountOverview', { accountOverview: query => query.billPeriod === '2026-09' ? first.promise : Promise.resolve({ ...query, scope: 'ACCOUNT', costs: [], coverage: { status: 'NOT_OBTAINED' } }) });
  const vm = instance(c, { accounts: [account], period: '2026-09' }); const old = vm.load(); vm.period = '2026-10'; await vm.load(); first.resolve({ ...account, scope: 'ACCOUNT', billPeriod: '2026-09', costs: [] }); await old;
  assert.equal(vm.reports[0].data.billPeriod, '2026-10');
  const wrong = component('BillingAccountOverview', { accountOverview: async () => ({ ...account, accountRef: 'other', scope: 'ACCOUNT', billPeriod: '2026-10' }) });
  const denied = instance(wrong, { accounts: [account], period: '2026-10' }); await denied.load(); assert.match(denied.reports[0].error, /不匹配/);
});

test('bill list requests a single layer and details stay under the selected parent', async () => {
  const calls = []; const c = component('BillingBillList', { accountBills: async query => { calls.push(query); return { list: [], total: 0 }; } });
  const vm = instance(c, { query: { provider: 'VOLCENGINE', accountRef: 'v', billPeriod: '2026-10', product: 'TTS' }, accountView: true });
  await vm.load(); vm.selected = { billId: 'parent', provider: 'VOLCENGINE', accountRef: 'v' }; await vm.loadDetails();
  assert.equal(calls[0].level, 'BILL'); assert.equal(calls[1].level, 'DETAIL'); assert.equal(calls[1].billId, 'parent'); assert.equal(calls[1].accountRef, 'v'); assert.equal(calls[1].product, undefined);
  const response = deferred(); const stale = component('BillingBillList', { accountBills: () => response.promise }); const instance2 = instance(stale, { query: {}, accountView: true }); const pending = instance2.load(); instance2.generation++; response.resolve({ list: [{ id: 'old' }], total: 1 }); await pending; assert.deepEqual(instance2.rows, []);
});

test('workspace health rejects platform data even for an administrator', async () => {
  storage();
  for (const response of [{ scope: 'PLATFORM', workspaceId: null }, { scope: 'WORKSPACE', workspaceId: 'foreign' }]) {
    const c = component('BillingCollectionHealth', { collectionHealth: async () => response }); const vm = instance(c, { workspaceId: 'space' }); await vm.load(); assert.equal(vm.data, null); assert.match(vm.error, /范围不匹配/);
  }
  const c = component('BillingAdminHealth', { collectionHealth: async query => { assert.equal(query.accountView, true); return { scope: 'PLATFORM', python: { status: 'ALIVE', instances: [] } }; } }); const vm = instance(c); await vm.load(); assert.equal(vm.data.python.status, 'ALIVE');
});

test('asset observations are not added or subtracted and entitlements remain separate', () => {
  const asset = { category: 'QUOTA', product: 'TTS', sourceObservations: [{ purchasedAmount: '100', currentUsage: '25', availableAmount: null }, { availableAmount: '77', totalAmount: '100', status: 'ACTIVE' }] };
  assert.equal(assets.primaryObservation(asset).availableAmount, '77'); assert.equal(asset.sourceObservations[0].availableAmount, null);
  const c = component('BillingBenefits'); const vm = { filteredAssets: [asset, { category: 'ENTITLEMENT' }, { category: 'UNKNOWN' }] };
  assert.deepEqual(c.computed.quotas.call(vm), [asset]); assert.equal(c.computed.entitlements.call(vm).length, 1); assert.equal(c.computed.unknownAssets.call(vm).length, 1);
  assert.equal(assets.filterAssets([asset, { product: 'ASR', sourceObservations: [{ status: 'Expired' }] }], '', 'current').length, 1);
});

test('benefit account selection is sent explicitly without inheriting historical usage filters', async () => {
  storage(); let received;
  const c = component('BillingBenefits', { benefits: async query => { received = query; return { scope: 'ACCOUNT', workspaceId: null, accountView: true, canManage: true, assets: [] }; } });
  const vm = instance(c, { workspaceId: 'space', canManage: true, accountView: true, account: { provider: 'DEEPSEEK', accountRef: 'd' } }); await vm.load();
  assert.deepEqual(received, { accountView: true, provider: 'DEEPSEEK', accountRef: 'd' }); assert.equal(vm.error, '');
});

test('all billing templates compile with Vue 2 including accessible scope and three-entry navigation', () => {
  const root = path.resolve(__dirname, '..');
  const files = ['src/views/ModelBillingManagement.vue', ...fs.readdirSync(path.join(root, 'src/components/billing')).filter(file => file.endsWith('.vue')).map(file => `src/components/billing/${file}`)];
  for (const file of files) { const source = fs.readFileSync(path.join(root, file), 'utf8'); const descriptor = compiler.parseComponent(source); assert.deepEqual(compiler.compile(descriptor.template.content).errors, [], file); }
});

test('official expiry and depletion alerts override stale active status without deriving balances', () => {
  const expired = { alerts: [{ code: 'EXPIRED' }], sourceObservations: [{ status: 'ACTIVE', availableAmount: '10' }] };
  const exhausted = { alerts: [{ code: 'DEPLETED' }], sourceObservations: [{ status: 'ACTIVE', availableAmount: '0' }] };
  const unknown = { sourceObservations: [{ purchasedAmount: '100', currentUsage: '100', availableAmount: null }] };
  assert.equal(assets.assetState(expired), 'expired'); assert.equal(assets.assetState(exhausted), 'exhausted');
  assert.deepEqual(assets.filterAssets([expired, exhausted, unknown], '', 'current'), [unknown]);
  assert.deepEqual(assets.filterAssets([expired, exhausted], '', 'expired'), [expired]);
  assert.deepEqual(assets.filterAssets([expired, exhausted], '', 'exhausted'), [exhausted]);
});

test('money formatting removes only redundant trailing zeros and retains exact decimal precision', () => {
  const { amountText, decimalText } = loadModule('src/utils/billing.js');
  assert.equal(amountText('25.000000000', 'CNY'), 'CNY 25.00');
  assert.equal(amountText('25.120000000', 'CNY'), 'CNY 25.12');
  assert.equal(amountText('9007199254740993.12003000', 'CNY'), 'CNY 9,007,199,254,740,993.12003');
  assert.equal(amountText('0.00000008000', 'CNY'), 'CNY 0.00000008');
  assert.equal(decimalText('1.23000'), '1.23');
  assert.equal(decimalText('120.000000000'), '120');
  assert.equal(decimalText('32.000000000'), '32');
  assert.equal(decimalText('6.800000000'), '6.8');
  assert.equal(decimalText('6.800000000', 3), '6.800');
  assert.equal(decimalText('9007199254740993.12003000'), '9,007,199,254,740,993.12003');
  assert.equal(decimalText('0.00000008000', 2), '0.00000008');
});
