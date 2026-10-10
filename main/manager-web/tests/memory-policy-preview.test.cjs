const assert = require('node:assert/strict');
const { test } = require('node:test');
const Vue = require('vue/dist/vue.common.js');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const response = data => ({ data: { code: 0, data } });
const policy = extra => ({ memoryScope: 'AGENT', unknownSpeakerPolicy: 'SHARED', maintenanceMode: 'AUTO', activeMaxChars: 1000, contextMaxChars: 4000, contextMaxItems: 30, retentionDays: 0, canWrite: true, devices: [{deviceId:'device-a',label:'设备A'}], speakers: [{speakerId:'speaker-a',label:'人物A'}], ...extra });

function create(name, memoryApi, propsData = {}) {
  const component = loadVueComponent(`src/views/memory/${name}.vue`, { '@/apis/api': { __esModule: true, default: { memory: memoryApi } } });
  const Ctor = Vue.extend({ ...component, render(h) { return h('div'); } });
  const vm = new Ctor({ propsData });
  vm.$message = { success() {}, error() {}, warning() {} };
  return vm;
}

test('policy save sends persisted fields only and reloads the authoritative result', () => {
  const reads = []; const writes = [];
  const vm = create('MemoryPolicyPanel', {
    policy(...args) { reads.push(args); },
    savePolicy(...args) { writes.push(args); }
  }, {agentId:'agent-a'});
  reads[0][1](response(policy()));
  vm.form.memoryScope = 'DEVICE';
  vm.form.maintenanceMode = 'REVIEW';
  vm.form.retentionDays = 30;
  vm.save();
  assert.equal(writes.length, 1);
  assert.equal(writes[0][1].maintenanceMode, 'REVIEW');
  assert.equal(writes[0][1].memoryScope, 'DEVICE');
  assert.equal(writes[0][1].retentionDays, 30);
  assert.equal('devices' in writes[0][1], false);
  assert.equal('canWrite' in writes[0][1], false);
  writes[0][2](response({}));
  assert.equal(reads.length, 2);
  assert.equal(vm.loaded, false);
  reads[1][1](response(policy({memoryScope:'DEVICE',maintenanceMode:'REVIEW',retentionDays:30})));
  assert.equal(vm.form.memoryScope, 'DEVICE');
  assert.equal(vm.saving, false);
  vm.$destroy();
});

test('read-only or failed policy reads cannot issue a save', () => {
  let callback; let saves = 0;
  const vm = create('MemoryPolicyPanel', { policy(...args) { callback = args; }, savePolicy() { saves++; } }, {agentId:'agent-a'});
  vm.save();
  callback[1](response(policy({canWrite:false})));
  vm.save();
  vm.load();
  callback[2](new Error('denied'));
  vm.save();
  assert.equal(saves, 0);
  assert.equal(vm.canWrite, false);
  assert.match(vm.error, /denied/);
  vm.$destroy();
});

test('late policy response from another agent cannot install permission or settings', async () => {
  const reads = [];
  const vm = create('MemoryPolicyPanel', {policy(...args) { reads.push(args); }}, {agentId:'agent-a'});
  vm.agentId = 'agent-b';
  reads[0][1](response(policy()));
  assert.equal(vm.canWrite, false);
  await Vue.nextTick();
  reads[1][1](response(policy({canWrite:false,activeMaxChars:2000})));
  reads[0][1](response(policy({activeMaxChars:9000})));
  assert.equal(vm.form.activeMaxChars, 2000);
  assert.equal(vm.canWrite, false);
  vm.$destroy();
});

test('preview displays the API selected context instead of frontend lexical scores', async () => {
  const calls = [];
  const vm = create('MemoryRecallTest', {preview(...args) { calls.push(args); }}, {agentId:'agent-a',policy:policy()});
  vm.deviceId='device-a'; vm.speakerId='speaker-a';
  await Vue.nextTick();
  vm.runTest();
  assert.deepEqual(calls[0][1], {deviceId:'device-a',speakerId:'speaker-a'});
  calls[0][2](response({snapshot:{status:'ready',truncated:true},selected_context:'私有事实\n共享事实',budgets:{maxChars:4000,maxItems:30},usage:{chars:9,items:2}}));
  assert.equal(vm.result.selected_context, '私有事实\n共享事实');
  assert.equal(vm.ready, true);
  assert.equal(vm.truncated, true);
  assert.equal(vm.scoreFacts, undefined);
  vm.$destroy();
});

test('changing preview identity or facts discards stale context and late responses', async () => {
  const calls=[];
  const vm=create('MemoryRecallTest',{preview(...args){calls.push(args);}}, {agentId:'agent-a',policy:policy()});
  vm.deviceId='device-a'; await Vue.nextTick(); vm.runTest();
  vm.speakerId='speaker-a';
  calls[0][2](response({selected_context:'old-person'}));
  assert.equal(vm.result,null);
  await Vue.nextTick(); vm.runTest();
  vm.revision++;
  calls[1][2](response({selected_context:'old-facts'}));
  assert.equal(vm.result,null);
  await Vue.nextTick(); vm.runTest();
  vm.$destroy(); calls[2][2](response({selected_context:'closed'}));
  assert.equal(vm.result,null);
});

test('preview failure clears any previous result and reports failure', async () => {
  const calls=[];
  const vm=create('MemoryRecallTest',{preview(...args){calls.push(args);}}, {agentId:'agent-a',policy:policy()});
  vm.deviceId='device-a'; await Vue.nextTick(); vm.runTest();
  calls[0][2](response({snapshot:{status:'ready'},selected_context:'current'}));
  vm.runTest(); calls[1][3](new Error('preview unavailable'));
  assert.equal(vm.result,null); assert.equal(vm.running,false); assert.match(vm.error,/unavailable/);
  vm.$destroy();
});

test('manual edit keeps zero scores and sends explicit private/device attribution', () => {
  const vm=create('MemoryEditDialog',{}, {canWrite:true,memoryScope:'DEVICE',memoryId:'fact-1',memory:{canonicalText:'咖啡偏好',importance:0,confidence:0,payload:'{"speaker_id":"speaker-a"}',macAddress:'device-a'}});
  let saved; vm.$on('save', (...args)=>{saved=args;});
  vm.handleSave();
  assert.equal(saved[1].visibility,'private'); assert.equal(saved[1].speakerId,'speaker-a'); assert.equal(saved[1].deviceId,'device-a');
  assert.equal(saved[1].importance,0); assert.equal(saved[1].confidence,0);
  vm.$destroy();
});

test('manual fact edit blocks missing identity and read-only writes', () => {
  const vm=create('MemoryEditDialog',{}, {canWrite:true,memoryScope:'DEVICE',memory:{canonicalText:'个人资料'}});
  let count=0; vm.$on('save',()=>count++);
  vm.handleSave();
  vm.attribution={visibility:'private',speakerId:'',deviceId:'device-a'}; vm.handleSave();
  vm.attribution.speakerId='speaker-a'; vm.canWrite=false; vm.handleSave();
  assert.equal(count,0); vm.$destroy();
});

test('failed session tasks can only be retried with write permission', () => {
  const reads=[]; const retries=[];
  const vm=create('MemorySessionTasks', {sessionTasks(...args){reads.push(args);},retrySessionTask(...args){retries.push(args);}}, {agentId:'agent-a',canWrite:false});
  reads[0][1](response([{taskId:'task-a',status:'FAILED'}]));
  vm.retry(vm.tasks[0]); assert.equal(retries.length,0);
  vm.canWrite=true; vm.retry({taskId:'task-b',status:'SUCCEEDED'}); assert.equal(retries.length,0);
  vm.retry(vm.tasks[0]); assert.equal(retries.length,1);
  retries[0][2](response(null)); assert.equal(reads.length,2);
  vm.$destroy();
});

function managementVm(memoryApi = {}) {
  const component = loadVueComponent('src/views/MemoryManagement.vue', { '@/apis/api': { __esModule: true, default: { memory: memoryApi } } });
  const vm = new (Vue.extend({ ...component, render(h) { return h('div'); } }))();
  vm.$message = { success() {}, error() {}, warning() {} };
  return vm;
}

test('management separates archived facts from active facts and guards manual creation', () => {
  const vm = managementVm();
  vm.dashboard={facts:[{status:'active'},{status:'archived'},{status:'deleted'}]};
  assert.equal(vm.activeFacts.length,1); assert.equal(vm.listedFacts.length,2);
  vm.handleRemember(); assert.equal(vm.editVisible,false);
  vm.memoryPolicy=policy(); vm.handleRemember();
  assert.equal(vm.editVisible,true); assert.equal(vm.editMemoryId,'');
  vm.$destroy();
});

test('DEVICE rebuild must select a bound device and sends its MAC', () => {
  const vm=managementVm();
  vm.memoryPolicy=policy({memoryScope:'DEVICE'});
  assert.equal(vm.validateRebuildDevice(),false);
  vm.rebuildDeviceId='foreign-device'; assert.equal(vm.validateRebuildDevice(),false);
  vm.rebuildDeviceId='device-a'; assert.equal(vm.validateRebuildDevice(),true);
  assert.equal(vm.rebuildPayload(true).deviceId,'device-a');
  vm.memoryPolicy=policy(); assert.equal('deviceId' in vm.rebuildPayload(true),false);
  vm.$destroy();
});

test('forget request keeps selected person and device boundaries', () => {
  const requests=[];
  const vm=managementVm({forget(...args){requests.push(args);}});
  vm.agentId='agent-a'; vm.memoryPolicy=policy({memoryScope:'DEVICE'});
  vm.forgetText='旧偏好'; vm.forgetAttribution={visibility:'private',speakerId:'speaker-a',deviceId:'device-a'};
  vm.handleForget();
  assert.equal(requests.length,1);
  assert.equal(requests[0][1].speakerId,'speaker-a');
  assert.equal(requests[0][1].deviceId,'device-a');
  assert.equal(requests[0][1].shared,false);
  vm.$destroy();
});
