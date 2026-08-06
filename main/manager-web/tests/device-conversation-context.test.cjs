const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { test } = require('node:test');

test('device management does not expose manual conversation context reset', () => {
  const pageSource = fs.readFileSync(
    path.resolve(__dirname, '../src/views/DeviceManagement.vue'),
    'utf8'
  );
  const apiSource = fs.readFileSync(
    path.resolve(__dirname, '../src/apis/module/device.js'),
    'utf8'
  );

  assert.doesNotMatch(pageSource, /handleResetConversationContext|清空上下文/);
  assert.doesNotMatch(apiSource, /resetConversationContext|conversation-context\/reset/);
  assert.match(pageSource, /handleAudioPreview\(scope\.row\)/);
  assert.match(pageSource, /handleScenes\(scope\.row\)/);
});
