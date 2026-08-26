const assert = require('node:assert/strict');
const { describe, test } = require('node:test');
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
});
