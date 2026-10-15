#!/usr/bin/env node
'use strict';
// No Docker startup, database access, credentials, or production writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const root = path.resolve(__dirname, '..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const install = read('deploy/production/install');
const assignments = install.split('\n').filter(line => /^PLATFORM_TRIAL_(ENABLED|AMOUNT|POLICY|MAX_USERS)=/.test(line)).join('\n');
assert.equal(assignments.split('\n').length, 8, 'Installer policy assignment contract changed: review the test');
function resolve(existing, overrides) {
  const env = {...process.env};
  for (const key of Object.keys(env)) if (/^(XIAOZHI_)?PLATFORM_TRIAL_/.test(key)) delete env[key];
  Object.assign(env, overrides);
  const entries = Object.entries(existing).map(([key,value]) => {
    assert.match(key, /^XIAOZHI_PLATFORM_TRIAL_[A-Z_]+$/);
    assert.match(value, /^[a-zA-Z0-9.-]+$/);
    return `${key}) printf '%s' '${value}' ;;`;
  }).join('\n');
  return execFileSync('bash', ['-c', `set -eu\nread_existing_env() { case "$1" in\n${entries}\nesac; }\n${assignments}\nprintf '%s|%s|%s|%s' "$PLATFORM_TRIAL_ENABLED" "$PLATFORM_TRIAL_AMOUNT" "$PLATFORM_TRIAL_POLICY" "$PLATFORM_TRIAL_MAX_USERS"`], {env, encoding:'utf8'});
}
assert.equal(resolve({}, {}), 'true|100|trial-v1|0');
assert.equal(resolve({XIAOZHI_PLATFORM_TRIAL_ENABLED:'false',XIAOZHI_PLATFORM_TRIAL_AMOUNT:'0'}, {}), 'false|0|trial-v1|0');
assert.equal(resolve({XIAOZHI_PLATFORM_TRIAL_ENABLED:'false',XIAOZHI_PLATFORM_TRIAL_AMOUNT:'0'}, {XIAOZHI_PLATFORM_TRIAL_ENABLED:'true',XIAOZHI_PLATFORM_TRIAL_AMOUNT:'100'}), 'true|100|trial-v1|0');
assert.equal(resolve({XIAOZHI_PLATFORM_TRIAL_POLICY:'trial-v2',XIAOZHI_PLATFORM_TRIAL_MAX_USERS:'50'}, {}), 'true|100|trial-v2|50');
for (const [key,value] of [['ENABLED','true'],['AMOUNT','100']]) {
  assert.ok(read('deploy/production/env.example').includes(`XIAOZHI_PLATFORM_TRIAL_${key}=${value}\n`));
  assert.ok(read('deploy/production/docker-compose.yml').includes('${XIAOZHI_PLATFORM_TRIAL_'+key+':-'+value+'}'));
  assert.ok(read('main/manager-api/src/main/resources/application.yml').includes('${XIAOZHI_PLATFORM_TRIAL_'+key+':'+value+'}'));
}
console.log('Trial deployment: default 100, explicit disable, upgrade preservation and override checks passed');
