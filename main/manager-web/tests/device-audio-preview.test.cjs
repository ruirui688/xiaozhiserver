const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { describe, test } = require("node:test");
const { loadModule, loadVueComponent } = require("./helpers/loadVueComponent.cjs");

describe("device real audio preview", () => {
  test("ticket API uses authenticated POST endpoint", () => {
    const state = { url: "", method: "" };
    const requestService = {
      clearRequestTime() {},
      sendRequest() {
        const request = {
          url(value) { state.url = value; return request; },
          method(value) { state.method = value; return request; },
          success(callback) { request.successCallback = callback; return request; },
          networkFail() { return request; },
          send() { request.successCallback({ data: { code: 0 } }); return request; },
        };
        return request;
      },
    };
    const exports = loadModule("src/apis/module/device.js", {
      "../api": { getServiceUrl: () => "/xiaozhi" },
      "../httpRequest": { __esModule: true, default: requestService },
    });

    let response;
    exports.default.createAudioPreviewTicket("device-a", value => { response = value; });

    assert.equal(state.url, "/xiaozhi/device/device-a/audio-preview-ticket");
    assert.equal(state.method, "POST");
    assert.deepEqual(response, { data: { code: 0 } });
  });

  test("dialog handles exact Opus stream controls and busy state", () => {
    const component = loadVueComponent("src/components/DeviceAudioPreviewDialog.vue", {
      "opus-decoder": { OpusDecoder: class {} },
      "@/apis/api": { __esModule: true, default: { device: {} } },
    });
    const context = {
      status: "connecting",
      retryIndex: 3,
      decodeChain: Promise.resolve(),
      clearStreamStopTimer() {},
      resetDecoder() {},
      scheduleWaitingStatus() {},
      resetPlaybackAfterGap() {},
    };

    component.methods.handleControlMessage.call(context, JSON.stringify({ type: "ready" }));
    assert.equal(context.status, "waiting");
    assert.equal(context.retryIndex, 0);

    component.methods.handleControlMessage.call(context, JSON.stringify({ type: "listener_busy" }));
    assert.equal(context.status, "busy");
  });

  test("device table exposes per-row preview action and cleanup safeguards", () => {
    const pageSource = fs.readFileSync(
      path.resolve(__dirname, "../src/views/DeviceManagement.vue"),
      "utf8"
    );
    const dialogSource = fs.readFileSync(
      path.resolve(__dirname, "../src/components/DeviceAudioPreviewDialog.vue"),
      "utf8"
    );
    assert.match(pageSource, /handleAudioPreview\(scope\.row\)/);
    assert.match(pageSource, /DeviceAudioPreviewDialog/);
    assert.match(dialogSource, /binaryType = 'arraybuffer'/);
    assert.match(dialogSource, /decoder\.free\(\)/);
    assert.match(dialogSource, /audioContext\.close\(\)/);
    assert.match(dialogSource, /requestAnimationFrame/);
  });
});
