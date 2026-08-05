const test = require("node:test");
const assert = require("node:assert/strict");
const { loadModule } = require("./helpers/loadVueComponent.cjs");

const targets = loadModule("src/utils/personDeviceTargets.js");

test("device destination labels never repeat an unnamed MAC as the device name", () => {
  const device = {
    name: "e7:f6:68:5d:38:43",
    macAddress: "e7:f6:68:5d:38:43"
  };

  assert.equal(targets.deviceDisplayName(device), "未命名设备");
  assert.equal(targets.deviceDisplayName({ ...device, alias: "展厅入口" }), "展厅入口");
});

test("large device target search uses alias, MAC, hardware and bound agent facts", () => {
  const device = {
    id: "device-a",
    alias: "展厅入口",
    macAddress: "AA:BB:CC:DD",
    board: "esp32-s3",
    appVersion: "1.2.3",
    agentId: "agent-a",
    agentName: "迎宾助手"
  };

  assert.equal(targets.matchesDeviceTarget(device, "展厅", "all"), true);
  assert.equal(targets.matchesDeviceTarget(device, "aa:bb", "all"), true);
  assert.equal(targets.matchesDeviceTarget(device, "esp32", "all"), true);
  assert.equal(targets.matchesDeviceTarget(device, "迎宾", "agent-a"), true);
  assert.equal(targets.matchesDeviceTarget(device, "迎宾", "agent-b"), false);
});

test("named devices are sorted before devices that still need aliases", () => {
  const sorted = targets.sortDeviceTargets([
    { id: "unnamed", name: "AA", macAddress: "AA" },
    { id: "named", alias: "前台", macAddress: "BB" }
  ]);

  assert.equal(sorted[0].id, "named");
});
