import assert from 'node:assert/strict'
// eslint-disable-next-line test/no-import-node-test -- This suite runs with the package's Node test script.
import test from 'node:test'

import {
  completedDocumentTransitions,
  createDocumentPollScheduler,
  createLatestRequestGate,
  documentProgressPercent,
  isDocumentProcessing,
  isDocumentTerminal,
  nextDocumentPollDelay,
  parseReplacementJobs,
  replacementDecision,
} from '../src/pages/knowledge/documentWorkflow'

test('polls UNSTART and RUNNING documents until a terminal state', () => {
  assert.equal(isDocumentProcessing({ id: 'a', run: 'UNSTART' }), true)
  assert.equal(isDocumentProcessing({ id: 'a', run: 'running' }), true)
  assert.equal(isDocumentTerminal({ id: 'a', run: 'DONE' }), true)
  assert.equal(isDocumentTerminal({ id: 'a', run: 'FAIL' }), true)
  assert.equal(documentProgressPercent({ id: 'a', run: 'RUNNING', progress: 0.46 }), 46)
})

test('reports only active-to-terminal transitions', () => {
  const transitions = completedDocumentTransitions(
    [{ id: 'a', run: 'RUNNING' }, { id: 'b', run: 'DONE' }],
    [{ id: 'a', run: 'FAIL', error: '模型调用失败' }, { id: 'b', run: 'DONE' }],
  )
  assert.deepEqual(transitions.map(item => item.id), ['a'])
})

test('keeps the old document until its replacement is DONE', () => {
  const job = {
    oldDocumentId: 'old',
    newDocumentId: 'new',
    oldName: 'manual.md',
    newName: 'manual.md',
    createdAt: 1,
  }
  assert.equal(replacementDecision(job, [{ id: 'new', run: 'RUNNING' }]), 'WAIT')
  assert.equal(replacementDecision(job, [{ id: 'new', run: 'FAIL' }]), 'KEEP_OLD')
  assert.equal(replacementDecision(job, [{ id: 'new', run: 'DONE' }]), 'DELETE_OLD')
})

test('ignores stale load responses and bounds polling backoff', () => {
  const gate = createLatestRequestGate()
  const first = gate.next()
  const second = gate.next()
  assert.equal(gate.isLatest(first), false)
  assert.equal(gate.isLatest(second), true)
  gate.invalidate()
  assert.equal(gate.isLatest(second), false)
  assert.equal(nextDocumentPollDelay(0), 2000)
  assert.equal(nextDocumentPollDelay(20), 10000)
})

test('stops a scheduled poll when the page leaves', () => {
  let scheduledCallback: (() => void) | null = null
  const cancelled: unknown[] = []
  let polls = 0
  const scheduler = createDocumentPollScheduler(
    (callback) => {
      scheduledCallback = callback
      return 'timer-1'
    },
    handle => cancelled.push(handle),
    () => true,
    () => polls += 1,
  )
  scheduler.schedule(2000)
  assert.equal(scheduler.isScheduled(), true)
  scheduler.stop()
  assert.deepEqual(cancelled, ['timer-1'])
  assert.equal(scheduler.isScheduled(), false)
  assert.equal(polls, 0)
  assert.notEqual(scheduledCallback, null)
})

test('restores only valid persisted replacement jobs', () => {
  const jobs = parseReplacementJobs(JSON.stringify([
    { oldDocumentId: 'old', newDocumentId: 'new', oldName: 'a.md', newName: 'a.md', createdAt: 1 },
    { oldDocumentId: 'broken' },
  ]))
  assert.equal(jobs.length, 1)
  assert.equal(jobs[0].newDocumentId, 'new')
  assert.deepEqual(parseReplacementJobs('{bad json'), [])
})
