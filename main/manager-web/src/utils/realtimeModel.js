const REALTIME_ADAPTERS = new Set([
  "qwen_omni_realtime",
  "qwen-omni-realtime",
  "doubao_realtime",
  "doubao-realtime",
]);

function normalize(value) {
  return String(value || "").trim().toLowerCase();
}

function modelSources(model = {}) {
  const value = model && typeof model === "object" ? model : {};
  const raw = value.raw || {};
  return [
    value,
    raw,
    value.params,
    raw.params,
    value.typedConfig,
    raw.typedConfig,
    value.typed_config,
    raw.typed_config,
    value.configJson,
    raw.configJson,
    value.runtimeConfig,
    raw.runtimeConfig,
  ].filter((item) => item && typeof item === "object");
}

export function getConversationMode(model = {}) {
  for (const source of modelSources(model)) {
    const mode = normalize(source.conversation_mode || source.conversationMode);
    if (mode) return mode;
  }
  return "";
}

export function getRealtimeAdapter(model = {}) {
  for (const source of modelSources(model)) {
    const adapter = normalize(
      source.adapterCode
      || source.providerCode
      || source.adapter_code
      || source.type
    );
    if (REALTIME_ADAPTERS.has(adapter)) return adapter;
  }
  return "";
}

export function isRealtimeModel(model = {}) {
  if (modelSources(model).some((source) => source.realtime === true)) return true;
  if (getConversationMode(model) === "realtime") return true;
  if (getRealtimeAdapter(model)) return true;

  return modelSources(model).some((source) => {
    const templateCode = normalize(source.templateCode || source.template_code);
    return templateCode.includes("omni_realtime") || templateCode.includes("doubao_realtime");
  });
}

export function isDoubaoRealtimeModel(model = {}) {
  return getRealtimeAdapter(model).startsWith("doubao");
}

export function getConversationModeBadge(model = {}) {
  return isRealtimeModel(model) ? "实时多模态" : "传统拼接";
}
