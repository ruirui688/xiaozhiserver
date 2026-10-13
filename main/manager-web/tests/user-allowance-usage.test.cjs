const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue');
const compiler = require('vue-template-compiler');
const { loadVueComponent, loadModule } = require('./helpers/loadVueComponent.cjs');
const settled = () => new Promise(resolve => setImmediate(resolve));
const nodes = vnode => [vnode, ...(vnode.children || []).flatMap(nodes)];
const content = vnode => Array.isArray(vnode) ? vnode.map(content).join('') : [vnode.text || '', ...(vnode.children || []).map(content)].join('');
const deferred = () => { let resolve; return { promise: new Promise(done => { resolve = done; }), resolve }; };
const emptyOverview = workspaceId => ({ workspaceId, callCount: '0', sessionCount: '0', failedCount: '0', cancelledCount: '0', usageUnknownCount: '0', usage: [] });

function component(t, file = 'components/allowance/UserUsagePanel', api = {}, props = {}, query = {}) {
  let workspace = props.workspaceId || 'space-a';
  global.localStorage = { getItem: key => key === 'currentWorkspaceId' ? workspace : null, setItem: (key,value) => { if (key === 'currentWorkspaceId') workspace = value; } };
  const calls = [];
  const defaults = {
    usageOptions: async params => ({ workspaceId: params.workspaceId, serviceTypes: ['LLM','ASR','TTS'], agents: [], devices: [], models: [] }),
    usageOverview: async params => emptyOverview(params.workspaceId),
    usageGroups: async () => ({ list: [], total: '0' }), usageEvents: async () => ({ list: [], total: '0' }),
    summary: async id => ({ workspaceId: id, canViewBalance: false, serviceStatus: 'READY' }),
    ledger: async () => ({ list: [], total: '0' }), trial: async () => ({})
  };
  const userPlatform = Object.fromEntries(Object.entries({ ...defaults, ...api }).map(([name,fn]) => [name, async params => { calls.push({ name, params }); return fn(params); }]));
  const definition = loadVueComponent(`src/${file}.vue`, { '@/apis/platform': { userPlatform } });
  const template = compiler.parseComponent(fs.readFileSync(path.resolve(__dirname, `../src/${file}.vue`),'utf8')).template.content;
  assert.deepEqual(compiler.compile(template).errors, []);
  const compiled = compiler.compileToFunctions(template);
  const vm = new Vue({ ...definition, created: undefined, propsData: { workspaceId: workspace, ...props }, render: compiled.render, staticRenderFns: compiled.staticRenderFns,
    beforeCreate() { this.$route = Vue.observable({ query }); this.$router = { replace: async route => { this.$route.query = route.query; } }; this.$message = { error() {}, success() {} }; }
  });
  t.after(() => vm.$destroy());
  return { vm, calls, setWorkspace(value) { workspace = value; vm.workspaceId = value; } };
}

test('user allowance has distinct authoritative readiness states without exposing a collaborator balance', t => {
  const { allowanceServiceState, visibleAllowance } = loadModule('src/utils/allowance.js');
  for (const [serviceStatus,label] of [['READY','服务已就绪'],['PAYER_FROZEN','服务账户已暂停'],['ALLOWANCE_EXHAUSTED','可用额度不足'],['WORKSPACE_ALLOWANCE_LIMIT','当前空间已达使用上限'],['PLATFORM_SERVICE_NOT_READY','平台服务待开通'],['PENDING','服务状态待确认']]) {
    assert.equal(allowanceServiceState({ serviceStatus }).label,label);
  }
  const secretBalance = { available: '9223372036854775806.123456' };
  assert.equal(visibleAllowance({ canViewBalance: false, allowance: secretBalance }),null);
  const { vm } = component(t,'views/Allowance');
  vm.summary = { workspaceId: 'space-a', canViewBalance: false, allowance: secretBalance, serviceStatus: 'ALLOWANCE_EXHAUSTED' };
  const rendered = vm._render();
  assert.ok(nodes(rendered).some(node => node.componentOptions?.tag === 'UserUsagePanel'));
  assert.ok(!content(rendered).includes(secretBalance.available));
  assert.ok(!nodes(rendered).some(node => /BillingWorkspacePanel|BillingCollectionHealth/.test(node.componentOptions?.tag || '')));
});

test('empty usage reads only the user endpoints and renders exactly one neutral empty message', async t => {
  const { vm, calls } = component(t);
  await vm.initialize();
  assert.deepEqual(calls.map(call => call.name),['usageOptions','usageGroups','usageOverview']);
  assert.ok(calls.every(call => call.params.workspaceId === 'space-a'));
  const rendered = vm._render();
  assert.equal(content(rendered).split('暂无使用记录').length - 1,1);
  assert.ok(!content(rendered).includes('采集'));
  assert.ok(!nodes(rendered).some(node => node.data?.staticClass === 'user-usage-incomplete'));
  assert.equal(vm.total,0);
});

test('fetch failure and malformed totals remain errors rather than successful empty usage', async t => {
  for (const api of [{ usageOverview: async () => { throw Error('读取失败'); } },{ usageGroups: async () => ({ list: [], total: null }) },{ usageOverview: async () => ({ workspaceId: 'space-a' }) }]) {
    const { vm } = component(t,undefined,api);
    await vm.initialize();
    assert.ok(vm.error); assert.equal(vm.overview,null);
    assert.ok(!content(vm._render()).includes('暂无使用记录'));
  }
});

test('unknown usage is retained with a scoped notice and precision is never replaced by zero', async t => {
  const usage = [{ metric: 'input_tokens', quantity: '9007199254740993.123456', unit: 'token', source: 'PROVIDER' }];
  const row = { eventId: 'call-1', status: 'FAILED', usageComplete: false, usage, settlementStatus: 'UNKNOWN', settledAmount: null, reservedAmount: '0.001234' };
  const { vm } = component(t,undefined,{ usageOverview: async () => ({ ...emptyOverview('space-a'), callCount: 1, usageUnknownCount: 1 }), usageEvents: async () => ({ list: [row], total: '1' }) },{}, { group: 'events' });
  await vm.initialize();
  assert.match(content(vm._render()),/1 条记录的用量尚未完整/);
  assert.equal(vm.rows[0].settledAmount,null);
  assert.equal(vm.rows[0].reservedAmount,'0.001234');
  assert.equal(vm.rows[0].usage[0].quantity,'9007199254740993.123456');
});

test('call failure and platform settlement render as independent columns', t => {
  const { vm } = component(t,'components/allowance/UserUsageRecords',{}, { kind: 'events', rows: [] });
  const row = { status: 'FAILED', settlementStatus: 'SETTLED', settledAmount: '0.000001', reservedAmount: '0', usage: [] };
  const column = label => nodes(vm._render()).find(node => node.tag === 'el-table-column' && node.data.attrs.label === label);
  assert.match(content(column('调用结果').data.scopedSlots.default({ row })),/失败/);
  assert.match(content(column('平台结算').data.scopedSlots.default({ row })),/已结算/);
  assert.match(content(column('扣减额度（元）').data.scopedSlots.default({ row })),/小于 0.01/);
  assert.equal(vm.amount(null),'—');
  assert.match(vm.settlementNote({ settlementStatus: 'UNTRACKED' }),/扣额未知/);
  assert.match(vm.settlementNote({ settlementStatus: 'UNKNOWN' }),/预占暂时保留/);
  const shared = { ...row, settlementShared: true, reservationId: '9223372036854775806' };
  assert.match(content(column('扣减额度（元）').data.scopedSlots.default({ row: shared })),/此调用共享同一结算/);
  assert.match(vm.settlementNote(shared),/金额仅扣减一次，汇总已去重/);
  for (const settlementStatus of ['RELEASED','EXPIRED','CANCELLED']) {
    assert.match(content(column('扣减额度（元）').data.scopedSlots.default({ row: { settlementStatus, settledAmount: '0' } })),/0\.00/);
  }
});

test('unsettled groups do not turn missing deductions into zero while confirmed zero remains visible', () => {
  const { confirmedSettlementAmount, settlementLabel } = loadModule('src/utils/userUsage.js');
  for (const key of ['pendingCount','reviewCount','unlinkedCount']) assert.equal(confirmedSettlementAmount({ settledCount: '0', settledAmount: '0', [key]: '1' }),null);
  assert.equal(confirmedSettlementAmount({ settledCount: '1', settledAmount: '0', unlinkedCount: '1' }),'0');
  assert.equal(confirmedSettlementAmount({ settledCount: '1', settledAmount: '0.000001' }),'0.000001');
  assert.equal(settlementLabel('STARTED'),'结算中');
  assert.equal(settlementLabel('EXPIRED'),'已过期');
  assert.equal(settlementLabel('CANCELLED'),'已取消');
});

test('workspace changes discard slower usage and clear the prior list immediately', async t => {
  const delayed = deferred();
  const { vm, setWorkspace } = component(t,undefined,{ usageOverview: params => params.workspaceId === 'space-a' ? delayed.promise : Promise.resolve({ ...emptyOverview('space-b'), callCount: 1 }), usageGroups: async params => ({ list: [{ id: params.workspaceId }], total: '1' }) });
  const first = vm.initialize(); await settled();
  setWorkspace('space-b'); await settled();
  assert.equal(vm.overview.workspaceId,'space-b'); assert.equal(vm.rows[0].id,'space-b');
  delayed.resolve({ ...emptyOverview('space-a'), callCount: 1 }); await first;
  assert.equal(vm.overview.workspaceId,'space-b'); assert.equal(vm.rows[0].id,'space-b');
  assert.equal(vm.loading,false);
});

test('mismatched workspace options or records cannot be rendered', async t => {
  for (const api of [{ usageOptions: async () => ({ workspaceId: 'space-other' }) },{ usageOverview: async () => emptyOverview('space-other') },{ usageGroups: async () => ({ workspaceId: 'space-other', list: [{ id: 'private' }], total: 1 }) }]) {
    const { vm } = component(t,undefined,api); await vm.initialize();
    assert.match(vm.error || vm.optionsError,/工作空间/); assert.deepEqual(vm.rows,[]); assert.equal(vm.overview,null);
  }
});

test('user routes retain usage filters and discard supplier account, cost and collection parameters', async t => {
  const { vm } = component(t,undefined,{}, {}, { group: 'devices', deviceId: 'device-a', accountRef: 'supplier-account', billPeriod: '2026-10', view: 'costs', scope: 'account', limit: '100' });
  await vm.initialize(); assert.equal(vm.limit,10);
  vm.changePage(2); await settled();
  assert.equal(vm.$route.query.group,'devices'); assert.equal(vm.$route.query.deviceId,'device-a'); assert.equal(vm.$route.query.page,'2');
  assert.ok(!('accountRef' in vm.$route.query)); assert.ok(!('billPeriod' in vm.$route.query)); assert.ok(!('scope' in vm.$route.query));
});

test('allowance refresh changes the usage instance and refreshes the summary without reading supplier APIs', async t => {
  const { vm, calls } = component(t,'views/Allowance'); await vm.load();
  const key = () => nodes(vm._render()).find(node => node.componentOptions?.tag === 'UserUsagePanel')?.key;
  const before = key(); vm.refresh(); await settled();
  assert.notEqual(key(),before); assert.equal(calls.filter(call => call.name === 'summary').length,2);
  assert.ok(calls.every(call => call.name === 'summary'));
});

test('user ledger serialized totals become numeric paging counts and missing values remain errors', async t => {
  const { vm } = component(t,'views/Allowance',{ ledger: async () => ({ list: [{ id: '9223372036854775806', amount: '9007199254740993.123456' }], total: '21' }) });
  await vm.loadLedger(); assert.equal(vm.total,21); assert.equal(vm.ledger[0].id,'9223372036854775806'); assert.equal(vm.ledger[0].amount,'9007199254740993.123456');
  const invalid = component(t,'views/Allowance',{ ledger: async () => ({ list: [], total: null }) }); await invalid.vm.loadLedger();
  assert.ok(invalid.vm.ledgerError); assert.deepEqual(invalid.vm.ledger,[]);
});
