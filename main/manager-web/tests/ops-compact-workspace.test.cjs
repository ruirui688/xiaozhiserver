const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue');
const compiler = require('vue-template-compiler');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const nav = loadModule('src/ops/navigation.js');
const profile = permissions => ({ audience: 'OPS', username: 'test-employee', permissions });
const nodes = vnode => [vnode, ...(vnode.children || []).flatMap(nodes)];
const text = vnode => [vnode.text || '', ...(vnode.children || []).map(text)].join('');

function component(t, file, { user = profile([]), route = '/home', operations = {} } = {}) {
  const definition = loadVueComponent(file, {
    '@/apis/platform': { operations },
    '@/apis/api': { __esModule: true, default: { user: {} } },
    '@/utils': { getUUID: () => 'test-only-id', sm2Encrypt: () => 'test-only-ciphertext' },
  });
  const descriptor = compiler.parseComponent(fs.readFileSync(path.resolve(__dirname, '..', file), 'utf8'));
  const compiled = compiler.compileToFunctions(descriptor.template.content);
  const calls = { commits: [], routes: [], warnings: [] };
  const vm = new Vue({ ...definition, render: compiled.render, staticRenderFns: compiled.staticRenderFns,
    beforeCreate() {
      this.$store = { state: Vue.observable({ userInfo: user }), commit: value => calls.commits.push(value) };
      this.$route = { path: route, query: {} };
      this.$router = { replace: value => calls.routes.push(value) };
      this.$message = { warning: value => calls.warnings.push(value) };
    },
  });
  t.after(() => vm.$destroy());
  return { vm, calls };
}

test('quick tasks use exact service tab permissions and keep every destination authorized', () => {
  const cases = [
    [[], []],
    [['allowance.read'], ['allowance', 'tasks']],
    [['pool.read'], ['allowance', 'tasks']],
    [['credentials.read'], ['credentials', 'tasks']],
    [['rates.read'], ['rates']],
    [['allowance.review'], ['review']],
  ];
  for (const [permissions, tabs] of cases) {
    const user = profile(permissions), tasks = nav.availableOperationsTasks(user);
    assert.deepEqual(tasks.map(task => task.to.query.tab), tabs);
    for (const task of tasks) assert.equal(nav.canVisitOperations(user, task.to.path), true);
  }
  for (const user of [null, { audience: 'USER', permissions: ['credentials.read', 'supplier.bill.read'] }, profile(['*'])]) {
    assert.deepEqual(nav.availableOperationsTasks(user), []);
  }
});

test('financial quick tasks never expose credential, model or tenant content destinations', () => {
  const user = profile(['supplier.bill.read', 'pool.read', 'allowance.review', 'official.resource.approve', 'audit.read']);
  const tasks = nav.availableOperationsTasks(user);
  assert.ok(tasks.some(task => task.to === '/model-billing'));
  assert.ok(tasks.some(task => task.to === '/approvals'));
  assert.ok(!tasks.some(task => ['credentials', 'rates'].includes(task.to?.query?.tab)));
  assert.deepEqual(tasks.map(task => task.key), ['/model-billing', 'review', '/audit', '/approvals', 'allowance', 'tasks']);
  assert.equal(nav.availableOperations(user).find(item => item.path === '/services').label, '额度总池');
  assert.equal(tasks.find(task => task.key === 'allowance').label, '额度总池');
  assert.equal(nav.availableOperations(profile(['allowance.review']))[0].label, '用量复核');
  for (const task of tasks) assert.equal(nav.canVisitOperations(user, typeof task.to === 'string' ? task.to : task.to.path), true);
});

test('header renders visible authorized links, marks the current page and reacts to revoked permissions', t => {
  const { vm } = component(t, 'src/ops/OpsHeader.vue', { user: profile(['credentials.read', 'model.catalog.read']), route: '/services' });
  const links = () => nodes(vm._render()).filter(node => node.tag === 'router-link' && node.data?.staticClass === 'ops-nav-link');
  assert.deepEqual(links().map(node => node.data.attrs.to), ['/home', '/services', '/model-config']);
  assert.deepEqual(links().filter(node => node.data.attrs['aria-current'] === 'page').map(node => node.data.attrs.to), ['/services']);
  assert.equal(nodes(vm._render()).some(node => node.tag === 'el-select'), false);
  vm.$store.state.userInfo = profile(['supplier.bill.read']);
  assert.deepEqual(links().map(node => node.data.attrs.to), ['/home', '/model-billing']);
});

test('home presents actionable links without loading business summaries or retaining large recovery notices', t => {
  const { vm } = component(t, 'src/ops/OpsHome.vue', { user: profile(['credentials.read', 'rates.read', 'model.catalog.read']) });
  const rendered = vm._render();
  const links = nodes(rendered).filter(node => node.tag === 'router-link');
  assert.deepEqual(links.map(node => node.data.attrs.to), [
    { path: '/services', query: { tab: 'credentials' } },
    '/model-config',
    { path: '/services', query: { tab: 'rates' } },
    { path: '/services', query: { tab: 'tasks' } },
  ]);
  assert.match(text(rendered), /常用任务/);
  assert.doesNotMatch(text(rendered), /今天，从这里开始|岗位各有边界|本人身份恢复码/);
  vm.$store.state.userInfo = profile([]);
  assert.equal(nodes(vm._render()).filter(node => node.tag === 'router-link').length, 0);
  assert.equal(nodes(vm._render()).some(node => node.tag === 'el-empty'), true);
});

test('content operators see official resources and firmware first while pool and platform tasks stay later', () => {
  const tasks = nav.availableOperationsTasks(profile(['pool.read', 'official.resource.read', 'ota.read', 'prompt.catalog.read', 'system.read']));
  assert.deepEqual(tasks.slice(0, 3).map(task => task.key), ['/official-resources', '/ota-management', '/function-plugin-management']);
  assert.deepEqual(tasks.slice(-2).map(task => task.key), ['allowance', 'tasks']);
  assert.equal(tasks.at(-2).label, '额度总池');
});

test('recovery entry occupies no space when unavailable and only offers its small action after a valid policy', async t => {
  const { vm } = component(t, 'src/ops/OpsRecoveryCode.vue', { operations: { identityPolicy: async () => ({ available: false }) } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(vm._render().isComment, true);
  vm.identityPolicy = { available: true };
  const rendered = vm._render();
  assert.equal(rendered.data.staticClass, 'ops-recovery-access');
  assert.match(text(rendered), /账户恢复码/);
  assert.equal(vm.visible, false);
  vm.identityPolicyError = 'test-only policy failure';
  assert.equal(vm._render().isComment, true);
});

test('logout prevents duplicate requests and clears the local session even when the server fails', async t => {
  let reject, requests = 0;
  const pending = new Promise((resolve, fail) => { reject = fail; });
  const { vm, calls } = component(t, 'src/ops/OpsHeader.vue', { operations: { logout: () => { requests++; return pending; } } });
  const first = vm.logout();
  await vm.logout();
  assert.equal(requests, 1);
  assert.equal(vm.loggingOut, true);
  reject(new Error('test-only network failure'));
  await first;
  assert.deepEqual(calls.commits, ['clearAuth']);
  assert.deepEqual(calls.routes, ['/login']);
  assert.equal(calls.warnings.length, 1);
  assert.equal(vm.loggingOut, false);
});
