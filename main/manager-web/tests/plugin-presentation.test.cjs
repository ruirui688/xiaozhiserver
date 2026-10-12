const assert = require('node:assert/strict');
const test = require('node:test');
const { loadModule } = require('./helpers/loadVueComponent.cjs');
const { pluginPresentation, pluginSectionCounts, filterCatalogPlugins } = loadModule('src/utils/pluginPresentation.js');

function row(code, fields = [], metadata = {}) {
  return { id: code, name: code, providerCode: code, modelType: 'Plugin', fields,
    catalogMetadata: metadata, sort: 1 };
}
const catalogOnly = [{ key: 'catalog_only', default: true }];
const productionShape = [
  ...['direct_answer', 'handle_exit_intent', 'reset_conversation_context', 'get_lunar', 'end_interaction', 'change_role']
    .map(code => row(code, catalogOnly)),
  row('set_session_voice', [{ key: 'runtime_builtin', default: true }]),
  ...['feishu_assistant', 'search_from_local_knowledge', 'search_from_ragflow'].map(code => row(code, catalogOnly)),
  ...['get_weather', 'play_music', 'meeting_minutes', 'get_news_from_chinanews', 'get_news_from_newsnow',
    'hass_get_state', 'hass_set_state', 'hass_play_music', 'web_search', 'call_device'].map(code => row(code)),
];

test('the deployed twenty-record shape opens with ten addable plugins, not controls or retired tools', () => {
  assert.deepEqual(pluginSectionCounts(productionShape), {
    plugins: 10, activities: 0, integrations: 3, system: 6, retired: 1, total: 20,
  });
  const defaultPage = filterCatalogPlugins(productionShape);
  assert.equal(defaultPage.length, 10);
  assert.ok(defaultPage.every(item => pluginPresentation(item).setupTarget === 'functions'));
  assert.ok(!defaultPage.some(item => ['direct_answer', 'set_session_voice', 'change_role'].includes(item.providerCode)));
  assert.deepEqual(filterCatalogPlugins(productionShape, { section: 'activities' }), []);
});

test('weather and search distinguish service preparation from adding to a particular agent', () => {
  for (const code of ['get_weather', 'web_search']) {
    const display = pluginPresentation(row(code));
    assert.match(display.preparation, /配置.*测试连接/);
    assert.equal(display.setupTarget, 'functions');
    assert.match(display.setupSteps[1], /选择智能体/);
    assert.match(display.setupSteps[2], /保存智能体配置/);
    assert.doesNotMatch(display.availabilityNote, /此功能已启用|当前可用/);
  }
});

test('dedicated integrations and discontinued voice offer their real setting destinations', () => {
  for (const [code, target] of [['feishu_assistant', 'feishu'], ['search_from_local_knowledge', 'knowledge'],
    ['search_from_ragflow', 'knowledge'], ['set_session_voice', 'voice'], ['manage_memory', 'memory']]) {
    const display = pluginPresentation(row(code, catalogOnly));
    assert.equal(display.setupTarget, target);
    assert.notEqual(display.setupLabel, '添加到智能体');
  }
  assert.equal(pluginPresentation(row('change_role')).setupTarget, null);
  assert.match(pluginPresentation(row('set_session_voice')).setupSteps.join(' '), /不能恢复/);
});

test('unknown restricted integrations never acquire a misleading plugin enable action', () => {
  for (const plugin of [row('future_binding', catalogOnly), row('future_binding', [], { selectable: false }),
    row('future_binding', [], { activationMode: 'binding' })]) {
    assert.equal(pluginPresentation(plugin).section, 'integrations');
    assert.equal(pluginPresentation(plugin).setupTarget, null);
  }
  assert.equal(pluginPresentation(row('future_control', [], { activationMode: 'runtime' })).section, 'system');
  assert.equal(pluginPresentation(row('future_retired', [], { lifecycle: 'retired' })).setupTarget, null);
});

test('new activities use the existing catalog without creating presets or changing role authorization', () => {
  const activity = row('story_time', [{ key: 'custom_plugin', default: 'true' }]);
  const before = JSON.stringify(activity);
  assert.equal(pluginPresentation(activity).section, 'activities');
  assert.equal(pluginPresentation(activity).setupTarget, 'functions');
  assert.deepEqual(filterCatalogPlugins([activity], { section: 'activities' }), [activity]);
  assert.equal(JSON.stringify(activity), before);
});

test('plain-language search stays within its section and finds user-facing descriptions', () => {
  assert.deepEqual(filterCatalogPlugins(productionShape, { search: '天气' }).map(item => item.providerCode), ['get_weather']);
  assert.equal(filterCatalogPlugins(productionShape, { category: 'device' }).length, 3);
  assert.equal(filterCatalogPlugins(productionShape, { search: 'direct_answer' }).length, 0);
  assert.equal(filterCatalogPlugins(productionShape, { section: 'system', search: 'direct_answer' }).length, 1);
  assert.equal(filterCatalogPlugins(productionShape, { section: 'integrations', search: '资料' }).length, 2);
});

test('catalog API failures return to the caller instead of leaving loading stuck or retrying a different endpoint', () => {
  let errorHandler, networkHandler, response, error, retries = 0;
  const request = {
    url() { return this; }, method() { return this; }, success() { return this; },
    fail(handler) { errorHandler = handler; return this; }, networkFail(handler) { networkHandler = handler; return this; }, send() {},
  };
  const module = loadModule('src/apis/module/model.js', {
    '../api': { getServiceUrl: () => '/xiaozhi' },
    '../httpRequest': { __esModule: true, default: { sendRequest: () => request,
      clearRequestTime() {}, reAjaxFun() { retries++; } } },
  }).default;
  module.getModelProvidersPage({ modelType: 'Plugin' }, value => { response = value; }, value => { error = value; });
  const failure = new Error('network unavailable'); networkHandler(failure);
  assert.equal(error, failure); assert.equal(response, undefined);
  module.getModelProvidersPage({}, value => { response = value; }); errorHandler(failure);
  assert.equal(response.data.code, -1); assert.equal(retries, 0);
});

test('service configuration read, save, test and delete report business failures to release their busy state', () => {
  let errorHandler;
  const request = {
    url() { return this; }, method() { return this; }, data() { return this; }, success() { return this; },
    fail(handler) { errorHandler = handler; return this; }, networkFail() { return this; }, send() {},
  };
  const module = loadModule('src/apis/module/user.js', {
    '../api': { getServiceUrl: () => '/xiaozhi' },
    '../httpRequest': { __esModule: true, default: { sendRequest: () => request } },
  }).default;
  const failure = { data: { code: 500, msg: 'Configuration unavailable' } };
  for (const [method, args] of [
    ['getPluginConfigs', []], ['getPluginConfig', ['get_weather']],
    ['savePluginConfig', ['get_weather', {}]], ['testPluginConfig', ['get_weather', {}]],
    ['deletePluginConfig', ['get_weather']],
  ]) {
    errorHandler = undefined;
    let response;
    module[method](...args, value => { response = value; });
    assert.equal(typeof errorHandler, 'function', method);
    errorHandler(failure);
    assert.equal(response, failure, method);
  }
});
