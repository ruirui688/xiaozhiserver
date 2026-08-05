const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');
const { loadModule } = require('./helpers/loadVueComponent.cjs');

test('manual context reset uses authenticated device control endpoint', () => {
  const state = { url: '', method: '' };
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
  const api = loadModule('src/apis/module/device.js', {
    '../api': { getServiceUrl: () => '/xiaozhi' },
    '../httpRequest': { __esModule: true, default: requestService },
  });

  api.default.resetConversationContext('record-a', () => {});

  assert.equal(state.url, '/xiaozhi/device/record-a/conversation-context/reset');
  assert.equal(state.method, 'POST');
});

test('device page exposes per-device reset with short-term memory boundary', () => {
  const source = fs.readFileSync(
    path.resolve(__dirname, '../src/views/DeviceManagement.vue'),
    'utf8'
  );

  assert.match(source, /handleResetConversationContext\(scope\.row\)/);
  assert.match(source, /不会删除聊天记录或长期记忆/);
  assert.match(source, /resetConversationContext\(row\.device_id/);
});
