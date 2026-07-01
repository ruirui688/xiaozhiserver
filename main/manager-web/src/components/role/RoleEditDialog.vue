<template>
  <div class="role-edit-form">
    <el-form ref="form" :model="form" label-width="72px">
      <div class="form-content">
        <div class="form-grid">
          <div class="form-column">
            <el-form-item>
              <template #label>
                <el-tooltip :content="$t('roleConfig.tooltip.agentName')" placement="top" effect="light" popper-class="custom-tooltip">
                  <span>{{ $t('roleConfig.agentName') }}：</span>
                </el-tooltip>
              </template>
              <el-input
                v-model="form.agentName"
                class="form-input"
                maxlength="64"
              />
            </el-form-item>
            <el-form-item>
              <template #label>
                <el-tooltip :content="$t('roleConfig.tooltip.roleTemplate')" placement="top" effect="light" popper-class="custom-tooltip">
                  <span>{{ $t('roleConfig.roleTemplate') }}：</span>
                </el-tooltip>
              </template>
              <div class="template-container">
                <div
                  v-for="(template, index) in templates"
                  :key="`template-${index}`"
                  class="template-item"
                  :class="{ 'template-loading': loadingTemplate }"
                  @click="selectTemplate(template)"
                >
                  {{ template.agentName }}
                </div>
              </div>
            </el-form-item>
            <el-form-item class="context-provider-item">
              <template #label>
                <el-tooltip :content="$t('roleConfig.tooltip.contextProvider')" placement="top" effect="light" popper-class="custom-tooltip">
                  <span>{{ $t('roleConfig.contextProvider') }}：</span>
                </el-tooltip>
              </template>
              <div style="display: flex; align-items: center; justify-content: space-between;">
                <span style="color: #606266; font-size: 13px;">
                  {{ $t('roleConfig.contextProviderSuccess', { count: currentContextProviders.length }) }}<a href="https://github.com/xinnan-tech/xiaozhi-esp32-server/blob/main/docs/context-provider-integration.md" target="_blank" class="doc-link">{{ $t('roleConfig.contextProviderDocLink') }}</a>
                </span>
                <el-button
                  class="edit-function-btn"
                  size="small"
                  @click="openContextProviderDialog"
                >
                  {{ $t('roleConfig.editContextProvider') }}
                </el-button>
              </div>
            </el-form-item>
            <el-form-item>
              <template #label>
                <el-tooltip :content="$t('roleConfig.tooltip.roleIntroduction')" placement="top" effect="light" popper-class="custom-tooltip">
                  <span>{{ $t('roleConfig.roleIntroduction') }}：</span>
                </el-tooltip>
              </template>
              <el-input
                type="textarea"
                rows="8"
                resize="none"
                :placeholder="$t('roleConfig.pleaseEnterContent')"
                v-model="form.systemPrompt"
                maxlength="2000"
                show-word-limit
                class="form-textarea"
              />
            </el-form-item>

            <el-form-item>
              <template #label>
                <el-tooltip :content="$t('roleConfig.tooltip.memoryHis')" placement="top" effect="light" popper-class="custom-tooltip">
                  <span>{{ $t('roleConfig.memoryHis') }}：</span>
                </el-tooltip>
              </template>
              <el-input
                type="textarea"
                rows="4"
                resize="none"
                v-model="form.summaryMemory"
                maxlength="2000"
                show-word-limit
                class="form-textarea"
                :disabled="form.model.memModelId !== 'Memory_mem_local_short'"
              />
            </el-form-item>
            <el-form-item
              style="display: none"
            >
              <template #label>
                <el-tooltip :content="$t('roleConfig.tooltip.languageCode')" placement="top" effect="light" popper-class="custom-tooltip">
                  <span>{{ $t('roleConfig.languageCode') }}：</span>
                </el-tooltip>
              </template>
              <el-input
                v-model="form.langCode"
                :placeholder="$t('roleConfig.pleaseEnterLangCode')"
                maxlength="10"
                show-word-limit
                class="form-input"
              />
            </el-form-item>
            <el-form-item
              style="display: none"
            >
              <template #label>
                <el-tooltip :content="$t('roleConfig.tooltip.interactionLanguage')" placement="top" effect="light" popper-class="custom-tooltip">
                  <span>{{ $t('roleConfig.interactionLanguage') }}：</span>
                </el-tooltip>
              </template>
              <el-input
                v-model="form.language"
                :placeholder="$t('roleConfig.pleaseEnterLangName')"
                maxlength="10"
                show-word-limit
                class="form-input"
              />
            </el-form-item>
          </div>
          <div class="form-column">
            <div class="model-row">
              <el-form-item
                v-if="featureStatus.vad"
                class="model-item"
              >
                <template #label>
                  <el-tooltip :content="$t('roleConfig.tooltip.vad')" placement="top" effect="light" popper-class="custom-tooltip">
                    <span>{{ $t('roleConfig.vad') }}</span>
                  </el-tooltip>
                </template>
                <div class="model-select-wrapper">
                  <el-select
                    v-model="form.model.vadModelId"
                    filterable
                    clearable
                    :placeholder="$t('roleConfig.pleaseSelect')"
                    class="form-select"
                    popper-class="model-select-dropdown"
                    @change="handleModelChange('VAD', $event)"
                  >
                    <el-option
                      v-for="(item, optionIndex) in modelOptions['VAD']"
                      :key="`option-vad-${optionIndex}`"
                      :label="item.label"
                      :value="item.value"
                    >
                      <div class="model-option-content">
                        <div class="model-option-title">{{ item.title || item.label }}</div>
                        <div v-if="item.meta" class="model-option-meta">{{ item.meta }}</div>
                      </div>
                    </el-option>
                  </el-select>
                </div>
              </el-form-item>
              <el-form-item
                v-if="featureStatus.asr"
                class="model-item"
              >
                <template #label>
                  <el-tooltip :content="$t('roleConfig.tooltip.asr')" placement="top" effect="light" popper-class="custom-tooltip">
                    <span>{{ $t('roleConfig.asr') }}</span>
                  </el-tooltip>
                </template>
                <div class="model-select-wrapper">
                  <el-select
                    v-model="form.model.asrModelId"
                    filterable
                    clearable
                    :placeholder="$t('roleConfig.pleaseSelect')"
                    class="form-select"
                    popper-class="model-select-dropdown"
                    @change="handleModelChange('ASR', $event)"
                  >
                    <el-option
                      v-for="(item, optionIndex) in modelOptions['ASR']"
                      :key="`option-asr-${optionIndex}`"
                      :label="item.label"
                      :value="item.value"
                    >
                      <div class="model-option-content">
                        <div class="model-option-title">{{ item.title || item.label }}</div>
                        <div v-if="item.meta" class="model-option-meta">{{ item.meta }}</div>
                      </div>
                    </el-option>
                  </el-select>
                </div>
              </el-form-item>
            </div>
            <div class="model-row">
              <el-form-item class="model-item">
                <template #label>
                  <el-tooltip :content="$t('roleConfig.tooltip.llm')" placement="top" effect="light" popper-class="custom-tooltip">
                    <span>{{ $t('roleConfig.llm') }}</span>
                  </el-tooltip>
                </template>
                <div class="model-select-wrapper">
                  <el-select
                    v-model="form.model.llmModelId"
                    filterable
                    clearable
                    :placeholder="$t('roleConfig.pleaseSelect')"
                    class="form-select"
                    popper-class="model-select-dropdown"
                    @change="handleModelChange('LLM', $event)"
                  >
                    <el-option
                      v-for="(item, optionIndex) in modelOptions['LLM']"
                      :key="`option-llm-${optionIndex}`"
                      :label="item.label"
                      :value="item.value"
                    >
                      <div class="model-option-content">
                        <div class="model-option-title">{{ item.title || item.label }}</div>
                        <div v-if="item.meta" class="model-option-meta">{{ item.meta }}</div>
                      </div>
                    </el-option>
                  </el-select>
                </div>
              </el-form-item>
              <el-form-item class="model-item">
                <template #label>
                  <el-tooltip :content="$t('roleConfig.tooltip.slm')" placement="top" effect="light" popper-class="custom-tooltip">
                    <span>{{ $t('roleConfig.slm') }}</span>
                  </el-tooltip>
                </template>
                <div class="model-select-wrapper">
                  <el-select
                    v-model="form.model.slmModelId"
                    filterable
                    clearable
                    :placeholder="$t('roleConfig.pleaseSelect')"
                    class="form-select"
                    popper-class="model-select-dropdown"
                  >
                    <el-option
                      v-for="(item, optionIndex) in modelOptions['SLM']"
                      :key="`option-slm-${optionIndex}`"
                      :label="item.label"
                      :value="item.value"
                    >
                      <div class="model-option-content">
                        <div class="model-option-title">{{ item.title || item.label }}</div>
                        <div v-if="item.meta" class="model-option-meta">{{ item.meta }}</div>
                      </div>
                    </el-option>
                  </el-select>
                </div>
              </el-form-item>
            </div>
            <el-form-item
              v-for="(model, index) in models.slice(4)"
              :key="`model-${index}`"
              class="model-item"
            >
              <template #label>
                <el-tooltip :content="$t('roleConfig.tooltip.' + model.type.toLowerCase())" placement="top" effect="light" popper-class="custom-tooltip">
                  <span>{{ $t('roleConfig.' + model.type.toLowerCase()) }}</span>
                </el-tooltip>
              </template>
              <div class="model-select-wrapper">
                <el-select
                  v-model="form.model[model.key]"
                  filterable
                  clearable
                  :placeholder="$t('roleConfig.pleaseSelect')"
                  class="form-select"
                  popper-class="model-select-dropdown"
                  @change="handleModelChange(model.type, $event)"
                >
                  <el-option
                    v-for="(item, optionIndex) in modelOptions[model.type]"
                    v-if="!item.isHidden"
                    :key="`option-${index}-${optionIndex}`"
                    :label="item.label"
                    :value="item.value"
                  >
                    <div class="model-option-content">
                      <div class="model-option-title">{{ item.title || item.label }}</div>
                      <div v-if="item.meta" class="model-option-meta">{{ item.meta }}</div>
                    </div>
                  </el-option>
                </el-select>
                <div
                  v-if="
                    model.type === 'Memory' &&
                    isMemoryModelEnabled(form.model.memModelId)
                  "
                  class="chat-history-options"
                >
                  <el-radio-group
                    v-model="form.chatHistoryConf"
                    @change="updateChatHistoryConf"
                  >
                    <el-radio-button :label="1">{{
                      $t("roleConfig.reportText")
                    }}</el-radio-button>
                    <el-radio-button :label="2">{{
                      $t("roleConfig.reportTextVoice")
                    }}</el-radio-button>
                  </el-radio-group>
                </div>
              </div>
              <div v-if="showFunctionIcons(model.type)" class="capability-config-panel">
                <div class="capability-row">
                  <div class="capability-label">{{ $t("roleConfig.functionPlugins") }}</div>
                  <div class="function-summary">
                    <el-tooltip
                      v-for="func in currentFunctions"
                      :key="func.name"
                      effect="light"
                      placement="top"
                    >
                      <div slot="content">
                        <div><strong>功能名称:</strong> {{ func.name }}</div>
                      </div>
                      <div class="icon-dot">
                        {{ getFunctionDisplayChar(func.name) }}
                      </div>
                    </el-tooltip>
                    <span v-if="currentFunctions.length === 0" class="capability-empty">
                      {{ $t("roleConfig.noFunctionsSelected") }}
                    </span>
                  </div>
                  <el-button
                    class="edit-function-btn"
                    @click="openFunctionDialog"
                    :class="{ 'active-btn': showFunctionDialog }"
                  >
                    {{ $t("roleConfig.editFunctions") }}
                  </el-button>
                </div>
                <div class="capability-row knowledge-row">
                  <div class="capability-label">{{ $t("roleConfig.knowledgeBases") }}</div>
                  <div class="knowledge-chip-list">
                    <el-tag
                      v-for="knowledge in currentKnowledgeBases"
                      :key="knowledge.id"
                      size="small"
                      class="knowledge-chip"
                      closable
                      @close="removeKnowledgeBase(knowledge.id)"
                    >
                      {{ knowledge.name }}
                    </el-tag>
                    <span v-if="currentKnowledgeBases.length === 0" class="capability-empty">
                      {{ $t("roleConfig.noKnowledgeBaseSelected") }}
                    </span>
                  </div>
                  <el-button
                    class="knowledge-add-btn"
                    @click="openKnowledgeBaseDialog"
                    :title="$t('roleConfig.editKnowledgeBases')"
                  >
                    +
                  </el-button>
                </div>
              </div>
            </el-form-item>
            <div class="model-row">
              <!-- Language filter -->
              <el-form-item class="model-item language-select-item">
                <template #label>
                  <el-tooltip :content="$t('roleConfig.tooltip.language')" placement="top" effect="light" popper-class="custom-tooltip">
                    <span>{{ $t('roleConfig.language') }}</span>
                  </el-tooltip>
                </template>
                <div class="model-select-wrapper">
                  <el-select
                    v-model="localSelectedLanguage"
                    :placeholder="$t('roleConfig.selectLanguage')"
                    class="form-select language-select"
                    @change="filterVoicesByLanguage"
                  >
                    <el-option
                      v-for="(lang, index) in languageOptions"
                      :key="`lang-${index}`"
                      :label="lang.label"
                      :value="lang.value"
                    />
                  </el-select>
                </div>
              </el-form-item>

              <!-- Voice selection -->
              <el-form-item class="model-item">
                <template #label>
                  <el-tooltip :content="$t('roleConfig.tooltip.voiceType')" placement="top" effect="light" popper-class="custom-tooltip">
                    <span>{{ $t('roleConfig.voiceType') }}</span>
                  </el-tooltip>
                </template>
                <div class="model-select-wrapper">
                  <el-select
                    v-model="form.ttsVoiceId"
                    filterable
                    :placeholder="$t('roleConfig.pleaseSelect')"
                    class="form-select"
                  >
                    <el-option
                      v-for="(item, index) in voiceOptions"
                      :key="`voice-${index}`"
                      :label="item.label"
                      :value="item.value"
                    >
                      <div
                        style="
                          display: flex;
                          justify-content: space-between;
                          align-items: center;
                        "
                      >
                        <span>{{ item.label }}</span>
                        <template v-if="hasAudioPreview(item)">
                          <el-button
                            type="text"
                            :icon="
                              playingVoice &&
                              currentPlayingVoiceId === item.value &&
                              !isPaused
                                ? 'el-icon-video-pause'
                                : 'el-icon-video-play'
                            "
                            size="small"
                            @click.stop="toggleAudioPlayback(item.value)"
                            :loading="false"
                            class="play-button"
                          />
                        </template>
                      </div>
                    </el-option>
                  </el-select>
                  <el-button
                    class="edit-function-btn"
                    style="margin-left: 10px;"
                    @click="openTtsAdvancedSettings"
                  >
                    {{ $t('roleConfig.advancedSettings') }}
                  </el-button>
                </div>
              </el-form-item>
            </div>
          </div>
        </div>
      </div>
    </el-form>

    <!-- Sub-dialogs -->
    <function-dialog
      v-model="showFunctionDialog"
      :functions="currentFunctions"
      :all-functions="allFunctions"
      :agent-id="$route.query.agentId || ''"
      @update-functions="handleUpdateFunctions"
      @dialog-closed="handleDialogClosed"
    />
    <knowledge-base-selector-dialog
      v-model="showKnowledgeBaseDialog"
      :selected="currentKnowledgeBases"
      @confirm="handleUpdateKnowledgeBases"
    />
    <tts-advanced-settings
      :visible.sync="showTtsAdvancedDialog"
      :settings="ttsSettings"
      :checked-replacement-word-ids="checkedReplacementWordIds"
      @save="handleTtsSettingsSave"
    />
  </div>
</template>

<script>
import Api from "@/apis/api";
import { getServiceUrl } from "@/apis/api";
import RequestService from "@/apis/httpRequest";
import FunctionDialog from "@/components/FunctionDialog.vue";
import KnowledgeBaseSelectorDialog from "@/components/KnowledgeBaseSelectorDialog.vue";
import TtsAdvancedSettings from "@/components/TtsAdvancedSettings.vue";
import i18n from "@/i18n";

export default {
  name: "RoleEditForm",
  components: { FunctionDialog, KnowledgeBaseSelectorDialog, TtsAdvancedSettings },
  props: {
    initialForm: { type: Object, default: () => null },
    models: { type: Array, default: () => [] },
    modelOptions: { type: Object, default: () => ({}) },
    templates: { type: Array, default: () => [] },
    voiceOptions: { type: Array, default: () => [] },
    languageOptions: { type: Array, default: () => [] },
    voiceDetails: { type: Object, default: () => ({}) },
    featureStatus: { type: Object, default: () => ({}) },
    currentFunctions: { type: Array, default: () => [] },
    currentKnowledgeBases: { type: Array, default: () => [] },
    currentContextProviders: { type: Array, default: () => [] },
    selectedLanguage: { type: String, default: '' },
    playingVoice: { type: Boolean, default: false },
    currentPlayingVoiceId: { type: String, default: '' },
    isPaused: { type: Boolean, default: false },
    loadingTemplate: { type: Boolean, default: false },
    checkedReplacementWordIds: { type: Array, default: () => [] },
    llmModeTypeMap: { type: Map, default: () => new Map() },
  },
  data() {
    return {
      form: this.buildFormFromProp(this.initialForm),
      localSelectedLanguage: this.selectedLanguage,
      showFunctionDialog: false,
      showKnowledgeBaseDialog: false,
      showTtsAdvancedDialog: false,
      ttsSettings: { volume: 0, speed: 0, pitch: 0 },
      tempSummaryMemory: "",
      allFunctions: [],
      allKnowledgeBases: [],
      originalFunctions: [],
      currentAudio: null,
      noAvailableModelTypes: [],
      noAvailableModelWarningTimer: null,
    };
  },
  computed: {
    effectiveAgentId() {
      return this.$route.query.agentId || '';
    }
  },
  methods: {
    buildFormFromProp(initial) {
      if (!initial) {
        return this.getEmptyForm();
      }
      return {
        agentCode: initial.agentCode || "",
        agentName: initial.agentName || "",
        ttsVoiceId: initial.ttsVoiceId || "",
        ttsVolume: initial.ttsVolume !== undefined ? initial.ttsVolume : null,
        ttsRate: initial.ttsRate !== undefined ? initial.ttsRate : null,
        ttsPitch: initial.ttsPitch !== undefined ? initial.ttsPitch : null,
        chatHistoryConf: initial.chatHistoryConf || 0,
        systemPrompt: initial.systemPrompt || "",
        summaryMemory: initial.summaryMemory || "",
        langCode: initial.langCode || "",
        language: initial.language || "",
        sort: initial.sort || "",
        ttsLanguage: initial.ttsLanguage || "",
        model: {
          ttsModelId: (initial.model && initial.model.ttsModelId) || "",
          vadModelId: (initial.model && initial.model.vadModelId) || "",
          asrModelId: (initial.model && initial.model.asrModelId) || "",
          llmModelId: (initial.model && initial.model.llmModelId) || "",
          slmModelId: (initial.model && initial.model.slmModelId) || "",
          vllmModelId: (initial.model && initial.model.vllmModelId) || "",
          memModelId: (initial.model && initial.model.memModelId) || "",
          intentModelId: (initial.model && initial.model.intentModelId) || "",
        }
      };
    },
    getEmptyForm() {
      return {
        agentCode: "", agentName: "", ttsVoiceId: "", ttsVolume: null, ttsRate: null, ttsPitch: null,
        chatHistoryConf: 0, systemPrompt: "", summaryMemory: "", langCode: "", language: "", sort: "",
        ttsLanguage: "",
        model: { ttsModelId: "", vadModelId: "", asrModelId: "", llmModelId: "", slmModelId: "", vllmModelId: "", memModelId: "", intentModelId: "" }
      };
    },
    saveConfig() {
      this.$emit('save', { ...this.form });
    },
    resetConfig() {
      this.form = this.getEmptyForm();
      this.$emit('reset');
    },
    submitSaveConfig(agentId) {
      const configData = {
        agentCode: this.form.agentCode,
        agentName: this.form.agentName,
        asrModelId: this.form.model.asrModelId,
        vadModelId: this.form.model.vadModelId,
        llmModelId: this.form.model.llmModelId,
        slmModelId: this.form.model.slmModelId,
        vllmModelId: this.form.model.vllmModelId,
        ttsModelId: this.form.model.ttsModelId,
        ttsVoiceId: this.form.ttsVoiceId,
        ttsLanguage: this.localSelectedLanguage,
        chatHistoryConf: this.form.chatHistoryConf,
        memModelId: this.form.model.memModelId,
        intentModelId: this.form.model.intentModelId,
        systemPrompt: this.form.systemPrompt,
        summaryMemory: this.form.summaryMemory,
        langCode: this.form.langCode,
        language: this.form.language,
        sort: this.form.sort,
        functions: this.buildFunctionMappings(),
        contextProviders: this.currentContextProviders,
        correctWordFileIds: this.checkedReplacementWordIds,
      };
      if (this.form.ttsVolume !== null && this.form.ttsVolume !== undefined) {
        configData.ttsVolume = this.form.ttsVolume;
      }
      if (this.form.ttsRate !== null && this.form.ttsRate !== undefined) {
        configData.ttsRate = this.form.ttsRate;
      }
      if (this.form.ttsPitch !== null && this.form.ttsPitch !== undefined) {
        configData.ttsPitch = this.form.ttsPitch;
      }
      return configData;
    },
    fetchTemplates() {
      Api.agent.getAgentTemplate(({ data }) => {
        if (data.code === 0) {
          this.$emit('update-templates', data.data);
        }
      });
    },
    selectTemplate(template) {
      this.$emit('select-template', template);
    },
    applyTemplateData(templateData) {
      this.form = {
        ...this.form,
        agentName: templateData.agentName || this.form.agentName,
        ttsVoiceId: templateData.ttsVoiceId || this.form.ttsVoiceId,
        chatHistoryConf: templateData.chatHistoryConf || this.form.chatHistoryConf,
        systemPrompt: templateData.systemPrompt || this.form.systemPrompt,
        summaryMemory: templateData.summaryMemory || this.form.summaryMemory,
        langCode: templateData.langCode || this.form.langCode,
        model: {
          ttsModelId: this.templateValue(templateData, "ttsModelId", this.form.model.ttsModelId),
          vadModelId: this.templateValue(templateData, "vadModelId", this.form.model.vadModelId),
          asrModelId: this.templateValue(templateData, "asrModelId", this.form.model.asrModelId),
          llmModelId: this.templateValue(templateData, "llmModelId", this.form.model.llmModelId),
          slmModelId: this.templateValue(templateData, "slmModelId", this.form.model.slmModelId),
          vllmModelId: this.templateValue(templateData, "vllmModelId", this.form.model.vllmModelId),
          memModelId: this.templateValue(templateData, "memModelId", this.form.model.memModelId),
          intentModelId: this.templateValue(templateData, "intentModelId", this.form.model.intentModelId),
        },
      };
    },
    templateValue(templateData, key, fallback) {
      return Object.prototype.hasOwnProperty.call(templateData, key)
        ? (templateData[key] || "")
        : fallback;
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
    shouldWarnNoAvailableModel(model, options) {
      if (options.length > 0) return false;
      if (model.type === "Intent") return false;
      const selectedValue = this.form.model[model.key];
      return Boolean(selectedValue);
    },
    queueNoAvailableModelWarning(model, options) {
      if (!this.shouldWarnNoAvailableModel(model, options)) return;
      if (!this.noAvailableModelTypes.includes(model.type)) {
        this.noAvailableModelTypes.push(model.type);
      }
      if (this.noAvailableModelWarningTimer) {
        clearTimeout(this.noAvailableModelWarningTimer);
      }
      this.noAvailableModelWarningTimer = setTimeout(() => {
        const labels = this.models
          .filter((item) => this.noAvailableModelTypes.includes(item.type))
          .map((item) => item.label);
        if (labels.length > 0) {
          this.$message.warning(
            this.$t("roleConfig.noAvailableModels", { type: labels.join("、") })
          );
        }
      }, 100);
    },
    filterVoicesByLanguage() {
      if (!this.voiceDetails || Object.keys(this.voiceDetails).length === 0) {
        this.$emit('update:voiceOptions', []);
        return;
      }
      const allVoices = Object.values(this.voiceDetails);
      const filteredVoices = allVoices.filter(voice => {
        if (!voice.languages) {
          return Boolean(voice.isClone);
        }
        const languagesArray = voice.languages.split(/[、；;,，]/).map(lang => lang.trim()).filter(lang => lang);
        return languagesArray.includes(this.localSelectedLanguage);
      });
      const newVoiceOptions = filteredVoices.map((voice) => ({
        value: voice.id,
        label: voice.name,
        voiceDemo: voice.voiceDemo,
        voice_demo: voice.voice_demo,
        isClone: Boolean(voice.isClone),
        train_status: voice.trainStatus,
      }));
      this.$emit('update:voiceOptions', newVoiceOptions);
      const currentVoiceSupportsLanguage = this.form.ttsVoiceId &&
        filteredVoices.some(voice => voice.id === this.form.ttsVoiceId);
      if (!currentVoiceSupportsLanguage) {
        this.form.ttsVoiceId = filteredVoices.length > 0 ? filteredVoices[0].id : '';
      }
      this.ttsSettings = {
        volume: this.form.ttsVolume !== null && this.form.ttsVolume !== undefined ? this.form.ttsVolume : 0,
        speed: this.form.ttsRate !== null && this.form.ttsRate !== undefined ? this.form.ttsRate : 0,
        pitch: this.form.ttsPitch !== null && this.form.ttsPitch !== undefined ? this.form.ttsPitch : 0
      };
    },
    getFunctionDisplayChar(name) {
      if (!name || name.length === 0) return "";
      for (let i = 0; i < name.length; i++) {
        const char = name[i];
        if (/[一-龥a-zA-Z0-9]/.test(char)) return char;
      }
      return name.charAt(0);
    },
    showFunctionIcons(type) {
      return type === "Intent" && this.form.model.intentModelId !== "Intent_nointent";
    },
    handleModelChange(type, value) {
      if (type === "Intent" && value !== "Intent_nointent") {
        this.fetchAllFunctions();
      }
      if (type === "Memory") {
        if (!this.isMemoryModelEnabled(value)) {
          this.form.chatHistoryConf = 0;
        } else {
          this.form.chatHistoryConf = 2;
        }
        if (this.isSummaryMemoryDisabled(value)) {
          this.tempSummaryMemory = this.form.summaryMemory;
          this.form.summaryMemory = "";
        } else if (this.tempSummaryMemory !== "" && this.form.summaryMemory === "") {
          this.form.summaryMemory = this.tempSummaryMemory;
          this.tempSummaryMemory = "";
        }
      }
      if (type === "LLM") {
        this.updateIntentOptionsVisibility();
      }
    },
    fetchAllFunctions() {
      return new Promise((resolve, reject) => {
        Api.model.getPluginFunctionList(null, ({ data }) => {
          if (data.code === 0) {
            this.allFunctions = data.data.map((item) => {
              const meta = JSON.parse(item.fields || "[]");
              const params = meta.reduce((m, f) => { m[f.key] = f.default; return m; }, {});
              return { ...item, fieldsMeta: meta, params };
            });
            resolve();
          } else {
            console.error(data.msg || "获取插件失败");
            reject();
          }
        });
      });
    },
    fetchAllKnowledgeBases() {
      return new Promise((resolve) => {
        Api.knowledgeBase.getKnowledgeBaseList(
          { page: 1, page_size: 1000, name: "" },
          (res) => {
            if (res.data && res.data.code === 0) {
              const pageData = res.data.data || {};
              this.allKnowledgeBases = (pageData.list || []).filter((item) => item.status === 1);
            } else {
              this.allKnowledgeBases = [];
            }
            resolve();
          },
          () => {
            this.allKnowledgeBases = [];
            resolve();
          }
        );
      });
    },
    applyFunctionMappings(savedMappings) {
      const knowledgeMap = new Map(this.allKnowledgeBases.map((item) => [item.id, item]));
      const functions = [];
      const knowledgeBases = [];
      savedMappings.forEach((mapping) => {
        const knowledge = knowledgeMap.get(mapping.pluginId);
        if (knowledge) {
          knowledgeBases.push({
            id: knowledge.id,
            name: knowledge.name,
            description: knowledge.description,
            documentCount: knowledge.documentCount || 0,
            queryPolicy: mapping.paramInfo?.queryPolicy || this.defaultKnowledgeQueryPolicy(),
          });
          return;
        }
        const meta = this.allFunctions.find((f) => f.id === mapping.pluginId);
        if (!meta) {
          functions.push({ id: mapping.pluginId, name: mapping.pluginId, params: {} });
          return;
        }
        functions.push({
          id: mapping.pluginId,
          name: meta.name,
          providerCode: meta.providerCode,
          params: mapping.paramInfo || { ...meta.params },
          fieldsMeta: meta.fieldsMeta,
        });
      });
      this.$emit('update:currentFunctions', functions);
      this.$emit('update:currentKnowledgeBases', knowledgeBases);
    },
    buildFunctionMappings() {
      const pluginMappings = (this.currentFunctions || []).map((item) => ({
        pluginId: item.id,
        paramInfo: item.params,
      }));
      const knowledgeMappings = (this.currentKnowledgeBases || []).map((item) => ({
        pluginId: item.id,
        paramInfo: { queryPolicy: item.queryPolicy || this.defaultKnowledgeQueryPolicy() },
      }));
      return [...pluginMappings, ...knowledgeMappings];
    },
    defaultKnowledgeQueryPolicy() {
      return "优先使用用户原话和对话中已经明确的酒类、产区、风味、价格、餐酒搭配等信息生成查询词；不要擅自扩写成酒杯、酒具、器皿或其他用户没有提到的对象。";
    },
    openFunctionDialog() {
      if (this.allFunctions.length === 0) {
        this.fetchAllFunctions().then(() => (this.showFunctionDialog = true));
      } else {
        this.showFunctionDialog = true;
      }
    },
    openKnowledgeBaseDialog() {
      this.fetchAllKnowledgeBases().then(() => {
        this.showKnowledgeBaseDialog = true;
      });
    },
    openContextProviderDialog() {
      this.$emit('open-context-provider-dialog');
    },
    openTtsAdvancedSettings() {
      this.showTtsAdvancedDialog = true;
    },
    handleTtsSettingsSave(settings) {
      const { replacementWordIds, ...ttsSettings } = settings;
      this.$emit('update:checkedReplacementWordIds', replacementWordIds);
      this.ttsSettings = ttsSettings;
      this.form.ttsVolume = ttsSettings.volume;
      this.form.ttsRate = ttsSettings.speed;
      this.form.ttsPitch = ttsSettings.pitch;
    },
    handleUpdateFunctions(selected) {
      this.$emit('update:currentFunctions', selected);
    },
    handleUpdateKnowledgeBases(selected) {
      const updated = (selected || []).map((item) => ({
        ...item,
        queryPolicy: item.queryPolicy || this.defaultKnowledgeQueryPolicy(),
      }));
      this.$emit('update:currentKnowledgeBases', updated);
    },
    removeKnowledgeBase(id) {
      const updated = (this.currentKnowledgeBases || []).filter((item) => item.id !== id);
      this.$emit('update:currentKnowledgeBases', updated);
    },
    handleDialogClosed(saved) {
      if (!saved) {
        this.$emit('update:currentFunctions', JSON.parse(JSON.stringify(this.originalFunctions)));
      } else {
        this.originalFunctions = JSON.parse(JSON.stringify(this.currentFunctions));
      }
      this.showFunctionDialog = false;
    },
    updateIntentOptionsVisibility() {
      const currentLlmId = this.form.model.llmModelId;
      if (!currentLlmId || !this.modelOptions["Intent"]) return;
      const llmType = this.llmModeTypeMap ? this.llmModeTypeMap.get(currentLlmId) : null;
      if (!llmType) return;
      this.modelOptions["Intent"].forEach((item) => {
        if (item.value === "Intent_function_call") {
          item.isHidden = !(llmType === "openai" || llmType === "ollama");
        } else {
          item.isHidden = false;
        }
      });
      if (
        this.form.model.intentModelId === "Intent_function_call" &&
        llmType !== "openai" && llmType !== "ollama"
      ) {
        const firstVisibleOption = this.modelOptions["Intent"].find((item) => !item.isHidden);
        this.form.model.intentModelId = firstVisibleOption ? firstVisibleOption.value : "Intent_nointent";
      }
    },
    hasAudioPreview(item) {
      const isCloneAudio = Boolean(item.isClone);
      const hasValidAudioUrl = !!((item.voice_demo || item.voiceDemo)?.trim());
      return isCloneAudio || hasValidAudioUrl;
    },
    toggleAudioPlayback(voiceId) {
      if (this.playingVoice && this.currentPlayingVoiceId === voiceId) {
        if (this.isPaused) {
          this.currentAudio.play().catch((error) => {
            console.error("恢复播放失败:", error);
            this.$message.warning(this.$t('roleConfig.cannotResumeAudio'));
          });
          this.$emit('update:isPaused', false);
        } else {
          this.currentAudio.pause();
          this.$emit('update:isPaused', true);
        }
        return;
      }
      this.playVoicePreview(voiceId);
    },
    playVoicePreview(voiceId = null) {
      const targetVoiceId = voiceId || this.form.ttsVoiceId;
      if (!targetVoiceId) {
        this.$message.warning(this.$t('roleConfig.selectVoiceFirst'));
        return;
      }
      if (this.currentAudio) {
        this.currentAudio.pause();
        this.currentAudio = null;
      }
      this.$emit('update:isPaused', false);
      this.$emit('update:currentPlayingVoiceId', targetVoiceId);
      try {
        const voiceDetail = this.voiceDetails[targetVoiceId];
        let audioUrl = null;
        let isCloneAudio = false;
        if (voiceDetail) {
          isCloneAudio = Boolean(voiceDetail.isClone);
          if (isCloneAudio && voiceDetail.id) {
            const getCloneAudioUrl = () => {
              return new Promise((resolve) => {
                RequestService.sendRequest()
                  .url(`${getServiceUrl()}/voiceClone/audio/${voiceDetail.id}`)
                  .method("POST")
                  .success((res) => {
                    if (res.data.code === 0 && res.data.data) {
                      const audioId = res.data.data;
                      const playUrl = `${getServiceUrl()}/voiceClone/play/${audioId}`;
                      resolve(playUrl);
                    } else {
                      resolve(null);
                    }
                  })
                  .networkFail((err) => { resolve(null); })
                  .send();
              });
            };
            this.$emit('update:playingVoice', true);
            this.currentAudio = new Audio();
            this.currentAudio.volume = 1.0;
            const timeoutId = setTimeout(() => {
              if (this.currentAudio) {
                this.$message.warning(this.$t('roleConfig.audioLoadTimeout'));
                this.$emit('update:playingVoice', false);
              }
            }, 10000);
            this.currentAudio.onerror = () => {
              clearTimeout(timeoutId);
              this.$message.warning(this.$t('roleConfig.cloneAudioPlayFailed'));
              this.$emit('update:playingVoice', false);
            };
            this.currentAudio.onplay = () => { clearTimeout(timeoutId); };
            this.currentAudio.onended = () => { this.$emit('update:playingVoice', false); };
            getCloneAudioUrl().then((url) => {
              if (url) {
                this.currentAudio.src = url;
                this.currentAudio.play().catch((error) => {
                  clearTimeout(timeoutId);
                  this.$message.warning(this.$t('roleConfig.cannotPlayCloneAudio'));
                  this.$emit('update:playingVoice', false);
                });
              } else {
                clearTimeout(timeoutId);
                this.$message.warning(this.$t('roleConfig.getCloneAudioFailed'));
                this.$emit('update:playingVoice', false);
              }
            });
            return;
          } else {
            audioUrl = voiceDetail.voiceDemo || voiceDetail.voice_demo;
          }
          if (!audioUrl) {
            for (const key in voiceDetail) {
              const value = voiceDetail[key];
              if (typeof value === "string" && (value.startsWith("http://") || value.startsWith("https://") || value.endsWith(".mp3") || value.endsWith(".wav") || value.endsWith(".ogg"))) {
                audioUrl = value;
                break;
              }
            }
          }
        }
        if (!audioUrl) {
          this.$message.warning(this.$t('roleConfig.noPreviewAudio'));
          return;
        }
        if (!isCloneAudio) {
          this.$emit('update:playingVoice', true);
          this.currentAudio = new Audio();
          this.currentAudio.src = audioUrl;
          this.currentAudio.volume = 1.0;
          const timeoutId = setTimeout(() => {
            if (this.currentAudio) {
              this.$message.warning(this.$t('roleConfig.audioLoadTimeout'));
              this.$emit('update:playingVoice', false);
            }
          }, 10000);
          this.currentAudio.onerror = () => {
            clearTimeout(timeoutId);
            this.$message.warning(this.$t('roleConfig.audioPlayFailed'));
            this.$emit('update:playingVoice', false);
          };
          this.currentAudio.onplay = () => { clearTimeout(timeoutId); };
          this.currentAudio.onended = () => { this.$emit('update:playingVoice', false); };
          this.currentAudio.play().catch((error) => {
            clearTimeout(timeoutId);
            this.$message.warning(this.$t('roleConfig.cannotPlayAudio'));
            this.$emit('update:playingVoice', false);
          });
        }
      } catch (error) {
        console.error("播放音频过程出错:", error);
        this.$message.error(this.$t('roleConfig.audioPlayError'));
        this.$emit('update:playingVoice', false);
      }
    },
    updateChatHistoryConf() {
      if (!this.isMemoryModelEnabled(this.form.model.memModelId)) {
        this.form.chatHistoryConf = 0;
      }
    },
    isMemoryModelEnabled(modelId) {
      return Boolean(modelId && modelId !== "Memory_nomem");
    },
    isSummaryMemoryDisabled(modelId) {
      return !modelId || modelId === "Memory_nomem" || modelId === "Memory_mem_report_only" || modelId === "Memory_managed_memory";
    },
  },
  watch: {
    initialForm: {
      handler(newVal) {
        if (newVal) {
          this.form = this.buildFormFromProp(newVal);
        }
      },
      deep: true,
    },
    selectedLanguage: {
      handler(newVal) {
        if (newVal !== this.localSelectedLanguage) {
          this.localSelectedLanguage = newVal;
        }
      },
    },
    localSelectedLanguage(newVal) {
      this.$emit('update:selectedLanguage', newVal);
    },
    'form.model.ttsModelId': {
      handler(newVal, oldVal) {
        if (newVal) {
          this.$emit('tts-model-changed', newVal);
        }
      },
    },
    form: {
      handler() {
        this.$emit('form-change', { ...this.form });
      },
      deep: true,
    },
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

.form-content {
  padding: 2vh 0;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.form-column {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-input {
  width: 100%;
}

.form-select {
  flex: 1;
  width: 100%;
  height: 36px;
}

.play-button {
  color: #409eff;
  transition: color 0.3s;
}

.play-button:hover {
  color: #66b1ff;
}

.play-button.is-loading {
  color: #909399;
}

.form-textarea {
  width: 100%;
}

.voice-select-wrapper {
  display: flex;
  align-items: center;
  gap: 10px;
}

.template-container {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.template-item {
  height: 4vh;
  min-width: 60px;
  padding: 0 12px;
  border-radius: 8px;
  background: #e6ebff;
  line-height: 4vh;
  font-weight: 400;
  font-size: 11px;
  text-align: center;
  color: #5778ff;
  cursor: pointer;
  transition: background-color 0.3s ease;
  white-space: nowrap;
}

.template-item:hover {
  background-color: #d0d8ff;
}

.model-select-wrapper {
  display: flex;
  align-items: center;
  width: 100%;
}

.model-row {
  display: flex;
  gap: 20px;
  margin-bottom: 6px;
}

.model-row .model-item {
  flex: 1;
  margin-bottom: 0;
}

.model-row .language-select-item {
  flex: 0 0 35%;
  max-width: 35%;
}

.model-row .language-select-item .language-select {
  width: 100%;
}

.model-row .el-form-item__label {
  font-size: 12px !important;
  color: #3d4566 !important;
  font-weight: 400;
  line-height: 22px;
  padding-bottom: 2px;
}

.capability-config-panel {
  width: 100%;
  margin-top: 10px;
  padding: 6px 0 0;
}

.capability-row {
  display: grid;
  grid-template-columns: 64px 1fr auto;
  align-items: center;
  gap: 10px;
  min-height: 34px;
  padding: 4px 0;
}

.capability-row + .capability-row {
  margin-top: 2px;
}

.capability-label {
  color: #5f6680;
  font-size: 13px;
  line-height: 20px;
}

.function-summary,
.knowledge-chip-list {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
}

.capability-empty {
  color: #a3a8b8;
  font-size: 13px;
}

.knowledge-chip {
  max-width: 180px;
  background: #eceef2;
  border-color: #eceef2;
  color: #6f7482;
  border-radius: 4px;
}

.knowledge-chip ::v-deep .el-tag__close {
  color: #8a8f9e;
}

.knowledge-chip ::v-deep .el-tag__close:hover {
  color: #fff;
  background: #9aa0ad;
}

.knowledge-add-btn {
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px dashed #c7cbd6;
  border-radius: 4px;
  background: #f6f7fa;
  color: #777d8c;
  font-size: 22px;
  line-height: 24px;
  font-weight: 400;
}

.knowledge-add-btn:hover {
  color: #5778ff;
  background: #eef2ff;
  border-color: #adbdff;
}

.icon-dot {
  width: 25px;
  height: 25px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #5778ff;
  font-weight: bold;
  font-size: 12px;
  margin-right: 8px;
  position: relative;
  background-color: #e6ebff;
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
  background: none;
  position: absolute;
  font-size: 12px;
  right: 3%;
}

.edit-function-btn {
  background: #e6ebff;
  color: #5778ff;
  border: 1px solid #adbdff;
  border-radius: 18px;
  padding: 10px 20px;
  transition: all 0.3s;
}

.edit-function-btn.active-btn {
  background: #5778ff;
  color: white;
}

.chat-history-options {
  display: flex;
  gap: 10px;
  min-width: 250px;
  justify-content: flex-end;
}

.chat-history-options ::v-deep .el-radio-button {
  border-color: #5778ff;
}

.chat-history-options ::v-deep .el-radio-button .el-radio-button__inner {
  color: #5778ff;
  border-color: #5778ff;
  background-color: transparent;
}

.chat-history-options ::v-deep .el-radio-button.is-active .el-radio-button__inner {
  background-color: #5778ff;
  border-color: #5778ff;
  color: white;
}

.chat-history-options ::v-deep .el-radio-button .el-radio-button__inner:hover {
  color: #5778ff;
}

.context-provider-item ::v-deep .el-form-item__label {
  line-height: 42px !important;
}

.doc-link {
  color: #5778ff;
  text-decoration: none;
  margin-left: 4px;

  &:hover {
    text-decoration: underline;
  }
}

.slider-wrapper {
  width: 100%;
  padding-right: 12px;
}

.slider-hint {
  display: block;
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
  line-height: 1.5;
}

.tts-slider {
  width: 100%;
}

.tts-slider ::v-deep .el-slider__input {
  width: 80px;
}

.tts-slider ::v-deep .el-input__inner {
  text-align: center;
  padding: 0 8px;
}
</style>

<style>
.custom-tooltip {
  max-width: 400px !important;
  word-break: break-word;
}

.model-select-dropdown .el-select-dropdown__item {
  height: auto;
  min-height: 46px;
  line-height: normal;
  padding-top: 6px;
  padding-bottom: 6px;
}

.model-option-content {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
}

.model-option-title {
  color: #303133;
  font-size: 14px;
  line-height: 20px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-option-meta {
  color: #909399;
  font-size: 12px;
  line-height: 18px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-select-dropdown .el-select-dropdown__item.selected .model-option-title {
  color: #409eff;
  font-weight: 600;
}
</style>
