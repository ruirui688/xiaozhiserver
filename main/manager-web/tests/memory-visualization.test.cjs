const assert = require('node:assert/strict');
const { test } = require('node:test');
const Vue = require('vue/dist/vue.common.js');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const visual = loadModule('src/views/memory/memoryVisualState.mjs');
const now = Date.parse('2026-10-10T12:00:00+08:00');
const policy = { memoryScope: 'DEVICE', activeMaxChars: 1000, devices: [{deviceId:'device-a',label:'客厅设备'}], speakers: [{speakerId:'alice',label:'小林'}] };
const fact = {memoryId:'fact-a',agentId:'agent-a',status:'active',canonicalText:'喜欢茶',scopeId:'device-a',payload:'{"speaker_id":"alice"}'};

function create(name, propsData) {
  const component = loadVueComponent(`src/views/memory/${name}.vue`);
  return new (Vue.extend({...component, render(h) { return h('div'); }}))({propsData});
}

test('expiry agrees with the server GMT+8 Date format and supports revision epoch values', () => {
  assert.equal(visual.timestamp('2026-10-10 12:00:00'), now);
  assert.equal(visual.timestamp('2026-10-10T04:00:00Z'), now);
  assert.equal(visual.timestamp(now), now);
  assert.equal(visual.memoryStatus({...fact,expiresAt:'2026-10-10 12:00:00'}, now), 'expired');
  assert.equal(visual.memoryStatus({...fact,status:'archived',expiresAt:now - 1}, now), 'archived');
  assert.equal(visual.memoryStatus({...fact,expiresAt:'invalid'}, now), 'unknown');
  assert.equal(visual.memoryStatus({}, now), 'unknown');
});

test('visual identity resolves labels without converting damaged or historical identity to shared', () => {
  assert.equal(visual.memoryIdentity(fact, policy).speakerLabel, '小林');
  assert.equal(visual.memoryIdentity(fact, policy).deviceLabel, '客厅设备');
  assert.equal(visual.memoryIdentity({...fact,payload:'{"speaker_id":"old-person"}'}, policy).speakerLabel, 'old-person');
  assert.equal(visual.memoryIdentity({...fact,payload:'not-json'}, policy).visibility, 'unknown');
  assert.equal(visual.memoryIdentity({...fact,payload:'{"speaker_id":17}'}, policy).visibility, 'unknown');
  assert.equal(visual.memoryIdentity({...fact,payload:'{}',scopeId:'agent-a',macAddress:'agent-a'}, policy).deviceId, '');
  const scoped = visual.memoryIdentity({...fact,macAddress:'historical-source'}, policy);
  assert.equal(scoped.deviceLabel, 'historical-source');
  assert.equal(scoped.deviceScopeLabel, '仅设备：客厅设备');
  assert.equal(visual.memoryIdentity({...fact,scopeId:''}, policy).deviceScopeLabel, '设备归属缺失，需核实');
  assert.equal(visual.memoryIdentity(fact, {...policy,memoryScope:'AGENT'}).deviceScopeLabel, '同智能体所有设备');
});

test('overview excludes expired facts from active capacity and counts Unicode code points', () => {
  const facts = [{...fact,canonicalText:'茶😀'}, {...fact,memoryId:'expired',expiresAt:now - 1}, {...fact,memoryId:'archive',status:'archived'}];
  const vm = create('MemoryOverview', {facts,now,policy,candidates:[{status:'pending'},{status:'confirmed'}],tombstones:[{status:'active'}]});
  assert.deepEqual({...vm.summary.counts}, {active:1,expired:1,archived:1,deleted:0,unknown:0});
  assert.equal(vm.summary.activeChars, 2);
  assert.equal(vm.privateCount, 1);
  assert.equal(vm.pendingCount, 1);
  assert.equal(vm.forgetCount, 1);
  assert.equal(vm.nextExpiry, undefined);
  vm.$destroy();
});

test('list expiration and person filters compose and react to the clock crossing expiry', async () => {
  const expiresAt = now + 1000;
  const vm = create('MemoryList', {facts:[{...fact,expiresAt},{...fact,memoryId:'shared',payload:'{}'}],policy,now});
  assert.equal(vm.filteredFacts.length, 2);
  vm.filterSpeaker='alice';
  assert.equal(vm.filteredFacts.length, 1);
  vm.now=now + 1000;
  assert.equal(vm.filteredFacts.length, 0);
  vm.filterStatus='expired';
  assert.equal(vm.filteredFacts.length, 1);
  assert.equal(vm.identity(vm.filteredFacts[0]).speakerLabel, '小林');
  vm.searchQuery='小林';
  assert.equal(vm.filteredFacts.length, 1);
  vm.filterSpeaker=''; vm.filterStatus='active'; vm.searchQuery='';
  vm.now=now; vm.pageSize=1;
  await Vue.nextTick();
  vm.currentPage=2;
  vm.now=now+1000;
  await Vue.nextTick();
  assert.equal(vm.currentPage, 1);
  assert.equal(vm.paginatedFacts[0].memoryId, 'shared');
  vm.$destroy();
});

test('detail uses effective expiry, structured evidence, and separately scoped forget rules', () => {
  const vm=create('MemoryDetailPanel',{memory:{...fact,expiresAt:now-1,payload:'{"speaker_id":"alice","evidence_quote":"我喜欢茶"}'},policy,now});
  assert.equal(vm.effectiveStateLabel,'已到期');
  assert.equal(vm.evidence,'我喜欢茶');
  assert.equal(vm.identity.speakerLabel,'小林');
  assert.equal(vm.confirmationLabel,'来源未标注');
  vm.memory={tombstoneId:'rule',speakerId:'alice',scopeId:'device-a',matchType:'exact',patternText:'喜欢茶'};
  assert.equal(vm.identity.speakerLabel,'小林');
  assert.equal(vm.identity.deviceLabel,'客厅设备');
});

test('parent never opens undefined timeline records and routes graph forget rules to the rule detail', () => {
  const component=loadVueComponent('src/views/MemoryManagement.vue', {'@/apis/api':{__esModule:true,default:{}}});
  const ctx={detailItem:{},detailVisible:false};
  component.methods.handleOpenDetail.call(ctx,undefined);
  assert.equal(ctx.detailVisible,false);
  component.methods.handleOpenDetail.call(ctx,{tombstoneId:'rule'},'tombstone');
  assert.equal(ctx.detailType,'tomb');
  component.methods.handleOpenDetail.call(ctx,fact);
  assert.equal(ctx.detailType,'fact');
});
