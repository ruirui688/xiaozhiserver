export interface FirmwareType {
  name: string
  key: string
}

export interface Device {
  id: string
  userId: string
  macAddress: string
  lastConnectedAt: string
  autoUpdate: number
  board: string
  alias?: string
  agentId: string
  appVersion: string
  sort: number
  updater?: string
  updateDate: string
  creator: string
  createDate: string
  speechInputMode?: SpeechRouteMode
  speechOutputMode?: SpeechRouteMode
}

export type SpeechRouteMode = 'server' | 'device' | 'auto'

export interface DeviceScene {
  id: string
  deviceId: string
  agentId: string
  agentName: string
  sceneName: string
  switchAliases: string[]
  enabled: number
  sort: number
  current: boolean
  configVersion: number
  switchRequestId?: string
  switchStatus?: 'REQUESTED' | 'SCHEDULED' | 'APPLIED' | 'FAILED' | 'ROLLED_BACK'
  failureCode?: string
}
