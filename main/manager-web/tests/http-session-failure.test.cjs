const assert = require('node:assert/strict');
const { test } = require('node:test');
const { loadModule } = require('./helpers/loadVueComponent.cjs');

function storage() {
  const values = new Map();
  return { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
}

function harness(t, { audience = 'USER', response, rejection } = {}) {
  const previous = { localStorage: global.localStorage, sessionStorage: global.sessionStorage, document: global.document, setTimeout: global.setTimeout };
  global.localStorage = storage();
  global.sessionStorage = storage();
  global.document = { documentElement: { dataset: { console: audience } } };
  const state = { requests: [], retries: [], redirects: [], warnings: [], errors: [], clears: 0 };
  global.setTimeout = callback => { state.retries.push(callback); };
  t.after(() => { for (const [name, value] of Object.entries(previous)) { if (value === undefined) delete global[name]; else global[name] = value; } });
  localStorage.setItem('token', JSON.stringify({ token: 'test-only-user-session' }));
  sessionStorage.setItem('xiaozhi:ops:token', JSON.stringify({ token: 'test-only-ops-session' }));
  const session = loadModule('src/security/session.js');
  const store = {
    getters: { get getToken() { return session.readSession('token'); } },
    commit(name) { assert.equal(name, 'clearAuth'); state.clears++; session.removeSession('token'); session.removeSession('userInfo'); },
  };
  class FlyMock {
    constructor() { this.config = {}; }
    request(url, data, options) {
      state.requests.push({ url, data, options });
      return rejection ? Promise.reject(rejection) : Promise.resolve(response);
    }
  }
  const requestService = loadModule('src/apis/httpRequest.js', {
    'flyio/dist/npm/fly': { __esModule: true, default: FlyMock },
    '../store/index': { __esModule: true, default: store },
    '../utils/constant': { __esModule: true, default: { PAGE: { LOGIN: '/login' } } },
    '../utils/index': {
      isNotNull: value => value != null,
      goToPage: (path, replace) => state.redirects.push({ path, replace, audience: session.consoleAudience() }),
      showDanger: message => state.errors.push(message), showWarning: message => state.warnings.push(message),
    },
  }).default;
  return { state, requestService };
}

const settled = () => new Promise(resolve => setImmediate(resolve));

test('expired user-info returns to user login without leaving a network retry on the login page', async t => {
  const { state, requestService } = harness(t, { response: { status: 200, data: { code: 401 } } });
  const user = loadModule('src/apis/module/user.js', {
    '../api': { getServiceUrl: () => '/xiaozhi' },
    '../httpRequest': { __esModule: true, default: requestService },
  }).default;
  user.getUserInfo(() => assert.fail('expired session cannot return user information'));
  await settled();
  assert.equal(state.requests.length, 1);
  assert.equal(state.clears, 1);
  assert.equal(localStorage.getItem('token'), null);
  assert.notEqual(sessionStorage.getItem('xiaozhi:ops:token'), null);
  assert.deepEqual(state.redirects, [{ path: '/login', replace: true, audience: 'USER' }]);
  assert.deepEqual(state.retries, []);
  assert.deepEqual(state.warnings, []);
});

test('operations authentication failure notifies failure and clears only the operations session', async t => {
  const { state, requestService } = harness(t, { audience: 'OPS', response: { status: 200, data: { code: 401 } } });
  const failures = [], networkFailures = [];
  requestService.sendRequest().url('/xiaozhi/ops/auth/me').fail(info => failures.push(info)).networkFail(info => networkFailures.push(info)).send();
  await settled();
  assert.equal(failures.length, 1);
  assert.equal(failures[0].data.code, 401);
  assert.deepEqual(networkFailures, []);
  assert.equal(sessionStorage.getItem('xiaozhi:ops:token'), null);
  assert.notEqual(localStorage.getItem('token'), null);
  assert.deepEqual(state.redirects, [{ path: '/login', replace: true, audience: 'OPS' }]);
});

test('HTTP 401 rejection also avoids automatic network retries', async t => {
  const { state, requestService } = harness(t, { rejection: { status: 401, data: { code: 401 } } });
  let networkFailures = 0;
  requestService.sendRequest().url('/xiaozhi/user/info').networkFail(() => networkFailures++).send();
  await settled();
  assert.equal(networkFailures, 0);
  assert.equal(state.clears, 1);
  assert.equal(state.redirects.length, 1);
});

test('genuine transport failure still reaches the network callback without request credentials', async t => {
  const { state, requestService } = harness(t, { rejection: { status: 0, request: { headers: { Authorization: 'test-only-sensitive-header' } } } });
  const failures = [];
  requestService.sendRequest().url('/xiaozhi/user/pub-config').networkFail(info => failures.push(info)).send();
  await settled();
  assert.deepEqual(failures, [{ status: 0, data: undefined }]);
  assert.equal(state.clears, 0);
  assert.deepEqual(state.redirects, []);
});

test('successful response callback errors do not masquerade as network failures or replay a request', async t => {
  const { state, requestService } = harness(t, { response: { status: 200, data: { code: 0, data: {} } } });
  let networkFailures = 0;
  requestService.sendRequest().url('/xiaozhi/user/pub-config').success(() => { throw new Error('test-only callback failure'); }).networkFail(() => networkFailures++).send();
  await settled();
  assert.equal(networkFailures, 0);
  assert.equal(state.requests.length, 1);
  assert.deepEqual(state.errors, ['页面处理响应失败，请刷新页面后重试']);
  assert.deepEqual(state.retries, []);
});
