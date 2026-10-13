const assert = require("node:assert/strict");
const { afterEach, beforeEach, test } = require("node:test");
const Vue = require("vue/dist/vue.common.js");
const { loadVueComponent } = require("./helpers/loadVueComponent.cjs");

function mountComponent(component, propsData) {
  const Ctor = Vue.extend({
    ...component,
    render(createElement) {
      return createElement("div");
    },
  });
  return new Ctor({ propsData });
}

function createEnrollmentDialog(agentApi, form = { id: null, sourceName: "张三", introduce: "家人" }) {
  const component = loadVueComponent("src/components/VoicePrintDialog.vue", {
    "@/apis/api": { __esModule: true, default: { agent: agentApi } },
  });
  const vm = mountComponent(component, {
    visible: true,
    agentId: "agent-a",
    devices: [{ deviceId: "device-a", label: "A 设备" }],
    form,
  });
  vm.validateBasicFields = done => done();
  return vm;
}

beforeEach(() => {
  Vue.prototype.$t = key => key;
  Vue.prototype.$message = {
    success() {},
    error() {},
    warning() {},
  };
});

afterEach(() => {
  delete Vue.prototype.$t;
  delete Vue.prototype.$message;
});

test("a late enrollment creation is cancelled after its dialog lifecycle closes", () => {
  let createSuccess;
  const cancelled = [];
  const vm = createEnrollmentDialog({
    createAgentVoicePrintEnrollment(payload, success) {
      assert.equal(payload.agentId, "agent-a");
      assert.equal(payload.deviceId, "device-a");
      createSuccess = success;
    },
    cancelAgentVoicePrintEnrollment(id, success) {
      cancelled.push(id);
      success && success({ data: { code: 0 } });
    },
  });

  vm.startEnrollment();
  assert.equal(vm.creatingEnrollment, true);
  vm.deactivateEnrollmentLifecycle();
  createSuccess({ data: { code: 0, data: { id: "enrollment-a", status: "collecting" } } });

  assert.deepEqual(cancelled, ["enrollment-a"]);
  assert.equal(vm.enrollmentId, "");
  assert.equal(vm.pollingTimer, null);
  vm.$destroy();
});

test("closing during a restart prevents the pending cancel callback from creating another task", () => {
  let restartCancelled;
  let createCalls = 0;
  const cancelled = [];
  const vm = createEnrollmentDialog({
    createAgentVoicePrintEnrollment() {
      createCalls += 1;
    },
    cancelAgentVoicePrintEnrollment(id, success) {
      cancelled.push(id);
      if (id === "old-enrollment" && !restartCancelled) restartCancelled = success;
    },
  });
  vm.enrollmentId = "old-enrollment";
  vm.enrollmentStatus = "collecting";

  vm.startEnrollment();
  assert.equal(typeof restartCancelled, "function");
  assert.deepEqual(cancelled, ["old-enrollment"]);
  vm.deactivateEnrollmentLifecycle();
  assert.deepEqual(cancelled, ["old-enrollment"]);
  restartCancelled({ data: { code: 0 } });

  assert.equal(createCalls, 0);
  vm.$destroy();
  assert.deepEqual(cancelled, ["old-enrollment"]);
});

test("dialog close requests cannot bypass an enrollment completion", () => {
  const vm = createEnrollmentDialog({});
  const cancelEvents = [];
  const doneArguments = [];
  vm.$on("cancel", () => cancelEvents.push("cancel"));
  const lifecycleGeneration = vm.lifecycleGeneration;

  vm.completingEnrollment = true;
  vm.beforeDialogClose(value => doneArguments.push(value));

  assert.equal(vm.lifecycleGeneration, lifecycleGeneration);
  assert.equal(vm.completingEnrollment, true);
  assert.deepEqual(cancelEvents, []);
  assert.deepEqual(doneArguments, []);

  vm.completingEnrollment = false;
  vm.beforeDialogClose(value => doneArguments.push(value));

  assert.equal(vm.lifecycleGeneration, lifecycleGeneration + 1);
  assert.deepEqual(cancelEvents, ["cancel"]);
  assert.deepEqual(doneArguments, [false]);
  vm.$destroy();
});

test("metadata saving keeps every close path locked until the request finishes", () => {
  const vm = createEnrollmentDialog({}, {
    id: "voice-a",
    sourceName: "张三",
    introduce: "家人",
  });
  let submission;
  const cancelEvents = [];
  const doneArguments = [];
  vm.$refs.form = { validate: callback => callback(true) };
  vm.$on("submit", payload => { submission = payload; });
  vm.$on("cancel", () => cancelEvents.push("cancel"));
  const lifecycleGeneration = vm.lifecycleGeneration;

  vm.submit();
  assert.equal(vm.saving, true);
  vm.beforeDialogClose(value => doneArguments.push(value));
  vm.cancel();

  assert.equal(vm.lifecycleGeneration, lifecycleGeneration);
  assert.deepEqual(cancelEvents, []);
  assert.deepEqual(doneArguments, []);

  submission.done();
  assert.equal(vm.saving, false);
  vm.beforeDialogClose(value => doneArguments.push(value));
  assert.equal(vm.lifecycleGeneration, lifecycleGeneration + 1);
  assert.deepEqual(cancelEvents, ["cancel"]);
  assert.deepEqual(doneArguments, [false]);
  vm.$destroy();
});

test("deactivating during completion releases busy once and ignores the late result", () => {
  let completeSuccess;
  const events = [];
  const vm = createEnrollmentDialog({
    completeAgentVoicePrintEnrollment(id, payload, success) {
      assert.equal(id, "enrollment-a");
      assert.equal(payload.agentId, "agent-a");
      completeSuccess = success;
    },
  });
  vm.$on("busy-change", value => events.push(`busy:${value}`));
  vm.$on("completed", () => events.push("completed"));
  vm.$on("submit", () => events.push("submit"));
  vm.enrollmentId = "enrollment-a";
  vm.enrollmentStatus = "ready";
  const context = vm.beginEnrollmentOperation("agent-a");

  vm.completeEnrollment(context);
  assert.deepEqual(events, ["busy:true"]);
  vm.deactivateEnrollmentLifecycle();
  assert.deepEqual(events, ["busy:true", "busy:false"]);

  completeSuccess({
    data: { code: 0, data: { voicePrintId: "voice-a", registrationResult: {} } },
  });
  assert.deepEqual(events, ["busy:true", "busy:false"]);
  vm.$destroy();
});

test("successful completion hands busy ownership to the parent before releasing the dialog", () => {
  let completeSuccess;
  const events = [];
  const vm = createEnrollmentDialog({
    completeAgentVoicePrintEnrollment(_id, _payload, success) {
      completeSuccess = success;
    },
  });
  vm.$on("busy-change", value => events.push(`busy:${value}`));
  vm.$on("completed", () => events.push("completed"));
  vm.$on("submit", () => events.push("submit"));
  vm.enrollmentId = "enrollment-a";
  vm.enrollmentStatus = "ready";
  const context = vm.beginEnrollmentOperation("agent-a");

  vm.completeEnrollment(context);
  completeSuccess({
    data: { code: 0, data: { voicePrintId: "voice-a", registrationResult: {} } },
  });

  assert.deepEqual(events, ["busy:true", "completed", "submit", "busy:false"]);
  vm.$destroy();
});

test("device lookup keeps the agent that initiated it even if the selector later changes", () => {
  let devicesSuccess;
  const emptyComponent = { render(createElement) { return createElement("div"); } };
  const component = loadVueComponent("src/components/person/PersonVoiceprintPanel.vue", {
    "@/apis/api": {
      __esModule: true,
      default: {
        device: {
          getAgentBindDevices(agentId, success) {
            assert.equal(agentId, "agent-a");
            devicesSuccess = success;
          },
        },
        agent: {},
        person: {},
      },
    },
    "@/components/VoicePrintDialog.vue": { __esModule: true, default: emptyComponent },
  });
  const vm = mountComponent(component, {
    personId: "person-1",
    personName: "张三",
    personDescription: "家人",
    agentIds: ["agent-a", "agent-b"],
    agents: [
      { id: "agent-a", name: "智能体 A" },
      { id: "agent-b", name: "智能体 B" },
    ],
    voicePrint: { ready: false },
    canWrite: true,
    active: true,
  });

  vm.selectedAgentId = "agent-a";
  vm.startEnrollment();
  vm.selectedAgentId = "agent-b";
  devicesSuccess({
    data: {
      code: 0,
      data: [{ id: "device-a", alias: "A 设备" }],
    },
  });

  assert.equal(vm.voiceDialogContext.agentId, "agent-a");
  assert.equal(vm.dialogAgentId, "agent-a");
  assert.deepEqual(vm.deviceOptions.map(item => item.deviceId), ["device-a"]);
  vm.$destroy();
});

test("the outer editor stays busy until completed enrollment is linked to the person", () => {
  let bindSuccess;
  const emptyComponent = { render(createElement) { return createElement("div"); } };
  const component = loadVueComponent("src/components/person/PersonVoiceprintPanel.vue", {
    "@/apis/api": {
      __esModule: true,
      default: {
        device: {},
        agent: {},
        person: {
          bindVoicePrint(personId, voicePrintId, success) {
            assert.equal(personId, "person-1");
            assert.equal(voicePrintId, "voice-1");
            bindSuccess = success;
          },
        },
      },
    },
    "@/components/VoicePrintDialog.vue": { __esModule: true, default: emptyComponent },
  });
  const vm = mountComponent(component, {
    personId: "person-1",
    personName: "张三",
    personDescription: "家人",
    agentIds: ["agent-a"],
    agents: [{ id: "agent-a", name: "智能体 A" }],
    voicePrint: { ready: false },
    canWrite: true,
    active: true,
  });
  const busyChanges = [];
  vm.$on("operation-busy-change", value => busyChanges.push(value));
  vm.voiceDialogContext = vm.captureContext({ agentId: "agent-a" });
  vm.voiceDialogVisible = true;
  vm.handleEnrollmentBusyChange(true);

  vm.closeVoiceDialog();
  assert.equal(vm.voiceDialogVisible, true);
  assert.equal(vm.voiceOperationBusy, true);

  vm.handleEnrollmentCompleted({
    voicePrintId: "voice-1",
    agentId: "agent-a",
    sourceName: "张三",
    introduce: "家人",
  });

  assert.equal(vm.voiceOperationBusy, true);
  assert.equal(vm.binding, true);
  assert.deepEqual(busyChanges, [true]);
  vm.handleEnrollmentBusyChange(false);
  assert.equal(vm.voiceOperationBusy, true);
  assert.deepEqual(busyChanges, [true]);
  bindSuccess({
    data: {
      code: 0,
      data: { id: "person-1", voicePrintReady: true, voicePrintId: "voice-1" },
    },
  });
  assert.equal(vm.voiceOperationBusy, false);
  assert.equal(vm.binding, false);
  assert.deepEqual(busyChanges, [true, false]);
  vm.$destroy();
});

test("voiceprint metadata update ignores duplicate submissions while the request is pending", () => {
  let updateSuccess;
  let updateCalls = 0;
  const emptyComponent = { render(createElement) { return createElement("div"); } };
  const component = loadVueComponent("src/components/person/PersonVoiceprintPanel.vue", {
    "@/apis/api": {
      __esModule: true,
      default: {
        device: {},
        person: {},
        agent: {
          updateAgentVoicePrint(payload, success) {
            updateCalls += 1;
            assert.equal(payload.id, "voice-1");
            updateSuccess = success;
          },
        },
      },
    },
    "@/components/VoicePrintDialog.vue": { __esModule: true, default: emptyComponent },
  });
  const vm = mountComponent(component, {
    personId: "person-1",
    personName: "张三",
    personDescription: "家人",
    agentIds: ["agent-a"],
    agents: [{ id: "agent-a", name: "智能体 A" }],
    voicePrint: { ready: true, id: "voice-1", agentId: "agent-a" },
    canWrite: true,
    active: true,
  });
  vm.voiceDialogContext = vm.captureContext({ agentId: "agent-a" });
  vm.voiceDialogVisible = true;
  let firstDoneCalls = 0;
  let duplicateDoneCalls = 0;
  const form = { id: "voice-1", sourceName: "张三", introduce: "家人" };

  vm.handleVoiceSubmit({ form, done: () => { firstDoneCalls += 1; } });
  vm.handleVoiceSubmit({ form, done: () => { duplicateDoneCalls += 1; } });

  assert.equal(updateCalls, 1);
  assert.equal(vm.updating, true);
  assert.equal(vm.voiceOperationBusy, true);
  assert.equal(duplicateDoneCalls, 0);

  updateSuccess({ data: { code: 0 } });
  assert.equal(firstDoneCalls, 1);
  assert.equal(vm.updating, false);
  assert.equal(vm.voiceOperationBusy, false);
  assert.equal(vm.voiceDialogVisible, false);
  vm.$destroy();
});
