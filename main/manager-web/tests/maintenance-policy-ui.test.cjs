const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const assert = require('node:assert/strict')

const root = path.resolve(__dirname, '..')
const panel = fs.readFileSync(path.join(root, 'src/components/maintenance/MaintenancePolicyPanel.vue'), 'utf8')
const conversation = fs.readFileSync(path.join(root, 'src/components/maintenance/MaintenanceConversationPanel.vue'), 'utf8')
const api = fs.readFileSync(path.join(root, 'src/apis/module/maintenance.js'), 'utf8')
const knowledgeApi = fs.readFileSync(path.join(root, 'src/apis/module/knowledgeBase.js'), 'utf8')
const uploadPage = fs.readFileSync(path.join(root, 'src/views/KnowledgeFileUpload.vue'), 'utf8')

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
  assert.match(api, /\/runs\/\$\{runId\}\/events\?after_id=/)
  assert.match(api, /\/proposals\/\$\{proposalId\}\/approve/)
})

test('knowledge approval remains applying until indexing and exposes persistent live events', () => {
  assert.match(conversation, /同步入库中/)
  assert.match(conversation, /已入库/)
  assert.match(conversation, /window\.setInterval\([^]*1000\)/)
  assert.match(conversation, /runEvents/)
  assert.match(conversation, /loadEvents/)
  assert.match(conversation, /previous === 'APPLYING' && item\.status === 'APPLIED'/)
  assert.match(conversation, /已批准，正在抓取并发布/)
})

test('knowledge maintenance is an operator-guided conversation with rich proposal preview', () => {
  assert.match(panel, /初始维护提示词/)
  assert.match(panel, /customPrompt/)
  assert.match(panel, /MaintenanceConversationPanel/)
  assert.match(conversation, /发送并运行/)
  assert.match(conversation, /userInstruction/)
  assert.match(conversation, /proposal\.sourceUri/)
  assert.match(conversation, /evidence\(proposal\)/)
  assert.match(conversation, /批准并入库/)
  assert.match(conversation, /建议淘汰旧来源/)
  assert.match(conversation, /批准移除旧来源/)
})

test('knowledge maintenance entry follows backend capability and exposes backend failures', () => {
  assert.match(knowledgeApi, /getKnowledgeBase\(datasetId/)
  assert.match(uploadPage, /!readOnly && canManageMaintenance/)
  assert.match(uploadPage, /dataset\.canManageMaintenance/)
  assert.match(panel, /error && error\.data && error\.data\.msg/)
})
