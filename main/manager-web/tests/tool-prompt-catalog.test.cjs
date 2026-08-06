const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');
const resourceCenter = fs.readFileSync(
  path.join(root, 'src/views/FunctionPluginManagement.vue'),
  'utf8'
);
const roleEditor = fs.readFileSync(
  path.join(root, 'src/components/role/RoleEditDialog.vue'),
  'utf8'
);
const card = fs.readFileSync(
  path.join(root, 'src/components/resource/PluginResourceCard.vue'),
  'utf8'
);
const catalogMigration = fs.readFileSync(
  path.join(root, '../manager-api/src/main/resources/db/changelog/202608061600.sql'),
  'utf8'
);

test('resource center exposes a prompt editor for every manageable tool', () => {
  assert.match(resourceCenter, /编辑大模型工具提示词/);
  assert.match(resourceCenter, /saveToolPrompt\(\)/);
  assert.match(resourceCenter, /maxlength="1000"/);
  assert.match(card, /编辑提示词/);
  assert.match(card, /\$emit\('edit-prompt', plugin\)/);
});

test('runtime builtins stay visible in catalog but out of role selection', () => {
  assert.match(resourceCenter, /isRuntimeBuiltin\(plugin\)/);
  assert.match(resourceCenter, /连接建立后自动启用/);
  assert.match(resourceCenter, /getActivationHint\(currentPlugin\)/);
  assert.match(roleEditor, /field\.key === "catalog_only"/);
});

test('resource catalog includes every statically registered server tool', () => {
  const providerCodes = [
    'direct_answer',
    'handle_exit_intent',
    'set_session_voice',
    'reset_conversation_context',
    'get_lunar',
    'feishu_assistant',
    'search_from_local_knowledge',
    'search_from_ragflow',
    'change_role',
    'get_weather',
    'play_music',
    'get_news_from_chinanews',
    'get_news_from_newsnow',
    'hass_get_state',
    'hass_set_state',
    'hass_play_music',
    'call_device',
    'meeting_minutes',
    'web_search'
  ];
  for (const providerCode of providerCodes) {
    assert.match(catalogMigration, new RegExp(`'${providerCode}'`));
  }
});
