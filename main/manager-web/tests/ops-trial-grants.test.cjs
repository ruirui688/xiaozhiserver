const assert = require('node:assert/strict');
const { test } = require('node:test');
const Vue = require('vue');
const { loadVueComponent, loadModule } = require('./helpers/loadVueComponent.cjs');
const settled = () => new Promise(resolve => setImmediate(resolve));
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return { promise, resolve, reject }; };
const largeId = '9223372036854775806';
function mount(t, permissions=['allowance.read','allowance.grant'], overrides={}) {
  const calls = [], mutations = [];
  const definition = loadVueComponent('src/ops/OpsTrialGrants.vue', { '@/apis/platform': { operations: {
    trial: async () => ({ enabled: true, policy: 'trial-v1', amount: '100', maxUsers: '0', issuedUsers: '3' }),
    trialRequests: async query => { calls.push(query); return [{ userId: largeId, status: 'REVIEW', reasonCode: 'ISSUANCE_POOL_EXHAUSTED' }]; },
    retryTrial: async data => { mutations.push(data); return [{ userId: largeId, status: 'PENDING' }]; }, ...overrides,
  } } });
  const vm = new Vue({ ...definition, beforeCreate() { this.$store={ state: { userInfo: { audience:'OPS', permissions } } }; } });
  t.after(()=>vm.$destroy()); return { vm, calls, mutations };
}
test('trial list loads only with read permission; no implicit money mutation', async t => {
  const denied=mount(t,[]);await settled();assert.equal(denied.calls.length,0);denied.vm.openTargets([{userId:largeId}]);assert.equal(denied.vm.dialog,false);
  const reader=mount(t,['allowance.read']);await settled();assert.equal(reader.calls.length,1);assert.deepEqual(reader.calls[0],{status:'REVIEW',afterUserId:'0',limit:20});reader.vm.openTargets([{userId:largeId}]);await reader.vm.retry();assert.equal(reader.mutations.length,0);assert.equal(reader.vm.dialog,false);
});
test('explicit single historical user and selected users preserve long string IDs; success is only queued', async t => {
  const {vm,mutations}=mount(t);await settled();vm.openTargets([{userId:largeId}]);assert.deepEqual(vm.targets,[largeId]);await vm.retry();assert.equal(mutations.length,0);
  vm.reason='补发测试注册赠额';await vm.retry();assert.deepEqual(mutations,[{userIds:[largeId],reason:'补发测试注册赠额'}]);assert.match(vm.notice,/不代表已到账/);assert.equal(vm.dialog,false);
});
test('cancel, invalid and oversized targets never dispatch; recheck permission before mutation', async t => {
  const {vm,mutations}=mount(t);await settled();vm.openTargets([{userId:Number.MAX_SAFE_INTEGER+1}]);assert.equal(vm.dialog,false);
  vm.openTargets(Array.from({length:101},(_,i)=>({userId:String(i+1)})));assert.equal(vm.dialog,false);
  vm.openTargets([{userId:largeId}]);vm.reason='test';vm.cancel();await vm.retry();assert.equal(mutations.length,0);
  vm.openTargets([{userId:largeId}]);vm.reason='test';vm.$store.state.userInfo.permissions=['allowance.read'];await vm.retry();assert.equal(mutations.length,0);
});
test('bounded multi-selection and cursor navigation clear selection and keep exact IDs', async t => {
  const {vm,calls}=mount(t);await settled();vm.limit=1;vm.selectRows(vm.rows);assert.equal(vm.selected.length,1);await vm.nextPage();assert.equal(calls.at(-1).afterUserId,largeId);assert.equal(vm.selected.length,0);await vm.previousPage();assert.equal(calls.at(-1).afterUserId,'0');vm.status='PENDING';await vm.resetPage();assert.equal(calls.at(-1).status,'PENDING');
  vm.selectRows([{userId:'1',status:'GRANTED'},{userId:'2',status:'PROCESSING'},{userId:'3',status:'REVIEW'}]);assert.deepEqual(vm.selected.map(row=>row.userId),['3']);
});
test('older list response cannot overwrite a newer filter and failures are visible', async t => {
  const old=deferred(),latest=deferred();let count=0;const {vm}=mount(t,undefined,{trialRequests:()=> (++count===1?old:latest).promise});vm.status='PENDING';const next=vm.resetPage();latest.resolve([{userId:'2',status:'PENDING'}]);await next;old.resolve([{userId:'1',status:'REVIEW'}]);await settled();assert.equal(vm.rows[0].userId,'2');
  assert.match(vm.reasonLabel('ISSUANCE_POOL_EXHAUSTED'),/总池/);assert.equal(vm.reasonLabel('FUTURE_CODE'),'FUTURE_CODE');
});
test('mutation error remains in confirmation; destroyed component ignores late success', async t => {
  const pending=deferred();const {vm}=mount(t,undefined,{retryTrial:()=>pending.promise});await settled();vm.openTargets([{userId:largeId}]);vm.reason='test';const saving=vm.retry();vm.cancel();assert.equal(vm.dialog,true);pending.reject(new Error('当前岗位无法补发'));await saving;assert.match(vm.saveError,/岗位/);assert.equal(vm.saving,false);
  const pending2=deferred();const second=mount(t,undefined,{retryTrial:()=>pending2.promise}).vm;await settled();second.openTargets([{userId:largeId}]);second.reason='test';const result=second.retry();second.$destroy();pending2.resolve([]);await result;assert.equal(second.notice,'');
});
test('operations API uses protected trial endpoints without pool mutation', async () => {
  const sent=[];const chain={url(value){this.path=value;return this;},method(value){this.verb=value;return this;},data(value){this.body=value;return this;},success(fn){this.done=fn;return this;},fail(){return this;},networkFail(){return this;},send(){sent.push({path:this.path,verb:this.verb,body:this.body});this.done({data:{code:0,data:[]}});}};
  const {operations}=loadModule('src/apis/platform.js',{'./httpRequest':{__esModule:true,default:{sendRequest:()=>chain}},'./api':{getServiceUrl:()=>'/xiaozhi'}});
  await operations.trialRequests({afterUserId:largeId,status:'REVIEW',limit:20});await operations.retryTrial({userIds:[largeId],reason:'test'});assert.equal(sent[0].path,'/xiaozhi/ops/platform/trial/requests');assert.equal(sent[0].body.afterUserId,largeId);assert.equal(sent[1].path,'/xiaozhi/ops/platform/trial/retry');assert.equal(sent[1].verb,'POST');
});
