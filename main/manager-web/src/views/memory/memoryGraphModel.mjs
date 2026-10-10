import { memoryIdentity, memoryStatus, memoryStatusLabel, memorySourceLabel } from './memoryVisualState.mjs'

const text = value => String(value == null ? '' : value)
const categoryLabel = value => ({ explicit: '明确记住', manual: '手动维护' }[value] || value || '未分类')
const key = (...parts) => JSON.stringify(parts)
const audienceKey = identity => identity.visibility === 'shared' ? 'shared'
  : identity.visibility === 'unknown' ? 'unknown' : `speaker:${identity.speakerId}`
const deviceKey = identity => identity.deviceId ? `device:${identity.deviceId}` : 'missing'
const compare = (a, b) => a.label.localeCompare(b.label, 'zh-CN') || a.id.localeCompare(b.id)

function describe(record, policy, agentId, now, rule) {
  const withAgent = { ...record, agentId: record.agentId || agentId }
  const source = memoryIdentity(withAgent, policy)
  // Runtime DEVICE visibility is defined by scopeId; macAddress is only provenance.
  const identity = policy.memoryScope === 'DEVICE'
    ? memoryIdentity({ ...withAgent, deviceId: record.scopeId || '', macAddress: '' }, policy) : source
  const boundSpeaker = (policy.speakers || []).some(item => item.speakerId === identity.speakerId)
  const speakerLabel = identity.visibility === 'private' && !boundSpeaker && identity.speakerLabel !== identity.speakerId
    ? `${identity.speakerLabel}（${identity.speakerId}）` : identity.speakerLabel
  const status = rule ? 'active' : memoryStatus(record, now)
  const matchLabel = record.matchType === 'exact' ? '精确匹配 exact'
    : record.matchType === 'contains' ? '包含匹配 contains' : '匹配方式未标注，待核实'
  return { record, identity: { ...identity, speakerLabel }, sourceLabel: source.deviceLabel, status, rule, matchLabel,
    audience: audienceKey(identity), device: deviceKey(identity),
    label: text(rule ? record.patternText : record.canonicalText) || '（正文为空）',
    category: categoryLabel(record.category) }
}

function matches(item, filters) {
  if (filters.speaker && filters.speaker !== item.audience) return false
  if (filters.device && filters.device !== item.device) return false
  if (!item.rule) {
    if (filters.status && filters.status !== item.status) return false
    if (filters.source && filters.source !== item.record.confirmationState) return false
    if (filters.tier && filters.tier !== text(item.record.memoryTier).toUpperCase()) return false
  }
  const query = text(filters.query).trim().toLocaleLowerCase()
  if (!query) return true
  const fields = [item.label, item.category, item.identity.speakerLabel, item.identity.speakerId,
    item.identity.deviceLabel, item.identity.deviceId, item.record.reason,
    item.rule ? item.matchLabel : memoryStatusLabel(item.status), memorySourceLabel(item.record.confirmationState), item.record.memoryType]
  return fields.some(value => text(value).toLocaleLowerCase().includes(query))
}

function options(items, field, label) {
  const found = new Map()
  items.forEach(item => found.set(item[field], { value: item[field], label: item.identity[label] }))
  return [...found.values()].sort((a, b) => a.label.localeCompare(b.label, 'zh-CN') || a.value.localeCompare(b.value))
}

/** Only persisted ownership/category relations are edges; no inferred semantic links. */
export function buildMemoryGraph({ facts = [], tombstones = [], policy = {}, agentId = '', agentName = '',
  filters = {}, collapsed = {}, now = Date.now() } = {}) {
  policy = policy || {}
  const allFacts = facts.filter(record => memoryStatus(record, now) !== 'deleted')
    .map(record => describe(record, policy, agentId, now, false))
  const allRules = tombstones.filter(record => record.status === 'active')
    .map(record => describe(record, policy, agentId, now, true))
  const all = [...allFacts, ...allRules]
  const selected = all.filter(item => matches(item, filters))
  const result = { nodes: [], edges: [], factCount: selected.filter(item => !item.rule).length,
    ruleCount: selected.filter(item => item.rule).length, totalFacts: allFacts.length, totalRules: allRules.length,
    scopeKnown: ['AGENT', 'DEVICE'].includes(policy.memoryScope),
    options: { speakers: options(all, 'audience', 'speakerLabel'), devices: options(all, 'device', 'deviceLabel') } }
  if (!selected.length || !result.scopeKnown) return result

  const root = { id: 'agent', type: 'agent', label: agentName || '当前智能体', children: [], depth: 0 }
  const groups = new Map([[root.id, root]])
  const ensure = (parent, type, id, label, sub) => {
    const groupId = key(parent.id, type, id)
    if (!groups.has(groupId)) {
      const group = { id: groupId, type, label, sub, children: [] }
      parent.children.push(group)
      groups.set(groupId, group)
    }
    return groups.get(groupId)
  }
  selected.forEach((item, index) => {
    const identity = item.identity
    let parent = root
    if (policy.memoryScope === 'DEVICE') {
      parent = ensure(parent, 'device', item.device,
        identity.deviceId ? identity.deviceLabel : '设备归属缺失', identity.deviceId || '不会自动归入任意设备')
    }
    parent = ensure(parent, 'audience', item.audience, identity.speakerLabel,
      identity.visibility === 'shared' ? '共享范围' : identity.visibility === 'unknown' ? '人物归属待核实' : `人物 ID：${identity.speakerId}`)
    parent = ensure(parent, item.rule ? 'rules' : 'category', item.rule ? 'rules' : item.category,
      item.rule ? '遗忘规则' : item.category, '')
    const source = `来源：${item.sourceLabel}`
    const range = policy.memoryScope === 'DEVICE' ? `仅设备：${identity.deviceLabel}` : '同智能体全部设备'
    const sub = item.rule ? `${item.matchLabel} · ${range}`
      : `${memoryStatusLabel(item.status)} · ${memorySourceLabel(item.record.confirmationState)}`
    parent.children.push({ id: key(parent.id, item.rule ? 'tombstone' : 'fact',
      item.record.memoryId || item.record.tombstoneId || item.record.id || index),
    type: item.rule ? 'tombstone' : 'fact', label: item.label, sub, source, status: item.status,
    identity, rec: item.record, children: [], width: 310, height: 76 })
  })

  function count(node) {
    node.children.sort(compare)
    node.count = node.children.length ? node.children.reduce((sum, child) => sum + count(child), 0) : 1
    return node.count
  }
  count(root)
  root.sub = `${result.factCount} 条事实 · ${result.ruleCount} 条遗忘规则`
  let row = 0
  function layout(node, depth) {
    node.hasChildren = node.children.length > 0
    node.collapsed = node.hasChildren && Boolean(collapsed[node.id])
    node.depth = depth
    node.width = node.width || 180
    node.height = node.height || 64
    node.x = depth * 272
    if (!node.hasChildren || node.collapsed) node.y = row++ * 96
    else {
      node.children.forEach(child => layout(child, depth + 1))
      node.y = (node.children[0].y + node.children[node.children.length - 1].y) / 2
    }
    result.nodes.push(node)
    if (!node.collapsed) node.children.forEach(child => result.edges.push({ id: key(node.id, child.id), from: node.id, to: child.id }))
  }
  layout(root, 0)
  result.nodes.sort((a, b) => a.depth - b.depth || a.y - b.y)
  return result
}

export function graphBounds(nodes) {
  if (!nodes.length) return null
  return nodes.reduce((box, node) => ({ minX: Math.min(box.minX, node.x - node.width / 2),
    maxX: Math.max(box.maxX, node.x + node.width / 2), minY: Math.min(box.minY, node.y - node.height / 2),
    maxY: Math.max(box.maxY, node.y + node.height / 2) }),
  { minX: Infinity, maxX: -Infinity, minY: Infinity, maxY: -Infinity })
}
