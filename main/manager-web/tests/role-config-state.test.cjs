const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { afterEach, beforeEach, describe, test } = require("node:test");
const Vue = require("vue/dist/vue.common.js");
const { loadVueComponent } = require("./helpers/loadVueComponent.cjs");

function flushVue() {
  return new Promise((resolve) => Vue.nextTick(resolve));
}

function deferredCallbacks() {
  const calls = [];
  return {
    calls,
    fn(...args) {
      calls.push(args);
    },
  };
}

function createVm(component, propsData = {}) {
  const Ctor = Vue.extend({
    ...component,
    render(createElement) {
      return createElement("div");
    },
  });
  const vm = new Ctor({ propsData });
  return vm;
}

describe("role configuration component state", () => {
  let messages;

  beforeEach(() => {
    messages = { error: [], warning: [], success: [] };
    Vue.prototype.$t = (key) => key;
    Vue.prototype.$route = { query: { agentId: "agent-1" } };
    Vue.prototype.$router = { push() {} };
    Vue.prototype.$message = {
      error: (value) => messages.error.push(value),
      warning: (value) => messages.warning.push(value),
      success: (value) => messages.success.push(value),
      info() {},
    };
  });

  afterEach(() => {
    delete Vue.prototype.$t;
    delete Vue.prototype.$route;
    delete Vue.prototype.$router;
    delete Vue.prototype.$message;
  });

  test("late server hydration preserves dirty fields and fills untouched model fields", async () => {
    const api = {
      model: {
        getPluginFunctionList(params, success) {
          success({ data: { code: 0, data: [{ id: "weather", name: "天气", fields: "[]" }] } });
        },
      },
      knowledgeBase: {
        getKnowledgeBaseList(params, success) {
          success({ data: { code: 0, data: { list: [{ id: "kb-1", name: "产品资料", status: 1 }] } } });
        },
      },
    };
    const component = loadVueComponent("src/components/role/RoleEditDialog.vue", {
      "@/apis/api": { __esModule: true, default: api },
      "@/apis/httpRequest": { __esModule: true, default: {} },
      "@/i18n": { __esModule: true, default: { t: (key) => key } },
    });
    const vm = createVm(component, {
      initialForm: {},
      currentFunctions: [],
      currentKnowledgeBases: [],
    });

    vm.form.systemPrompt = "用户刚输入的角色设定";
    await flushVue();
    await vm.hydrateAgentConfig({
      agentName: "服务端助手",
      systemPrompt: "服务端旧设定",
      llmModelId: "llm-server",
      functions: [],
    });
    await flushVue();

    assert.equal(vm.form.systemPrompt, "用户刚输入的角色设定");
    assert.equal(vm.form.agentName, "服务端助手");
    assert.equal(vm.form.model.llmModelId, "llm-server");
    vm.$destroy();
  });

  test("saved model and knowledge mapping survive a close and reopen round trip", async () => {
    const api = {
      model: { getPluginFunctionList: (params, success) => success({ data: { code: 0, data: [] } }) },
      knowledgeBase: {
        getKnowledgeBaseList(params, success) {
          success({ data: { code: 0, data: { list: [{ id: "kb-1", name: "产品资料", status: 1 }] } } });
        },
      },
    };
    const component = loadVueComponent("src/components/role/RoleEditDialog.vue", {
      "@/apis/api": { __esModule: true, default: api },
      "@/apis/httpRequest": { __esModule: true, default: {} },
      "@/i18n": { __esModule: true, default: { t: (key) => key } },
    });
    const firstVm = createVm(component, {
      currentFunctions: [],
      currentKnowledgeBases: [{
        id: "kb-1",
        name: "产品资料",
        queryPolicy: "仅查产品",
      }],
    });
    firstVm.form.model.llmModelId = "llm-1";
    firstVm.capabilityMappingsReady = true;
    const savedPayload = firstVm.submitSaveConfig("agent-1");
    firstVm.$destroy();

    const reopenedVm = createVm(component, {
      currentFunctions: [],
      currentKnowledgeBases: [],
    });
    let restoredKnowledge = [];
    reopenedVm.$on("update:currentKnowledgeBases", (value) => {
      restoredKnowledge = value;
      reopenedVm._props.currentKnowledgeBases = value;
    });

    await reopenedVm.hydrateAgentConfig({
      llmModelId: savedPayload.llmModelId,
      functions: savedPayload.functions.map((mapping) => ({
        ...mapping,
        paramInfo: JSON.stringify(mapping.paramInfo),
      })),
    });
    await flushVue();

    assert.equal(reopenedVm.form.model.llmModelId, "llm-1");
    assert.deepEqual(restoredKnowledge.map((item) => item.id), ["kb-1"]);
    assert.equal(restoredKnowledge[0].queryPolicy, "仅查产品");
    assert.deepEqual(reopenedVm.submitSaveConfig("agent-1").functions, savedPayload.functions);
    reopenedVm.$destroy();
  });

  test("doubao ASR hotword selection is visible and survives save hydration", async () => {
    const api = {
      model: { getPluginFunctionList: (params, success) => success({ data: { code: 0, data: [] } }) },
      knowledgeBase: {
        getKnowledgeBaseList: (params, success) => success({ data: { code: 0, data: { list: [] } } }),
      },
    };
    const component = loadVueComponent("src/components/role/RoleEditDialog.vue", {
      "@/apis/api": { __esModule: true, default: api },
      "@/apis/httpRequest": { __esModule: true, default: {} },
      "@/i18n": { __esModule: true, default: { t: (key) => key } },
    });
    const vm = createVm(component, {
      modelOptions: {
        ASR: [{ value: "asr-doubao", type: "doubao_stream" }],
      },
      currentFunctions: [],
      currentKnowledgeBases: [],
    });

    await vm.hydrateAgentConfig({
      asrModelId: "asr-doubao",
      asrHotwordId: "example-hotword-table-b",
      asrHotwordIds: ["example-hotword-table-a", "example-hotword-table-b"],
      functions: [],
    });

    assert.equal(vm.supportsAsrHotword, true);
    const saved = vm.submitSaveConfig("agent-1");
    assert.equal(saved.asrHotwordId, "example-hotword-table-b");
    assert.deepEqual(saved.asrHotwordIds, [
      "example-hotword-table-a",
      "example-hotword-table-b",
    ]);
    vm.form.model.asrModelId = "";
    assert.equal(vm.supportsAsrHotword, false);
    vm.$destroy();
  });

  test("hotword configuration links to the Volcengine console safely", () => {
    const source = fs.readFileSync(
      path.resolve(__dirname, "../src/components/role/AsrHotwordField.vue"),
      "utf8"
    );

    assert.match(
      source,
      /href="https:\/\/console\.volcengine\.com\/speech\/new\/hot-word\?projectName=default"/
    );
    assert.match(source, /target="_blank"/);
    assert.match(source, /rel="noopener noreferrer"/);
    assert.match(source, /expanded: false/);
    assert.match(source, /v-show="expanded"/);
    assert.match(source, /管理热词/);
  });

  test("legacy single hotword id is promoted to the candidate list", async () => {
    const component = loadVueComponent("src/components/role/RoleEditDialog.vue", {
      "@/apis/api": {
        __esModule: true,
        default: {
          model: {
            getPluginFunctionList: (params, success) => success({ data: { code: 0, data: [] } }),
          },
          knowledgeBase: {
            getKnowledgeBaseList: (params, success) => success({ data: { code: 0, data: { list: [] } } }),
          },
        },
      },
      "@/apis/httpRequest": { __esModule: true, default: {} },
      "@/i18n": { __esModule: true, default: { t: (key) => key } },
    });
    const vm = createVm(component, {
      currentFunctions: [],
      currentKnowledgeBases: [],
    });

    await vm.hydrateAgentConfig({
      asrHotwordId: "legacy-table-id",
      functions: [],
    });

    const saved = vm.submitSaveConfig("agent-1");
    assert.equal(saved.asrHotwordId, "legacy-table-id");
    assert.deepEqual(saved.asrHotwordIds, ["legacy-table-id"]);
    vm.$destroy();
  });

  test("removing the active candidate selects the next available hotword table", () => {
    const component = loadVueComponent("src/components/role/AsrHotwordField.vue");
    const vm = createVm(component, {
      ids: ["table-a", "table-b"],
      activeId: "table-b",
    });
    let selection = null;
    vm.$on("change", (value) => { selection = value; });

    vm.removeId(1);

    assert.deepEqual(selection, { ids: ["table-a"], activeId: "table-a" });
    vm.$destroy();
  });

  test("default knowledge query policy is concise and domain neutral", () => {
    const component = loadVueComponent("src/components/role/RoleEditDialog.vue", {
      "@/apis/api": { __esModule: true, default: {} },
      "@/apis/httpRequest": { __esModule: true, default: {} },
      "@/i18n": { __esModule: true, default: { t: (key) => key } },
    });
    const vm = createVm(component, {
      currentFunctions: [],
      currentKnowledgeBases: [],
    });

    const policy = vm.defaultKnowledgeQueryPolicy();
    assert.match(policy, /用户当前问题/);
    assert.doesNotMatch(policy, /酒类|酒杯|酒具|器皿/);
    assert.ok(policy.length <= 60);
    vm.$destroy();
  });

  test("memory model changes preserve the user's text or text-and-audio choice", () => {
    const component = loadVueComponent("src/components/role/RoleEditDialog.vue", {
      "@/apis/api": { __esModule: true, default: {} },
      "@/apis/httpRequest": { __esModule: true, default: {} },
      "@/i18n": { __esModule: true, default: { t: (key) => key } },
    });
    const vm = createVm(component, {
      initialForm: {
        memModelId: "Memory_managed_memory",
        chatHistoryConf: 1,
      },
      currentFunctions: [],
      currentKnowledgeBases: [],
    });

    vm.handleModelChange("Memory", "Memory_managed_memory");
    assert.equal(vm.form.chatHistoryConf, 1);
    assert.equal(vm.showLegacySummaryMemory, false);

    vm.handleModelChange("Memory", "Memory_nomem");
    assert.equal(vm.form.chatHistoryConf, 0);

    vm.form.model.memModelId = "Memory_managed_memory";
    vm.handleModelChange("Memory", "Memory_managed_memory");
    assert.equal(vm.form.chatHistoryConf, 1);

    vm.form.chatHistoryConf = 2;
    vm.updateChatHistoryConf();
    vm.form.model.memModelId = "Memory_mem_local_short";
    vm.handleModelChange("Memory", "Memory_mem_local_short");
    assert.equal(vm.form.chatHistoryConf, 2);
    assert.equal(vm.showLegacySummaryMemory, true);
    assert.equal(vm.submitSaveConfig("agent-1").chatHistoryConf, 2);

    vm.replaceFormWithoutTracking({
      ...vm.form,
      chatHistoryConf: 1,
      model: {
        ...vm.form.model,
        memModelId: "Memory_managed_memory",
      },
    });
    vm.handleModelChange("Memory", "Memory_nomem");
    vm.form.model.memModelId = "Memory_managed_memory";
    vm.handleModelChange("Memory", "Memory_managed_memory");
    assert.equal(vm.form.chatHistoryConf, 1);
    vm.$destroy();
  });

  test("failed save keeps the draft, reports the error, and duplicate clicks are gated", async () => {
    const update = deferredCallbacks();
    const api = {
      agent: {
        updateAgentConfig: update.fn,
        saveAgentTags() {
          throw new Error("标签不应在配置失败后保存");
        },
      },
    };
    const component = loadVueComponent("src/views/roleConfig.vue", {
      "@/apis/api": { __esModule: true, default: api },
      "@/i18n": { __esModule: true, default: { t: (key) => key } },
      "@/utils/featureManager": { __esModule: true, default: {} },
    });
    const vm = createVm(component);
    const draft = { systemPrompt: "未保存但必须保留" };
    let markedSaved = false;
    vm.agentConfigLoaded = true;
    vm.$refs.editDialog = {
      isReadyToSave: () => true,
      submitSaveConfig: () => draft,
      markSaved: () => { markedSaved = true; },
    };

    const first = vm.handleSave();
    const second = vm.handleSave();
    assert.equal(update.calls.length, 1);
    update.calls[0][3](new Error("网络中断"));
    await Promise.all([first, second]);

    assert.equal(draft.systemPrompt, "未保存但必须保留");
    assert.equal(markedSaved, false);
    assert.equal(messages.error.length, 1);
    assert.match(String(messages.error[0].message), /网络中断/);
    vm.$destroy();
  });

  test("reports tag failure as a partial save without discarding the saved config", async () => {
    const api = {
      agent: {
        updateAgentConfig(agentId, config, success) {
          success({ data: { code: 0, data: null } });
        },
        saveAgentTags(agentId, tags, success, failure) {
          failure(new Error("标签服务暂时不可用"));
        },
      },
    };
    const component = loadVueComponent("src/views/roleConfig.vue", {
      "@/apis/api": { __esModule: true, default: api },
      "@/i18n": { __esModule: true, default: { t: (key) => key } },
      "@/utils/featureManager": { __esModule: true, default: {} },
    });
    const vm = createVm(component);
    let markedSaved = false;
    vm.agentConfigLoaded = true;
    vm.dynamicTags = [{ id: 1, tagName: "重要" }];
    vm.$refs.editDialog = {
      isReadyToSave: () => true,
      submitSaveConfig: () => ({ systemPrompt: "已保存配置" }),
      markSaved: () => { markedSaved = true; },
    };

    await vm.handleSave();

    assert.equal(markedSaved, true);
    assert.equal(messages.error.length, 0);
    assert.equal(messages.warning.length, 1);
    assert.match(String(messages.warning[0].message), /标签服务暂时不可用/);
    vm.$destroy();
  });
});

describe("knowledge selector request ordering", () => {
  test("stale list responses and selection restoration cannot erase the latest selection", async () => {
    Vue.prototype.$t = (key) => key;
    Vue.prototype.$message = { error() {} };
    const requests = deferredCallbacks();
    const component = loadVueComponent("src/components/KnowledgeBaseSelectorDialog.vue", {
      "@/apis/api": {
        __esModule: true,
        default: { knowledgeBase: { getKnowledgeBaseList: requests.fn } },
      },
    });
    const vm = createVm(component, {
      value: true,
      selected: [{ id: "kb-selected", name: "已选知识库", queryPolicy: "保留策略" }],
    });
    vm.dialogVisible = true;
    vm.selectedRows = [{ id: "kb-selected", name: "已选知识库", queryPolicy: "保留策略" }];

    vm.fetchKnowledgeBases();
    vm.fetchKnowledgeBases();
    requests.calls[1][1]({ data: { code: 0, data: { list: [{ id: "kb-new", status: 1 }], total: 1 } } });
    requests.calls[0][1]({ data: { code: 0, data: { list: [{ id: "kb-stale", status: 1 }], total: 1 } } });
    assert.deepEqual(vm.knowledgeBases.map((item) => item.id), ["kb-new"]);

    vm.$refs.knowledgeTable = {
      clearSelection: () => vm.handleSelectionChange([]),
      toggleRowSelection() {},
    };
    vm.restoreSelection();
    assert.equal(vm.selectedRows[0].queryPolicy, "保留策略");
    vm.$destroy();
    delete Vue.prototype.$t;
    delete Vue.prototype.$message;
  });
});
