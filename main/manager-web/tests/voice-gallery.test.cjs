const { test } = require('node:test');
const assert = require('node:assert/strict');
const { loadVueComponent } = require('./helpers/loadVueComponent.cjs');
const component = loadVueComponent('src/components/role/VoiceGallery.vue');
function vm(extra = {}) {
  return { ...component.data(), ...component.methods, options: [
    { value: 'system', label: '晴天', languages: '中文' },
    { value: 'clone', label: '我的声音', languages: '中文', isClone: true }
  ], value: 'system', realtime: false, allowCustom: false, ...extra };
}
test('voice selection is a draft until confirmation and opening restores saved choice', () => {
  const events = []; const state = vm({ $emit: (...args) => events.push(args) });
  state.open(); state.draft = 'clone'; assert.equal(state.value, 'system'); assert.equal(events.length, 0);
  state.open(); assert.equal(state.draft, 'system');
  state.draft = 'clone'; state.confirm(); assert.deepEqual(events, [['input', 'clone']]);
});
test('system and clone tabs filter by actual resource type, language and name', () => {
  const state = vm({ tab: 'clone', search: '我的', language: '中文' });
  assert.deepEqual(component.computed.filtered.call(state).map(v => v.value), ['clone']);
  state.language = '英语'; assert.equal(component.computed.filtered.call(state).length, 0);
});
test('stale incompatible voices cannot be selected while supported realtime custom IDs can', () => {
  const events = []; const state = vm({ $emit: (...args) => events.push(args), draft: 'foreign-voice' });
  state.confirm(); assert.equal(events.length, 0);
  state.realtime = true; state.confirm(); assert.equal(events.length, 0);
  state.allowCustom = true; state.confirm(); assert.deepEqual(events, [['input', 'foreign-voice']]);
});
test('realtime audio is not previewed through incompatible TTS route', () => {
  const state = vm({ realtime: true });
  assert.equal(state.canPreview({ isClone: true, voiceDemo: 'demo.wav' }), false);
  state.realtime = false;
  assert.equal(state.canPreview({}), false);
  assert.equal(state.canPreview({ isClone: true }), true);
});
