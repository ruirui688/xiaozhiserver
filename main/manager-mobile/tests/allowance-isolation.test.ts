import assert from 'node:assert/strict'
import test from 'node:test'
import type { AllowanceSummary } from '../src/api/platform'
import { allowanceAmount, allowanceDisplayAmount, allowancePayerLabel, visibleAllowance } from '../src/utils/allowance'

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

test('user allowance display distinguishes exact zero from nonzero sub-cent values', () => {
  for (const value of [0, '0', '0.000000000', '-0.000', '000.000']) assert.equal(allowanceDisplayAmount(value), '0.00')
  for (const value of ['0.000000001', '0.005', '0.009999999']) assert.equal(allowanceDisplayAmount(value), '小于 0.01')
  for (const value of ['-0.000000001', '-0.005', '-0.009999999']) assert.equal(allowanceDisplayAmount(value), '大于 -0.01 且小于 0')
  assert.equal(allowanceDisplayAmount('0.01'), '0.01')
  assert.equal(allowanceDisplayAmount('-0.01'), '-0.01')
})

test('user allowance display rounds decimal strings without losing large amounts or carry digits', () => {
  const cases: [string | number, string][] = [
    ['12', '12.00'], ['12.3', '12.30'], ['00012.30', '12.30'], [1.005, '1.01'],
    ['1234.567890123', '1234.57'], ['1.004999999', '1.00'], ['9.995', '10.00'], ['999.999999999', '1000.00'],
    ['-12.3456789', '-12.35'], ['-1.005', '-1.01'], ['-1.004999999', '-1.00'], ['-9.995', '-10.00'],
    ['9007199254740993.125', '9007199254740993.13'], ['999999999999999999999999999999.995', '1000000000000000000000000000000.00'],
  ]
  for (const [value, expected] of cases) assert.equal(allowanceDisplayAmount(value), expected)
  assert.equal(allowanceAmount('9007199254740993.125'), '9007199254740993.125')
})

test('user allowance display keeps malformed or absent decimal values unknown', () => {
  for (const value of [null, undefined, '', ' ', '12 ', '12\n', '12\r\n', '12.', '.12', '+12', '1e3', '1,000', 'abc', 'NaN', Number.NaN, Infinity, -Infinity]) {
    assert.equal(allowanceDisplayAmount(value), '—')
  }
})
