const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue');
const compiler = require('vue-template-compiler');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');

const PASSWORD = 'TestOnlyPassword123';
const policy = (extra = {}) => ({ available: false, message: '员工账号由部署维护人员管理，请联系维护人员', captchaRequired: false, mfaRequired: false, ...extra });
const settled = () => new Promise(resolve => setImmediate(resolve));
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }
function nodes(vnode) { return [vnode, ...(vnode.children || []).flatMap(nodes)]; }
function labels(vm) { return nodes(vm._render()).filter(node => node.tag === 'el-form-item').map(node => node.data.attrs.label); }
function submit(vm) { return nodes(vm._render()).find(node => node.tag === 'el-button' && node.data.attrs['native-type'] === 'submit'); }

function create(t, { initialPolicy = policy(), login, profile = { audience: 'OPS', username: 'test-staff' } } = {}) {
  const calls = { policy: 0, captcha: [], encryption: [], login: [], me: 0, commits: [], routes: [], dispatch: [] };
  let currentPolicy = initialPolicy;
  let currentLogin = login || (async () => ({ token: 'test-only-token' }));
  const component = loadVueComponent('src/ops/OpsLogin.vue', {
    '@/apis/platform': { operations: {
      identityPolicy: async () => { calls.policy++; if (currentPolicy instanceof Error) throw currentPolicy; return currentPolicy; },
      login: async payload => { calls.login.push(payload); return currentLogin(payload); },
      me: async () => { calls.me++; return profile; },
    } },
    '@/apis/api': { __esModule: true, default: { user: { getCaptcha(id, callback) { calls.captcha.push({ id, callback }); } } } },
    '@/utils': {
      getUUID: () => `test-captcha-${calls.captcha.length + 1}`,
      sm2Encrypt: (key, plaintext) => { calls.encryption.push({ key, plaintext }); return 'test-only-ciphertext'; },
    },
  });
  const template = compiler.parseComponent(fs.readFileSync(path.resolve(__dirname, '../src/ops/OpsLogin.vue'), 'utf8')).template.content;
  const compiled = compiler.compileToFunctions(template);
  const vm = new Vue({
    ...component,
    render: compiled.render,
    staticRenderFns: compiled.staticRenderFns,
    beforeCreate() {
      this.$store = {
        state: { pubConfig: { sm2PublicKey: 'test-only-public-key' } },
        dispatch: async name => { calls.dispatch.push(name); },
        commit: (name, value) => { calls.commits.push({ name, value }); },
      };
      this.$router = { replace: async route => { calls.routes.push(route); } };
    },
  });
  Object.assign(vm.form, { username: ' test-staff ', password: PASSWORD });
  t.after(() => vm.$destroy());
  return { vm, calls, setPolicy(value) { currentPolicy = value; }, setLogin(value) { currentLogin = value; } };
}

test('managed employees log in with username and password when additional challenges are disabled', async t => {
  const { vm, calls } = create(t);
  await settled();
  assert.equal(vm.identityPolicyAvailable, false);
  assert.equal(vm.loginPolicyReady, true);
  assert.deepEqual(labels(vm), ['员工账号', '密码']);
  assert.equal(submit(vm).data.attrs.disabled, false);
  assert.equal(calls.captcha.length, 0);
  Object.assign(vm.form, { captcha: 'STALE', otpCode: '123456' });
  await vm.login();
  assert.deepEqual(calls.login, [{ username: 'test-staff', password: 'test-only-ciphertext' }]);
  assert.deepEqual(calls.encryption, [{ key: 'test-only-public-key', plaintext: PASSWORD }]);
  assert.deepEqual(calls.commits.map(call => call.name), ['setToken', 'setUserInfo']);
  assert.deepEqual(calls.routes, ['/home']);
  assert.equal(vm.form.password, '');
  assert.equal(vm.form.otpCode, '');
});

test('CAPTCHA policy requires a challenge and includes it in the encrypted password', async t => {
  const { vm, calls } = create(t, { initialPolicy: policy({ captchaRequired: true }) });
  await settled();
  assert.deepEqual(labels(vm), ['员工账号', '密码', '图形验证码']);
  assert.equal(calls.captcha.length, 1);
  await vm.login();
  assert.equal(calls.login.length, 0);
  assert.match(vm.error, /图形验证码/);
  vm.form.captcha = 'ABCDE';
  await vm.login();
  assert.deepEqual(calls.login, [{ username: 'test-staff', password: 'test-only-ciphertext', captchaId: calls.captcha[0].id }]);
  assert.equal(calls.encryption[0].plaintext, `ABCDE${PASSWORD}`);
});

test('MFA-only policy validates six digits and preserves a leading zero', async t => {
  const { vm, calls } = create(t, { initialPolicy: policy({ mfaRequired: true }) });
  await settled();
  assert.deepEqual(labels(vm), ['员工账号', '密码', '动态验证码']);
  assert.equal(calls.captcha.length, 0);
  for (const otpCode of ['', '12345', '1234567', 'abcdef']) {
    vm.form.otpCode = otpCode;
    await vm.login();
    assert.equal(calls.login.length, 0);
    assert.match(vm.error, /6 位动态验证码/);
  }
  vm.form.otpCode = '012345';
  await vm.login();
  assert.deepEqual(calls.login, [{ username: 'test-staff', password: 'test-only-ciphertext', otpCode: '012345' }]);
  assert.equal(calls.encryption[0].plaintext, PASSWORD);
});

test('both enabled challenges preserve the strengthened login flow', async t => {
  const { vm, calls } = create(t, { initialPolicy: policy({ captchaRequired: true, mfaRequired: true }) });
  await settled();
  assert.deepEqual(labels(vm), ['员工账号', '密码', '图形验证码', '动态验证码']);
  Object.assign(vm.form, { captcha: 'ABCDE', otpCode: '012345' });
  await vm.login();
  assert.deepEqual(calls.login, [{ username: 'test-staff', password: 'test-only-ciphertext', captchaId: calls.captcha[0].id, otpCode: '012345' }]);
  assert.equal(calls.encryption[0].plaintext, `ABCDE${PASSWORD}`);
});

for (const [name, initialPolicy] of [
  ['unavailable', new Error('test-only network failure')],
  ['missing', { available: false }],
  ['string CAPTCHA', policy({ captchaRequired: 'false' })],
  ['string MFA', policy({ mfaRequired: 'false' })],
  ['malformed availability', policy({ available: 'false' })],
]) {
  test(`${name} policy disables login and supports explicit retry`, async t => {
    const { vm, calls, setPolicy } = create(t, { initialPolicy });
    await settled();
    assert.equal(vm.loginPolicyReady, false);
    assert.equal(submit(vm).data.attrs.disabled, true);
    await vm.login();
    assert.equal(calls.login.length, 0);
    assert.equal(calls.encryption.length, 0);
    assert.equal(calls.captcha.length, 0);
    setPolicy(policy());
    await vm.prepareLogin();
    assert.equal(vm.loginPolicyReady, true);
    assert.equal(submit(vm).data.attrs.disabled, false);
    await vm.login();
    assert.equal(calls.login.length, 1);
  });
}

test('submitting with Enter while policy is loading cannot initiate login', async t => {
  const pending = deferred();
  const { vm, calls } = create(t, { initialPolicy: pending.promise });
  const form = nodes(vm._render()).find(node => node.tag === 'el-form');
  let prevented = false;
  await form.data.nativeOn.submit({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(submit(vm).data.attrs.disabled, true);
  assert.equal(calls.login.length, 0);
  assert.match(vm.error, /正在读取/);
  pending.resolve(policy());
  await settled();
  assert.equal(vm.loginPolicyReady, true);
});

test('a rejected login refreshes policy before allowing a new attempt', async t => {
  const { vm, calls, setPolicy, setLogin } = create(t, { login: async () => { throw new Error('test-only rejected login'); } });
  await settled();
  setPolicy(policy({ captchaRequired: true, mfaRequired: true }));
  await vm.login();
  assert.equal(calls.policy, 2);
  assert.equal(calls.captcha.length, 1);
  assert.equal(calls.commits.at(-1).name, 'clearAuth');
  assert.deepEqual(calls.routes, []);
  setLogin(async () => ({ token: 'test-only-token' }));
  await vm.login();
  assert.equal(calls.login.length, 1);
  Object.assign(vm.form, { captcha: 'ABCDE', otpCode: '012345' });
  await vm.login();
  assert.equal(calls.login.length, 2);
  assert.equal(calls.login[1].otpCode, '012345');
  assert.deepEqual(calls.routes, ['/home']);
});

test('pending authentication prevents duplicate submissions and rejects a non-OPS profile', async t => {
  const pending = deferred();
  const { vm, calls } = create(t, { login: () => pending.promise, profile: { audience: 'USER' } });
  await settled();
  const first = vm.login();
  assert.equal(vm.busy, true);
  assert.equal(submit(vm).data.attrs.disabled, true);
  await vm.login();
  assert.equal(calls.login.length, 1);
  pending.resolve({ token: 'test-only-token' });
  await first;
  assert.equal(vm.busy, false);
  assert.match(vm.error, /员工身份不匹配/);
  assert.deepEqual(calls.commits.map(call => call.name), ['setToken', 'clearAuth']);
  assert.deepEqual(calls.routes, []);
});

test('missing encryption configuration never submits a password and requests configuration again', async t => {
  const { vm, calls } = create(t);
  await settled();
  vm.$store.state.pubConfig = {};
  await vm.login();
  assert.equal(calls.login.length, 0);
  assert.equal(calls.encryption.length, 0);
  assert.deepEqual(calls.dispatch, ['fetchPubConfig', 'fetchPubConfig']);
  assert.match(vm.error, /登录配置尚未就绪/);
});

test('late policy and CAPTCHA responses do not create images after leaving the login page', async t => {
  const pending = deferred();
  const waiting = create(t, { initialPolicy: pending.promise });
  waiting.vm.$destroy();
  pending.resolve(policy({ captchaRequired: true }));
  await settled();
  assert.equal(waiting.calls.captcha.length, 0);
  const ready = create(t, { initialPolicy: policy({ captchaRequired: true }) });
  await settled();
  ready.vm.captcha();
  ready.calls.captcha[0].callback({ data: null });
  assert.equal(ready.vm.captchaUrl, '');
  ready.vm.$destroy();
  ready.calls.captcha[1].callback({ data: null });
  assert.equal(ready.vm.captchaUrl, '');
  assert.equal(ready.vm.form.password, '');
});
