const assert = require('node:assert/strict');
const { test } = require('node:test');
const Vue = require('vue');
const fs = require('node:fs');
const path = require('node:path');
const compiler = require('vue-template-compiler');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const { platformErrorMessage, allowanceServiceState } = loadModule('src/utils/allowance.js');
const settled = () => new Promise(resolve => setImmediate(resolve));
const text = vnode => [vnode.text || '', ...(vnode.children || []).map(text)].join('');
function component(t, permissions) {
  const calls = [], success = [];
  const definition = loadVueComponent('src/ops/OpsAllowance.vue', { '@/apis/platform': { operations: {
    accounts: async () => ({ list: [], total: 0 }), allowanceRequests: async () => ({ list: [] }),
    pool: async () => ({ total: '801', allocated: '801', available: '0' }),
    grant: async data => { calls.push(['grant', data]); throw new Error('ISSUANCE_POOL_EXHAUSTED'); },
    adjustPool: async data => { calls.push(['pool', data]); },
  } } });
  const source = fs.readFileSync(path.resolve(__dirname, '../src/ops/OpsAllowance.vue'), 'utf8');
  const compiled = compiler.compileToFunctions(compiler.parseComponent(source).template.content);
  const vm = new Vue({ ...definition, render: compiled.render, staticRenderFns: compiled.staticRenderFns,
    beforeCreate() { this.$store = { state: { userInfo: { audience: 'OPS', permissions } } }; this.$message = { success: message => success.push(message), error() {} }; },
  });
  t.after(() => vm.$destroy());return { vm, calls, success };
}
test('known issuance errors map to fixed Chinese guidance without changing generic failures', () => {
  for (const [code, phrase] of [['ISSUANCE_POOL_EXHAUSTED','本次未发放'],['GRANT_LIMIT_EXCEEDED','单笔上限'],['OPERATOR_DAILY_GRANT_LIMIT','将超过操作员今日发放限额'],['POOL_ALREADY_ALLOCATED','已分配'],['ALLOWANCE_ALREADY_USED','预占']]) {
    assert.match(platformErrorMessage(code),new RegExp(phrase));assert.ok(!platformErrorMessage(code).includes(code));
  }
  assert.equal(platformErrorMessage('请联系管理员'),'请联系管理员');assert.equal(platformErrorMessage(undefined),'请求失败，请稍后重试');
  assert.match(platformErrorMessage('ALLOWANCE_EXHAUSTED'),/不足/);assert.doesNotMatch(platformErrorMessage('ALLOWANCE_EXHAUSTED'),/用完/);
  assert.doesNotMatch(allowanceServiceState({serviceStatus:'ALLOWANCE_EXHAUSTED'}).description,/用完/);
});
test('grant failure stays in form with Chinese message and never adjusts pool or reports success', async t => {
  const {vm,calls,success}=component(t,['allowance.read','allowance.grant']);await settled();
  vm.open('grant',{userId:'9223372036854775806',username:'fixture'});vm.form.amount='100';vm.form.reason='初始注册';const requestId=vm.form.requestId;await vm.save();
  assert.equal(calls.length,1);assert.equal(calls[0][0],'grant');assert.equal(calls[0][1].userId,'9223372036854775806');assert.equal(vm.form.requestId,requestId);
  assert.match(vm.saveError,/总池.*不足/);assert.equal(vm.dialog,true);assert.equal(vm.saving,false);assert.equal(vm.form.amount,'100');assert.deepEqual(success,[]);
  assert.match(text(vm._render()),/联系发放池管理员/);assert.doesNotMatch(text(vm._render()),/页面点击「调整总池」/);
  assert.match(text(vm._render()),/普通发放属于额外额度/);assert.match(text(vm._render()),/补发注册赠额/);assert.equal(calls[0][1].reason,'初始注册');
  vm.open('pool');assert.equal(vm.action,'grant');assert.equal(calls.length,1);
});
test('authorized operators receive manual pool guidance; opening adjustment neither funds nor resets it', async t => {
  const {vm,calls}=component(t,['allowance.read','allowance.grant','pool.read','pool.adjust']);await settled();
  vm.open('grant',{userId:'1'});assert.match(text(vm._render()),/页面点击「调整总池」/);assert.match(text(vm._render()),/单独确认/);assert.equal(calls.length,0);
  vm.open('pool');assert.equal(vm.form.amount,'');assert.match(text(vm._render()),/本次增减额，不是调整后的总额/);assert.match(text(vm._render()),/不会直接给用户发钱/);assert.match(text(vm._render()),/不会在重启时重置/);assert.equal(calls.length,0);
  vm.form.amount='25';vm.form.reason='explicit test budget';await vm.save();assert.equal(calls.length,1);assert.equal(calls[0][0],'pool');assert.equal(calls[0][1].amount,'25');
});
