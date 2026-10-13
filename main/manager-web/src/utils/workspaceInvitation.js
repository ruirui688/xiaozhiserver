export const PENDING_WORKSPACE_INVITE_KEY = 'xiaozhi:pendingWorkspaceInvite'

const DEFAULT_REDIRECT = '/home'
const MAX_REDIRECT_LENGTH = 2048
const PENDING_INVITE_TTL_MS = 24 * 60 * 60 * 1000
const INVITE_CODE_PATTERN = /^[A-Za-z0-9_-]{20,63}$/
const CONTROL_CHARACTER_PATTERN = /[\u0000-\u001f\u007f]/

function decodeForValidation(value) {
  let decoded = value
  for (let index = 0; index < 8; index += 1) {
    try {
      const next = decodeURIComponent(decoded)
      if (next === decoded) return decoded
      decoded = next
    } catch (_) {
      return null
    }
  }
  // Reject excessively nested encodings instead of validating a partially decoded target.
  return null
}

function storageOrDefault(storage) {
  if (storage) return storage
  if (typeof sessionStorage !== 'undefined') return sessionStorage
  return null
}

export function normalizeInviteCode(code) {
  if (typeof code !== 'string' || code !== code.trim()) return ''
  return INVITE_CODE_PATTERN.test(code) ? code : ''
}

export function safeInternalRedirect(value, fallback = DEFAULT_REDIRECT) {
  if (typeof value !== 'string' || !value || value !== value.trim() || value.length > MAX_REDIRECT_LENGTH) {
    return fallback
  }
  if (CONTROL_CHARACTER_PATTERN.test(value) || value.includes('\\') || value.includes('#')) return fallback

  const decoded = decodeForValidation(value)
  if (!decoded || CONTROL_CHARACTER_PATTERN.test(decoded) || decoded.includes('\\') || decoded.includes('#')) {
    return fallback
  }
  if (!decoded.startsWith('/') || decoded.startsWith('//')) return fallback

  const decodedPath = decoded.split('?')[0]
  if (decodedPath.split('/').some(segment => segment === '.' || segment === '..')) return fallback

  try {
    const parsed = new URL(value, 'https://xiaozhi.invalid')
    if (parsed.origin !== 'https://xiaozhi.invalid') return fallback
  } catch (_) {
    return fallback
  }
  return value
}

export function workspaceInvitePath(code) {
  const normalized = normalizeInviteCode(code)
  return normalized ? `/invite?code=${encodeURIComponent(normalized)}` : ''
}

export function isWorkspaceInviteRedirect(value) {
  const redirect = safeInternalRedirect(value, '')
  if (!redirect) return false
  try {
    const parsed = new URL(redirect, 'https://xiaozhi.invalid')
    return parsed.pathname === '/invite'
  } catch (_) {
    return false
  }
}

export function rememberPendingWorkspaceInvite(code, storage) {
  const normalized = normalizeInviteCode(code)
  if (!normalized) return ''
  const targetStorage = storageOrDefault(storage)
  if (!targetStorage) return normalized
  try {
    targetStorage.setItem(PENDING_WORKSPACE_INVITE_KEY, JSON.stringify({ code: normalized, savedAt: Date.now() }))
  } catch (_) {
    // Some privacy modes disable sessionStorage. The landing page keeps the code in memory for this visit.
  }
  return normalized
}

export function readPendingWorkspaceInvite(storage, now = Date.now()) {
  const targetStorage = storageOrDefault(storage)
  if (!targetStorage) return ''
  try {
    const raw = targetStorage.getItem(PENDING_WORKSPACE_INVITE_KEY)
    if (!raw) return ''
    const saved = JSON.parse(raw)
    const validTimestamp = Number.isFinite(saved.savedAt)
      && saved.savedAt <= now
      && now - saved.savedAt <= PENDING_INVITE_TTL_MS
    const code = normalizeInviteCode(saved.code)
    if (!validTimestamp || !code) {
      targetStorage.removeItem(PENDING_WORKSPACE_INVITE_KEY)
      return ''
    }
    return code
  } catch (_) {
    try { targetStorage.removeItem(PENDING_WORKSPACE_INVITE_KEY) } catch (_) {}
    return ''
  }
}

export function clearPendingWorkspaceInvite(storage) {
  const targetStorage = storageOrDefault(storage)
  if (!targetStorage) return
  try { targetStorage.removeItem(PENDING_WORKSPACE_INVITE_KEY) } catch (_) {}
}

export function resolvePostAuthRedirect(value, storage) {
  const explicit = safeInternalRedirect(value, '')
  if (explicit) {
    if (isWorkspaceInviteRedirect(explicit)) {
      try {
        const parsed = new URL(explicit, 'https://xiaozhi.invalid')
        const code = normalizeInviteCode(parsed.searchParams.get('code') || '')
        if (code) rememberPendingWorkspaceInvite(code, storage)
      } catch (_) {}
      return '/invite'
    }
    return explicit
  }
  return readPendingWorkspaceInvite(storage) ? '/invite' : DEFAULT_REDIRECT
}

export function buildWorkspaceInviteLink(code, locationLike) {
  const normalized = normalizeInviteCode(code)
  if (!normalized) return ''
  const currentLocation = locationLike || (typeof window !== 'undefined' ? window.location : null)
  if (!currentLocation) return ''
  const origin = currentLocation.origin
    || `${currentLocation.protocol || ''}//${currentLocation.host || ''}`
  const pathname = currentLocation.pathname || '/'
  let parsedOrigin
  try {
    parsedOrigin = new URL(origin)
  } catch (_) {
    return ''
  }
  if (!['http:', 'https:'].includes(parsedOrigin.protocol)
      || parsedOrigin.origin !== origin
      || parsedOrigin.username
      || parsedOrigin.password
      || !pathname.startsWith('/')
      || pathname.startsWith('//')
      || pathname.includes('\\')
      || pathname.includes('#')) {
    return ''
  }
  return `${origin}${pathname}#${workspaceInvitePath(normalized)}`
}

export function isWorkspaceInviteFlowRoute(route, pendingCode = '') {
  if (!route) return false
  if (route.path === '/invite') return true
  const authRoute = route.path === '/login' || route.path === '/register'
  if (!authRoute) return false
  return isWorkspaceInviteRedirect(route.query && route.query.redirect) || Boolean(normalizeInviteCode(pendingCode))
}
