export const BILLING_TABS = ['overview', 'agents', 'devices', 'events', 'bills', 'benefits'];
const FILTER_KEYS = ['startDate', 'endDate', 'provider', 'serviceType', 'agentId', 'deviceId', 'modelId', 'keyword', 'board'];

export function chinaDate(value = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(value);
  const get = key => parts.find(part => part.type === key).value;
  return `${get('year')}-${get('month')}-${get('day')}`;
}

export function defaultBillingFilters(now = new Date()) {
  const today = chinaDate(now);
  return { startDate: `${today.slice(0, 7)}-01`, endDate: today, provider: '', serviceType: '', agentId: '', deviceId: '', modelId: '', keyword: '', board: '' };
}

export function validDate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T00:00:00.000Z`);
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

export function validateBillingDates(filters) {
  if (!validDate(filters.startDate) || !validDate(filters.endDate)) return '请选择完整、有效的起止日期';
  if (filters.startDate > filters.endDate) return '开始日期不能晚于结束日期';
  return '';
}

export function billingFiltersFromQuery(query = {}, now) {
  const filters = defaultBillingFilters(now);
  FILTER_KEYS.forEach(key => { if (typeof query[key] === 'string') filters[key] = query[key]; });
  return filters;
}

export function billingQuery(filters, page = 1, limit = 20) {
  const result = {};
  FILTER_KEYS.forEach(key => {
    if (typeof filters[key] === 'string' && filters[key].trim()) result[key] = filters[key].trim();
  });
  result.page = Math.max(1, Number(page) || 1);
  result.limit = Math.min(100, Math.max(1, Number(limit) || 20));
  return result;
}

// Keep supplier decimal strings exact. Missing amounts must never become zero.
export function decimalText(value, minDecimals = 0) {
  if (value === null || value === undefined || value === '') return '—';
  let text = String(value);
  const scientific = text.match(/^(-?)(\d+)(?:\.(\d+))?[eE]([+-]?\d+)$/);
  if (scientific) {
    const exponent = Number(scientific[4]);
    if (Math.abs(exponent) > 100) return '—';
    const digits = scientific[2] + (scientific[3] || '');
    const point = scientific[2].length + exponent;
    text = scientific[1] + (point <= 0 ? `0.${'0'.repeat(-point)}${digits}` : point >= digits.length ? digits + '0'.repeat(point - digits.length) : `${digits.slice(0, point)}.${digits.slice(point)}`);
  }
  if (!/^-?\d+(?:\.\d+)?$/.test(text)) return '—';
  const [whole, fraction = ''] = text.split('.');
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (fraction || minDecimals ? `.${fraction.padEnd(minDecimals, '0')}` : '');
}

export function amountText(value, currency) {
  const amount = decimalText(value, 2);
  return amount === '—' ? amount : `${currency || '币种未提供'} ${amount}`;
}

export function billingLabel(value) {
  const labels = {
    LLM: '大模型', ASR: '语音识别', TTS: '语音合成', REALTIME: '实时多模态', EMBEDDING: '向量嵌入', OTHER: '其他服务',
    deepseek: 'DeepSeek', volcengine: '火山引擎', DEEPSEEK: 'DeepSeek', VOLCENGINE: '火山引擎', VOLCENGINE_SPEECH: '火山语音资源包',
    prompt_tokens: '输入 Token', input_tokens: '输入 Token', completion_tokens: '输出 Token', output_tokens: '输出 Token',
    total_tokens: '总 Token', prompt_cache_hit_tokens: '缓存命中 Token', prompt_cache_miss_tokens: '缓存未命中 Token',
    cache_hit_tokens: '缓存命中 Token', cache_miss_tokens: '缓存未命中 Token', reasoning_tokens: '推理 Token',
    cached_input_tokens: '输入缓存命中 Token', cache_miss_input_tokens: '输入缓存未命中 Token',
    input_audio_tokens: '输入音频 Token', output_audio_tokens: '输出音频 Token', audio_tokens: '音频 Token',
    input_text_tokens: '输入文本 Token', output_text_tokens: '输出文本 Token', input_cached_tokens: '输入缓存 Token',
    prompt_tokens_details_cached_tokens: '输入缓存 Token', prompt_tokens_details_audio_tokens: '输入音频 Token',
    completion_tokens_details_reasoning_tokens: '输出推理 Token', completion_tokens_details_audio_tokens: '输出音频 Token',
    audio_duration_ms: '音频时长', audio_duration_seconds: '音频时长', audio_duration: '音频时长',
    input_audio_duration_ms: '输入音频时长', output_audio_duration_ms: '输出音频时长',
    character_count: '字符数', characters: '字符', text_characters: '字符数',
    submitted_audio_duration: '提交音频时长', billable_characters: '供应商计费字符', submitted_characters: '提交字符',
    provider: '供应商返回', PROVIDER: '供应商返回', provider_reported: '供应商返回', PROVIDER_REPORTED: '供应商返回',
    measured: '本地观测', MEASURED: '本地观测', local_measured: '本地观测', LOCAL_MEASURED: '本地观测',
    OBSERVED: '本地观测', LOCAL: '本地观测',
    OFFICIAL_BILLED: '官方已出账', PENDING_OR_UNATTRIBUTED: '待出账或未归属', NOT_MATCHED: '无逐调用账单',
    OFFICIAL: '官方已出账', IMPORTED_STATEMENT: '人工导入官方账单', NO_ATTRIBUTABLE_BILLS: '无可归属官方账单', NO_CALL_LEVEL_BILL: '无逐调用账单',
    SUCCESS: '成功', FAILED: '失败', ERROR: '失败', ABORTED: '已打断', CANCELLED: '已取消', UNKNOWN: '未知',
    WORKSPACE: '工作空间', AGENT: '智能体', DEVICE: '设备', UNATTRIBUTED: '未归属', UNASSIGNED: '未归属', AMBIGUOUS: '归属冲突',
    CONVERSATION: '对话', MEMORY: '长期记忆', INTENT: '意图识别', CHAT_TITLE: '会话标题', CHAT_SUMMARY: '会话摘要', MEETING_SUMMARY: '会议总结',
    MODEL_TEST: '模型测试', BENCHMARK: '模型评测', BENCHMARK_JUDGE: '评测裁判', BENCHMARK_CASE_GENERATION: '评测样本生成',
    MODEL_ASSISTANT: '模型配置助手', CONFIG_ASSISTANT: '模型配置助手', EXTERNAL_TTS: '外部内容语音合成', BACKGROUND: '后台任务',
    KNOWLEDGE_CHUNKING: '知识库分块', KNOWLEDGE_EMBEDDING: '知识库向量化',
    BILL: '账单汇总', DETAIL: '账单明细', IMPORT_ONLY: '通过官方导出导入', VOLCENGINE_API: '火山引擎账单接口', STATEMENT_IMPORT: '人工导入账单（未自动核验）',
    ACCOUNT: '平台账号', NOT_CONFIGURED: '未配置', NEVER_SYNCED: '尚未同步', READY: '已同步', SYNC_FAILED: '同步失败', UNSUPPORTED: '暂不支持',
    ACTIVE: '有效', AVAILABLE: '可用', EXPIRED: '已过期', EXHAUSTED: '已用尽', PENDING: '待生效', INACTIVE: '未生效',
    Expired: '已过期', UsedUp: '已用尽', Unlimited: '不限量', unlimited: '不限量',
    PrePaid: '预付费', PostPaid: '后付费',
    MONTHLY: '每月', YEARLY: '每年', DAILY: '每日', monthly: '每月', yearly: '每年', daily: '每日', month: '月', year: '年', day: '天',
    minute: '分钟', minutes: '分钟', hour: '小时', hours: '小时', count: '次', requests: '次',
    token: 'Token', tokens: 'Token', ms: '毫秒', milliseconds: '毫秒', second: '秒', seconds: '秒', character: '字符', char: '字符'
  };
  return labels[value] || value || '—';
}

export function billingCoverageNotices(overview = {}, canManage = false) {
  const notices = [];
  if (Number(overview.usageUnknownCount) > 0) notices.push({
    key: 'usage', type: 'warning', title: `${decimalText(overview.usageUnknownCount)} 次调用缺少完整的供应商用量，当前用量汇总可能不完整。`
  });
  const status = overview.meteringStatus || {};
  if (!canManage) return notices;
  const details = [];
  if (Number(status.pendingCount) > 0) details.push(`${decimalText(status.pendingCount)} 条记录等待入账`);
  if (Number(status.rejectedCount) > 0) details.push(`${decimalText(status.rejectedCount)} 条记录被隔离，需核查`);
  if (Number(status.persistenceFailureCount) > 0) details.push(`${decimalText(status.persistenceFailureCount)} 次用量保存失败`);
  if (details.length || ['PENDING', 'INCOMPLETE', 'UNAVAILABLE'].includes(status.status)) notices.push({
    key: 'delivery', type: status.status === 'INCOMPLETE' || Number(status.rejectedCount) > 0 || Number(status.persistenceFailureCount) > 0 ? 'error' : 'warning',
    title: `管理服务${details.length ? `：${details.join('；')}` : status.status === 'UNAVAILABLE' ? '用量入账状态暂不可用' : '用量尚未完整入账'}。当前统计可能不完整。`,
    description: '此状态为平台范围，不受当前工作空间和筛选条件限制。'
  });
  return notices;
}

export function supplierUsageRows(usage = {}) {
  return [['计量数量', 'Count', 'Unit'], ['使用时长', 'UseDuration', 'UseDurationUnit'],
    ['抵扣数量', 'DeductionCount', 'Unit'], ['抵扣时长', 'DeductionUseDuration', 'UseDurationUnit']].map(([name, value, unit]) => {
    const quantity = decimalText(usage[value]);
    return { name, value: quantity === '—' ? '—' : `${quantity} ${usage[unit] ? billingLabel(usage[unit]) : '单位未提供'}` };
  });
}

export function billingTime(value) {
  if (!value) return '—';
  // Backend local date-times are already in the billing timezone.
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}/.test(value) && !/(?:Z|[+-]\d{2}:?\d{2})$/.test(value)) return value.replace('T', ' ');
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium' }).format(date) : String(value);
}

export function optionItems(items = []) {
  return items.map(item => typeof item === 'string' ? { id: item, name: billingLabel(item) } : item);
}
