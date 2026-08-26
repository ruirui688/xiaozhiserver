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
            <div class="config-toolbar">
              <div class="config-summary">
                <div class="header-left">
                  <div class="header-icon">
                    <img loading="lazy" src="@/assets/home/setting-user.png" alt="" />
                  </div>
                  <div class="header-copy">
                    <span class="header-title">{{ form.agentName || '未命名角色' }}</span>
                    <span class="header-subtitle">
                      <i class="el-icon-refresh" />保存后自动更新在线设备，无需重连
                    </span>
                  </div>
                </div>
                <div class="header-tags">
                  <span class="tags-label">标签</span>
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
                  <el-button class="custom-tag-btn" v-else size="small" @click="showInput">
                    <i class="el-icon-plus" />{{ $t("roleConfig.addTag") }}
                  </el-button>
                </div>
              </div>
              <div class="header-actions">
                <el-button class="reset-btn" icon="el-icon-refresh-left" @click="handleReset">
                  {{ $t("roleConfig.reset") }}
                </el-button>
                <el-button
                  v-if="workspaceCanWrite"
                  class="save-template-btn"
                  icon="el-icon-folder-add"
                  :loading="templateSaveInProgress"
                  :disabled="agentConfigLoading || !agentConfigLoaded || saveInProgress || templateSaveInProgress"
                  @click="handleSaveAsTemplate">
                  另存为角色模板
                </el-button>
                <el-button
                  type="primary"
                  class="save-btn"
                  icon="el-icon-check"
                  :loading="saveInProgress"
                  :disabled="agentConfigLoading || !agentConfigLoaded || saveInProgress"
                  @click="handleSave">
                  {{ $t("roleConfig.saveConfig") }}
                </el-button>
                <button class="custom-close-btn" title="关闭角色配置" @click="goToHome">
                  <i class="el-icon-close" />
                </button>
              </div>
            </div>

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
              :workspace-can-write="workspaceCanWrite"
              :checked-replacement-word-ids="checkedReplacementWordIds"
              @select-template="handleTemplateSelect"
              @delete-template="handleTemplateDelete"
              @submit-template-review="handleTemplateSubmitReview"
              @open-template-review="openTemplateReview"
              @publish-official-template="publishOfficialTemplate"
              @open-context-provider-dialog="handleOpenContextProvider"
              @update:currentFunctions="val => currentFunctions = val"
              @update:currentKnowledgeBases="val => currentKnowledgeBases = val"
              @update:checkedReplacementWordIds="val => checkedReplacementWordIds = val"
              @update:currentContextProviders="val => currentContextProviders = val"
              @update:voiceOptions="val => voiceOptions = val"
              @tts-model-changed="handleTtsModelChanged"
              @form-change="handleFormChange"
            />

            <el-dialog title="模板中心审核" :visible.sync="templateReviewVisible" width="680px" append-to-body>
              <el-table :data="templateReviewQueue" v-loading="templateReviewLoading" empty-text="暂无待审核模板">
                <el-table-column prop="templateName" label="模板名称" min-width="220" />
                <el-table-column prop="agentName" label="角色名称" min-width="180" />
                <el-table-column label="操作" width="150" align="center">
                  <template slot-scope="scope">
                    <el-button type="text" @click="reviewTemplate(scope.row, 'APPROVED')">通过</el-button>
                    <el-button type="text" class="danger-action" @click="reviewTemplate(scope.row, 'REJECTED')">驳回</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-dialog>
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
import { isRealtimeModel } from "@/utils/realtimeModel";

export default {
  name: "RoleConfigPage",
  components: { HeaderBar, VersionFooter, RoleEditDialog },
  data() {
    return {
      form: {
        agentCode: "",
        agentName: "",
        ttsVoiceId: "",
        realtimeVoiceId: "",
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
      templateReviewVisible: false,
      templateReviewLoading: false,
      templateReviewQueue: [],
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
      templateSaveInProgress: false,
      workspaceCanWrite: typeof localStorage === "undefined"
        || localStorage.getItem("currentWorkspaceCanWrite") !== "false",
      tagsRequestSeq: 0,
      tagsDirty: false,
    };
  },
  methods: {
    goToHome() {
      this.$router.push("/home");
    },
    loadWorkspacePermission() {
      Api.workspace.current(({ data }) => {
        if (data?.code === 0 && data.data) {
          this.workspaceCanWrite = Boolean(data.data.canWrite);
          if (typeof localStorage !== "undefined") {
            localStorage.setItem(
              "currentWorkspaceCanWrite",
              this.workspaceCanWrite ? "true" : "false"
            );
          }
        }
      });
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
      const realtime = isRealtimeModel(item);
      return {
        value: item.id,
        label: `${realtime ? "[实时多模态] " : ""}${meta ? `${title} · ${meta}` : title}`,
        title,
        meta,
        type: item.type || item.adapterCode || "",
        conversationMode: realtime ? "realtime" : "pipeline",
        isHidden: false,
        raw: item,
      };
    },
    fetchTemplates() {
      Api.agent.getAccessibleAgentTemplates(({ data }) => {
        if (data.code === 0) {
          this.templates = data.data;
        }
      }, () => {
        this.$message.error("角色模板加载失败");
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
    async handleSaveAsTemplate() {
      if (this.templateSaveInProgress) return;
      if (!this.workspaceCanWrite) {
        this.$message.warning("当前工作空间为只读，不能创建角色模板");
        return;
      }
      const editDialog = this.$refs.editDialog;
      if (!this.agentConfigLoaded || !editDialog?.isReadyToSave()) {
        this.$message.warning(i18n.t("roleConfig.configNotReady"));
        return;
      }
      try {
        const promptResult = await this.$prompt(
          "模板将在当前工作空间共享；声纹绑定和飞书配置会随模板切换，飞书密钥只加密保存。设备绑定和敏感请求头不会保存。",
          "另存为角色模板",
          {
            confirmButtonText: "保存模板",
            cancelButtonText: this.$t("common.cancel"),
            inputValue: `${editDialog.form.agentName || "角色"}模板`,
            inputPlaceholder: "请输入模板名称",
            inputValidator: (value) => {
              const name = String(value || "").trim();
              if (!name) return "请输入模板名称";
              if (name.length > 64) return "模板名称不能超过64个字符";
              return true;
            },
          }
        );
        this.templateSaveInProgress = true;
        const agentId = this.$route.query.agentId;
        const config = editDialog.submitSaveConfig(agentId);
        const payload = {
          templateName: String(promptResult.value).trim(),
          config,
          tagNames: this.dynamicTags.map((tag) => tag.tagName),
        };
        const result = await this.saveAgentAsTemplate(agentId, payload);
        await this.fetchTemplatesAsync();
        const warnings = result?.warnings || [];
        this.$message.success("角色模板已保存");
        warnings.forEach((warning) => this.$message.warning({ message: warning, showClose: true }));
      } catch (error) {
        if (error !== "cancel" && error !== "close") {
          this.$message.error(error?.data?.msg || error?.message || "角色模板保存失败");
        }
      } finally {
        this.templateSaveInProgress = false;
      }
    },
    saveAgentAsTemplate(agentId, payload) {
      return new Promise((resolve, reject) => {
        Api.agent.saveAgentAsTemplate(agentId, payload, ({ data }) => {
          if (data?.code === 0) resolve(data.data);
          else reject(new Error(data?.msg || "角色模板保存失败"));
        }, reject);
      });
    },
    fetchTemplatesAsync() {
      return new Promise((resolve, reject) => {
        Api.agent.getAccessibleAgentTemplates(({ data }) => {
          if (data?.code === 0) {
            this.templates = data.data || [];
            resolve(this.templates);
          } else {
            reject(new Error(data?.msg || "角色模板加载失败"));
          }
        }, reject);
      });
    },
    async handleTemplateSelect(template) {
      if (this.loadingTemplate) return;
      try {
        await this.$confirm(
          `确认将“${template.templateName || template.agentName}”立即应用到当前角色吗？角色配置、声纹绑定和飞书集成会一起替换并在线生效；失效引用会被跳过。`,
          "切换角色模板",
          {
            confirmButtonText: "确认切换",
            cancelButtonText: this.$t("common.cancel"),
            type: "warning",
          }
        );
        this.loadingTemplate = true;
        const result = await this.applyAgentTemplate(template);
        await this.hydrateAppliedTemplate(result);
        this.$message.success({ message: "角色模板已切换并在线生效", showClose: true });
        (result.skippedItems || []).forEach((item) => {
          this.$message.warning({ message: item, showClose: true, duration: 8000 });
        });
      } catch (error) {
        if (error !== "cancel" && error !== "close") {
          this.$message.error({
            message: error?.data?.msg || error?.message || i18n.t("roleConfig.applyTemplateFailed"),
            showClose: true,
          });
        }
      } finally {
        this.loadingTemplate = false;
      }
    },
    applyAgentTemplate(template) {
      return new Promise((resolve, reject) => {
        const agentId = this.$route.query.agentId;
        Api.agent.applyAgentTemplate(agentId, template.id, template.version, ({ data }) => {
          if (data?.code === 0) resolve(data.data);
          else reject(new Error(data?.msg || "角色模板切换失败"));
        }, reject);
      });
    },
    async hydrateAppliedTemplate(result) {
      const agentData = result?.agent;
      if (!agentData) throw new Error("模板应用成功，但未返回最新角色配置");
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
      this.dynamicTags = (result.tagNames || []).map((tagName, index) => ({
        id: `template-tag-${index}`,
        tagName,
      }));
      this.tagsDirty = false;
      await this.$nextTick();
      const hydrated = await this.$refs.editDialog.hydrateAgentConfig(agentData);
      this.agentConfigLoaded = hydrated;
      this.$refs.editDialog.markSaved();
      this.$refs.editDialog.fetchAgentSkillCount();
    },
    async handleTemplateDelete(template) {
      try {
        await this.$confirm(
          `确认删除工作空间模板“${template.templateName}”吗？`,
          "删除角色模板",
          {
            confirmButtonText: this.$t("common.confirm"),
            cancelButtonText: this.$t("common.cancel"),
            type: "warning",
          }
        );
        await new Promise((resolve, reject) => {
          Api.agent.deleteWorkspaceAgentTemplate(template.id, ({ data }) => {
            if (data?.code === 0) resolve();
            else reject(new Error(data?.msg || "角色模板删除失败"));
          }, reject);
        });
        await this.fetchTemplatesAsync();
        this.$message.success("角色模板已删除");
      } catch (error) {
        if (error !== "cancel" && error !== "close") {
          this.$message.error(error?.data?.msg || error?.message || "角色模板删除失败");
        }
      }
    },
    async handleTemplateSubmitReview(template) {
      try {
        await this.$confirm(
          `确认将“${template.templateName}”提交到模板中心审核吗？审核通过前仍只在当前工作空间可见。`,
          '提交模板审核',
          { type: 'info' }
        );
        await new Promise((resolve, reject) => {
          Api.agent.submitAgentTemplateReview(template.id, ({ data }) => {
            if (data?.code === 0) resolve();
            else reject(new Error(data?.msg || '提交审核失败'));
          }, reject);
        });
        await this.fetchTemplatesAsync();
        this.$message.success('模板已提交审核');
      } catch (error) {
        if (error !== 'cancel' && error !== 'close') {
          this.$message.error(error?.data?.msg || error?.message || '提交审核失败');
        }
      }
    },
    openTemplateReview() {
      this.templateReviewVisible = true;
      this.loadTemplateReviewQueue();
    },
    publishOfficialTemplate(template) {
      this.$confirm(
        `确认将“${template.templateName}”直接发布为所有用户可见的官方模板吗？工作空间私有凭据和绑定会被清除。`,
        '发布官方模板',
        { type: 'warning' }
      ).then(() => {
        Api.agent.publishOfficialAgentTemplate(template.id, ({ data }) => {
          if (data?.code === 0) {
            this.$message.success('已发布为官方模板');
            this.fetchTemplatesAsync();
          } else this.$message.error(data?.msg || '发布失败');
        }, (err) => this.$message.error(err?.data?.msg || '发布失败'));
      }).catch(() => {});
    },
    loadTemplateReviewQueue() {
      this.templateReviewLoading = true;
      Api.agent.getAgentTemplateReviewQueue(({ data }) => {
        this.templateReviewLoading = false;
        if (data?.code === 0) this.templateReviewQueue = data.data || [];
        else this.$message.error(data?.msg || '审核队列加载失败');
      }, (err) => {
        this.templateReviewLoading = false;
        this.$message.error(err?.data?.msg || '审核队列加载失败');
      });
    },
    reviewTemplate(template, decision) {
      const action = decision === 'APPROVED' ? '通过' : '驳回';
      this.$prompt(`请输入${action}说明（可留空）`, `${action}模板`, {
        inputValue: '',
        inputValidator: (value) => !value || value.length <= 500 || '审核说明不能超过500字'
      }).then(({ value }) => {
        Api.agent.reviewAgentTemplate(template.id, decision, value || '', ({ data }) => {
          if (data?.code === 0) {
            this.$message.success(`已${action}模板`);
            this.loadTemplateReviewQueue();
            this.fetchTemplatesAsync();
          } else this.$message.error(data?.msg || `${action}失败`);
        }, (err) => this.$message.error(err?.data?.msg || `${action}失败`));
      }).catch(() => {});
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
    this.loadWorkspacePermission();
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
  flex: 1;
  min-height: 0;
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
  overflow: hidden;
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
  scrollbar-color: #b7c4f8 #f1f4ff;
}

.config-card::v-deep .el-card__body {
  padding: 0 20px 26px;
}

.config-toolbar {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 18px;
  min-height: 68px;
  margin: 0 -2px 16px;
  padding: 10px 2px 10px;
  border-bottom: 1px solid #e8edf8;
  background: rgba(255, 255, 255, 0.97);
  box-shadow: 0 5px 14px rgba(49, 61, 103, 0.04);
  backdrop-filter: blur(10px);
  font-weight: 700;
  font-size: 19px;
  color: #3d4566;
  justify-content: space-between;
}

.config-summary {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 16px;
  min-width: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 13px;
  flex-shrink: 0;
}

.header-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.header-title {
  overflow: hidden;
  color: #2f3952;
  font-size: 17px;
  line-height: 23px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.header-subtitle {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 2px;
  color: #9299ac;
  font-size: 11px;
  font-weight: 400;
  line-height: 16px;
}

.header-tags {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  padding: 3px 0;
  &::-webkit-scrollbar {
    height: 6px;
    background: #e6ebff;
  }
  &::-webkit-scrollbar-thumb {
    background: #5778ff;
    border-radius: 8px;
  }
}

.tags-label {
  flex-shrink: 0;
  color: #a1a8b8;
  font-size: 11px;
  font-weight: 400;
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

.custom-close-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin-left: 2px;
  padding: 0;
  border: 1px solid #dfe3ed;
  border-radius: 9px;
  background: #fff;
  color: #9299ab;
  cursor: pointer;
  font-size: 16px;
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
  flex-shrink: 0;
}

.header-actions .save-btn {
  background: #5778ff;
  color: white;
  border: none;
  border-radius: 9px;
  padding: 8px 16px;
  height: 32px;
  font-size: 14px;
}

.header-actions .save-template-btn {
  background: #fff;
  color: #5778ff;
  border: 1px solid #9eb0ff;
  border-radius: 9px;
  padding: 8px 16px;
  height: 32px;
}

.header-actions .reset-btn {
  background: #f6f7fa;
  color: #7b8294;
  border: 1px solid #e1e4ec;
  border-radius: 9px;
  padding: 8px 16px;
  height: 32px;
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

@media (max-width: 1250px) {
  .config-toolbar {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }

  .config-summary {
    width: 100%;
  }

  .header-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
