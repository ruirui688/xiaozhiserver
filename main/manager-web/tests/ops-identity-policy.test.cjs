const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue');
const compiler = require('vue-template-compiler');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');

function create(t, file, { policy = { available: false, message: '员工账号由部署维护人员管理，请联系维护人员' }, route = '/enroll' } = {}) {
  const calls = { policy: 0, captcha: 0, secret: 0, writes: 0 };
  let currentPolicy = policy;
  const component = loadVueComponent(file, {
    '@/apis/platform': {
      operations: { identityPolicy: async () => { calls.policy++; if (currentPolicy instanceof Error) throw currentPolicy; return currentPolicy; } },
      platformRequest: async () => { calls.writes++; return { id: 'test-request', recoveryCode: 'test-only-code' }; },
    },
    '@/apis/api': { __esModule: true, default: { user: { getCaptcha() { calls.captcha++; } } } },
    '@/utils': { getUUID: () => 'test-captcha', sm2Encrypt: () => 'test-only-ciphertext' },
    '@/security/totp': { generateTotpSecret() { calls.secret++; return 'test-only-seed'; } },
  });
  const template = compiler.parseComponent(fs.readFileSync(path.resolve(__dirname, '..', file), 'utf8')).template.content;
  const compiled = compiler.compileToFunctions(template);
  const vm = new Vue({
    ...component,
    render: compiled.render, staticRenderFns: compiled.staticRenderFns,
    beforeCreate() { this.$route = { path: route }; this.$store = { dispatch: async () => {}, state: { pubConfig: { sm2PublicKey: 'test-only-public-key' }, userInfo: { username: 'test-employee' } } }; },
  });
  t.after(() => vm.$destroy());
  return { vm, calls, setPolicy(value) { currentPolicy = value; } };
}
const settled = () => new Promise(resolve => setImmediate(resolve));
function nodes(vnode) { return [vnode, ...(vnode.children || []).flatMap(nodes)]; }
function formVisible(vm) { return nodes(vm._render()).some(node => node.tag === 'el-form'); }
function identityLinks(vm) { return nodes(vm._render()).filter(node => node.tag === 'router-link').map(node => node.data.attrs.to); }

test('login hides unavailable identity entry points without blocking employee login', async t => {
  const { vm } = create(t, 'src/ops/OpsLogin.vue');
  await settled();
  assert.equal(vm.identityPolicyAvailable, false);
  assert.deepEqual(identityLinks(vm), []);
  assert.equal(formVisible(vm), true);
  assert.match(vm.identityPolicyNotice, /部署维护人员/);
});

for (const route of ['/enroll', '/recover']) {
  test(`direct ${route} cannot prepare or submit a form without identity reviewers`, async t => {
    const { vm, calls } = create(t, 'src/ops/OpsEnrollment.vue', { route });
    await settled();
    assert.equal(formVisible(vm), false);
    assert.equal(vm.secret, '');
    assert.equal(calls.captcha, 0);
    assert.equal(calls.secret, 0);
    await vm.submit();
    assert.equal(calls.writes, 0);
    assert.match(vm.error, /部署维护人员/);
  });
}

test('a failed or malformed policy response fails closed and can be retried', async t => {
  const { vm, calls, setPolicy } = create(t, 'src/ops/OpsEnrollment.vue', { policy: new Error('test-only network failure') });
  await settled();
  assert.equal(formVisible(vm), false);
  assert.match(vm.identityPolicyNotice, /请重试/);
  assert.equal(calls.secret, 0);
  setPolicy({ available: 'true' });
  await vm.prepareIdentityForm();
  assert.equal(vm.identityPolicyAvailable, false);
  setPolicy({ available: true, message: '' });
  await vm.prepareIdentityForm();
  assert.equal(formVisible(vm), true);
  assert.equal(calls.secret, 1);
  assert.equal(calls.captcha, 1);
});

test('configured identity governance preserves login links and recovery application', async t => {
  const login = create(t, 'src/ops/OpsLogin.vue', { policy: { available: true, message: '' } });
  const recovery = create(t, 'src/ops/OpsEnrollment.vue', { route: '/recover', policy: { available: true, message: '' } });
  await settled();
  assert.deepEqual(identityLinks(login.vm), ['/enroll', '/recover']);
  Object.assign(recovery.vm.form, { username: 'test-staff', password: 'TestOnlyPassword123', captcha: 'ABCDE', otpCode: '012345', recoveryCode: 'test-only-old-recovery' });
  await recovery.vm.submit();
  assert.equal(recovery.calls.writes, 1);
  assert.equal(recovery.vm.result.id, 'test-request');
  assert.equal(recovery.vm.secret, '');
});

test('home recovery-code generation is unavailable on disabled or failed policy checks', async t => {
  const { vm, calls, setPolicy } = create(t, 'src/ops/OpsRecoveryCode.vue');
  await settled();
  vm.open();
  await vm.submit();
  assert.equal(vm.visible, false);
  assert.equal(calls.captcha, 0);
  assert.equal(calls.writes, 0);
  assert.equal(nodes(vm._render()).some(node => node.tag === 'el-dialog'), false);
  setPolicy(new Error('test-only network failure'));
  await vm.loadIdentityPolicy();
  vm.open();
  assert.equal(vm.visible, false);
  setPolicy({ available: true, message: '' });
  await vm.loadIdentityPolicy();
  vm.open();
  assert.equal(vm.visible, true);
  assert.equal(calls.captcha, 1);
});

test('optional login challenges do not disable enrollment or recovery verification', async t => {
  const policy = { available: true, message: '', captchaRequired: false, mfaRequired: false };
  for (const route of ['/enroll', '/recover']) {
    const { vm, calls } = create(t, 'src/ops/OpsEnrollment.vue', { policy, route });
    await settled();
    assert.equal(calls.captcha, 1);
    Object.assign(vm.form, { username: 'test-staff', password: 'TestOnlyPassword123', recoveryCode: 'test-only-old-recovery' });
    for (const missing of [{ captcha: '', otpCode: '012345' }, { captcha: 'ABCDE', otpCode: '' }]) {
      Object.assign(vm.form, missing);
      await vm.submit();
      assert.equal(calls.writes, 0);
      assert.match(vm.error, /填写验证信息/);
    }
  }
  const { vm, calls } = create(t, 'src/ops/OpsRecoveryCode.vue', { policy });
  await settled();
  vm.open();
  assert.equal(calls.captcha, 1);
  vm.password = 'TestOnlyPassword123';
  for (const missing of [{ captchaValue: '', otpCode: '012345' }, { captchaValue: 'ABCDE', otpCode: '' }]) {
    Object.assign(vm, missing);
    await vm.submit();
    assert.equal(calls.writes, 0);
    assert.match(vm.error, /填写密码和验证码/);
  }
});
