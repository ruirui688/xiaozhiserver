const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');

const router = read('src/router/index.js');
const header = read('src/components/HeaderBar.vue');
const nav = read('src/components/resource/ResourceCenterNav.vue');
const knowledge = read('src/views/KnowledgeBaseManagement.vue');
const people = read('src/views/PersonProfileManagement.vue');
const personApi = read('src/apis/module/person.js');
const plugins = read('src/views/FunctionPluginManagement.vue');
const pluginCard = read('src/components/resource/PluginResourceCard.vue');
const pluginConfigDialog = read('src/components/UserPluginConfigDialog.vue');
const userApi = read('src/apis/module/user.js');

test('resource center groups knowledge, people and plugins', () => {
  assert.match(router, /person-profile-management/);
  assert.match(header, />资源中心</);
  assert.match(nav, /label: "知识库"/);
  assert.match(nav, /label: "人物与人脸"/);
  assert.match(nav, /label: "插件"/);
  assert.doesNotMatch(nav, /RESOURCE CENTER|resource-nav__intro|统一维护可复用/);
  assert.doesNotMatch(knowledge, /<h2 class="page-title">/);
  assert.doesNotMatch(people, /<h1>人物与人脸<\/h1>/);
  assert.doesNotMatch(plugins, /资源中心 \/ 插件管理|<h1 class="page-title">插件管理<\/h1>/);
});

test('person UI makes device-local biometric boundary explicit', () => {
  assert.match(people, /设备本地识别/);
  assert.match(people, /服务端识别未启用/);
  assert.match(people, /新增人物/);
  assert.match(people, /上传正面照片/);
  assert.match(people, /绑定智能体/);
  assert.match(people, /显式发布/);
  assert.match(people, /上传不会下发/);
  assert.match(people, /识别后动作/);
  assert.match(people, /识别后说一句话/);
  assert.match(people, /person-list/);
  assert.doesNotMatch(people, /PersonDeviceTargetSelector|form\.deviceIds|下发设备/);
  assert.doesNotMatch(people, /用户信息管理|pipeline-notice|person-grid/);
  assert.match(personApi, /resource\/persons\/\$\{encodeURIComponent\(id\)\}\/face/);
  assert.match(people, /this\.editingId = profile\.id/);
  assert.match(people, /人物资料已保存，但照片上传失败，请重试/);
  assert.doesNotMatch(personApi, /api[_-]?key|access[_-]?token/i);
});

test('plugin catalog uses task-oriented cards and keeps admin mutations gated', () => {
  assert.match(plugins, /<PluginResourceCard/);
  assert.match(plugins, /v-if="canManagePlugins"/);
  assert.match(plugins, /getPluginFunctionList/);
  assert.match(plugins, /能力类型/);
  assert.match(plugins, /列表视图/);
  assert.match(plugins, /卡片视图/);
  assert.match(plugins, /按后台排序/);
  assert.match(plugins, /overflow-y: auto/);
  assert.doesNotMatch(plugins, /\.welcome\s*\{[^}]*overflow:\s*hidden/s);
  assert.match(pluginCard, /用户可以说/);
  assert.match(pluginCard, /查看说明/);
  assert.match(pluginCard, /plugin-resource-card--list/);
  assert.match(pluginCard, /编辑/);
  assert.match(pluginCard, /复制玩法/);
  assert.match(pluginCard, /删除/);
  assert.match(pluginCard, /待配置密钥/);
  assert.match(pluginCard, /密钥已配置/);
  assert.match(pluginCard, /\$emit\('configure', plugin\)/);
  assert.match(pluginCard, /\$emit\('test', plugin\)/);
  assert.match(plugins, /<UserPluginConfigDialog/);
  assert.match(plugins, /fetchSharedConfigStatuses/);
  assert.match(plugins, /testSharedConfig/);
  assert.match(pluginConfigDialog, /测试连接/);
  assert.match(userApi, /plugin-configs\/\$\{encodeURIComponent\(providerCode\)\}\/test/);
  assert.doesNotMatch(pluginConfigDialog, /console\.(log|info|debug).*api_key/i);
});
