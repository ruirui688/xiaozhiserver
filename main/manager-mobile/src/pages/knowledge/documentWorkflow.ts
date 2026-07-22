export interface WorkflowDocument {
  id: string
  name?: string
  run?: string
  progress?: number
  error?: string
}

export interface ReplacementJob {
  oldDocumentId: string
  newDocumentId: string
  oldName: string
  newName: string
  createdAt: number
}

export type ReplacementDecision = 'WAIT' | 'DELETE_OLD' | 'KEEP_OLD' | 'MISSING_NEW'

const ACTIVE_STATUSES = new Set(['UNSTART', 'RUNNING'])
const TERMINAL_STATUSES = new Set(['DONE', 'FAIL', 'CANCEL'])

export function normalizeDocumentStatus(status?: string) {
  return String(status || 'UNSTART').trim().toUpperCase()
}

export function isDocumentProcessing(document?: WorkflowDocument | null) {
  return Boolean(document && ACTIVE_STATUSES.has(normalizeDocumentStatus(document.run)))
}

export function isDocumentTerminal(document?: WorkflowDocument | null) {
  return Boolean(document && TERMINAL_STATUSES.has(normalizeDocumentStatus(document.run)))
}

export function documentStatusText(document: WorkflowDocument) {
  const status = normalizeDocumentStatus(document.run)
  return ({
    UNSTART: '等待解析',
    RUNNING: '解析中',
    DONE: '已完成',
    FAIL: '解析失败',
    CANCEL: '已取消',
  } as Record<string, string>)[status] || status
}

export function documentProgressPercent(document: WorkflowDocument) {
  if (normalizeDocumentStatus(document.run) === 'DONE')
    return 100
  const raw = Number(document.progress)
  if (!Number.isFinite(raw) || raw <= 0)
    return 0
  const percent = raw <= 1 ? raw * 100 : raw
  return Math.min(99, Math.max(0, Math.round(percent)))
}

export function nextDocumentPollDelay(failureCount: number) {
  const failures = Math.max(0, Number(failureCount) || 0)
  return Math.min(10000, 2000 * (failures + 1))
}

export function completedDocumentTransitions(previous: WorkflowDocument[], current: WorkflowDocument[]) {
  const previousById = new Map(previous.map(document => [document.id, document]))
  return current.filter((document) => {
    const oldDocument = previousById.get(document.id)
    return Boolean(oldDocument && isDocumentProcessing(oldDocument) && isDocumentTerminal(document))
  })
}

export function replacementDecision(job: ReplacementJob, documents: WorkflowDocument[]): ReplacementDecision {
  const replacement = documents.find(document => document.id === job.newDocumentId)
  if (!replacement)
    return 'MISSING_NEW'
  const status = normalizeDocumentStatus(replacement.run)
  if (status === 'DONE')
    return 'DELETE_OLD'
  if (status === 'FAIL' || status === 'CANCEL')
    return 'KEEP_OLD'
  return 'WAIT'
}

export function createLatestRequestGate() {
  let latest = 0
  return {
    next() {
      latest += 1
      return latest
    },
    isLatest(requestId: number) {
      return requestId === latest
    },
    invalidate() {
      latest += 1
    },
  }
}

export function parseReplacementJobs(value: unknown): ReplacementJob[] {
  try {
    const parsed = typeof value === 'string' ? JSON.parse(value) : value
    if (!Array.isArray(parsed))
      return []
    return parsed.filter(item => item
      && typeof item.oldDocumentId === 'string'
      && typeof item.newDocumentId === 'string'
      && typeof item.oldName === 'string'
      && typeof item.newName === 'string'
      && Number.isFinite(Number(item.createdAt)))
  }
  catch {
    return []
  }
}

export function createDocumentPollScheduler(
  scheduleTimer: (callback: () => void, delay: number) => unknown,
  cancelTimer: (handle: unknown) => void,
  shouldRun: () => boolean,
  poll: () => void,
) {
  let handle: unknown = null
  return {
    schedule(delay: number) {
      if (handle != null || !shouldRun())
        return
      handle = scheduleTimer(() => {
        handle = null
        poll()
      }, delay)
    },
    stop() {
      if (handle == null)
        return
      cancelTimer(handle)
      handle = null
    },
    isScheduled() {
      return handle != null
    },
  }
}
