const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const assert = require('node:assert/strict')

const root = path.resolve(__dirname, '..')
const panel = fs.readFileSync(path.join(root, 'src/components/maintenance/MaintenancePolicyPanel.vue'), 'utf8')
const proposals = fs.readFileSync(path.join(root, 'src/components/maintenance/MaintenanceProposalPanel.vue'), 'utf8')
const api = fs.readFileSync(path.join(root, 'src/apis/module/maintenance.js'), 'utf8')
const knowledgeApi = fs.readFileSync(path.join(root, 'src/apis/module/knowledgeBase.js'), 'utf8')
const uploadPage = fs.readFileSync(path.join(root, 'src/views/KnowledgeFileUpload.vue'), 'utf8')

test('maintenance settings remain resource scoped and memory never offers web discovery', () => {
  assert.match(panel, /resourceType === 'KNOWLEDGE'/)
  assert.match(panel, /DISCOVERY_REVIEW/)
  assert.match(panel, /DISCOVERY_AUTO/)
  assert.match(panel, /长期记忆维护不联网/)
  assert.match(panel, /初始提示词/)
  assert.match(panel, /customPrompt/)
})

test('native Harness launch and approvals stay behind the resource API', () => {
  assert.match(api, /maintenance\/\$\{String\(type\)\.toLowerCase\(\)\}/)
  assert.match(api, /\/console-sessions/)
  assert.match(api, /\/proposals\/\$\{proposalId\}\/approve/)
  assert.match(uploadPage, /openMaintenanceConsole/)
  assert.match(uploadPage, /payload\.launchUrl/)
  assert.match(uploadPage, /打开 Harness/)
})

test('platform UI keeps approval authority but no longer implements a custom chat client', () => {
  assert.doesNotMatch(panel, /MaintenanceConversationPanel/)
  assert.match(proposals, /审核维护提案/)
  assert.match(proposals, /scope\.row\.sourceUri/)
  assert.match(proposals, /批准/)
  assert.match(proposals, /拒绝/)
})

test('knowledge maintenance entry follows backend capability and exposes backend failures', () => {
  assert.match(knowledgeApi, /getKnowledgeBase\(datasetId/)
  assert.match(uploadPage, /!readOnly && canManageMaintenance/)
  assert.match(uploadPage, /dataset\.canManageMaintenance/)
  assert.match(panel, /error && error\.data && error\.data\.msg/)
})
