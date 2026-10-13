export const metricDefinitions = {
  input_tokens: { key: 'input_tokens', label: '输入 Token', unit: 'Token' },
  output_tokens: { key: 'output_tokens', label: '输出 Token', unit: 'Token' },
  audio_duration: { key: 'audio_duration', label: '识别音频', unit: '毫秒' },
  billable_characters: { key: 'billable_characters', label: '合成文字', unit: '字符' },
};
export function fieldsForService(serviceType) {
  return ({ LLM: ['input_tokens', 'output_tokens'], ASR: ['audio_duration'], TTS: ['billable_characters'] })[serviceType]?.map(key => metricDefinitions[key]) || [];
}
export function parseMetricMap(value) {
  try { const map = typeof value === 'string' ? JSON.parse(value) : value; return map && typeof map === 'object' && !Array.isArray(map) ? map : {}; } catch (_) { return {}; }
}
export function ratePayload(form, fields) {
  if (!form.modelId || !form.version.trim() || !fields.length) throw new Error('请选择受支持的模型并填写价格版本');
  const metricRates = {}, maxUsage = {};
  for (const field of fields) {
    const price = String(form.prices[field.key] ?? '').trim(), maximum = String(form.maximums[field.key] ?? '').trim();
    if (!/^\d+(\.\d+)?$/.test(price)) throw new Error(`请填写${field.label}的非负单位价格`);
    if (!/^\d+$/.test(maximum) || !/[1-9]/.test(maximum)) throw new Error(`请填写${field.label}的正整数调用上限`);
    metricRates[field.key] = price; maxUsage[field.key] = maximum;
  }
  return { modelId: form.modelId, version: form.version.trim(), metricRates, maxUsage };
}
