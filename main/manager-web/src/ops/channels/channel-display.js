export const channelProviders = [{ value: 'DEEPSEEK', label: 'DeepSeek' }, { value: 'VOLCENGINE', label: '火山引擎' }];
export function providerLabel(value) { return channelProviders.find(item => item.value === value)?.label || value || '—'; }
export function capabilityLabel(value) { return ({ LLM: '大模型', ASR: '语音识别', TTS: '语音合成' })[value] || value || '—'; }
export function executionLabel(value) {
  return ({ SETTLED: '已结算', STARTED: '调用中', RESERVED: '待执行', UNKNOWN: '待核对', FAILED: '失败', SUCCESS: '成功', RELEASED: '已释放', EXPIRED: '已过期', CANCELLED: '已取消' })[value] || (value ? '待核对' : '暂无调用');
}
export function channelPayload(form, row, requestId) {
  const payload = { requestId, name: form.name.trim(), modelId: form.modelId, accountRef: form.accountRef.trim(), region: form.region.trim(), projectRef: form.projectRef.trim(), enabled: form.enabled, maxConcurrency: form.maxConcurrency };
  if (!payload.name || !payload.modelId || !payload.accountRef) throw new Error('请填写通道名称、模型和供应商账号');
  if (!Number.isInteger(payload.maxConcurrency) || payload.maxConcurrency < 1 || payload.maxConcurrency > 200) throw new Error('并发上限须为 1 到 200 的整数');
  const key = form.apiKey.trim();
  if (!row?.credentialConfigured && !key) throw new Error('请填写供应商 API Key');
  if (row?.id && payload.accountRef !== row.accountRef && !key) throw new Error('更换供应商账号时，请同时填写新账号的 API Key');
  if (key) payload.secrets = { api_key: key };
  if (row?.id) { payload.id = row.id; payload.expectedVersion = row.version; }
  return payload;
}
