import type { AllowanceSummary } from '@/api/platform'
export function allowanceAmount(value: string | number | null | undefined) {
  return value != null && /^-?\d+(\.\d+)?$/.test(String(value)) ? String(value) : '—'
}
export function allowanceDisplayAmount(value: string | number | null | undefined) {
  if (typeof value !== 'string' && typeof value !== 'number') return '—'
  const text = String(value)
  const parts = /^(-?)(\d+)(?:\.(\d+))?$/.exec(text)
  if (!parts || parts[0] !== text) return '—'
  const sign = parts[1]
  const whole = parts[2].replace(/^0+(?=\d)/, '')
  const fraction = parts[3] || ''
  if (whole === '0' && !/[1-9]/.test(fraction)) return '0.00'
  const cents = fraction.padEnd(2, '0').slice(0, 2)
  if (whole === '0' && cents === '00') return sign ? '大于 -0.01 且小于 0' : '小于 0.01'
  // Round decimal digits, never the monetary value as a floating-point number.
  const digits = (whole + cents).split('')
  if (fraction.length > 2 && fraction[2] >= '5') {
    let index = digits.length - 1
    while (index >= 0 && digits[index] === '9') digits[index--] = '0'
    if (index < 0) digits.unshift('1')
    else digits[index] = String.fromCharCode(digits[index].charCodeAt(0) + 1)
  }
  return `${sign}${digits.slice(0, -2).join('')}.${digits.slice(-2).join('')}`
}
export function visibleAllowance(summary: AllowanceSummary | null) {
  return summary?.canViewBalance === true ? summary.allowance : null
}
export function allowancePayerLabel(summary: AllowanceSummary | null) {
  return summary?.canViewBalance === true ? '当前空间使用本人的凭据与额度' : '当前空间使用负责人的凭据与额度'
}
