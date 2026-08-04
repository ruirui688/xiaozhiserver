import assert from 'node:assert/strict'
// eslint-disable-next-line test/no-import-node-test -- This suite runs with the package's Node test script.
import test from 'node:test'

import { filterTraditionalTextModels, isRealtimeModel } from '../src/utils/realtimeModel'

test('detects realtime models from the manager-api contract', () => {
  assert.equal(isRealtimeModel({ conversationMode: 'realtime' }), true)
  assert.equal(isRealtimeModel({ realtime: true }), true)
  assert.equal(isRealtimeModel({ adapterCode: 'qwen_omni_realtime' }), true)
  assert.equal(isRealtimeModel({ adapterCode: 'doubao_realtime' }), true)
  assert.equal(isRealtimeModel({ adapterCode: 'doubao-realtime' }), true)
  assert.equal(isRealtimeModel({ adapterCode: 'openai', conversationMode: 'pipeline' }), false)
})

test('keeps realtime main models out of the SLM selector', () => {
  const models = [
    { id: 'traditional', adapterCode: 'openai' },
    { id: 'realtime', conversationMode: 'realtime' },
  ]

  assert.deepEqual(filterTraditionalTextModels(models).map(item => item.id), ['traditional'])
})
