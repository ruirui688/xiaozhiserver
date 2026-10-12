import { getServiceUrl } from '@/apis/api';
import RequestService from '@/apis/httpRequest';
import { isSelectablePlugin } from '@/utils/pluginCatalog';

const SETUP_TARGETS = new Set(['functions', 'knowledge', 'feishu', 'voice', 'memory']);

export function pluginSetupQuery(query = {}) {
  if (!query || typeof query.agentId !== 'string' || !query.agentId.trim()
      || !SETUP_TARGETS.has(query.setup)) return null;
  return {
    agentId: query.agentId,
    setup: query.setup,
    plugin: typeof query.plugin === 'string' ? query.plugin.slice(0, 128) : '',
  };
}

export function setupWorkspaceId() {
  return typeof localStorage === 'undefined' ? '' : localStorage.getItem('currentWorkspaceId') || '';
}

// Use the existing authenticated request boundary, with explicit failure handling.
export function readSetupWorkspace() {
  return new Promise((resolve, reject) => {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/workspace/current`)
      .method('GET')
      .success(({ data }) => {
        if (data?.code === 0 && data.data) resolve(data.data);
        else reject(new Error('无法确认当前工作空间权限，请重试。'));
      })
      .fail(reject)
      .networkFail(reject)
      .send();
  });
}

export default {
  data() {
    return {
      pluginSetupMessage: '',
      pluginSetupMessageType: 'info',
      focusedPluginCode: '',
      pluginSetupReadyAgent: '',
      pluginSetupAppliedKey: '',
      pluginSetupSequence: 0,
      pluginVoiceNavigationPending: false,
    };
  },
  watch: {
    '$route.query': {
      deep: true,
      handler() { this.applyPluginSetupNavigation(); },
    },
    selectedLlmModel() {
      if (this.pluginVoiceNavigationPending) this.positionPluginVoiceSettings();
    },
    activePanel(panel) {
      if (this.pluginVoiceNavigationPending && panel !== 'voice') this.pluginVoiceNavigationPending = false;
    },
  },
  beforeDestroy() {
    this.pluginSetupSequence += 1;
  },
  methods: {
    completePluginSetupHydration(agentId) {
      this.pluginSetupReadyAgent = agentId;
      this.applyPluginSetupNavigation();
    },
    positionPluginVoiceSettings() {
      this.activePanel = this.realtimeSessionVoiceSupported ? 'intelligence' : 'voice';
      this.pluginVoiceNavigationPending = !(this.modelOptions.LLM || []).length;
    },
    async applyPluginSetupNavigation() {
      const query = pluginSetupQuery(this.$route?.query);
      const workspaceId = setupWorkspaceId();
      const key = query ? JSON.stringify([query, workspaceId]) : '';
      if (key === this.pluginSetupAppliedKey) return;
      const sequence = ++this.pluginSetupSequence;
      if (this.pluginSetupAppliedKey) {
        this.showFunctionDialog = false;
        this.showKnowledgeBaseDialog = false;
        this.showFeishuDialog = false;
      }
      this.pluginSetupMessage = '';
      this.focusedPluginCode = '';
      this.pluginVoiceNavigationPending = false;
      this.pluginSetupAppliedKey = '';
      if (!query || !this.capabilityMappingsReady || query.agentId !== this.pluginSetupReadyAgent) return;
      this.pluginSetupAppliedKey = key;
      const stillCurrent = () => sequence === this.pluginSetupSequence
        && query.agentId === this.pluginSetupReadyAgent
        && key === JSON.stringify([pluginSetupQuery(this.$route?.query), workspaceId])
        && workspaceId === setupWorkspaceId();
      this.pluginSetupMessage = '正在确认配置权限…';
      this.pluginSetupMessageType = 'info';
      try {
        const workspace = await readSetupWorkspace();
        if (!stillCurrent()) return;
        if (workspace.canWrite !== true) {
          this.pluginSetupMessage = '当前工作空间为只读，请联系管理员开通编辑权限。';
          this.pluginSetupMessageType = 'warning';
          return;
        }
        // Parent mapping props settle on the next Vue tick after hydration.
        await this.$nextTick();
        if (!stillCurrent()) return;
        this.showFunctionDialog = false;
        this.showKnowledgeBaseDialog = false;
        this.showFeishuDialog = false;
        this.focusedPluginCode = '';
        this.pluginVoiceNavigationPending = false;
        if (query.setup === 'functions') {
          this.activePanel = 'intelligence';
          const plugin = this.allFunctions.find(item => item.providerCode === query.plugin);
          if (query.plugin && (!plugin || !isSelectablePlugin(plugin))) {
            this.pluginSetupMessage = '该功能已删除、停用，或需要通过专用入口配置，请返回功能库重新选择。';
            this.pluginSetupMessageType = 'warning';
            return;
          }
          this.focusedPluginCode = query.plugin;
          this.showFunctionDialog = true;
          this.pluginSetupMessage = '勾选需要的功能并保存功能选择，最后点击页面右上角“保存配置”。';
          if ((!this.form.model.intentModelId || this.form.model.intentModelId === 'Intent_nointent')
              && !this.selectedLlmModel?.raw?.nativeFunctionCalling) {
            this.pluginSetupMessage += ' 若所选模型不提供原生工具调用，请在“模型与能力”中启用工具调用。';
          }
        } else if (query.setup === 'knowledge') {
          this.activePanel = 'intelligence';
          this.showKnowledgeBaseDialog = true;
          this.pluginSetupMessage = '选择要绑定的知识库，确认后点击页面右上角“保存配置”；无需再勾选知识查询插件。';
        } else if (query.setup === 'feishu') {
          this.activePanel = 'integrations';
          this.showFeishuDialog = true;
          this.pluginSetupMessage = '在飞书配置中完成应用绑定、目标授权和动作选择，并在弹窗中保存。';
        } else if (query.setup === 'voice') {
          this.positionPluginVoiceSettings();
          this.pluginSetupMessage = '传统模型在“语音链路”选择角色音色；实时模型在“模型与能力”选择实时音色，最后保存配置。';
        } else if (query.setup === 'memory') {
          this.activePanel = 'memory';
          this.pluginSetupMessage = '选择记忆模型并保存配置；长期记忆由记忆模块管理，无需勾选插件。';
        }
      } catch (_) {
        if (!stillCurrent()) return;
        this.pluginSetupMessage = '配置权限加载失败，请刷新页面重试。';
        this.pluginSetupMessageType = 'error';
      }
    },
  },
};
