const assert = require("node:assert/strict");
const { afterEach, beforeEach, describe, test } = require("node:test");
const Vue = require("vue/dist/vue.common.js");
const { loadModule, loadVueComponent } = require("./helpers/loadVueComponent.cjs");

function createRequestService(errorType, error) {
  const state = { retries: 0, methods: [] };
  const requestService = {
    clearRequestTime() {},
    reAjaxFun() { state.retries += 1; },
    sendRequest() {
      let fail;
      let networkFail;
      const request = {
        url() { return request; },
        method(value) { state.methods.push(value); return request; },
        success() { return request; },
        fail(callback) { fail = callback; return request; },
        networkFail(callback) { networkFail = callback; return request; },
        send() {
          (errorType === "business" ? fail : networkFail)(error);
          return request;
        },
      };
      return request;
    },
  };
  return { requestService, state };
}

function createView(agentApi) {
  const emptyComponent = { render(createElement) { return createElement("div"); } };
  const component = loadVueComponent("src/views/VoicePrint.vue", {
    "@/apis/api": { __esModule: true, default: { agent: agentApi } },
    "@/components/HeaderBar.vue": { __esModule: true, default: emptyComponent },
    "@/components/VersionFooter.vue": { __esModule: true, default: emptyComponent },
    "@/components/VoicePrintDialog.vue": { __esModule: true, default: emptyComponent },
  });
  const Ctor = Vue.extend({
    ...component,
    render(createElement) { return createElement("div"); },
  });
  return new Ctor();
}

describe("voiceprint delete reliability", () => {
  let messages;

  beforeEach(() => {
    messages = { success: [], error: [] };
    Vue.prototype.$t = (key) => key;
    Vue.prototype.$confirm = () => Promise.resolve();
    Vue.prototype.$message = {
      success: (message) => messages.success.push(message),
      error: (message) => messages.error.push(message),
    };
  });

  afterEach(() => {
    delete Vue.prototype.$t;
    delete Vue.prototype.$confirm;
    delete Vue.prototype.$message;
  });

  test("destructive API call reports failures without retrying", () => {
    for (const errorType of ["business", "network"]) {
      const error = { status: errorType === "business" ? 200 : 504 };
      const { requestService, state } = createRequestService(errorType, error);
      const exports = loadModule("src/apis/module/agent.js", {
        "../api": { getServiceUrl: () => "/xiaozhi" },
        "../httpRequest": { __esModule: true, default: requestService },
      });
      const responses = [];

      exports.default.deleteAgentVoicePrint("voice-1", response => responses.push(response));

      assert.deepEqual(responses, [error]);
      assert.equal(state.retries, 0);
      assert.deepEqual(state.methods, ["DELETE"]);
    }
  });

  test("view blocks duplicate deletes and releases state after failure", async () => {
    let callback;
    let calls = 0;
    const vm = createView({
      deleteAgentVoicePrint(id, done) {
        assert.equal(id, "voice-1");
        calls += 1;
        callback = done;
      },
    });

    vm.deleteVoicePrint("voice-1");
    await Promise.resolve();
    assert.equal(vm.deletingVoicePrintId, "voice-1");
    assert.equal(calls, 1);

    vm.deleteVoicePrint("voice-1");
    await Promise.resolve();
    assert.equal(calls, 1);

    callback({
      status: 200,
      data: { code: 10089, msg: "voiceprint server unavailable; local record retained" },
    });
    assert.equal(vm.deletingVoicePrintId, "");
    assert.deepEqual(messages.error, [{
      message: "voiceprint server unavailable; local record retained",
      showClose: true,
    }]);
    vm.$destroy();
  });
});
