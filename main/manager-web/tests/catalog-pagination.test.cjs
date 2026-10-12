const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const Vue = require('vue');
const compiler = require('vue-template-compiler');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');

function nodes(vnode) { return [vnode, ...(vnode.children || []).flatMap(nodes)]; }
function text(vnode) { return [vnode.text || '', ...(vnode.children || []).map(text)].join(''); }

for (const [file, loader, prefix] of [
  ['ModelConfig.vue', 'loadData', 'modelConfig'],
  ['ParamsManagement.vue', 'fetchParams', 'paramManagement'],
]) {
  function create(t, { total = 0, currentPage = 1, loading = false } = {}) {
    const source = `src/views/${file}`;
    const component = loadVueComponent(source, { '@/apis/api': { __esModule: true, default: {} } });
    const template = compiler.parseComponent(fs.readFileSync(path.resolve(__dirname, '..', source), 'utf8')).template.content;
    const compiled = compiler.compileToFunctions(template);
    const calls = [];
    const vm = new Vue({
      ...component,
      created: undefined,
      mounted: undefined,
      updated: undefined,
      beforeDestroy: undefined,
      methods: { ...component.methods, [loader]() { calls.push(this.currentPage); } },
      render: compiled.render,
      staticRenderFns: compiled.staticRenderFns,
      beforeCreate() { this.$t = key => key; this.$store = { state: { userInfo: { audience: 'OPS', permissions: [] } } }; },
    });
    Object.assign(vm, { total, currentPage, pageSize: 10, loading });
    t.after(() => vm.$destroy());
    return { vm, calls };
  }
  function button(vm, label) { return nodes(vm._render()).find(node => node.tag === 'button' && text(node).trim() === `${prefix}.${label}`); }

  test(`${file}: an empty search disables forward navigation and sends no page requests`, t => {
    const { vm, calls } = create(t);
    assert.equal(button(vm, 'nextPage').data.attrs.disabled, true);
    assert.equal(button(vm, 'prevPage').data.attrs.disabled, true);
    assert.equal(button(vm, 'firstPage').data.attrs.disabled, true);
    vm.goNext(); vm.goPrev(); vm.goFirst(); vm.goToPage(1);
    assert.equal(vm.currentPage, 1);
    assert.deepEqual(calls, []);
  });

  test(`${file}: only valid new pages load and the last page cannot advance`, t => {
    const { vm, calls } = create(t, { total: 21 });
    assert.equal(button(vm, 'nextPage').data.attrs.disabled, false);
    vm.goNext(); vm.goNext();
    assert.equal(vm.currentPage, 3);
    assert.equal(button(vm, 'nextPage').data.attrs.disabled, true);
    for (const invalid of [0, -1, 4, 1.5, '2', NaN, 3]) vm.goToPage(invalid);
    vm.goNext();
    assert.deepEqual(calls, [2, 3]);
    vm.goPrev(); vm.goFirst(); vm.goFirst();
    assert.deepEqual(calls, [2, 3, 2, 1]);
  });

  test(`${file}: loading blocks rapid repeat navigation and stale pages stay bounded`, t => {
    const { vm, calls } = create(t, { total: 21, currentPage: 2, loading: true });
    for (const label of ['firstPage', 'prevPage', 'nextPage']) assert.equal(button(vm, label).data.attrs.disabled, true);
    vm.goFirst(); vm.goPrev(); vm.goNext(); vm.goToPage(3);
    assert.deepEqual(calls, []);
    assert.equal(vm.currentPage, 2);
    vm.loading = false;
    vm.total = 0;
    assert.equal(button(vm, 'nextPage').data.attrs.disabled, true);
    vm.goNext(); vm.goPrev();
    assert.deepEqual(calls, []);
  });
}
