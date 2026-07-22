import { clearCurrentWorkspaceId } from '@/utils/workspace'

export function getStoredAccessToken(): string {
  const stored = uni.getStorageSync('token')
  if (!stored)
    return ''

  if (typeof stored === 'object')
    return String(stored.token || '')

  try {
    const parsed = JSON.parse(String(stored))
    return typeof parsed === 'string' ? parsed : String(parsed?.token || '')
  }
  catch {
    return String(stored)
  }
}

export function storeLoginToken(value: { token?: string } | string) {
  const token = typeof value === 'string' ? value : value?.token
  if (!token)
    throw new Error('登录响应缺少 token')
  clearCurrentWorkspaceId()
  uni.setStorageSync('token', JSON.stringify(value))
}
