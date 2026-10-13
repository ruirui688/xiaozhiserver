const assert = require('node:assert/strict');
const { test } = require('node:test');
const Vue = require('vue');
const fs = require('node:fs');
const path = require('node:path');
const compiler = require('vue-template-compiler');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const settled = () => new Promise(resolve => setImmediate(resolve));
const nodes = vnode => [vnode, ...(vnode.children || []).flatMap(nodes)];
const text = vnode => [vnode.text || '', ...(vnode.children || []).map(text)].join('');
function mount(t, file, { permissions = [], mocks = {}, props = {} } = {}) {
  const source = fs.readFileSync(path.resolve(__dirname, '..', file), 'utf8');
  const compiled = compiler.compileToFunctions(compiler.parseComponent(source).template.content);
  const definition = loadVueComponent(file, mocks);
  const vm = new Vue({ ...definition, render: compiled.render, staticRenderFns: compiled.staticRenderFns, propsData: props,
    beforeCreate() { this.$store = { state: { userInfo: { audience: 'OPS', permissions } } }; this.$t = key => key; this.$message = { error() {}, success() {}, warning() {} }; },
  });
  t.after(() => vm.$destroy()); return vm;
}

test('operations plugin details omit tenant setup and secret controls while user details retain them', t => {
  const plugin = { providerCode: 'get_weather', providerName: '天气' };
  const vm = mount(t, 'src/components/resource/PluginDetailDialog.vue', { props: { plugin, visible: true, configurable: true, allowSetup: false } });
  assert.doesNotMatch(nodes(vm._render()).filter(node => node.tag === 'el-button').map(text).join('|'), /配置服务密钥|添加到智能体|测试连接/);
  vm.allowSetup = true; assert.match(text(vm._render()), /配置服务密钥/);
});

test('model assistant uses catalog-scoped list and cannot submit with a read-only role', async t => {
  const calls = [], mutations = [];
  const vm = mount(t, 'src/components/ModelConfigAssistant.vue', { permissions: ['model.catalog.read'], mocks: {
    '@/apis/platform': { platformRequest: async (...args) => { calls.push(args); return { list: [{ id: 'model-a', modelName: 'A' }] }; } },
    '@/apis/api': { __esModule: true, default: { model: { chatWithAssistant: (...args) => mutations.push(args), updateAssistantSettings: (...args) => mutations.push(args), applyAssistantArtifacts: (...args) => mutations.push(args) } } },
  } });
  await vm.loadLlmModels(); assert.deepEqual(calls[0], ['GET', '/models/list', { modelType: 'LLM', page: 1, limit: 200 }]);
  assert.equal(vm.llmModels[0].id, 'model-a'); vm.prompt = 'test-only'; vm.selectedLlmModelId = 'model-a'; vm.send(); vm.saveSettings(); vm.applyArtifacts(false); assert.equal(mutations.length, 0);
});

test('View products focuses the selected account and scrolls even when already selected', () => {
  const component = loadVueComponent('src/components/billing/BillingAccountOverview.vue', { '@/apis/api': {} });
  const account = { provider: 'VOLCENGINE', accountRef: 'fixture-account' }; const scrolls = [];
  const vm = { focusedKey: '', billQuery: { old: true }, $nextTick: callback => callback(), $refs: { productPanel: { scrollIntoView: args => scrolls.push(args) } } };
  component.methods.focusAccount.call(vm, account); const key = vm.focusedKey;
  component.methods.focusAccount.call(vm, account); assert.equal(vm.focusedKey, key); assert.equal(vm.billQuery, null); assert.equal(scrolls.length, 2);
});

test('price publishing uses selected model and named metric fields without sending parsed floating values', async t => {
  const saved = [];
  const vm = mount(t, 'src/ops/OpsRates.vue', { permissions: ['rates.read', 'rates.write'], mocks: { '@/apis/platform': { operations: {
    rates: async () => ({ list: [{ modelId: 'unknown', metricRatesJson: '{"future":"0.5"}', maxUsageJson: '{"future":"100"}' }], total: '1' }),
    channelModels: async () => [{ id: 'model-a', name: 'A', serviceType: 'TTS' }], addRate: async payload => saved.push(payload),
  } } } });
  await settled(); assert.equal(vm.rows[0].metricRates.future, '0.5'); vm.open(); vm.form.modelId = 'model-a'; vm.chooseModel(); vm.form.version = 'v1';
  vm.form.prices.billable_characters = '0.000000123456789'; vm.form.maximums.billable_characters = '5000'; await vm.save();
  assert.deepEqual(saved[0], { modelId: 'model-a', version: 'v1', metricRates: { billable_characters: '0.000000123456789' }, maxUsage: { billable_characters: '5000' } });
});

test('platform task creation uses selected model ids without allocating any credentials', async t => {
  const created = [], assigned = [];
  const vm = mount(t, 'src/ops/OpsPlatformAccounts.vue', { permissions: ['pool.adjust', 'pool.read'], mocks: { '@/apis/platform': { operations: {
    services: async () => [], channelModels: async () => [{ id: 'model-a', name: 'A', serviceType: 'LLM' }], addService: async payload => created.push(payload), assignCredential: async payload => assigned.push(payload),
  } } } });
  await settled(); vm.open('create'); vm.form.id = 'task'; vm.form.modelIds = ['model-a']; vm.form.purposes = ['MODEL_TEST']; await vm.save();
  assert.deepEqual(created, [{ id: 'task', modelIds: ['model-a'], purposes: ['MODEL_TEST'] }]); assert.equal(assigned.length, 0);
  vm.open('assign', { accountId: '-9223372036854775806' }); assert.equal(vm.dialog, false);
});

test('assistant settings failure is visible and blocks sending until a successful retry', t => {
  let receive; const chats = [];
  const vm = mount(t, 'src/components/ModelConfigAssistant.vue', { permissions: ['model.catalog.read', 'model.catalog.test'], mocks: {
    '@/apis/platform': { platformRequest: async () => ({ list: [] }) },
    '@/apis/api': { __esModule: true, default: { model: { getAssistantSettings: callback => { receive = callback; }, chatWithAssistant: (...args) => chats.push(args) } } },
  } });
  vm.loadSettings(); assert.equal(vm.settingsLoading, true); vm.prompt = 'test-only'; vm.selectedLlmModelId = 'old'; vm.send(); assert.equal(chats.length, 0);
  receive({ data: { code: -1, msg: '助手设置暂不可用' } }); assert.equal(vm.settingsError, '助手设置暂不可用'); vm.send(); assert.equal(chats.length, 0);
  vm.loadSettings(); receive({ data: { code: 0, data: { llmModelId: 'new', llmModelName: '已配置模型' } } }); assert.equal(vm.settingsError, ''); assert.equal(vm.settingsLoading, false); assert.equal(vm.selectedLlmModelId, 'new');
});
