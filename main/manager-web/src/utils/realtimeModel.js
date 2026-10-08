const REALTIME_ADAPTERS = new Set([
  "qwen_omni_realtime",
  "qwen-omni-realtime",
  "doubao_duplex",
  "doubao-duplex",
  "doubao_realtime",
  "doubao-realtime",
]);

const REALTIME_TEMPLATE_ADAPTERS = new Map([
  ["llm_ali_qwen_omni_realtime", "qwen_omni_realtime"],
  ["llm_doubao_duplex", "doubao_duplex"],
  ["llm_doubao_realtime", "doubao_realtime"],
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

export function getModelAdapter(model = {}) {
  for (const source of modelSources(model)) {
    const adapter = normalize(
      source.adapterCode
      || source.providerCode
      || source.adapter_code
      || source.type
    );
    if (adapter) return adapter;
  }
  return "";
}

export function getRealtimeAdapter(model = {}) {
  const adapter = getModelAdapter(model);
  return REALTIME_ADAPTERS.has(adapter) ? adapter : "";
}

export function getRealtimeTemplateAdapter(model = {}) {
  for (const source of modelSources(model)) {
    const templateCode = normalize(source.templateCode || source.template_code);
    if (REALTIME_TEMPLATE_ADAPTERS.has(templateCode)) {
      return REALTIME_TEMPLATE_ADAPTERS.get(templateCode);
    }
  }
  return "";
}

export function isRealtimeModel(model = {}) {
  if (modelSources(model).some((source) => source.realtime === true)) return true;
  if (getConversationMode(model) === "realtime") return true;
  if (getRealtimeAdapter(model)) return true;

  return modelSources(model).some((source) => {
    const templateCode = normalize(source.templateCode || source.template_code);
    return templateCode.includes("omni_realtime") || templateCode.includes("doubao_realtime") || templateCode === "llm_doubao_duplex";
  });
}

export function isDoubaoRealtimeModel(model = {}) {
  return ["doubao_realtime", "doubao-realtime"].includes(getRealtimeAdapter(model));
}

export function hasRealtimeAdapterMismatch(model = {}) {
  const mode = getConversationMode(model);
  const adapter = getModelAdapter(model);
  const expectedAdapter = getRealtimeTemplateAdapter(model);
  if (expectedAdapter) {
    return (!!adapter && adapter !== expectedAdapter) || (!!mode && mode !== "realtime");
  }
  if (mode === "realtime") {
    return !!adapter && !REALTIME_ADAPTERS.has(adapter);
  }
  return REALTIME_ADAPTERS.has(adapter) && !!mode && mode !== "realtime";
}

export function getConversationModeBadge(model = {}) {
  return isRealtimeModel(model) ? "实时多模态" : "传统拼接";
}
