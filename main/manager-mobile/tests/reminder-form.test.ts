import assert from 'node:assert/strict'
// eslint-disable-next-line test/no-import-node-test -- This suite runs with the package's Node test script.
import test from 'node:test'
import { createReminderPayload, defaultReminderForm, parseReminderDate, reminderToForm } from '../src/pages/reminder/reminderForm'

test('builds a normalized reminder payload from date and time pickers', () => {
  const now = new Date('2026-10-13T04:00:00.000Z')
  const payload = createReminderPayload({
    deviceId: ' device-1 ',
    title: ' 下午开会 ',
    date: '2026-10-14',
    time: '14:00',
    timezone: 'Asia/Shanghai',
  }, now, 'Asia/Shanghai')

  assert.equal(payload.deviceId, 'device-1')
  assert.equal(payload.title, '下午开会')
  assert.equal(payload.timezone, 'Asia/Shanghai')
  assert.equal(payload.remindAt, '2026-10-14T06:00:00.000Z')
})

test('rejects missing devices and past reminder times', () => {
  const now = new Date('2026-10-13T12:00:00')
  assert.throws(() => createReminderPayload({
    deviceId: '',
    title: '开会',
    date: '2026-10-14',
    time: '14:00',
    timezone: 'Asia/Shanghai',
  }, now), /请选择提醒设备/)
  assert.throws(() => createReminderPayload({
    deviceId: 'device-1',
    title: '开会',
    date: '2026-10-13',
    time: '11:00',
    timezone: 'Asia/Shanghai',
  }, now), /晚于当前时间/)
})

test('provides timezone-aware picker defaults and converts an existing reminder back to a form', () => {
  const form = defaultReminderForm(new Date('2026-10-13T05:10:00.000Z'), 'Asia/Shanghai')
  assert.equal(form.date, '2026-10-13')
  assert.equal(form.time, '14:10')
  assert.equal(form.timezone, 'Asia/Shanghai')
  assert.equal(parseReminderDate('not-a-date', '14:00'), null)

  const edited = reminderToForm({
    deviceId: 'device-2',
    title: '喝水',
    remindAt: '2026-10-14T06:00:00.000Z',
    timezone: 'Asia/Shanghai',
  })
  assert.equal(edited.deviceId, 'device-2')
  assert.equal(edited.title, '喝水')
  assert.equal(edited.date, '2026-10-14')
  assert.equal(edited.time, '14:00')
})

test('keeps the original instant when editing an unchanged DST fallback wall time', () => {
  const form = reminderToForm({
    deviceId: 'device-2',
    title: '冬令时会议',
    remindAt: '2026-11-01T06:30:00.000Z',
    timezone: 'America/New_York',
  })
  assert.equal(form.date, '2026-11-01')
  assert.equal(form.time, '01:30')

  form.title = '冬令时会议（已编辑）'
  const payload = createReminderPayload(form, new Date('2026-10-31T12:00:00.000Z'))

  assert.deepEqual(payload, {
    deviceId: 'device-2',
    title: '冬令时会议（已编辑）',
    remindAt: '2026-11-01T06:30:00.000Z',
    timezone: 'America/New_York',
  })
})
