const FORM_PATHS = [
  "agentCode",
  "agentName",
  "ttsVoiceId",
  "ttsVolume",
  "ttsRate",
  "ttsPitch",
  "chatHistoryConf",
  "systemPrompt",
  "summaryMemory",
  "asrHotwordId",
  "asrHotwordIds",
  "langCode",
  "language",
  "sort",
  "model.ttsModelId",
  "model.vadModelId",
  "model.asrModelId",
  "model.llmModelId",
  "model.slmModelId",
  "model.vllmModelId",
  "model.memModelId",
  "model.intentModelId",
];

export function cloneRoleValue(value) {
  if (value === undefined) return undefined;
  return JSON.parse(JSON.stringify(value));
}

export function emptyRoleForm() {
  return {
    agentCode: "",
    agentName: "",
    ttsVoiceId: "",
    ttsVolume: null,
    ttsRate: null,
    ttsPitch: null,
    chatHistoryConf: 0,
    systemPrompt: "",
    summaryMemory: "",
    asrHotwordId: "",
    asrHotwordIds: [],
    langCode: "",
    language: "",
    sort: "",
    model: {
      ttsModelId: "",
      vadModelId: "",
      asrModelId: "",
      llmModelId: "",
      slmModelId: "",
      vllmModelId: "",
      memModelId: "",
      intentModelId: "",
    },
  };
}

function valueOrFallback(value, fallback) {
  return value === undefined || value === null ? fallback : value;
}

export function buildRoleForm(initial) {
  if (!initial) return emptyRoleForm();
  const sourceModel = initial.model || initial;
  const hotwordSelection = normalizeAsrHotwordConfig(
    initial.asrHotwordIds,
    initial.asrHotwordId
  );
  return {
    agentCode: valueOrFallback(initial.agentCode, ""),
    agentName: valueOrFallback(initial.agentName, ""),
    ttsVoiceId: valueOrFallback(initial.ttsVoiceId, ""),
    ttsVolume: valueOrFallback(initial.ttsVolume, null),
    ttsRate: valueOrFallback(initial.ttsRate, null),
    ttsPitch: valueOrFallback(initial.ttsPitch, null),
    chatHistoryConf: valueOrFallback(initial.chatHistoryConf, 0),
    systemPrompt: valueOrFallback(initial.systemPrompt, ""),
    summaryMemory: valueOrFallback(initial.summaryMemory, ""),
    asrHotwordId: hotwordSelection.activeId,
    asrHotwordIds: hotwordSelection.ids,
    langCode: valueOrFallback(initial.langCode, ""),
    language: valueOrFallback(initial.language, ""),
    sort: valueOrFallback(initial.sort, ""),
    model: {
      ttsModelId: valueOrFallback(sourceModel.ttsModelId, ""),
      vadModelId: valueOrFallback(sourceModel.vadModelId, ""),
      asrModelId: valueOrFallback(sourceModel.asrModelId, ""),
      llmModelId: valueOrFallback(sourceModel.llmModelId, ""),
      slmModelId: valueOrFallback(sourceModel.slmModelId, ""),
      vllmModelId: valueOrFallback(sourceModel.vllmModelId, ""),
      memModelId: valueOrFallback(sourceModel.memModelId, ""),
      intentModelId: valueOrFallback(sourceModel.intentModelId, ""),
    },
  };
}

function getPath(target, path) {
  return path.split(".").reduce((value, key) => value?.[key], target);
}

function setPath(target, path, value) {
  const keys = path.split(".");
  const lastKey = keys.pop();
  const parent = keys.reduce((current, key) => current[key], target);
  parent[lastKey] = cloneRoleValue(value);
}

export function changedRolePaths(previous, current) {
  return FORM_PATHS.filter((path) => !sameRoleValue(getPath(previous, path), getPath(current, path)));
}

function sameRoleValue(left, right) {
  if (left === right) return true;
  if (Array.isArray(left) && Array.isArray(right)) {
    return left.length === right.length
      && left.every((value, index) => sameRoleValue(value, right[index]));
  }
  return false;
}

export const MAX_ASR_HOTWORD_IDS = 10;

export function normalizeAsrHotwordConfig(candidateIds, activeId) {
  const sourceIds = Array.isArray(candidateIds) ? candidateIds : [];
  const normalizedIds = [];
  sourceIds.forEach((value) => {
    const id = String(value ?? "").trim();
    if (id && !normalizedIds.includes(id) && normalizedIds.length < MAX_ASR_HOTWORD_IDS) {
      normalizedIds.push(id);
    }
  });

  let normalizedActiveId = String(activeId ?? "").trim();
  if (normalizedActiveId && !normalizedIds.includes(normalizedActiveId)) {
    normalizedIds.unshift(normalizedActiveId);
    if (normalizedIds.length > MAX_ASR_HOTWORD_IDS) normalizedIds.pop();
  }
  if (!normalizedActiveId || !normalizedIds.includes(normalizedActiveId)) {
    normalizedActiveId = normalizedIds[0] || "";
  }

  return { ids: normalizedIds, activeId: normalizedActiveId };
}

export function mergeRoleDraft(serverForm, currentDraft, dirtyPaths) {
  const merged = buildRoleForm(serverForm);
  (dirtyPaths || []).forEach((path) => setPath(merged, path, getPath(currentDraft, path)));
  return merged;
}

export function normalizeMappingParams(params, fallback = {}) {
  if (params === null || params === undefined || params === "") return { ...fallback };
  if (typeof params === "string") {
    try {
      const parsed = JSON.parse(params);
      return parsed && typeof parsed === "object" && !Array.isArray(parsed)
        ? parsed
        : { ...fallback };
    } catch (error) {
      return { ...fallback };
    }
  }
  return typeof params === "object" && !Array.isArray(params)
    ? { ...params }
    : { ...fallback };
}

export function splitCapabilityMappings(savedMappings, functions, knowledgeBases, defaultQueryPolicy) {
  const functionMap = new Map((functions || []).map((item) => [item.id, item]));
  const knowledgeMap = new Map((knowledgeBases || []).map((item) => [item.id, item]));
  const result = { functions: [], knowledgeBases: [] };

  (savedMappings || []).forEach((mapping) => {
    const pluginId = mapping.pluginId || mapping.id;
    if (!pluginId) return;
    const rawParams = mapping.paramInfo ?? mapping.params;
    const knowledge = knowledgeMap.get(pluginId);
    if (knowledge) {
      const params = normalizeMappingParams(rawParams);
      result.knowledgeBases.push({
        id: knowledge.id,
        name: knowledge.name,
        description: knowledge.description,
        documentCount: knowledge.documentCount || 0,
        queryPolicy: params.queryPolicy || defaultQueryPolicy,
      });
      return;
    }
    const metadata = functionMap.get(pluginId);
    const params = normalizeMappingParams(rawParams, metadata?.params || {});
    result.functions.push({
      id: pluginId,
      name: metadata?.name || mapping.name || pluginId,
      providerCode: metadata?.providerCode,
      params,
      fieldsMeta: metadata?.fieldsMeta || [],
    });
  });

  return result;
}
