const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test, beforeEach, afterEach } = require('node:test');
const Vue = require('vue/dist/vue.common.js');
const compiler = require('vue-template-compiler');
const { loadVueComponent, loadModule } = require('./helpers/loadVueComponent.cjs');

const SETUP_FILE = 'src/components/resource/PluginSetupDialog.vue';
const ROLE_FILE = 'src/components/role/RoleEditDialog.vue';
const DRAWER_FILE = 'src/components/FunctionDialog.vue';
let workspaceId;

beforeEach(() => {
  workspaceId = 'workspace-1';
  global.localStorage = { getItem: () => workspaceId };
  Vue.prototype.$route = Vue.observable({ query: { agentId: 'agent-1' } });
  Vue.prototype.$t = key => key;
  Vue.prototype.$message = { error() {}, warning() {}, success() {} };
});

afterEach(() => {
  delete global.localStorage;
  delete Vue.prototype.$route;
  delete Vue.prototype.$t;
  delete Vue.prototype.$message;
});

async function flush() {
  for (let i = 0; i < 5; i++) await Vue.nextTick();
}

function deferred() {
  let resolve; let reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

function plugin(code = 'get_weather', overrides = {}) {
  return { id: code, name: code, providerCode: code, fieldsMeta: [], params: {}, ...overrides };
}

function harness() {
  const requests = [];
  const agentRequests = [];
  const sharedConfigRequests = [];
  const requestService = {
    sendRequest() {
      const request = {};
      const builder = {};
      for (const key of ['url', 'method', 'success', 'fail', 'networkFail']) {
        builder[key] = value => { request[key] = value; return builder; };
      }
      builder.send = () => { requests.push(request); return builder; };
      return builder;
    },
    clearRequestTime() {},
    reAjaxFun() {},
  };
  const api = {
    agent: { getAgentList(success, fail) { agentRequests.push({ success, fail }); } },
    user: { getPluginConfigs(callback) { sharedConfigRequests.push(callback); } },
  };
  const mocks = {
    '@/apis/api': { __esModule: true, default: api, getServiceUrl: () => '/api' },
    '@/apis/httpRequest': { __esModule: true, default: requestService },
    '@/i18n': { __esModule: true, default: { t: key => key } },
    '@/utils/featureManager': { __esModule: true, default: {} },
  };
  function vm(file, props = {}) {
    const component = loadVueComponent(file, mocks);
    return new (Vue.extend({ ...component, render: h => h('div') }))({ propsData: props });
  }
  function resolveWorkspace(index = 0, canWrite = true) {
    requests[index].success({ data: { code: 0, data: { canWrite } } });
  }
  function resolveAgents(rows = [{ id: 'agent-1', agentName: '家庭助手' }], index = 0) {
    agentRequests[index].success({ data: { code: 0, data: rows } });
  }
  return { requests, agentRequests, sharedConfigRequests, requestService, mocks, vm, resolveWorkspace, resolveAgents };
}

test('setup query accepts only known destinations and valid agent ids', () => {
  const { mocks } = harness();
  const { pluginSetupQuery } = loadModule('src/mixins/rolePluginNavigation.js', mocks);
  for (const query of [null, {}, { agentId: ['a'], setup: 'functions' },
    { agentId: 'a', setup: 'save' }, { agentId: ' ', setup: 'voice' }]) {
    assert.equal(pluginSetupQuery(query), null);
  }
  assert.deepEqual(pluginSetupQuery({ agentId: 'a', setup: 'knowledge', plugin: 'x', extra: 'ignored' }),
    { agentId: 'a', setup: 'knowledge', plugin: 'x' });
});

test('setup requires an explicit accessible agent choice and only navigates', async () => {
  const h = harness();
  const vm = h.vm(SETUP_FILE, { visible: true, plugin: plugin() });
  const routes = [];
  vm.$router = { push: async route => { routes.push(route); } };
  assert.equal(vm.canNavigate, false);
  h.resolveAgents(); h.resolveWorkspace(); await flush();
  assert.equal(vm.selectedAgentId, '');
  assert.equal(vm.canNavigate, false);
  vm.selectedAgentId = 'not-in-authorized-list';
  await vm.goToSetup(); assert.equal(routes.length, 0);
  vm.selectedAgentId = 'agent-1';
  assert.equal(vm.canNavigate, true);
  await vm.goToSetup();
  assert.deepEqual(routes, [{ path: '/role-config', query: {
    agentId: 'agent-1', setup: 'functions', plugin: 'get_weather',
  } }]);
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].method, 'GET');
  vm.$destroy();
});

test('read-only workspace and empty agent list prevent configuration navigation', async () => {
  for (const [canWrite, rows] of [[false, [{ id: 'agent-1' }]], [true, []]]) {
    const h = harness();
    const vm = h.vm(SETUP_FILE, { visible: true, plugin: plugin() });
    h.resolveWorkspace(0, canWrite); h.resolveAgents(rows); await flush();
    vm.selectedAgentId = 'agent-1';
    assert.equal(vm.canNavigate, false);
    assert.equal(vm.canWrite, canWrite);
    assert.equal(vm.agents.length, rows.length);
    assert.equal(vm.loading, false);
    vm.$destroy();
  }
});

test('setup read failure supports retry and does not retain old selections', async () => {
  const h = harness();
  const vm = h.vm(SETUP_FILE, { visible: true, plugin: plugin() });
  h.resolveWorkspace(); h.agentRequests[0].fail(new Error('network')); await flush();
  assert.match(vm.error, /加载失败/); assert.equal(vm.canNavigate, false);
  const retry = vm.loadAgents();
  h.resolveWorkspace(1); h.resolveAgents(undefined, 1); await retry;
  assert.equal(vm.error, ''); assert.equal(vm.selectedAgentId, '');
  assert.equal(vm.agents.length, 1);
  vm.$destroy();
});

test('superseded and closed setup requests cannot replace the current state', async () => {
  const h = harness();
  const vm = h.vm(SETUP_FILE, { visible: true, plugin: plugin() });
  const newer = vm.loadAgents();
  h.resolveWorkspace(1); h.resolveAgents([{ id: 'new-agent' }], 1); await newer;
  h.resolveWorkspace(0); h.resolveAgents([{ id: 'stale-agent' }], 0); await flush();
  assert.deepEqual(vm.agents.map(agent => agent.id), ['new-agent']);
  const closing = vm.loadAgents();
  vm._props.visible = false; await flush();
  h.resolveWorkspace(2); h.resolveAgents([{ id: 'closed-agent' }], 2); await closing;
  assert.deepEqual(vm.agents, []); assert.equal(vm.canNavigate, false);
  vm.$destroy();
});

test('workspace changes discard loaded setup access and unknown integrations have no navigation', async () => {
  const h = harness();
  const vm = h.vm(SETUP_FILE, { visible: true, plugin: plugin() });
  workspaceId = 'workspace-2';
  h.resolveWorkspace(); h.resolveAgents(); await flush();
  assert.match(vm.error, /工作空间已切换/); assert.equal(vm.canNavigate, false);
  vm.$destroy();
  const unknown = h.vm(SETUP_FILE, { visible: true,
    plugin: plugin('unknown-binding', { catalogMetadata: { activationMode: 'binding', selectable: false } }) });
  assert.equal(unknown.presentation.setupTarget, null);
  assert.equal(unknown.loading, false); assert.equal(unknown.canNavigate, false);
  assert.equal(h.requests.length, 1);
  unknown.$destroy();
});

test('navigation rechecks workspace after a list has already loaded', async () => {
  const h = harness(); const vm = h.vm(SETUP_FILE, { visible: true, plugin: plugin() });
  let navigations = 0; vm.$router = { push: async () => { navigations++; } };
  h.resolveWorkspace(); h.resolveAgents(); await flush();
  vm.selectedAgentId = 'agent-1'; assert.equal(vm.canNavigate, true);
  workspaceId = 'workspace-2';
  await vm.goToSetup();
  assert.equal(navigations, 0); assert.match(vm.error, /工作空间已切换/);
  vm.$destroy();
});

test('role navigation waits for actual hydration and does not enable or save the focused plugin', async () => {
  const h = harness();
  const vm = h.vm(ROLE_FILE, { currentFunctions: [], currentKnowledgeBases: [] });
  const functions = deferred(); const knowledge = deferred();
  vm.fetchAllFunctions = () => functions.promise;
  vm.fetchAllKnowledgeBases = () => knowledge.promise;
  vm.allFunctions = [plugin()];
  vm.$route.query = { agentId: 'agent-1', setup: 'functions', plugin: 'get_weather' };
  await flush();
  assert.equal(vm.showFunctionDialog, false); assert.equal(h.requests.length, 0);
  let saves = 0;
  vm.$on('save', () => saves++);
  const hydration = vm.hydrateAgentConfig({ functions: [], intentModelId: 'Intent_nointent' });
  functions.resolve(true); await flush(); assert.equal(h.requests.length, 0);
  knowledge.resolve(true); assert.equal(await hydration, true);
  assert.equal(h.requests.length, 1);
  const repeated = vm.applyPluginSetupNavigation();
  h.resolveWorkspace(); await repeated; await flush();
  assert.equal(vm.showFunctionDialog, true);
  assert.equal(vm.focusedPluginCode, 'get_weather');
  assert.equal(vm.activePanel, 'intelligence');
  assert.deepEqual(vm.currentFunctions, []); assert.equal(saves, 0);
  assert.match(vm.pluginSetupMessage, /保存配置/);
  assert.match(vm.pluginSetupMessage, /工具调用/);
  vm.$destroy();
});

async function readyRole(h, query, props = {}) {
  const vm = h.vm(ROLE_FILE, props);
  vm.allFunctions = [plugin()];
  vm.capabilityMappingsReady = true;
  vm.pluginSetupReadyAgent = 'agent-1';
  vm.$route.query = { agentId: 'agent-1', ...query };
  await flush();
  return vm;
}

test('role navigation explains read-only, deleted and retired destinations', async () => {
  for (const [code, canWrite, text] of [['get_weather', false, /只读/],
    ['deleted', true, /已删除/], ['set_session_voice', true, /停用/]]) {
    const h = harness();
    const vm = await readyRole(h, { setup: 'functions', plugin: code });
    vm.allFunctions.push(plugin('set_session_voice'));
    h.resolveWorkspace(0, canWrite); await flush();
    assert.equal(vm.showFunctionDialog, false);
    assert.match(vm.pluginSetupMessage, text);
    vm.$destroy();
  }
});

test('native function calling is not described as disabled when Intent is off', async () => {
  const h = harness();
  const vm = await readyRole(h, { setup: 'functions', plugin: 'get_weather' }, {
    modelOptions: { LLM: [{ value: 'realtime', raw: { nativeFunctionCalling: true } }] },
  });
  vm.form.model.llmModelId = 'realtime';
  vm.form.model.intentModelId = 'Intent_nointent';
  h.resolveWorkspace(); await flush();
  assert.equal(vm.showFunctionDialog, true);
  assert.doesNotMatch(vm.pluginSetupMessage, /启用工具调用|未开启工具调用/);
  vm.$destroy();
});

test('role navigation uses dedicated binding tabs and model-aware voice destination', async () => {
  for (const [setup, panel, dialog] of [['knowledge', 'intelligence', 'showKnowledgeBaseDialog'],
    ['feishu', 'integrations', 'showFeishuDialog'], ['memory', 'memory'], ['voice', 'voice']]) {
    const h = harness(); const vm = await readyRole(h, { setup });
    h.resolveWorkspace(); await flush();
    assert.equal(vm.activePanel, panel);
    if (dialog) assert.equal(vm[dialog], true);
    assert.equal(vm.showFunctionDialog, false);
    vm.$destroy();
  }
  const h = harness();
  const vm = await readyRole(h, { setup: 'voice', plugin: 'set_session_voice' });
  vm.form.model.llmModelId = 'realtime';
  h.resolveWorkspace(); await flush();
  assert.equal(vm.pluginVoiceNavigationPending, true);
  vm._props.modelOptions = { LLM: [{ value: 'realtime', raw: { sessionVoice: true } }] };
  await flush();
  assert.equal(vm.activePanel, 'intelligence');
  assert.equal(vm.pluginVoiceNavigationPending, false);
  assert.match(vm.pluginSetupMessage, /实时音色/);
  vm.$destroy();
});

test('late role permission responses cannot open a dialog for another agent or a destroyed page', async () => {
  const h = harness();
  const vm = await readyRole(h, { setup: 'functions', plugin: 'get_weather' });
  vm.$route.query = { agentId: 'agent-2', setup: 'functions', plugin: 'get_weather' };
  await flush(); h.resolveWorkspace(); await flush();
  assert.equal(vm.showFunctionDialog, false);
  vm.pluginSetupReadyAgent = 'agent-2';
  const request = vm.applyPluginSetupNavigation();
  vm.$destroy(); h.resolveWorkspace(1); await request;
  assert.equal(vm.showFunctionDialog, false);
});

test('role permission failure stays visible and opens no configuration dialog', async () => {
  const h = harness(); const vm = await readyRole(h, { setup: 'knowledge' });
  h.requests[0].fail(new Error('denied')); await flush();
  assert.equal(vm.showKnowledgeBaseDialog, false);
  assert.equal(vm.pluginSetupMessageType, 'error');
  assert.match(vm.pluginSetupMessage, /权限加载失败/);
  vm.$destroy();
});

function prepareDrawer(h, props = {}) {
  const vm = h.vm(DRAWER_FILE, { allFunctions: [plugin()], functions: [], agentId: 'agent-1',
    focusPluginCode: 'get_weather', ...props });
  vm.loadFeatureStatus = async () => {};
  for (const method of ['loadMcpAddress', 'loadMcpTools', 'loadNoCodeTools', 'loadSharedConfigStatus']) vm[method] = () => {};
  const open = loadVueComponent(DRAWER_FILE, h.mocks).watch.value;
  return { vm, open: () => open.call(vm, true) };
}

function renderDrawerNodes(vm) {
  const template = compiler.parseComponent(fs.readFileSync(path.join(__dirname, '..', DRAWER_FILE), 'utf8')).template.content;
  Object.assign(vm.$options, compiler.compileToFunctions(template));
  const nodes = node => node ? [node, ...(node.children || []).flatMap(nodes)] : [];
  return nodes(vm._render());
}

function saveButtonDisabled(vm) {
  const button = renderDrawerNodes(vm).find(node => node.data?.on?.click === vm.saveSelection);
  assert.ok(button, 'save button must exist in the real drawer template');
  return button.data.attrs.disabled;
}

test('drawer focuses an unselected plugin for inspection without saving or selecting it', async () => {
  const h = harness(); const { vm, open } = prepareDrawer(h);
  let saves = 0; vm.$on('update-functions', () => saves++);
  await open();
  assert.equal(vm.currentFunction.providerCode, 'get_weather');
  assert.deepEqual(vm.selectedNames, []); assert.equal(saves, 0);
  assert.match(vm.focusNotice, /勾选/);
  vm.handleFunctionClick(plugin('web_search'));
  assert.equal(vm.currentFunction.providerCode, 'web_search');
  assert.deepEqual(vm.selectedNames, []);
  vm.closeDialog(); assert.equal(saves, 0);
  vm.$destroy();
});

test('drawer explains unavailable feature targets and ignores a closed initialization', async () => {
  for (const [code, text] of [['call_device', /通讯录/], ['set_session_voice', /停用/], ['missing', /已删除/]]) {
    const h = harness(); const { vm, open } = prepareDrawer(h, {
      focusPluginCode: code, allFunctions: [plugin('call_device'), plugin('set_session_voice')],
    });
    await open(); assert.equal(vm.currentFunction, null);
    assert.match(vm.focusNotice, text); assert.deepEqual(vm.selectedNames, []);
    vm.$destroy();
  }
  const h = harness(); const { vm, open } = prepareDrawer(h);
  const gate = deferred(); vm.loadFeatureStatus = () => gate.promise;
  const opening = open(); vm.closeDialog(); gate.resolve(); await opening;
  assert.equal(vm.currentFunction, null); assert.equal(vm.dialogVisible, false);
  vm.$destroy();
});

test('drawer cannot save before initialization, during loading or after failure', async () => {
  const h = harness();
  const original = plugin('get_weather', { params: { location: '北京' } });
  const { vm, open } = prepareDrawer(h, { functions: [original] });
  const saves = []; vm.$on('update-functions', value => saves.push(value));
  vm.selectedNames = ['get_weather'];
  vm.tempFunctions = { get_weather: { ...original, params: { location: '未保存修改' } } };
  vm.saveSelection();
  assert.equal(vm.canSaveSelection, false); assert.deepEqual(saves, []);
  assert.equal(saveButtonDisabled(vm), true);
  const status = deferred(); vm.loadFeatureStatus = () => status.promise;
  const opening = open();
  vm.saveSelection();
  assert.equal(vm.canSaveSelection, false); assert.deepEqual(saves, []);
  assert.equal(saveButtonDisabled(vm), true);
  status.reject(new Error('status unavailable')); await opening;
  vm.saveSelection();
  assert.equal(vm.loading, false); assert.equal(vm.canSaveSelection, false);
  assert.equal(saveButtonDisabled(vm), true);
  assert.match(vm.focusNotice, /加载失败/); assert.deepEqual(saves, []);
  assert.deepEqual(vm.selectedNames, ['get_weather']);
  assert.equal(vm.functions[0].params.location, '北京');
  assert.equal(vm.tempFunctions.get_weather.params.location, '未保存修改');
  assert.equal(vm.hasSaved, false);
  vm.$destroy();
});

test('drawer retries after failure and saves only the restored current selection', async () => {
  const h = harness(); const original = plugin('get_weather', { params: { location: '北京' } });
  const { vm, open } = prepareDrawer(h, { functions: [original] });
  const saves = []; vm.$on('update-functions', value => saves.push(value));
  vm.loadFeatureStatus = async () => { throw new Error('status unavailable'); };
  await open(); assert.equal(vm.canSaveSelection, false);
  vm.closeDialog(); await flush();
  vm.loadFeatureStatus = async () => {};
  await open();
  assert.equal(vm.canSaveSelection, true);
  assert.equal(saveButtonDisabled(vm), false);
  assert.deepEqual(vm.selectedNames, ['get_weather']);
  vm.saveSelection();
  assert.equal(saves.length, 1); assert.equal(saves[0][0].params.location, '北京');
  assert.equal(vm.canSaveSelection, false);
  vm.saveSelection(); assert.equal(saves.length, 1);
  vm.$destroy();
});

test('late success from a closed drawer cannot unlock a newer failed initialization', async () => {
  const h = harness(); const { vm, open } = prepareDrawer(h, { functions: [plugin()] });
  let saves = 0; vm.$on('update-functions', () => saves++);
  const stale = deferred(); vm.loadFeatureStatus = () => stale.promise;
  const oldOpening = open(); vm.closeDialog(); await flush();
  vm.loadFeatureStatus = async () => { throw new Error('new request failed'); };
  await open(); stale.resolve(); await oldOpening;
  vm.saveSelection();
  assert.equal(vm.canSaveSelection, false); assert.equal(saves, 0);
  assert.match(vm.focusNotice, /加载失败/);
  vm.$destroy();
});

test('late failure cannot relock a successful reopening and agent changes block saving', async () => {
  const h = harness(); const { vm, open } = prepareDrawer(h, { functions: [plugin()] });
  let saves = 0; vm.$on('update-functions', () => saves++);
  const stale = deferred(); vm.loadFeatureStatus = () => stale.promise;
  const oldOpening = open(); vm.closeDialog(); await flush();
  vm.loadFeatureStatus = async () => {};
  await open(); stale.reject(new Error('stale request failed')); await oldOpening;
  assert.equal(vm.canSaveSelection, true); assert.doesNotMatch(vm.focusNotice, /加载失败/);
  vm._props.agentId = 'other-agent';
  assert.equal(vm.canSaveSelection, false); vm.saveSelection(); assert.equal(saves, 0);
  vm.$destroy();
});

function sharedConfigDrawer(h) {
  const weather = plugin();
  const vm = h.vm(DRAWER_FILE, { allFunctions: [weather], functions: [{ ...weather }], agentId: 'agent-1' });
  vm.dialogVisible = true;
  vm.currentFunction = weather;
  vm.selectedNames = ['get_weather'];
  return vm;
}

test('drawer service configuration distinguishes loading, saved and weather fallback without claiming a tested connection', () => {
  const h = harness(); const vm = sharedConfigDrawer(h);
  let saves = 0; vm.$on('update-functions', () => saves++);
  vm.loadSharedConfigStatus();
  assert.equal(vm.sharedConfigState('get_weather'), 'loading');
  assert.match(vm.sharedConfigStatusText('get_weather'), /正在读取/);
  assert.doesNotMatch(vm.sharedConfigStatusText('get_weather'), /尚未保存/);
  h.sharedConfigRequests[0]({ data: { code: 0, data: [{ providerCode: 'get_weather', configured: true, secretConfigured: true }] } });
  assert.equal(vm.sharedConfigState('get_weather'), 'saved');
  assert.match(vm.sharedConfigStatusText('get_weather'), /已保存/);
  assert.doesNotMatch(vm.sharedConfigStatusText('get_weather'), /测试通过|连接可用|已验证/);
  const text = renderDrawerNodes(vm).map(node => node.text || '').join('');
  assert.match(text, /我的服务配置/);
  assert.match(text, /其他账号的智能体由所属账号配置/);
  vm.loadSharedConfigStatus(); h.sharedConfigRequests[1]({ data: { code: 0, data: [] } });
  assert.equal(vm.sharedConfigState('get_weather'), 'missing');
  assert.match(vm.sharedConfigStatusText('get_weather'), /普通天气可尝试备用来源/);
  assert.match(vm.sharedConfigStatusText('get_weather'), /天气预警需配置和风天气/);
  assert.match(vm.sharedConfigStatusText('web_search'), /请先配置服务密钥/);
  assert.deepEqual(vm.selectedNames, ['get_weather']); assert.equal(saves, 0);
  vm.$destroy();
});

test('drawer service read failures never display a missing configuration and expose retry', () => {
  const h = harness(); const vm = sharedConfigDrawer(h);
  vm.sharedConfigStatuses = { get_weather: true };
  for (const response of [{ data: { code: 403 } }, { status: 503 }, undefined, { data: { code: 0, data: {} } }]) {
    vm.loadSharedConfigStatus(); h.sharedConfigRequests.at(-1)(response);
    assert.equal(vm.sharedConfigState('get_weather'), 'error');
    assert.equal(vm.sharedConfigState('web_search'), 'error');
    assert.match(vm.sharedConfigStatusText('get_weather'), /读取失败/);
    assert.doesNotMatch(vm.sharedConfigStatusText('get_weather'), /尚未保存|未配置/);
  }
  const retry = renderDrawerNodes(vm).find(node => node.data?.on?.click === vm.loadSharedConfigStatus);
  assert.ok(retry, 'failed service state provides a retry action');
  retry.data.on.click(); assert.equal(vm.sharedConfigState('get_weather'), 'loading');
  h.sharedConfigRequests.at(-1)({ data: { code: 0, data: [] } });
  assert.equal(vm.sharedConfigState('get_weather'), 'missing');
  vm.$destroy();
});

test('stale or closed drawer service responses cannot overwrite the latest status or selection', async () => {
  const h = harness(); const vm = sharedConfigDrawer(h);
  const ready = { data: { code: 0, data: [{ providerCode: 'get_weather', configured: true, secretConfigured: true }] } };
  let saves = 0; vm.$on('update-functions', () => saves++);
  vm.loadSharedConfigStatus(); vm.loadSharedConfigStatus();
  h.sharedConfigRequests[1](ready); h.sharedConfigRequests[0]({ status: 500 });
  assert.equal(vm.sharedConfigState('get_weather'), 'saved');
  vm.loadSharedConfigStatus(); vm.closeDialog(); await flush();
  h.sharedConfigRequests[2]({ data: { code: 0, data: [] } });
  assert.equal(vm.sharedConfigStatuses.get_weather, true);
  vm.dialogVisible = true; vm.loadSharedConfigStatus();
  h.sharedConfigRequests[3]({ data: { code: 0, data: [] } });
  assert.equal(vm.sharedConfigState('get_weather'), 'missing');
  vm.loadSharedConfigStatus(); vm.$destroy(); h.sharedConfigRequests[4](ready);
  assert.equal(vm.sharedConfigStatuses.get_weather, undefined);
  assert.deepEqual(vm.selectedNames, ['get_weather']); assert.equal(saves, 0);
});

test('agent list optional failure callback handles failures while legacy callers retain retry', () => {
  const h = harness();
  let failures = 0; let retries = 0;
  h.requestService.reAjaxFun = () => { retries++; };
  const api = loadModule('src/apis/module/agent.js', {
    '../api': { getServiceUrl: () => '/api' },
    '../httpRequest': { __esModule: true, default: h.requestService },
  }).default;
  api.getAgentList(() => {}, () => { failures++; });
  h.requests[0].networkFail(new Error('network'));
  assert.equal(failures, 1); assert.equal(retries, 0);
  api.getAgentList(() => {});
  h.requests[1].networkFail(new Error('network'));
  assert.equal(retries, 1);
});
