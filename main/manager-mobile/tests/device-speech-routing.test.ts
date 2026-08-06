import assert from 'node:assert/strict'
import fs from 'node:fs'
// eslint-disable-next-line test/no-import-node-test -- This suite runs with the package's Node test script.
import test from 'node:test'
import path from 'node:path'
import { createDeviceSpeechRoutingPayload, normalizeSpeechRouteMode } from '../src/utils/deviceSpeechRouting'

test('normalizes device speech routing and preserves valid modes', () => {
  assert.equal(normalizeSpeechRouteMode(undefined), 'server')
  assert.equal(normalizeSpeechRouteMode('invalid'), 'server')
  assert.equal(normalizeSpeechRouteMode('auto'), 'auto')
  assert.deepEqual(createDeviceSpeechRoutingPayload('device', 'auto'), {
    speechInputMode: 'device',
    speechOutputMode: 'auto',
  })
})

test('mobile device API submits both speech route fields to the device update endpoint', () => {
  const source = fs.readFileSync(path.resolve(import.meta.dirname, '../src/api/device/device.ts'), 'utf8')

  assert.match(source, /updateDeviceSpeechRouting/)
  assert.match(source, /http\.Put\(`\/device\/update\/\$\{deviceId\}`/)
  assert.match(source, /speechInputMode: SpeechRouteMode/)
  assert.match(source, /speechOutputMode: SpeechRouteMode/)
})
