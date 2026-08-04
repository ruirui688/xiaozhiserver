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
    assert.match(dialogSource, /template\.scope === 'WORKSPACE'/);
    assert.match(dialogSource, /@click\.stop="deleteTemplate\(template\)"/);
  });

  await t.test('read-only workspace members cannot create or apply templates', () => {
    assert.match(pageSource, /Api\.workspace\.current/);
    assert.match(pageSource, /v-if="workspaceCanWrite"/);
    assert.match(pageSource, /当前工作空间为只读，不能创建角色模板/);
    assert.match(dialogSource, /'template-disabled': !workspaceCanWrite/);
    assert.match(dialogSource, /当前工作空间为只读，不能切换角色模板/);
  });
});
