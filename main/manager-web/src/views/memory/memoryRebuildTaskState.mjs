const ACTIVE_STATUSES = new Set(['PENDING', 'RUNNING'])
const TERMINAL_STATUSES = new Set(['SUCCEEDED', 'FAILED', 'TIMED_OUT'])

export function normalizeRebuildTask(task) {
  if (!task || !task.taskId) return null
  return {
    ...task,
    status: String(task.status || 'PENDING').toUpperCase(),
    progress: Math.max(0, Math.min(100, Number(task.progress) || 0)),
    result: task.result || null
  }
}

export function isActiveRebuildTask(task) {
  const normalized = normalizeRebuildTask(task)
  return Boolean(normalized && ACTIVE_STATUSES.has(normalized.status))
}

export function isTerminalRebuildTask(task) {
  const normalized = normalizeRebuildTask(task)
  return Boolean(normalized && TERMINAL_STATUSES.has(normalized.status))
}

export function rebuildTaskPollDelay(failureCount) {
  const failures = Math.max(0, Number(failureCount) || 0)
  return Math.min(10000, 2000 * (failures + 1))
}

export function canRecoverRebuildTask(task) {
  const normalized = normalizeRebuildTask(task)
  return Boolean(normalized && isActiveRebuildTask(normalized))
}

export function rebuildTaskAlertType(task) {
  const normalized = normalizeRebuildTask(task)
  if (!normalized || isActiveRebuildTask(normalized)) return 'info'
  return normalized.status === 'SUCCEEDED' ? 'success' : 'error'
}

export function rebuildTaskStatusText(task) {
  const normalized = normalizeRebuildTask(task)
  if (!normalized) return ''
  if (isActiveRebuildTask(normalized)) {
    return `${normalized.message || '记忆重建处理中'}（${normalized.progress}%）`
  }
  if (normalized.status === 'SUCCEEDED') {
    const result = normalized.result || {}
    return `最近一次重建已完成：读取 ${Number(result.sessionsRead) || 0} 个会话，实际变更 ${Number(result.factsChanged) || 0} 条事实。`
  }
  return normalized.errorMessage || normalized.message || '最近一次记忆重建未完成'
}
