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
const personCard = read('src/components/person/PersonProfileCard.vue');
const personVoiceprint = read('src/components/person/PersonVoiceprintPanel.vue');
const voicePrintDialog = read('src/components/VoicePrintDialog.vue');
const voicePrintView = read('src/views/VoicePrint.vue');
const personApi = read('src/apis/module/person.js');
const agentApi = read('src/apis/module/agent.js');
const plugins = read('src/views/FunctionPluginManagement.vue');
const pluginCard = read('src/components/resource/PluginResourceCard.vue');
const pluginDetail = read('src/components/resource/PluginDetailDialog.vue');
const pluginConfigDialog = read('src/components/UserPluginConfigDialog.vue');
const userApi = read('src/apis/module/user.js');

test('user resource center groups private resources while operations owns the tool catalog', () => {
  assert.match(router, /person-profile-management/);
  assert.match(header, />资源中心</);
  assert.match(nav, /label: "知识库"/);
  assert.match(nav, /label: "用户信息"/);
  assert.doesNotMatch(nav, /function-plugin-management/);
  assert.match(read('src/ops/router.js'), /function-plugin-management/);
  assert.doesNotMatch(nav, /RESOURCE CENTER|resource-nav__intro|统一维护可复用/);
  assert.doesNotMatch(knowledge, /<h2 class="page-title">/);
  assert.match(people, /<h1>用户信息<\/h1>/);
  assert.match(people, /不是后台登录账号/);
  assert.doesNotMatch(plugins, /资源中心 \/ 插件管理|<h1 class="page-title">插件管理<\/h1>/);
});

test('person UI uses grouped user profile cards while keeping the device-local face boundary explicit', () => {
  assert.match(people, /设备本地识别/);
  assert.match(people, /服务端识别未启用/);
  assert.match(people, /新增用户信息/);
  assert.match(people, /上传正面照片/);
  assert.match(people, /绑定智能体/);
  assert.match(people, /显式发布/);
  assert.match(people, /上传不会下发/);
  assert.match(people, /识别后动作/);
  assert.match(people, /识别后说一句话/);
  assert.match(people, /class="person-groups"/);
  assert.match(people, /class="person-grid"/);
  assert.match(people, /<PersonProfileCard/);
  assert.match(people, /<PersonVoiceprintPanel/);
  assert.match(people, /<PersonReceptionDialog/);
  assert.match(people, /<ReceptionWorkspaceDialog/);
  assert.match(personCard, /人脸已上传/);
  assert.match(personCard, /声纹已录入/);
  assert.match(personCard, /:disabled="!canWrite"/);
  assert.doesNotMatch(people, /person-list/);
  assert.doesNotMatch(people, /PersonDeviceTargetSelector|form\.deviceIds|下发设备/);
  assert.doesNotMatch(people, /pipeline-notice/);
  assert.match(personApi, /resource\/persons\/\$\{encodeURIComponent\(id\)\}\/face/);
  assert.match(people, /this\.editingId = profile\.id/);
  assert.match(people, /人物资料已保存，但照片上传失败，请重试/);
  assert.doesNotMatch(personApi, /api[_-]?key|access[_-]?token/i);
});

test('person voiceprint UI supports workspace options, explicit linkage and destructive warnings', () => {
  assert.match(personVoiceprint, /声纹信息/);
  assert.match(personVoiceprint, /录入新声纹/);
  assert.match(personVoiceprint, /关联已有声纹/);
  assert.match(personVoiceprint, /解除关联/);
  assert.match(personVoiceprint, /删除声纹/);
  assert.match(personVoiceprint, /仍可关联工作空间内已有声纹/);
  assert.match(personVoiceprint, /Api\.person\.voicePrintOptions\(context\.personId/);
  assert.match(personVoiceprint, /filter\(item => !item\.personId \|\| item\.personId === context\.personId\)/);
  assert.doesNotMatch(personVoiceprint, /getAgentVoicePrintList/);
  assert.match(personApi, /voicePrintOptions\(personId, callback, failCallback\)/);
  assert.match(personApi, /resource\/persons\/\$\{encodeURIComponent\(personId\)\}\/voice-print-options/);
  assert.match(personApi, /method, path, data, callback, failCallback/);
  assert.match(personApi, /'PUT',[\s\S]*voice-print\/\$\{encodeURIComponent\(voicePrintId\)\}/);
  assert.match(personApi, /'DELETE',[\s\S]*resource\/persons\/\$\{encodeURIComponent\(personId\)\}\/voice-print/);
  assert.match(voicePrintDialog, /this\.\$emit\('completed', \{\s*voicePrintId,/);
  assert.match(voicePrintDialog, /voicePrintId,[\s\S]*completedByEnrollment: true/);
  assert.match(personVoiceprint, /删除会同时移除远程声纹特征、所有智能体绑定以及当前用户关联，且无法恢复/);
  assert.doesNotMatch(personVoiceprint, /reload: true/);
});

test('person voiceprint callbacks stay scoped to the dialog person that started the operation', () => {
  assert.match(people, /:active="dialogVisible"/);
  assert.match(people, /:key="editingId \|\| 'new-person'"/);
  assert.match(people, /personId !== this\.editingId/);
  assert.match(personVoiceprint, /contextGeneration/);
  assert.match(personVoiceprint, /context\.personId === this\.personId/);
  assert.match(personVoiceprint, /context\.generation === this\.contextGeneration/);
  assert.match(personVoiceprint, /Api\.person\.bindVoicePrint\(context\.personId/);
  assert.match(personVoiceprint, /Api\.person\.unbindVoicePrint\(context\.personId/);
  assert.match(personVoiceprint, /const voicePrintId = this\.voicePrint\.id/);
  assert.match(personVoiceprint, /\$emit\("changed", \{ personId: context\.personId/);
  assert.match(personVoiceprint, /:disabled="busy \|\| voiceDialogVisible"/);
  assert.match(personVoiceprint, /:agent-id="dialogAgentId"/);
  assert.match(personVoiceprint, /captureContext\(\{ agentId \}\)/);
});

test('person save callbacks keep the original edit target and face file', () => {
  assert.match(people, /:before-close="beforeEditorClose"/);
  assert.match(people, /:disabled="saving \|\| voiceOperationBusy" @click="closeEditor"/);
  assert.match(people, /editSessionGeneration/);
  assert.match(people, /operationId: \+\+this\.activeSaveId/);
  assert.match(people, /faceFile: this\.faceFile/);
  assert.match(people, /Api\.person\.uploadFace\(profile\.id, context\.faceFile/);
  assert.match(people, /Api\.person\.update\(context\.targetId/);
  assert.match(people, /context\.generation === this\.editSessionGeneration/);
  assert.match(people, /context\.targetId === this\.editingId/);
});

test('person editor fails closed and ignores stale profile, face and enrollment work', () => {
  assert.match(people, /canWrite: false/);
  assert.match(people, /Api\.workspace\.current/);
  assert.match(people, /requestId !== this\.profileRequestId/);
  assert.match(people, /generation !== this\.faceLoadGeneration/);
  assert.match(people, /URL\.revokeObjectURL\(previousUrl\)/);
  assert.match(people, /该用户已关联声纹，请先在用户信息中解除关联或删除声纹/);
  assert.match(personVoiceprint, /loadingDevices/);
  assert.match(voicePrintDialog, /cancelActiveEnrollment/);
  assert.match(voicePrintDialog, /isEnrollmentContextActive/);
  assert.match(voicePrintDialog, /cancelStaleEnrollment/);
  assert.match(voicePrintDialog, /deactivateEnrollmentLifecycle/);
  assert.match(voicePrintDialog, /:visible="visible"/);
  assert.doesNotMatch(voicePrintDialog, /:visible\.sync="visible"/);
  assert.match(voicePrintDialog, /:close-on-press-escape="!saving && !completingEnrollment"/);
  assert.match(voicePrintDialog, /:before-close="beforeDialogClose"/);
  assert.doesNotMatch(voicePrintDialog, /setTimeout\(\(\) => \{\s*this\.saving = false/);
  assert.doesNotMatch(personVoiceprint, /:visible\.sync="voiceDialogVisible"/);
  assert.match(voicePrintDialog, /\$emit\('busy-change', true\)/);
  assert.match(people, /voiceOperationBusy/);
  assert.match(people, /声纹操作正在处理，请稍候/);
});

test('voiceprint metadata update releases busy state on transport failures', () => {
  assert.match(agentApi, /updateAgentVoicePrint\(voicePrintData, callback, failCallback\)/);
  assert.match(agentApi, /if \(failCallback\) \{[\s\S]*request\.fail/);
  assert.match(agentApi, /networkFail\(\(err\) => \{[\s\S]*failCallback\(err\)/);
  assert.match(agentApi, /this\.updateAgentVoicePrint\(voicePrintData, callback\)/);
  assert.match(personVoiceprint, /done && done\(\);\s*if \(!this\.isContextActive\(context\)\) return;\s*this\.updating = false;\s*this\.setVoiceOperationBusy\(false\);\s*this\.\$message\.error/);
  assert.match(voicePrintView, /updateAgentVoicePrint\(payload,[\s\S]*\}, \(err\) => \{[\s\S]*done && done\(\);/);
});

test('plugin catalog uses task-oriented cards and keeps admin mutations gated', () => {
  assert.match(plugins, /<PluginResourceCard/);
  assert.match(plugins, /v-if="canManagePlugins"/);
  assert.match(plugins, /getPluginFunctionList/);
  assert.match(plugins, /可添加插件/);
  assert.match(plugins, /专用集成/);
  assert.match(plugins, /高级目录/);
  assert.match(plugins, /列表视图/);
  assert.match(plugins, /卡片视图/);
  assert.match(plugins, /默认排序/);
  assert.match(plugins, /overflow-y: auto/);
  assert.doesNotMatch(plugins, /\.welcome\s*\{[^}]*overflow:\s*hidden/s);
  assert.doesNotMatch(pluginCard.split('</template>')[0], /注入阶段|编辑调用说明|plugin\.providerCode/);
  assert.match(pluginCard, /使用说明/);
  assert.match(pluginCard, /plugin-resource-card--list/);
  assert.match(pluginDetail, /高级：技术说明与维护/);
  assert.match(pluginDetail, /复制玩法/);
  assert.match(pluginDetail, /删除玩法/);
  assert.match(pluginCard, /需要配置服务密钥/);
  assert.match(pluginCard, /服务密钥已保存/);
  assert.match(pluginDetail, /不代表连接测试已通过/);
  assert.match(pluginCard, /\$emit\('setup', plugin\)/);
  assert.match(pluginCard, /\$emit\('configure', plugin\)/);
  assert.match(pluginCard, /\$emit\('test', plugin\)/);
  assert.match(plugins, /<UserPluginConfigDialog/);
  assert.match(plugins, /<PluginSetupDialog/);
  assert.match(plugins, /fetchSharedConfigStatuses/);
  assert.match(plugins, /testSharedConfig/);
  assert.match(pluginConfigDialog, /测试连接/);
  assert.match(userApi, /plugin-configs\/\$\{encodeURIComponent\(providerCode\)\}\/test/);
  assert.doesNotMatch(pluginConfigDialog, /console\.(log|info|debug).*api_key/i);
});
