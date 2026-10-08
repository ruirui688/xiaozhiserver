const assert = require('node:assert/strict');
const { test } = require('node:test');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');
function drawer(deviceApi) {
  const component = loadVueComponent('src/components/device/DeviceSettingsDrawer.vue', {
    '@/apis/api': { __esModule: true, default: { device: deviceApi } },
    '@/apis/httpRequest': { __esModule: true, default: {} }
  });
  const events = [];
  return { ...component.data(), ...component.methods, canWrite: true,
    $set: (obj, key, value) => { obj[key] = value; }, $emit: (...args) => events.push(args), events };
}
test('settings update only the opened device after server acceptance', async () => {
  let finish; const vm = drawer({ updateDeviceInfo(id, data, cb) {
    assert.equal(id, 'd'); assert.deepEqual(data, { autoUpdate: 1 }); finish = cb;
  } });
  const device = { id: 'd', autoUpdate: 0 }; vm.open(device);
  const pending = vm.updateOta(true); assert.equal(device.autoUpdate, 0);
  let closed = false; vm.close(() => { closed = true; }); assert.equal(closed, false);
  finish({ data: { code: 0 } }); await pending; assert.equal(device.autoUpdate, 1); assert.equal(vm.saving, false);
});
test('failed update preserves prior setting and read-only users cannot submit', async () => {
  let calls = 0; const vm = drawer({ updateDeviceInfo(id, data, cb) { calls++; cb({ data: { code: 403, msg: '无权限' } }); } });
  vm.open({ id: 'd', autoUpdate: 0 }); await vm.updateOta(true);
  assert.equal(vm.device.autoUpdate, 0); assert.equal(vm.error, '无权限');
  vm.canWrite = false; await vm.updateOta(true); assert.equal(calls, 1);
});
test('unbind cancellation never sends request and successful unbind refreshes center', async () => {
  let calls = 0; const vm = drawer({ unbindDevice(id, cb) { calls++; assert.equal(id, 'd'); cb({ data: { code: 0 } }); } });
  vm.open({ id: 'd', alias: '大眼睛' }); vm.$confirm = () => Promise.reject('cancel'); await vm.unbind();
  assert.equal(calls, 0); assert.equal(vm.visible, true);
  vm.$confirm = () => Promise.resolve(); await vm.unbind();
  assert.equal(calls, 1); assert.equal(vm.visible, false); assert.deepEqual(vm.events, [['refresh']]);
});
