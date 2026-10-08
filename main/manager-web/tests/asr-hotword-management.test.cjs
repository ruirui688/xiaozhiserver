const assert = require('node:assert/strict');
const { test } = require('node:test');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const utils = loadModule('src/utils/asrHotwords.js');
test('bulk hotwords normalize CRLF, default weights and surrounding whitespace', () => {
  assert.deepEqual(utils.parseHotwordText(' 慧苑 \r\n 星岚 |5\n'), [{ word: '慧苑', weight: 4 }, { word: '星岚', weight: 5 }]);
});
test('reject malformed, duplicate and out of range hotwords before registration', () => {
  for (const text of ['', '词|', '词|0', '词|11', '词|1.2', '词|x', '词|4|5', '词\n词', '词\t条']) {
    assert.throws(() => utils.parseHotwordText(text));
  }
  assert.throws(() => utils.parseHotwordText(Array.from({ length: 5001 }, (_, n) => `词${n}`).join('\n')));
});
function component(api = {}) {
  return loadVueComponent('src/views/AsrHotwordManagement.vue', { '@/apis/module/asrHotword': { __esModule: true, default: api } });
}
test('manual local refresh explains an empty result without claiming vendor synchronization', async () => {
  const notices = [];
  const c = component({ list: async () => [], sources: async () => ({ sources: [], capabilities: {} }), agents: async () => [] });
  const vm = { generation: 0, current: () => true, $message: { success: text => notices.push(text) } };
  await c.methods.load.call(vm, true);
  assert.equal(vm.loading, false);
  assert.match(notices[0], /本地暂无词库/);
  assert.match(notices[0], /从火山同步/);
  await c.methods.load.call(vm);
  assert.equal(notices.length, 1);
});
test('failed refresh displays error without reporting success', async () => {
  const c = component({ list: async () => { throw new Error('读取失败'); }, sources: async () => ({ sources: [], capabilities: {} }), agents: async () => [] });
  const vm = { generation: 0, current: () => true, $message: { success: () => assert.fail('false success') } };
  await c.methods.load.call(vm, true);
  assert.equal(vm.error, '读取失败');
  assert.equal(vm.loading, false);
});
test('stale workspace responses never replace current rows', async () => {
  let finish;
  const c = component({ list: () => new Promise(resolve => { finish = resolve; }), sources: async () => ({ sources: [], capabilities: {} }), agents: async () => [] });
  const vm = { generation: 0, rows: ['current'], current: () => false };
  const promise = c.methods.load.call(vm); finish(['stale']); await promise;
  assert.deepEqual(vm.rows, ['current']);
});
test('save retains editor and reports rejection instead of claiming publication', async () => {
  const c = component({ create: async () => { throw new Error('无写入权限'); } });
  const vm = { ...c.methods, busy: false, canWrite: true, generation: 1, current: () => true, editorVisible: true,
    editingId: '', form: { name: '词库', sourceId: 'source', text: '慧苑' } };
  await vm.save();
  assert.equal(vm.editorVisible, true); assert.equal(vm.dialogError, '无写入权限'); assert.equal(vm.busy, false);
});
test('publish FAILED response never displays success and refreshes authoritative state', async () => {
  const c = component({ publish: async () => ({ status: 'FAILED', lastError: '发布失败' }) });
  const errors = []; let reloads = 0;
  const vm = { ...c.methods, generation: 1, canWrite: true, busy: false, current: () => true,
    $confirm: async () => true, $message: { success: () => assert.fail('false success'), error: text => errors.push(text) }, load: async () => reloads++ };
  await vm.publish({ id: 'id', name: '词库' });
  assert.deepEqual(errors, ['发布失败']); assert.equal(reloads, 1);
});
test('workspace changes while confirmation is open prevent mutation', async () => {
  const c = component({ remove: async () => assert.fail('cross workspace delete') });
  const vm = { ...c.methods, generation: 1, current: () => false, $confirm: async () => true };
  await vm.remove({ id: 'id', name: '词库' });
});

test('highest supported weight is accepted without clipping', () => {
  assert.deepEqual(utils.parseHotwordText('慧苑|10'), [{ word: '慧苑', weight: 10 }]);
});

test('publishing a revision never implicitly applies it to agents', async () => {
  let confirmation = ''; const notices = [];
  const c = component({ publish: async () => ({ status: 'PUBLISHED' }), apply: async () => assert.fail('implicit apply') });
  const vm = { ...c.methods, generation: 1, canWrite: true, busy: false, current: () => true,
    $confirm: async text => { confirmation = text; }, $message: { success: text => notices.push(text) }, load: async () => {} };
  await vm.publish({ id: 'id', name: '词库' });
  assert.match(confirmation, /已有智能体保持当前版本/); assert.match(notices[0], /请应用/);
});

test('lease recovery only enables the same interrupted operation', () => {
  const c = component(); const vm = { ...c.methods };
  assert.equal(vm.actionLocked({ status: 'PUBLISHING', recoverable: true }, 'PUBLISHING'), false);
  assert.equal(vm.actionLocked({ status: 'PUBLISHING', recoverable: true }, 'DELETING'), true);
  assert.equal(vm.actionLocked({ status: 'DELETING', recoverable: true }, 'DELETING'), false);
  assert.equal(vm.actionLocked({ status: 'DELETING', recoverable: false }, 'DELETING'), true);
  assert.equal(vm.locked({ status: 'PUBLISHING', recoverable: true }), true);
});

test('word length respects UTF-8 quota for Chinese, ASCII and mixed words', () => {
  for (const word of ['慧'.repeat(10), 'a'.repeat(30), '慧'.repeat(9) + 'abc']) {
    assert.equal(utils.parseHotwordText(word)[0].word, word);
  }
  for (const word of ['慧'.repeat(11), 'a'.repeat(31), '慧'.repeat(9) + 'abcd']) {
    assert.throws(() => utils.parseHotwordText(word), /热词过长/);
  }
});

function importDialog(api = {}) {
  return loadVueComponent('src/components/resource/AsrHotwordImportDialog.vue', { '@/apis/module/asrHotword': { __esModule: true, default: api } });
}
function importVm(c, extra = {}) {
  return { ...c.data(), ...c.methods, canWrite: true, sourceId: 'source', current: () => true, ...extra };
}
test('source capability messages distinguish permission, configuration and availability', () => {
  const c = component();
  for (const message of ['无同步权限', '尚未配置火山来源', '火山来源暂不可用']) {
    assert.equal(c.computed.sourceNotice.call({ capabilities: { message } }), message);
  }
  assert.deepEqual(c.computed.availableSources.call({ sources: [] }), []);
});
test('single source creation selects automatically and same source permits different ASR models', () => {
  const c = component(); const vm = { ...c.methods, availableSources: [{ id: 'source' }] };
  vm.edit(); assert.equal(vm.form.sourceId, 'source');
  const agents = [{ id: 'a', asrModelId: 'stream', sourceId: 'source' }, { id: 'b', asrModelId: 'recording', sourceId: 'source' }, { id: 'c', sourceId: 'other' }];
  assert.deepEqual(c.computed.compatibleAgents.call({ agents, applying: { sourceId: 'source' } }), agents.slice(0, 2));
});
test('opening sync automatically loads all sources without selecting a model', async () => {
  const calls = [];
  const c = importDialog({ remote: async (...args) => { calls.push(args); return { tables: [{ sourceId: 's', remoteId: 'r' }], errors: [] }; } });
  const vm = importVm(c); c.created.call(vm); await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls, [[]]); assert.equal(vm.rows.length, 1);
});
test('newer remote refresh ignores a previous response', async () => {
  let finish; let count = 0;
  const c = importDialog({ remote: () => ++count === 1 ? new Promise(resolve => { finish = resolve; }) : Promise.resolve({ tables: [{ remoteId: 'new' }], errors: [] }) });
  const vm = importVm(c); vm.current = generation => vm.generation === generation;
  const oldRequest = vm.loadRemote(); await vm.loadRemote();
  finish({ tables: [{ remoteId: 'old' }] }); await oldRequest;
  assert.deepEqual(vm.rows, [{ remoteId: 'new' }]); assert.equal(vm.loading, false);
});
test('partial cloud failure preserves successful source results and retry clears errors', async () => {
  let attempts = 0;
  const rows = [{ sourceId: 'ok', remoteId: 'r' }];
  const c = importDialog({ remote: async () => ({ tables: rows, errors: ++attempts === 1 ? [{ sourceName: '第二来源', message: '连接失败' }] : [] }) });
  const vm = importVm(c); await vm.loadRemote();
  assert.deepEqual(vm.rows, rows); assert.equal(vm.sourceErrors.length, 1);
  await vm.loadRemote(); assert.deepEqual(vm.sourceErrors, []);
});
test('network failure preserves last successful list and supports retry', async () => {
  let attempts = 0;
  const c = importDialog({ remote: async () => { if (++attempts === 1) throw new Error('火山读取失败'); return { tables: [], errors: [] }; } });
  const vm = importVm(c, { rows: [{ remoteId: 'existing' }] });
  await vm.loadRemote(); assert.equal(vm.rows.length, 1); assert.equal(vm.error, '火山读取失败'); assert.equal(vm.loading, false);
  await vm.loadRemote(); assert.deepEqual(vm.rows, []); assert.equal(vm.error, '');
});
test('successful import uses source identity and refreshes parent without duplicate submission', async () => {
  const requests = []; const events = [];
  const c = importDialog({ importRemote: async payload => requests.push(payload), publish: () => assert.fail('publish'), apply: () => assert.fail('apply') });
  const row = { sourceId: 'source', remoteId: 'remote', imported: false };
  const vm = importVm(c, { rows: [row], $message: { success: () => {} }, $emit: event => events.push(event) });
  await vm.importRow(row); await vm.importRow(vm.rows[0]);
  assert.deepEqual(requests, [{ sourceId: 'source', remoteId: 'remote' }]); assert.deepEqual(events, ['imported']); assert.equal(vm.importing, '');
});
test('failed import keeps row actionable and does not refresh or claim success', async () => {
  const c = importDialog({ importRemote: async () => { throw new Error('无导入权限'); } });
  const row = { sourceId: 'source', remoteId: 'remote', imported: false };
  const vm = importVm(c, { rows: [row], $message: { success: () => assert.fail('success') }, $emit: () => assert.fail('refresh') });
  await vm.importRow(row); assert.equal(vm.error, '无导入权限'); assert.equal(vm.rows[0].imported, false); assert.equal(vm.importing, '');
});
test('read-only and changed workspace cannot import cloud words', async () => {
  const c = importDialog({ importRemote: () => assert.fail('unauthorized import') });
  await importVm(c, { canWrite: false }).importRow({ sourceId: 's', remoteId: 'id' });
  await importVm(c, { current: () => false }).importRow({ sourceId: 's', remoteId: 'id' });
});
