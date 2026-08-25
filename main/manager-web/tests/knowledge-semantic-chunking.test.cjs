const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { describe, test } = require("node:test");
const { loadVueComponent } = require("./helpers/loadVueComponent.cjs");

const component = loadVueComponent("src/components/KnowledgeBaseDialog.vue", {
  "@/apis/api": { __esModule: true, default: {} },
});

describe("knowledge processing strategy configuration", () => {
  const retrievalForm = {
    mode: "normal",
    search_type: "semantic",
    semantic_level: 4,
    semantic_top_k: 10,
    faq_threshold: 1,
    semantic_threshold: 0.05,
    rerank_enabled: false,
  };

  test("persists direct processing without an LLM model", () => {
    const context = {
      parserConfigValue: { llm_enabled: false, llm_api_key: "must-not-persist" },
      parserForm: {
        processing_strategy: "direct",
        llm_model_id: "",
      },
      retrievalForm,
    };

    const config = JSON.parse(component.methods.buildParserConfig.call(context));

    assert.equal(config.processing_strategy, "direct");
    assert.equal(config.llm_enabled, false);
    assert.equal(config.llm_model_id, "");
    assert.equal("llm_api_key" in config, false);
    assert.equal("llm_max_input_chars" in config, false);
    assert.equal("llm_max_tokens" in config, false);
  });

  test("persists the managed model for LLM enhanced processing", () => {
    const context = {
      parserConfigValue: {},
      parserForm: {
        processing_strategy: "llm_enhanced",
        llm_model_id: "llm-openai",
      },
      retrievalForm,
    };

    const config = JSON.parse(component.methods.buildParserConfig.call(context));

    assert.equal(config.processing_strategy, "llm_enhanced");
    assert.equal(config.llm_enabled, true);
    assert.equal(config.llm_model_id, "llm-openai");
  });

  test("only accepts OpenAI Chat Completions compatible models", () => {
    assert.equal(component.methods.isOpenAiCompatibleModel({ adapterCode: "openai" }), true);
    assert.equal(component.methods.isOpenAiCompatibleModel({ protocol: "OpenAI Compatible" }), true);
    assert.equal(component.methods.isOpenAiCompatibleModel({ adapterCode: "dify" }), false);
  });

  test("renders explicit strategies without exposing token budget controls", () => {
    const source = fs.readFileSync(
      path.resolve(__dirname, "../src/components/KnowledgeBaseDialog.vue"),
      "utf8"
    );
    assert.equal(source.includes("<el-switch v-model=\"parserForm.llm_enabled\""), false);
    assert.equal(source.includes('label="auto"'), true);
    assert.equal(source.includes('label="direct"'), true);
    assert.equal(source.includes('label="llm_enhanced"'), true);
    assert.equal(source.includes("parserForm.llm_max_input_chars"), false);
    assert.equal(source.includes("parserForm.llm_max_tokens"), false);
  });
});
