export const SPEECH_ROUTE_MODES = Object.freeze(['server', 'device', 'auto'])

export function normalizeSpeechRouteMode(value) {
  return SPEECH_ROUTE_MODES.includes(value) ? value : 'server'
}

export function createDeviceSpeechRoutingPayload(inputMode, outputMode) {
  return {
    speechInputMode: normalizeSpeechRouteMode(inputMode),
    speechOutputMode: normalizeSpeechRouteMode(outputMode)
  }
}
