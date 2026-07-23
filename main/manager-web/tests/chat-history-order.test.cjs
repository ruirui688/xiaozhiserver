const assert = require("node:assert/strict");
const { afterEach, beforeEach, describe, test } = require("node:test");
const Vue = require("vue/dist/vue.common.js");
const { loadVueComponent } = require("./helpers/loadVueComponent.cjs");

function createVm() {
  const component = loadVueComponent("src/components/ChatHistoryDialog.vue", {
    "@/utils": { debounce: (fn) => fn },
    "@/apis/api": { __esModule: true, default: {} },
  });
  const Ctor = Vue.extend({
    ...component,
    render(createElement) {
      return createElement("div");
    },
  });
  return new Ctor({
    propsData: { visible: false, agentId: "agent-1", agentName: "助手" },
  });
}

describe("chat history logical ordering", () => {
  beforeEach(() => {
    Vue.prototype.$t = (key) => key;
  });

  afterEach(() => {
    delete Vue.prototype.$t;
  });

  test("interrupted assistant reply stays before the next user turn", () => {
    const vm = createVm();
    const rows = [
      {
        id: "user-1",
        chatType: 1,
        content: "第一个问题",
        createdAt: "2026-07-23T14:32:15.551Z",
        metricsJson: JSON.stringify({
          turn_id: "turn-1",
          started_at_epoch_ms: 1784817135000,
          message_role: "user",
        }),
      },
      {
        id: "user-2",
        chatType: 1,
        content: "打断后的问题",
        createdAt: "2026-07-23T14:32:19.390Z",
        metricsJson: JSON.stringify({
          turn_id: "turn-2",
          started_at_epoch_ms: 1784817139000,
          message_role: "user",
        }),
      },
      {
        id: "assistant-1",
        chatType: 2,
        content: "被打断的回答",
        createdAt: "2026-07-23T14:32:19.811Z",
        metricsJson: JSON.stringify({
          turn_id: "turn-1",
          started_at_epoch_ms: 1784817135000,
          message_role: "assistant",
          outcome: { status: "aborted", reason: "aec_barge_in" },
          trace: {
            steps: [{
              sequence: 1,
              type: "interruption",
              reason: "aec_barge_in",
              response_audio_started: true,
              generated_audio_duration_ms: 600,
            }],
          },
        }),
      },
    ].map(vm.normalizeMessage);

    const sorted = vm.sortMessagesByTurn(rows);

    assert.deepEqual(sorted.map((message) => message.id), [
      "user-1",
      "assistant-1",
      "user-2",
    ]);
    assert.deepEqual(sorted[1].metricsSummary[0], {
      label: "状态",
      value: "已被打断",
    });
    assert.equal(sorted[1].metricsTrace[0].label, "回复已打断");
    assert.match(sorted[1].metricsTrace[0].detail, /AEC检测到开口/);
    vm.$destroy();
  });

  test("legacy rows without metrics keep their server order", () => {
    const vm = createVm();
    const rows = [
      { id: "old-1", chatType: 1, content: "A", createdAt: "2026-07-23T14:00:00Z" },
      { id: "old-2", chatType: 2, content: "B", createdAt: "2026-07-23T14:00:01Z" },
    ].map(vm.normalizeMessage);

    assert.deepEqual(
      vm.sortMessagesByTurn(rows).map((message) => message.id),
      ["old-1", "old-2"]
    );
    vm.$destroy();
  });

  test("orders tool call and result between user and assistant", () => {
    const vm = createVm();
    const logicalStart = 1784817135000;
    const row = (id, chatType, role, sequence) => vm.normalizeMessage({
      id,
      chatType,
      content: id,
      createdAt: `2026-07-23T14:32:${10 + sequence}.000Z`,
      metricsJson: JSON.stringify({
        turn_id: "turn-tools",
        started_at_epoch_ms: logicalStart,
        message_role: role,
        message_sequence: sequence,
      }),
    });
    const rows = [
      row("assistant", 2, "assistant", 4),
      row("tool-result", 3, "tool", 3),
      row("user", 1, "user", 1),
      row("tool-call", 3, "tool", 2),
    ];

    assert.deepEqual(
      vm.sortMessagesByTurn(rows).map((message) => message.id),
      ["user", "tool-call", "tool-result", "assistant"]
    );
    vm.$destroy();
  });
});
