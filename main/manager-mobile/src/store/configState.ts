import type { PublicConfig } from '../api/auth'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function stringValue(value: unknown, fallback: string) {
  return typeof value === 'string' ? value : fallback
}

function booleanValue(value: unknown, fallback: boolean) {
  return typeof value === 'boolean' ? value : fallback
}

function mobileAreaListValue(value: unknown): PublicConfig['mobileAreaList'] {
  if (!Array.isArray(value))
    return []

  return value.flatMap((item) => {
    if (!isRecord(item) || typeof item.name !== 'string' || typeof item.key !== 'string')
      return []

    return [{ name: item.name, key: item.key }]
  })
}

export function createInitialPublicConfig(appTitle = ''): PublicConfig {
  return {
    enableMobileRegister: false,
    version: '',
    year: '',
    allowUserRegister: false,
    mobileAreaList: [],
    beianIcpNum: '',
    beianGaNum: '',
    sm2PublicKey: '',
    name: appTitle,
  }
}

export function normalizePublicConfig(value: unknown, appTitle = ''): PublicConfig {
  const defaults = createInitialPublicConfig(appTitle)
  const source = isRecord(value) && isRecord(value.config) ? value.config : value
  if (!isRecord(source))
    return defaults

  return {
    enableMobileRegister: booleanValue(source.enableMobileRegister, defaults.enableMobileRegister),
    version: stringValue(source.version, defaults.version),
    year: stringValue(source.year, defaults.year),
    allowUserRegister: booleanValue(source.allowUserRegister, defaults.allowUserRegister),
    mobileAreaList: mobileAreaListValue(source.mobileAreaList),
    beianIcpNum: stringValue(source.beianIcpNum, defaults.beianIcpNum),
    beianGaNum: stringValue(source.beianGaNum, defaults.beianGaNum),
    sm2PublicKey: stringValue(source.sm2PublicKey, defaults.sm2PublicKey),
    name: stringValue(source.name, defaults.name),
  }
}

export function deserializePublicConfig(value: unknown, appTitle = ''): PublicConfig {
  if (typeof value !== 'string')
    return normalizePublicConfig(value, appTitle)

  try {
    return normalizePublicConfig(JSON.parse(value), appTitle)
  }
  catch {
    return createInitialPublicConfig(appTitle)
  }
}
