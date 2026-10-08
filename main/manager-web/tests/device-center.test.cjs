const assert = require('node:assert/strict');
const { test } = require('node:test');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const ok = data => ({ data: { code: 0, data } });
function mocks(device) {
  return {
    '@/apis/api': { __esModule: true, default: { device }, getServiceUrl: () => '' },
    '@/apis/httpRequest': { __esModule: true, default: {} },
  };
}
function service(device) { return loadModule('src/utils/deviceCenter.js', mocks(device)); }
test('direct switch reuses existing scene and does not rebind device', async () => {
  let switched;
  const api = service({
    getDeviceScenes: (id, cb) => cb(ok([{ id: 'binding', agentId: 'a', enabled: 1 }])),
    switchDeviceScene: (id, binding, cb) => { switched = [id, binding]; cb(ok({ switchStatus: 'REQUESTED' })); }
  });
  assert.equal((await api.switchDeviceAgent('d', { id: 'a' })).switchStatus, 'REQUESTED');
  assert.deepEqual(switched, ['d', 'binding']);
});
test('first switch creates a scene before switching', async () => {
  const calls = [];
  const api = service({
    getDeviceScenes: (id, cb) => cb(ok([])),
    createDeviceScene: (id, payload, cb) => { calls.push(payload); cb(ok({ id: 'new' })); },
    switchDeviceScene: (id, binding, cb) => { calls.push(binding); cb(ok({})); }
  });
  await api.switchDeviceAgent('d', { id: 'a', agentName: '老师' });
  assert.deepEqual(calls, [{ agentId: 'a', sceneName: '老师', enabled: 1, switchAliases: [], sort: 0 }, 'new']);
});
test('disabled scenes cannot be silently enabled', async () => {
  const api = service({ getDeviceScenes: (id, cb) => cb(ok([{ agentId: 'a', enabled: 0 }])) });
  await assert.rejects(api.switchDeviceAgent('d', { id: 'a' }), /已禁用/);
});
test('scene validation failure is surfaced without executing switch', async () => {
  const api = service({
    getDeviceScenes: (id, cb) => cb(ok([])),
    createDeviceScene: (id, payload, cb) => cb({ data: { code: 1, msg: '跨工作空间拒绝' } })
  });
  await assert.rejects(api.switchDeviceAgent('d', { id: 'a' }), /跨工作空间拒绝/);
});
test('device list has bounded concurrency and deduplicates devices', async () => {
  let pending = 0, max = 0;
  const api = service({ getAgentBindDevices: (id, cb) => {
    pending++; max = Math.max(max, pending);
    setImmediate(() => { pending--; cb(ok([{ id: 'device' }])); });
  } });
  const devices = await api.listWorkspaceDevices(Array.from({ length: 10 }, (_, id) => ({ id: String(id) })));
  assert.equal(devices.length, 1); assert.equal(max, 4);
});
test('failed device reads are not reported as an empty workspace', async () => {
  const api = service({ getAgentBindDevices: (id, cb) => cb({ data: { code: 1, msg: '无权限' } }) });
  await assert.rejects(api.listWorkspaceDevices([{ id: 'a' }]), /无权限/);
});
function card(device = {}) { return loadVueComponent('src/components/device/DeviceAgentCard.vue', mocks(device)); }
test('rollback restores current agent and displays failure from audit', () => {
  const component = card(); const emitted = [];
  const vm = { $emit: (...args) => emitted.push(args) };
  component.methods.applyScenes.call(vm, [
    { agentId: 'old', agentName: '旧智能体', current: true },
    { agentId: 'new', current: false, switchStatus: 'ROLLED_BACK' }
  ]);
  assert.equal(vm.currentAgentId, 'old'); assert.equal(vm.failed, true);
  assert.match(vm.status, /已回滚/); assert.equal(emitted[0][1].agentId, 'old');
});
test('poll discards response after component destruction or a newer request', async () => {
  let complete;
  const component = card({ getDeviceScenes: (id, cb) => { complete = cb; } });
  const vm = { generation: 1, device: { id: 'd' }, applyScenes() { assert.fail('stale result applied'); } };
  const pending = component.methods.poll.call(vm, 1, 0);
  vm.generation = 2; complete(ok([{ current: true }])); await pending;
});
test('failed switch leaves displayed current agent unchanged', async () => {
  const component = card({ getDeviceScenes: (id, cb) => cb({ data: { code: 1, msg: '模型未通过测试' } }) });
  const vm = { ...component.methods, busy: false, canWrite: true, currentAgentId: 'old', targetId: 'new',
    agents: [{ id: 'new' }], generation: 0, device: { id: 'd' }, $emit() {} };
  await vm.switchAgent();
  assert.equal(vm.currentAgentId, 'old'); assert.equal(vm.busy, false); assert.equal(vm.failed, true);
  assert.match(vm.status, /模型未通过测试/);
});
test('pending switch is polled without claiming device has applied it', async () => {
  const component = card({ getDeviceScenes: (id, cb) => cb(ok([
    { agentId: 'new', agentName: '新智能体', current: true, switchStatus: 'SCHEDULED' }
  ])) });
  const vm = { ...component.methods, generation: 1, device: { id: 'd' }, $emit() {}, busy: true };
  await vm.poll(1, 0);
  assert.equal(vm.busy, true); assert.match(vm.status, /等待本轮结束/);
  clearTimeout(vm.timer);
  await vm.poll(1, 40);
  assert.equal(vm.busy, false); assert.match(vm.status, /稍后刷新确认/);
});
test('API business errors reach scene callback to release loading state', () => {
  let failure;
  const request = { url() { return this; }, method() { return this; }, data() { return this; },
    success() { return this; }, fail(cb) { failure = cb; return this; }, networkFail() { return this; }, send() {} };
  const api = loadModule('src/apis/module/device.js', {
    '../api': { getServiceUrl: () => '' },
    '../httpRequest': { __esModule: true, default: { sendRequest: () => request } }
  }).default;
  let received;
  api.switchDeviceScene('d', 'b', res => { received = res; });
  const denied = { data: { code: 403, msg: '无写权限' } };
  failure(denied); assert.equal(received, denied);
});
test('device center paginates filtered devices and resets page when filters change', () => {
  const component = loadVueComponent('src/views/DeviceCenter.vue', mocks({}));
  const devices = Array.from({ length: 53 }, (_, id) => ({ id: String(id) }));
  const context = { filteredDevices: devices, currentPage: 3, pageSize: 20 };
  assert.equal(component.computed.pagedDevices.call(context).length, 13);
  assert.equal(component.computed.pagedDevices.call(context)[0].id, '40');
  component.watch.keyword.call(context);
  assert.equal(context.currentPage, 1);
  context.currentPage = 3;
  component.watch['filteredDevices.length'].call(context, 12);
  assert.equal(context.currentPage, 1);
  context.currentPage = 2;
  component.watch.agentFilter.call(context);
  assert.equal(context.currentPage, 1);
});
test('agent picker opens on demand and cancellation restores current agent', () => {
  const component = card();
  const vm = { busy: false, canWrite: true, currentAgentId: 'current', targetId: 'old', editingAgent: false };
  component.methods.beginAgentEdit.call(vm);
  assert.equal(vm.editingAgent, true); assert.equal(vm.targetId, '');
  vm.targetId = 'candidate'; component.methods.cancelAgentEdit.call(vm);
  assert.equal(vm.editingAgent, false); assert.equal(vm.targetId, 'current');
  vm.canWrite = false; component.methods.beginAgentEdit.call(vm);
  assert.equal(vm.editingAgent, false);
  vm.canWrite = true; vm.busy = true; component.methods.beginAgentEdit.call(vm);
  assert.equal(vm.editingAgent, false);
});
test('confirmed applied switch closes inline picker', async () => {
  const component = card({ getDeviceScenes: (id, cb) => cb(ok([
    { agentId: 'new', agentName: '新智能体', current: true, switchStatus: 'APPLIED' }
  ])) });
  const vm = { ...component.methods, generation: 1, device: { id: 'd' }, $emit() {}, busy: true, editingAgent: true };
  await vm.poll(1, 0);
  assert.equal(vm.editingAgent, false); assert.equal(vm.currentAgentId, 'new');
});

test('failed scene read never writes and does not claim an association was created', async () => {
  const component = card({
    getDeviceScenes: (id, cb) => cb({ data: { code: -1, msg: 'HTTP 502' } }),
    createDeviceScene: () => assert.fail('read failure must stop writes'),
    switchDeviceScene: () => assert.fail('read failure must stop switching')
  });
  const vm = { ...component.methods, busy: false, canWrite: true, currentAgentId: 'old', targetId: 'new',
    agents: [{ id: 'new' }], generation: 0, device: { id: 'd' }, $emit() {} };
  await vm.switchAgent();
  assert.match(vm.status, /HTTP 502.*尚未提交切换/);
  assert.doesNotMatch(vm.status, /新关联|已保留/);
  assert.equal(vm.currentAgentId, 'old');
  assert.equal(vm.busy, false);
});

test('lost create response reports uncertain association without submitting switch', async () => {
  const api = service({
    getDeviceScenes: (id, cb) => cb(ok([])),
    createDeviceScene: (id, data, cb) => cb({ data: { code: -1, msg: '超时' } }),
    switchDeviceScene: () => assert.fail('cannot switch without binding')
  });
  await assert.rejects(api.switchDeviceAgent('d', { id: 'a' }), /尚未提交切换.*确认关联状态/);
});

test('lost switch response preserves binding and requests authoritative refresh', async () => {
  const api = service({
    getDeviceScenes: (id, cb) => cb(ok([])),
    createDeviceScene: (id, data, cb) => cb(ok({ id: 'b' })),
    switchDeviceScene: (id, binding, cb) => cb({ data: { code: -1, msg: '超时' } }),
    deleteDeviceScene: () => assert.fail('must retain binding')
  });
  await assert.rejects(api.switchDeviceAgent('d', { id: 'a' }), /刷新确认切换结果.*关联场景已保留/);
});

test('scene endpoints preserve HTTP status and never display raw proxy or request data', () => {
  let failure;
  const request = { url() { return this; }, method() { return this; }, data() { return this; },
    success() { return this; }, fail() { return this; }, networkFail(cb) { failure = cb; return this; }, send() {} };
  const api = loadModule('src/apis/module/device.js', {
    '../api': { getServiceUrl: () => '' },
    '../httpRequest': { __esModule: true, default: { sendRequest: () => request } }
  }).default;
  for (const [name, args] of [
    ['getDeviceScenes', ['d']], ['createDeviceScene', ['d', {}]],
    ['updateDeviceScene', ['d', 'b', {}]], ['deleteDeviceScene', ['d', 'b']],
    ['switchDeviceScene', ['d', 'b']]
  ]) {
    for (const [status, expected] of [[401, /重新登录/], [403, /权限/], [404, /接口不存在/],
      [500, /内部错误/], [502, /网关/], [504, /超时/], [0, /未收到有效响应/], [1, /请求超时/]]) {
      let received;
      api[name](...args, res => { received = res.data; });
      failure({ status, data: '<html>sensitive-proxy-body</html>', message: 'sensitive-exception' });
      assert.equal(received.code, -1);
      assert.match(received.msg, expected);
      if (status >= 100) assert.match(received.msg, new RegExp(`HTTP ${status}`));
      assert.doesNotMatch(received.msg, /sensitive|html/);
    }
  }
});
