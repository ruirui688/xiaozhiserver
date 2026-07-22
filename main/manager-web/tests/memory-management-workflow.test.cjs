const assert = require("node:assert/strict");
const { afterEach, beforeEach, describe, test } = require("node:test");
const Vue = require("vue/dist/vue.common.js");
const { loadVueComponent } = require("./helpers/loadVueComponent.cjs");

function apiResponse(data) {
  return { data: { code: 0, data } };
}

function createVm(component) {
  const Ctor = Vue.extend({
    ...component,
    render(createElement) {
      return createElement("div");
    },
  });
  return new Ctor();
}

function loadMemoryManagement(memoryApi) {
  return loadVueComponent("src/views/MemoryManagement.vue", {
    "@/apis/api": {
      __esModule: true,
      default: { memory: memoryApi },
    },
  });
}

describe("memory management rebuild workflow", () => {
  let messages;
  let scheduledTimers;
  let originalSetTimeout;
  let originalClearTimeout;

  beforeEach(() => {
    messages = { error: [], warning: [], success: [], info: [] };
    scheduledTimers = [];
    originalSetTimeout = global.setTimeout;
    originalClearTimeout = global.clearTimeout;
    global.setTimeout = (callback, delay) => {
      const timer = { callback, delay, cleared: false };
      scheduledTimers.push(timer);
      return timer;
    };
    global.clearTimeout = (timer) => {
      if (timer) timer.cleared = true;
    };
    Vue.prototype.$route = {
      query: { agentId: "agent-1", agentName: "测试智能体" },
    };
    Vue.prototype.$router = { push() {} };
    Vue.prototype.$message = {
      error: (value) => messages.error.push(value),
      warning: (value) => messages.warning.push(value),
      success: (value) => messages.success.push(value),
      info: (value) => messages.info.push(value),
    };
  });

  afterEach(() => {
    global.setTimeout = originalSetTimeout;
    global.clearTimeout = originalClearTimeout;
    delete Vue.prototype.$route;
    delete Vue.prototype.$router;
    delete Vue.prototype.$message;
  });

  test("mounted page queries latest task and resumes polling a running task", () => {
    const calls = { dashboard: [], latest: [], poll: [] };
    const component = loadMemoryManagement({
      dashboard(agentId, success) {
        calls.dashboard.push(agentId);
        success(apiResponse({ facts: [], candidates: [] }));
      },
      latestRebuildTask(agentId, success) {
        calls.latest.push(agentId);
        success(apiResponse({
          taskId: "task-running",
          status: "RUNNING",
          progress: 40,
        }));
      },
      rebuildTask(...args) {
        calls.poll.push(args);
      },
    });
    const vm = createVm(component);

    component.mounted.call(vm);

    assert.deepEqual(calls.dashboard, ["agent-1"]);
    assert.deepEqual(calls.latest, ["agent-1"]);
    assert.equal(vm.rebuildTask.taskId, "task-running");
    assert.equal(vm.maintenanceOperation, "重建记忆");
    assert.equal(scheduledTimers.length, 1);
    assert.equal(scheduledTimers[0].delay, 2000);
    assert.equal(vm.rebuildTaskPollTimer, scheduledTimers[0]);
    assert.equal(calls.poll.length, 0);

    component.beforeDestroy.call(vm);
  });

  test("destroying the page clears the pending rebuild poll timer", () => {
    const component = loadMemoryManagement({
      dashboard(agentId, success) {
        success(apiResponse({ facts: [], candidates: [] }));
      },
      latestRebuildTask(agentId, success) {
        success(apiResponse({ taskId: "task-running", status: "RUNNING" }));
      },
    });
    const vm = createVm(component);
    component.mounted.call(vm);
    const timer = vm.rebuildTaskPollTimer;

    component.beforeDestroy.call(vm);

    assert.equal(timer.cleared, true);
    assert.equal(vm.rebuildTaskPollTimer, null);
  });

  test("re-entering restores a succeeded task without scheduling another poll", () => {
    let latestStatus = "RUNNING";
    let pollCalls = 0;
    const component = loadMemoryManagement({
      dashboard(agentId, success) {
        success(apiResponse({ facts: [], candidates: [] }));
      },
      latestRebuildTask(agentId, success) {
        const task = latestStatus === "RUNNING"
          ? { taskId: "task-1", status: "RUNNING", progress: 80 }
          : {
              taskId: "task-1",
              status: "SUCCEEDED",
              progress: 100,
              result: { sessionsRead: 6, factsChanged: 2 },
            };
        success(apiResponse(task));
      },
      rebuildTask() {
        pollCalls += 1;
      },
    });
    const firstVm = createVm(component);
    component.mounted.call(firstVm);
    const firstTimer = firstVm.rebuildTaskPollTimer;
    component.beforeDestroy.call(firstVm);
    assert.equal(firstTimer.cleared, true);

    latestStatus = "SUCCEEDED";
    scheduledTimers = [];
    const reopenedVm = createVm(component);
    component.mounted.call(reopenedVm);

    assert.equal(reopenedVm.rebuildTask.status, "SUCCEEDED");
    assert.deepEqual(reopenedVm.rebuildTask.result, {
      sessionsRead: 6,
      factsChanged: 2,
    });
    assert.match(reopenedVm.maintenanceStatusText, /读取 6 个会话，实际变更 2 条事实/);
    assert.equal(reopenedVm.rebuildTaskPollTimer, null);
    assert.equal(scheduledTimers.length, 0);
    assert.equal(pollCalls, 0);

    component.beforeDestroy.call(reopenedVm);
  });

  test("latest-task API failure preserves the task already shown on the page", () => {
    const existingTask = {
      taskId: "task-existing",
      status: "SUCCEEDED",
      progress: 100,
      result: { sessionsRead: 4, factsChanged: 1 },
    };
    const component = loadMemoryManagement({
      latestRebuildTask(agentId, success, failure) {
        failure(new Error("network unavailable"));
      },
    });
    const vm = createVm(component);
    vm.agentId = "agent-1";
    vm.rebuildTask = existingTask;

    vm.restoreRebuildTask();

    assert.equal(vm.rebuildTask, existingTask);
    assert.equal(vm.rebuildTask.taskId, "task-existing");
    assert.equal(vm.rebuildTask.status, "SUCCEEDED");
    assert.equal(vm.rebuildTaskPollTimer, null);

    component.beforeDestroy.call(vm);
  });
});
