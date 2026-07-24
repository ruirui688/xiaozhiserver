const assert = require("node:assert/strict");
const { afterEach, beforeEach, describe, test } = require("node:test");
const Vue = require("vue/dist/vue.common.js");
const { loadModule, loadVueComponent } = require("./helpers/loadVueComponent.cjs");

function createRequestService(networkError) {
  const state = { retries: 0, methods: [] };
  const requestService = {
    clearRequestTime() {},
    reAjaxFun() {
      state.retries += 1;
    },
    sendRequest() {
      let networkFail;
      const request = {
        url() { return request; },
        method(value) { state.methods.push(value); return request; },
        success() { return request; },
        networkFail(callback) { networkFail = callback; return request; },
        send() { networkFail(networkError); return request; },
      };
      return request;
    },
  };
  return { requestService, state };
}

function createDialog(agentApi) {
  const component = loadVueComponent("src/components/ChatHistoryDialog.vue", {
    "@/utils": { debounce: (fn) => fn },
    "@/apis/api": { __esModule: true, default: { agent: agentApi } },
  });
  const Ctor = Vue.extend({
    ...component,
    render(createElement) { return createElement("div"); },
  });
  return new Ctor({
    propsData: { visible: false, agentId: "agent-1", agentName: "助手" },
  });
}

describe("chat history delete reliability", () => {
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

  test("destructive API calls report a network failure without retrying", () => {
    const networkError = { status: 504 };
    const { requestService, state } = createRequestService(networkError);
    const exports = loadModule("src/apis/module/agent.js", {
      "../api": { getServiceUrl: () => "/xiaozhi" },
      "../httpRequest": { __esModule: true, default: requestService },
    });
    const api = exports.default;
    const responses = [];

    api.deleteAgentChatHistory("agent-1", (response) => responses.push(response));
    api.deleteAgentChatSession("agent-1", "session-1", (response) => responses.push(response));
    api.deleteAgentChatMessage("agent-1", 1, (response) => responses.push(response));

    assert.deepEqual(responses, [networkError, networkError, networkError]);
    assert.equal(state.retries, 0);
    assert.deepEqual(state.methods, ["DELETE", "DELETE", "DELETE"]);
  });

  test("dialog blocks duplicate deletes and surfaces the network failure", async () => {
    let deleteCallback;
    let calls = 0;
    const vm = createDialog({
      deleteAgentChatHistory(agentId, callback) {
        assert.equal(agentId, "agent-1");
        calls += 1;
        deleteCallback = callback;
      },
    });

    vm.deleteAllHistory();
    await Promise.resolve();
    assert.equal(vm.deletingKey, "all:");
    assert.equal(calls, 1);

    vm.deleteAllHistory();
    await Promise.resolve();
    assert.equal(calls, 1);

    deleteCallback({ status: 504 });
    assert.equal(vm.deletingKey, "");
    assert.deepEqual(messages.error, ["chatHistory.deleteFailed"]);
    vm.$destroy();
  });
});
