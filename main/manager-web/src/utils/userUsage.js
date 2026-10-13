import { billingFiltersFromQuery, billingQuery, defaultBillingFilters } from './billing';

const FILTERS = ['startDate', 'endDate', 'serviceType', 'agentId', 'deviceId', 'modelId', 'board', 'keyword'];
export const USER_USAGE_GROUPS = ['agents', 'devices', 'events'];

export function userUsageFilters(query = {}) {
  const source = billingFiltersFromQuery(query);
  return Object.fromEntries(FILTERS.map(key => [key, source[key]]));
}
export function defaultUserUsageFilters() { return userUsageFilters(defaultBillingFilters()); }
export function userUsageQuery(filters, page = 1, limit = 10) {
  const selected = Object.fromEntries(FILTERS.map(key => [key, filters[key]]));
  return billingQuery(selected, page, limit);
}
export function userUsageRoute(filters, group, page, limit) {
  return Object.fromEntries(Object.entries({ ...userUsageQuery(filters, page, limit), group: USER_USAGE_GROUPS.includes(group) ? group : 'agents' }).map(([key, value]) => [key, String(value)]));
}
export function userUsageCount(value) {
  if ((typeof value !== 'string' && typeof value !== 'number') || !/^\d+$/.test(String(value))) return null;
  const count = Number(value);
  return Number.isSafeInteger(count) ? count : null;
}
export function userUsagePage(result) {
  const total = userUsageCount(result?.total);
  if (!Array.isArray(result?.list) || total === null) throw new Error('记录暂时无法读取，请重试');
  return { list: result.list, total };
}
export function settlementLabel(status) {
  return ({ SETTLED: '已结算', RESERVED: '已预占', STARTED: '结算中', UNKNOWN: '待核对', RELEASED: '已释放', EXPIRED: '已过期', CANCELLED: '已取消', UNTRACKED: '无关联结算记录' })[status] || '结算状态未知';
}
export function settlementType(status) {
  return status === 'SETTLED' ? 'success' : status === 'UNKNOWN' ? 'warning' : 'info';
}
export function usageUnknownCount(overview) { return userUsageCount(overview?.usageUnknownCount); }
export function hasIncompleteUsage(row) { return row?.usageComplete === false || row?.usageComplete === 0 || (userUsageCount(row?.usageUnknownCount) || 0) > 0; }
export function confirmedSettlementAmount(value) {
  if (!value) return null;
  if (userUsageCount(value.settledCount) === 0 && ['pendingCount','reviewCount','unlinkedCount'].some(key => (userUsageCount(value[key]) || 0) > 0)) return null;
  return value.settledAmount;
}
