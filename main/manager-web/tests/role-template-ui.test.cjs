const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..');
const apiSource = fs.readFileSync(path.join(root, 'src/apis/module/agent.js'), 'utf8');
const pageSource = fs.readFileSync(path.join(root, 'src/views/roleConfig.vue'), 'utf8');
const dialogSource = fs.readFileSync(
  path.join(root, 'src/components/role/RoleEditDialog.vue'),
  'utf8'
);
const switcherSource = fs.readFileSync(
  path.join(root, 'src/components/role/RoleTemplateSwitcher.vue'),
  'utf8'
);
const tabsSource = fs.readFileSync(
  path.join(root, 'src/components/role/RoleConfigTaskTabs.vue'),
  'utf8'
);
const presentationSource = fs.readFileSync(
  path.join(root, 'src/utils/roleConfigPresentation.js'),
  'utf8'
);
const basicSettingsSource = fs.readFileSync(
  path.join(root, 'src/components/role/RoleBasicSettingsPanel.vue'),
  'utf8'
);

test('workspace role template UI', async (t) => {
  await t.test('write APIs use dedicated endpoints and do not retry uncertain mutations', () => {
    assert.match(apiSource, /agent\/\$\{agentId\}\/templates/);
    assert.match(apiSource, /templates\/\$\{templateId\}\/apply/);
    assert.match(apiSource, /deleteWorkspaceAgentTemplate/);

    for (const method of ['saveAgentAsTemplate', 'applyAgentTemplate', 'deleteWorkspaceAgentTemplate']) {
      const start = apiSource.indexOf(`${method}(`);
      const end = apiSource.indexOf('\n    },', start);
      const block = apiSource.slice(start, end);
      assert.doesNotMatch(block, /reAjaxFun/);
      assert.match(block, /failCallback/);
    }
  });

  await t.test('save-as captures the draft and tags while explaining secure integration switching', () => {
    assert.match(pageSource, /另存为角色模板/);
    assert.match(pageSource, /submitSaveConfig\(agentId\)/);
    assert.match(pageSource, /tagNames: this\.dynamicTags/);
    assert.match(pageSource, /声纹绑定和飞书配置会随模板切换/);
    assert.match(pageSource, /飞书密钥只加密保存/);
    assert.match(pageSource, /敏感请求头不会保存/);
  });

  await t.test('template click confirms immediate application and hydrates authoritative response', () => {
    assert.match(pageSource, /确认切换/);
    assert.match(pageSource, /声纹绑定和飞书集成会一起替换/);
    assert.match(pageSource, /applyAgentTemplate\(template\)/);
    assert.match(pageSource, /hydrateAgentConfig\(agentData\)/);
    assert.match(pageSource, /skippedItems/);
    assert.match(switcherSource, /template\.scope === 'WORKSPACE'/);
    assert.match(switcherSource, /@click="\$emit\('delete', selectedTemplate\)"/);
    assert.match(switcherSource, /@click="applySelected"/);
  });

  await t.test('read-only workspace members cannot create or apply templates', () => {
    assert.match(pageSource, /Api\.workspace\.current/);
    assert.match(pageSource, /v-if="workspaceCanWrite"/);
    assert.match(pageSource, /当前工作空间为只读，不能创建角色模板/);
    assert.match(switcherSource, /:disabled="!selectedTemplate \|\| !workspaceCanWrite"/);
    assert.match(dialogSource, /当前工作空间为只读，不能切换角色模板/);
  });

  await t.test('role editor explains template impact and groups long settings', () => {
    assert.match(switcherSource, /aria-label="角色模板切换"/);
    assert.doesNotMatch(switcherSource, /template-list|template-card|template-copy|一次切换模型/);
    assert.match(dialogSource, /class="role-control-bar"/);
    assert.match(dialogSource, /<role-config-task-tabs v-model="activePanel"/);
    assert.match(dialogSource, /<role-basic-settings-panel/);
    assert.match(basicSettingsSource, /class="basic-config-grid"/);
    assert.match(basicSettingsSource, /grid-template-columns: minmax\(190px, 260px\) minmax\(0, 1fr\)/);
    assert.match(basicSettingsSource, /:rows="12"/);
    assert.match(basicSettingsSource, /resize="vertical"/);
    assert.match(tabsSource, /aria-label="角色配置任务导航"/);
    assert.match(tabsSource, /ROLE_CONFIG_PANELS/);
    assert.match(presentationSource, /基础设定/);
    assert.match(presentationSource, /语音链路/);
    assert.match(presentationSource, /模型与能力/);
    assert.match(presentationSource, /记忆与记录/);
    assert.match(presentationSource, /外部集成/);
    assert.match(dialogSource, /showLegacySummaryMemory/);
    assert.match(dialogSource, /Memory_mem_local_short/);
    assert.match(presentationSource, /主动记忆会在会话结束后自动整理/);
    assert.match(dialogSource, /管理上下文源/);
    assert.match(dialogSource, /管理飞书配置/);
    assert.match(pageSource, /class="config-toolbar"/);
  });
});
