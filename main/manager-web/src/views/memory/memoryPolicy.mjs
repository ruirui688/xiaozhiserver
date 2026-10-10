export const DEFAULT_MEMORY_POLICY = Object.freeze({
  memoryScope: 'AGENT',
  unknownSpeakerPolicy: 'SHARED',
  maintenanceMode: 'AUTO',
  activeMaxChars: 1000,
  contextMaxChars: 4000,
  contextMaxItems: 30,
  retentionDays: 0
})

export function policyFields(value = {}) {
  return Object.fromEntries(Object.entries(DEFAULT_MEMORY_POLICY)
    .map(([key, fallback]) => [key, value[key] == null ? fallback : value[key]]))
}

export function responseData(response) {
  return response && response.data && response.data.data !== undefined
    ? response.data.data : (response && response.data) || {}
}

export function memoryError(error, fallback) {
  return (error && error.data && error.data.msg) || (error && error.message) || fallback
}

export function attributionOf(memory = {}) {
  let payload = memory.payload || {}
  try { if (typeof payload === 'string') payload = JSON.parse(payload) } catch (_) { payload = {} }
  if (!payload || typeof payload !== 'object') payload = {}
  const speakerId = memory.speakerId || payload.speaker_id || ''
  return {
    visibility: speakerId ? 'private' : 'shared',
    speakerId,
    deviceId: memory.deviceId || memory.macAddress || ''
  }
}

export function attributionError(value, memoryScope) {
  if (value.visibility === 'private' && !value.speakerId) return '请选择记忆所属人物'
  if (memoryScope === 'DEVICE' && !value.deviceId) return '设备隔离模式下请选择设备'
  return ''
}
