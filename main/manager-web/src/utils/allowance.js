export function allowanceAmount(value) {
  if (value === null || value === undefined || !/^-?\d+(\.\d+)?$/.test(String(value))) return '—';
  // Keep server decimal strings intact; converting large balances to Number loses precision.
  return String(value);
}
export function allowanceDisplayAmount(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return '—';
  const text = String(value);
  const parts = /^(-?)(\d+)(?:\.(\d+))?$/.exec(text);
  if (!parts || parts[0] !== text) return '—';
  const sign = parts[1];
  const whole = parts[2].replace(/^0+(?=\d)/, '');
  const fraction = parts[3] || '';
  if (whole === '0' && !/[1-9]/.test(fraction)) return '0.00';
  const cents = fraction.padEnd(2, '0').slice(0, 2);
  if (whole === '0' && cents === '00') return sign ? '大于 -0.01 且小于 0' : '小于 0.01';
  // Round decimal digits, never the monetary value as a floating-point number.
  const digits = (whole + cents).split('');
  if (fraction.length > 2 && fraction[2] >= '5') {
    let index = digits.length - 1;
    while (index >= 0 && digits[index] === '9') digits[index--] = '0';
    if (index < 0) digits.unshift('1');
    else digits[index] = String.fromCharCode(digits[index].charCodeAt(0) + 1);
  }
  return `${sign}${digits.slice(0, -2).join('')}.${digits.slice(-2).join('')}`;
}
export function allowancePayerLabel(summary) {
  return summary?.canViewBalance === true ? '当前空间使用本人的服务额度' : '当前空间使用负责人的服务额度';
}
export function visibleAllowance(summary) {
  return summary?.canViewBalance === true && summary.allowance ? summary.allowance : null;
}
export function allowanceServiceState(summary) {
  return ({
    READY: { label: '服务已就绪', type: 'success', description: '' },
    PAYER_FROZEN: { label: '服务账户已暂停', type: 'danger', description: '当前空间负责人的服务账户已暂停，请联系平台处理。' },
    ALLOWANCE_BUSY: { label: '服务正在结算', type: 'info', description: '服务正在结算，请稍后重试。' },
    ALLOWANCE_EXHAUSTED: { label: '可用额度不足', type: 'warning', description: '当前空间负责人的可用额度不足，新的付费调用暂时无法开始，请核对额度是否已发放。' },
    WORKSPACE_ALLOWANCE_LIMIT: { label: '当前空间已达使用上限', type: 'warning', description: '当前空间暂无法开始新的付费调用，请稍后重试或联系负责人调整上限。' },
    PLATFORM_SERVICE_NOT_READY: { label: '平台服务待开通', type: 'info', description: '平台尚未开通可用的模型服务，请联系平台处理。' }
  })[summary?.serviceStatus] || { label: '服务状态待确认', type: 'info', description: '暂未确认模型服务状态，请刷新后重试。' };
}
export function platformErrorMessage(value) {
  return ({
    ISSUANCE_POOL_EXHAUSTED: '额度总池的可发放余额不足，本次未发放。请由有权限的管理员补充总池预算后重试',
    GRANT_LIMIT_EXCEEDED: '发放金额超过单笔上限或为零，请核对金额及平台发放限额',
    OPERATOR_DAILY_GRANT_LIMIT: '本次发放将超过操作员今日发放限额，请核对发放记录或联系平台管理员',
    POOL_ALREADY_ALLOCATED: '总池收回金额超过尚未发放的余额，不能收回已分配给用户的额度',
    ALLOWANCE_ALREADY_USED: '用户额度已使用或被预占，无法收回超过当前可用余额的额度',
    ALLOWANCE_BUSY: '服务正在结算，请稍后重试',
    ALLOWANCE_EXHAUSTED: '可用服务额度不足，请核对额度是否已发放或联系平台运维',
    WORKSPACE_ALLOWANCE_LIMIT: '当前工作空间已达使用上限，请联系负责人',
    TEAM_ALLOWANCE_LIMIT: '当前工作空间已达使用上限，请联系负责人',
    PAYER_FROZEN: '负责人服务账户已暂停，请联系平台处理',
    WORKSPACE_DISABLED: '当前工作空间已停用',
    PENDING_CREDENTIAL: '模型服务暂不可用，请联系平台运维',
    CREDENTIAL_SERVICE_NOT_READY: '此模型服务尚未开通，请联系平台运维',
    CREDENTIAL_CHANGED: '模型服务配置已更新，请重试',
    CHANNEL_NOT_READY: '此模型尚未配置可用通道，请联系平台运维',
    CHANNEL_UNAVAILABLE: '模型服务暂不可用，请稍后重试',
    CHANNEL_CAPACITY_EXCEEDED: '模型服务繁忙，请稍后重试',
    CHANNEL_VERSION_CONFLICT: '通道已被其他人员更新，请刷新后重新配置',
    INVALID_CHANNEL_INPUT: '请检查通道名称、模型和供应商账号',
    INVALID_CHANNEL_CONCURRENCY: '并发上限须为 1 到 200 的整数',
    INVALID_CHANNEL_CREDENTIAL: '请填写有效的供应商 API Key',
    CHANNEL_NOT_FOUND: '通道已不存在，请刷新列表',
    CHANNEL_VERSION_CHANGED: '通道已被其他人员更新，请关闭后重新打开配置',
    CHANNEL_MODEL_IMMUTABLE: '已有通道不能更换模型，请为目标模型新建通道',
    CHANNEL_CREDENTIAL_REQUIRED: '请填写供应商 API Key',
    CHANNEL_MODEL_EXISTS: '此模型已有通道，请编辑已有通道',
    CHANNEL_ACCOUNT_REQUIRES_CREDENTIAL: '更换供应商账号时，请同时填写新账号的 API Key',
    INVALID_CHANNEL_SERVICE: '此模型能力暂不支持平台通道',
    MODEL_CHANNEL_UNAVAILABLE: '此模型暂无可用通道，请联系平台运维',
    MODEL_CHANNEL_MISMATCH: '模型与通道配置不匹配，请联系平台运维',
    CHANNEL_CONCURRENCY_LIMIT: '模型服务繁忙，请稍后重试',
    CHANNEL_CREDENTIAL_CHANGED: '通道配置已更新，请重新发起调用',
    MODEL_NOT_FOUND: '模型已不存在，请重新选择',
    MODEL_NOT_PUBLISHED: '模型尚未发布，请先完成模型测试与发布',
    ADAPTER_ADMISSION_UNSUPPORTED: '此模型适配器暂不支持平台额度服务',
    UNAPPROVED_MODEL_ENDPOINT: '模型地址不在受支持的官方服务范围内',
    RATE_UNPUBLISHED: '此模型尚未发布平台服务价格',
    RATE_METRICS_MISMATCH: '价格计量单位与模型能力不匹配，请重新选择模型',
    MODEL_NOT_ADMITTED: '此模型尚未开放，请选择已批准的模型',
    PLATFORM_SERVICE_BUDGET_REQUIRED: '请先配置该模型与任务的服务账户和预算',
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
