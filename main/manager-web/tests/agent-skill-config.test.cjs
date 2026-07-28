const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { afterEach, beforeEach, describe, test } = require("node:test");
const Vue = require("vue/dist/vue.common.js");
const { loadModule, loadVueComponent } = require("./helpers/loadVueComponent.cjs");

function createVm(component, propsData = {}) {
  const Ctor = Vue.extend({
    ...component,
    render(createElement) {
      return createElement("div");
    },
  });
  return new Ctor({ propsData });
}

describe("agent fixed-broadcast skill configuration", () => {
  let matcher;

  beforeEach(() => {
    Vue.prototype.$t = (key) => key;
    Vue.prototype.$message = { error() {}, success() {}, warning() {} };
    Vue.prototype.$confirm = () => Promise.resolve();
    matcher = loadModule("src/utils/agentSkillMatcher.js");
  });

  afterEach(() => {
    delete Vue.prototype.$t;
    delete Vue.prototype.$message;
    delete Vue.prototype.$confirm;
  });

  test("normalization ignores whitespace, common punctuation, and letter case", () => {
    assert.equal(matcher.normalizeSkillText(" HUI-YI，公司！介绍？ "), "huiyi公司介绍");
  });

  test("all-keywords matching requires every term but does not require order", () => {
    const skill = {
      id: 1,
      enabled: true,
      triggerRules: [{ type: "all_keywords", terms: ["惠易公司", "介绍"] }],
    };

    assert.ok(matcher.matchSkillRule(skill, "请介绍一下，惠易公司"));
    assert.ok(matcher.matchSkillRule(skill, "惠易公司可以给我介绍介绍吗"));
    assert.equal(matcher.matchSkillRule(skill, "惠易公司天气怎么样"), null);
    assert.equal(matcher.matchSkillRule(skill, "介绍一号点位"), null);
  });

  test("preview conflict resolution follows priority, keyword count, length, sort and id", () => {
    const skills = [
      {
        id: 20,
        skillName: "公司介绍",
        enabled: true,
        priority: 10,
        sort: 0,
        triggerRules: [{ type: "all_keywords", terms: ["惠易公司", "介绍"] }],
      },
      {
        id: 30,
        skillName: "一号点位介绍",
        enabled: true,
        priority: 10,
        sort: 3,
        triggerRules: [{ type: "all_keywords", terms: ["惠易公司", "一号点位", "介绍"] }],
      },
      {
        id: 10,
        skillName: "低优先级",
        enabled: true,
        priority: 9,
        sort: 0,
        triggerRules: [{ type: "all_keywords", terms: ["惠易公司", "一号点位", "介绍", "新能源"] }],
      },
    ];

    const result = matcher.findMatchedSkill(skills, "介绍惠易公司新能源一号点位");
    assert.equal(result.skill.id, 30);
    assert.equal(matcher.describeSkillMatch(result), "惠易公司 + 一号点位 + 介绍");
  });

  test("dialog validates limits and rejects duplicate normalized keywords", () => {
    const component = loadVueComponent("src/components/role/AgentSkillDialog.vue", {
      "@/apis/api": { __esModule: true, default: { agent: {} } },
    });
    const vm = createVm(component, { agentId: "agent-1", visible: false });

    vm.startCreate();
    vm.draft.skillName = "惠易公司介绍";
    vm.draft.triggerRules[0].terms = ["惠易公司", "介绍"];
    vm.draft.responseText = "惠易新能源专注新能源结构件的研发与生产。";
    assert.equal(vm.validateDraft(), "");

    vm.draft.triggerRules[0].terms = ["惠 易公司", "惠易公司"];
    assert.equal(vm.validateDraft(), "agentSkill.validation.duplicateTerm");

    vm.draft.triggerRules[0].terms = ["惠易公司"];
    assert.equal(vm.validateDraft(), "agentSkill.validation.keywordCount");
    vm.$destroy();
  });

  test("dialog preview includes a valid unsaved draft without making a server call", () => {
    const component = loadVueComponent("src/components/role/AgentSkillDialog.vue", {
      "@/apis/api": { __esModule: true, default: { agent: {} } },
    });
    const vm = createVm(component, { agentId: "agent-1", visible: false });
    vm.startCreate();
    vm.draft.skillName = "惠易公司介绍";
    vm.draft.triggerRules[0].terms = ["惠易公司", "介绍"];
    vm.draft.responseText = "固定播报";
    vm.previewInput = "请介绍一下惠易公司";

    assert.equal(vm.previewResult.skill.skillName, "惠易公司介绍");
    assert.equal(vm.previewResult.match.ruleType, "all_keywords");
    vm.$destroy();
  });

  test("agent API uses the agreed collection, item and order endpoints", () => {
    const source = fs.readFileSync(
      path.resolve(__dirname, "../src/apis/module/agent.js"),
      "utf8"
    );
    assert.match(source, /getAgentSkills[\s\S]*\/agent\/\$\{agentId\}\/skills`[\s\S]*\.method\('GET'\)/);
    assert.match(source, /createAgentSkill[\s\S]*\/agent\/\$\{agentId\}\/skills`[\s\S]*\.method\('POST'\)/);
    assert.match(source, /updateAgentSkill[\s\S]*\/agent\/\$\{agentId\}\/skills\/\$\{skillId\}`[\s\S]*\.method\('PUT'\)/);
    assert.match(source, /deleteAgentSkill[\s\S]*\/agent\/\$\{agentId\}\/skills\/\$\{skillId\}`[\s\S]*\.method\('DELETE'\)/);
    assert.match(source, /saveAgentSkillOrder[\s\S]*\/agent\/\$\{agentId\}\/skills\/order`[\s\S]*\.data\(\{ items \}\)/);
  });
});
