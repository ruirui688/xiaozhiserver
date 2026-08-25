const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const assert = require('node:assert/strict')

const root = path.resolve(__dirname, '..')
const panel = fs.readFileSync(path.join(root, 'src/components/maintenance/MaintenancePolicyPanel.vue'), 'utf8')
const api = fs.readFileSync(path.join(root, 'src/apis/module/maintenance.js'), 'utf8')

test('maintenance is explicitly scoped and memory never offers autonomous web mode', () => {
  assert.match(panel, /resourceType === 'KNOWLEDGE'/)
  assert.match(panel, /DISCOVERY_REVIEW/)
  assert.match(panel, /DISCOVERY_AUTO/)
  assert.match(panel, /记忆维护不会联网，所有变更必须人工审批/)
  assert.match(panel, /resource-type="MEMORY"|resourceType === 'MEMORY'/)
})

test('maintenance API keeps policy run and approval under one resource path', () => {
  assert.match(api, /maintenance\/\$\{String\(type\)\.toLowerCase\(\)\}/)
  assert.match(api, /\/policy/)
  assert.match(api, /\/runs/)
  assert.match(api, /\/proposals\/\$\{proposalId\}\/approve/)
})
