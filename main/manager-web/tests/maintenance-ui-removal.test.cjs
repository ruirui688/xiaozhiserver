const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const assert = require('node:assert/strict')

const root = path.resolve(__dirname, '..')
const read = name => fs.readFileSync(path.join(root, name), 'utf8')
const uploadPage = read('src/views/KnowledgeFileUpload.vue')
const memoryPage = read('src/views/MemoryManagement.vue')

test('resource pages no longer render or request Harness interfaces', () => {
  for (const page of [uploadPage, memoryPage]) {
    assert.doesNotMatch(page, /harness|NativeHarness|MaintenancePolicy|maintenanceWorkbench|canManageMaintenance|Api\.maintenance/i)
  }
  assert.doesNotMatch(read('src/apis/api.js'), /module\/maintenance|\bmaintenance\b/)
  for (const name of ['MaintenancePolicyPanel', 'MaintenanceProposalPanel', 'MaintenanceRunPanel', 'NativeHarnessPanel']) {
    assert.equal(fs.existsSync(path.join(root, `src/components/maintenance/${name}.vue`)), false)
  }
  assert.equal(fs.existsSync(path.join(root, 'src/apis/module/maintenance.js')), false)
})

test('ordinary knowledge management and memory operations remain available', () => {
  assert.match(uploadPage, /knowledge-source-panel/)
  assert.match(uploadPage, /@click="showRetrievalTestDialog"/)
  assert.match(uploadPage, /:readonly="readOnly"/)
  assert.match(uploadPage, /@changed="fetchFileList"/)
  for (const action of ['handleRemember', 'handleForget', 'handleRebuild', 'handleReview', 'handleClear', 'handleSaveEdit']) {
    assert.ok(memoryPage.includes(action))
  }
})
