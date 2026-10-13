import { http } from '@/http/request/alova'

export type ReminderStatus = 'PENDING' | 'DELIVERED' | 'CANCELLED' | 'MISSED'
export type ReminderSource = 'VOICE' | 'MINI_PROGRAM'
export type ReminderListFilter = ReminderStatus | 'HISTORY'

export interface ReminderDevice {
  deviceId: string
  deviceName?: string
  agentId: string
  agentName?: string
  lastConnectedAt?: string
}

export interface DeviceReminder {
  id: string
  deviceId: string
  deviceName?: string
  agentId: string
  title: string
  remindAt: string
  timezone: string
  status: ReminderStatus
  source: ReminderSource
  deliveredAt?: string
  lastErrorCode?: string
  createDate?: string
}

export interface SaveReminderRequest {
  deviceId: string
  title: string
  remindAt: string
  timezone: string
}

const requestOptions = {
  cacheFor: { expire: 0 },
  meta: { ignoreAuth: false, toast: false },
}

export function getReminders(status: ReminderListFilter = 'PENDING') {
  return http.Get<DeviceReminder[]>('/reminders', {
    ...requestOptions,
    params: { status },
  })
}

export function getReminderDevices() {
  return http.Get<ReminderDevice[]>('/reminders/devices', requestOptions)
}

export function createReminder(data: SaveReminderRequest) {
  return http.Post<DeviceReminder>('/reminders', data, {
    meta: { ignoreAuth: false, toast: false },
  })
}

export function updateReminder(id: string, data: SaveReminderRequest) {
  return http.Put<DeviceReminder>(`/reminders/${encodeURIComponent(id)}`, data, {
    meta: { ignoreAuth: false, toast: false },
  })
}

export function cancelReminder(id: string) {
  return http.Delete<void>(`/reminders/${encodeURIComponent(id)}`, {
    meta: { ignoreAuth: false, toast: false },
  })
}
