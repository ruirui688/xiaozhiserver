export function allowanceAmount(value) {
  if (value === null || value === undefined || !/^-?\d+(\.\d+)?$/.test(String(value))) return '—';
  // Keep server decimal strings intact; converting large balances to Number loses precision.
  return String(value);
}
export function allowancePayerLabel(summary) {
  return summary?.canViewBalance === true ? '当前空间使用本人的凭据和服务额度' : '当前空间使用负责人的凭据和服务额度';
}
export function visibleAllowance(summary) {
  return summary?.canViewBalance === true && summary.allowance ? summary.allowance : null;
}
export function platformErrorMessage(value) {
  return ({
    ALLOWANCE_EXHAUSTED: '服务额度已用完，请联系平台运维',
    WORKSPACE_ALLOWANCE_LIMIT: '当前工作空间已达使用上限，请联系负责人',
    TEAM_ALLOWANCE_LIMIT: '当前工作空间已达使用上限，请联系负责人',
    PAYER_FROZEN: '负责人服务账户已暂停，请联系平台处理',
    WORKSPACE_DISABLED: '当前工作空间已停用',
    PENDING_CREDENTIAL: '模型服务待开通，请等待平台补齐凭据',
    CREDENTIAL_SERVICE_NOT_READY: '此模型能力尚未开通，缺少对应服务凭据',
    CREDENTIAL_CHANGED: '模型凭据已更新，请重试',
    MODEL_NOT_ADMITTED: '此模型尚未开放，请选择已批准的模型',
    PLATFORM_SERVICE_BUDGET_REQUIRED: '请先配置该模型与任务的独立服务账户、凭据和预算',
    MODEL_OUTSIDE_PLATFORM_SERVICE: '此模型不在该任务账户的授权范围内',
    UNSUPPORTED_MANAGED_PROVIDER: '该模型适配器尚未接入平台额度服务',
    WORKSPACE_OWNER_REQUIRED: '请切换到本人负责的工作空间后操作',
    IDEMPOTENCY_CONFLICT: '本次操作内容与先前提交不一致，请刷新核对结果',
  })[value] || value || '请求失败，请稍后重试';
}
export function newOperationId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}
