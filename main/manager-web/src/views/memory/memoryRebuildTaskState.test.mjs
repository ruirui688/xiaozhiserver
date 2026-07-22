import test from 'node:test'
import assert from 'node:assert/strict'

import {
  canRecoverRebuildTask,
  isActiveRebuildTask,
  isTerminalRebuildTask,
  normalizeRebuildTask,
  rebuildTaskAlertType,
  rebuildTaskPollDelay,
  rebuildTaskStatusText
} from './memoryRebuildTaskState.mjs'

test('restores an active server task after the page is recreated', () => {
  const restored = normalizeRebuildTask({
    taskId: 'task-1', status: 'running', progress: 45,
    message: '维护模型正在生成重建动作'
  })
  assert.equal(isActiveRebuildTask(restored), true)
  assert.equal(isTerminalRebuildTask(restored), false)
  assert.equal(rebuildTaskAlertType(restored), 'info')
  assert.match(rebuildTaskStatusText(restored), /45%/)
})

test('preserves the final rebuild result for a later page visit', () => {
  const restored = normalizeRebuildTask({
    taskId: 'task-2', status: 'SUCCEEDED', progress: 100,
    result: { sessionsRead: 8, factsChanged: 3 }
  })
  assert.equal(isActiveRebuildTask(restored), false)
  assert.equal(isTerminalRebuildTask(restored), true)
  assert.equal(rebuildTaskAlertType(restored), 'success')
  assert.match(rebuildTaskStatusText(restored), /读取 8 个会话，实际变更 3 条事实/)
})

test('makes timeout state observable and terminal', () => {
  const restored = normalizeRebuildTask({
    taskId: 'task-3', status: 'TIMED_OUT', progress: 100,
    errorMessage: '维护模型在执行时限内未完成'
  })
  assert.equal(isTerminalRebuildTask(restored), true)
  assert.equal(rebuildTaskAlertType(restored), 'error')
  assert.equal(rebuildTaskStatusText(restored), '维护模型在执行时限内未完成')
})

test('recovers only active server tasks after a start response interruption', () => {
  assert.equal(canRecoverRebuildTask({ taskId: 'task-4', status: 'PENDING' }), true)
  assert.equal(canRecoverRebuildTask({ taskId: 'task-4', status: 'SUCCEEDED' }), false)
  assert.equal(canRecoverRebuildTask(null), false)
})

test('backs off failed polling without exceeding ten seconds', () => {
  assert.equal(rebuildTaskPollDelay(0), 2000)
  assert.equal(rebuildTaskPollDelay(2), 6000)
  assert.equal(rebuildTaskPollDelay(20), 10000)
})
