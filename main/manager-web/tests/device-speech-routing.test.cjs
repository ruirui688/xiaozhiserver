const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const { loadModule } = require('./helpers/loadVueComponent.cjs')

test('device speech route defaults missing and invalid values to server', () => {
  const routing = loadModule('src/utils/deviceSpeechRouting.js')

  assert.equal(routing.normalizeSpeechRouteMode(undefined), 'server')
  assert.equal(routing.normalizeSpeechRouteMode('unsupported'), 'server')
  assert.equal(routing.normalizeSpeechRouteMode('device'), 'device')
  assert.deepEqual(routing.createDeviceSpeechRoutingPayload('auto', 'device'), {
    speechInputMode: 'auto',
    speechOutputMode: 'device'
  })
})

test('device management exposes the independent speech settings dialog', () => {
  const page = fs.readFileSync(path.resolve(__dirname, '../src/views/DeviceManagement.vue'), 'utf8')
  const dialog = fs.readFileSync(path.resolve(__dirname, '../src/components/device/DeviceSpeechSettingsDialog.vue'), 'utf8')

  assert.match(page, /handleSpeechSettings\(scope\.row\)/)
  assert.match(page, /DeviceSpeechSettingsDialog/)
  assert.match(dialog, /speechInputMode/)
  assert.match(dialog, /speechOutputMode/)
  assert.match(dialog, /updateDeviceInfo/)
})

test('speech routing saves through the authenticated device update endpoint', () => {
  const state = { url: '', method: '', data: null }
  const requestService = {
    clearRequestTime() {},
    sendRequest() {
      const request = {
        url(value) { state.url = value; return request },
        method(value) { state.method = value; return request },
        data(value) { state.data = value; return request },
        success(callback) { request.successCallback = callback; return request },
        networkFail() { return request },
        send() { request.successCallback({ data: { code: 0 } }); return request },
      }
      return request
    },
  }
  const api = loadModule('src/apis/module/device.js', {
    '../api': { getServiceUrl: () => '/xiaozhi' },
    '../httpRequest': { __esModule: true, default: requestService },
  }).default
  const payload = { speechInputMode: 'device', speechOutputMode: 'auto' }

  api.updateDeviceInfo('device-1', payload, () => {})

  assert.equal(state.url, '/xiaozhi/device/update/device-1')
  assert.equal(state.method, 'PUT')
  assert.deepEqual(state.data, payload)
})
