const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const utils = loadModule('src/utils/billing.js');
const mocks = billing => ({ '@/apis/api': { __esModule: true, default: { billing } } });
const page = billing => loadVueComponent('src/views/ModelBillingManagement.vue', mocks(billing));
const admin = billing => loadVueComponent('src/components/billing/BillingAdminDialog.vue', mocks(billing));
const benefits = billing => loadVueComponent('src/components/billing/BillingBenefits.vue', mocks(billing));
const deferred = () => { let resolve, reject; const promise = new Promise((ok, no) => { resolve = ok; reject = no; }); return { promise, resolve, reject }; };
function storage(workspace = 'ws-a') {
  const values = new Map([['currentWorkspaceId', workspace]]);
  global.localStorage = { getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value) };
  return values;
}
function instance(component, data = {}) {
  const vm = { ...component.data.call({}), ...data };
  Object.entries(component.methods).forEach(([key, method]) => { vm[key] = method.bind(vm); });
  return vm;
}

test('billing date defaults use Beijing day boundaries and validate real calendar dates', () => {
  assert.equal(utils.chinaDate(new Date('2026-09-30T16:00:00Z')), '2026-10-01');
  assert.deepEqual([utils.defaultBillingFilters(new Date('2026-09-30T16:00:00Z')).startDate, utils.defaultBillingFilters(new Date('2026-09-30T16:00:00Z')).endDate], ['2026-10-01', '2026-10-01']);
  assert.equal(utils.validDate('2026-02-30'), false);
  assert.equal(utils.validDate('2024-02-29'), true);
  assert.match(utils.validateBillingDates({ startDate: '2026-10-10', endDate: '2026-10-01' }), /晚于/);
});

test('missing official amounts remain unknown and supplier decimal precision is preserved', () => {
  for (const value of [null, undefined, '', NaN, Infinity, 'not-available']) assert.equal(utils.amountText(value, 'CNY'), '—');
  assert.equal(utils.amountText('0', 'CNY'), 'CNY 0.00');
  assert.equal(utils.amountText('9007199254740993.12345678', 'USD'), 'USD 9,007,199,254,740,993.12345678');
  assert.equal(utils.amountText('-0.003', 'CNY'), 'CNY -0.003');
  assert.equal(utils.amountText(0.00000008, 'CNY'), 'CNY 0.00000008');
  assert.equal(utils.amountText('1.23E+3', 'CNY'), 'CNY 1,230.00');
  assert.equal(utils.amountText('1E+1000', 'CNY'), '—');
  assert.equal(utils.amountText('12', null), '币种未提供 12.00');
});

test('query filters are allowlisted and cannot smuggle a foreign workspace or amounts', () => {
  const filters = utils.billingFiltersFromQuery({ agentId: 'a', provider: 'DEEPSEEK', workspaceId: 'foreign', payableAmount: '12', deviceId: ['bad'] });
  const query = utils.billingQuery(filters, 2, 200);
  assert.equal(query.agentId, 'a'); assert.equal(query.provider, 'DEEPSEEK'); assert.equal(query.limit, 100);
  assert.equal(query.workspaceId, undefined); assert.equal(query.payableAmount, undefined); assert.equal(query.deviceId, undefined);
});

test('drilldown and pagination retain all applied dimensions and reset pagination only as needed', () => {
  const component = page({});
  const calls = [];
  const filters = { ...utils.defaultBillingFilters(), provider: 'DEEPSEEK', serviceType: 'LLM', modelId: 'model', deviceId: 'd', board: 'board' };
  const vm = { appliedFilters: filters, tab: 'agents', limit: 50, navigate: (...args) => calls.push(args) };
  component.methods.drill.call(vm, { id: 'agent' });
  assert.deepEqual(calls[0], [{ ...filters, agentId: 'agent' }, 'events']);
  component.methods.changePage.call(vm, 3); assert.deepEqual(calls[1], [filters, 'agents', 3]);
  component.methods.changeLimit.call(vm, 100); assert.deepEqual(calls[2], [filters, 'agents', 1, 100]);
});

test('older response cannot overwrite a newer filter result', async () => {
  storage(); const first = deferred(), second = deferred(); let calls = 0;
  const component = page({ overview: () => (++calls === 1 ? first.promise : second.promise) });
  const vm = instance(component, { workspaceId: 'ws-a', tab: 'overview', canManage: false });
  const pendingFirst = vm.loadData(); const pendingSecond = vm.loadData();
  second.resolve({ workspaceId: 'ws-a', callCount: 2 }); await pendingSecond;
  first.resolve({ workspaceId: 'ws-a', callCount: 1 }); await pendingFirst;
  assert.equal(vm.overview.callCount, 2); assert.equal(vm.loading, false);
});

test('workspace switch discards pending data and unauthorized reload clears previous rows', async () => {
  const values = storage(); const delayed = deferred();
  const component = page({ overview: () => delayed.promise });
  const vm = instance(component, { workspaceId: 'ws-a', tab: 'overview', overview: { callCount: 99 }, rows: [{ id: 'old' }] });
  const pending = vm.loadData(); values.set('currentWorkspaceId', 'ws-b');
  delayed.resolve({ workspaceId: 'ws-a', callCount: 10 }); await pending;
  assert.equal(vm.overview, null); assert.deepEqual(vm.rows, []);
  const denied = page({ overview: () => Promise.reject(new Error('无权读取此工作空间')) });
  const deniedVm = instance(denied, { workspaceId: 'ws-b', tab: 'overview', overview: { callCount: 99 }, rows: [{ id: 'old' }] });
  await deniedVm.loadData(); assert.equal(deniedVm.overview, null); assert.deepEqual(deniedVm.rows, []); assert.match(deniedVm.error, /无权/);
});

test('unexpected workspace in response is rejected rather than displayed', async () => {
  storage(); const component = page({ overview: async () => ({ workspaceId: 'foreign', callCount: 500 }) });
  const vm = instance(component, { workspaceId: 'ws-a', tab: 'overview' });
  await vm.loadData(); assert.equal(vm.overview, null); assert.match(vm.error, /工作空间不匹配/);
});

test('options handle header selecting the same default workspace during initialization', async () => {
  const values = storage(''); const delayed = deferred();
  const component = page({ options: () => delayed.promise, overview: async () => ({ workspaceId: 'ws-a', callCount: 1 }) });
  const vm = instance(component, { $route: { query: {} }, canManage: false });
  const pending = vm.initialize(); values.set('currentWorkspaceId', 'ws-a');
  delayed.resolve({ workspaceId: 'ws-a', canManage: false }); await pending;
  assert.equal(vm.workspaceId, 'ws-a'); assert.equal(vm.overview.callCount, 1); assert.equal(vm.optionsError, '');
});

test('ordinary members query workspace bills without platform-only scope', async () => {
  storage(); let sent;
  const component = page({ overview: async () => ({ workspaceId: 'ws-a' }), bills: async query => { sent = query; return { list: [], total: 0 }; } });
  const vm = instance(component, { workspaceId: 'ws-a', tab: 'bills', canManage: false, unattributed: true });
  await vm.loadData(); assert.equal(sent.unattributed, undefined);
});

test('admin calls cannot execute for ordinary members or stale workspace', async () => {
  storage(); let calls = 0;
  const component = admin({ sync: async () => { calls++; } });
  const vm = instance(component, { canManage: false, workspaceId: 'ws-a', options: { volcengineSyncEnabled: true }, $emit() {} });
  await vm.sync(); assert.equal(calls, 0);
  vm.canManage = true; vm.workspaceId = 'foreign'; await vm.sync(); assert.equal(calls, 0);
});

test('mapping effective dates are converted from Beijing to UTC and sent to the current workspace', async () => {
  storage(); let payload;
  const component = admin({ createBinding: async body => { payload = body; return {}; } });
  const vm = instance(component, { canManage: true, workspaceId: 'ws-a', $emit() {}, form: { provider: 'VOLCENGINE', accountRef: 'account', scopeType: 'PROJECT', scopeValue: 'project', agentId: '', deviceId: '', validFrom: '2026-10-01 00:00:00', validTo: '2026-11-01 00:00:00', note: '' } });
  vm.loadBindings = () => {};
  await vm.saveBinding(); assert.equal(payload.workspaceId, 'ws-a'); assert.equal(payload.validFrom, '2026-09-30T16:00:00.000Z'); assert.equal(payload.validTo, '2026-10-31T16:00:00.000Z'); assert.equal(payload.agentId, null);
});

test('CSV import requires explicit official-origin confirmation and a bounded CSV file', async () => {
  storage(); let calls = 0;
  const component = admin({ importBill: async () => { calls++; return {}; } });
  const vm = instance(component, { canManage: true, workspaceId: 'ws-a', accountRef: 'account', $emit() {} });
  vm.selectFile({ target: { files: [{ name: 'unsafe.txt', size: 10 }], value: 'x' } }); assert.equal(vm.file, null);
  vm.selectFile({ target: { files: [{ name: 'bill.csv', size: 10 }], value: 'x' } });
  await vm.importBill(); assert.equal(calls, 0); vm.confirmOfficial = true; await vm.importBill(); assert.equal(calls, 1);
});

test('bill import reports processed rows without inventing insertion or attribution counts', () => {
  const component = admin({});
  const message = component.methods.report({ importedCount: 3 });
  assert.match(message, /处理记录 3 条/); assert.match(message, /覆盖修订/);
  assert.doesNotMatch(message, /未归属|更新 \d+|新增 \d+/);
  assert.match(component.methods.report({}), /处理记录 — 条/);
});

test('API failures reject with safe errors instead of returning an empty report', async () => {
  let failure;
  const request = { url() { return this; }, method() { return this; }, data() { return this; }, timeout() { return this; }, success() { return this; }, fail() { return this; }, networkFail(fn) { failure = fn; return this; }, send() {} };
  const api = loadModule('src/apis/module/billing.js', { '../api': { getServiceUrl: () => '' }, '../httpRequest': { __esModule: true, default: { sendRequest: () => request } } }).default;
  const pending = api.overview({}); failure({ status: 403, data: '<html>private proxy contents</html>' });
  await assert.rejects(pending, /没有权限/);
});

test('multipart import preserves the file and account label without a forced JSON content type', async () => {
  let payload, headers, received;
  const request = { url() { return this; }, method() { return this; }, data(value) { payload = value; return this; }, header(value) { headers = value; return this; }, timeout() { return this; }, success(fn) { received = fn; return this; }, fail() { return this; }, networkFail() { return this; }, send() { received({ data: { code: 0, data: { imported: 1 } } }); } };
  const api = loadModule('src/apis/module/billing.js', { '../api': { getServiceUrl: () => '' }, '../httpRequest': { __esModule: true, default: { sendRequest: () => request } } }).default;
  const result = await api.importBill(new Blob(['header\nrow'], { type: 'text/csv' }), 'DEEPSEEK', 'account-label');
  assert.equal(result.imported, 1); assert.deepEqual(headers, {});
  assert.equal(payload.get('provider'), 'DEEPSEEK'); assert.equal(payload.get('accountRef'), 'account-label'); assert.equal(await payload.get('file').text(), 'header\nrow');
});

test('download rejects HTML proxy responses instead of saving them as official templates', async () => {
  let receive;
  const request = { url() { return this; }, method() { return this; }, data() { return this; }, type() { return this; }, timeout() { return this; }, success(fn) { receive = fn; return this; }, fail() { return this; }, networkFail() { return this; }, send() {} };
  const api = loadModule('src/apis/module/billing.js', { '../api': { getServiceUrl: () => '' }, '../httpRequest': { __esModule: true, default: { sendRequest: () => request } } }).default;
  const rejected = api.importTemplate('DEEPSEEK'); receive({ data: new Blob(['private proxy page'], { type: 'text/html' }) }); await assert.rejects(rejected, /计费请求失败/);
  const accepted = api.importTemplate('DEEPSEEK'); const csv = new Blob(['billId,paidAmount'], { type: 'text/csv;charset=UTF-8' }); receive({ data: csv }); assert.equal(await accepted, csv);
});

test('admin capability must be an authoritative boolean and incomplete provider data stays labeled', () => {
  const component = page({});
  assert.equal(component.computed.canManage.call({ options: { canManage: 'true' } }), false);
  assert.equal(component.computed.canManage.call({ options: { canManage: true } }), true);
  assert.equal(utils.billingLabel('OBSERVED'), '本地观测');
  assert.equal(utils.billingLabel('billable_characters'), '供应商计费字符');
  assert.equal(utils.billingLabel('NO_CALL_LEVEL_BILL'), '无逐调用账单');
  assert.equal(utils.billingLabel('cached_input_tokens'), '输入缓存命中 Token');
  assert.equal(utils.billingLabel('cache_miss_input_tokens'), '输入缓存未命中 Token');
  assert.equal(utils.billingLabel('CONVERSATION'), '对话');
  assert.equal(utils.billingLabel('CHAT_TITLE'), '会话标题');
  assert.equal(utils.billingLabel('AMBIGUOUS'), '归属冲突');
  assert.equal(utils.billingLabel('IMPORTED_STATEMENT'), '人工导入官方账单');
});

test('incomplete usage and pending ledger entries are visible without claiming complete supplier coverage', () => {
  const component = page({});
  const overview = { usageUnknownCount: 3, meteringStatus: { status: 'PENDING', pendingCount: 2, rejectedCount: 0, persistenceFailureCount: 0 } };
  const adminNotices = component.computed.coverageNotices.call({ overview, canManage: true });
  assert.equal(adminNotices.length, 2);
  assert.match(adminNotices[0].title, /3 次调用缺少完整/);
  assert.match(adminNotices[1].title, /2 条记录等待入账/);
  assert.match(adminNotices[1].description, /平台范围/);
  const memberNotices = component.computed.coverageNotices.call({ overview, canManage: false });
  assert.equal(memberNotices.length, 1); assert.equal(memberNotices[0].key, 'usage');
  assert.deepEqual(utils.billingCoverageNotices({ usageUnknownCount: 0, meteringStatus: { status: 'READY' } }, true), []);
});

test('persistent metering failures remain errors after pending records are drained', () => {
  const notices = utils.billingCoverageNotices({ meteringStatus: { status: 'INCOMPLETE', pendingCount: 0, rejectedCount: 1, persistenceFailureCount: 2 } }, true);
  assert.equal(notices.length, 1); assert.equal(notices[0].type, 'error');
  assert.match(notices[0].title, /1 条记录被隔离/); assert.match(notices[0].title, /2 次用量保存失败/);
  assert.doesNotMatch(notices[0].title, /等待入账/);
  const unavailable = utils.billingCoverageNotices({ meteringStatus: { status: 'UNAVAILABLE' } }, true);
  assert.match(unavailable[0].title, /暂不可用/);
  assert.deepEqual(utils.billingCoverageNotices({ meteringStatus: { status: 'COLLECTED_RECORDS_ONLY' } }), []);
});

test('billing route is login-protected and monetary UI is separated from call usage', () => {
  const root = path.join(__dirname, '..');
  const router = fs.readFileSync(path.join(root, 'src/router/index.js'), 'utf8');
  assert.match(router, /protectedRoutes = .*'ModelBillingManagement'/);
  const component = loadVueComponent('src/components/billing/BillingCosts.vue');
  assert.equal(component.props.status.default, 'PENDING_OR_UNATTRIBUTED');
  const template = fs.readFileSync(path.join(root, 'src/components/billing/BillingRecords.vue'), 'utf8');
  assert.match(template, /逐调用实际费用/); assert.match(template, /NOT_MATCHED/);
});

test('current benefits do not request historical overview or validate unrelated dates', async () => {
  storage(); let calls = 0; let route;
  const component = page({ overview: () => { calls++; } });
  const vm = instance(component, { workspaceId: 'ws-a', tab: 'benefits', appliedFilters: { startDate: 'invalid' }, $route: { query: {} }, $router: { replace: value => { route = value; return Promise.resolve(); } } });
  await vm.loadData(); assert.equal(calls, 0); assert.equal(vm.error, '');
  vm.navigate(vm.appliedFilters, 'benefits'); assert.equal(route.query.tab, 'benefits');
  assert.equal(utils.BILLING_TABS.includes('benefits'), true);
});

test('workspace benefits request has no historical or resource filters and never enables account view for members', async () => {
  storage(); let query;
  const component = benefits({ benefits: async value => { query = value; return { workspaceId: 'ws-a', canManage: false, accountView: false, sources: [], packages: [] }; } });
  const vm = instance(component, { workspaceId: 'ws-a', canManage: false, accountView: true });
  await vm.load(); assert.deepEqual(query, { accountView: false }); assert.equal(vm.accountView, false); assert.equal(vm.error, '');
  assert.equal(component.computed.showBalances.call(vm), false);
});

test('benefits account balances require authoritative admin capability and an account response', async () => {
  const component = benefits({});
  assert.equal(component.computed.showBalances.call({ canManage: true, accountView: true, data: { canManage: true, accountView: true } }), true);
  for (const data of [{ canManage: 'true', accountView: true }, { canManage: true, accountView: false }, { canManage: false, accountView: true }]) {
    assert.equal(component.computed.showBalances.call({ canManage: true, accountView: true, data }), false);
  }
  assert.equal(component.methods.quantity(null, 'minutes'), '—');
  assert.equal(component.methods.quantity('0', 'minutes'), '0 分钟');
  assert.equal(component.methods.quantity('9007199254740993.123', 'characters'), '9,007,199,254,740,993.123 字符');
  assert.equal(component.methods.quantity('Unlimited', 'requests'), '不限量');
  assert.equal(component.methods.yesNo('true'), '是'); assert.equal(component.methods.yesNo('false'), '否'); assert.equal(component.methods.yesNo(null), '未提供');
});

test('benefits rejects wrong workspace or downgraded account view responses', async () => {
  storage();
  for (const response of [{ workspaceId: 'ws-b', accountView: true, canManage: true }, { workspaceId: 'ws-a', accountView: false, canManage: true }, { workspaceId: 'ws-a', accountView: true, canManage: false }]) {
    const component = benefits({ benefits: async () => response });
    const vm = instance(component, { workspaceId: 'ws-a', canManage: true, accountView: true });
    await vm.load(); assert.equal(vm.data, null); assert.match(vm.error, /工作空间或权限不匹配/);
  }
});

test('switching benefit scope discards prior account response and workspace switches discard pending snapshots', async () => {
  const values = storage(); const first = deferred(); const second = deferred(); let count = 0;
  const component = benefits({ benefits: () => ++count === 1 ? first.promise : second.promise });
  const vm = instance(component, { workspaceId: 'ws-a', canManage: true, accountView: true });
  const earlier = vm.load(); vm.accountView = false; const later = vm.load();
  second.resolve({ workspaceId: 'ws-a', accountView: false, canManage: true, packages: [{ instanceNo: 'workspace-package' }] }); await later;
  first.resolve({ workspaceId: 'ws-a', accountView: true, canManage: true, packages: [{ instanceNo: 'account-package' }] }); await earlier;
  assert.equal(vm.data.packages[0].instanceNo, 'workspace-package');
  const switched = deferred();
  const component2 = benefits({ benefits: () => switched.promise });
  const vm2 = instance(component2, { workspaceId: 'ws-a', canManage: true });
  const pending = vm2.load(); values.set('currentWorkspaceId', 'ws-b');
  switched.resolve({ workspaceId: 'ws-a', accountView: false, canManage: true }); await pending;
  assert.equal(vm2.data, null);
});

test('benefits service failure is shown as an error instead of an empty package result', async () => {
  storage(); const component = benefits({ benefits: async () => { throw new Error('计费服务暂时不可用'); } });
  const vm = instance(component, { workspaceId: 'ws-a', canManage: false, data: { packages: [{ instanceNo: 'old' }] } });
  await vm.load(); assert.equal(vm.data, null); assert.match(vm.error, /暂时不可用/); assert.equal(vm.loading, false);
});

test('benefits sync remains admin-only and stale workspace cannot trigger a mutation', async () => {
  storage(); let count = 0;
  const component = benefits({ syncBenefits: async () => { count++; } });
  const vm = instance(component, { workspaceId: 'ws-a', canManage: false });
  await vm.sync(); assert.equal(count, 0);
  vm.canManage = true; vm.workspaceId = 'foreign'; await vm.sync(); assert.equal(count, 0);
});

test('partial supplier sync preserves source failure and previous snapshot amounts', async () => {
  storage(); let count = 0;
  const snapshot = { workspaceId: 'ws-a', canManage: true, accountView: false, sources: [{ provider: 'VOLCENGINE', status: 'SYNC_FAILED', lastSyncedAt: '2026-10-01T00:00:00Z' }, { provider: 'DEEPSEEK', status: 'READY' }], packages: [{ instanceNo: 'previous', availableAmount: '12.500' }], balances: [] };
  const component = benefits({ syncBenefits: async () => { count++; return { status: 'PARTIAL' }; }, benefits: async () => snapshot });
  const vm = instance(component, { workspaceId: 'ws-a', canManage: true });
  await vm.sync(); assert.equal(count, 1); assert.equal(vm.syncError, ''); assert.equal(vm.syncing, false);
  assert.equal(vm.data.sources[0].status, 'SYNC_FAILED'); assert.equal(vm.data.packages[0].availableAmount, '12.500');
  assert.equal(vm.sourceType(vm.data.sources[0].status), 'danger');
});

test('failed benefits sync retains previous snapshot with an explicit stale-data error', async () => {
  storage(); const component = benefits({ syncBenefits: async () => { throw new Error('同步失败'); } });
  const old = { workspaceId: 'ws-a', packages: [{ availableAmount: '1' }] };
  const vm = instance(component, { workspaceId: 'ws-a', canManage: true, data: old });
  await vm.sync(); assert.equal(vm.data, old); assert.match(vm.syncError, /此前快照/); assert.equal(vm.syncing, false);
});

test('benefits API keeps current-account selector separate and sync sends an empty object', async () => {
  const calls = []; let call;
  const request = { url(value) { call = { url: value }; calls.push(call); return this; }, method(value) { call.method = value; return this; }, data(value) { call.data = value; return this; }, timeout() { return this; }, success(fn) { call.success = fn; return this; }, fail() { return this; }, networkFail() { return this; }, send() { call.success({ data: { code: 0, data: {} } }); } };
  const api = loadModule('src/apis/module/billing.js', { '../api': { getServiceUrl: () => '' }, '../httpRequest': { __esModule: true, default: { sendRequest: () => request } } }).default;
  await api.benefits({ accountView: false }); await api.syncBenefits();
  assert.equal(calls[0].url, '/billing/benefits'); assert.equal(calls[0].method, 'GET'); assert.deepEqual(calls[0].data, { accountView: false });
  assert.equal(calls[1].url, '/billing/benefits/sync'); assert.equal(calls[1].method, 'POST'); assert.deepEqual(calls[1].data, {});
});

test('supplier resource deductions stay quantities with original units and never become currency amounts', () => {
  const component = loadVueComponent('src/components/billing/BillingRecords.vue');
  const fields = component.methods.supplierUsage({ Count: '100.500', Unit: 'token', UseDuration: '60', UseDurationUnit: 'seconds', DeductionCount: '0', DeductionUseDuration: null, payableAmount: '999', currency: 'CNY' });
  assert.deepEqual(fields, [{ name: '计量数量', value: '100.500 Token' }, { name: '使用时长', value: '60 秒' }, { name: '抵扣数量', value: '0 Token' }, { name: '抵扣时长', value: '—' }]);
  assert.equal(utils.supplierUsageRows({}).every(item => item.value === '—'), true);
});

test('speech package purchase and current usage never produce a fabricated supplier balance', async () => {
  storage();
  const speech = Object.freeze({ provider: 'VOLCENGINE_SPEECH', instanceNo: 'speech-1', purchasedAmount: '100.500', currentUsage: '20.250', isShareable: true, totalAmount: null, availableAmount: null, unit: null, expiryTime: '供应商未提供有效期' });
  const component = benefits({ benefits: async () => ({ workspaceId: 'ws-a', canManage: false, accountView: false, packages: [speech], sources: [] }) });
  const vm = instance(component, { workspaceId: 'ws-a', canManage: false });
  await vm.load();
  const row = vm.data.packages[0];
  assert.equal(vm.quantity(row.purchasedAmount, row.unit), '100.500（单位：—）');
  assert.equal(vm.quantity(row.currentUsage, row.unit), '20.250（单位：—）');
  assert.equal(vm.quantity(row.totalAmount, row.unit), '—'); assert.equal(vm.quantity(row.availableAmount, row.unit), '—');
  assert.equal(vm.time(row.expiryTime), '供应商未提供有效期'); assert.equal(vm.time(null), '—');
  assert.equal(vm.shareability(true), '可共享'); assert.equal(vm.shareability(false), '不可共享'); assert.equal(vm.shareability(null), '未知');
  assert.equal(vm.label(row.provider), '火山语音资源包'); assert.equal(vm.label(row.unit), '—');
});

test('anonymous free packages get distinct display identities without inventing bindable instance IDs', async () => {
  storage();
  const first = Object.freeze({ provider: 'VOLCENGINE_SPEECH', accountRef: 'speech-account', rowKey: 'speech-pack-0', instanceNo: null, code: 'same-product', name: '赠送语音包' });
  const second = Object.freeze({ ...first, rowKey: 'speech-pack-1' });
  const packages = [first, second, Object.freeze({ ...first, accountRef: 'another-account' }), Object.freeze({ ...first, provider: 'VOLCENGINE' })];
  let bindingCalls = 0; let syncArguments;
  const component = benefits({
    benefits: async () => ({ workspaceId: 'ws-a', accountView: false, canManage: true, sources: [], packages }),
    syncBenefits: async (...args) => { syncArguments = args; },
    createBinding: async () => { bindingCalls++; }
  });
  const vm = instance(component, { workspaceId: 'ws-a', canManage: true });
  await vm.load();
  assert.equal(vm.data.packages.length, 4);
  assert.equal(new Set(vm.data.packages.map(vm.packageKey)).size, 4);
  assert.equal(vm.packageKey(first), vm.packageKey({ ...first, instanceNo: 'irrelevant-for-display-key' }));
  assert.equal(vm.instanceLabel(first), '未知（供应商未返回）');
  assert.equal(vm.instanceLabel({ ...first, instanceNo: 'real-supplier-instance' }), 'real-supplier-instance');
  assert.equal(first.instanceNo, null); assert.equal(second.instanceNo, null);
  await vm.sync(); assert.deepEqual(syncArguments, []); assert.equal(bindingCalls, 0);
  const instanceOnly = { provider: first.provider, accountRef: first.accountRef, instanceNo: first.rowKey };
  assert.notEqual(vm.packageKey(instanceOnly), vm.packageKey(first));
});
