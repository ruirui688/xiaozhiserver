const assert = require("node:assert/strict");
const { afterEach, beforeEach, describe, test } = require("node:test");
const Vue = require("vue/dist/vue.common.js");
const { loadModule, loadVueComponent } = require("./helpers/loadVueComponent.cjs");

function createVm(component, propsData = {}) {
  const Ctor = Vue.extend({
    ...component,
    render(createElement) { return createElement("div"); },
  });
  return new Ctor({ propsData });
}

describe("workspace agent quota", () => {
  const originalLocalStorage = global.localStorage;
  let warnings;

  beforeEach(() => {
    warnings = [];
    global.localStorage = { getItem: () => null, setItem() {} };
    Vue.prototype.$t = (key) => key;
    Vue.prototype.$message = { warning: (message) => warnings.push(message) };
  });

  afterEach(() => {
    global.localStorage = originalLocalStorage;
    delete Vue.prototype.$t;
    delete Vue.prototype.$message;
  });

  test("quota API targets the current workspace endpoint", () => {
    const state = {};
    const request = {
      url(value) { state.url = value; return request; },
      method(value) { state.method = value; return request; },
      success() { return request; },
      fail() { return request; },
      networkFail() { return request; },
      send() { return request; },
    };
    const exports = loadModule("src/apis/module/workspace.js", {
      "../api": { getServiceUrl: () => "/xiaozhi" },
      "../httpRequest": { __esModule: true, default: { sendRequest: () => request } },
    });

    exports.default.quota(() => {});

    assert.equal(state.url, "/xiaozhi/workspace/current/quota");
    assert.equal(state.method, "GET");
  });

  test("home blocks the add dialog when the selected workspace is full", () => {
    const component = loadVueComponent("src/views/home.vue", {
      "@/apis/api": { __esModule: true, default: {} },
      "@/utils/featureManager": { __esModule: true, default: {} },
    });
    const vm = createVm(component);
    vm.canWrite = true;
    vm.workspaceQuota = { agentCount: 10, agentLimit: 10, agentStorageLimitBytes: 209715200 };

    vm.showAddDialog();

    assert.equal(vm.addDeviceDialogVisible, false);
    assert.deepEqual(warnings, ["home.workspaceAgentLimitReached"]);
    vm.$destroy();
  });

  test("agent card reports bounded storage percentage", () => {
    const component = loadVueComponent("src/components/DeviceItem.vue", {
      "@/i18n": { __esModule: true, default: {} },
    });
    const vm = createVm(component, {
      device: { storageBytes: 250, storageLimitBytes: 200 },
    });

    assert.equal(vm.storagePercentage, 100);
    assert.equal(vm.storageProgressColor, "#f56c6c");
    assert.equal(vm.formatBytes(200 * 1024 * 1024), "200.0 MB");
    vm.$destroy();
  });
});
