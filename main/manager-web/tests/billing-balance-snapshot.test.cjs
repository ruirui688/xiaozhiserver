const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue');
const compiler = require('vue-template-compiler');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const { balanceSnapshotState } = loadModule('src/utils/billingBalances.js');
const balance = Object.freeze({ provider: 'DEEPSEEK', accountRef: 'test-account', currency: 'CNY', totalBalance: '123.456789', toppedUpBalance: '120', grantedBalance: '3.456789', isAvailable: true });
const timestamp = '2026-10-10 12:30:00';
const source = (extra = {}) => ({ provider: 'DEEPSEEK', accountRef: 'test-account', status: 'READY', lastSyncedAt: timestamp, ...extra });
const text = vnode => [vnode.text || '', ...(vnode.children || []).map(text)].join('');
const nodes = vnode => [vnode, ...(vnode.children || []).flatMap(nodes)];

function create(t, sources, { balances = [balance], syncError = '', billing = {}, account = null } = {}) {
  const file = 'src/components/billing/BillingBenefits.vue';
  const component = loadVueComponent(file, { '@/apis/api': { __esModule: true, default: { billing } } });
  const template = compiler.parseComponent(fs.readFileSync(path.resolve(__dirname, '..', file), 'utf8')).template.content;
  const compiled = compiler.compileToFunctions(template);
  const vm = new Vue({ ...component, created: undefined, render: compiled.render, staticRenderFns: compiled.staticRenderFns, propsData: { workspaceId: '', canManage: true, scope: 'account', standaloneAccount: true, account } });
  vm.accountView = true;
  vm.data = { scope: 'ACCOUNT', accountView: true, canManage: true, sources, balances, assets: [] };
  vm.syncError = syncError;
  t.after(() => vm.$destroy());
  return vm;
}
function card(vm) { return nodes(vm._render()).find(node => node.tag === 'article'); }

for (const status of ['NOT_CONFIGURED', 'SYNC_FAILED', 'NEVER_SYNCED', 'UNSUPPORTED']) {
  test(`${status} keeps historical money and time without claiming current service availability`, t => {
    const vm = create(t, [source({ status })]);
    const content = text(card(vm));
    assert.match(content, /123\.456789/);
    assert.match(content, /历史快照/);
    assert.match(content, /当前服务状态：无法确认/);
    assert.match(content, /上次快照时间：2026-10-10 12:30:00/);
    assert.doesNotMatch(content, /服务状态：可用/);
    assert.equal(vm.balanceSnapshots[0].balance, balance);
    if (status === 'NOT_CONFIGURED') assert.match(content, /当前无法刷新/);
    if (status === 'SYNC_FAILED') assert.match(content, /最近同步失败/);
  });
}

test('a confirmed successful snapshot describes availability at its recorded time', t => {
  for (const status of ['READY', 'SUCCESS']) {
    const vm = create(t, [source({ status, lastAttemptStatus: status })]);
    const content = text(card(vm));
    assert.match(content, /已同步快照/);
    assert.match(content, /快照时服务状态：可用/);
    assert.match(content, /上次快照时间：2026-10-10 12:30:00/);
    assert.doesNotMatch(content, /历史快照/);
  }
  assert.equal(balanceSnapshotState({ ...balance, isAvailable: false }, [source()]).availability, '不可用');
  assert.equal(balanceSnapshotState({ ...balance, isAvailable: 'true' }, [source()]).availability, '供应商未提供');
});

test('unmatched, ambiguous, or incomplete sources never establish a current balance', () => {
  for (const sources of [[], null, [null], [source({ provider: 'VOLCENGINE' })], [source({ accountRef: 'other-account' })], [source({ accountRef: undefined })], [source(), source()], [source({ status: undefined })], [source({ lastSyncedAt: null })], [source({ lastSyncedAt: 'unverified date' })]]) {
    const state = balanceSnapshotState(balance, sources);
    assert.equal(state.healthy, false);
    assert.equal(state.availability, '无法确认');
  }
  assert.equal(balanceSnapshotState({ ...balance, accountRef: '' }, [source()]).healthy, false);
  assert.equal(balanceSnapshotState(balance, [source({ accountRef: 'other-account' })]).snapshotAt, null);
});

test('currency-specific sources cannot establish another currency balance', () => {
  assert.equal(balanceSnapshotState(balance, [source({ currency: 'USD' })]).healthy, false);
  const sources = [source({ currency: 'CNY', status: 'NOT_CONFIGURED' }), source({ currency: 'USD', status: 'READY' })];
  assert.equal(balanceSnapshotState(balance, sources).healthy, false);
  assert.equal(balanceSnapshotState({ ...balance, currency: 'USD' }, sources).healthy, true);
  assert.equal(balanceSnapshotState(balance, [source()]).healthy, true);
  assert.equal(balanceSnapshotState({ ...balance, currency: 'USD' }, [source()]).healthy, true);
});

test('latest sync failure overrides an earlier READY snapshot and uses its real success time', t => {
  const vm = create(t, [source({ lastAttemptStatus: 'SYNC_FAILED', lastSuccessAt: timestamp, lastAttemptAt: '2026-10-12 12:00:00' })]);
  const content = text(card(vm));
  assert.match(content, /最近同步失败/);
  assert.match(content, /当前服务状态：无法确认/);
  assert.doesNotMatch(content, /2026-10-12/);
  const localFailure = create(t, [source()], { syncError: '本次同步请求失败' });
  assert.match(text(card(localFailure)), /历史快照/);
  assert.match(text(card(localFailure)), /当前服务状态：无法确认/);
});

test('missing snapshot timestamps stay explicitly unknown', t => {
  const vm = create(t, [source({ status: 'NOT_CONFIGURED', lastSyncedAt: null })]);
  assert.match(text(card(vm)), /上次快照时间：未提供/);
  assert.match(text(card(vm)), /当前服务状态：无法确认/);
});

test('standalone OPS sync refreshes account evidence independently from the USER workspace', async t => {
  const previous = global.localStorage;
  global.localStorage = { getItem: () => 'unrelated-user-workspace' };
  t.after(() => { global.localStorage = previous; });
  let reads = 0;
  const vm = create(t, [source()], { billing: {
    syncBenefits: async () => ({ status: 'PARTIAL' }),
    benefits: async query => { reads++; assert.equal(query.accountView, true); return { scope: 'ACCOUNT', accountView: true, canManage: true, balances: [balance], sources: [source({ status: 'NOT_CONFIGURED' })], assets: [] }; },
  } });
  await vm.sync();
  assert.equal(reads, 1);
  assert.equal(vm.syncing, false);
  assert.match(text(card(vm)), /当前服务状态：无法确认/);
  assert.match(text(card(vm)), /当前无法刷新/);
});

test('standalone OPS sync errors mark retained balances stale and obsolete scopes cannot reload', async t => {
  const vm = create(t, [source()], { billing: { syncBenefits: async () => { throw new Error('test-only sync failure'); } } });
  await vm.sync();
  assert.match(vm.syncError, /此前快照/);
  assert.equal(vm.data.balances[0], balance);
  assert.match(text(card(vm)), /历史快照/);
  assert.match(text(card(vm)), /当前服务状态：无法确认/);
  let resolve, reads = 0;
  const pending = new Promise(done => { resolve = done; });
  const changing = create(t, [source()], { billing: { syncBenefits: () => pending, benefits: async () => { reads++; } } });
  const request = changing.sync();
  changing.accountView = false;
  resolve({ status: 'SUCCESS' });
  await request;
  assert.equal(reads, 0);
  assert.equal(changing.syncing, false);
});

test('changing the account or destroying the panel discards pending sync outcomes', async t => {
  let resolve, reads = 0;
  const pending = new Promise(done => { resolve = done; });
  const vm = create(t, [source()], { account: { provider: 'DEEPSEEK', accountRef: 'test-account' }, billing: { syncBenefits: () => pending, benefits: async () => { reads++; } } });
  const request = vm.sync();
  vm.account.accountRef = 'other-account';
  resolve({ status: 'SUCCESS' });
  await request;
  assert.equal(reads, 0);
  assert.equal(vm.syncError, '');
  let reject;
  const abandoned = new Promise((resolve, fail) => { reject = fail; });
  const leaving = create(t, [source()], { billing: { syncBenefits: () => abandoned } });
  const old = leaving.sync();
  leaving.$destroy();
  reject(new Error('test-only delayed failure'));
  await old;
  assert.equal(leaving.data, null);
  assert.equal(leaving.syncError, '');
  assert.equal(leaving.syncing, false);
});
