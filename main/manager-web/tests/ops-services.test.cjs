const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const Vue = require('vue');
const compiler = require('vue-template-compiler');
const { loadVueComponent, loadModule } = require('./helpers/loadVueComponent.cjs');
const settled = () => new Promise(resolve => setImmediate(resolve));
const profile = permissions => ({ audience: 'OPS', permissions });
const nodes = vnode => [vnode, ...(vnode.children || []).flatMap(nodes)];
const text = vnode => [vnode.text || '', ...(vnode.children || []).map(text)].join('');
const full = ['allowance.read', 'allowance.grant', 'allowance.freeze', 'pool.read', 'pool.adjust', 'allowance.review', 'credentials.read', 'rates.read'];
const account = (userId = '9223372036854775806') => ({ userId, username: '测试用户', available: '1234.567890', reserved: '0.000004', spent: '9007199254740993.126', frozen: false });
function deferred() { let resolve; return { promise: new Promise(done => { resolve = done; }), resolve }; }
function create(t, file = 'OpsAllowance', { permissions = full, query = {}, operations = {}, platformRequest } = {}) {
  const calls = { routes: [], accounts: [], grant: [], freeze: [], pool: [] };
  const defaults = {
    accounts: async args => { calls.accounts.push(args); return { list: [account()], total: 1 }; },
    pool: async () => ({ total: '1000.000000', allocated: '12.340000', available: '987.660000' }),
    allowanceRequests: async () => ({ list: [] }),
    grant: async value => { calls.grant.push(value); },
    freeze: async value => { calls.freeze.push(value); },
    adjustPool: async value => { calls.pool.push(value); },
  };
  const definition = loadVueComponent(`src/ops/${file}.vue`, { '@/apis/platform': { operations: { ...defaults, ...operations }, platformRequest } });
  const template = compiler.parseComponent(fs.readFileSync(path.resolve(__dirname, `../src/ops/${file}.vue`), 'utf8')).template.content;
  assert.deepEqual(compiler.compile(template).errors, []);
  const compiled = compiler.compileToFunctions(template);
  const vm = new Vue({ ...definition, render: compiled.render, staticRenderFns: compiled.staticRenderFns,
    beforeCreate() {
      this.$store = { state: Vue.observable({ userInfo: profile(permissions) }) };
      this.$route = Vue.observable({ path: '/services', query, hash: '#test' });
      this.$router = { replace: async route => { calls.routes.push(route); this.$route.query = route.query; } };
      this.$message = { success() {}, error() {} };
    },
  });
  t.after(() => vm.$destroy());
  return { vm, calls };
}
function column(vm, label) { return nodes(vm._render()).find(node => node.tag === 'el-table-column' && node.data.attrs.label === label); }
function cell(vm, label, row) { return column(vm, label).data.scopedSlots.default({ row }); }
function panel(vm) { return nodes(vm._render()).filter(node => node.componentOptions).map(node => node.componentOptions.tag).filter(tag => tag.startsWith('Ops')); }

function renderElementPagination(attrs) {
  // Isolate Element's real rendering in SSR mode; no browser or DOM dependency is needed.
  return JSON.parse(execFileSync(process.execPath, ['-e', `
    const Vue = require('vue');
    const Pagination = require('element-ui/lib/pagination').default;
    const attrs = JSON.parse(process.argv[1]);
    const vm = new Vue({ ...Pagination, propsData: { total: attrs.total, pageSize: attrs['page-size'], currentPage: attrs['current-page'], layout: attrs.layout } });
    const total = new Vue({ ...Pagination.components.Total, parent: vm });
    const pager = new Vue({ ...Pagination.components.Pager, propsData: { currentPage: vm.internalCurrentPage, pageCount: vm.internalPageCount, pagerCount: 7 } });
    const text = node => [node.text || '', ...(node.children || []).map(text)].join('');
    const result = { pageCount: vm.internalPageCount, total: text(total._render()), pages: pager._render().children.filter(node => node.data?.staticClass === 'number').map(text), changes: [] };
    vm.$on('current-change', page => result.changes.push(page));
    if (vm.internalPageCount > 1) vm.next();
    vm.$nextTick(() => { process.stdout.write(JSON.stringify(result)); pager.$destroy(); total.$destroy(); vm.$destroy(); });
  `, JSON.stringify(attrs)], { cwd: path.resolve(__dirname, '..'), env: { ...process.env, VUE_ENV: 'server' }, encoding: 'utf8' }));
}

test('serialized Long counts render the real Element total, page numbers and next-page action', async t => {
  for (const count of ['8', '21']) {
    const { vm } = create(t, 'OpsAllowance', { operations: { accounts: async () => ({ list: [account()], total: count }) } });
    await settled();
    const pagination = nodes(vm._render()).find(node => node.tag === 'el-pagination');
    assert.equal(typeof pagination.data.attrs.total, 'number');
    const result = renderElementPagination(pagination.data.attrs);
    assert.equal(result.total, `共 ${count} 条`);
    assert.deepEqual(result.pages, count === '8' ? ['1'] : ['1', '2']);
    assert.equal(result.pageCount, count === '8' ? 1 : 2);
    assert.deepEqual(result.changes, count === '8' ? [] : [2]);
    assert.equal(vm.accounts[0].userId, '9223372036854775806');
    assert.equal(vm.accounts[0].spent, '9007199254740993.126');
  }
});

test('pagination count conversion accepts only nonnegative safe integers', () => {
  const { paginationTotal } = loadModule('src/ops/service-display.js');
  for (const value of ['8', 8, '0008']) assert.equal(paginationTotal(value), 8);
  assert.equal(paginationTotal(String(Number.MAX_SAFE_INTEGER)), Number.MAX_SAFE_INTEGER);
  for (const value of [undefined, null, '', 'abc', '8.5', -1, '-1', true, {}, Infinity, NaN, '9007199254740992']) assert.equal(paginationTotal(value), 0);
});

for (const [file, endpoint, permission] of [
  ['OpsReservationReview', '/ops/platform/reservations/review', 'allowance.review'],
]) {
  test(`${file} renders serialized counts and fetches the next page without changing row values`, async t => {
    for (const count of ['0', '8', '21']) {
      const calls = [];
      const row = { id: '9223372036854775806', reservedAmount: '9007199254740993.126' };
      const { vm } = create(t, file, {
        permissions: [permission],
        platformRequest: async (method, url, params) => {
          calls.push({ method, url, params });
          return url === endpoint ? { list: count === '0' ? [] : [{ ...row }], total: count } : [];
        },
      });
      await settled();
      const pagination = nodes(vm._render()).find(node => node.tag === 'el-pagination');
      assert.equal(typeof pagination.data.attrs.total, 'number');
      const result = renderElementPagination(pagination.data.attrs);
      assert.equal(result.total, `共 ${count} 条`);
      assert.deepEqual(result.pages, count === '21' ? ['1', '2'] : ['1']);
      assert.equal(result.pageCount, count === '21' ? 2 : 1);
      if (count !== '0') assert.deepEqual({ ...vm.rows[0] }, row);
      for (const page of result.changes) pagination.data.on['current-change'](page);
      await settled();
      const requests = calls.filter(call => call.url === endpoint);
      assert.deepEqual(requests.map(call => call.params), count === '21' ? [{ page: 1, limit: 20 }, { page: 2, limit: 20 }] : [{ page: 1, limit: 20 }]);
      assert.ok(calls.every(call => call.method === 'GET'));
      assert.equal(vm.page, count === '21' ? 2 : 1);
    }
  });
}

test('tabs default to allowance, retain deep links and never mount unauthorized panels', async t => {
  const normal = create(t, 'OpsServices');
  assert.equal(normal.vm.activeTab, 'allowance');
  assert.deepEqual(normal.vm.tabs.map(tab => tab.id), ['allowance', 'credentials', 'rates', 'review', 'tasks']);
  assert.deepEqual(panel(normal.vm), ['OpsAllowance']);
  const linked = create(t, 'OpsServices', { query: { tab: 'rates', filter: 'kept' } });
  assert.equal(linked.vm.activeTab, 'rates');
  assert.deepEqual(panel(linked.vm), ['OpsRates']);
  await linked.vm.selectTab('credentials');
  assert.deepEqual(linked.calls.routes.at(-1), { path: '/services', query: { tab: 'credentials', filter: 'kept' }, hash: '#test' });
  const denied = create(t, 'OpsServices', { permissions: ['allowance.review'], query: { tab: 'allowance' } });
  assert.equal(denied.vm.activeTab, 'review');
  assert.deepEqual(panel(denied.vm), ['OpsReservationReview']);
  await denied.vm.selectTab('rates');
  assert.equal(denied.calls.routes.length, 1);
  denied.vm.$store.state.userInfo = profile([]);
  assert.deepEqual(panel(denied.vm), []);
  assert.match(text(denied.vm._render()), /没有可查看/);
  const finance = create(t, 'OpsServices', { permissions: ['pool.read', 'allowance.review'] });
  assert.deepEqual(finance.vm.tabs.map(tab => tab.label), ['额度总池', '用量复核', '平台任务']);
});

test('money cells preserve tiny nonzero values, huge precision and full user IDs', async t => {
  const { vm } = create(t); await settled(); const row = account();
  assert.equal(cell(vm, '可用额度（元）', row).map(text).join(''), '1234.57');
  assert.equal(cell(vm, '使用中（元）', row).map(text).join(''), '小于 0.01');
  assert.equal(cell(vm, '累计使用（元）', row).map(text).join(''), '9007199254740993.13');
  assert.equal(cell(vm, '可用额度（元）', row)[0].data.attrs.title, '1234.567890');
  assert.match(cell(vm, '用户', row).map(text).join(''), /测试用户9223372036854775806/);
  assert.equal(row.spent, '9007199254740993.126');
  assert.match(cell(vm, '状态', row).map(text).join(''), /正常/);
});

test('server search trims terms, resets the page and preserves filters across paging', async t => {
  const { vm, calls } = create(t); await settled(); vm.page = 4; vm.keyword = '  测试用户  ';
  const button = nodes(vm._render()).find(node => node.tag === 'el-button' && text(node) === '搜索');
  await button.data.on.click();
  assert.deepEqual(calls.accounts.at(-1), { page: 1, limit: 20, keyword: '测试用户' });
  await vm.changePage(2);
  assert.deepEqual(calls.accounts.at(-1), { page: 2, limit: 20, keyword: '测试用户' });
  vm.keyword = ''; await vm.search();
  assert.deepEqual(calls.accounts.at(-1), { page: 1, limit: 20, keyword: '' });
});

test('late account searches cannot replace newer results', async t => {
  const old = deferred();
  const { vm } = create(t, 'OpsAllowance', { operations: { accounts: args => args.keyword === 'old' ? old.promise : Promise.resolve({ list: [account('42')], total: 1 }) } });
  await settled(); vm.keyword = 'old'; const first = vm.search();
  vm.keyword = 'new'; await vm.search();
  old.resolve({ list: [account('43')], total: 1 }); await first;
  assert.equal(vm.accounts[0].userId, '42');
});

test('row grant locks the exact user and submits unchanged input only after confirmation', async t => {
  const { vm, calls } = create(t); await settled();
  const action = cell(vm, '操作', account()).flatMap(nodes).find(node => node.tag === 'el-button' && text(node) === '发放额度');
  action.data.on.click();
  assert.equal(vm.form.userId, '9223372036854775806');
  assert.equal(vm.targetLocked, true);
  assert.equal(nodes(vm._render()).find(node => node.tag === 'el-select').data.attrs.disabled, true);
  assert.equal(calls.grant.length, 0);
  vm.form.amount = '10.000001'; vm.form.reason = 'test-only grant'; await vm.save();
  assert.equal(calls.grant.length, 1);
  assert.equal(calls.grant[0].userId, '9223372036854775806');
  assert.equal(calls.grant[0].amount, '10.000001');
});

test('top grant searches remotely and loads users beyond the first twenty results', async t => {
  const calls = [];
  const { vm } = create(t, 'OpsAllowance', { operations: { accounts: async args => { calls.push(args); return { list: [account(args.page === 1 ? '9223372036854775806' : '9223372036854775805')], total: '21' }; } } });
  await settled(); vm.open('grant'); await settled();
  assert.equal(vm.targetLocked, false); assert.equal(vm.hasMoreUsers, true);
  assert.equal(vm.userTotal, 21);
  await vm.searchUsers('  another  ');
  assert.deepEqual(calls.at(-1), { page: 1, limit: 20, keyword: 'another' });
  await vm.loadMoreUsers();
  assert.deepEqual(calls.at(-1), { page: 2, limit: 20, keyword: 'another' });
  assert.deepEqual(vm.userOptions.map(row => row.userId), ['9223372036854775806', '9223372036854775805']);
  assert.equal(vm.hasMoreUsers, false);
});

test('remote selection ignores stale responses and rejects unsafe numeric IDs', async t => {
  const old = deferred();
  const { vm, calls } = create(t, 'OpsAllowance', { operations: { accounts: args => args.keyword === 'old' ? old.promise : Promise.resolve({ list: [account('42')], total: 1 }) } });
  await settled(); vm.open('grant'); await settled();
  const first = vm.searchUsers('old'); await vm.searchUsers('new');
  old.resolve({ list: [account('43')], total: 1 }); await first;
  assert.equal(vm.userOptions[0].userId, '42');
  vm.open('grant', account(9223372036854775806));
  vm.form.amount = '1'; vm.form.reason = 'test-only'; await vm.save();
  assert.equal(vm.form.userId, ''); assert.equal(calls.grant.length, 0);
  const helper = loadModule('src/ops/service-display.js');
  assert.equal(helper.exactUserId('9223372036854775806'), '9223372036854775806');
  assert.equal(helper.exactUserId(9223372036854775806), '');
});

test('read-only staff see no write controls and write methods reject unauthorized calls', async t => {
  for (const permissions of [['allowance.read'], ['pool.read', 'allowance.review']]) {
    const { vm, calls } = create(t, 'OpsAllowance', { permissions }); await settled();
    assert.equal(column(vm, '操作'), undefined);
    const buttons = nodes(vm._render()).filter(node => node.tag === 'el-button').map(text);
    assert.ok(!buttons.includes('发放额度')); assert.ok(!buttons.includes('调整总池'));
    vm.open('grant', account()); assert.equal(vm.dialog, false);
    vm.open('freeze', account()); assert.equal(vm.dialog, false);
    vm.action = 'grant'; vm.form = { userId: '42', amount: '1', reason: 'test-only' };
    await vm.save(); assert.equal(calls.grant.length, 0);
    if (!permissions.includes('allowance.read')) assert.equal(calls.accounts.length, 0);
  }
});

test('empty legacy requests occupy no space and existing requests are collapsed', async t => {
  const { vm } = create(t); await settled();
  assert.equal(nodes(vm._render()).some(node => node.tag === 'el-collapse'), false);
  vm.requests = [{ id: 'test-only-legacy', userId: '42', requestedAmount: '1.000000' }];
  const collapse = nodes(vm._render()).find(node => node.tag === 'el-collapse');
  assert.ok(collapse); assert.equal(collapse.data?.model, undefined); assert.equal(collapse.data?.attrs?.value, undefined);
});

test('freeze stays under More and needs a reason plus explicit confirmation', async t => {
  const { vm, calls } = create(t); await settled();
  const actions = cell(vm, '操作', account()).flatMap(nodes);
  assert.ok(!actions.some(node => node.tag === 'el-button' && text(node) === '冻结账户'));
  actions.find(node => node.tag === 'el-dropdown').data.on.command('freeze');
  assert.equal(vm.dialog, true); assert.equal(calls.freeze.length, 0);
  await vm.save(); assert.equal(calls.freeze.length, 0);
  vm.form.reason = 'test-only freeze'; await vm.save();
  assert.equal(calls.freeze.length, 1); assert.equal(calls.freeze[0].userId, '9223372036854775806');
  assert.equal(calls.freeze[0].frozen, true);
});
