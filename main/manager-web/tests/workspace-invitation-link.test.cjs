const assert = require('node:assert/strict');
const { test } = require('node:test');
const Vue = require('vue/dist/vue.common.js');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');

const INVITE_CODE = 'InviteToken_0123456789';

function memoryStorage() {
  const values = new Map();
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, String(value)),
    removeItem: key => values.delete(key),
  };
}

function loadInvitationUtils() {
  return loadModule('src/utils/workspaceInvitation.js');
}

test('generated invitation links keep the one-time token in the hash fragment only', () => {
  const { buildWorkspaceInviteLink } = loadInvitationUtils();
  const link = buildWorkspaceInviteLink(INVITE_CODE, {
    origin: 'https://console.example.test',
    pathname: '/console/index.html',
  });

  const parsed = new URL(link);
  assert.equal(parsed.origin, 'https://console.example.test');
  assert.equal(parsed.pathname, '/console/index.html');
  assert.equal(parsed.search, '');
  assert.equal(parsed.hash, `#/invite?code=${INVITE_CODE}`);
  assert.equal(link.slice(0, link.indexOf('#')).includes(INVITE_CODE), false);
});

test('post-auth redirects accept local paths and reject external or encoded traversal targets', () => {
  const { safeInternalRedirect } = loadInvitationUtils();
  assert.equal(safeInternalRedirect(`/invite?code=${INVITE_CODE}`), `/invite?code=${INVITE_CODE}`);
  assert.equal(safeInternalRedirect('/organization-management?tab=members'), '/organization-management?tab=members');

  for (const unsafe of [
    'https://attacker.example/invite',
    '//attacker.example/invite',
    '/\\attacker.example/invite',
    '/invite#secret',
    '/invite%23secret',
    '/%2e%2e/admin',
    '/%252e%252e/admin',
    '/%25252e%25252e/admin',
    '/%2525252525252525252e%2525252525252525252e/admin',
    '/%255c%255cattacker.example/invite',
    '/invite\nnext',
    ' /invite',
  ]) {
    assert.equal(safeInternalRedirect(unsafe, ''), '', unsafe);
  }
});

test('pending invitation storage safely restores an auth redirect and expires stale context', () => {
  const {
    PENDING_WORKSPACE_INVITE_KEY,
    readPendingWorkspaceInvite,
    rememberPendingWorkspaceInvite,
    resolvePostAuthRedirect,
  } = loadInvitationUtils();
  const storage = memoryStorage();

  assert.equal(rememberPendingWorkspaceInvite(INVITE_CODE, storage), INVITE_CODE);
  const saved = JSON.parse(storage.getItem(PENDING_WORKSPACE_INVITE_KEY));
  assert.equal(resolvePostAuthRedirect('https://attacker.example', storage), '/invite');
  assert.equal(readPendingWorkspaceInvite(storage, saved.savedAt + (24 * 60 * 60 * 1000)), INVITE_CODE);
  assert.equal(readPendingWorkspaceInvite(storage, saved.savedAt + (24 * 60 * 60 * 1000) + 1), '');
  assert.equal(storage.getItem(PENDING_WORKSPACE_INVITE_KEY), null);
});

test('workspace invitation is a public route while protected routes still require login', () => {
  let routerOptions;
  let guard;
  class RouterMock {
    constructor(options) { routerOptions = options; }
    beforeEach(callback) { guard = callback; }
  }
  loadModule('src/router/index.js', {
    vue: { use: () => {} },
    'vue-router': { __esModule: true, default: RouterMock },
    '../security/session': { readSession: () => null },
  });

  const inviteRoute = routerOptions.routes.find(route => route.path === '/invite');
  assert.equal(inviteRoute.name, 'WorkspaceInvitation');
  const inviteTransitions = [];
  guard({ name: inviteRoute.name, fullPath: `/invite?code=${INVITE_CODE}` }, {}, value => inviteTransitions.push(value));
  assert.deepEqual(inviteTransitions, [undefined]);

  const protectedTransitions = [];
  guard({ name: 'OrganizationManagement', fullPath: '/organization-management' }, {}, value => protectedTransitions.push(value));
  assert.deepEqual(protectedTransitions, [{
    name: 'login',
    query: { redirect: '/organization-management' },
  }]);
});

function setupBrowserStorage(t) {
  const previous = {
    localStorage: global.localStorage,
    sessionStorage: global.sessionStorage,
    document: global.document,
  };
  global.localStorage = memoryStorage();
  global.sessionStorage = memoryStorage();
  delete global.document;
  t.after(() => {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete global[key];
      else global[key] = value;
    }
  });
}

function createInvitationLanding(t, { authenticated, preview, accept, logout }) {
  const calls = { preview: [], accept: [], replace: [], pendingAtReplace: [], push: [], messages: [] };
  const api = {
    previewInvitation(code, success, failure) {
      calls.preview.push(code);
      if (preview instanceof Error) failure({ data: { msg: preview.message } });
      else success({ data: { code: 0, data: preview } });
    },
    acceptInvitation(code, success, failure) {
      calls.accept.push(code);
      if (accept instanceof Error) failure({ data: { msg: accept.message } });
      else success({ data: { code: 0, data: accept } });
    },
  };
  const component = loadVueComponent('src/views/WorkspaceInvitationLanding.vue', {
    '@/apis/api': { __esModule: true, default: { workspace: api } },
    '@/security/session': {
      readSession: key => key === 'token' && authenticated ? 'test-only-token' : null,
      parseStoredProfile: () => ({ username: 'invited-user' }),
    },
  });
  const Ctor = Vue.extend({
    ...component,
    render(createElement) { return createElement('div'); },
    beforeCreate() {
      this.$route = { path: '/invite', query: { code: INVITE_CODE } };
      this.$router = {
        replace: value => {
          calls.replace.push(value);
          calls.pendingAtReplace.push(sessionStorage.getItem('xiaozhi:pendingWorkspaceInvite'));
          return Promise.resolve();
        },
        push: value => { calls.push.push(value); return Promise.resolve(); },
      };
      this.$store = {
        state: { pubConfig: { allowUserRegister: true }, userInfo: { username: 'invited-user' } },
        dispatch: action => action === 'logout' && logout ? logout() : Promise.resolve(),
      };
      this.$message = {
        success: message => calls.messages.push(message),
        error: message => calls.messages.push(message),
      };
    },
  });
  const vm = new Ctor();
  t.after(() => vm.$destroy());
  return { vm, calls };
}

test('landing stores and scrubs the token before auth without previewing or accepting', t => {
  setupBrowserStorage(t);
  const { PENDING_WORKSPACE_INVITE_KEY } = loadInvitationUtils();
  const { vm, calls } = createInvitationLanding(t, {
    authenticated: false,
    preview: { workspaceName: '研发空间', role: 'MEMBER' },
    accept: { id: 'workspace-2', canWrite: false },
  });

  assert.equal(vm.inviteCode, INVITE_CODE);
  assert.equal(JSON.parse(sessionStorage.getItem(PENDING_WORKSPACE_INVITE_KEY)).code, INVITE_CODE);
  assert.deepEqual(calls.replace, [{ path: '/invite' }]);
  assert.equal(JSON.parse(calls.pendingAtReplace[0]).code, INVITE_CODE);
  assert.deepEqual(calls.preview, []);
  assert.deepEqual(calls.accept, []);

  vm.goToAuth('/login');
  assert.deepEqual(calls.push, [{ path: '/login', query: { redirect: '/invite' } }]);
});

test('authenticated landing previews only, then explicit acceptance switches workspace and clears context', t => {
  setupBrowserStorage(t);
  const { PENDING_WORKSPACE_INVITE_KEY } = loadInvitationUtils();
  const { vm, calls } = createInvitationLanding(t, {
    authenticated: true,
    preview: {
      workspaceName: '研发空间',
      role: 'ADMIN',
      expiresAt: '2026-10-14T12:00:00Z',
      linkInvitation: true,
    },
    accept: { id: 'workspace-2', canWrite: true },
  });

  assert.deepEqual(calls.preview, [INVITE_CODE]);
  assert.deepEqual(calls.accept, []);
  assert.equal(vm.invitation.workspaceName, '研发空间');
  assert.equal(localStorage.getItem('currentWorkspaceId'), null);

  vm.acceptInvitation();
  assert.deepEqual(calls.accept, [INVITE_CODE]);
  assert.equal(localStorage.getItem('currentWorkspaceId'), 'workspace-2');
  assert.equal(localStorage.getItem('currentWorkspaceCanWrite'), 'true');
  assert.equal(sessionStorage.getItem(PENDING_WORKSPACE_INVITE_KEY), null);
  assert.equal(vm.inviteCode, '');
  assert.deepEqual(calls.replace, [{ path: '/invite' }, '/organization-management']);
  assert.equal(JSON.parse(calls.pendingAtReplace[0]).code, INVITE_CODE);
  assert.equal(calls.pendingAtReplace[1], null);
  assert.deepEqual(calls.messages, ['已加入工作空间']);
});

test('switching accounts synchronously blocks the old session from consuming the invitation', t => {
  setupBrowserStorage(t);
  let finishLogout;
  const pendingLogout = new Promise(resolve => { finishLogout = resolve; });
  const { vm, calls } = createInvitationLanding(t, {
    authenticated: true,
    preview: { workspaceName: '研发空间', role: 'MEMBER' },
    accept: { id: 'workspace-2', canWrite: false },
    logout: () => pendingLogout,
  });

  vm.switchAccount();
  assert.equal(vm.switchingAccount, true);
  vm.acceptInvitation();
  assert.deepEqual(calls.accept, []);

  finishLogout();
});

function requestRecorder() {
  const requests = [];
  return {
    requests,
    service: {
      sendRequest() {
        const record = { withoutWorkspace: false };
        const request = {
          url(value) { record.url = value; return request; },
          method(value) { record.method = value; return request; },
          data(value) { record.data = value; return request; },
          withoutWorkspace() { record.withoutWorkspace = true; return request; },
          success(value) { record.success = value; return request; },
          fail(value) { record.fail = value; return request; },
          networkFail(value) { record.networkFail = value; return request; },
          send() { requests.push(record); return request; },
        };
        return request;
      },
    },
  };
}

test('invitation preview and acceptance send the token in POST bodies without stale workspace context', () => {
  const recorder = requestRecorder();
  const workspace = loadModule('src/apis/module/workspace.js', {
    '../api': { getServiceUrl: () => '/xiaozhi' },
    '../httpRequest': { __esModule: true, default: recorder.service },
  }).default;
  const callback = () => {};

  workspace.previewInvitation(INVITE_CODE, callback, callback);
  workspace.acceptInvitation(INVITE_CODE, callback, callback);

  assert.deepEqual(recorder.requests.map(({ url, method, data, withoutWorkspace }) => ({
    url, method, data, withoutWorkspace,
  })), [
    {
      url: '/xiaozhi/workspace/invitations/preview',
      method: 'POST',
      data: { code: INVITE_CODE },
      withoutWorkspace: true,
    },
    {
      url: '/xiaozhi/workspace/invitations/accept',
      method: 'POST',
      data: { code: INVITE_CODE },
      withoutWorkspace: true,
    },
  ]);
  assert.equal(recorder.requests.some(request => request.url.includes(INVITE_CODE)), false);
});

test('preview and acceptance preserve an optional session but omit stale workspace headers', async t => {
  const previousStorage = global.localStorage;
  global.localStorage = memoryStorage();
  global.localStorage.setItem('currentWorkspaceId', 'workspace-before-accept');
  t.after(() => {
    if (previousStorage === undefined) delete global.localStorage;
    else global.localStorage = previousStorage;
  });
  const requests = [];
  class FlyMock {
    constructor() { this.config = {}; }
    request(url, data, options) {
      requests.push({ url, data, options });
      return Promise.resolve({ status: 200, data: { code: 0 } });
    }
  }
  const requestService = loadModule('src/apis/httpRequest.js', {
    'flyio/dist/npm/fly': { __esModule: true, default: FlyMock },
    '../security/session': { isOperationsConsole: () => false },
    '../store/index': { __esModule: true, default: {
      getters: { getToken: JSON.stringify({ token: 'test-only-user-token' }) },
      commit: () => {},
    } },
    '../utils/constant': { __esModule: true, default: { PAGE: { LOGIN: '/login' } } },
    '../utils/index': {
      isNotNull: value => value != null,
      goToPage: () => {},
      showDanger: () => {},
      showWarning: () => {},
    },
  }).default;

  requestService.sendRequest().url('/preview').method('POST').withoutWorkspace().send();
  requestService.sendRequest().url('/accept').method('POST').withoutWorkspace().send();
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(requests.length, 2);
  assert.equal(requests[0].options.headers.Authorization, 'Bearer test-only-user-token');
  assert.equal(requests[0].options.headers['X-Workspace-Id'], undefined);
  assert.equal(requests[1].options.headers.Authorization, 'Bearer test-only-user-token');
  assert.equal(requests[1].options.headers['X-Workspace-Id'], undefined);
});

test('invite dialog offers ADMIN and MEMBER links and reuses a link after copy failure', async t => {
  const previousWindow = global.window;
  global.window = {
    location: {
      origin: 'https://console.example.test',
      pathname: '/index.html',
    },
  };
  t.after(() => {
    if (previousWindow === undefined) delete global.window;
    else global.window = previousWindow;
  });
  const inviteCalls = [];
  const component = loadVueComponent('src/components/workspace/WorkspaceInviteDialog.vue', {
    '@/apis/api': { __esModule: true, default: { workspace: {
      invite(workspaceId, data, success) {
        inviteCalls.push({ workspaceId, data });
        success({ data: { code: 0, data: { code: INVITE_CODE } } });
      },
    } } },
  });
  const Ctor = Vue.extend({
    ...component,
    render(createElement) { return createElement('div'); },
  });
  const messages = { success: [], warning: [], error: [] };
  const vm = new Ctor({
    propsData: {
      visible: true,
      workspaceId: 'workspace-1',
      occupiedSlots: 1,
      maxMembers: 5,
    },
  });
  vm.$message = {
    success: message => messages.success.push(message),
    warning: message => messages.warning.push(message),
    error: message => messages.error.push(message),
  };
  vm.$nextTick = () => {};
  vm.writeClipboard = async () => { throw new Error('test-only clipboard denial'); };
  t.after(() => vm.$destroy());

  assert.deepEqual(vm.roleOptions.map(option => option.role), ['ADMIN', 'MEMBER']);
  assert.match(vm.roleOptions[0].description, /管理成员/);
  assert.match(vm.roleOptions[1].description, /查看并使用/);

  vm.createOrCopy('ADMIN');
  await new Promise(resolve => setImmediate(resolve));
  const fullLink = `https://console.example.test/index.html#/invite?code=${INVITE_CODE}`;
  assert.deepEqual(inviteCalls, [{ workspaceId: 'workspace-1', data: { role: 'ADMIN' } }]);
  assert.equal(vm.links.ADMIN, fullLink);
  assert.equal(vm.manualRole, 'ADMIN');
  assert.deepEqual(messages.warning, ['自动复制失败，请手动复制邀请链接']);

  vm.createOrCopy('ADMIN');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(inviteCalls.length, 1);
  assert.equal(vm.links.ADMIN, fullLink);
  assert.equal(vm.manualRole, 'ADMIN');
  assert.equal(messages.warning.length, 2);
});

test('mobile Web redirect is skipped for invite and invite-targeted authentication flows', t => {
  setupBrowserStorage(t);
  const previousH5Url = process.env.VUE_APP_H5_URL;
  const previousWindow = global.window;
  const previousConsoleInfo = console.info;
  process.env.VUE_APP_H5_URL = 'https://h5.example.test/';
  global.window = { location: { href: '' } };
  console.info = () => {};
  t.after(() => {
    if (previousH5Url === undefined) delete process.env.VUE_APP_H5_URL;
    else process.env.VUE_APP_H5_URL = previousH5Url;
    if (previousWindow === undefined) delete global.window;
    else global.window = previousWindow;
    console.info = previousConsoleInfo;
  });
  const component = loadVueComponent('src/App.vue', {
    '@/security/session': {
      parseStoredProfile: () => ({}),
      isOperationsConsole: () => false,
    },
    '@/utils/cacheViewer': { logCacheStatus: async () => false },
  });
  const invokeMounted = route => {
    window.location.href = 'https://web.example.test/index.html';
    component.mounted.call({
      $route: route,
      $t: key => key,
      isCDNEnabled: false,
      isMobileDevice: () => true,
    });
    return window.location.href;
  };

  assert.equal(invokeMounted({ path: '/invite', query: {} }), 'https://web.example.test/index.html');
  assert.equal(invokeMounted({ path: '/login', query: { redirect: '/invite' } }), 'https://web.example.test/index.html');
  assert.equal(invokeMounted({ path: '/register', query: { redirect: `/invite?code=${INVITE_CODE}` } }), 'https://web.example.test/index.html');

  loadInvitationUtils().rememberPendingWorkspaceInvite(INVITE_CODE, sessionStorage);
  assert.equal(invokeMounted({ path: '/login', query: {} }), 'https://web.example.test/index.html');
  loadInvitationUtils().clearPendingWorkspaceInvite(sessionStorage);
  assert.equal(invokeMounted({ path: '/home', query: {} }), 'https://h5.example.test/');
});

test('registration locks duplicate CAPTCHA submissions and preserves the pending invite redirect', t => {
  setupBrowserStorage(t);
  loadInvitationUtils().rememberPendingWorkspaceInvite(INVITE_CODE, sessionStorage);
  const pendingRequests = [];
  const routes = [];
  const notices = { success: [], danger: [] };
  const component = loadVueComponent('src/views/register.vue', {
    '@/apis/api': { __esModule: true, default: { user: {
      register(data, success, failure) { pendingRequests.push({ data, success, failure }); },
      getCaptcha() {},
      sendSmsVerification() {},
    } } },
    '@/utils': {
      getUUID: () => 'one-time-captcha',
      showDanger: message => notices.danger.push(message),
      showSuccess: message => notices.success.push(message),
      sm2Encrypt: (_key, value) => `encrypted:${value}`,
      validateMobile: () => true,
    },
  });
  const Ctor = Vue.extend({
    ...component,
    render(createElement) { return createElement('div'); },
    beforeCreate() {
      this.$route = { path: '/register', query: {} };
      this.$router = { push: value => routes.push(value) };
      this.$store = {
        state: {
          pubConfig: {
            allowUserRegister: true,
            enableMobileRegister: false,
            mobileAreaList: [],
            sm2PublicKey: 'test-only-public-key',
          },
        },
        dispatch: () => Promise.resolve(),
      };
      this.$t = key => key;
    },
  });
  const vm = new Ctor();
  Object.assign(vm.form, {
    username: 'new-user',
    password: 'TestPassword123',
    confirmPassword: 'TestPassword123',
    captcha: 'ABCDE',
    captchaId: 'one-time-captcha',
  });
  t.after(() => vm.$destroy());

  vm.register();
  vm.register();
  assert.equal(vm.registerSubmitting, true);
  assert.equal(pendingRequests.length, 1);
  assert.deepEqual(pendingRequests[0].data, {
    username: 'new-user',
    password: 'encrypted:ABCDETestPassword123',
    captchaId: 'one-time-captcha',
    mobileCaptcha: '',
  });

  pendingRequests[0].failure({ status: 0 });
  assert.equal(vm.registerSubmitting, false);
  assert.deepEqual(routes, []);

  vm.register();
  assert.equal(pendingRequests.length, 2);
  pendingRequests[1].success({ data: { code: 0 } });
  assert.equal(vm.registerSubmitting, false);
  assert.deepEqual(routes, [{ path: '/login', query: { redirect: '/invite' } }]);
  assert.deepEqual(notices.success, ['register.registerSuccess']);
});

test('registration transport failures notify once without replaying the one-time CAPTCHA request', () => {
  const networkError = { status: 0, data: { msg: 'test-only network failure' } };
  const state = { sends: 0, retries: 0, failures: [] };
  const requestService = {
    clearRequestTime() {},
    reAjaxFun() { state.retries += 1; },
    sendRequest() {
      let networkFail;
      const request = {
        url() { return request; },
        method() { return request; },
        data() { return request; },
        success() { return request; },
        fail() { return request; },
        networkFail(callback) { networkFail = callback; return request; },
        send() { state.sends += 1; networkFail(networkError); return request; },
      };
      return request;
    },
  };
  const user = loadModule('src/apis/module/user.js', {
    '../api': { getServiceUrl: () => '/xiaozhi' },
    '../httpRequest': { __esModule: true, default: requestService },
  }).default;

  user.register({ captchaId: 'one-time-captcha' }, () => assert.fail('network failure cannot succeed'), error => {
    state.failures.push(error);
  });

  assert.equal(state.sends, 1);
  assert.equal(state.retries, 0);
  assert.deepEqual(state.failures, [networkError]);
});
