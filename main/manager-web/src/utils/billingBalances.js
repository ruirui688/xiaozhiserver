const SUCCESS_STATES = ['READY', 'SUCCESS'];

// A balance inherits sync evidence only from its exact supplier account.
export function balanceSnapshotState(balance = {}, sources = [], refreshFailed = false) {
  const matches = typeof balance.provider === 'string' && balance.provider && typeof balance.accountRef === 'string' && balance.accountRef
    ? (Array.isArray(sources) ? sources : []).filter(source => source && source.provider === balance.provider && source.accountRef === balance.accountRef
      && (source.currency == null || source.currency === balance.currency)) : [];
  const source = matches.length === 1 ? matches[0] : null;
  const snapshotAt = source && [source.lastSuccessAt, source.lastSyncedAt].find(value =>
    (typeof value === 'string' && value.trim() || typeof value === 'number') && Number.isFinite(new Date(value).getTime())) || null;
  const status = refreshFailed ? 'SYNC_FAILED' : SUCCESS_STATES.includes(source?.status) && source.lastAttemptStatus != null
    ? source.lastAttemptStatus : source?.status;
  const healthy = Boolean(source && snapshotAt && SUCCESS_STATES.includes(status)
    && (source.lastAttemptStatus == null || SUCCESS_STATES.includes(source.lastAttemptStatus)));
  const notes = {
    NOT_CONFIGURED: '余额查询尚未配置，当前无法刷新；金额保留自上次快照。',
    SYNC_FAILED: '最近同步失败，无法确认最新余额；金额保留自上次快照。',
    NEVER_SYNCED: '尚无可核实的成功同步，不能确认当前余额。',
    UNSUPPORTED: '当前来源不支持刷新余额；金额保留自上次快照。',
  };
  return {
    healthy,
    snapshotAt,
    badge: healthy ? '已同步快照' : '历史快照 · 待更新',
    availability: healthy ? balance.isAvailable === true ? '可用' : balance.isAvailable === false ? '不可用' : '供应商未提供' : '无法确认',
    note: healthy ? '' : notes[status] || '同步状态或快照时间尚未核实，不能确认当前余额。',
  };
}
