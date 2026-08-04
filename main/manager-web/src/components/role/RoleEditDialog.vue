<template>
  <div class="role-edit-form">
    <role-template-switcher
      ref="templateSection"
      :templates="templates"
      :loading="loadingTemplate"
      :workspace-can-write="workspaceCanWrite"
      @select="selectTemplate"
      @delete="deleteTemplate"
    />

    <nav class="section-nav" aria-label="角色配置分区导航">
      <span class="section-nav-title">快速定位</span>
      <button type="button" @click="scrollToSection('templateSection')">
        <i class="el-icon-s-operation" />模板切换
      </button>
      <button type="button" @click="scrollToSection('personaSection')">
        <i class="el-icon-user" />角色与集成
      </button>
      <button type="button" @click="scrollToSection('modelSection')">
        <i class="el-icon-set-up" />模型与能力
      </button>
    </nav>

    <el-form ref="form" :model="form" label-width="72px">
      <div class="form-content">
        <div class="form-grid">
          <section ref="personaSection" class="form-column config-section">
            <div class="section-heading">
              <span class="section-number">01</span>
              <div>
                <h3>角色设定与外部集成</h3>
                <p>定义角色身份、回复方式，以及上下文、技能和飞书协作。</p>
              </div>
            </div>
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
            <div class="subsection-title">
              <i class="el-icon-link" />
              <span>资源与集成</span>
              <small>按需连接上下文、技能和协作平台</small>
            </div>
            <el-form-item class="context-provider-item integration-config-item">
              <template #label>
                <el-tooltip :content="$t('roleConfig.tooltip.contextProvider')" placement="top" effect="light" popper-class="custom-tooltip">
                  <span>{{ $t('roleConfig.contextProvider') }}：</span>
                </el-tooltip>
              </template>
              <div class="integration-row">
                <span class="integration-title">
                  {{ $t('roleConfig.contextProviderSuccess', { count: currentContextProviders.length }) }}<a href="https://github.com/xinnan-tech/xiaozhi-esp32-server/blob/main/docs/context-provider-integration.md" target="_blank" class="doc-link">{{ $t('roleConfig.contextProviderDocLink') }}</a>
                </span>
                <el-button
                  class="edit-function-btn"
                  size="small"
                  icon="el-icon-setting"
                  @click="openContextProviderDialog"
                >
                  管理上下文源
                </el-button>
              </div>
            </el-form-item>
            <el-form-item class="integration-config-item">
              <template #label>
                <el-tooltip :content="$t('roleConfig.tooltip.agentSkill')" placement="top" effect="light" popper-class="custom-tooltip">
                  <span>{{ $t('roleConfig.agentSkill') }}：</span>
                </el-tooltip>
              </template>
              <div class="integration-row">
                <div class="integration-main">
                  <span class="integration-title">{{ $t('roleConfig.agentSkillTitle') }}</span>
                  <span class="integration-desc">
                    {{ skillCount === null
                      ? $t('roleConfig.agentSkillSummaryUnknown')
                      : $t('roleConfig.agentSkillSummary', { count: skillCount }) }}
                  </span>
                </div>
                <el-button
                  class="edit-function-btn"
                  size="small"
                  icon="el-icon-microphone"
                  @click="openAgentSkillDialog"
                >{{ $t('roleConfig.agentSkillConfigure') }}</el-button>
              </div>
            </el-form-item>
            <el-form-item class="integration-config-item" label="集成：">
              <div class="integration-row">
                <div class="integration-main">
                  <span class="integration-title">飞书协作</span>
                  <span class="integration-desc">独立保存 App Secret 和群聊权限，不属于功能库勾选项</span>
                </div>
                <el-button
                  class="edit-function-btn"
                  size="small"
                  icon="el-icon-connection"
                  @click="showFeishuDialog = true"
                >管理飞书配置</el-button>
              </div>
            </el-form-item>
            <div class="subsection-title role-content-subsection">
              <i class="el-icon-chat-line-square" />
              <span>角色内容</span>
              <small>决定角色身份、表达方式和短期记忆</small>
            </div>
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
          </section>
          <section ref="modelSection" class="form-column config-section">
            <div class="section-heading">
              <span class="section-number">02</span>
              <div>
                <h3>语音、模型与能力</h3>
                <p>配置识别、思考、记忆、知识库、功能和最终发声效果。</p>
              </div>
            </div>
            <div class="subsection-title">
              <i class="el-icon-microphone" />
              <span>语音输入</span>
              <small>检测用户开口并将语音转成文字</small>
            </div>
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
                  <asr-hotword-field
                    v-if="supportsAsrHotword"
                    :ids="form.asrHotwordIds"
                    :active-id="form.asrHotwordId"
                    @change="handleAsrHotwordChange"
                  />
                </div>
              </el-form-item>
            </div>
            <div class="subsection-title">
              <i class="el-icon-cpu" />
              <span>智能模型与能力</span>
              <small>控制角色如何思考、调用功能和使用记忆</small>
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
                        <div class="model-option-title">
                          {{ item.title || item.label }}
                          <el-tag v-if="item.conversationMode === 'realtime'" size="mini" type="warning">
                            {{ $t("realtimeModel.badge") }}
                          </el-tag>
                        </div>
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
            <realtime-model-notice
              :model="selectedLlmModel"
              context="agent"
            />
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
                  <span class="chat-history-label">{{
                    $t("roleConfig.chatHistoryStorage")
                  }}</span>
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
                <div class="capability-card">
                  <div class="capability-card-heading">
                    <span class="capability-icon"><i class="el-icon-s-grid" /></span>
                    <div>
                      <strong>{{ $t("roleConfig.functionPlugins") }}</strong>
                      <span>让角色可以查询信息、控制设备或执行任务</span>
                    </div>
                  </div>
                  <div class="function-summary capability-selection">
                    <el-tag
                      v-for="func in currentFunctions"
                      :key="func.name"
                      size="small"
                      class="function-chip"
                    >
                      {{ func.name }}
                    </el-tag>
                    <span v-if="currentFunctions.length === 0" class="capability-empty">
                      {{ $t("roleConfig.noFunctionsSelected") }}
                    </span>
                  </div>
                  <el-button
                    class="edit-function-btn"
                    icon="el-icon-setting"
                    @click="openFunctionDialog"
                    :class="{ 'active-btn': showFunctionDialog }"
                  >
                    {{ $t("roleConfig.editFunctions") }}
                  </el-button>
                </div>
                <div class="capability-card">
                  <div class="capability-card-heading">
                    <span class="capability-icon knowledge-icon"><i class="el-icon-collection" /></span>
                    <div>
                      <strong>{{ $t("roleConfig.knowledgeBases") }}</strong>
                      <span>为角色提供可检索的专属资料</span>
                    </div>
                  </div>
                  <div class="knowledge-chip-list capability-selection">
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
                    class="edit-function-btn"
                    icon="el-icon-plus"
                    @click="openKnowledgeBaseDialog"
                  >
                    {{ $t('roleConfig.editKnowledgeBases') }}
                  </el-button>
                </div>
              </div>
            </el-form-item>
            <div class="subsection-title voice-subsection">
              <i class="el-icon-headset" />
              <span>发声效果</span>
              <small>选择角色最终使用的音色和语速参数</small>
            </div>
            <div class="model-row">
              <el-form-item class="model-item">
                <template #label>
                  <el-tooltip :content="$t('roleConfig.tooltip.voiceType')" placement="top" effect="light" popper-class="custom-tooltip">
                    <span>{{ $t('roleConfig.voiceType') }}</span>
                  </el-tooltip>
                </template>
                <div class="model-select-wrapper voice-model-config">
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
                    class="edit-function-btn voice-settings-btn"
                    icon="el-icon-s-tools"
                    @click="openTtsAdvancedSettings"
                  >
                    音色参数设置
                  </el-button>
                </div>
              </el-form-item>
            </div>
          </section>
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
    <feishu-integration-dialog
      :visible.sync="showFeishuDialog"
      :agent-id="effectiveAgentId"
    />
    <agent-skill-dialog
      :visible.sync="showAgentSkillDialog"
      :agent-id="effectiveAgentId"
      @count-change="skillCount = $event"
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
import FeishuIntegrationDialog from "@/components/role/FeishuIntegrationDialog.vue";
import AgentSkillDialog from "@/components/role/AgentSkillDialog.vue";
import AsrHotwordField from "@/components/role/AsrHotwordField.vue";
import RoleTemplateSwitcher from "@/components/role/RoleTemplateSwitcher.vue";
import RealtimeModelNotice from "@/components/model/RealtimeModelNotice.vue";
import i18n from "@/i18n";
import {
  buildRoleForm,
  changedRolePaths,
  cloneRoleValue,
  emptyRoleForm,
  mergeRoleDraft,
  normalizeAsrHotwordConfig,
  normalizeMappingParams,
  splitCapabilityMappings,
} from "@/utils/roleConfigDraft";

export default {
  name: "RoleEditForm",
  components: {
    FunctionDialog,
    KnowledgeBaseSelectorDialog,
    TtsAdvancedSettings,
    FeishuIntegrationDialog,
    AgentSkillDialog,
    AsrHotwordField,
    RoleTemplateSwitcher,
    RealtimeModelNotice,
  },
  props: {
    initialForm: { type: Object, default: () => null },
    models: { type: Array, default: () => [] },
    modelOptions: { type: Object, default: () => ({}) },
    templates: { type: Array, default: () => [] },
    voiceOptions: { type: Array, default: () => [] },
    voiceDetails: { type: Object, default: () => ({}) },
    featureStatus: { type: Object, default: () => ({}) },
    currentFunctions: { type: Array, default: () => [] },
    currentKnowledgeBases: { type: Array, default: () => [] },
    currentContextProviders: { type: Array, default: () => [] },
    playingVoice: { type: Boolean, default: false },
    currentPlayingVoiceId: { type: String, default: '' },
    isPaused: { type: Boolean, default: false },
    loadingTemplate: { type: Boolean, default: false },
    workspaceCanWrite: { type: Boolean, default: true },
    checkedReplacementWordIds: { type: Array, default: () => [] },
    llmModeTypeMap: { type: Map, default: () => new Map() },
  },
  data() {
    const form = this.buildFormFromProp(this.initialForm);
    return {
      form,
      showFunctionDialog: false,
      showKnowledgeBaseDialog: false,
      showTtsAdvancedDialog: false,
      showFeishuDialog: false,
      showAgentSkillDialog: false,
      skillCount: null,
      skillCountLoadSeq: 0,
      ttsSettings: { volume: 0, speed: 0, pitch: 0 },
      tempSummaryMemory: "",
      lastEnabledChatHistoryConf: [1, 2].includes(Number(form.chatHistoryConf))
        ? Number(form.chatHistoryConf)
        : 2,
      allFunctions: [],
      allKnowledgeBases: [],
      originalFunctions: [],
      currentAudio: null,
      noAvailableModelTypes: [],
      noAvailableModelWarningTimer: null,
      lastFormSnapshot: cloneRoleValue(form),
      dirtyFormPaths: [],
      suppressFormTracking: false,
      capabilityLoadSeq: 0,
      capabilityRevision: 0,
      capabilityMappingsReady: false,
    };
  },
  computed: {
    effectiveAgentId() {
      return this.$route.query.agentId || '';
    },
    supportsAsrHotword() {
      const selectedId = this.form.model.asrModelId;
      const selected = (this.modelOptions.ASR || []).find((item) => item.value === selectedId);
      const adapter = String(selected?.type || selected?.raw?.adapterCode || "").toLowerCase();
      return adapter === "doubao_stream" || adapter === "doubao_recording";
    },
    selectedLlmModel() {
      const selectedId = this.form.model.llmModelId;
      return (this.modelOptions.LLM || []).find((item) => item.value === selectedId) || {};
    }
  },
  methods: {
    scrollToSection(refName) {
      const target = this.$refs[refName];
      const element = target && (target.$el || target);
      if (!element || typeof element.scrollIntoView !== "function") return;
      element.scrollIntoView({ behavior: "smooth", block: "start" });
    },
    buildFormFromProp(initial) {
      return buildRoleForm(initial);
    },
    getEmptyForm() {
      return emptyRoleForm();
    },
    async hydrateAgentConfig(agentData) {
      const requestSeq = ++this.capabilityLoadSeq;
      const capabilityRevision = this.capabilityRevision;
      const mergedForm = mergeRoleDraft(agentData, this.form, this.dirtyFormPaths);
      this.replaceFormWithoutTracking(mergedForm);
      this.ttsSettings = {
        volume: mergedForm.ttsVolume ?? 0,
        speed: mergedForm.ttsRate ?? 0,
        pitch: mergedForm.ttsPitch ?? 0,
      };
      this.$emit('update:checkedReplacementWordIds', agentData.correctWordFileIds || []);
      this.$emit('update:currentContextProviders', agentData.contextProviders || []);

      const metadataReady = await Promise.all([
        this.fetchAllFunctions(),
        this.fetchAllKnowledgeBases(),
      ]);
      if (requestSeq !== this.capabilityLoadSeq) return false;
      if (!metadataReady.every(Boolean)) {
        this.capabilityMappingsReady = false;
        return false;
      }
      if (capabilityRevision === this.capabilityRevision) {
        this.applyFunctionMappings(agentData.functions || []);
      }
      this.capabilityMappingsReady = true;
      return true;
    },
    replaceFormWithoutTracking(form) {
      this.suppressFormTracking = true;
      const nextForm = cloneRoleValue(form);
      this.form = nextForm;
      this.lastEnabledChatHistoryConf = [1, 2].includes(
        Number(nextForm.chatHistoryConf)
      )
        ? Number(nextForm.chatHistoryConf)
        : 2;
      this.lastFormSnapshot = cloneRoleValue(nextForm);
      this.$nextTick(() => {
        this.suppressFormTracking = false;
        this.lastFormSnapshot = cloneRoleValue(this.form);
      });
    },
    isReadyToSave() {
      return this.capabilityMappingsReady;
    },
    markSaved() {
      this.dirtyFormPaths = [];
      this.lastFormSnapshot = cloneRoleValue(this.form);
    },
    trackFormChanges() {
      if (this.suppressFormTracking) {
        this.lastFormSnapshot = cloneRoleValue(this.form);
        return;
      }
      const changed = changedRolePaths(this.lastFormSnapshot || emptyRoleForm(), this.form);
      this.dirtyFormPaths = Array.from(new Set([...this.dirtyFormPaths, ...changed]));
      this.lastFormSnapshot = cloneRoleValue(this.form);
      this.$emit('form-change', cloneRoleValue(this.form));
    },
    saveConfig() {
      this.$emit('save', { ...this.form });
    },
    resetConfig() {
      this.dirtyFormPaths = [];
      this.replaceFormWithoutTracking(this.getEmptyForm());
      this.$emit('reset');
    },
    submitSaveConfig(agentId) {
      const hotwordSelection = normalizeAsrHotwordConfig(
        this.form.asrHotwordIds,
        this.form.asrHotwordId
      );
      const configData = {
        agentCode: this.form.agentCode,
        agentName: this.form.agentName,
        asrModelId: this.form.model.asrModelId,
        asrHotwordId: hotwordSelection.activeId,
        asrHotwordIds: hotwordSelection.ids,
        vadModelId: this.form.model.vadModelId,
        llmModelId: this.form.model.llmModelId,
        slmModelId: this.form.model.slmModelId,
        vllmModelId: this.form.model.vllmModelId,
        ttsModelId: this.form.model.ttsModelId,
        ttsVoiceId: this.form.ttsVoiceId,
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
    handleAsrHotwordChange({ ids, activeId }) {
      this.form.asrHotwordIds = ids;
      this.form.asrHotwordId = activeId;
    },
    fetchTemplates() {
      Api.agent.getAgentTemplate(({ data }) => {
        if (data.code === 0) {
          this.$emit('update-templates', data.data);
        }
      });
    },
    selectTemplate(template) {
      if (!this.workspaceCanWrite) {
        this.$message.warning("当前工作空间为只读，不能切换角色模板");
        return;
      }
      this.$emit('select-template', template);
    },
    deleteTemplate(template) {
      this.$emit('delete-template', template);
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
          if ([1, 2].includes(Number(this.form.chatHistoryConf))) {
            this.lastEnabledChatHistoryConf = Number(this.form.chatHistoryConf);
          }
          this.form.chatHistoryConf = 0;
        } else if (![1, 2].includes(Number(this.form.chatHistoryConf))) {
          this.form.chatHistoryConf = this.lastEnabledChatHistoryConf;
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
      return new Promise((resolve) => {
        Api.model.getPluginFunctionList(null, ({ data }) => {
          if (data.code === 0) {
            const functions = Array.isArray(data.data) ? data.data : [];
            this.allFunctions = functions.map((item) => {
              let meta = [];
              try {
                meta = Array.isArray(item.fields)
                  ? item.fields
                  : JSON.parse(item.fields || "[]");
              } catch (error) {
                meta = [];
              }
              const params = meta.reduce((m, f) => { m[f.key] = f.default; return m; }, {});
              return { ...item, fieldsMeta: meta, params };
            });
            resolve(true);
          } else {
            resolve(false);
          }
        }, () => resolve(false));
      });
    },
    fetchAllKnowledgeBases() {
      return new Promise((resolve) => {
        Api.knowledgeBase.getKnowledgeBaseList(
          { page: 1, page_size: 1000, name: "" },
          (res) => {
            if (res.data && res.data.code === 0) {
              const pageData = res.data.data || {};
              this.allKnowledgeBases = pageData.list || [];
              resolve(true);
            } else {
              resolve(false);
            }
          },
          () => {
            resolve(false);
          }
        );
      });
    },
    applyFunctionMappings(savedMappings) {
      const mappings = splitCapabilityMappings(
        savedMappings,
        this.allFunctions,
        this.allKnowledgeBases,
        this.defaultKnowledgeQueryPolicy()
      );
      this.$emit('update:currentFunctions', mappings.functions);
      this.$emit('update:currentKnowledgeBases', mappings.knowledgeBases);
      this.originalFunctions = cloneRoleValue(mappings.functions);
    },
    buildFunctionMappings() {
      const pluginMappings = (this.currentFunctions || []).map((item) => ({
        pluginId: item.id,
        paramInfo: normalizeMappingParams(item.params),
      }));
      const knowledgeMappings = (this.currentKnowledgeBases || []).map((item) => ({
        pluginId: item.id,
        paramInfo: { queryPolicy: item.queryPolicy || this.defaultKnowledgeQueryPolicy() },
      }));
      return [...pluginMappings, ...knowledgeMappings];
    },
    defaultKnowledgeQueryPolicy() {
      return "仅依据用户当前问题和已明确的上下文生成查询词，不得补充用户未提及的对象、品类或场景。";
    },
    openFunctionDialog() {
      if (!this.capabilityMappingsReady) {
        this.$message.warning(this.$t("roleConfig.configNotReady"));
        return;
      }
      if (this.allFunctions.length === 0) {
        this.fetchAllFunctions().then(() => (this.showFunctionDialog = true));
      } else {
        this.showFunctionDialog = true;
      }
    },
    openKnowledgeBaseDialog() {
      if (!this.capabilityMappingsReady) {
        this.$message.warning(this.$t("roleConfig.configNotReady"));
        return;
      }
      this.fetchAllKnowledgeBases().then((ready) => {
        if (ready) {
          this.showKnowledgeBaseDialog = true;
        } else {
          this.$message.error(this.$t("knowledgeBaseSelector.fetchFailed"));
        }
      });
    },
    openContextProviderDialog() {
      this.$emit('open-context-provider-dialog');
    },
    openAgentSkillDialog() {
      if (!this.effectiveAgentId) {
        this.$message.warning(this.$t("roleConfig.agentSkillSaveAgentFirst"));
        return;
      }
      this.showAgentSkillDialog = true;
    },
    fetchAgentSkillCount() {
      if (!this.effectiveAgentId || typeof Api.agent.getAgentSkills !== "function") return;
      const sequence = ++this.skillCountLoadSeq;
      Api.agent.getAgentSkills(this.effectiveAgentId, (response) => {
        if (sequence !== this.skillCountLoadSeq) return;
        const body = response && response.data ? response.data : response;
        if (!body || Number(body.code) !== 0) return;
        const data = body.data;
        const skills = Array.isArray(data) ? data : (data && Array.isArray(data.list) ? data.list : []);
        this.skillCount = skills.length;
      });
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
      this.capabilityRevision += 1;
      this.$emit('update:currentFunctions', selected);
    },
    handleUpdateKnowledgeBases(selected) {
      this.capabilityRevision += 1;
      const updated = (selected || []).map((item) => ({
        ...item,
        queryPolicy: item.queryPolicy || this.defaultKnowledgeQueryPolicy(),
      }));
      this.$emit('update:currentKnowledgeBases', updated);
    },
    removeKnowledgeBase(id) {
      this.capabilityRevision += 1;
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
        return;
      }
      if ([1, 2].includes(Number(this.form.chatHistoryConf))) {
        this.lastEnabledChatHistoryConf = Number(this.form.chatHistoryConf);
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
    'form.model.ttsModelId': {
      handler(newVal, oldVal) {
        if (newVal) {
          this.$emit('tts-model-changed', newVal);
        }
      },
    },
    form: {
      handler() {
        this.trackFormChanges();
      },
      deep: true,
    },
  },
  beforeDestroy() {
    this.capabilityLoadSeq += 1;
    this.skillCountLoadSeq += 1;
    if (this.noAvailableModelWarningTimer) {
      clearTimeout(this.noAvailableModelWarningTimer);
    }
  },
  mounted() {
    this.fetchAgentSkillCount();
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
  padding: 14px 0 30px;
}

.section-nav {
  position: sticky;
  top: 74px;
  z-index: 6;
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 12px 0 0;
  padding: 8px 10px;
  border: 1px solid #e8ebf5;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 4px 12px rgba(53, 66, 110, 0.06);
  backdrop-filter: blur(8px);
}

.section-nav-title {
  margin: 0 3px 0 2px;
  color: #9aa1b4;
  font-size: 12px;
}

.section-nav button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 30px;
  padding: 0 11px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #5f6881;
  cursor: pointer;
  font-size: 12px;
  transition: background-color 0.2s ease, color 0.2s ease;
}

.section-nav button:hover,
.section-nav button:focus {
  background: #edf1ff;
  color: #5778ff;
  outline: none;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  align-items: start;
  gap: 14px;
}

.form-column {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.config-section {
  scroll-margin-top: 122px;
  padding: 18px 18px 8px;
  border: 1px solid #e5e9f4;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 4px 18px rgba(48, 59, 97, 0.05);
}

.section-heading {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 17px;
  padding-bottom: 13px;
  border-bottom: 1px solid #edf0f6;
}

.section-number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 29px;
  height: 29px;
  border-radius: 9px;
  background: #edf1ff;
  color: #5778ff;
  font-size: 11px;
  font-weight: 700;
}

.section-heading h3 {
  margin: 0;
  color: #2e374f;
  font-size: 15px;
  line-height: 20px;
}

.section-heading p {
  margin: 3px 0 0;
  color: #9299aa;
  font-size: 11px;
  line-height: 17px;
}

.subsection-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 3px 0 8px;
  color: #56617b;
  font-size: 12px;
  font-weight: 600;
}

.subsection-title i {
  color: #6d89ff;
  font-size: 14px;
}

.subsection-title small {
  margin-left: auto;
  color: #a1a7b7;
  font-size: 10px;
  font-weight: 400;
}

.voice-subsection {
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px solid #edf0f6;
}

.form-input {
  width: 100%;
}

.integration-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  color: #606266;
  font-size: 13px;
}

.integration-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.integration-title {
  color: #303133;
  font-weight: 600;
  line-height: 20px;
}

.integration-desc {
  color: #909399;
  font-size: 12px;
  line-height: 18px;
}

.integration-config-item {
  margin-bottom: 10px;
}

.integration-config-item ::v-deep .el-form-item__content {
  min-height: 48px;
  padding: 7px 9px;
  border: 1px solid #e8ebf3;
  border-radius: 9px;
  background: #fafbfe;
}

.role-content-subsection {
  margin-top: 5px;
  padding-top: 12px;
  border-top: 1px solid #edf0f6;
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

.model-select-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
}

.voice-model-config {
  flex-direction: row;
  gap: 9px;
}

.voice-settings-btn {
  flex-shrink: 0;
  margin: 0;
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

.model-row .el-form-item__label {
  font-size: 12px !important;
  color: #3d4566 !important;
  font-weight: 400;
  line-height: 22px;
  padding-bottom: 2px;
}

.capability-config-panel {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  width: 100%;
  margin-top: 12px;
}

.capability-card {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 12px;
  border: 1px solid #e5e9f3;
  border-radius: 10px;
  background: #fafbfe;
}

.capability-card-heading {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.capability-card-heading > div {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.capability-card-heading strong {
  color: #3b445c;
  font-size: 12px;
  line-height: 18px;
}

.capability-card-heading span:not(.capability-icon) {
  overflow: hidden;
  color: #9aa1b2;
  font-size: 10px;
  line-height: 15px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.capability-icon {
  display: inline-flex;
  flex: 0 0 28px;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: #e9eeff;
  color: #5778ff;
}

.capability-icon.knowledge-icon {
  background: #e8f7f1;
  color: #43a37d;
}

.capability-selection {
  grid-row: 2;
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 5px;
  min-width: 0;
  min-height: 24px;
}

.capability-card > .edit-function-btn {
  grid-row: 1;
  grid-column: 2;
  padding: 7px 10px;
  font-size: 11px;
}

.capability-empty {
  color: #a3a8b8;
  font-size: 11px;
}

.function-chip {
  max-width: 160px;
  overflow: hidden;
  border-color: #d9e1ff;
  background: #edf1ff;
  color: #5778ff;
  text-overflow: ellipsis;
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
  align-items: center;
  gap: 10px;
  min-width: 250px;
  justify-content: flex-end;
  margin-top: 8px;
}

.chat-history-label {
  color: #606266;
  font-size: 12px;
  white-space: nowrap;
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

@media (max-width: 1250px) {
  .form-grid {
    grid-template-columns: 1fr;
  }

  .section-nav {
    position: static;
    overflow-x: auto;
  }

  .section-nav-title,
  .section-nav button {
    flex-shrink: 0;
  }
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
