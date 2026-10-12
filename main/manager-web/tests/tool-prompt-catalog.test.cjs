const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const Vue = require('vue/dist/vue.common.js');
const compiler = require('vue-template-compiler');
const { loadVueComponent, loadModule } = require('./helpers/loadVueComponent.cjs');
const catalog = loadModule('src/utils/pluginCatalog.js');
function plugin(code, overrides = {}) {
  return { id: code, modelType: 'Plugin', providerCode: code, name: code, sort: 1,
    fieldsMeta: [
      { key: 'custom_plugin', type: 'boolean', default: true },
      { key: 'description', type: 'textarea', default: '用户明确要玩时启动' },
      { key: 'instructions', type: 'textarea', default: '目录规则 v1' },
      { key: 'opening_message', type: 'textarea', default: '欢迎开始' },
      { key: 'rounds', type: 'number', default: 3 },
      { key: 'parameters_schema', type: 'json', default: { type: 'object' } },
    ], ...overrides };
}
function mocks(api = {}) {
  return { '@/apis/api': { __esModule: true, default: api },
    '@/apis/httpRequest': { __esModule: true, default: {} },
    '@/i18n': { __esModule: true, default: { t: key => key } },
    '@/utils/featureManager': { __esModule: true, default: {} } };
}
function vmFor(file, props = {}, api = {}) {
  const Ctor = Vue.extend({ ...loadVueComponent(file, mocks(api)), created: [], render: h => h('div') });
  const vm = new Ctor({ propsData: props });
  vm.$message = { error() {}, success() {}, warning() {} };
  vm.$store = { state: { userInfo: { superAdmin: true } } };
  return vm;
}
function nodes(vnode) { return vnode ? [vnode, ...(vnode.children || []).flatMap(nodes)] : []; }

async function openFunctionDrawer(vm) {
  vm.loadFeatureStatus = async () => {};
  for (const method of ['loadMcpAddress', 'loadMcpTools', 'loadNoCodeTools', 'loadSharedConfigStatus']) vm[method] = () => {};
  const open = loadVueComponent('src/components/FunctionDialog.vue', mocks()).watch.value;
  await open.call(vm, true);
}

test('catalog opens on addable plugins and separates activities, integrations, system and retired records', () => {
  const rows = [plugin('riddle', { catalogMetadata: { capabilityType: 'activity', injectionMode: 'on_demand_activity' } }),
    plugin('get_weather', { fieldsMeta: [] }),
    plugin('set_session_voice', { fieldsMeta: [{ key: 'runtime_builtin', default: true }] }), plugin('manage_memory'),
    plugin('direct_answer', { fieldsMeta: [], catalogMetadata: { activationMode: 'runtime', selectable: false } }),
    plugin('feishu_assistant', { fieldsMeta: [{ key: 'catalog_only', default: true }] })]
    .map(row => ({ ...row, fields: row.fieldsMeta, fieldsMeta: undefined }));
  const vm = vmFor('src/views/FunctionPluginManagement.vue', {}, { model: {
    getModelProvidersPage(params, callback) {
      assert.equal(params.modelType, 'Plugin'); callback({ data: { code: 0, data: { list: rows } } });
    } } });
  vm.fetchPlugins();
  assert.deepEqual(vm.pluginCounts, { total: 6, plugins: 1, activities: 1, integrations: 1, system: 1, retired: 2 });
  assert.equal(vm.activeType, 'plugins');
  assert.deepEqual(vm.visiblePlugins.map(row => row.providerCode), ['get_weather']);
  vm.setActiveType('activities');
  assert.deepEqual(vm.visiblePlugins.map(row => row.providerCode), ['riddle']);
  vm.setActiveType('retired');
  assert.deepEqual(vm.visiblePlugins.map(row => row.providerCode), ['set_session_voice', 'manage_memory']);
  assert.match(catalog.pluginActivation(vm.visiblePlugins[0]), /已退役/);
  assert.match(catalog.pluginInjection(rows[0]), /模型启动后/);
  assert.match(catalog.pluginModelPaths(rows[0]), /未声明/);
  vm.$destroy();
});

test('retired records cannot open or submit catalog editors', () => {
  let writes = 0;
  const vm = vmFor('src/views/FunctionPluginManagement.vue', {}, { model: {
    updateModelProvider() { writes++; }, addModelProvider() { writes++; } } });
  const retired = plugin('set_session_voice'); vm.plugins = [retired];
  vm.openPromptDialog(retired); vm.openEditDialog(retired); vm.openCreateDialog(retired);
  assert.equal(vm.promptDialogVisible, false); assert.equal(vm.formDialogVisible, false);
  vm.currentPlugin = retired; vm.promptForm.description = 'attempt'; vm.saveToolPrompt();
  vm.editingId = retired.id; vm.savePlugin(); assert.equal(writes, 0); vm.$destroy();
});

test('retired card renders no edit or configure action even with admin props', () => {
  const file = 'src/components/resource/PluginResourceCard.vue';
  const template = compiler.parseComponent(fs.readFileSync(path.join(__dirname, '..', file), 'utf8')).template.content;
  const Ctor = Vue.extend({ ...loadVueComponent(file), ...compiler.compileToFunctions(template) });
  const vm = new Ctor({ propsData: { plugin: plugin('manage_memory'), retired: true, interaction: true, canManage: true, configurable: true } });
  const labels = nodes(vm._render()).map(node => node.text || '').join('');
  assert.match(labels, /已停用/); assert.doesNotMatch(labels, /编辑调用说明|编辑玩法|复制玩法|配置服务密钥|添加到智能体/);
  vm.$destroy();
});

test('catalog call description update preserves rules and copying uses current catalog content', () => {
  let saved;
  const vm = vmFor('src/views/FunctionPluginManagement.vue', {}, { model: { updateModelProvider(payload) { saved = payload; } } });
  const source = plugin('riddle'); vm.plugins = [source]; vm.openPromptDialog(source);
  vm.promptForm.description = '新的调用说明'; vm.saveToolPrompt();
  assert.equal(saved.fields.find(field => field.key === 'description').default, '新的调用说明');
  assert.equal(saved.fields.find(field => field.key === 'instructions').default, '目录规则 v1');
  vm.openEditDialog(source);
  assert.deepEqual(vm.buildFields().find(field => field.key === 'parameters_schema').default, { type: 'object' });
  assert.equal(vm.pluginForm.parametersSchemaText, undefined);
  source.fieldsMeta.find(field => field.key === 'instructions').default = '目录规则 v2';
  vm.openCreateDialog(source); assert.equal(vm.pluginForm.instructions, '目录规则 v2');
  assert.equal(vm.pluginForm.providerCode, ''); assert.equal(vm.interactionPresets, undefined); vm.$destroy();
});

test('role selection excludes retired and catalog-only records and strips catalog fields', async () => {
  const activity = plugin('riddle');
  activity.params = { ...catalog.newPluginParams(activity), description: '旧角色说明', custom_plugin: true, instructions: '角色规则' };
  const retired = plugin('manage_memory', { params: {} });
  const builtin = plugin('direct_answer', { fieldsMeta: [{ key: 'catalog_only', default: true }], params: {} });
  const vm = vmFor('src/components/FunctionDialog.vue', { allFunctions: [activity, retired, builtin],
    functions: [{ ...activity, params: { ...activity.params } }], agentId: 'a' });
  await openFunctionDrawer(vm);
  vm.selectAll(); assert.deepEqual(vm.selectedNames, ['riddle']);
  assert.deepEqual(vm.agentConfigFields(activity).map(field => field.key), ['rounds']);
  let selected; vm.$on('update-functions', value => selected = value); vm.saveSelection();
  assert.deepEqual(selected.map(item => item.id), ['riddle']);
  assert.deepEqual(selected[0].params, { rounds: 3, instructions: '角色规则' }); vm.$destroy();
});

test('rule source round trip preserves explicit empty values and supports returning to inheritance', async () => {
  const activity = plugin('riddle', { params: { rounds: 3 } });
  const selector = vmFor('src/components/FunctionDialog.vue', { allFunctions: [activity], functions: [], agentId: 'a' });
  await openFunctionDrawer(selector);
  selector.selectedNames = ['riddle']; selector.currentFunction = activity;
  const editor = vmFor('src/components/resource/PluginRuleOverrides.vue', { plugin: activity });
  editor.$on('change', params => { selector.handleRuleParams(params); editor._props.plugin = selector.currentFunction; });
  assert.equal(editor.usesOverride('instructions'), false); assert.equal(editor.valueFor('instructions'), '目录规则 v1');
  editor.changeSource('instructions', 'override'); editor.changeValue('instructions', '');
  selector.handleParamChange(selector.currentFunction, 'rounds', 8); editor._props.plugin = selector.currentFunction;
  assert.equal(editor.usesOverride('instructions'), true); assert.equal(editor.valueFor('instructions'), '');
  let saved; selector.$on('update-functions', value => { saved = value; selector._props.functions = value; }); selector.saveSelection();
  assert.equal(saved[0].params.instructions, ''); assert.equal(saved[0].params.rounds, 8);
  await openFunctionDrawer(selector); editor._props.plugin = selector.currentFunction;
  editor.changeSource('instructions', 'inherit'); selector.saveSelection();
  assert.equal(Object.hasOwn(saved[0].params, 'instructions'), false); assert.equal(saved[0].params.rounds, 8);
  activity.fieldsMeta.find(field => field.key === 'instructions').default = '目录规则 v2';
  editor._props.plugin = { ...activity, params: saved[0].params }; await Vue.nextTick();
  assert.equal(editor.valueFor('instructions'), '目录规则 v2'); editor.$destroy(); selector.$destroy();
});

test('role load/save keeps inherited prompts absent, preserves explicit empty rules and removes retired mappings', async () => {
  const rows = [plugin('riddle'), plugin('set_session_voice')].map(item => ({ ...item, fields: item.fieldsMeta }));
  const vm = vmFor('src/components/role/RoleEditDialog.vue', { currentFunctions: [], currentKnowledgeBases: [] }, { model: {
    getPluginFunctionList(_params, callback) { callback({ data: { code: 0, data: rows } }); } } });
  await vm.fetchAllFunctions(); vm.$on('update:currentFunctions', value => vm._props.currentFunctions = value);
  vm.applyFunctionMappings([{ pluginId: 'riddle', paramInfo: { instructions: '', description: '旧副本' } }, { pluginId: 'set_session_voice', paramInfo: {} }]);
  assert.deepEqual(vm.buildFunctionMappings(), [{ pluginId: 'riddle', paramInfo: { instructions: '' } }]);
  vm.applyFunctionMappings([{ pluginId: 'riddle', paramInfo: {} }]);
  assert.deepEqual(vm.buildFunctionMappings(), [{ pluginId: 'riddle', paramInfo: {} }]); vm.$destroy();
});

test('reopening the role drawer restores saved sources and discards cancelled rule edits', async () => {
  const activity = plugin('riddle', { params: {} });
  const file = 'src/components/FunctionDialog.vue';
  const vm = vmFor(file, { allFunctions: [activity], functions: [{ ...activity, params: {} }], agentId: 'a' });
  vm.loadFeatureStatus = async () => {};
  for (const method of ['loadMcpAddress', 'loadMcpTools', 'loadNoCodeTools', 'loadSharedConfigStatus']) vm[method] = () => {};
  const open = loadVueComponent(file, mocks()).watch.value;
  await open.call(vm, true);
  assert.equal(catalog.ruleUsesOverride(vm.currentFunction, 'instructions'), false);
  vm.handleRuleParams({ instructions: '未保存内容' });
  vm.closeDialog();
  await open.call(vm, true);
  assert.equal(catalog.ruleUsesOverride(vm.currentFunction, 'instructions'), false);
  assert.equal(catalog.pluginRuleValue(vm.currentFunction, 'instructions'), '目录规则 v1');
  vm.handleRuleParams({ instructions: '保存的角色规则' });
  vm.$on('update-functions', value => vm._props.functions = value);
  vm.saveSelection();
  await open.call(vm, true);
  assert.equal(catalog.pluginRuleValue(vm.currentFunction, 'instructions'), '保存的角色规则');
  vm.$destroy();
});

test('legacy description-only rules survive sanitization as explicit role rules', () => {
  const legacy = plugin('legacy_game', { fieldsMeta: [{ key: 'custom_plugin', default: true }] });
  assert.deepEqual(catalog.sanitizePluginParams(legacy, { description: '旧玩法规则', custom_plugin: true }), { instructions: '旧玩法规则' });
  assert.deepEqual(catalog.sanitizePluginParams(legacy, { description: '旧说明', instructions: '' }), { instructions: '' });
  assert.deepEqual(catalog.sanitizePluginParams(plugin('riddle'), { description: '过时调用说明' }), {});
});

test('reselecting and select-all keep displayed rule source aligned with the pending save', async () => {
  const activity = plugin('riddle', { params: { instructions: '旧角色规则' } });
  const vm = vmFor('src/components/FunctionDialog.vue', {
    allFunctions: [activity], functions: [{ ...activity, params: { ...activity.params } }], agentId: 'a',
  });
  await openFunctionDrawer(vm);
  vm.selectedNames = ['riddle'];
  vm.currentFunction = activity;
  vm.handleRuleParams({});
  vm.handleCheckboxChange(activity, false);
  vm.handleCheckboxChange(activity, true);
  assert.equal(catalog.ruleUsesOverride(vm.currentFunction, 'instructions'), false);
  vm.selectAll();
  assert.equal(catalog.ruleUsesOverride(vm.currentFunction, 'instructions'), false);
  let saved;
  vm.$on('update-functions', value => { saved = value; vm._props.functions = value; });
  vm.saveSelection();
  assert.deepEqual(saved[0].params, {});

  await openFunctionDrawer(vm);
  vm.handleRuleParams({ instructions: '新的角色规则' });
  vm.handleCheckboxChange(activity, false);
  vm.handleCheckboxChange(activity, true);
  assert.equal(catalog.pluginRuleValue(vm.currentFunction, 'instructions'), '新的角色规则');
  vm.deselectAll();
  vm.selectAll();
  assert.equal(catalog.pluginRuleValue(vm.currentFunction, 'instructions'), '新的角色规则');
  vm.saveSelection();
  assert.deepEqual(saved[0].params, { instructions: '新的角色规则' });
  vm.$destroy();
});

test('empty activities and failed service status never claim a configured capability is missing', () => {
  let configCallback;
  const vm = vmFor('src/views/FunctionPluginManagement.vue', {}, { user: {
    getPluginConfigs(callback) { configCallback = callback; }
  } });
  vm.plugins = [plugin('get_weather', { fieldsMeta: [] })];
  vm.setActiveType('activities');
  assert.deepEqual(vm.visiblePlugins, []);
  assert.equal(vm.pluginCounts.activities, 0);
  assert.equal(vm.emptyDescription, '还没有互动玩法');
  vm.fetchSharedConfigStatuses();
  assert.equal(vm.sharedConfigState('get_weather'), 'loading');
  configCallback({ status: 503 });
  assert.equal(vm.sharedConfigState('get_weather'), 'error');
  assert.equal(vm.sharedConfigConfigured('get_weather'), false);
  vm.fetchSharedConfigStatuses();
  configCallback({ data: { code: 0, data: [{ providerCode: 'get_weather', configured: true, secretConfigured: true }] } });
  assert.equal(vm.sharedConfigState('get_weather'), 'saved');
  vm.$destroy();
});

test('normal cards lead to setup without exposing tool codes or maintenance controls', () => {
  const file = 'src/components/resource/PluginResourceCard.vue';
  const template = compiler.parseComponent(fs.readFileSync(path.join(__dirname, '..', file), 'utf8')).template.content;
  const Ctor = Vue.extend({ ...loadVueComponent(file), ...compiler.compileToFunctions(template) });
  const weather = plugin('get_weather', { fieldsMeta: [] });
  const vm = new Ctor({ propsData: { plugin: weather, configurable: true, configState: 'saved' } });
  const tree = nodes(vm._render());
  const labels = tree.map(node => node.text || '').join('');
  assert.match(labels, /天气查询|服务密钥已保存/);
  assert.doesNotMatch(labels, /get_weather|注入阶段|schema|编辑调用说明|测试通过/);
  const setupButton = tree.find(node => node.tag === 'el-button' && nodes(node).some(child => /添加到智能体/.test(child.text || '')));
  assert.ok(setupButton);
  let selected;
  vm.$on('setup', value => selected = value);
  setupButton.data.on.click();
  assert.equal(selected, weather);
  vm.$destroy();
});

test('detail keeps technical text collapsed and gives retired voice a replacement settings action', () => {
  const file = 'src/components/resource/PluginDetailDialog.vue';
  const template = compiler.parseComponent(fs.readFileSync(path.join(__dirname, '..', file), 'utf8')).template.content;
  const Ctor = Vue.extend({ ...loadVueComponent(file), ...compiler.compileToFunctions(template) });
  const voice = plugin('set_session_voice', { fieldsMeta: [] });
  const vm = new Ctor({ propsData: { plugin: voice, visible: true, canManage: true } });
  let labels = nodes(vm._render()).map(node => node.text || '').join('');
  assert.match(labels, /前往语音设置/);
  assert.doesNotMatch(labels, /set_session_voice|编辑调用说明|编辑玩法|复制玩法/);
  assert.deepEqual(vm.advancedPanels, []);
  vm.advancedPanels = ['technical'];
  labels = nodes(vm._render()).map(node => node.text || '').join('');
  assert.match(labels, /set_session_voice/);
  assert.doesNotMatch(labels, /编辑调用说明|编辑玩法|复制玩法/);
  let selected;
  vm.$on('setup', value => selected = value);
  const setupButton = nodes(vm._render()).find(node => node.tag === 'el-button'
    && nodes(node).some(child => /前往语音设置/.test(child.text || '')));
  setupButton.data.on.click();
  assert.equal(selected, voice);
  vm.$destroy();
});
