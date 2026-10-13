export const DEFAULT_REMINDER_TIMEZONE = 'Asia/Shanghai'

export interface ReminderFormValue {
  deviceId: string
  title: string
  date: string
  time: string
  timezone: string
  originalRemindAt?: string
  originalDate?: string
  originalTime?: string
  originalTimezone?: string
}

export interface ReminderPayload {
  deviceId: string
  title: string
  remindAt: string
  timezone: string
}

function pad(value: number) {
  return String(value).padStart(2, '0')
}

export function formatPickerDate(date: Date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function formatPickerTime(date: Date) {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function defaultReminderForm(
  now = new Date(),
  timezone = resolveDeviceTimezone(),
): ReminderFormValue {
  const target = new Date(now.getTime() + 60 * 60 * 1000)
  target.setSeconds(0, 0)
  const picker = formatInstantInTimezone(target, timezone)
  return {
    deviceId: '',
    title: '',
    date: picker.date,
    time: picker.time,
    timezone,
  }
}

export function resolveDeviceTimezone() {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || DEFAULT_REMINDER_TIMEZONE
  }
  catch {
    return DEFAULT_REMINDER_TIMEZONE
  }
}

export function parseReminderDate(
  date: string,
  time: string,
  timezone = DEFAULT_REMINDER_TIMEZONE,
) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time))
    return null
  const [year, month, day] = date.split('-').map(Number)
  const [hour, minute] = time.split(':').map(Number)
  if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59)
    return null
  const desiredWallTime = Date.UTC(year, month - 1, day, hour, minute, 0)
  let candidate = new Date(desiredWallTime)
  try {
    // Resolve the timezone offset twice so times around a daylight-saving
    // transition are compared against the offset at the final instant.
    for (let attempt = 0; attempt < 2; attempt += 1) {
      const shown = formatInstantParts(candidate, timezone)
      const shownAsUtc = Date.UTC(shown.year, shown.month - 1, shown.day, shown.hour, shown.minute, 0)
      candidate = new Date(candidate.getTime() + desiredWallTime - shownAsUtc)
    }
    const verified = formatInstantParts(candidate, timezone)
    if (verified.year !== year || verified.month !== month || verified.day !== day
      || verified.hour !== hour || verified.minute !== minute) {
      return null
    }
    return candidate
  }
  catch {
    if (timezone !== DEFAULT_REMINDER_TIMEZONE)
      return null
    const value = new Date(Date.UTC(year, month - 1, day, hour - 8, minute, 0))
    return Number.isNaN(value.getTime()) ? null : value
  }
}

export function createReminderPayload(
  form: ReminderFormValue,
  now = new Date(),
  timezone = form.timezone || DEFAULT_REMINDER_TIMEZONE,
): ReminderPayload {
  const title = form.title.trim()
  const deviceId = form.deviceId.trim()
  if (!deviceId)
    throw new Error('请选择提醒设备')
  if (!title)
    throw new Error('请输入提醒内容')
  if (title.length > 200)
    throw new Error('提醒内容不能超过 200 个字符')

  let remindAt: Date | null
  if (form.originalRemindAt
    && form.date === form.originalDate
    && form.time === form.originalTime
    && timezone === form.originalTimezone) {
    remindAt = new Date(form.originalRemindAt)
  }
  else {
    remindAt = parseReminderDate(form.date, form.time, timezone)
  }
  if (!remindAt)
    throw new Error('请选择有效的提醒时间')
  if (Number.isNaN(remindAt.getTime()))
    throw new Error('请选择有效的提醒时间')
  if (remindAt.getTime() < now.getTime() + 5000)
    throw new Error('提醒时间必须晚于当前时间')
  const latest = now.getTime() + 365 * 24 * 60 * 60 * 1000
  if (remindAt.getTime() > latest)
    throw new Error('提醒时间不能超过一年')

  return {
    deviceId,
    title,
    remindAt: remindAt.toISOString(),
    timezone,
  }
}

export function reminderToForm(reminder: { deviceId: string, title: string, remindAt: string, timezone?: string }): ReminderFormValue {
  const remindAt = new Date(reminder.remindAt)
  if (Number.isNaN(remindAt.getTime()))
    throw new Error('提醒时间格式无效')
  const timezone = reminder.timezone || DEFAULT_REMINDER_TIMEZONE
  const picker = formatInstantInTimezone(remindAt, timezone)
  return {
    deviceId: reminder.deviceId,
    title: reminder.title,
    date: picker.date,
    time: picker.time,
    timezone,
    originalRemindAt: remindAt.toISOString(),
    originalDate: picker.date,
    originalTime: picker.time,
    originalTimezone: timezone,
  }
}

interface ZonedParts {
  year: number
  month: number
  day: number
  hour: number
  minute: number
}

function formatInstantParts(value: Date, timezone: string): ZonedParts {
  const formatter = new Intl.DateTimeFormat('en-CA', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  })
  const parts: Record<string, number> = {}
  formatter.formatToParts(value).forEach((part) => {
    if (part.type !== 'literal')
      parts[part.type] = Number(part.value)
  })
  return {
    year: parts.year,
    month: parts.month,
    day: parts.day,
    hour: parts.hour,
    minute: parts.minute,
  }
}

function formatInstantInTimezone(value: Date, timezone: string) {
  try {
    const parts = formatInstantParts(value, timezone)
    return {
      date: `${parts.year}-${pad(parts.month)}-${pad(parts.day)}`,
      time: `${pad(parts.hour)}:${pad(parts.minute)}`,
    }
  }
  catch {
    if (timezone !== DEFAULT_REMINDER_TIMEZONE)
      throw new Error('当前运行环境不支持该提醒时区')
    const shifted = new Date(value.getTime() + 8 * 60 * 60 * 1000)
    return {
      date: `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`,
      time: `${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}`,
    }
  }
}

export function formatReminderDateTime(value: string | Date, timezone: string) {
  const instant = value instanceof Date ? value : new Date(value)
  if (Number.isNaN(instant.getTime()))
    return String(value)
  const picker = formatInstantInTimezone(instant, timezone)
  return `${picker.date} ${picker.time}`
}
