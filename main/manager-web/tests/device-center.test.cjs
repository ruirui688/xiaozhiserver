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
