const assert = require('node:assert/strict');
const { test } = require('node:test');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');
function dialog(update) {
  const component = loadVueComponent('src/components/device/DeviceAliasDialog.vue', {
    '@/apis/api': { __esModule: true, default: { device: { updateDeviceInfo: update } }, getServiceUrl: () => '' },
    '@/apis/httpRequest': { __esModule: true, default: {} }
  });
  const events = [];
  return { ...component.data(), ...component.methods, canWrite: true, events,
    $emit: (...args) => events.push(args), $message: { success() {} } };
}
test('alias save sends only alias and updates UI after server acceptance', async () => {
  let finish;
  const vm = dialog((id, body, cb) => {
    assert.equal(id, 'd'); assert.deepEqual(body, { alias: '客厅小智' }); finish = cb;
  });
  vm.open({ id: 'd', alias: '旧名' }); vm.alias = ' 客厅小智 ';
  const pending = vm.save(); assert.equal(vm.events.length, 0); assert.equal(vm.saving, true);
  finish({ data: { code: 0 } }); await pending;
  assert.deepEqual(vm.events, [['saved', { id: 'd', alias: '客厅小智' }]]);
  assert.equal(vm.visible, false);
});
test('clearing alias submits empty string to restore default name', async () => {
  const vm = dialog((id, body, cb) => { assert.deepEqual(body, { alias: '' }); cb({ data: { code: 0 } }); });
  vm.open({ id: 'd', alias: '旧名' }); vm.alias = ' '; await vm.save();
  assert.equal(vm.events[0][1].alias, '');
});
test('failed save keeps draft and dialog open without publishing a changed name', async () => {
  const vm = dialog((id, body, cb) => cb({ data: { code: 403, msg: '无写权限' } }));
  vm.open({ id: 'd' }); vm.alias = '书房助手'; await vm.save();
  assert.equal(vm.error, '无写权限'); assert.equal(vm.alias, '书房助手');
  assert.equal(vm.visible, true); assert.equal(vm.saving, false); assert.equal(vm.events.length, 0);
});
test('read-only users and overlong aliases cannot submit', async () => {
  const vm = dialog(() => assert.fail('unexpected request'));
  vm.canWrite = false; vm.open({ id: 'd' }); assert.equal(vm.visible, false); await vm.save();
  vm.canWrite = true; vm.open({ id: 'd' }); vm.alias = 'a'.repeat(65); await vm.save();
  assert.match(vm.error, /64/);
});
test('duplicate save is ignored while first request is pending', async () => {
  let finish, calls = 0;
  const vm = dialog((id, body, cb) => { calls++; finish = cb; });
  vm.open({ id: 'd' }); vm.alias = '小伙伴'; const pending = vm.save(); await vm.save();
  assert.equal(calls, 1); finish({ data: { code: 0 } }); await pending;
});
