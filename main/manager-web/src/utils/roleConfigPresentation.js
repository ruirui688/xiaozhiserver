export const ROLE_CONFIG_PANELS = [
  {
    key: "basic",
    label: "基础设定",
    icon: "el-icon-user",
    title: "基础设定",
    description: "设置角色名称、身份、表达方式和系统提示词。",
  },
  {
    key: "voice",
    label: "语音链路",
    icon: "el-icon-microphone",
    title: "语音链路",
    description: "配置语音检测、识别、热词、合成模型和最终音色。",
  },
  {
    key: "intelligence",
    label: "模型与能力",
    icon: "el-icon-cpu",
    title: "模型与能力",
    description: "选择思考与视觉模型，并为角色分配功能和知识库。",
  },
  {
    key: "memory",
    label: "记忆与记录",
    icon: "el-icon-notebook-2",
    title: "记忆与记录",
    description: "选择长期记忆方式，并决定聊天记录是否保存语音。",
  },
  {
    key: "integrations",
    label: "外部集成",
    icon: "el-icon-connection",
    title: "外部集成",
    description: "管理上下文来源、固定播报技能和飞书协作配置。",
  },
];

export function rolePanelMeta(key) {
  return ROLE_CONFIG_PANELS.find((panel) => panel.key === key) || ROLE_CONFIG_PANELS[0];
}

export function roleMemoryModeHint(modelId) {
  if (!modelId || modelId === "Memory_nomem") {
    return "当前不生成或注入长期记忆，聊天记录上报也会关闭。";
  }
  if (modelId === "Memory_managed_memory") {
    return "主动记忆会在会话结束后自动整理；这里无需填写摘要，可在长期记忆页面查看和维护。";
  }
  if (modelId === "Memory_mem_report_only") {
    return "仅保存聊天记录，不生成可注入对话的长期记忆。";
  }
  if (modelId === "Memory_mem_local_short") {
    return "这是旧的本地短摘要兼容模式，可在下方编辑摘要内容。";
  }
  return "记忆由当前所选服务管理，聊天记录保存方式可在此独立选择。";
}
