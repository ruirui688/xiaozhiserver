import assert from 'node:assert/strict'
import test from 'node:test'
import type { AllowanceSummary } from '../src/api/platform'
import { allowanceAmount, allowancePayerLabel, visibleAllowance } from '../src/utils/allowance'

test('workspace allowance hides responsible person private balance unless explicitly permitted', () => {
  const summary = { canViewBalance: false, allowance: { available: '100' } } as AllowanceSummary
  assert.equal(visibleAllowance(summary), null)
  assert.match(allowancePayerLabel(summary), /负责人/)
  summary.canViewBalance = true
  assert.equal(visibleAllowance(summary), summary.allowance)
  assert.match(allowancePayerLabel(summary), /本人/)
})
test('allowance display preserves server precision and does not report missing data as zero', () => {
  assert.equal(allowanceAmount('9007199254740993.123456'), '9007199254740993.123456')
  assert.equal(allowanceAmount(null), '—')
  assert.equal(allowanceAmount(''), '—')
  assert.equal(allowanceAmount('0'), '0')
})
