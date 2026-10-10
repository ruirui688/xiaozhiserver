export function parsePayload(value) {
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch (_) { return {} }
}

export function timestamp(value) {
  if (value == null || value === '') return null
  // WebMvcConfig serializes Date in GMT+8; revision snapshots also contain epoch milliseconds.
  const normalized = typeof value === 'string' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)
    ? value.replace(' ', 'T') + '+08:00' : value
  const parsed = typeof normalized === 'number' ? normalized : Date.parse(normalized)
  return Number.isFinite(parsed) ? parsed : null
}

export function memoryStatus(memory = {}, now = Date.now()) {
  const state = String(memory.status || '').toLowerCase()
  if (state !== 'active') return ['archived', 'deleted', 'pending', 'confirmed', 'rejected'].includes(state) ? state : 'unknown'
  const expiry = timestamp(memory.expiresAt)
  if (memory.expiresAt && expiry == null) return 'unknown'
  return expiry != null && expiry <= now ? 'expired' : 'active'
}

export function memoryStatusLabel(state) {
  return { active: '生效', expired: '已到期', archived: '已归档', deleted: '已删除', pending: '待确认', confirmed: '已确认', rejected: '已拒绝', unknown: '状态待核实' }[state] || '状态待核实'
}

export function memorySourceLabel(value) {
  return { auto: '自动提取', manual: '人工维护', confirmed: '人工确认' }[value] || '来源未标注'
}

export function memoryIdentity(memory = {}, policy = {}) {
  policy = policy || {}
  const payload = parsePayload(memory.payload)
  let invalidPayload = false
  if (memory.payload) {
    try {
      const parsed = typeof memory.payload === 'string' ? JSON.parse(memory.payload) : memory.payload
      invalidPayload = !parsed || typeof parsed !== 'object' || Array.isArray(parsed)
    } catch (_) { invalidPayload = true }
  }
  const rawId = memory.speakerId || payload.speaker_id || ''
  const invalidIdentity = invalidPayload || (payload.speaker_id != null && typeof payload.speaker_id !== 'string') || (rawId && typeof rawId !== 'string')
  const speakerId = invalidIdentity ? '__unknown__' : String(rawId).trim()
  const speaker = (policy.speakers || []).find(item => item.speakerId === speakerId)
  const speakerLabel = invalidIdentity ? '人物归属待核实' : speakerId
    ? ((speaker && speaker.label) || payload.speaker_name || memory.speakerName || speakerId)
    : '共享记忆'
  const source = memory.deviceId || memory.macAddress || memory.scopeId || ''
  const deviceId = source === memory.agentId ? '' : String(source)
  const device = (policy.devices || []).find(item => item.deviceId === deviceId)
  const scopeId = typeof memory.scopeId === 'string' && memory.scopeId !== memory.agentId ? memory.scopeId.trim() : ''
  const scopeDevice = (policy.devices || []).find(item => item.deviceId === scopeId)
  const deviceScopeLabel = policy.memoryScope === 'AGENT' ? '同智能体所有设备'
    : policy.memoryScope === 'DEVICE' ? scopeId ? `仅设备：${scopeDevice && scopeDevice.label || scopeId}` : '设备归属缺失，需核实'
      : '策略未加载'
  return { speakerId, speakerLabel, visibility: invalidIdentity ? 'unknown' : speakerId ? 'private' : 'shared',
    deviceId, deviceLabel: (device && device.label) || deviceId || '未指定设备来源', scopeId, deviceScopeLabel }
}

export function summarizeMemory(facts = [], now = Date.now()) {
  const counts = { active: 0, expired: 0, archived: 0, deleted: 0, unknown: 0 }
  let activeChars = 0
  for (const fact of facts) {
    const state = memoryStatus(fact, now)
    counts[state] = (counts[state] || 0) + 1
    if (state === 'active') activeChars += Array.from(fact.canonicalText || '').length
  }
  return { counts, activeChars, total: facts.length }
}

export function boundedPercent(value, limit) {
  return Number.isFinite(Number(value)) && Number(limit) > 0 ? Math.min(100, Math.max(0, Number(value) / Number(limit) * 100)) : 0
}
