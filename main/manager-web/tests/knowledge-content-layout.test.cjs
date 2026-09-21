const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const root = path.resolve(__dirname, '..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');

test('knowledge content editor labels are isolated from global native label styling', () => {
  const source = read('src/components/knowledge/content/EntryEditorPanel.vue');
  assert.doesNotMatch(source, /<label(?:\s|>)/);
  assert.match(source, /class="field-label"/);
  assert.match(source, /<style scoped>/);
  assert.match(source, /v-if="!draft && editable"/);
  assert.match(source, /:disabled="!editable"/);
  assert.match(source, /class="editor-scroll"/);
  assert.match(source, /\.editor-scroll\s*\{[^}]*min-height:0/);
});

test('list titles are excerpts only and editor preserves structured-looking text', () => {
  const panel = loadVueComponent('src/components/knowledge/content/EntryListPanel.vue');
  const content = '价格：28\n备注：http://example.test/a:b\n正文保留：多行内容';
  const entry = { content };
  assert.equal(panel.methods.summary(entry), '价格：28');
  assert.equal(entry.content, content);
  const editor = loadVueComponent('src/components/knowledge/content/EntryEditorPanel.vue');
  let event, changed;
  const draft = { mode: 'text', content, expectedVersion: 4, active: true };
  editor.methods.update.call({ draft, $emit(name, value) { event = name; changed = value; } }, 'content', content + '\n新行');
  assert.equal(event, 'draft-change');
  assert.equal(changed.expectedVersion, 4);
  assert.equal(changed.content, content + '\n新行');
  assert.equal(draft.content, content);
});

test('content management has a protected independent route and split workspace, not a full-page dialog', () => {
  const router = read('src/router/index.js');
  assert.match(router, /path: '\/knowledge-content-management'/);
  assert.match(router, /const protectedRoutes = [^\n]*'KnowledgeContentManagement'/);
  const page = read('src/views/KnowledgeContentManagement.vue');
  const workspace = read('src/components/knowledge/content/KnowledgeContentWorkspace.vue');
  assert.doesNotMatch(page, /<el-dialog/);
  assert.doesNotMatch(workspace, /<el-dialog/);
  assert.match(workspace, /<entry-list-panel/);
  assert.match(workspace, /<entry-editor-panel/);
  assert.doesNotMatch(workspace, /entry-history-dialog|showHistory|restoreRevision|entryRevisions/);
  assert.doesNotMatch(read('src/components/knowledge/content/EntryEditorPanel.vue'), /修改历史|恢复此版本|\$emit\('history'/);
  assert.doesNotMatch(read('src/apis/module/knowledgeBase.js'), /entryRevisions|restoreEntry/);
  assert.equal(fs.existsSync(path.join(root, 'src/components/knowledge/content/EntryHistoryDialog.vue')), false);
  assert.match(workspace, /grid-template-columns:/);
  assert.match(workspace, /grid-template-rows:minmax\(0,1fr\)/);
  assert.match(page, /:readonly="readOnly"/);
  const upload = read('src/views/KnowledgeFileUpload.vue');
  assert.match(upload, /name: 'KnowledgeContentManagement'/);
  assert.doesNotMatch(upload, /<file-preview-dialog|sliceDialogVisible/);
});

test('cancelled route navigation is handled without an unhandled rejection', async () => {
  const component = loadVueComponent('src/views/KnowledgeContentManagement.vue', {
    'vue-router': { isNavigationFailure: error => error.type === 'cancelled' }
  });
  let alerts = 0;
  const vm = { datasetId:'dataset', knowledgeBaseName:'demo', readOnly:false,
    $router:{push:async()=>{throw {type:'cancelled'};}}, $message:{error(){alerts++;}} };
  await component.methods.returnToDocuments.call(vm, false);
  assert.equal(alerts,0);
  vm.$router.push = async()=>{throw new Error('unexpected');};
  await component.methods.returnToDocuments.call(vm, false);
  assert.equal(alerts,1);
});

test('route leave and same-view document navigation delegate to the workspace guard', async () => {
  const component = loadVueComponent('src/views/KnowledgeContentManagement.vue');
  let nextValue = 'unset';
  const vm = { $refs: { workspace: { confirmLeave: async () => false } } };
  vm.checkLeave = component.methods.checkLeave.bind(vm);
  await vm.checkLeave(value => { nextValue = value; });
  assert.equal(nextValue, false);
  vm.$refs.workspace.confirmLeave = async () => true;
  await vm.checkLeave(value => { nextValue = value; });
  assert.equal(nextValue, undefined);
  let delegated = 0;
  const hookVm = { checkLeave(next) { delegated++; next(false); } };
  for (const hook of ['beforeRouteLeave', 'beforeRouteUpdate']) {
    component[hook].call(hookVm, {}, {}, value => { assert.equal(value, false); });
  }
  assert.equal(delegated, 2);
});
