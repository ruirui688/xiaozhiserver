// The catalog describes configuration, never a connection's live tool list.
const RETIRED_CODES = new Set(['set_session_voice', 'manage_memory']);
const RULE_FIELDS = new Set(['instructions', 'opening_message']);
const CATALOG_FIELDS = new Set([
  'description', 'custom_plugin', 'parameters_schema', 'catalog_only',
  'runtime_builtin', 'tool_source', 'activation_hint', 'lifecycle', 'catalogMetadata',
]);
const SHARED_FIELDS = {
  get_weather: ['api_host', 'api_key', 'default_location'],
  web_search: ['provider', 'api_key'],
};

export function pluginFields(plugin) {
  const fields = plugin && (plugin.fieldsMeta || plugin.fields);
  if (Array.isArray(fields)) return fields;
  try {
    const parsed = JSON.parse(fields || '[]');
    return Array.isArray(parsed) ? parsed : [];
  } catch (_) {
    return [];
  }
}

export function pluginDefault(plugin, key, fallback = '') {
  const field = pluginFields(plugin).find(item => item.key === key);
  return field && field.default !== undefined ? field.default : fallback;
}

export function pluginBoolean(plugin, key) {
  const value = pluginDefault(plugin, key, false);
  return value === true || String(value).toLowerCase() === 'true';
}

export function isRetiredPlugin(plugin) {
  return RETIRED_CODES.has(plugin && plugin.providerCode)
    || (plugin && plugin.catalogMetadata && plugin.catalogMetadata.lifecycle === 'retired');
}

export function isInteractionPlugin(plugin) {
  return (plugin && plugin.catalogMetadata && plugin.catalogMetadata.capabilityType === 'activity')
    || pluginBoolean(plugin, 'custom_plugin');
}

export function isSelectablePlugin(plugin) {
  return !isRetiredPlugin(plugin) && !pluginBoolean(plugin, 'catalog_only')
    && !(plugin && plugin.catalogMetadata && plugin.catalogMetadata.selectable === false);
}

export function catalogCounts(plugins) {
  return plugins.reduce((counts, plugin) => {
    counts.all += 1;
    if (isRetiredPlugin(plugin)) counts.retired += 1;
    else if (isInteractionPlugin(plugin)) counts.interaction += 1;
    else counts.builtin += 1;
    return counts;
  }, { all: 0, builtin: 0, interaction: 0, retired: 0 });
}

export function pluginActivation(plugin) {
  if (isRetiredPlugin(plugin)) return '已退役，不再注册或执行；历史目录仅供核对。';
  const mode = plugin && plugin.catalogMetadata && plugin.catalogMetadata.activationMode;
  const configured = pluginDefault(plugin, 'activation_hint');
  if (configured) return configured + '（目录声明；本轮可用性由运行时确认）';
  if (mode === 'runtime' || pluginBoolean(plugin, 'runtime_builtin')) {
    return '由运行时按模型能力与会话状态提供，无需角色勾选；目录不表示本轮可用。';
  }
  if (mode === 'binding' || pluginBoolean(plugin, 'catalog_only')) return '通过对应的专用配置绑定；不在角色插件列表中勾选。';
  if (mode === 'intent_allowlist') return '由服务端 Intent functions 白名单配置启用。';
  return '在角色配置的功能管理中勾选并保存；最终可用范围由运行时校验。';
}

export function pluginInjection(plugin) {
  if (isRetiredPlugin(plugin)) return '不注入';
  const mode = plugin && plugin.catalogMetadata && plugin.catalogMetadata.injectionMode;
  const labels = {
    tool_schema: '调用前：工具 schema 中的调用说明；实际工具结果在调用后回注',
    routing_schema: '调用前：模型路由 schema 中的调用说明',
    on_demand_activity: '调用前：活动入口说明；模型启动后：注入当前活动规则，结束后停止',
    tool_followup: '调用前：工具调用说明；调用后：玩法规则与开场要求',
  };
  return labels[mode] || '后台尚未声明注入阶段；实际请求以运行时组装为准';
}

export function pluginModelPaths(plugin) {
  if (isRetiredPlugin(plugin)) return '不支持';
  const paths = plugin && plugin.catalogMetadata && plugin.catalogMetadata.modelPaths;
  const labels = {
    function_call: '传统 Function Call', traditional_function_call: '传统 Function Call',
    intent_llm: '传统 Intent LLM', realtime: '支持工具协议的 Realtime',
    traditional: '传统文本对话',
    qwen_realtime: 'Qwen Realtime', doubao_duplex: '豆包 Duplex',
  };
  return Array.isArray(paths) && paths.length
    ? paths.map(path => labels[path] || path).join('、')
    : '未声明；取决于模型协议和运行时能力';
}

export function pluginBusinessFields(plugin) {
  const shared = SHARED_FIELDS[plugin && plugin.providerCode] || [];
  return pluginFields(plugin).filter(field => !CATALOG_FIELDS.has(field.key)
    && !RULE_FIELDS.has(field.key) && !shared.includes(field.key));
}

export function sanitizePluginParams(plugin, params) {
  const result = { ...(params || {}) };
  // Preserve historical activities that used the role description as their
  // only rule before removing the ineffective role-level call description.
  if (!Object.prototype.hasOwnProperty.call(result, 'instructions')
      && !pluginFields(plugin).some(field => field.key === 'instructions' && field.default !== undefined)
      && (isInteractionPlugin(plugin) || result.custom_plugin === true)
      && typeof result.description === 'string' && result.description.trim()) {
    result.instructions = result.description;
  }
  CATALOG_FIELDS.forEach(key => delete result[key]);
  (SHARED_FIELDS[plugin && plugin.providerCode] || []).forEach(key => delete result[key]);
  return result;
}

export function newPluginParams(plugin) {
  return pluginBusinessFields(plugin).reduce((params, field) => {
    if (field.default !== undefined) params[field.key] = field.default;
    return params;
  }, {});
}

export function ruleUsesOverride(plugin, key) {
  return Object.prototype.hasOwnProperty.call((plugin && plugin.params) || {}, key);
}

export function pluginRuleValue(plugin, key) {
  return ruleUsesOverride(plugin, key) ? plugin.params[key] : pluginDefault(plugin, key);
}
