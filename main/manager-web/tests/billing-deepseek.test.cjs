const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue');
const compiler = require('vue-template-compiler');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const { billingAccountState } = loadModule('src/utils/billingAccounts.js');
const mocks = billing => ({ '@/apis/api': { __esModule: true, default: { billing } }, '@/assets/styles/billing.css': {} });
const card = () => loadVueComponent('src/components/billing/BillingAccountOverview.vue', mocks({}));
const page = () => loadVueComponent('src/views/ModelBillingManagement.vue', mocks({}));
const admin = billing => loadVueComponent('src/components/billing/BillingAdminDialog.vue', mocks(billing));
const account = { provider: 'DEEPSEEK', accountRef: 'deepseek-account', capabilities: { billSyncSupported: false, statementImportSupported: true, balanceSupported: true, balanceEnabled: false } };
function instance(c, data = {}) { const vm = { ...(c.data ? c.data.call({}) : {}), ...data }; Object.entries(c.methods || {}).forEach(([key, method]) => vm[key] = method.bind(vm)); return vm; }
function storage() { global.localStorage = { getItem: () => 'space' }; }
const deferred = () => { let resolve; const promise = new Promise(ok => { resolve = ok; }); return { promise, resolve }; };
function renderCard(data) {
  const source = fs.readFileSync(path.resolve(__dirname, '../src/components/billing/BillingAccountOverview.vue'), 'utf8');
  const compiled = compiler.compile(compiler.parseComponent(source).template.content);
  const c = card();
  const vm = new Vue({ ...c, created: [], render: new Function(compiled.render), staticRenderFns: compiled.staticRenderFns.map(code => new Function(code)), propsData: { accounts: [account] }, data: () => ({ ...c.data(), reports: [{ account, data }] }) });
  const nodes = [];
  function visit(node) { if (!node) return; nodes.push(node); (node.children || []).forEach(visit); }
  visit(vm._render());
  return { text: nodes.map(node => node.text || '').join(' '), nodes };
}

test('DeepSeek expenses require official imports even when only balance is configured', () => {
  const state = billingAccountState(account, { costs: [], coverage: { status: 'NOT_OBTAINED' }, sync: { lastAttemptStatus: 'STATEMENT_REQUIRED' } });
  assert.equal(state.importOnly, true); assert.equal(state.emptyText, '尚未导入官方消费凭证');
  assert.equal(state.sourceTime, ''); assert.doesNotMatch(state.coverageLabel, /同步/);
  assert.equal(billingAccountState({ provider: 'DEEPSEEK' }).importOnly, true);
  assert.equal(billingAccountState({ ...account, capabilities: { billSyncSupported: 'true' } }).capabilities.billSyncSupported, false);
});

test('DeepSeek card renders actionable missing data without fabricated money or pending sync', () => {
  const rendered = renderCard({ costs: [], billCount: 0, coverage: { status: 'NOT_OBTAINED' }, sync: { lastAttemptStatus: 'STATEMENT_REQUIRED' } });
  assert.match(rendered.text, /尚未导入官方消费凭证/); assert.match(rendered.text, /暂不支持自动获取消费账单/);
  assert.match(rendered.text, /查看余额/); assert.match(rendered.text, /导入官方凭证/);
  assert.doesNotMatch(rendered.text, /尚无成功同步记录|等待同步|CNY 0\.00/);
  const link = rendered.nodes.find(node => node.tag === 'a' && node.data.attrs.href === 'https://platform.deepseek.com/usage');
  assert.equal(link.data.attrs.target, '_blank'); assert.equal(link.data.attrs.rel, 'noopener noreferrer');
});

test('imported DeepSeek money retains exact amounts and import provenance while automatic sources retain sync status', () => {
  const data = { costs: [{ currency: 'CNY', paidAmount: '9007199254740993.12003000', payableAmount: '9007199254740993.12003000' }], billCount: 1, coverage: { status: 'PARTIAL' }, sync: { lastAttemptStatus: 'IMPORTED_STATEMENT', lastImportedAt: '2026-10-12T00:00:00Z', lastSuccessAt: null } };
  const rendered = renderCard(data);
  assert.match(rendered.text, /9,007,199,254,740,993\.12003/); assert.match(rendered.text, /最近导入/); assert.match(rendered.text, /完整性尚未自动核验/);
  assert.doesNotMatch(rendered.text, /尚无成功同步记录|最近成功同步/);
  const automatic = billingAccountState({ provider: 'VOLCENGINE', capabilities: { billSyncSupported: true } }, { sync: { lastSuccessAt: '2026-10-12T00:00:00Z' }, coverage: { status: 'SNAPSHOT' } });
  assert.equal(automatic.importOnly, false); assert.match(automatic.sourceTime, /最近成功同步/);
});

test('imported details without a parent bill remain imported with unknown aggregate money', () => {
  const rendered = renderCard({ costs: [], billCount: 0, detailCount: 1, coverage: { status: 'PARTIAL', orphanDetailCount: 1 }, sync: { lastAttemptStatus: 'IMPORTED_STATEMENT', lastImportedAt: '2026-10-12T00:00:00Z' } });
  assert.match(rendered.text, /已导入凭证，暂无可汇总金额/); assert.match(rendered.text, /已导入 · 覆盖待核对/);
  assert.doesNotMatch(rendered.text, /尚未导入官方消费凭证|CNY 0\.00/);
});

test('card import carries the account and period through file submission and exact account refresh', async () => {
  storage(); const events = []; let payload; let navigation; let reloads = 0; let notice;
  const p = instance(page(), { canManage: true, accounts: [account], $message: { success: value => { notice = value; } } });
  p.navigate = query => { navigation = query; }; p.loadAccounts = () => { reloads++; };
  const c = instance(card(), { period: '2026-09', $emit: (name, context) => { assert.equal(name, 'manage'); p.openAdmin(context); } });
  c.openImport(account);
  assert.deepEqual(p.importContext, { provider: 'DEEPSEEK', accountRef: account.accountRef, billPeriod: '2026-09' });
  const a = instance(admin({ importBill: async (...args) => { payload = args; return { importedCount: 1 }; } }), { canManage: true, visible: true, workspaceId: 'space', importContext: p.importContext, $refs: { file: { value: 'old.csv' } }, $nextTick: fn => fn(), file: { name: 'old.csv' }, confirmOfficial: true, $emit: (name, context) => { events.push(name); if (name === 'updated') p.handleUpdated(context); } });
  a.prepareImport();
  assert.equal(a.importExpanded, true); assert.equal(a.importProvider, 'DEEPSEEK'); assert.equal(a.accountRef, account.accountRef); assert.equal(a.importPeriod, '2026-09');
  assert.equal(a.file, null); assert.equal(a.$refs.file.value, ''); assert.equal(a.confirmOfficial, false);
  const file = { name: 'official.csv', size: 50 }; a.selectFile({ target: { files: [file] } }); a.confirmOfficial = true;
  await a.importBill();
  assert.deepEqual(payload, [file, 'DEEPSEEK', account.accountRef]);
  assert.equal(events.filter(name => name === 'updated').length, 1); assert.equal(reloads, 1); assert.equal(p.revision, 1);
  assert.equal(p.adminVisible, false); assert.match(notice, /处理记录 1 条/);
  assert.deepEqual(navigation, { view: 'costs', scope: 'account', accountProvider: 'DEEPSEEK', accountRef: account.accountRef, billPeriod: '2026-09', product: '', page: 1 });
});

test('closing or changing an import target clears selected files and official confirmation', () => {
  const c = admin({}); const vm = instance(c, { $refs: { file: { value: 'previous.csv' } }, file: {}, confirmOfficial: true });
  c.watch.visible.call(vm, false); assert.equal(vm.file, null); assert.equal(vm.confirmOfficial, false); assert.equal(vm.$refs.file.value, '');
  for (const target of ['importProvider', 'accountRef']) { vm.file = {}; vm.confirmOfficial = true; vm.$refs.file.value = 'previous.csv'; c.watch[target].call(vm); assert.equal(vm.file, null); assert.equal(vm.confirmOfficial, false); assert.equal(vm.$refs.file.value, ''); }
});

test('failed official import leaves its form and file available without claiming a successful refresh', async () => {
  storage(); let updates = 0; const file = { name: 'official.csv' };
  const vm = instance(admin({ importBill: async () => { throw new Error('账单缺少官方金额'); } }), { visible: true, canManage: true, workspaceId: 'space', importProvider: 'DEEPSEEK', accountRef: account.accountRef, file, confirmOfficial: true, $emit: () => { updates++; } });
  await vm.importBill();
  assert.equal(vm.visible, true); assert.equal(vm.file, file); assert.equal(vm.busy, false); assert.equal(updates, 0); assert.match(vm.error, /缺少官方金额/);
});

test('pending import prevents duplicate requests and binding pagination cannot invalidate its completion', async () => {
  storage(); const request = deferred(); let calls = 0; let updated = 0;
  const vm = instance(admin({ importBill: () => { calls++; return request.promise; } }), { canManage: true, workspaceId: 'space', importProvider: 'DEEPSEEK', accountRef: account.accountRef, file: {}, confirmOfficial: true, $emit: name => { if (name === 'updated') updated++; } });
  const pending = vm.importBill(); await vm.importBill(); const generation = vm.generation; vm.changeBindingPage(2); await vm.loadBindings();
  assert.equal(calls, 1); assert.equal(vm.bindingPage, 1); assert.equal(vm.generation, generation);
  request.resolve({ importedCount: 1 }); await pending; assert.equal(updated, 1); assert.equal(vm.busy, false);
});

test('closed dialogs and changed import targets ignore stale successes and never navigate another account', async () => {
  storage();
  for (const change of ['close', 'account', 'period', 'permission']) {
    const request = deferred(); let updates = 0; const c = admin({ importBill: () => request.promise });
    const vm = instance(c, { canManage: true, workspaceId: 'space', importProvider: 'DEEPSEEK', accountRef: account.accountRef, file: {}, confirmOfficial: true, $emit: () => { updates++; } });
    const pending = vm.importBill();
    if (change === 'close') c.watch.visible.call(vm, false);
    if (change === 'account') vm.accountRef = 'other';
    if (change === 'period') vm.importPeriod = '2020-01';
    if (change === 'permission') vm.canManage = false;
    request.resolve({ importedCount: 1 }); await pending;
    assert.equal(updates, 0, change); assert.equal(vm.result, '', change); assert.equal(vm.busy, false, change);
  }
});

test('balance navigation selects the precise account and usage explicitly enters the current workspace', () => {
  const queries = []; const vm = instance(page(), { canManage: true, accounts: [account] }); vm.navigate = query => queries.push(query);
  vm.openAccountBenefits(account); assert.equal(queries[0].scope, 'account'); assert.equal(queries[0].view, 'benefits'); assert.equal(queries[0].accountRef, account.accountRef);
  vm.openAccountUsage(account); assert.equal(vm.workspacePrompt, true); assert.equal(queries.length, 1);
  vm.enterUsage(); assert.equal(queries[1].scope, 'workspace'); assert.equal(queries[1].provider, 'DEEPSEEK'); assert.equal(queries[1].accountRef, ''); assert.equal(queries[1].modelId, '');
  vm.openAccountBenefits({ ...account, accountRef: 'foreign' }); assert.equal(queries.length, 2);
  vm.canManage = false; vm.openAdmin({ ...account, billPeriod: '2026-10' }); assert.equal(vm.adminVisible, false);
});

test('automatic synchronization remains explicit to the configured Volcengine account', async () => {
  storage(); const queries = []; const c = admin({ sync: async query => { queries.push(query); return { importedCount: 0 }; } });
  const vm = instance(c, { canManage: true, workspaceId: 'space', options: {}, $emit() {} });
  for (const enabled of [undefined, false, 'true']) { vm.options.volcengineSyncEnabled = enabled; await vm.sync(); }
  assert.equal(queries.length, 0);
  vm.options = { volcengineSyncEnabled: true, volcengine: { accountRef: 'configured-volcano' } }; await vm.sync();
  assert.deepEqual(queries[0], { billPeriod: vm.billPeriod, provider: 'VOLCENGINE', accountRef: 'configured-volcano' });
});
