// Each HTML entry has its own audience and token namespace. Server authorization is authoritative.
export function consoleAudience() {
  return typeof document !== 'undefined' && document.documentElement.dataset.console === 'OPS' ? 'OPS' : 'USER';
}
export function isOperationsConsole() { return consoleAudience() === 'OPS'; }
export function sessionStorageKey(key) { return isOperationsConsole() ? `xiaozhi:ops:${key}` : key; }
export function sessionStorageBackend() { return isOperationsConsole() ? sessionStorage : localStorage; }
export function readSession(key) { return sessionStorageBackend().getItem(sessionStorageKey(key)); }
export function writeSession(key, value) { sessionStorageBackend().setItem(sessionStorageKey(key), value); }
export function removeSession(key) { sessionStorageBackend().removeItem(sessionStorageKey(key)); }
export function hasPermission(profile, permission) {
  return profile?.audience === 'OPS' && Array.isArray(profile.permissions) && profile.permissions.includes(permission);
}
export function parseStoredProfile() {
  try { return JSON.parse(readSession('userInfo') || '{}'); } catch (_) { return {}; }
}
