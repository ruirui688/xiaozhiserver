export interface RealtimeModelLike {
  adapterCode?: string
  type?: string
  conversationMode?: string
  realtime?: boolean
}

const REALTIME_ADAPTERS = new Set([
  'qwen_omni_realtime',
  'qwen-omni-realtime',
  'doubao_realtime',
  'doubao-realtime',
])

export function isRealtimeModel(model?: RealtimeModelLike | null): boolean {
  if (!model)
    return false
  if (model.realtime === true)
    return true
  if (String(model.conversationMode || '').trim().toLowerCase() === 'realtime')
    return true

  const adapter = String(model.type || model.adapterCode || '').trim().toLowerCase()
  return REALTIME_ADAPTERS.has(adapter)
}

export function filterTraditionalTextModels<T extends RealtimeModelLike>(models: T[]): T[] {
  return (models || []).filter(model => !isRealtimeModel(model))
}
