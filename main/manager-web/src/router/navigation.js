let activeRouter;
export function setNavigationRouter(router) { activeRouter = router; }
export function navigate(path, replace = false) {
  if (activeRouter) return activeRouter[replace ? 'replace' : 'push'](path);
  window.location.hash = path;
}
