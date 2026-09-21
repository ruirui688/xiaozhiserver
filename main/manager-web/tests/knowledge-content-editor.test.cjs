const assert = require('node:assert/strict');
const { test } = require('node:test');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');

function fixture(api = {}) {
  const component = loadVueComponent('src/components/knowledge/content/KnowledgeContentWorkspace.vue', {
    '@/apis/module/knowledgeBase': { __esModule: true, default: api }
  });
  const vm = { ...component.data(), datasetId: 'dataset', documentId: 'doc', visible: true, readonly: false,
    $emit() {}, $message: { success() {}, warning() {} }, $confirm: async () => {} };
  for (const [key, fn] of Object.entries(component.methods)) vm[key] = fn.bind(vm);
  for (const [key, fn] of Object.entries(component.computed)) Object.defineProperty(vm, key, { get: () => fn.call(vm) });
  vm.editable = true;
  return vm;
}
const ok = data => ({ data: { code: 0, data } });

test('out of order lists cannot replace a newer document or capability', async () => {
  const requests = [];
  const vm = fixture({ listEntries(...args) { requests.push(args[3]); } });
  const first = vm.fetchEntries();
  vm.documentId = 'other';
  const second = vm.fetchEntries();
  requests[1](ok({ list: [{ id: 'new' }], total: 1, editable: false, reason: '只读' }));
  await second;
  requests[0](ok({ list: [{ id: 'old' }], total: 1, editable: true }));
  await first;
  assert.equal(vm.entries[0].id, 'new');
  assert.equal(vm.canEdit, false);
});
test('QA save preserves optimistic version and clears stale text', async () => {
  let submitted;
  const vm = fixture({ updateEntry(...args) { submitted = args[3]; args[4](ok({})); },
    listEntries(...args) { args[3](ok({ list: [], total: 0, editable: true })); } });
  vm.editEntry({ id: 'entry', editVersion: 7, content: 'old', question: '价格？', answer: '28', enabled: 1 });
  await vm.saveDraft();
  assert.equal(submitted.expectedVersion, 7);
  assert.equal(submitted.content, '');
  assert.equal(submitted.answer, '28');
  assert.equal(vm.draft, null);
});
test('conflict and network failures preserve draft and release saving lock', async () => {
  const vm = fixture({ updateEntry(...args) { args[4]({ data: { code: 409, msg: '版本冲突，请重新加载' } }); } });
  vm.editEntry({ id: 'entry', editVersion: 1, content: 'edited' });
  await vm.saveDraft();
  assert.equal(vm.draft.content, 'edited');
  assert.match(vm.error, /版本冲突/);
  assert.equal(vm.saving, false);
});
test('empty fields are rejected and read-only documents never submit', async () => {
  let calls = 0;
  const vm = fixture({ createEntry() { calls++; } });
  vm.editEntry(); vm.draft.content = '  ';
  await vm.saveDraft();
  assert.match(vm.error, /不能为空/);
  vm.draft.content = 'valid'; vm.readonly = true;
  await vm.saveDraft();
  assert.equal(calls, 0);
});
test('closing while saving is blocked and cancelled discard preserves draft', async () => {
  const vm = fixture();
  let closed = false; vm.$emit = () => { closed = true; };
  vm.editEntry({ content: 'unsaved' }); vm.saving = true;
  await vm.closeDialog(); assert.equal(closed, false);
  vm.saving = false; vm.$confirm = async () => { throw new Error('cancel'); };
  await vm.closeDialog(); assert.equal(closed, false); assert.equal(vm.draft.content, 'unsaved');
});
test('status changes retain body and expected version; restores are explicit', async () => {
  let submitted;
  const vm = fixture({ updateEntry(...args) { submitted = args[3]; args[4](ok({})); },
    listEntries(...args) { args[3](ok({ list: [], editable: true })); } });
  await vm.toggleEntry({ id: 'one', editVersion: 3, content: 'price 28', enabled: 1 });
  assert.equal(submitted.content, 'price 28'); assert.equal(submitted.enabled, 0); assert.equal(submitted.expectedVersion, 3);
});
test('closing context invalidates delayed revision responses', async () => {
  let respond;
  const vm = fixture({ entryRevisions(...args) { respond = args[3]; } });
  const pending = vm.showHistory({ id: 'one' });
  vm.invalidate(); respond(ok([{ id: 'old-history' }])); await pending;
  assert.equal(vm.revisions.length, 0);
});
test('restore sends current version and chosen revision, creating a new version', async () => {
  let submitted;
  const vm = fixture({ restoreEntry(...args) { submitted = args[3]; args[4](ok({ indexStatus: 'pending' })); },
    listEntries(...args) { args[3](ok({ list: [], editable: true, indexStatus: 'pending' })); } });
  vm.historyEntry = { id: 'one', editVersion: 9 };
  vm.revisions = [{ id: 'revision-2', content: 'old', enabled: 0 }]; vm.revisionId = 'revision-2'; vm.historyVisible = true;
  await vm.restoreRevision();
  assert.equal(submitted.expectedVersion, 9); assert.equal(submitted.revisionId, 'revision-2');
  assert.equal(vm.historyVisible, false); assert.match(vm.reason, /下次检索时更新索引；更新失败时使用数据库检索/);
});
test('in-flight save from a closed context cannot clear another document draft', async () => {
  let respond;
  const vm = fixture({ updateEntry(...args) { respond = args[4]; } });
  vm.editEntry({ id: 'one', content: 'old document' }); const pending = vm.saveDraft();
  vm.invalidate(); vm.saving = false; vm.editEntry({ id: 'two', content: 'new document' });
  respond(ok({})); await pending;
  assert.equal(vm.draft.id, 'two');
});

test('route leave blocks saving and cancelled discard without modifying the draft', async () => {
  const vm = fixture();
  assert.equal(await vm.confirmLeave(), true);
  vm.editEntry({ id: 'one', content: 'unsaved' });
  vm.saving = true;
  assert.equal(await vm.confirmLeave(), false);
  vm.saving = false;
  vm.$confirm = async () => { throw new Error('cancel'); };
  assert.equal(await vm.confirmLeave(), false);
  assert.equal(vm.draft.content, 'unsaved');
  vm.$confirm = async () => {};
  assert.equal(await vm.confirmLeave(), true);
});

test('switching selection cannot silently discard a draft or apply a late confirmation', async () => {
  const vm = fixture();
  vm.editEntry({ id: 'one', content: 'unsaved' });
  vm.$confirm = async () => { throw new Error('cancel'); };
  await vm.selectEntry({ id: 'two' });
  assert.equal(vm.selectedId, 'one'); assert.equal(vm.draft.content, 'unsaved');
  let approve;
  vm.$confirm = () => new Promise(resolve => { approve = resolve; });
  const pending = vm.selectEntry({ id: 'two' });
  vm.invalidate(); approve(); await pending;
  assert.equal(vm.selectedId, 'one'); assert.equal(vm.draft.content, 'unsaved');
  vm.$confirm = async () => {};
  await vm.selectEntry({ id: 'two' });
  assert.equal(vm.selectedId, 'two'); assert.equal(vm.draft, null);
});

test('browser unload guard protects drafts and pending saves, clean views remain unblocked', () => {
  const vm = fixture();
  let prevented = 0;
  const event = { preventDefault() { prevented++; } };
  vm.handleBeforeUnload(event); assert.equal(prevented, 0);
  vm.editEntry({ content: 'draft' }); vm.handleBeforeUnload(event);
  assert.equal(prevented, 1); assert.equal(event.returnValue, '');
  vm.draft = null; vm.saving = true; vm.handleBeforeUnload(event);
  assert.equal(prevented, 2);
});

test('legacy Excel text preserves colons and multiline content without guessed fields', () => {
  const vm = fixture();
  const content = '售价：28\n库存：41\n备注：http://example.test/a:b\n多行说明';
  vm.editEntry({ id: 'old', content, editVersion: 2 });
  assert.equal(vm.draft.mode, 'text');
  assert.equal(vm.draft.content, content);
  assert.equal(vm.draft.question, ''); assert.equal(vm.draft.answer, '');
});
