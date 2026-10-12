export const OPERATIONS_BASE = '/ops/';

// Legacy hashes contain router paths, never an origin or a path outside OPS.
export function legacyOperationsTarget(location) {
  if (location.pathname !== '/ops.html') return null;
  const route = (location.hash || '').slice(1);
  const pathname = route.split(/[?#]/, 1)[0];
  // Current OPS routes use plain path segments. Reject traversal, encoded paths,
  // backslashes and control characters before passing anything to browser history.
  if (!/^\/(?:[a-zA-Z0-9_-]+\/?)*$/.test(pathname) || /[\u0000-\u0020\u007f\\]/.test(route)) {
    return OPERATIONS_BASE;
  }
  return `${OPERATIONS_BASE.slice(0, -1)}${route}`;
}

export function migrateLegacyOperationsLocation(location, history) {
  const target = legacyOperationsTarget(location);
  if (target !== null) history.replaceState(history.state, '', target);
}
