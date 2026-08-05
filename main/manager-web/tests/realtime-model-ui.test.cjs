const assert = require("node:assert/strict");
const { describe, test } = require("node:test");
const Vue = require("vue/dist/vue.common.js");
const { loadModule, loadVueComponent } = require("./helpers/loadVueComponent.cjs");

describe("realtime multimodal model UI", () => {
  test("detects explicit realtime mode and the supported realtime adapter", () => {
    const realtime = loadModule("src/utils/realtimeModel.js");

    assert.equal(realtime.isRealtimeModel({ params: { conversation_mode: "realtime" } }), true);
    assert.equal(realtime.isRealtimeModel({ raw: { adapterCode: "qwen_omni_realtime" } }), true);
    assert.equal(realtime.isRealtimeModel({ raw: { adapterCode: "doubao_realtime" } }), true);
    assert.equal(realtime.isRealtimeModel({ templateCode: "LLM_DOUBAO_REALTIME" }), true);
    assert.equal(realtime.isDoubaoRealtimeModel({ adapterCode: "doubao-realtime" }), true);
    assert.equal(realtime.isRealtimeModel({ templateCode: "LLM_ALI_QWEN_OMNI_REALTIME" }), true);
    assert.equal(realtime.isRealtimeModel({ adapterCode: "openai", params: { conversation_mode: "pipeline" } }), false);
    assert.equal(realtime.hasRealtimeAdapterMismatch({
      adapterCode: "openai",
      templateCode: "LLM_ALI_QWEN_OPENAI",
      params: { conversation_mode: "realtime" },
    }), true);
    assert.equal(realtime.hasRealtimeAdapterMismatch({
      adapterCode: "qwen_omni_realtime",
      templateCode: "LLM_ALI_QWEN_OMNI_REALTIME",
      params: { conversation_mode: "realtime" },
    }), false);
    assert.equal(realtime.getConversationModeBadge({ adapterCode: "openai" }), "传统拼接");
  });

  test("agent model options expose a realtime badge without changing the selected model id", () => {
    const component = loadVueComponent("src/views/roleConfig.vue", {
      "@/apis/api": { __esModule: true, default: {} },
      "@/i18n": { __esModule: true, default: { t: (key) => key } },
      "@/utils/featureManager": { __esModule: true, default: {} },
    });

    const option = component.methods.buildModelOption({
      id: "LLM_QwenOmniRealtime",
      modelName: "Qwen Omni Realtime",
      adapterCode: "qwen_omni_realtime",
      vendorName: "阿里云百炼",
    });

    assert.equal(option.value, "LLM_QwenOmniRealtime");
    assert.equal(option.conversationMode, "realtime");
    assert.equal(option.raw.adapterCode, "qwen_omni_realtime");
    assert.match(option.label, /^\[实时多模态\]/);
  });

  test("notice selects the agent and test-specific guidance", () => {
    Vue.prototype.$t = (key) => key;
    const component = loadVueComponent("src/components/model/RealtimeModelNotice.vue");
    const Ctor = Vue.extend({
      ...component,
      render(createElement) {
        return createElement("div");
      },
    });

    const agentVm = new Ctor({
      propsData: {
        model: { typed_config: { conversation_mode: "realtime" } },
        context: "agent",
      },
    });
    assert.equal(agentVm.realtime, true);
    assert.equal(agentVm.message, "realtimeModel.agentDescription");
    agentVm.$destroy();

    const testVm = new Ctor({
      propsData: {
        model: { adapterCode: "qwen_omni_realtime" },
        context: "test",
      },
    });
    assert.equal(testVm.realtime, true);
    assert.equal(testVm.message, "realtimeModel.configDescription");
    testVm.$destroy();

    const doubaoVm = new Ctor({
      propsData: {
        model: { adapterCode: "doubao_realtime" },
        context: "agent",
      },
    });
    assert.equal(doubaoVm.realtime, true);
    assert.equal(doubaoVm.message, "realtimeModel.doubaoAgentDescription");
    doubaoVm.$destroy();

    const mismatchVm = new Ctor({
      propsData: {
        model: { adapterCode: "openai", params: { conversation_mode: "realtime" } },
        context: "test",
      },
    });
    assert.equal(mismatchVm.realtime, true);
    assert.equal(mismatchVm.adapterMismatch, true);
    assert.equal(mismatchVm.message, "realtimeModel.mismatchDescription");
    mismatchVm.$destroy();
    delete Vue.prototype.$t;
  });

  test("edit dialog preserves an explicit realtime template before generic Qwen inference", () => {
    const component = loadVueComponent("src/components/ModelEditDialog.vue", {
      "@/apis/api": { __esModule: true, default: {} },
    });
    const templates = [
      { templateCode: "LLM_ALI_QWEN_OPENAI", providerCode: "openai" },
      { templateCode: "LLM_ALI_QWEN_OMNI_REALTIME", providerCode: "qwen_omni_realtime" },
    ];
    const context = {
      allProvidersData: templates,
      findTemplate(code) {
        return templates.find((item) => item.templateCode === code);
      },
      isAliBailianQwenModel: component.methods.isAliBailianQwenModel,
    };

    assert.equal(
      component.methods.resolveTemplateCode.call(
        context,
        "LLM_ALI_QWEN_OMNI_REALTIME",
        { modelName: "Qwen3.5 Omni Flash Realtime" }
      ),
      "LLM_ALI_QWEN_OMNI_REALTIME"
    );
  });

  test("voice select creation follows backend field capability instead of the field name", () => {
    const source = require("node:fs").readFileSync(
      require("node:path").resolve(__dirname, "../src/components/ModelEditDialog.vue"),
      "utf8"
    );

    assert.match(source, /allowCreate: inputType === "select" && !!f\.allowCreate/);
    assert.doesNotMatch(source, /f\.key === "voice"/);
  });

  test("model test details expose realtime first-text and first-audio metrics", () => {
    const component = loadVueComponent("src/views/ModelConfig.vue", {
      "@/apis/api": { __esModule: true, default: {} },
    });
    const context = {
      activeTab: "llm",
      currentTestModel: { conversationMode: "realtime" },
      isRealtimeModel: component.methods.isRealtimeModel,
      $t: (key) => key,
    };

    assert.equal(
      component.methods.shouldShowFirstTextMetric.call(context, { firstTextMs: 12 }),
      true
    );
    assert.equal(
      component.methods.shouldShowFirstAudioMetric.call(context, { firstAudioMs: 24 }),
      true
    );
    assert.equal(
      component.methods.getFirstTextMetricLabel.call(context),
      "modelConfig.realtimeFirstText"
    );
  });
});
