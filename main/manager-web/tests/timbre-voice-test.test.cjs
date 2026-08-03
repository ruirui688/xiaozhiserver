const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { describe, test } = require("node:test");
const { loadModule, loadVueComponent } = require("./helpers/loadVueComponent.cjs");

describe("timbre real voice test", () => {
  test("API calls the authenticated voice test endpoint", () => {
    const state = { url: "", method: "", timeout: 0 };
    const requestService = {
      clearRequestTime() {},
      sendRequest() {
        const request = {
          url(value) { state.url = value; return request; },
          method(value) { state.method = value; return request; },
          timeout(value) { state.timeout = value; return request; },
          success(callback) { request.successCallback = callback; return request; },
          fail() { return request; },
          networkFail() { return request; },
          send() { request.successCallback({ data: { code: 0 } }); return request; },
        };
        return request;
      },
    };
    const exports = loadModule("src/apis/module/timbre.js", {
      "../api": { getServiceUrl: () => "/xiaozhi" },
      "../httpRequest": { __esModule: true, default: requestService },
    });

    exports.default.testVoice("voice-a", () => {});

    assert.equal(state.url, "/xiaozhi/ttsVoice/voice-a/test");
    assert.equal(state.method, "POST");
    assert.equal(state.timeout, 130000);
  });

  test("component stores playable audio only for a verified success", () => {
    const component = loadVueComponent("src/components/TtsModel.vue", {
      "@/apis/api": {
        __esModule: true,
        default: {
          timbre: {
            testVoice(id, callback) {
              callback({
                data: {
                  code: 0,
                  data: {
                    success: true,
                    verified: true,
                    message: "真实音频已生成",
                    testAudioDataUrl: "data:audio/wav;base64,UklGRg==",
                    firstAudioMs: 120,
                    responseTimeMs: 360,
                  },
                },
              });
            },
          },
        },
      },
      "./AudioPlayer.vue": { __esModule: true, default: {} },
    });
    const row = { id: "voice-a", editing: false };
    const context = {
      testingVoiceId: "",
      $set(target, key, value) { target[key] = value; },
      $t(key) { return key; },
      $message: { success() {}, error() {}, warning() {} },
      isValidAudioUrl: component.methods.isValidAudioUrl,
    };

    component.methods.testVoice.call(context, row);

    assert.equal(row.testSuccess, true);
    assert.equal(row.testAudioUrl, "data:audio/wav;base64,UklGRg==");
    assert.equal(row.firstAudioMs, 120);
    assert.equal(row.responseTimeMs, 360);
    assert.equal(context.testingVoiceId, "");
  });

  test("component does not expose audio when the provider test fails", () => {
    const component = loadVueComponent("src/components/TtsModel.vue", {
      "@/apis/api": {
        __esModule: true,
        default: {
          timbre: {
            testVoice(id, callback) {
              callback({
                data: {
                  code: 500,
                  msg: "厂商没有返回音频",
                  data: {
                    success: false,
                    verified: false,
                    message: "音色与当前资源不兼容",
                  },
                },
              });
            },
          },
        },
      },
      "./AudioPlayer.vue": { __esModule: true, default: {} },
    });
    const row = { id: "voice-b", editing: false };
    const context = {
      testingVoiceId: "",
      $set(target, key, value) { target[key] = value; },
      $t(key) { return key; },
      $message: { success() {}, error() {}, warning() {} },
      isValidAudioUrl: component.methods.isValidAudioUrl,
    };

    component.methods.testVoice.call(context, row);

    assert.equal(row.testSuccess, false);
    assert.equal(row.testAudioUrl, "");
    assert.equal(row.testMessage, "音色与当前资源不兼容");
  });

  test("component exposes a per-row test action and accepts audio data URLs", () => {
    const source = fs.readFileSync(
      path.resolve(__dirname, "../src/components/TtsModel.vue"),
      "utf8"
    );
    assert.match(source, /@click="testVoice\(scope\.row\)"/);
    assert.match(source, /result\.success === true/);
    assert.match(source, /result\.verified === true/);
    assert.match(source, /testAudioDataUrl/);
    assert.match(source, /\^data:audio/);
  });
});
