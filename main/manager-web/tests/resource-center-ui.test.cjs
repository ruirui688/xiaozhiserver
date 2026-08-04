const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

const router = read('src/router/index.js');
const header = read('src/components/HeaderBar.vue');
const nav = read('src/components/resource/ResourceCenterNav.vue');
const people = read('src/views/PersonProfileManagement.vue');
const personApi = read('src/apis/module/person.js');
const plugins = read('src/views/FunctionPluginManagement.vue');
const pluginCard = read('src/components/resource/PluginResourceCard.vue');

test('resource center groups knowledge, people and plugins', () => {
  assert.match(router, /person-profile-management/);
  assert.match(header, />资源中心</);
  assert.match(nav, /知识库管理/);
  assert.match(nav, /用户信息管理/);
  assert.match(nav, /插件管理/);
});

test('person UI makes device-local biometric boundary explicit', () => {
  assert.match(people, /当前识别链路：设备本地计算/);
  assert.match(people, /服务端识别暂未开放/);
  assert.match(people, /下发到设备/);
  assert.match(people, /识别后动作/);
  assert.match(people, /返回设备播报/);
  assert.match(personApi, /resource\/persons\/\$\{encodeURIComponent\(id\)\}\/face/);
  assert.doesNotMatch(personApi, /api[_-]?key|access[_-]?token/i);
});

test('plugin catalog uses task-oriented cards and keeps admin mutations gated', () => {
  assert.match(plugins, /<PluginResourceCard/);
  assert.match(plugins, /v-if="canManagePlugins" type="primary"/);
  assert.match(plugins, /getPluginFunctionList/);
  assert.match(pluginCard, /用户可以说/);
  assert.match(pluginCard, /查看能力说明/);
});
