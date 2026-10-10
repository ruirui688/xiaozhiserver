const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue/dist/vue.common.js');
const { parseComponent, compile } = require('vue-template-compiler');
const { loadVueComponent, loadModule } = require('./helpers/loadVueComponent.cjs');
const { presentRevision, groupRevisions, formatRevisionTime } = loadModule('src/views/memory/memoryRevisionPresentation.mjs');

test('history preserves its snapshots when current facts change or disappear', () => {
  const revision = { memoryId: 'm1', revisionAction: 'update',
    beforePayload: JSON.stringify({ canonicalText: '住杭州', payload: { speaker_id: 'alice', speaker_name: '小甲' }, status: 'active', expiresAt: null }),
    afterPayload: { canonicalText: '住苏州', payload: { speaker_id: 'bob', speaker_name: '小乙' }, status: 'archived', expiresAt: 1791590400000 } };
  const current = { memoryId: 'm1', canonicalText: '住北京', status: 'active' };
  const item = presentRevision(revision, [current]);
  assert.equal(item.summary, '住苏州');
  assert.equal(item.currentFact, current);
  assert.equal(item.changes.find(row => row.key === 'content').before, '住杭州');
  assert.equal(item.changes.find(row => row.key === 'person').after, '小乙（bob）');
  assert.equal(item.changes.find(row => row.key === 'state').after, '已归档');
  assert.equal(item.changes.find(row => row.key === 'expiry').before, '未设到期时间');
  assert.ok(item.changes.every(row => row.changed));
  assert.equal(presentRevision(revision).summary, '住苏州');
  assert.equal(presentRevision(revision, [{ ...current, status: 'deleted' }]).currentFact, null);
});

test('rule creation, duplicate merge and unknown actions are not mistaken for fact updates', () => {
  assert.equal(presentRevision({ revisionAction: 'create_forget_tombstone' }).label, '创建忘记规则');
  assert.equal(presentRevision({ revisionAction: 'update_merged_as_duplicate' }).kind, 'delete');
  assert.equal(presentRevision({ revisionAction: 'future_update_rule' }).label, '其他操作');
  assert.equal(presentRevision({ revisionAction: 'constructor' }).label, '其他操作');
  assert.equal(presentRevision({ revisionAction: 'archived' }).kind, 'archive');
  assert.equal(presentRevision({ revisionAction: 'deleted' }).kind, 'delete');
  assert.equal(presentRevision({ revisionAction: 'refresh' }).label, '刷新记忆证据');
});

test('candidate review shows its one-sided snapshot and session review reports proposed candidates', () => {
  const candidate = presentRevision({ revisionAction: 'reject_candidate', beforePayload: '{"canonicalText":"旧偏好","status":"rejected"}' });
  assert.equal(candidate.summary, '旧偏好');
  assert.equal(candidate.changes[0].after, undefined);
  const session = presentRevision({ revisionAction: 'session_memory_processed', afterPayload: { maintenanceMode: 'REVIEW', factsChanged: 3, sessionId: 'segment-a' } });
  assert.equal(session.summary, '会话处理完成，生成 3 条待审候选。');
  assert.equal(session.currentFact, null);
  assert.equal(session.sessionId, 'segment-a');
  assert.equal(presentRevision({ revisionAction: 'session_memory_processed', afterPayload: { maintenanceMode: 'AUTO', factsChanged: 0 } }).summary, '会话处理完成，执行 0 项记忆维护动作。');
});

test('malformed and unknown payloads never become raw JSON in the presentation', () => {
  const item = presentRevision({ revisionAction: 'new_action', beforePayload: '{broken', afterPayload: { privateInternalField: 'do-not-display', nested: { token: 'secret' } } });
  assert.equal(item.changes.length, 0);
  assert.equal(item.metrics.length, 0);
  assert.equal(item.summary, '该记录未保存可展示的内容快照。');
  assert.equal(presentRevision({ revisionAction: 'create', afterPayload: '[1,2]' }).changes.length, 0);
});

test('rule snapshots preserve explicit person and matching boundary without inferring deleted facts', () => {
  const item = presentRevision({ revisionAction: 'create_forget_tombstone', afterPayload: { patternText: '旧地址', speakerId: 'alice', scopeId: 'device-a', matchType: 'exact', status: 'active' } });
  assert.equal(item.summary, '旧地址');
  assert.equal(item.changes.find(row => row.key === 'person').after, 'alice');
  assert.equal(item.changes.find(row => row.key === 'match').after, '完全匹配');
  assert.equal(item.currentFact, null);
});

test('invalid dates remain visible and date groups use East Asia time independently of browser timezone', () => {
  const groups = groupRevisions([
    { revisionId: 'unknown', createdAt: 'invalid' },
    { revisionId: 'first', createdAt: '2026-10-09T23:00:00Z' },
    { revisionId: 'second', createdAt: '2026-10-10T01:00:00Z' },
    { revisionId: 'missing' }
  ]);
  assert.equal(groups[0].date, '2026-10-10');
  assert.deepEqual(groups[0].items.map(item => item.key), ['second', 'first']);
  assert.equal(groups[1].date, '时间未记录');
  assert.equal(groups[1].items.length, 2);
  assert.equal(formatRevisionTime('2026-10-10T00:00:00Z'), '2026-10-10 08:00:00');
  assert.equal(formatRevisionTime('2026-10-10 08:00:00'), '2026-10-10 08:00:00');
  assert.equal(formatRevisionTime(0), '1970-01-01 08:00:00');
});

test('timeline history opens independently and only currently loaded facts can be emitted for editing', () => {
  const component = loadVueComponent('src/views/memory/MemoryTimeline.vue');
  const vm = new (Vue.extend({ ...component, render(h) { return h('div'); } }))({ propsData: {
    revisions: [{ revisionId: 'r1', memoryId: 'm1', revisionAction: 'deleted', beforePayload: { canonicalText: '历史值' } }], facts: []
  } });
  const opened = [];
  vm.$on('open-detail', fact => opened.push(fact));
  const item = vm.groupedRevisions[0].items[0];
  vm.toggle(item.key);
  assert.equal(vm.expanded.r1, true);
  assert.equal(item.summary, '历史值');
  vm.openCurrent(item);
  assert.equal(opened.length, 0);
  vm.facts = [{ memoryId: 'm1', canonicalText: '当前值', status: 'archived' }];
  vm.openCurrent(item);
  assert.equal(opened[0].canonicalText, '当前值');
  vm.facts = [];
  vm.openCurrent(item);
  assert.equal(opened.length, 1);
  vm.$destroy();
});

test('timeline renders a native keyboard-activatable expansion button with state', () => {
  const filename = path.resolve(__dirname, '../src/views/memory/MemoryTimeline.vue');
  const descriptor = parseComponent(fs.readFileSync(filename, 'utf8'));
  const result = compile(descriptor.template.content);
  assert.deepEqual(result.errors, []);
  const component = loadVueComponent('src/views/memory/MemoryTimeline.vue');
  const vm = new (Vue.extend({ ...component, render: new Function(result.render), staticRenderFns: result.staticRenderFns.map(source => new Function(source)) }))({ propsData: {
    revisions: [{ revisionId: 'r1', revisionAction: 'forget_boundary' }]
  } });
  function findButton(node) {
    if (node.tag === 'button') return node;
    for (const child of node.children || []) { const found = findButton(child); if (found) return found; }
  }
  const button = findButton(vm._render());
  assert.equal(button.data.attrs.type, 'button');
  assert.equal(button.data.attrs['aria-expanded'], false);
  button.data.on.click();
  assert.equal(findButton(vm._render()).data.attrs['aria-expanded'], true);
  vm.$destroy();
});
