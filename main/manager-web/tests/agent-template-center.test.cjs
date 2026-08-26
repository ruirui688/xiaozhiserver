const assert = require('node:assert/strict');
const { describe, test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue/dist/vue.common.js');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');

function createVm(component, propsData = {}) {
  const Ctor = Vue.extend({
    ...component,
    render(createElement) { return createElement('div'); }
  });
  return new Ctor({ propsData });
}

describe('agent template center', () => {
  test('create API uses the atomic template endpoint and version contract', () => {
    const state = {};
    const request = {
      url(value) { state.url = value; return request; },
      method(value) { state.method = value; return request; },
      data(value) { state.data = value; return request; },
      success() { return request; },
      fail() { return request; },
      networkFail() { return request; },
      send() { return request; }
    };
    const api = loadModule('src/apis/module/agent.js', {
      '../api': { getServiceUrl: () => '/xiaozhi' },
      '../httpRequest': { __esModule: true, default: { sendRequest: () => request } }
    }).default;

    api.createAgentFromTemplate('official-1', '新军师', 4, () => {});

    assert.equal(state.url, '/xiaozhi/agent/templates/official-1/create');
    assert.equal(state.method, 'POST');
    assert.deepEqual(state.data, { agentName: '新军师', expectedVersion: 4 });

    api.getAgentTemplatePreview('official-1', () => {});
    assert.equal(state.url, '/xiaozhi/agent/templates/official-1/preview');
    assert.equal(state.method, 'GET');
  });

  test('template center separates official, marketplace and workspace templates', () => {
    const component = loadVueComponent('src/components/home/AgentTemplateCenter.vue');
    const vm = createVm(component, {
      templates: [
        { id: '1', templateName: '官方军师', scope: 'OFFICIAL' },
        { id: '2', templateName: '玩家军师', scope: 'PUBLIC' },
        { id: '3', templateName: '私有军师', scope: 'WORKSPACE' }
      ]
    });

    vm.activeScope = 'OFFICIAL';
    assert.deepEqual(vm.filteredTemplates.map(item => item.id), ['1']);
    vm.activeScope = 'ALL';
    vm.keyword = '玩家';
    assert.deepEqual(vm.filteredTemplates.map(item => item.id), ['2']);
    vm.$destroy();
  });

  test('template center previews portable speech bindings and exposes official maintenance', () => {
    const root = path.resolve(__dirname, '..');
    const center = fs.readFileSync(path.join(root, 'src/components/home/AgentTemplateCenter.vue'), 'utf8');
    const preview = fs.readFileSync(path.join(root, 'src/components/home/AgentTemplatePreviewDialog.vue'), 'utf8');
    const home = fs.readFileSync(path.join(root, 'src/views/home.vue'), 'utf8');

    assert.match(center, /查看预览/);
    assert.match(center, /\$emit\('preview', template\)/);
    assert.doesNotMatch(center, /\$emit\('create', template\)/);
    assert.match(preview, /角色设定/);
    assert.match(preview, /模型与能力/);
    assert.match(preview, /预览本身不会调用模型或产生模型费用/);
    assert.match(preview, /复制已校验的 TTS 模型与音色/);
    assert.match(preview, /编辑官方模板/);
    assert.match(preview, /officialMaintainer/);
    assert.match(home, /getAgentTemplatePreview/);
    assert.match(home, /template-quick-config/);
  });

  test('official template editor persists model, voice and official knowledge snapshot', () => {
    const root = path.resolve(__dirname, '..');
    const editor = fs.readFileSync(path.join(root, 'src/views/TemplateQuickConfig.vue'), 'utf8');

    assert.match(editor, /TTS 音色/);
    assert.match(editor, /官方知识库/);
    assert.match(editor, /getModelVoices/);
    assert.match(editor, /scope: "OFFICIAL"/);
    assert.match(editor, /snapshotJson: \{ schemaVersion: 2, config \}/);
    assert.match(editor, /repairMojibake/);
  });

  test('home exposes a bounded runtime monitor instead of raw server logs', () => {
    const root = path.resolve(__dirname, '..');
    const home = fs.readFileSync(path.join(root, 'src/views/home.vue'), 'utf8');
    const device = fs.readFileSync(path.join(root, 'src/components/DeviceItem.vue'), 'utf8');
    const monitor = fs.readFileSync(path.join(root, 'src/components/home/AgentRuntimeMonitorDialog.vue'), 'utf8');

    assert.match(device, /运行监控/);
    assert.match(device, /runtime-monitor/);
    assert.match(home, /getAgentSessions/);
    assert.match(home, /setInterval\(\(\) =>/);
    assert.match(monitor, /不暴露服务器原始日志/);
    assert.match(monitor, /每 5 秒自动刷新/);
    assert.match(monitor, /Token 和模型密钥不会进入运行监控/);
  });
});
