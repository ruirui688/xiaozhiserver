<template>
  <div class="welcome">
    <HeaderBar />

    <div class="operation-bar">
      <h2 class="page-title">{{ $t('templateQuickConfig.title') }}</h2>
    </div>

    <div class="main-wrapper">
      <div class="content-panel">
        <div class="content-area">
          <el-card class="config-card" shadow="never">
              <div class="config-header">
              <!-- 使用角色配置页面相同的彩色图标效果 -->
              <div class="header-icon">
                <img loading="lazy" src="@/assets/home/setting-user.png" alt="">
              </div>
              <span class="header-title">{{ form.agentName }}</span>
              <div class="header-actions">
                <el-button type="primary" class="save-btn" @click="saveConfig">{{ $t('templateQuickConfig.saveConfig') }}</el-button>
                <el-button class="reset-btn" @click="resetConfig">{{ $t('templateQuickConfig.resetConfig') }}</el-button>
                <button class="custom-close-btn" @click="goToHome">
                  ×
                </button>
              </div>
            </div>
            <div class="divider"></div>

            <el-form ref="form" :model="form" label-width="72px" class="full-height-form">
              <el-form-item label="模板名称" prop="templateName" class="nickname-item">
                <el-input v-model="form.templateName" placeholder="用于模板中心展示" class="form-input" />
              </el-form-item>
              <!-- 助手昵称 -->
              <el-form-item :label="$t('templateQuickConfig.agentSettings.agentName')" prop="agentName" class="nickname-item">
                <el-input
                  v-model="form.agentName"
                  :placeholder="$t('templateQuickConfig.agentSettings.agentNamePlaceholder')"
                  :validate-event="false"
                  class="form-input"
                />
              </el-form-item>

              <div class="template-config-grid">
                <el-form-item v-for="item in modelFields" :key="item.key" :label="item.label">
                  <el-select v-model="form.model[item.key]" clearable filterable placeholder="未配置"
                    @change="item.key === 'ttsModelId' && handleTtsModelChanged($event)">
                    <el-option v-for="option in modelOptions[item.type] || []" :key="option.id"
                      :label="option.modelName || option.id" :value="option.id" />
                  </el-select>
                </el-form-item>
                <el-form-item label="TTS 音色">
                  <el-select v-model="form.ttsVoiceId" clearable filterable :disabled="!form.model.ttsModelId"
                    placeholder="请先选择 TTS 模型">
                    <el-option v-for="voice in voiceOptions" :key="voice.id"
                      :label="voice.name || voice.id" :value="voice.id" />
                  </el-select>
                </el-form-item>
                <el-form-item label="交互语种">
                  <el-input v-model="form.language" placeholder="例如：中文" />
                </el-form-item>
              </div>

              <el-form-item label="官方知识库" class="knowledge-item">
                <el-select v-model="selectedKnowledgeIds" multiple clearable filterable
                  placeholder="选择允许所有用户使用的官方知识库">
                  <el-option v-for="knowledge in knowledgeOptions" :key="knowledge.id"
                    :label="knowledge.name || knowledge.id" :value="knowledge.id" />
                </el-select>
                <div class="field-help">官方模板只能跨工作空间绑定官方知识库；保存时会重新校验模型和音色。</div>
              </el-form-item>
              
              <!-- 角色介绍 -->
              <el-form-item :label="$t('templateQuickConfig.agentSettings.systemPrompt')" prop="systemPrompt" class="description-item">
                <el-input
                  v-model="form.systemPrompt"
                  type="textarea"
                  :placeholder="$t('templateQuickConfig.agentSettings.systemPromptPlaceholder')"
                  :validate-event="false"
                  show-word-limit
                  maxlength="2000"
                />
              </el-form-item>
            </el-form>
          </el-card>
        </div>
      </div>
    </div>
    <el-footer>
      <version-footer />
    </el-footer>
  </div>
</template>

<script>
import HeaderBar from "@/components/HeaderBar.vue";
import Api from '@/apis/api';
import VersionFooter from "@/components/VersionFooter.vue";

// 默认模型配置常量
const DEFAULT_MODEL_CONFIG = {
  ttsModelId: "",
  vadModelId: "",
  asrModelId: "",
  llmModelId: "",
  vllmModelId: "",
  memModelId: "",
  intentModelId: ""
};

export default {
  name: 'TemplateQuickConfig',
  components: { HeaderBar, VersionFooter },
  data() {
    return {
      form: {
        templateName: "",
        agentCode: "小智",
        agentName: "",
        systemPrompt: "",
        language: "",
        ttsVoiceId: "",
        sort: 0,
        model: { ...DEFAULT_MODEL_CONFIG }
      },
      modelFields: [
        { key: "asrModelId", type: "ASR", label: "语音识别" },
        { key: "llmModelId", type: "LLM", label: "语言模型" },
        { key: "memModelId", type: "Memory", label: "长期记忆" },
        { key: "intentModelId", type: "Intent", label: "意图识别" },
        { key: "ttsModelId", type: "TTS", label: "语音合成" }
      ],
      modelOptions: {},
      voiceOptions: [],
      knowledgeOptions: [],
      selectedKnowledgeIds: [],
      originalKnowledgeIds: [],
      snapshotConfig: {},
      originalSnapshotConfig: {},
      templateId: '',
      originalForm: null
    };
  },
  methods: {
    // 返回模板管理页面
    goToHome() {
      this.$router.push('/agent-template-management');
    },
    
    // 保存配置
    saveConfig() {
      if (!this.form.templateName.trim() || !this.form.agentName.trim()) {
        this.$message.warning("请填写模板名称和智能体名称");
        return;
      }
      const configData = this.prepareConfigData();
      
      if (this.templateId) {
        this.updateExistingTemplate(configData);
      } else {
        this.createNewTemplate(configData);
      }
    },
    
    // 准备配置数据
    prepareConfigData() {
      const knowledgeIds = new Set([
        ...this.knowledgeOptions.map(item => item.id),
        ...this.originalKnowledgeIds
      ]);
      const preservedFunctions = Array.isArray(this.snapshotConfig.functions)
        ? this.snapshotConfig.functions.filter(item => item && !knowledgeIds.has(item.pluginId))
        : [];
      const config = {
        ...this.snapshotConfig,
        agentCode: this.form.agentCode,
        agentName: this.form.agentName,
        systemPrompt: this.form.systemPrompt,
        language: this.form.language,
        ...this.form.model,
        ttsVoiceId: this.form.ttsVoiceId,
        functions: [
          ...preservedFunctions,
          ...this.selectedKnowledgeIds.map(id => ({
            pluginId: id,
            paramInfo: { queryPolicy: "仅依据用户当前问题和已明确上下文检索，不补充未提及对象。" }
          }))
        ]
      };
      return {
        id: this.templateId || '',
        templateName: this.form.templateName,
        agentCode: this.form.agentCode,
        agentName: this.form.agentName,
        systemPrompt: this.form.systemPrompt,
        language: this.form.language,
        ttsVoiceId: this.form.ttsVoiceId,
        sort: this.form.sort,
        ...this.form.model,
        snapshotJson: { schemaVersion: 2, config }
      };
    },
    
    // 更新现有模板
    updateExistingTemplate(configData) {
      Api.agent.updateAgentTemplate(configData, (res) => {
        if (res && res.data && res.data.code === 0) {
          this.$message.success({ 
            message: this.$t('templateQuickConfig.saveSuccess'), 
            showClose: true 
          });
          this.snapshotConfig = JSON.parse(JSON.stringify(configData.snapshotJson.config));
          this.originalForm = JSON.parse(JSON.stringify(this.form));
          this.originalKnowledgeIds = [...this.selectedKnowledgeIds];
          this.originalSnapshotConfig = JSON.parse(JSON.stringify(this.snapshotConfig));
        } else {
          this.$message.error({ 
            message: res?.data?.msg || this.$t('templateQuickConfig.saveFailed'), 
            showClose: true 
          });
        }
      });
    },
    
    // 创建新模板
    createNewTemplate(configData) {
      Api.agent.addAgentTemplate(configData, (res) => {
        if (res && res.data && res.data.code === 0) {
          this.$message.success({ 
            message: this.$t('templateQuickConfig.saveSuccess'), 
            showClose: true 
          });
          this.goToHome();
        } else {
          this.$message.error({ 
            message: res?.data?.msg || this.$t('templateQuickConfig.saveFailed'), 
            showClose: true 
          });
        }
      });
    },
    
    // 重置配置
    resetConfig() {
      this.$confirm(
        this.$t('templateQuickConfig.confirmReset'), 
        this.$t('common.tip'), 
        {
          confirmButtonText: this.$t('common.confirm'),
          cancelButtonText: this.$t('common.cancel'),
          type: 'warning'
        }
      ).then(() => {
        if (this.originalForm) {
          this.form = JSON.parse(JSON.stringify(this.originalForm));
          this.selectedKnowledgeIds = [...this.originalKnowledgeIds];
          this.snapshotConfig = JSON.parse(JSON.stringify(this.originalSnapshotConfig));
          this.loadVoices(this.form.model.ttsModelId);
        }
        this.$message.success({ 
          message: this.$t('templateQuickConfig.resetSuccess'), 
          showClose: true 
        });
      }).catch(() => {});
    },
    
    // 根据ID获取模板
    fetchTemplateById(templateId) {
      Api.agent.getAgentTemplateById(templateId, (res) => {
        if (res && res.data && res.data.code === 0 && res.data.data) {
          const template = res.data.data;
          this.applyTemplateData(template);
          this.templateId = templateId;
          this.originalForm = JSON.parse(JSON.stringify(this.form));
          this.originalKnowledgeIds = [...this.selectedKnowledgeIds];
          this.originalSnapshotConfig = JSON.parse(JSON.stringify(this.snapshotConfig));
        } else {
          this.$message.error(res?.data?.msg || this.$t('templateQuickConfig.templateNotFound'));
        }
      });
    },
    
    // 应用模板数据
    applyTemplateData(templateData) {
      const snapshot = templateData.snapshotJson && templateData.snapshotJson.config
        ? templateData.snapshotJson.config
        : templateData;
      this.snapshotConfig = { ...snapshot };
      this.form = {
        ...this.form,
        templateName: templateData.templateName || templateData.agentName || this.form.templateName,
        agentName: snapshot.agentName || templateData.agentName || this.form.agentName,
        agentCode: snapshot.agentCode || templateData.agentCode || this.form.agentCode,
        systemPrompt: snapshot.systemPrompt || templateData.systemPrompt || this.form.systemPrompt,
        language: this.repairMojibake(snapshot.language || templateData.language || ""),
        ttsVoiceId: snapshot.ttsVoiceId || templateData.ttsVoiceId || "",
        sort: templateData.sort || this.form.sort,
        model: {
          ttsModelId: this.templateValue(snapshot, "ttsModelId", templateData.ttsModelId || ""),
          vadModelId: this.templateValue(snapshot, "vadModelId", templateData.vadModelId || ""),
          asrModelId: this.templateValue(snapshot, "asrModelId", templateData.asrModelId || ""),
          llmModelId: this.templateValue(snapshot, "llmModelId", templateData.llmModelId || ""),
          vllmModelId: this.templateValue(snapshot, "vllmModelId", templateData.vllmModelId || ""),
          memModelId: this.templateValue(snapshot, "memModelId", templateData.memModelId || ""),
          intentModelId: this.templateValue(snapshot, "intentModelId", templateData.intentModelId || "")
        }
      };
      this.syncKnowledgeSelection();
      this.loadVoices(this.form.model.ttsModelId);
    },
    syncKnowledgeSelection() {
      const availableKnowledgeIds = new Set(this.knowledgeOptions.map(item => item.id));
      this.selectedKnowledgeIds = (this.snapshotConfig.functions || [])
        .map(item => item && item.pluginId)
        .filter(id => id && availableKnowledgeIds.has(id));
    },
    repairMojibake(value) {
      if (!value || !/[ÃÂäåæ]/.test(value)) return value;
      try {
        const windows1252 = {
          "€": 0x80, "‚": 0x82, "ƒ": 0x83, "„": 0x84, "…": 0x85, "†": 0x86,
          "‡": 0x87, "ˆ": 0x88, "‰": 0x89, "Š": 0x8a, "‹": 0x8b, "Œ": 0x8c,
          "Ž": 0x8e, "‘": 0x91, "’": 0x92, "“": 0x93, "”": 0x94, "•": 0x95,
          "–": 0x96, "—": 0x97, "˜": 0x98, "™": 0x99, "š": 0x9a, "›": 0x9b,
          "œ": 0x9c, "ž": 0x9e, "Ÿ": 0x9f
        };
        const bytes = Uint8Array.from(value, character => windows1252[character] ?? character.charCodeAt(0));
        const repaired = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
        const containsCjk = text => /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]/.test(text);
        return containsCjk(repaired) && !containsCjk(value) ? repaired : value;
      } catch (error) {
        return value;
      }
    },
    handleTtsModelChanged(modelId) {
      this.form.ttsVoiceId = "";
      this.loadVoices(modelId);
    },
    loadVoices(modelId) {
      if (!modelId) {
        this.voiceOptions = [];
        return;
      }
      Api.model.getModelVoices(modelId, "", ({ data }) => {
        this.voiceOptions = data && data.code === 0 && Array.isArray(data.data) ? data.data : [];
      });
    },
    loadModelOptions() {
      this.modelFields.forEach(field => {
        Api.model.getModelNames(field.type, "", ({ data }) => {
          this.$set(this.modelOptions, field.type,
            data && data.code === 0 && Array.isArray(data.data) ? data.data : []);
        });
      });
    },
    loadOfficialKnowledge() {
      Api.knowledgeBase.getKnowledgeBaseList(
        { page: 1, page_size: 1000, name: "", scope: "OFFICIAL" },
        ({ data }) => {
          this.knowledgeOptions = data && data.code === 0 && data.data
            ? (data.data.list || [])
            : [];
          this.syncKnowledgeSelection();
          if (this.templateId) {
            this.originalKnowledgeIds = [...this.selectedKnowledgeIds];
          }
        }
      );
    },
    templateValue(templateData, key, fallback) {
      return Object.prototype.hasOwnProperty.call(templateData, key)
        ? (templateData[key] || "")
        : fallback;
    },
    
    // 设置默认模板值
    setDefaultTemplateValues() {
      this.form = {
        ...this.form,
        templateName: this.$t('templateQuickConfig.newTemplate'),
        agentName: this.$t('templateQuickConfig.newTemplate'),
        agentCode: '小智',
        systemPrompt: '',
        sort: 1
      };
      
      this.originalForm = JSON.parse(JSON.stringify(this.form));
      this.originalKnowledgeIds = [];
      this.originalSnapshotConfig = {};
    },
    
    // 获取模板列表并设置排序号
    fetchTemplateListForSort() {
      Api.agent.getAgentTemplate((res) => {
        if (res && res.data && res.data.code === 0) {
          const templateList = res.data.data || [];
          if (templateList.length > 0) {
            const maxSort = Math.max(...templateList.map(t => t.sort || 0));
            this.form.sort = maxSort + 1;
          } else {
            this.form.sort = 1;
          }
        } else {
          this.form.sort = 1;
        }
        
        this.originalForm = JSON.parse(JSON.stringify(this.form));
      });
    }
  },
  
  // 组件挂载时执行初始化
  mounted() {
    this.loadModelOptions();
    this.loadOfficialKnowledge();
    const templateId = this.$route.query.templateId;
    
    if (templateId) {
      // 编辑模式：加载现有模板
      this.fetchTemplateById(templateId);
    } else {
      // 新建模式：设置默认值并获取排序号
      this.form.templateName = this.$t('templateQuickConfig.newTemplate');
      this.form.agentName = this.$t('templateQuickConfig.newTemplate');
      this.fetchTemplateListForSort();
    }
  }
};
</script>

<style scoped>
.welcome {
  min-width: 900px;
  height: 100vh;
  display: flex;
  position: relative;
  flex-direction: column;
  background: linear-gradient(to bottom right, #dce8ff, #e4eeff, #e6cbfd);
  background-size: cover;
  -webkit-background-size: cover;
  -o-background-size: cover;
  overflow: hidden;
}

.template-config-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 22px;
}

.template-config-grid .el-select,
.knowledge-item .el-select {
  width: 100%;
}

.field-help {
  margin-top: 6px;
  color: #9098aa;
  font-size: 12px;
  line-height: 1.5;
}

.operation-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
}

.page-title {
  font-size: 24px;
  margin: 0;
  color: #2c3e50;
}

.main-wrapper {
  height: calc(100vh - 63px - 35px - 60px);
  margin: 0 22px;
  border-radius: 15px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  position: relative;
  background: rgba(237, 242, 255, 0.5);
  display: flex;
  flex-direction: column;
  padding: 0 !important;
}

.content-panel {
  flex: 1;
  display: flex;
  overflow: hidden;
  height: 100%;
  border-radius: 15px;
  background: transparent;
  border: 1px solid #fff;
}

.content-area {
  flex: 1;
  height: 100%;
  overflow: auto;
  background-color: white;
  display: flex;
  flex-direction: column;
}

.config-card {
  background: white !important;
  border-radius: 15px !important;
  overflow: hidden !important;
  height: 100% !important;
  margin: 0 !important;
  border: none !important;
  box-shadow: none !important;
}

.full-height-form {
  display: flex;
  flex-direction: column;
  padding: 20px;
  gap: 20px;
  height: calc(100% - 120px);
}

.nickname-item {
  margin-bottom: 0 !important;
}

.description-item {
  margin-bottom: 0 !important;
  display: flex;
  flex-direction: column;
}

::v-deep .description-item .el-textarea {
  height: 300px;
  min-height: 200px;
  max-height: 400px;
  display: flex;
  flex-direction: column;
}

::v-deep .description-item .el-textarea__inner {
  height: 100% !important;
  min-height: 200px !important;
  max-height: 400px !important;
  resize: vertical !important;
  line-height: 1.6;
  font-size: 14px;
  padding: 10px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background-color: #fff;
  color: #303133;
}

::v-deep .el-form-item__label {
  font-size: 12px !important;
  color: #3d4566 !important;
  font-weight: 400;
  line-height: 22px;
  padding-bottom: 2px;
}

::v-deep .el-textarea .el-input__count {
  color: #909399;
  background: rgba(255, 255, 255, 0.8);
  position: absolute;
  font-size: 12px;
  right: 10px;
  bottom: 10px;
  padding: 2px 5px;
  border-radius: 3px;
}

.config-header {
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 15px;
  background: #f8f9ff;
  position: relative;
}

.header-icon {
  width: 37px;
  height: 37px;
  background: #5778ff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.header-icon img {
  width: 19px;
  height: 19px;
}

.header-title {
  font-size: 20px;
  font-weight: 600;
  color: #2c3e50;
}

.custom-close-btn {
  position: absolute;
  top: 25%;
  right: 0;
  transform: translateY(-50%);
  width: 35px;
  height: 35px;
  border-radius: 50%;
  border: 2px solid #cfcfcf;
  background: none;
  font-size: 30px;
  font-weight: lighter;
  color: #cfcfcf;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1;
  padding: 0;
  outline: none;
}

.custom-close-btn:hover {
  color: #409EFF;
  border-color: #409EFF;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.header-actions .save-btn {
  background: #5778ff;
  color: white;
  border: none;
  border-radius: 18px;
  padding: 8px 16px;
  height: 32px;
  font-size: 14px;
}

.header-actions .reset-btn {
  background: #e6ebff;
  color: #5778ff;
  border: 1px solid #adbdff;
  border-radius: 18px;
  padding: 8px 16px;
  height: 32px;
}

.header-actions .custom-close-btn {
  position: static;
  transform: none;
  width: 32px;
  height: 32px;
  margin-left: 8px;
}
</style>
