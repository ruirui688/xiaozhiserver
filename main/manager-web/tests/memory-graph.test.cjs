const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue/dist/vue.common.js');
const compiler = require('vue-template-compiler');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const { buildMemoryGraph, graphBounds } = loadModule('src/views/memory/memoryGraphModel.mjs');
const NOW = Date.parse('2026-10-10T12:00:00Z');
const policy = { memoryScope: 'AGENT', devices: [{ deviceId: 'device-a', label: '客厅设备' }, { deviceId: 'device-b', label: '卧室设备' }],
  speakers: [{ speakerId: 'alice', label: '小陈' }, { speakerId: 'bob', label: '小陈' }] };
const fact = (id, extra = {}) => ({ memoryId: id, agentId: 'agent-a', status: 'active', canonicalText: '喜欢钢琴',
  category: '偏好', confirmationState: 'auto', memoryTier: 'LONG_TERM', scopeId: 'device-a', payload: '{}', ...extra });
const rule = (id, extra = {}) => ({ tombstoneId: id, agentId: 'agent-a', status: 'active', patternText: '钢琴',
  scopeType: 'device', scopeId: 'device-a', speakerId: '', matchType: 'contains', ...extra });
const build = extra => buildMemoryGraph({ agentId: 'agent-a', agentName: '家庭助手', policy, now: NOW, ...extra });
function ancestors(graph, node) {
  const result = []; let current = node;
  while (current) {
    const edge = graph.edges.find(item => item.to === current.id);
    current = edge && graph.nodes.find(item => item.id === edge.from);
    if (current) result.unshift(current);
  }
  return result;
}

test('DEVICE graph preserves agent/device/person/category ownership with policy labels', () => {
  const graph = build({ policy: { ...policy, memoryScope: 'DEVICE' }, facts: [
    fact('a', { payload: JSON.stringify({ speaker_id: 'alice' }) }),
    fact('b', { scopeId: 'device-b', payload: { speaker_id: 'alice' } }),
    fact('c', { payload: { speaker_id: 'bob' } }), fact('shared')
  ] });
  const nodes = graph.nodes.filter(node => node.type === 'fact');
  assert.equal(nodes.length, 4);
  assert.equal(new Set(nodes.map(node => node.id)).size, 4);
  assert.deepEqual(ancestors(graph, nodes.find(node => node.rec.memoryId === 'a')).map(node => node.type), ['agent', 'device', 'audience', 'category']);
  assert.deepEqual(ancestors(graph, nodes.find(node => node.rec.memoryId === 'b')).map(node => node.label), ['家庭助手', '卧室设备', '小陈', '偏好']);
  const audiences = graph.nodes.filter(node => node.type === 'audience');
  assert.equal(audiences.filter(node => node.label === '小陈').length, 3, 'same display name must not merge distinct IDs or devices');
  assert.equal(audiences.filter(node => node.label === '共享记忆').length, 1);
});

test('AGENT graph uses device as a source filter, without inventing device isolation', () => {
  const graph = build({ facts: [fact('a'), fact('b', { scopeId: 'device-b' })], filters: { device: 'device:device-b' } });
  assert.equal(graph.nodes.some(node => node.type === 'device'), false);
  assert.equal(graph.factCount, 1);
  const node = graph.nodes.find(node => node.type === 'fact');
  assert.equal(node.rec.memoryId, 'b');
  assert.equal(node.source, '来源：卧室设备');
  assert.equal(graph.options.devices.length, 2, 'filters must not remove options needed to recover the full graph');
});

test('DEVICE grouping follows authoritative scopeId when provenance differs or the scope is missing', () => {
  const graph = build({ policy: { ...policy, memoryScope: 'DEVICE' }, facts: [
    fact('conflict', { scopeId: 'device-a', macAddress: 'device-b' }),
    fact('missing', { scopeId: null, macAddress: 'device-b' })
  ] });
  const conflicting = graph.nodes.find(node => node.rec && node.rec.memoryId === 'conflict');
  assert.equal(ancestors(graph, conflicting)[1].label, '客厅设备');
  assert.equal(conflicting.source, '来源：卧室设备');
  const missing = graph.nodes.find(node => node.rec && node.rec.memoryId === 'missing');
  assert.equal(ancestors(graph, missing)[1].label, '设备归属缺失');
});

test('historic private IDs and malformed identities never silently become shared', () => {
  const graph = build({ facts: [fact('old', { payload: { speaker_id: 'old-id' } }), fact('broken', { payload: '{broken' })] });
  const labels = graph.nodes.filter(node => node.type === 'audience').map(node => node.label);
  assert.deepEqual(new Set(labels), new Set(['old-id', '人物归属待核实']));
  assert.equal(labels.includes('共享记忆'), false);
});

test('state, search, device, person, source and tier filters remain an intersection', () => {
  const facts = [fact('selected', { payload: { speaker_id: 'alice' } }),
    fact('other-person', { payload: { speaker_id: 'bob' } }),
    fact('other-device', { scopeId: 'device-b', payload: { speaker_id: 'alice' } }),
    fact('expired', { expiresAt: '2026-10-09T00:00:00Z', payload: { speaker_id: 'alice' } }),
    fact('manual', { confirmationState: 'manual', payload: { speaker_id: 'alice' } }),
    fact('working', { memoryTier: 'WORKING', payload: { speaker_id: 'alice' } }),
    fact('other-text', { canonicalText: '喜欢游泳', payload: { speaker_id: 'alice' } })];
  const filters = { query: '钢琴', status: 'active', speaker: 'speaker:alice', device: 'device:device-a', source: 'auto', tier: 'LONG_TERM' };
  assert.deepEqual(build({ facts, filters }).nodes.filter(node => node.type === 'fact').map(node => node.rec.memoryId), ['selected']);
  assert.equal(build({ facts, filters: { ...filters, query: '' } }).factCount, 2);
  assert.equal(build({ facts, filters: { status: 'expired' } }).nodes.find(node => node.type === 'fact').rec.memoryId, 'expired');
});

test('archived facts are distinct from expired active facts and deleted facts stay out', () => {
  const facts = [fact('expired', { expiresAt: NOW }), fact('archived', { status: 'archived', expiresAt: NOW - 100 }), fact('deleted', { status: 'deleted' })];
  const graph = build({ facts });
  assert.equal(graph.totalFacts, 2);
  assert.deepEqual(new Set(graph.nodes.filter(node => node.type === 'fact').map(node => node.status)), new Set(['expired', 'archived']));
  assert.equal(build({ facts, filters: { status: 'archived' } }).factCount, 1);
});

test('confirmed facts use the shared source label and can be filtered independently', () => {
  const graph = build({ facts: [fact('auto'), fact('confirmed', { confirmationState: 'confirmed' })], filters: { source: 'confirmed' } });
  const nodes = graph.nodes.filter(node => node.type === 'fact');
  assert.equal(nodes.length, 1);
  assert.equal(nodes[0].rec.memoryId, 'confirmed');
  assert.match(nodes[0].sub, /人工确认/);
  assert.doesNotMatch(nodes[0].sub, /待核实|未标注/);
  const vm = create();
  assert.deepEqual(vm.sourceOptions.find(option => option.value === 'confirmed'), { value: 'confirmed', label: '人工确认' });
  vm.$destroy();
});

test('forget rules attach to their device and audience, not the all-person agent root', () => {
  const graph = build({ policy: { ...policy, memoryScope: 'DEVICE' }, tombstones: [
    rule('private', { speakerId: 'alice', matchType: 'exact' }), rule('shared'),
    rule('missing', { scopeId: null, speakerId: 'bob' }), rule('inactive', { status: 'inactive' })
  ] });
  assert.equal(graph.factCount, 0); assert.equal(graph.ruleCount, 3);
  const privateNode = graph.nodes.find(node => node.rec && node.rec.tombstoneId === 'private');
  assert.deepEqual(ancestors(graph, privateNode).map(node => node.label), ['家庭助手', '客厅设备', '小陈', '遗忘规则']);
  assert.match(privateNode.sub, /精确匹配 exact/);
  const sharedNode = graph.nodes.find(node => node.rec && node.rec.tombstoneId === 'shared');
  assert.equal(ancestors(graph, sharedNode)[2].label, '共享记忆');
  assert.match(sharedNode.sub, /包含匹配 contains/);
  const missingNode = graph.nodes.find(node => node.rec && node.rec.tombstoneId === 'missing');
  assert.equal(ancestors(graph, missingNode)[1].label, '设备归属缺失');
});

test('AGENT forget rule applies across devices for its person while retaining source', () => {
  const graph = build({ tombstones: [rule('r', { scopeId: 'device-b', speakerId: 'alice' })] });
  const node = graph.nodes.find(item => item.type === 'tombstone');
  assert.deepEqual(ancestors(graph, node).map(item => item.type), ['agent', 'audience', 'rules']);
  assert.match(node.sub, /同智能体全部设备/);
  assert.match(node.source, /卧室设备/);
  assert.equal(build({ tombstones: [rule('r')], filters: { speaker: 'speaker:alice' } }).ruleCount, 0);
});

test('missing and unknown forget match types are explicitly unverified instead of implied contains', () => {
  const graph = build({ tombstones: [rule('missing', { matchType: null }), rule('blank', { matchType: '' }), rule('unknown', { matchType: 'semantic' })] });
  const nodes = graph.nodes.filter(node => node.type === 'tombstone');
  assert.equal(nodes.length, 3);
  for (const node of nodes) {
    assert.match(node.sub, /未标注.*待核实/);
    assert.doesNotMatch(node.sub, /包含匹配|contains|精确匹配|exact/);
  }
});

test('empty or zero-match graphs have no decorative root; rule-only graphs remain inspectable', () => {
  assert.equal(build({}).nodes.length, 0);
  assert.equal(build({ facts: [fact('a')], filters: { query: '无匹配内容' } }).nodes.length, 0);
  assert.ok(build({ tombstones: [rule('r')] }).nodes.some(node => node.type === 'tombstone'));
});

test('missing policy cannot render a guessed AGENT ownership graph', () => {
  const graph = build({ policy: null, facts: [fact('a')] });
  assert.equal(graph.scopeKnown, false);
  assert.equal(graph.nodes.length, 0);
  assert.equal(graph.totalFacts, 1);
});

test('collapsing one person category cannot hide the same category of another person', () => {
  const facts = [fact('a', { payload: { speaker_id: 'alice' } }), fact('b', { payload: { speaker_id: 'bob' } })];
  const graph = build({ facts });
  const a = graph.nodes.find(node => node.rec && node.rec.memoryId === 'a');
  const category = ancestors(graph, a).at(-1);
  const folded = build({ facts, collapsed: { [category.id]: true } });
  assert.deepEqual(folded.nodes.filter(node => node.type === 'fact').map(node => node.rec.memoryId), ['b']);
  assert.equal(folded.factCount, 2);
  assert.ok(folded.edges.every(edge => folded.nodes.some(node => node.id === edge.from) && folded.nodes.some(node => node.id === edge.to)));
});

const component = loadVueComponent('src/views/memory/MemoryGraph.vue');
function create(props = {}) {
  return new (Vue.extend({ ...component, render(h) { return h('div'); } }))({ propsData: { facts: [fact('a')], policy, now: NOW, ...props } });
}

test('keyboard opens the correct detail type without clearing active filters', () => {
  const vm = create({ tombstones: [rule('r')] }); const details = [];
  vm.$on('open-detail', (...args) => details.push(args));
  vm.query = '钢琴'; vm.device = 'device:device-a';
  const node = vm.graph.nodes.find(item => item.type === 'tombstone');
  let prevented = false;
  vm.onNodeKey({ key: ' ', preventDefault() { prevented = true; } }, node);
  assert.equal(prevented, true);
  assert.equal(details[0][0].tombstoneId, 'r'); assert.equal(details[0][1], 'tombstone');
  assert.equal(vm.query, '钢琴'); assert.equal(vm.device, 'device:device-a');
  const group = vm.graph.nodes.find(item => item.type === 'audience');
  vm.onNodeKey({ key: 'Enter', preventDefault() {} }, group);
  assert.equal(vm.collapsed[group.id], true);
  vm.$destroy();
});

test('group focus survives DOM relocation so a subsequent Enter reopens the same group', async () => {
  const vm = create({ policy: { ...policy, memoryScope: 'DEVICE' } });
  const group = vm.graph.nodes.find(node => node.type === 'device');
  const doc = { body: {}, activeElement: null }; const calls = [];
  const element = () => ({ ownerDocument: doc, getAttribute: () => group.id,
    contains(target) { return target === this; }, focus(options) { calls.push(options); doc.activeElement = this; } });
  const oldNode = element(); vm.$refs.graphNodes = [oldNode]; doc.activeElement = oldNode;
  vm.pick(group);
  const movedNode = element(); vm.$refs.graphNodes = [movedNode]; doc.activeElement = doc.body;
  await Vue.nextTick();
  assert.equal(doc.activeElement, movedNode);
  assert.equal(vm.collapsed[group.id], true);
  assert.deepEqual(calls, [{ preventScroll: true }]);
  vm.onNodeKey({ key: 'Enter', preventDefault() {} }, vm.graph.nodes.find(node => node.id === group.id));
  doc.activeElement = doc.body;
  await Vue.nextTick();
  assert.equal(vm.collapsed[group.id], false);
  assert.equal(doc.activeElement, movedNode);
  vm.$destroy();
});

test('group rerender does not steal a new filter focus or a detail drawer focus', async () => {
  const vm = create(); const group = vm.graph.nodes.find(node => node.type === 'audience');
  const doc = { body: {}, activeElement: null }; let focusCalls = 0;
  const nodeElement = { ownerDocument: doc, getAttribute: () => group.id, contains(target) { return target === this; }, focus() { focusCalls++; } };
  vm.$refs.graphNodes = [nodeElement]; doc.activeElement = nodeElement;
  vm.pick(group);
  const input = {}; doc.activeElement = input;
  await Vue.nextTick();
  assert.equal(focusCalls, 0); assert.equal(doc.activeElement, input);
  vm.query = '钢琴'; await Vue.nextTick();
  assert.equal(focusCalls, 0); assert.equal(doc.activeElement, input);
  vm.collapsed = {};
  const drawer = {}; vm.$on('open-detail', () => { doc.activeElement = drawer; });
  vm.pick(vm.graph.nodes.find(node => node.type === 'fact'));
  await Vue.nextTick();
  assert.equal(focusCalls, 0); assert.equal(doc.activeElement, drawer);
  vm.$destroy();
});

test('fit waits for a visible canvas and contains large graphs at mobile and desktop sizes', () => {
  const vm = create({ active: false, facts: Array.from({ length: 200 }, (_, index) => fact(`f${index}`)),
    tombstones: Array.from({ length: 100 }, (_, index) => rule(`r${index}`)) });
  const canvas = { clientWidth: 0, clientHeight: 0 }; vm.$refs.canvas = canvas;
  vm.fitView(); assert.equal(vm.zoom, 1);
  vm.active = true;
  for (const width of [320, 768, 1366]) {
    canvas.clientWidth = width; canvas.clientHeight = 520; vm.fitView();
    const bounds = graphBounds(vm.graph.nodes);
    assert.ok(vm.panX + bounds.minX * vm.zoom >= 0);
    assert.ok(vm.panX + bounds.maxX * vm.zoom <= width + 0.001);
    assert.ok(vm.panY + bounds.maxY * vm.zoom <= 520 + 0.001);
    assert.ok(Number.isFinite(vm.zoom) && vm.zoom > 0);
  }
  assert.ok(vm.zoom < 0.25, 'large graphs must not be clipped by the old minimum zoom');
  vm.$destroy();
});

test('clock-only refresh preserves the user viewport when visible nodes are unchanged', async () => {
  const vm = create();
  vm.$refs.canvas = { clientWidth: 768, clientHeight: 500 };
  vm.panX = 87; vm.panY = -92; vm.zoom = 1.7;
  vm.now = NOW + 30000;
  await Vue.nextTick(); await new Promise(resolve => setTimeout(resolve, 5));
  assert.deepEqual([vm.panX, vm.panY, vm.zoom], [87, -92, 1.7]);
  vm.$destroy();
});

test('ResizeObserver restores a formerly hidden tab and is disconnected with timers on destroy', async () => {
  const original = global.ResizeObserver; let observer; let callback;
  global.ResizeObserver = class { constructor(handler) { callback = handler; observer = this; } observe(target) { this.target = target; } disconnect() { this.disconnected = true; } };
  const vm = create({ active: false }); const canvas = { clientWidth: 0, clientHeight: 0 }; vm.$refs.canvas = canvas;
  try {
    component.mounted.call(vm);
    assert.equal(observer.target, canvas);
    vm.active = true; canvas.clientWidth = 768; canvas.clientHeight = 500;
    callback(); await Vue.nextTick(); await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(vm.lastWidth, 768);
    vm.requestFit(); vm.$destroy();
    assert.equal(observer.disconnected, true); assert.equal(vm.disposed, true);
    assert.equal(vm.clockTimer._destroyed, true); assert.equal(vm.fitTimer._destroyed, true);
  } finally { global.ResizeObserver = original; if (!vm._isDestroyed) vm.$destroy(); }
});

test('graph SVG template compiles and exposes keyboard-operable nodes with full accessible names', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../src/views/memory/MemoryGraph.vue'), 'utf8');
  const template = compiler.parseComponent(source).template.content;
  assert.deepEqual(compiler.compile(template).errors, []);
  assert.match(template, /tabindex="0" role="button"/);
  assert.match(template, /:aria-label="nodeAria\(node\)"/);
  assert.match(template, /@keydown\.stop="onNodeKey\(\$event, node\)"/);
});
