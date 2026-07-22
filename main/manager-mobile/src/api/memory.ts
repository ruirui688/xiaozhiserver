import { http } from '@/http/request/alova'

export interface MemoryFact {
  memoryId: string
  canonicalText: string
  category?: string
  memoryType?: string
  memoryTier?: string
  importance?: number
  confidence?: number
  scopeType?: string
  status?: string
  confirmationState?: string
  updatedAt?: string
}

export interface MemoryDashboard {
  facts: MemoryFact[]
  candidates: Array<Record<string, unknown>>
  tombstones: Array<Record<string, unknown>>
  revisions: Array<Record<string, unknown>>
}

export function getMemoryDashboard(agentId: string) {
  return http.Get<MemoryDashboard>(`/agent/${agentId}/memories`, {
    cacheFor: { expire: 0 },
    meta: { ignoreAuth: false, toast: false },
  })
}

export function createMemoryFact(agentId: string, text: string) {
  return http.Post<string>(`/agent/${agentId}/memories/facts`, {
    canonicalText: text,
    category: '手动记录',
    memoryType: '长期记忆',
  }, {
    meta: { ignoreAuth: false, toast: false },
  })
}

export function forget(agentId: string, text: string) {
  return http.Post(`/agent/${agentId}/memories/forget`, {
    text,
    reason: '移动端手动忘记',
    patternType: 'semantic',
    shared: true,
  }, {
    timeout: 30000,
    meta: { ignoreAuth: false, toast: false },
  })
}

export function archiveMemory(agentId: string, memoryId: string) {
  return http.Post(`/agent/${agentId}/memories/facts/${memoryId}/archive`, {}, {
    meta: { ignoreAuth: false, toast: false },
  })
}

export function deleteMemory(agentId: string, memoryId: string) {
  return http.Delete(`/agent/${agentId}/memories/facts/${memoryId}`, {
    meta: { ignoreAuth: false, toast: false },
  })
}
