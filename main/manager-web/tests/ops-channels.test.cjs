const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue');
const compiler = require('vue-template-compiler');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const settled = () => new Promise(resolve => setImmediate(resolve));
const nodes = vnode => [vnode, ...(vnode.children || []).flatMap(nodes)];
const text = vnode => [vnode.text || '', ...(vnode.children || []).map(text)].join('');
const deferred = () => { let resolve, reject; const promise = new Promise((ok, fail) => { resolve = ok; reject = fail; }); return { promise, resolve, reject }; };
const row = { id: 'channel-a', name: '主通道', modelId: 'model-a', modelName: 'DeepSeek Chat', provider: 'DEEPSEEK', serviceType: 'LLM', accountRef: 'account-test', version: '9223372036854775806', enabled: true, credentialConfigured: true, maxConcurrency: 20, lastStatus: null };
function create(t, file = 'OpsCredentials', { permissions = ['credentials.read', 'credentials.write'], operations = {}, props = {}, confirm } = {}) {
  const calls = { list: [], saved: [], confirm: [] };
  const api = {
    channels: async data => { calls.list.push(data); return { list: [row], total: '1040' }; },
    channelModels: async () => [{ id: 'model-a', name: 'DeepSeek Chat', provider: 'DEEPSEEK', serviceType: 'LLM', published: true }],
    saveChannel: async data => { calls.saved.push(data); return {}; }, ...operations,
  };
  const fileName = `src/ops/${file === 'OpsCredentials' ? file : 'channels/' + file}.vue`;
  const definition = loadVueComponent(fileName, { '@/apis/platform': { operations: api } });
  const template = compiler.parseComponent(fs.readFileSync(path.resolve(__dirname, '..', fileName), 'utf8')).template.content;
  const compiled = compiler.compileToFunctions(template);
  const vm = new Vue({ ...definition, render: compiled.render, staticRenderFns: compiled.staticRenderFns, propsData: props,
    beforeCreate() { this.$store = { state: { userInfo: { audience: 'OPS', permissions } } }; this.$confirm = async (...args) => { calls.confirm.push(args); if (confirm) return confirm(...args); }; },
  });
  t.after(() => vm.$destroy()); return { vm, calls };
}
function fill(vm) { Object.assign(vm.form, { name: '新通道', modelId: 'model-a', accountRef: 'account-test', apiKey: 'test-only-fake-key', enabled: true }); }

test('channel page reads paginated shared routes without requesting per-user credential inventory', async t => {
  const { vm, calls } = create(t); await settled();
  assert.deepEqual(calls.list[0], { page: 1, limit: 20, provider: '', serviceType: '', keyword: '' });
  assert.equal(vm.total, 1040); assert.equal(vm.rows[0].id, row.id);
  assert.doesNotMatch(text(vm._render()), /批量入库|分配密钥|待分配|库存/);
  vm.page = 4; vm.keyword = '  主通道  '; vm.provider = 'DEEPSEEK'; vm.serviceType = 'LLM'; await vm.search();
  await vm.changePage(2);
  assert.deepEqual(calls.list.at(-1), { page: 2, limit: 20, provider: 'DEEPSEEK', serviceType: 'LLM', keyword: '主通道' });
});

test('channel search does not show stale results and failed reads stay visibly failed', async t => {
  const old = deferred(); const { vm } = create(t, 'OpsCredentials', { operations: { channels: args => args.keyword === 'old' ? old.promise : args.keyword === 'fail' ? Promise.reject(new Error('查询失败')) : Promise.resolve({ list: [{ ...row, id: 'new' }], total: '1' }) } });
  await settled(); vm.keyword = 'old'; const first = vm.search(); vm.keyword = 'new'; await vm.search();
  old.resolve({ list: [row], total: '100' }); await first; assert.equal(vm.rows[0].id, 'new');
  vm.keyword = 'fail'; await vm.search(); assert.equal(vm.error, '查询失败'); assert.deepEqual(vm.rows, []); assert.equal(vm.total, 0);
});

test('read-only staff can inspect metadata but cannot open writes or submit them', async t => {
  const { vm } = create(t, 'OpsCredentials', { permissions: ['credentials.read'] }); await settled();
  assert.doesNotMatch(text(vm._render()), /新增通道/); vm.open(); assert.equal(vm.editing, false); vm.open(row); assert.equal(vm.detail.id, row.id);
  const editor = create(t, 'ChannelEditor', { permissions: ['credentials.read'] }); await settled(); fill(editor.vm); await editor.vm.save(); assert.equal(editor.calls.saved.length, 0);
});

test('new channel validates metadata and submits supplier secret only to explicit save', async t => {
  const { vm, calls } = create(t, 'ChannelEditor'); await settled(); await vm.save(); assert.equal(calls.saved.length, 0);
  fill(vm); await Vue.nextTick(); await vm.save();
  assert.equal(calls.saved.length, 1); const payload = calls.saved[0];
  assert.equal(payload.modelId, 'model-a'); assert.deepEqual(payload.secrets, { api_key: 'test-only-fake-key' });
  assert.equal(payload.enabled, true); assert.equal(payload.id, undefined); assert.equal(payload.legacyCredentialId, undefined); assert.equal(payload.userId, undefined);
  assert.equal(vm.form.apiKey, '');
});

test('editing leaves secrets out when unchanged and preserves exact optimistic version', async t => {
  const { vm, calls } = create(t, 'ChannelEditor', { props: { row } }); await settled();
  await vm.save(); assert.equal(calls.saved.length, 1);
  assert.equal(calls.saved[0].expectedVersion, '9223372036854775806'); assert.equal(calls.saved[0].secrets, undefined);
  assert.equal(nodes(vm._render()).find(node => node.tag === 'el-select').data.attrs.disabled, true);
});

test('cancelled disable makes no mutation while confirmed disable is versioned', async t => {
  const cancelled = create(t, 'ChannelEditor', { props: { row }, confirm: () => Promise.reject(new Error('cancel')) }); await settled(); cancelled.vm.form.enabled = false; await cancelled.vm.save(); assert.equal(cancelled.calls.saved.length, 0); assert.equal(cancelled.vm.saving, false);
  const approved = create(t, 'ChannelEditor', { props: { row } }); await settled(); approved.vm.form.enabled = false; await approved.vm.save(); assert.equal(approved.calls.saved[0].enabled, false); assert.equal(approved.calls.confirm.length, 1);
});

test('double clicks cannot save twice, unchanged retries reuse id and edited retries use a new id', async t => {
  const request = deferred(); const submitted = [];
  const { vm } = create(t, 'ChannelEditor', { operations: { saveChannel: data => { submitted.push(data); return submitted.length === 1 ? request.promise : Promise.reject(new Error('test-only-retry')); } } });
  await settled(); fill(vm); await Vue.nextTick(); const first = vm.save(); await vm.save(); assert.equal(submitted.length, 1);
  request.reject(new Error('test-only-timeout')); await first; await vm.save(); assert.equal(submitted[0].requestId, submitted[1].requestId);
  vm.form.name = '修改后的名称'; await Vue.nextTick(); await vm.save(); assert.notEqual(submitted[1].requestId, submitted[2].requestId);
  assert.equal(vm.form.apiKey, 'test-only-fake-key'); vm.close(); assert.equal(vm.form.apiKey, '');
});

test('failed model lookup blocks saving, retry restores it, and destruction clears transient input', async t => {
  let fail = true; const { vm, calls } = create(t, 'ChannelEditor', { operations: { channelModels: async () => { if (fail) throw new Error('模型读取失败'); return []; } } });
  await settled(); fill(vm); await vm.save(); assert.equal(calls.saved.length, 0); assert.equal(vm.modelError, '模型读取失败');
  fail = false; await vm.loadModels(); assert.equal(vm.modelError, ''); vm.$destroy(); assert.equal(vm.form.apiKey, '');
});

test('all three roles have distinct titles derived only from actual grants', () => {
  const nav = loadModule('src/ops/navigation.js'); const profile = permissions => ({ audience: 'OPS', username: 'arbitrary-name', permissions });
  const cases = [[['credentials.read', 'allowance.read'], '模型与额度'], [['official.resource.read', 'ota.read'], '内容与设备'], [['supplier.bill.read', 'allowance.review'], '财务与审批']];
  for (const [permissions, title] of cases) { const user = profile(permissions); assert.equal(nav.operationsPersona(user).title, title); assert.equal(nav.operationsPageTitle(user, { path: '/home' }), `${title} · 智控运营`); }
  assert.equal(nav.operationsPageTitle(profile(['credentials.read']), { path: '/services', query: { tab: 'credentials' } }), '供应商通道 · 模型与额度 · 智控运营');
  assert.equal(nav.operationsPersona({ audience: 'USER', permissions: ['credentials.read'] }).title, '运营工作台');
});

test('editing account identity requires a matching new secret', () => {
  const { channelPayload } = loadModule('src/ops/channels/channel-display.js');
  const form = { name: 'changed', modelId: row.modelId, accountRef: 'other-account', region: '', projectRef: '', enabled: true, maxConcurrency: 20, apiKey: '' };
  assert.throws(() => channelPayload(form, row, 'test-only-request'), /更换供应商账号/);
  form.apiKey = 'test-only-new-secret'; assert.equal(channelPayload(form, row, 'test-only-request').accountRef, 'other-account');
});

test('rate form preserves base-unit decimal strings and requires matching integral usage limits', () => {
  const helper = loadModule('src/ops/channels/rate-fields.js');
  const fields = helper.fieldsForService('ASR'); assert.equal(fields[0].unit, '毫秒');
  const form = { modelId: 'model-asr', version: 'v2', prices: { audio_duration: '0.0000001234567890123456789' }, maximums: { audio_duration: '9007199254740993' } };
  const payload = helper.ratePayload(form, fields);
  assert.deepEqual(payload.metricRates, { audio_duration: '0.0000001234567890123456789' });
  assert.deepEqual(payload.maxUsage, { audio_duration: '9007199254740993' });
  form.maximums.audio_duration = '1.5'; assert.throws(() => helper.ratePayload(form, fields), /正整数/);
  assert.deepEqual(helper.parseMetricMap('{"future_metric":"0.001"}'), { future_metric: '0.001' });
});
