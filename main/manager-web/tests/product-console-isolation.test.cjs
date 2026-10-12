const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const compiler = require('vue-template-compiler');
const { loadModule, loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const root = path.resolve(__dirname, '..');
const session = loadModule('src/security/session.js');
const allowance = loadModule('src/utils/allowance.js');
function storage() { const values = new Map(); return { getItem: key => values.get(key) || null, setItem: (key,value) => values.set(key,value), removeItem: key => values.delete(key) }; }

test('user and employee tokens cannot overwrite or clear one another', () => {
  global.localStorage = storage(); global.sessionStorage = storage(); global.document = { documentElement: { dataset: {} } };
  session.writeSession('token','user-token');
  global.document.documentElement.dataset.console = 'OPS';
  assert.equal(session.readSession('token'),null);
  session.writeSession('token','employee-token');
  assert.equal(global.localStorage.getItem('token'),'user-token');
  session.removeSession('token');
  assert.equal(global.localStorage.getItem('token'),'user-token');
  assert.equal(global.sessionStorage.getItem('xiaozhi:ops:token'),null);
  delete global.document; delete global.localStorage; delete global.sessionStorage;
});

test('permissions require an employee audience and exact explicit actions', () => {
  assert.equal(session.hasPermission({ audience: 'USER', permissions: ['credentials.read'] },'credentials.read'),false);
  assert.equal(session.hasPermission({ audience: 'OPS', permissions: ['*'] },'credentials.read'),false);
  assert.equal(session.hasPermission({ audience: 'OPS', permissions: ['credentials.read'] },'credentials.write'),false);
  assert.equal(session.hasPermission({ audience: 'OPS', permissions: ['credentials.read'] },'credentials.read'),true);
});

test('operations navigation denies tenant content and respects independent job permissions', () => {
  const nav = loadModule('src/ops/navigation.js');
  const employee = { audience: 'OPS', permissions: ['ota.read'] };
  assert.deepEqual(nav.availableOperations(employee).map(item=>item.path),['/ota-management']);
  for (const route of ['/role-config','/memory-management','/knowledge-content-management','/services','/iam']) assert.equal(nav.canVisitOperations(employee,route),false);
  assert.equal(nav.canVisitOperations(employee,'/ota-management'),true);
});

test('team members never receive a payer balance from UI presentation and decimal precision is preserved', () => {
  const balance = { available: '9007199254740993.123456' };
  assert.equal(allowance.visibleAllowance({ canViewBalance: false, allowance: balance }),null);
  assert.equal(allowance.visibleAllowance({ canViewBalance: 'true', allowance: balance }),null);
  assert.equal(allowance.visibleAllowance({ canViewBalance: true, allowance: balance }),balance);
  assert.equal(allowance.allowanceAmount(balance.available),'9007199254740993.123456');
  assert.equal(allowance.allowanceAmount(undefined),'—');
  assert.match(allowance.allowancePayerLabel({ workspaceType: 'TEAM' }),/负责人/);
});

test('user allowance display distinguishes exact zero from nonzero sub-cent values', () => {
  for (const value of [0, '0', '0.000000000', '-0.000', '000.000']) assert.equal(allowance.allowanceDisplayAmount(value), '0.00');
  for (const value of ['0.000000001', '0.005', '0.009999999']) assert.equal(allowance.allowanceDisplayAmount(value), '小于 0.01');
  for (const value of ['-0.000000001', '-0.005', '-0.009999999']) assert.equal(allowance.allowanceDisplayAmount(value), '大于 -0.01 且小于 0');
  assert.equal(allowance.allowanceDisplayAmount('0.01'), '0.01');
  assert.equal(allowance.allowanceDisplayAmount('-0.01'), '-0.01');
});

test('user allowance display rounds decimal strings without losing large amounts or carry digits', () => {
  const cases = [['12', '12.00'], ['12.3', '12.30'], ['00012.30', '12.30'], [1.005, '1.01'],
    ['1234.567890123', '1234.57'], ['1.004999999', '1.00'], ['9.995', '10.00'], ['999.999999999', '1000.00'],
    ['-12.3456789', '-12.35'], ['-1.005', '-1.01'], ['-1.004999999', '-1.00'], ['-9.995', '-10.00'],
    ['9007199254740993.125', '9007199254740993.13'], ['999999999999999999999999999999.995', '1000000000000000000000000000000.00']];
  for (const [value, expected] of cases) assert.equal(allowance.allowanceDisplayAmount(value), expected);
  assert.equal(allowance.allowanceAmount('9007199254740993.125'), '9007199254740993.125');
});

test('user allowance display keeps malformed or absent decimal values unknown', () => {
  for (const value of [null, undefined, '', ' ', '12 ', '12\n', '12\r\n', '12.', '.12', '+12', '1e3', '1,000', 'abc', 'NaN', NaN, Infinity, -Infinity, true, [], {}]) {
    assert.equal(allowance.allowanceDisplayAmount(value), '—');
  }
});

test('uncertain allowance grant retries retain the original idempotency key', async () => {
  const calls = []; let attempts = 0;
  const component = loadVueComponent('src/ops/OpsAllowance.vue', { '@/apis/platform': { operations: { grant: async payload => { calls.push({...payload}); if (++attempts === 1) throw new Error('timeout'); } } } });
  const vm = { ...component.data(), $store: { state: { userInfo: { audience: 'OPS', permissions: ['allowance.read', 'allowance.grant'] } } }, $message: { success() {} }, load: async()=>{} };
  for (const [name,method] of Object.entries(component.methods)) vm[name] = method.bind(vm);
  vm.load = async()=>{};
  vm.open('grant', { userId: '42', username: 'test-only-user' }); vm.form.amount = '100'; vm.form.reason = '试用额度';
  await vm.save(); assert.equal(vm.dialog,true); assert.equal(vm.saving,false);
  await vm.save(); assert.equal(calls.length,2); assert.equal(calls[0].requestId,calls[1].requestId); assert.equal(vm.dialog,false);
});

test('user router has no operations routes and operations router has no tenant content routes', () => {
  const user = fs.readFileSync(path.join(root,'src/router/index.js'),'utf8');
  const ops = fs.readFileSync(path.join(root,'src/ops/router.js'),'utf8');
  for (const route of ['/model-config','/ota-management','/user-management','/params-management','/template-quick-config']) assert.ok(!user.includes(`path: '${route}'`));
  for (const route of ['/role-config','/memory-management','/device-management','/knowledge-base-management']) assert.ok(!ops.includes(`path: '${route}'`));
});

test('new user and operations templates compile on the repository Vue 2 runtime', () => {
  const files = ['src/views/Allowance.vue',...fs.readdirSync(path.join(root,'src/ops')).filter(file=>file.endsWith('.vue')).map(file=>`src/ops/${file}`)];
  for (const file of files) { const descriptor = compiler.parseComponent(fs.readFileSync(path.join(root,file),'utf8')); const compiled = compiler.compile(descriptor.template.content); assert.deepEqual(compiled.errors,[],file); }
});

test('default workspaces keep collaboration rights while fixed owners cannot be edited', () => {
  const component = loadVueComponent('src/views/OrganizationManagement.vue', { '@/apis/api': {} });
  const vm = { isOwner: true, canWrite: true, currentWorkspace: { defaultWorkspace: 1 }, isSelf: member => member.userId === 1 };
  assert.equal(component.methods.canChangeRole.call(vm, { userId: 2, role: 'MEMBER' }), true);
  assert.equal(component.methods.canChangeRole.call(vm, { userId: 2, role: 'OWNER' }), false);
  assert.equal(component.methods.canRemoveMember.call(vm, { userId: 2, role: 'OWNER' }), false);
});

test('official knowledge editor fetches complete draft entries before editing list metadata', async () => {
  const calls = [];
  const component = loadVueComponent('src/ops/OpsOfficialKnowledge.vue', { '@/apis/platform': { platformRequest: async (method,url) => { calls.push([method,url]); return { id:'draft-1',name:'Public FAQ',ragModelId:'local',entries:[{question:'Q',answer:'A',content:null}] }; } } });
  const vm = { ...component.data(), $message: { error(message) { throw new Error(message); } } };
  await component.methods.edit.call(vm,{id:'draft-1',name:'Public FAQ',scope:'OFFICIAL_DRAFT'});
  assert.deepEqual(calls,[['GET','/ops/content/knowledge/draft-1']]);
  assert.deepEqual(vm.form.entries,[{question:'Q',answer:'A',content:''}]);
  assert.equal(vm.visible,true);
});

test('managed model draft excludes legacy credentials and cannot enable itself', () => {
  const component=loadVueComponent('src/components/ModelEditDialog.vue', { '@/apis/api': {} });
  const vm={platformManaged:true,fieldJsonMap:{},modelData:{id:'model'},form:{params:{temperature:0.7,api_key:'legacy-test-only'},secrets:{api_key:'legacy-test-only'},isEnabled:1},isSecretField:component.methods.isSecretField};
  const result=component.methods.buildFormData.call(vm);
  assert.deepEqual(result.params,{temperature:0.7});
  assert.deepEqual(result.secrets,{});
  assert.equal(result.isEnabled,0);
});
