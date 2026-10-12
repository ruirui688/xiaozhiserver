const VIEWS = ['costs', 'benefits', 'usage'];
const KEYS = ['view', 'scope', 'provider', 'accountProvider', 'accountRef', 'billPeriod', 'product', 'group', 'startDate', 'endDate', 'serviceType', 'agentId', 'deviceId', 'modelId', 'board', 'keyword', 'page', 'limit', 'assetStatus', 'assetProduct'];

export function billingRoute(query = {}, canManage = false) {
  const legacy = { overview: 'usage', agents: 'usage', devices: 'usage', events: 'usage', bills: 'costs', benefits: 'benefits' };
  const legacyAdminHome = canManage && query.tab === 'overview' && !['agentId', 'deviceId', 'modelId', 'board', 'keyword'].some(key => !!query[key]);
  const view = VIEWS.includes(query.view) ? query.view : legacyAdminHome ? 'costs' : legacy[query.tab] || (canManage ? 'costs' : 'usage');
  const legacyWorkspace = !legacyAdminHome && !!legacy[query.tab] && query.unattributed !== 'true';
  const scope = !canManage || query.scope === 'workspace' || (!query.scope && legacyWorkspace) || view === 'usage' ? 'workspace' : 'account';
  const group = ['agents', 'devices', 'events'].includes(query.group) ? query.group : ['agents', 'devices', 'events'].includes(query.tab) ? query.tab : 'agents';
  return { view, scope, group };
}

export function billingNavigationQuery(query = {}, patch = {}, canManage = false) {
  const result = {};
  KEYS.forEach(key => { if (typeof query[key] === 'string' && query[key]) result[key] = query[key]; });
  Object.entries(patch).forEach(([key, value]) => { if (KEYS.includes(key)) { if (value === '' || value === null || value === undefined) delete result[key]; else result[key] = String(value); } });
  const state = billingRoute({ ...result, ...(!result.view && query.tab ? { tab: query.tab, unattributed: query.unattributed } : {}) }, canManage);
  Object.assign(result, state);
  if (!canManage) { delete result.accountRef; delete result.accountProvider; }
  return result;
}

export function accountKey(account) { return JSON.stringify([account.provider, account.accountRef]); }

export function accountQuery(account, billPeriod, extra = {}) {
  return { provider: account.provider, accountRef: account.accountRef, billPeriod, ...extra };
}
