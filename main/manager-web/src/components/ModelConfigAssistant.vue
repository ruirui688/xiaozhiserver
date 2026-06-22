<template>
  <aside class="model-assistant" :class="{ collapsed: !visible }">
    <button class="assistant-toggle" @click="$emit('toggle')">
      <i :class="visible ? 'el-icon-arrow-right' : 'el-icon-magic-stick'"></i>
      <span v-if="!visible">{{ $t("modelConfigAssistant.entry") }}</span>
    </button>

    <div v-if="visible" class="assistant-body">
      <div class="assistant-header">
        <div>
          <h3>{{ $t("modelConfigAssistant.title") }}</h3>
          <p>{{ assistantModelLabel }}</p>
        </div>
        <el-button
          circle
          size="mini"
          icon="el-icon-setting"
          :title="$t('modelConfigAssistant.settings')"
          @click="settingsOpen = !settingsOpen"
        />
      </div>

      <section v-if="settingsOpen" class="assistant-section">
        <div class="assistant-setting-row">
          <el-select
            v-model="selectedLlmModelId"
            filterable
            size="mini"
            :placeholder="$t('modelConfigAssistant.selectLlm')"
            class="assistant-select"
            @focus="loadLlmModels"
          >
            <el-option
              v-for="item in llmModels"
              :key="item.id"
              :label="formatLlmLabel(item)"
              :value="item.id"
            />
          </el-select>
          <el-button
            size="mini"
            type="primary"
            icon="el-icon-check"
            :loading="settingsSaving"
            :title="$t('modelConfigAssistant.saveSettings')"
            @click="saveSettings"
          >
            {{ $t("modelConfigAssistant.saveSettings") }}
          </el-button>
        </div>
      </section>

      <section class="assistant-context" :title="targetLabel">
        <span>{{ context.modelType || "-" }}</span>
        <i></i>
        <span>{{ context.providerCode || $t("modelConfigAssistant.currentPageTarget") }}</span>
        <i></i>
        <strong>{{ targetLabel }}</strong>
      </section>

      <section ref="messagesPane" class="assistant-messages">
        <div v-if="!messages.length" class="assistant-empty">
          <div>{{ $t("modelConfigAssistant.emptyTitle") }}</div>
          <p>{{ $t("modelConfigAssistant.emptyHint") }}</p>
        </div>
        <div
          v-for="(message, index) in messages"
          :key="index"
          class="assistant-message"
          :class="message.role"
        >
          <div class="message-role">
            {{ message.role === "assistant" ? $t("modelConfigAssistant.assistant") : $t("modelConfigAssistant.user") }}
          </div>
          <div class="message-content">{{ message.content }}</div>
        </div>
        <div v-if="loading" class="assistant-message assistant">
          <div class="typing">
            <span></span>
            <span></span>
            <span></span>
          </div>
        </div>
        <div v-if="lastResponse" class="assistant-result">
          <div class="result-head">
            <el-tag size="mini" :type="lastResponse.supported ? 'success' : 'warning'" effect="plain">
              {{
                lastResponse.supported
                  ? $t("modelConfigAssistant.supported")
                  : $t("modelConfigAssistant.unsupported")
              }}
            </el-tag>
            <code v-if="lastResponse.matchedAdapter">{{ lastResponse.matchedAdapter }}</code>
          </div>
          <div v-if="lastResponse.missingFields && lastResponse.missingFields.length" class="result-block">
            <strong>{{ $t("modelConfigAssistant.missingFields") }}</strong>
            <el-tag
              v-for="field in lastResponse.missingFields"
              :key="field"
              size="mini"
              type="danger"
              class="field-tag"
            >
              {{ field }}
            </el-tag>
          </div>
          <div v-if="lastResponse.warnings && lastResponse.warnings.length" class="result-block">
            <strong>{{ $t("modelConfigAssistant.warnings") }}</strong>
            <p v-for="item in lastResponse.warnings" :key="item">{{ item }}</p>
          </div>
          <div v-if="lastResponse.protocolSpecDraft" class="result-block">
            <strong>{{ $t("modelConfigAssistant.protocolSpec") }}</strong>
            <pre>{{ lastResponse.protocolSpecDraft }}</pre>
          </div>
          <div
            v-if="lastResponse.implementationTasks && lastResponse.implementationTasks.length"
            class="result-block"
          >
            <strong>{{ $t("modelConfigAssistant.implementationTasks") }}</strong>
            <ol class="task-list">
              <li v-for="task in lastResponse.implementationTasks" :key="task">{{ task }}</li>
            </ol>
          </div>
          <div
            v-if="lastResponse.integrationArtifacts && lastResponse.integrationArtifacts.length"
            class="result-block"
          >
            <strong>{{ $t("modelConfigAssistant.integrationArtifacts") }}</strong>
            <div class="assistant-actions artifact-actions">
              <el-button
                size="mini"
                type="primary"
                icon="el-icon-folder-add"
                :loading="applyingArtifacts"
                @click="applyArtifacts(false)"
              >
                {{ $t("modelConfigAssistant.applyArtifacts") }}
              </el-button>
              <el-button
                size="mini"
                icon="el-icon-refresh"
                :loading="applyingArtifacts"
                @click="applyArtifacts(true)"
              >
                {{ $t("modelConfigAssistant.applyArtifactsOverwrite") }}
              </el-button>
            </div>
            <div v-if="artifactResults.length" class="artifact-results">
              <p v-for="item in artifactResults" :key="`${item.path}-${item.status}`">
                <el-tag
                  size="mini"
                  :type="item.status === 'written' ? 'success' : item.status === 'skipped' ? 'info' : 'danger'"
                >
                  {{ item.status }}
                </el-tag>
                <code>{{ item.path }}</code>
                <span>{{ item.message }}</span>
              </p>
            </div>
            <el-collapse class="artifact-collapse">
              <el-collapse-item
                v-for="(artifact, artifactIndex) in lastResponse.integrationArtifacts"
                :key="artifactIndex"
                :name="String(artifactIndex)"
              >
                <template slot="title">
                  <span class="artifact-title">{{ artifact.path || artifact.purpose }}</span>
                </template>
                <div class="artifact-meta">
                  <p v-if="artifact.path">
                    <span>{{ $t("modelConfigAssistant.artifactPath") }}</span>
                    <code>{{ artifact.path }}</code>
                  </p>
                  <p v-if="artifact.purpose">
                    <span>{{ $t("modelConfigAssistant.artifactPurpose") }}</span>
                    <em>{{ artifact.purpose }}</em>
                  </p>
                  <p v-if="artifact.language">
                    <span>{{ $t("modelConfigAssistant.artifactLanguage") }}</span>
                    <em>{{ artifact.language }}</em>
                  </p>
                </div>
                <pre class="artifact-content">{{ artifact.content }}</pre>
                <el-button size="mini" icon="el-icon-document-copy" @click="copyArtifact(artifact)">
                  {{ $t("modelConfigAssistant.copyArtifact") }}
                </el-button>
              </el-collapse-item>
            </el-collapse>
          </div>
          <div v-if="lastResponse.draftConfigJson" class="result-block">
            <strong>{{ $t("modelConfigAssistant.draftConfig") }}</strong>
            <pre>{{ formatJson(lastResponse.draftConfigJson) }}</pre>
            <div class="assistant-actions">
              <el-button size="mini" type="success" @click="$emit('apply-draft', lastResponse)">
                {{ $t("modelConfigAssistant.applyDraft") }}
              </el-button>
              <el-button size="mini" @click="copyDraft">
                {{ $t("modelConfigAssistant.copyDraft") }}
              </el-button>
            </div>
          </div>
        </div>
      </section>

      <section class="assistant-composer">
        <el-collapse-transition>
          <div v-if="contextOpen" class="assistant-context-extra">
            <el-input
              v-model="documentUrl"
              size="mini"
              :placeholder="$t('modelConfigAssistant.documentUrl')"
            />
            <el-input
              v-model="documentText"
              type="textarea"
              :rows="3"
              :placeholder="$t('modelConfigAssistant.documentText')"
              class="assistant-textarea"
            />
          </div>
        </el-collapse-transition>
        <div class="composer-box">
          <el-input
            v-model="prompt"
            type="textarea"
            :rows="2"
            resize="none"
            :placeholder="$t('modelConfigAssistant.prompt')"
            @keydown.native.enter.exact.prevent="send"
          />
          <div class="composer-actions">
            <el-button
              size="mini"
              icon="el-icon-document"
              :title="$t('modelConfigAssistant.contextToggle')"
              @click="contextOpen = !contextOpen"
            >
              {{ $t("modelConfigAssistant.contextToggle") }}
            </el-button>
            <el-button
              type="primary"
              size="mini"
              icon="el-icon-position"
              :loading="loading"
              @click="send"
            >
              {{ $t("modelConfigAssistant.send") }}
            </el-button>
          </div>
        </div>
      </section>
    </div>
  </aside>
</template>

<script>
import Api from "@/apis/api";

export default {
  name: "ModelConfigAssistant",
  props: {
    visible: { type: Boolean, default: false },
    context: {
      type: Object,
      default: () => ({}),
    },
  },
  data() {
    return {
      loading: false,
      settingsSaving: false,
      applyingArtifacts: false,
      llmModels: [],
      selectedLlmModelId: "",
      savedLlmModelName: "",
      prompt: "",
      documentUrl: "",
      documentText: "",
      settingsOpen: false,
      contextOpen: false,
      messages: [],
      lastResponse: null,
      artifactResults: [],
    };
  },
  watch: {
    visible(val) {
      if (val) {
        this.loadSettings();
        this.loadLlmModels();
      }
    },
    context: {
      deep: true,
      handler() {
        if (!this.prompt && this.context && this.context.testMessage) {
          this.prompt = this.$t("modelConfigAssistant.defaultFailurePrompt");
        }
      },
    },
  },
  computed: {
    assistantModelLabel() {
      const selected = this.llmModels.find((item) => item.id === this.selectedLlmModelId);
      return (selected && this.formatLlmLabel(selected)) || this.savedLlmModelName || this.$t("modelConfigAssistant.noAssistantModel");
    },
    targetLabel() {
      return this.context.modelName || this.$t("modelConfigAssistant.currentPageTarget");
    },
  },
  methods: {
    loadSettings() {
      Api.model.getAssistantSettings(({ data }) => {
        if (data.code === 0 && data.data) {
          this.selectedLlmModelId = data.data.llmModelId || "";
          this.savedLlmModelName = data.data.llmModelName || "";
          this.settingsOpen = !this.selectedLlmModelId;
        }
      });
    },
    loadLlmModels() {
      Api.model.getLlmModelCodeList("", ({ data }) => {
        if (data.code === 0) {
          this.llmModels = data.data || [];
        }
      });
    },
    saveSettings() {
      this.settingsSaving = true;
      Api.model.updateAssistantSettings({ llmModelId: this.selectedLlmModelId }, ({ data }) => {
        this.settingsSaving = false;
        if (data.code === 0) {
          this.$message.success(this.$t("modelConfigAssistant.settingsSaved"));
          this.savedLlmModelName = data.data?.llmModelName || this.assistantModelLabel;
          this.settingsOpen = false;
        } else {
          this.$message.error(data.msg || this.$t("modelConfigAssistant.settingsFailed"));
        }
      });
    },
    send() {
      if (!this.selectedLlmModelId) {
        this.$message.warning(this.$t("modelConfigAssistant.selectLlmFirst"));
        return;
      }
      if (!this.prompt.trim()) {
        this.$message.warning(this.$t("modelConfigAssistant.enterPromptFirst"));
        return;
      }

      const content = this.prompt.trim();
      this.prompt = "";
      this.messages.push({ role: "user", content });
      this.lastResponse = null;
      this.artifactResults = [];
      this.loading = true;
      this.scrollMessagesToBottom();
      Api.model.chatWithAssistant(
        {
          message: content,
          modelType: this.context.modelType,
          modelId: this.context.modelId,
          providerCode: this.context.providerCode,
          configJson: this.context.configJson || {},
          docLink: this.context.docLink,
          documentText: this.documentText,
          documentUrl: this.documentUrl,
          testMessage: this.context.testMessage,
          pageContext: this.context.pageContext || "model-config",
        },
        ({ data }) => {
          this.loading = false;
          if (data.code === 0 && data.data) {
            this.lastResponse = data.data;
            this.messages.push({
              role: "assistant",
              content: data.data.answer || this.$t("modelConfigAssistant.emptyAnswer"),
            });
            this.$nextTick(this.scrollMessagesToBottom);
          } else {
            this.messages.push({
              role: "assistant",
              content: data.msg || this.$t("modelConfigAssistant.requestFailed"),
            });
            this.$nextTick(this.scrollMessagesToBottom);
          }
        }
      );
    },
    scrollMessagesToBottom() {
      this.$nextTick(() => {
        const pane = this.$refs.messagesPane;
        if (pane) {
          pane.scrollTop = pane.scrollHeight;
        }
      });
    },
    copyDraft() {
      const text = this.formatJson(this.lastResponse && this.lastResponse.draftConfigJson);
      if (navigator.clipboard) {
        navigator.clipboard.writeText(text);
        this.$message.success(this.$t("modelConfigAssistant.copied"));
      }
    },
    copyArtifact(artifact) {
      const text = artifact && artifact.content ? artifact.content : "";
      if (!text) {
        return;
      }
      if (navigator.clipboard) {
        navigator.clipboard.writeText(text);
        this.$message.success(this.$t("modelConfigAssistant.artifactCopied"));
      }
    },
    applyArtifacts(overwrite) {
      const artifacts = (this.lastResponse && this.lastResponse.integrationArtifacts) || [];
      if (!artifacts.length) {
        this.$message.warning(this.$t("modelConfigAssistant.noArtifacts"));
        return;
      }
      this.applyingArtifacts = true;
      Api.model.applyAssistantArtifacts({ artifacts, overwrite }, ({ data }) => {
        this.applyingArtifacts = false;
        if (data.code === 0 && data.data) {
          this.artifactResults = data.data.results || [];
          const failed = this.artifactResults.some((item) => item.status === "failed");
          this.$message[failed ? "warning" : "success"](
            failed
              ? this.$t("modelConfigAssistant.artifactsAppliedWithIssues")
              : this.$t("modelConfigAssistant.artifactsApplied")
          );
        } else {
          this.$message.error(data.msg || this.$t("modelConfigAssistant.artifactsApplyFailed"));
        }
      });
    },
    formatJson(value) {
      try {
        return JSON.stringify(value || {}, null, 2);
      } catch (e) {
        return "{}";
      }
    },
    formatLlmLabel(item) {
      return item.type ? `${item.modelName} (${item.type})` : item.modelName;
    },
  },
};
</script>

<style scoped>
.model-assistant {
  width: 460px;
  background: #ffffff;
  position: fixed;
  right: 24px;
  top: 128px;
  bottom: 84px;
  z-index: 1800;
  border: 1px solid #e8edf4;
  border-radius: 14px;
  box-shadow: 0 18px 48px rgba(24, 39, 75, 0.16);
  transition: transform 0.2s ease, opacity 0.2s ease;
}

.model-assistant.collapsed {
  width: auto;
  top: auto;
  bottom: 88px;
  right: 28px;
  border: none;
  box-shadow: none;
  background: transparent;
}

.assistant-toggle {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 2;
  border: 1px solid #d8e1ee;
  background: #f7f9fc;
  color: #2d5f9a;
  height: 30px;
  border-radius: 6px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 8px;
}

.model-assistant.collapsed .assistant-toggle {
  position: static;
  height: 40px;
  padding: 0 14px;
  color: #ffffff;
  border: none;
  background: #409eff;
  box-shadow: 0 10px 24px rgba(64, 158, 255, 0.28);
}

.assistant-body {
  height: 100%;
  padding: 52px 18px 16px;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}

.assistant-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.assistant-header h3 {
  margin: 0;
  color: #1f2d3d;
  font-size: 20px;
  line-height: 1.2;
}

.assistant-header p {
  margin: 6px 0 0;
  color: #718096;
  font-size: 12px;
  max-width: 330px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assistant-section {
  margin-top: 14px;
}

.assistant-section label {
  display: block;
  color: #4a5568;
  font-size: 12px;
  margin-bottom: 6px;
}

.assistant-setting-row {
  display: flex;
  gap: 8px;
}

.assistant-select {
  flex: 1;
}

.assistant-context {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  color: #718096;
  font-size: 12px;
  min-height: 24px;
  border-bottom: 1px solid #edf2f7;
  padding-bottom: 10px;
  overflow: hidden;
}

.assistant-context i {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #cbd5e0;
  flex: 0 0 3px;
}

.assistant-context strong {
  min-width: 0;
  color: #2d3748;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.assistant-textarea {
  margin-top: 8px;
}

.assistant-messages {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  margin-top: 16px;
  padding: 2px 4px 12px 0;
}

.assistant-empty {
  border: none;
  border-radius: 14px;
  padding: 26px 20px;
  color: #4a5568;
  background: #f7faff;
  text-align: center;
}

.assistant-empty div {
  font-weight: 600;
  margin-bottom: 6px;
}

.assistant-empty p {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: #718096;
}

.assistant-message {
  border-radius: 16px;
  padding: 12px 14px;
  margin-bottom: 12px;
  background: #f5f7fb;
  max-width: 86%;
  width: fit-content;
}

.assistant-message.user {
  background: #e8f2ff;
  margin-left: auto;
  border-bottom-right-radius: 5px;
}

.assistant-message.assistant {
  border-bottom-left-radius: 5px;
}

.message-role {
  font-size: 12px;
  color: #718096;
  margin-bottom: 5px;
}

.message-content {
  white-space: pre-wrap;
  color: #2d3748;
  font-size: 14px;
  line-height: 1.6;
}

.typing {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 18px;
}

.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #9fb3c8;
  animation: typingPulse 1s infinite ease-in-out;
}

.typing span:nth-child(2) {
  animation-delay: 0.15s;
}

.typing span:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes typingPulse {
  0%, 80%, 100% {
    opacity: 0.35;
    transform: translateY(0);
  }
  40% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

.assistant-result {
  border: 1px solid #edf2f7;
  border-radius: 12px;
  padding: 10px;
  margin: 2px 0 12px;
  background: #ffffff;
  box-shadow: 0 8px 24px rgba(24, 39, 75, 0.05);
}

.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.result-block {
  border-top: 1px solid #edf2f7;
  padding-top: 10px;
  margin-top: 10px;
  font-size: 13px;
}

.result-block p {
  margin: 6px 0 0;
  color: #4a5568;
  line-height: 1.5;
}

.result-block pre {
  max-height: 120px;
  overflow: auto;
  background: #f7fafc;
  border: 1px solid #edf2f7;
  border-radius: 6px;
  padding: 8px;
  white-space: pre-wrap;
}

.task-list {
  margin: 8px 0 0;
  padding-left: 18px;
  color: #4a5568;
  line-height: 1.55;
}

.task-list li {
  margin-top: 6px;
}

.artifact-collapse {
  margin-top: 8px;
  border: none;
}

.artifact-title {
  display: block;
  max-width: 340px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #2d3748;
  font-size: 12px;
}

.artifact-meta {
  margin-bottom: 8px;
}

.artifact-meta p {
  margin: 4px 0;
}

.artifact-meta span {
  display: inline-block;
  width: 64px;
  color: #718096;
}

.artifact-meta code,
.artifact-meta em {
  font-style: normal;
  color: #2d3748;
  word-break: break-all;
}

.artifact-content {
  max-height: 220px !important;
  word-break: break-word;
}

.field-tag {
  margin: 6px 6px 0 0;
}

.assistant-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.artifact-actions {
  flex-wrap: wrap;
}

.artifact-results {
  margin-top: 8px;
  border: 1px solid #edf2f7;
  border-radius: 6px;
  padding: 6px 8px;
  background: #fbfdff;
}

.artifact-results p {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 4px 0;
  line-height: 1.45;
}

.artifact-results code {
  max-width: 180px;
  color: #2d3748;
  word-break: break-all;
}

.artifact-results span {
  color: #718096;
}

.assistant-composer {
  flex: 0 0 auto;
  margin-top: 0;
  border-top: 1px solid #edf2f7;
  padding-top: 14px;
  background: #ffffff;
}

.assistant-context-extra {
  margin-bottom: 8px;
}

.composer-box {
  border: 1px solid #d8e1ee;
  border-radius: 14px;
  padding: 10px;
  background: #ffffff;
}

.composer-box ::v-deep .el-textarea__inner {
  border: none;
  padding: 4px;
  box-shadow: none;
}

.composer-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}
</style>
