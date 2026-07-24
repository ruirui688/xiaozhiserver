<template>
  <div class="welcome">
    <HeaderBar />

    <div class="operation-bar">
      <h2 class="page-title">{{ $t("roleConfig.title") }}</h2>
    </div>

    <div class="main-wrapper">
      <div class="content-panel">
        <div class="content-area">
          <el-card class="config-card" shadow="never">
            <div class="config-header">
              <div class="header-left">
                <div class="header-icon">
                  <img loading="lazy" src="@/assets/home/setting-user.png" alt="" />
                </div>
                <span class="header-title">{{ form.agentName }}</span>
              </div>
              <div class="header-tags">
                <el-tag
                  v-for="tag in dynamicTags"
                  :key="tag.id"
                  class="custom-tag"
                  closable
                  :disable-transitions="false"
                  @close="handleClose(tag.id)">
                  {{tag.tagName}}
                </el-tag>
                <el-input
                  class="input-new-tag"
                  v-if="inputVisible"
                  v-model="inputValue"
                  ref="saveTagInput"
                  size="small"
                  maxLength="20"
                  @keyup.enter.native="handleInputConfirm"
                  @blur="handleInputConfirm"
                >
                </el-input>
                <el-button class="custom-tag-btn" v-else size="small" @click="showInput">+ {{ $t("roleConfig.addTag") }}</el-button>
              </div>
              <div class="header-actions">
                <div class="hint-text">
                  <img loading="lazy" src="@/assets/home/info.png" alt="" />
                  <span>{{ $t("roleConfig.restartNotice") }}</span>
                </div>
                <el-button
                  type="primary"
                  class="save-btn"
                  :loading="saveInProgress"
                  :disabled="agentConfigLoading || !agentConfigLoaded || saveInProgress"
                  @click="handleSave">
                  {{ $t("roleConfig.saveConfig") }}
                </el-button>
                <el-button class="reset-btn" @click="handleReset">{{
                  $t("roleConfig.reset")
                }}</el-button>
                <button class="custom-close-btn" @click="goToHome">&times;</button>
              </div>
            </div>
            <div class="divider"></div>

            <role-edit-dialog
              ref="editDialog"
              :initial-form="form"
              :models="models"
              :model-options="modelOptions"
              :llm-mode-type-map="llmModeTypeMap"
              :templates="templates"
              :voice-options="voiceOptions"
              :voice-details="voiceDetails"
              :feature-status="featureStatus"
              :current-functions="currentFunctions"
              :current-knowledge-bases="currentKnowledgeBases"
              :current-context-providers="currentContextProviders"
              :playing-voice="playingVoice"
              :current-playing-voice-id="currentPlayingVoiceId"
              :is-paused="isPaused"
              :loading-template="loadingTemplate"
              :checked-replacement-word-ids="checkedReplacementWordIds"
              @select-template="handleTemplateSelect"
              @open-context-provider-dialog="handleOpenContextProvider"
              @update:currentFunctions="val => currentFunctions = val"
              @update:currentKnowledgeBases="val => currentKnowledgeBases = val"
              @update:checkedReplacementWordIds="val => checkedReplacementWordIds = val"
              @update:currentContextProviders="val => currentContextProviders = val"
              @update:voiceOptions="val => voiceOptions = val"
              @tts-model-changed="handleTtsModelChanged"
              @form-change="handleFormChange"
            />
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
import Api from "@/apis/api";
import HeaderBar from "@/components/HeaderBar.vue";
import VersionFooter from "@/components/VersionFooter.vue";
import RoleEditDialog from "@/components/role/RoleEditDialog.vue";
import i18n from "@/i18n";
import featureManager from "@/utils/featureManager";

export default {
  name: "RoleConfigPage",
  components: { HeaderBar, VersionFooter, RoleEditDialog },
  data() {
    return {
      form: {
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
      },
      models: [
        { label: this.$t("roleConfig.vad"), key: "vadModelId", type: "VAD" },
        { label: this.$t("roleConfig.asr"), key: "asrModelId", type: "ASR" },
        { label: this.$t("roleConfig.llm"), key: "llmModelId", type: "LLM" },
        { label: this.$t("roleConfig.slm"), key: "slmModelId", type: "SLM" },
        { label: this.$t("roleConfig.vllm"), key: "vllmModelId", type: "VLLM" },
        { label: this.$t("roleConfig.intent"), key: "intentModelId", type: "Intent" },
        { label: this.$t("roleConfig.memory"), key: "memModelId", type: "Memory" },
        { label: this.$t("roleConfig.tts"), key: "ttsModelId", type: "TTS" },
      ],
      modelOptions: {},
      llmModeTypeMap: new Map(),
      templates: [],
      loadingTemplate: false,
      voiceOptions: [],
      voiceDetails: {},
      currentFunctions: [],
      currentKnowledgeBases: [],
      currentContextProviders: [],
      playingVoice: false,
      isPaused: false,
      currentPlayingVoiceId: null,
      featureStatus: {
        vad: false,
        asr: false,
      },
      dynamicTags: [],
      inputVisible: false,
      inputValue: "",
      checkedReplacementWordIds: [],
      loadingVoices: false,
      voiceRequestSeq: 0,
      agentConfigLoading: false,
      agentConfigLoaded: false,
      agentConfigRequestSeq: 0,
      saveInProgress: false,
      tagsRequestSeq: 0,
      tagsDirty: false,
    };
  },
  methods: {
    goToHome() {
      this.$router.push("/home");
    },

    // ===== Tag Management =====
    handleClose(id) {
      this.dynamicTags = this.dynamicTags.filter((item) => item.id !== id);
      this.tagsDirty = true;
    },
    showInput() {
      this.inputVisible = true;
      this.$nextTick(() => {
        this.$refs.saveTagInput.$refs.input.focus();
      });
    },
    handleInputConfirm() {
      const inputValue = this.inputValue;
      if (inputValue) {
        const tag = { id: new Date().getTime(), tagName: inputValue };
        this.dynamicTags.push(tag);
        this.tagsDirty = true;
      }
      this.inputVisible = false;
      this.inputValue = "";
    },
    getAgentTags(agentId) {
      const requestSeq = ++this.tagsRequestSeq;
      Api.agent.getAgentTags(agentId, ({ data }) => {
        if (requestSeq === this.tagsRequestSeq && data.code === 0 && !this.tagsDirty) {
          this.dynamicTags = data.data || [];
        }
      }, () => {});
    },
    handleSaveAgentTags(agentId) {
      return new Promise((resolve, reject) => {
        const tagNames = this.dynamicTags.map((tag) => tag.tagName);
        const handleFailure = (error) => reject(error || new Error("保存标签失败"));
        Api.agent.saveAgentTags(agentId, { tagNames }, ({ data }) => {
          if (data.code === 0) {
            this.tagsDirty = false;
            resolve();
          } else {
            reject(data.msg);
          }
        }, handleFailure);
      });
    },

    // ===== Data Loading =====
    async loadFeatureStatus() {
      try {
        await featureManager.waitForInitialization();
        const config = featureManager.getConfig();
        this.featureStatus.vad = config.vad || false;
        this.featureStatus.asr = config.asr || false;
      } catch (error) {
        console.error("加载功能状态失败:", error);
      }
    },
    fetchModelOptions() {
      this.models.forEach((model) => {
        if (model.type === "LLM") {
          Api.model.getLlmModelCodeList("", ({ data }) => {
            if (data.code === 0) {
              const LLMdata = data.data.map((item) => {
                const option = this.buildModelOption(item);
                this.llmModeTypeMap.set(item.id, option.type);
                return option;
              });
              this.$set(this.modelOptions, model.type, LLMdata);
            }
          });
        } else {
          Api.model.getModelNames(model.type, "", ({ data }) => {
            if (data.code === 0) {
              const options = data.data.map((item) => this.buildModelOption(item));
              this.$set(this.modelOptions, model.type, options);
            }
          });
        }
      });
    },
    buildModelOption(item = {}) {
      const title = item.modelName || item.id || "";
      const provider = [item.vendorName, item.productName].filter(Boolean).join(" / ");
      const meta = provider || item.vendorName || item.productName || "";
      return {
        value: item.id,
        label: meta ? `${title} · ${meta}` : title,
        title,
        meta,
        type: item.type || item.adapterCode || "",
        isHidden: false,
        raw: item,
      };
    },
    fetchTemplates() {
      Api.agent.getAgentTemplate(({ data }) => {
        if (data.code === 0) {
          this.templates = data.data;
        }
      });
    },
    fetchAgentConfig(agentId) {
      const requestSeq = ++this.agentConfigRequestSeq;
      this.agentConfigLoading = true;
      this.agentConfigLoaded = false;
      return new Promise((resolve) => {
        const fail = (error) => {
          if (requestSeq !== this.agentConfigRequestSeq) return resolve(false);
          this.agentConfigLoading = false;
          this.$message.error(error?.data?.msg || error?.msg || i18n.t("roleConfig.fetchConfigFailed"));
          resolve(false);
        };
        Api.agent.getDeviceConfig(agentId, async ({ data }) => {
          try {
            if (requestSeq !== this.agentConfigRequestSeq) return resolve(false);
            if (data?.code !== 0 || !data.data) {
              fail(data);
              return;
            }
            const agentData = data.data;
            this.form = {
              ...this.form,
              ...agentData,
              model: {
                ttsModelId: agentData.ttsModelId || "",
                vadModelId: agentData.vadModelId || "",
                asrModelId: agentData.asrModelId || "",
                llmModelId: agentData.llmModelId || "",
                slmModelId: agentData.slmModelId || "",
                vllmModelId: agentData.vllmModelId || "",
                memModelId: agentData.memModelId || "",
                intentModelId: agentData.intentModelId || "",
              },
            };
            this.checkedReplacementWordIds = agentData.correctWordFileIds || [];
            this.currentContextProviders = agentData.contextProviders || [];
            await this.$nextTick();
            const hydrated = await this.$refs.editDialog.hydrateAgentConfig(agentData);
            if (requestSeq !== this.agentConfigRequestSeq) return resolve(false);
            this.agentConfigLoading = false;
            this.agentConfigLoaded = hydrated;
            if (!hydrated) {
              this.$message.error(i18n.t("roleConfig.fetchCapabilitiesFailed"));
            }
            resolve(hydrated);
          } catch (error) {
            fail(error);
          }
        }, fail);
      });
    },
    handleTtsModelChanged(ttsModelId) {
      this.loadVoicesForTtsModel(ttsModelId, this.form.ttsVoiceId);
    },
    loadVoicesForTtsModel(ttsModelId, preferredVoiceId = "") {
      if (!ttsModelId) {
        this.voiceRequestSeq += 1;
        this.voiceDetails = {};
        this.voiceOptions = [];
        return;
      }
      const requestSeq = this.voiceRequestSeq + 1;
      this.voiceRequestSeq = requestSeq;
      this.loadingVoices = true;
      Api.model.getModelVoices(ttsModelId, "", ({ data }) => {
        if (requestSeq !== this.voiceRequestSeq) {
          return;
        }
        this.loadingVoices = false;
        if (!data || data.code !== 0) {
          this.voiceDetails = {};
          this.voiceOptions = [];
          return;
        }
        const voices = Array.isArray(data.data) ? data.data : [];
        this.voiceDetails = voices.reduce((details, voice) => {
          details[voice.id] = {
            id: voice.id,
            name: voice.name,
            languages: voice.languages || "",
            voiceDemo: voice.voiceDemo || "",
            voice_demo: voice.voiceDemo || "",
            isClone: Boolean(voice.isClone),
          };
          return details;
        }, {});
        this.voiceOptions = this.buildVoiceOptions(voices);
        if (preferredVoiceId && this.voiceDetails[preferredVoiceId]) {
          this.form.ttsVoiceId = preferredVoiceId;
        } else if (!this.voiceOptions.some((item) => item.value === this.form.ttsVoiceId)) {
          this.form.ttsVoiceId = this.voiceOptions.length > 0 ? this.voiceOptions[0].value : "";
        }
      });
    },
    buildVoiceOptions(voices = []) {
      return voices.map((voice) => ({
          value: voice.id,
          label: voice.name,
          voiceDemo: voice.voiceDemo,
          voice_demo: voice.voiceDemo,
          isClone: Boolean(voice.isClone),
        }));
    },

    // ===== Save / Reset Flow =====
    async handleSave() {
      if (this.saveInProgress) return;
      const editDialog = this.$refs.editDialog;
      if (!this.agentConfigLoaded || !editDialog?.isReadyToSave()) {
        this.$message.warning(i18n.t("roleConfig.configNotReady"));
        return;
      }
      this.saveInProgress = true;
      try {
        const agentId = this.$route.query.agentId;
        const configData = editDialog.submitSaveConfig(agentId);
        await this.updateAgentConfig(agentId, configData);
        editDialog.markSaved();
        try {
          await this.handleSaveAgentTags(agentId);
        } catch (tagError) {
          this.$message.warning({
            message: tagError?.data?.msg || tagError?.message || i18n.t("roleConfig.configSavedTagsFailed"),
            showClose: true,
          });
          return;
        }
        this.$message.success({
          message: i18n.t("roleConfig.saveSuccess"),
          showClose: true,
        });
      } catch (error) {
        this.$message.error({
          message: error?.data?.msg || error?.message || error || i18n.t("roleConfig.saveFailed"),
          showClose: true,
        });
      } finally {
        this.saveInProgress = false;
      }
    },
    updateAgentConfig(agentId, configData) {
      return new Promise((resolve, reject) => {
        const fail = (error) => reject(error || new Error(i18n.t("roleConfig.saveFailed")));
        Api.agent.updateAgentConfig(agentId, configData, ({ data }) => {
          if (data?.code === 0) resolve(data.data);
          else fail(new Error(data?.msg || i18n.t("roleConfig.saveFailed")));
        }, fail);
      });
    },
    handleReset() {
      this.$refs.editDialog.resetConfig();
      this.dynamicTags = [];
      this.$message.success({
        message: i18n.t("roleConfig.resetSuccess"),
        showClose: true,
      });
    },
    handleTemplateSelect(template) {
      if (this.loadingTemplate) return;
      this.loadingTemplate = true;
      try {
        this.$refs.editDialog.applyTemplateData(template);
        this.$message.success({
          message: `${template.agentName}${i18n.t("roleConfig.templateApplied")}`,
          showClose: true,
        });
      } catch (error) {
        this.$message.error({
          message: i18n.t("roleConfig.applyTemplateFailed"),
          showClose: true,
        });
      } finally {
        this.loadingTemplate = false;
      }
    },
    handleOpenContextProvider() {
      this.$message.info(i18n.t("roleConfig.contextProviderHint") || "请前往设备管理页面配置上下文提供者");
    },
    handleFormChange(formData) {
      if (formData && formData.agentName !== undefined) {
        this.form.agentName = formData.agentName;
      }
    },
  },
  async mounted() {
    const agentId = this.$route.query.agentId;
    if (agentId) {
      this.fetchAgentConfig(agentId);
      this.getAgentTags(agentId);
    }
    this.fetchModelOptions();
    this.fetchTemplates();
    await this.loadFeatureStatus();
  },
  beforeDestroy() {
    this.agentConfigRequestSeq += 1;
    this.tagsRequestSeq += 1;
    this.voiceRequestSeq += 1;
  },
};
</script>

<style lang="scss" scoped>
::v-deep .el-radio-group {
  .is-active {
    .el-radio-button__inner {
      &:hover {
        color: #fff !important;
      }
    }
  }
}
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
  min-width: 600px;
  overflow: auto;
  background-color: white;
  display: flex;
  flex-direction: column;
}

.config-card {
  background: white;
  border: none;
  box-shadow: none;
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow-y: auto;
}

.config-header {
  position: relative;
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 0 0 5px 0;
  font-weight: 700;
  font-size: 19px;
  color: #3d4566;
  justify-content: space-between;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 13px;
  flex-shrink: 0;
}

.header-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  padding-bottom: 4px;
  &::-webkit-scrollbar {
    height: 6px;
    background: #e6ebff;
  }
  &::-webkit-scrollbar-thumb {
    background: #5778ff;
    border-radius: 8px;
  }
}

.header-tags .el-tag {
  flex-shrink: 0;
}

.more-tag {
  cursor: pointer;
  flex-shrink: 0;
}

.all-tags-popover {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 8px;
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

.divider {
  height: 1px;
  background: #e8f0ff;
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
  color: #409eff;
  border-color: #409eff;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}

.header-actions .hint-text {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #979db1;
  font-size: 12px;
  margin-right: 8px;
}

.header-actions .hint-text img {
  width: 16px;
  height: 16px;
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

.custom-tag {
  background: #e6ebff;
  color: #5778ff;
  border-radius: 8px;
  font-size: 12px;
  font-weight: normal;
  border: none;
}
.custom-tag-btn {
  background: #e6ebff;
  color: #5778ff;
  border-radius: 8px;
  font-weight: normal;
  border: 1px solid #e6ebff;
  &:hover {
    background-color: #d0d8ff;
  }
}
.input-new-tag {
  width: 90px;
  &::v-deep(.el-input__inner) {
    width: 90px !important;
  }
}
</style>
