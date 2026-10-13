const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const apiSource = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'apis', 'module', 'person.js'),
  'utf8'
);
const personView = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'views', 'PersonProfileManagement.vue'),
  'utf8'
);
const personCard = fs.readFileSync(
  path.join(__dirname, '..', 'src', 'components', 'person', 'PersonProfileCard.vue'),
  'utf8'
);

test('reception management API covers profile appointment and workspace config', () => {
  [
    '/resource/reception/persons/${encodeURIComponent(personId)}',
    '/resource/reception/persons/${encodeURIComponent(personId)}/appointments',
    '/resource/reception/appointments/${encodeURIComponent(appointmentId)}',
    '/resource/reception/workspace-config'
  ].forEach(endpoint => assert.ok(apiSource.includes(endpoint), endpoint));
});

test('person resource page exposes reception profile appointment and workspace settings', () => {
  assert.ok(personView.includes('PersonReceptionDialog'));
  assert.ok(personView.includes('ReceptionWorkspaceDialog'));
  assert.ok(personCard.includes('接待与预约'));
  assert.ok(personView.includes('接待配置'));
});

test('person face resources bind agents and require explicit publication', () => {
  assert.ok(apiSource.includes('/resource/persons/agents'));
  assert.ok(apiSource.includes('/publish'));
  assert.ok(personView.includes('form.agentIds'));
  assert.ok(personView.includes('上传不会下发'));
  assert.ok(personCard.includes('发布变更'));
  assert.ok(!personView.includes('form.deviceIds'));
});
