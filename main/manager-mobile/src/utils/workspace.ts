export const CURRENT_WORKSPACE_ID_KEY = 'currentWorkspaceId'

export function getCurrentWorkspaceId(): string {
  return String(uni.getStorageSync(CURRENT_WORKSPACE_ID_KEY) || '').trim()
}

export function setCurrentWorkspaceId(workspaceId: string) {
  const normalized = workspaceId.trim()
  if (normalized)
    uni.setStorageSync(CURRENT_WORKSPACE_ID_KEY, normalized)
  else
    clearCurrentWorkspaceId()
}

export function clearCurrentWorkspaceId() {
  uni.removeStorageSync(CURRENT_WORKSPACE_ID_KEY)
}
