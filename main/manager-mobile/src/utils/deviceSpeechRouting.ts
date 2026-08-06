export const speechRouteModes = ['server', 'device', 'auto'] as const

export type SpeechRouteMode = typeof speechRouteModes[number]

export function normalizeSpeechRouteMode(value?: string | null): SpeechRouteMode {
  return speechRouteModes.includes(value as SpeechRouteMode) ? value as SpeechRouteMode : 'server'
}

export function createDeviceSpeechRoutingPayload(inputMode?: string | null, outputMode?: string | null) {
  return {
    speechInputMode: normalizeSpeechRouteMode(inputMode),
    speechOutputMode: normalizeSpeechRouteMode(outputMode),
  }
}
