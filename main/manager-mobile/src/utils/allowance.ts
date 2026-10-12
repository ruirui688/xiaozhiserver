import type { AllowanceSummary } from '@/api/platform'
export function allowanceAmount(value: string | number | null | undefined) {
  return value != null && /^-?\d+(\.\d+)?$/.test(String(value)) ? String(value) : '—'
}
export function visibleAllowance(summary: AllowanceSummary | null) {
  return summary?.canViewBalance === true ? summary.allowance : null
}
export function allowancePayerLabel(summary: AllowanceSummary | null) {
  return summary?.canViewBalance === true ? '当前空间使用本人的凭据与额度' : '当前空间使用负责人的凭据与额度'
}
