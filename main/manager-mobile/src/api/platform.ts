import { http } from '@/http/request/alova'

export interface AllowanceBalance { status: string, remaining: string, available: string, reserved: string, spent: string, currency: string }
export interface AllowanceSummary {
  workspaceId: string
  ownerUserId: number | string
  responsibleUserId: number | string
  responsibilityVersion: number
  payerUserId: number | string
  canViewBalance: boolean
  allowance: AllowanceBalance | null
  serviceStatus: string
}
export interface AllowanceEntry { id: string, type: string, amount: string, balanceAfter: string, reason: string, createdAt: string }
export interface WorkspaceLimits {
  workspaceId: string
  responsibilityVersion: number
  canManage: boolean
  totalLimit: string | null
  dailyLimit: string | null
  totalSpent: string
  totalReserved: string
  dailySpent: string
  dailyReserved: string
}
export interface AllowanceRequest {
  id: string
  workspaceId: string | null
  requestedAmount: string | null
  grantedAmount: string | null
  reason: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED'
  decisionReason: string | null
  createdAt: string
  decidedAt: string | null
}
export interface TrialStatus { enabled: boolean, policy: string, grantedPolicy: string | null, granted: boolean }
const fresh = { cacheFor: { expire: 0 }, meta: { toast: false } }
export function getAllowanceSummary(workspaceId: string) { return http.Get<AllowanceSummary>('/user/platform/summary', { ...fresh, params: { workspaceId } }) }
export function getAllowanceLedger(page = 1) { return http.Get<{ list: AllowanceEntry[], total: number }>('/user/platform/ledger', { ...fresh, params: { page, limit: 20 } }) }
export function getWorkspaceLimits(workspaceId: string) { return http.Get<WorkspaceLimits>('/user/platform/workspace-limits', { ...fresh, params: { workspaceId } }) }
export function saveWorkspaceLimits(data: { workspaceId: string, totalLimit: string | null, dailyLimit: string | null, expectedVersion: number }) { return http.Put<void>('/user/platform/workspace-limits', data) }
export function getTrialStatus() { return http.Get<TrialStatus>('/user/platform/trial', fresh) }
export function getAllowanceRequests() { return http.Get<{ list: AllowanceRequest[], total: number }>('/user/platform/allowance-requests', { ...fresh, params: { page: 1, limit: 20 } }) }
export function requestAllowance(data: { requestId: string, amount: string | null, reason: string, workspaceId: string | null }) { return http.Post<AllowanceRequest>('/user/platform/allowance-requests', data) }
