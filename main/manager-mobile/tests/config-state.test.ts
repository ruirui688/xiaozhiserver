import assert from 'node:assert/strict'
// eslint-disable-next-line test/no-import-node-test -- This suite runs with the package's Node test script.
import test from 'node:test'

import { rethrowRequestError } from '../src/http/request/responseError'
import {
  createInitialPublicConfig,
  deserializePublicConfig,
  normalizePublicConfig,
} from '../src/store/configState'

test('keeps a complete safe config when the public response is empty', () => {
  assert.deepEqual(normalizePublicConfig(undefined, '小智'), createInitialPublicConfig('小智'))
  assert.equal(normalizePublicConfig(undefined, '小智').enableMobileRegister, false)
})

test('normalizes public config fields and ignores invalid area entries', () => {
  const config = normalizePublicConfig({
    enableMobileRegister: true,
    allowUserRegister: true,
    mobileAreaList: [
      { name: '中国大陆', key: '+86' },
      { name: '无效区号' },
    ],
    sm2PublicKey: 'public-key',
  }, '小智')

  assert.equal(config.enableMobileRegister, true)
  assert.equal(config.name, '小智')
  assert.deepEqual(config.mobileAreaList, [{ name: '中国大陆', key: '+86' }])
})

test('recovers raw-object, wrapped, and corrupted persisted config values', () => {
  const rawConfig = { enableMobileRegister: true, name: '服务端名称' }

  assert.equal(deserializePublicConfig(rawConfig, '小智').enableMobileRegister, true)
  assert.equal(deserializePublicConfig(JSON.stringify({ config: rawConfig }), '小智').name, '服务端名称')
  assert.deepEqual(deserializePublicConfig('{broken', '小智'), createInitialPublicConfig('小智'))
})

test('does not turn request failures into successful undefined responses', () => {
  const timeout = new Error('timeout')
  assert.throws(() => rethrowRequestError(timeout), error => error === timeout)
})
