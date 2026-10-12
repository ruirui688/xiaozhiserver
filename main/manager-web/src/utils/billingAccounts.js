import { billingTime } from './billing';

export function billingAccountCapabilities(account = {}, data = {}) {
  const source = data.capabilities || account.capabilities || {};
  const deepseek = account.provider === 'DEEPSEEK';
  return {
    billSyncSupported: source.billSyncSupported === true,
    statementImportSupported: source.statementImportSupported === true || (deepseek && source.statementImportSupported === undefined),
    balanceSupported: source.balanceSupported === true || (deepseek && source.balanceSupported === undefined)
  };
}

export function billingAccountState(account = {}, data = {}) {
  const capabilities = billingAccountCapabilities(account, data);
  const importOnly = !capabilities.billSyncSupported && capabilities.statementImportSupported;
  const sync = data.sync || {};
  const imported = sync.lastAttemptStatus === 'IMPORTED_STATEMENT' || (importOnly && (data.costs || []).length > 0);
  const status = (data.coverage || {}).status;
  return {
    capabilities, importOnly, imported,
    coverageLabel: importOnly ? (imported ? '已导入 · 覆盖待核对' : '待导入官方凭证') : ({ SNAPSHOT: '官方快照', PARTIAL: '覆盖待核对', EMPTY_CONFIRMED: '已同步 · 无账单', NOT_OBTAINED: '尚未取得账单' })[status] || '覆盖待核对',
    emptyText: imported ? '已导入凭证，暂无可汇总金额' : importOnly ? '尚未导入官方消费凭证' : status === 'EMPTY_CONFIRMED' ? '本账期暂无官方账单' : '尚无官方消费账单',
    sourceTime: imported ? (sync.lastImportedAt || sync.lastSnapshotAt ? `最近导入 ${billingTime(sync.lastImportedAt || sync.lastSnapshotAt)}` : '已有人工导入账单')
      : importOnly ? '' : sync.lastSuccessAt ? `最近成功同步 ${billingTime(sync.lastSuccessAt)}` : sync.lastSnapshotAt ? `已有账单快照 ${billingTime(sync.lastSnapshotAt)}` : '尚无成功同步记录'
  };
}
