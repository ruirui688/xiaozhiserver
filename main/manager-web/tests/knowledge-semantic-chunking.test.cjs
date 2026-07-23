const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { describe, test } = require("node:test");
const { loadVueComponent } = require("./helpers/loadVueComponent.cjs");

const component = loadVueComponent("src/components/KnowledgeBaseDialog.vue", {
  "@/apis/api": { __esModule: true, default: {} },
});

describe("knowledge semantic chunking configuration", () => {
  test("always persists semantic LLM chunking and managed model id", () => {
    const context = {
      parserConfigValue: { llm_enabled: false, llm_api_key: "must-not-persist" },
      parserForm: {
        llm_model_id: "llm-openai",
        llm_max_input_chars: 12000,
        llm_max_tokens: 4000,
      },
      retrievalForm: {
        mode: "normal",
        search_type: "semantic",
        semantic_level: 4,
        semantic_top_k: 10,
        faq_threshold: 1,
        semantic_threshold: 0.05,
        rerank_enabled: false,
      },
    };

    const config = JSON.parse(component.methods.buildParserConfig.call(context));

    assert.equal(config.llm_enabled, true);
    assert.equal(config.llm_model_id, "llm-openai");
    assert.equal("llm_api_key" in config, false);
  });

  test("only accepts OpenAI Chat Completions compatible models", () => {
    assert.equal(component.methods.isOpenAiCompatibleModel({ adapterCode: "openai" }), true);
    assert.equal(component.methods.isOpenAiCompatibleModel({ protocol: "OpenAI Compatible" }), true);
    assert.equal(component.methods.isOpenAiCompatibleModel({ adapterCode: "dify" }), false);
  });

  test("does not render a switch or rule-chunking fallback", () => {
    const source = fs.readFileSync(
      path.resolve(__dirname, "../src/components/KnowledgeBaseDialog.vue"),
      "utf8"
    );
    assert.equal(source.includes("<el-switch v-model=\"parserForm.llm_enabled\""), false);
    assert.equal(source.includes("使用规则切片"), false);
  });
});
