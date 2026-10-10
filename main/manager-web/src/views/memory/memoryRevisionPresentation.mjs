import { parsePayload, timestamp, memoryIdentity } from './memoryVisualState.mjs'

const actions = {
  create: ['创建记忆', 'create', 'success'],
  create_from_maintenance: ['维护创建记忆', 'create', 'success'],
  update: ['编辑记忆', 'update', ''],
  update_from_candidate: ['候选更新记忆', 'update', ''],
  update_from_maintenance: ['维护更新记忆', 'update', ''],
  update_merged_as_duplicate: ['合并重复记忆', 'delete', 'warning'],
  archived: ['归档记忆', 'archive', 'info'],
  archive_from_candidate: ['候选归档记忆', 'archive', 'info'],
  archive_from_maintenance: ['维护归档记忆', 'archive', 'info'],
  archive_for_capacity: ['容量归档', 'archive', 'info'],
  deleted: ['删除记忆', 'delete', 'danger'],
  delete_from_candidate: ['候选删除记忆', 'delete', 'danger'],
  delete_from_maintenance: ['维护删除记忆', 'delete', 'danger'],
  clear_delete: ['清空时删除记忆', 'delete', 'danger'],
  confirm_candidate: ['确认候选', 'candidate', 'success'],
  reject_candidate: ['拒绝候选', 'candidate', 'warning'],
  refresh: ['刷新记忆证据', 'update', ''],
  create_forget_tombstone: ['创建忘记规则', 'forget', 'danger'],
  forget_boundary: ['记录遗忘边界', 'forget', 'danger'],
  remember_instruction: ['处理记住指令', 'operation', ''],
  forget_instruction: ['处理忘记指令', 'forget', 'danger'],
  clear_memory: ['清空记忆', 'operation', 'danger'],
  rebuild_memory: ['重建记忆', 'operation', ''],
  review_active_memories: ['重审记忆', 'operation', ''],
  session_memory_processed: ['处理会话记忆', 'process', 'success']
}

const textValue = value => typeof value === 'string' ? value.trim() : ''
const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key)
const stateLabels = { active: '启用', archived: '已归档', deleted: '已删除', pending: '待确认', confirmed: '已确认', rejected: '已拒绝' }

export function formatRevisionTime(value, dateOnly = false) {
  const time = timestamp(value)
  if (time == null) return '时间未记录'
  const shifted = new Date(time + 8 * 60 * 60 * 1000)
  if (!Number.isFinite(shifted.getTime())) return '时间未记录'
  const iso = shifted.toISOString()
  return dateOnly ? iso.slice(0, 10) : iso.slice(0, 19).replace('T', ' ')
}

function snapshotFields(snapshot) {
  const fields = {}
  const content = textValue(snapshot.canonicalText) || textValue(snapshot.canonical_text) ||
    textValue(snapshot.patternText) || textValue(snapshot.tombstone_pattern) || textValue(snapshot.text)
  if (content) fields.content = content
  // Historical attribution comes only from the recorded snapshot.
  if (own(snapshot, 'payload') || own(snapshot, 'speakerId')) {
    const identity = memoryIdentity(snapshot)
    fields.person = identity.speakerId && identity.visibility !== 'unknown'
      ? `${identity.speakerLabel}${identity.speakerLabel === identity.speakerId ? '' : `（${identity.speakerId}）`}`
      : identity.speakerLabel
  }
  if (own(snapshot, 'macAddress') || own(snapshot, 'deviceId') || own(snapshot, 'scopeId')) fields.device = memoryIdentity(snapshot).deviceLabel
  if (textValue(snapshot.status)) fields.state = stateLabels[snapshot.status] || snapshot.status
  if (own(snapshot, 'expiresAt')) fields.expiry = snapshot.expiresAt == null || snapshot.expiresAt === ''
    ? '未设到期时间' : formatRevisionTime(snapshot.expiresAt)
  if (textValue(snapshot.matchType)) fields.match = { exact: '完全匹配', contains: '包含匹配' }[snapshot.matchType] || snapshot.matchType
  return fields
}

function countValue(value) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null
}

function operationSummary(action, after) {
  if (action === 'session_memory_processed') {
    const count = countValue(after.factsChanged)
    if (after.maintenanceMode === 'REVIEW') return count == null ? '会话处理完成，结果进入候选审核。' : `会话处理完成，生成 ${count} 条待审候选。`
    if (after.maintenanceMode === 'AUTO') return count == null ? '会话记忆维护完成。' : `会话处理完成，执行 ${count} 项记忆维护动作。`
    return '会话处理完成；记录未提供可确认的处理模式。'
  }
  if (action === 'forget_boundary') return '已记录阻止相同事实再次写入的遗忘边界；本条记录未保存内容快照。'
  return ''
}

export function presentRevision(revision = {}, facts = []) {
  const action = textValue(revision.revisionAction) || textValue(revision.action)
  const [label, kind, tagType] = own(actions, action) ? actions[action] : ['其他操作', 'operation', 'info']
  const before = parsePayload(revision.beforePayload)
  const after = parsePayload(revision.afterPayload)
  const beforeFields = snapshotFields(before)
  const afterFields = snapshotFields(after)
  const fieldLabels = { content: '内容', person: '人物归属', device: '记录设备来源', state: '存储状态', expiry: '到期时间（东八区）', match: '匹配方式' }
  const changes = Object.entries(fieldLabels)
    .filter(([key]) => own(beforeFields, key) || own(afterFields, key))
    .map(([key, fieldLabel]) => ({ key, label: fieldLabel, before: beforeFields[key], after: afterFields[key],
      changed: own(beforeFields, key) && own(afterFields, key) && beforeFields[key] !== afterFields[key] }))
  const metrics = []
  if (['clear_memory', 'rebuild_memory', 'review_active_memories'].includes(action)) {
    const labels = { sessionsRead: '读取会话', factsAdded: '新增事实', factsUpdated: '更新事实', factsArchived: '归档事实',
      factsDeleted: '删除事实', factsCleared: '清除事实', candidatesCleared: '清除候选', candidatesCreated: '生成候选', actionsProposed: '建议动作' }
    for (const [key, metricLabel] of Object.entries(labels)) {
      const value = countValue(after[key])
      if (value != null && value > 0) metrics.push({ label: metricLabel, value })
    }
  }
  const summary = operationSummary(action, after) || afterFields.content || beforeFields.content ||
    (metrics.length ? metrics.map(item => `${item.label} ${item.value}`).join('；') : '该记录未保存可展示的内容快照。')
  const currentFact = revision.memoryId ? facts.find(fact => fact && fact.memoryId === revision.memoryId && fact.status !== 'deleted') : null
  return { ...revision, action, label, kind, tagType, summary, changes, metrics, currentFact: currentFact || null,
    actorLabel: { user: '用户发起', system: '系统执行' }[revision.actorType] || textValue(revision.actorType) || '执行方未记录',
    sessionId: action === 'session_memory_processed' ? textValue(after.sessionId) : '', timestamp: timestamp(revision.createdAt) }
}

export function groupRevisions(revisions = [], facts = []) {
  const groups = new Map()
  revisions.filter(Boolean).map((revision, index) => ({ ...presentRevision(revision, facts), key: revision.revisionId || revision.id || `revision-${index}` }))
    .sort((a, b) => (b.timestamp == null ? -Infinity : b.timestamp) - (a.timestamp == null ? -Infinity : a.timestamp))
    .forEach(item => {
      const date = formatRevisionTime(item.createdAt, true)
      if (!groups.has(date)) groups.set(date, [])
      groups.get(date).push(item)
    })
  return Array.from(groups, ([date, items]) => ({ date, items }))
}
