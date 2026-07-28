<template>
  <el-dialog
    :title="$t('agentSkill.title')"
    :visible="visible"
    width="1080px"
    top="5vh"
    append-to-body
    class="agent-skill-dialog"
    @open="loadSkills"
    @close="closeDialog"
  >
    <el-alert
      :title="$t('agentSkill.restartNotice')"
      type="info"
      :closable="false"
      show-icon
      class="restart-notice"
    />

    <div class="skill-layout" v-loading="loading">
      <section class="skill-list-panel">
        <div class="panel-heading">
          <div>
            <strong>{{ $t('agentSkill.listTitle') }}</strong>
            <span>{{ $t('agentSkill.count', { count: skills.length }) }}</span>
          </div>
          <el-button
            type="primary"
            size="small"
            icon="el-icon-plus"
            :disabled="skills.length >= limits.maxSkills"
            @click="startCreate"
          >{{ $t('agentSkill.create') }}</el-button>
        </div>

        <el-empty v-if="!loading && skills.length === 0" :description="$t('agentSkill.empty')" />
        <div v-else class="skill-list">
          <article
            v-for="skill in sortedSkills"
            :key="skill.id"
            class="skill-list-item"
            :class="{ active: draft && draft.id === skill.id }"
            @click="startEdit(skill)"
          >
            <div class="skill-list-main">
              <div class="skill-name-row">
                <strong>{{ skill.skillName }}</strong>
                <el-tag size="mini" effect="plain">P{{ skill.priority || 0 }}</el-tag>
              </div>
              <span>{{ $t('agentSkill.ruleCount', { count: skill.triggerRules.length }) }}</span>
            </div>
            <div class="skill-list-actions" @click.stop>
              <el-switch
                :value="skill.enabled"
                :disabled="updatingId === skill.id"
                @change="toggleSkill(skill, $event)"
              />
              <el-button type="text" icon="el-icon-edit" @click="startEdit(skill)" />
              <el-button type="text" class="delete-button" icon="el-icon-delete" @click="removeSkill(skill)" />
            </div>
          </article>
        </div>
        <p v-if="skills.length >= limits.maxSkills" class="limit-warning">
          {{ $t('agentSkill.skillLimit', { max: limits.maxSkills }) }}
        </p>
      </section>

      <section class="skill-editor-panel">
        <template v-if="draft">
          <div class="panel-heading editor-heading">
            <div>
              <strong>{{ draft.id ? $t('agentSkill.editTitle') : $t('agentSkill.createTitle') }}</strong>
              <span>{{ $t('agentSkill.matchRuleHint') }}</span>
            </div>
          </div>

          <el-form label-position="top" class="skill-form" @submit.native.prevent>
            <div class="basic-fields">
              <el-form-item :label="$t('agentSkill.name')" required>
                <el-input
                  v-model="draft.skillName"
                  :maxlength="limits.maxNameLength"
                  show-word-limit
                  :placeholder="$t('agentSkill.namePlaceholder')"
                />
              </el-form-item>
              <el-form-item :label="$t('agentSkill.enabled')">
                <el-switch v-model="draft.enabled" />
              </el-form-item>
              <el-form-item :label="$t('agentSkill.priority')">
                <el-input-number v-model="draft.priority" :min="0" :max="9999" controls-position="right" />
              </el-form-item>
              <el-form-item :label="$t('agentSkill.sort')">
                <el-input-number v-model="draft.sort" :min="0" :max="9999" controls-position="right" />
              </el-form-item>
            </div>

            <el-form-item required>
              <div slot="label" class="rule-label">
                <span>{{ $t('agentSkill.rules') }}</span>
                <span>{{ $t('agentSkill.ruleLimit', { count: draft.triggerRules.length, max: limits.maxRules }) }}</span>
              </div>
              <div class="rule-list">
                <article v-for="(rule, ruleIndex) in draft.triggerRules" :key="rule.localId" class="rule-card">
                  <header>
                    <el-select v-model="rule.type" size="small" @change="changeRuleType(rule)">
                      <el-option :label="$t('agentSkill.phrase')" value="phrase" />
                      <el-option :label="$t('agentSkill.allKeywords')" value="all_keywords" />
                    </el-select>
                    <span>{{ rule.type === 'phrase' ? $t('agentSkill.phraseHint') : $t('agentSkill.keywordHint') }}</span>
                    <el-button type="text" class="delete-button" icon="el-icon-delete" @click="removeRule(ruleIndex)" />
                  </header>
                  <div v-if="rule.type === 'phrase'" class="term-row">
                    <el-input
                      v-model="rule.terms[0]"
                      :maxlength="limits.maxTermLength"
                      show-word-limit
                      :placeholder="$t('agentSkill.phrasePlaceholder')"
                    />
                  </div>
                  <div v-else class="keyword-list">
                    <div v-for="(term, termIndex) in rule.terms" :key="`${rule.localId}-${termIndex}`" class="term-row">
                      <el-input
                        v-model="rule.terms[termIndex]"
                        :maxlength="limits.maxTermLength"
                        :placeholder="$t('agentSkill.keywordPlaceholder', { index: termIndex + 1 })"
                      />
                      <el-button
                        icon="el-icon-minus"
                        circle
                        size="mini"
                        :disabled="rule.terms.length <= limits.minKeywordTerms"
                        @click="removeTerm(rule, termIndex)"
                      />
                    </div>
                    <el-button
                      type="text"
                      icon="el-icon-plus"
                      :disabled="rule.terms.length >= limits.maxKeywordTerms"
                      @click="addTerm(rule)"
                    >{{ $t('agentSkill.addKeyword') }}</el-button>
                  </div>
                </article>
              </div>
              <div class="rule-actions">
                <el-button
                  size="small"
                  icon="el-icon-plus"
                  :disabled="draft.triggerRules.length >= limits.maxRules"
                  @click="addRule('phrase')"
                >{{ $t('agentSkill.addPhrase') }}</el-button>
                <el-button
                  size="small"
                  icon="el-icon-plus"
                  :disabled="draft.triggerRules.length >= limits.maxRules"
                  @click="addRule('all_keywords')"
                >{{ $t('agentSkill.addKeywordGroup') }}</el-button>
              </div>
            </el-form-item>

            <el-form-item :label="$t('agentSkill.responseText')" required>
              <el-input
                v-model="draft.responseText"
                type="textarea"
                :rows="5"
                resize="vertical"
                :maxlength="limits.maxResponseLength"
                show-word-limit
                :placeholder="$t('agentSkill.responsePlaceholder')"
              />
            </el-form-item>

            <el-alert
              v-if="validationError"
              :title="validationError"
              type="error"
              :closable="false"
              show-icon
              class="validation-alert"
            />

            <div class="editor-actions">
              <el-button @click="cancelEdit">{{ $t('agentSkill.cancelEdit') }}</el-button>
              <el-button type="primary" :loading="saving" @click="saveDraft">{{ $t('agentSkill.save') }}</el-button>
            </div>
          </el-form>
        </template>
        <el-empty v-else :description="$t('agentSkill.selectHint')" />
      </section>
    </div>

    <section class="match-preview">
      <div class="preview-heading">
        <div>
          <strong>{{ $t('agentSkill.previewTitle') }}</strong>
          <span>{{ $t('agentSkill.previewHint') }}</span>
        </div>
        <el-tag v-if="previewResult" type="success">{{ $t('agentSkill.matched') }}</el-tag>
        <el-tag v-else-if="previewInput" type="info">{{ $t('agentSkill.notMatched') }}</el-tag>
      </div>
      <el-input
        v-model="previewInput"
        clearable
        :placeholder="$t('agentSkill.previewPlaceholder')"
      >
        <i slot="prefix" class="el-input__icon el-icon-search" />
      </el-input>
      <div v-if="previewResult" class="preview-result">
        <strong>{{ previewResult.skill.skillName }}</strong>
        <span>{{ previewRuleDescription }}</span>
      </div>
      <p v-else-if="previewInput" class="preview-empty">{{ $t('agentSkill.fallbackHint') }}</p>
    </section>

    <div slot="footer">
      <el-button @click="closeDialog">{{ $t('agentSkill.close') }}</el-button>
    </div>
  </el-dialog>
</template>

<script>
import Api from "@/apis/api";
import {
  describeSkillMatch,
  findMatchedSkill,
  normalizeSkillText,
} from "@/utils/agentSkillMatcher";

const LIMITS = Object.freeze({
  maxSkills: 50,
  maxRules: 20,
  maxNameLength: 64,
  minTermLength: 2,
  maxTermLength: 100,
  minKeywordTerms: 2,
  maxKeywordTerms: 8,
  maxResponseLength: 3000,
});

let localRuleId = 0;

function nextRuleId() {
  localRuleId += 1;
  return `skill-rule-${localRuleId}`;
}

function cloneRule(rule = {}) {
  const type = rule.type === "all_keywords" ? "all_keywords" : "phrase";
  let terms = Array.isArray(rule.terms) ? rule.terms.map((term) => String(term || "")) : [];
  if (type === "phrase") terms = [terms[0] || ""];
  if (type === "all_keywords") {
    terms = terms.slice(0, LIMITS.maxKeywordTerms);
    while (terms.length < LIMITS.minKeywordTerms) terms.push("");
  }
  return { type, terms, localId: nextRuleId() };
}

function cloneSkill(skill = {}) {
  return {
    id: skill.id,
    skillName: String(skill.skillName || ""),
    enabled: skill.enabled !== false,
    triggerRules: (Array.isArray(skill.triggerRules) ? skill.triggerRules : []).map(cloneRule),
    responseText: String(skill.responseText || ""),
    priority: Number(skill.priority || 0),
    sort: Number(skill.sort || 0),
  };
}

function skillPayload(skill) {
  return {
    skillName: String(skill.skillName || "").trim(),
    enabled: skill.enabled !== false,
    triggerRules: skill.triggerRules.map((rule) => ({
      type: rule.type,
      terms: rule.terms.map((term) => String(term || "").trim()),
    })),
    responseText: String(skill.responseText || "").trim(),
    priority: Number(skill.priority || 0),
    sort: Number(skill.sort || 0),
  };
}

export default {
  name: "AgentSkillDialog",
  props: {
    visible: { type: Boolean, default: false },
    agentId: { type: String, required: true },
  },
  data() {
    return {
      limits: LIMITS,
      skills: [],
      draft: null,
      loading: false,
      saving: false,
      updatingId: null,
      validationError: "",
      previewInput: "",
      loadSequence: 0,
    };
  },
  computed: {
    sortedSkills() {
      return [...this.skills].sort((left, right) =>
        Number(left.sort || 0) - Number(right.sort || 0) ||
        Number(right.priority || 0) - Number(left.priority || 0) ||
        String(left.id).localeCompare(String(right.id), "zh-CN", { numeric: true })
      );
    },
    previewSkills() {
      const items = this.skills.map(cloneSkill);
      if (!this.draft) return items;
      const candidate = { ...cloneSkill(this.draft), triggerRules: skillPayload(this.draft).triggerRules };
      if (candidate.id !== undefined && candidate.id !== null) {
        const index = items.findIndex((item) => String(item.id) === String(candidate.id));
        if (index >= 0) items.splice(index, 1, candidate);
      } else if (candidate.skillName) {
        candidate.id = "draft";
        items.push(candidate);
      }
      return items;
    },
    previewResult() {
      return findMatchedSkill(this.previewSkills, this.previewInput);
    },
    previewRuleDescription() {
      if (!this.previewResult) return "";
      const key = this.previewResult.match.ruleType === "phrase"
        ? "agentSkill.previewPhraseBasis"
        : "agentSkill.previewKeywordBasis";
      return this.$t(key, { terms: describeSkillMatch(this.previewResult) });
    },
  },
  methods: {
    responseBody(response) {
      return response && response.data ? response.data : response;
    },
    isSuccess(response) {
      const body = this.responseBody(response);
      return body && Number(body.code) === 0;
    },
    errorMessage(response, fallbackKey) {
      const body = this.responseBody(response);
      return (body && (body.msg || body.message)) || this.$t(fallbackKey);
    },
    extractSkills(response) {
      const body = this.responseBody(response);
      const data = body && body.data;
      const list = Array.isArray(data) ? data : (data && Array.isArray(data.list) ? data.list : []);
      return list.map(cloneSkill);
    },
    emitCount() {
      this.$emit("count-change", this.skills.length);
    },
    loadSkills() {
      if (!this.agentId || this.loading) return;
      const sequence = ++this.loadSequence;
      this.loading = true;
      Api.agent.getAgentSkills(this.agentId, (response) => {
        if (sequence !== this.loadSequence) return;
        this.loading = false;
        if (!this.isSuccess(response)) {
          this.$message.error(this.errorMessage(response, "agentSkill.loadFailed"));
          return;
        }
        this.skills = this.extractSkills(response);
        this.emitCount();
        if (this.draft && this.draft.id) {
          const fresh = this.skills.find((item) => String(item.id) === String(this.draft.id));
          this.draft = fresh ? cloneSkill(fresh) : null;
        }
      }, (error) => {
        if (sequence !== this.loadSequence) return;
        this.loading = false;
        this.$message.error(this.errorMessage(error, "agentSkill.loadFailed"));
      });
    },
    closeDialog() {
      this.loadSequence += 1;
      this.loading = false;
      this.draft = null;
      this.validationError = "";
      this.$emit("update:visible", false);
    },
    startCreate() {
      if (this.skills.length >= LIMITS.maxSkills) return;
      this.validationError = "";
      this.draft = cloneSkill({
        enabled: true,
        triggerRules: [{ type: "all_keywords", terms: ["", ""] }],
        priority: 0,
        sort: this.skills.length,
      });
    },
    startEdit(skill) {
      this.validationError = "";
      this.draft = cloneSkill(skill);
    },
    cancelEdit() {
      this.validationError = "";
      this.draft = null;
    },
    addRule(type) {
      if (!this.draft || this.draft.triggerRules.length >= LIMITS.maxRules) return;
      this.draft.triggerRules.push(cloneRule({
        type,
        terms: type === "phrase" ? [""] : ["", ""],
      }));
    },
    removeRule(index) {
      this.draft.triggerRules.splice(index, 1);
    },
    changeRuleType(rule) {
      rule.terms = rule.type === "phrase" ? [rule.terms[0] || ""] : [rule.terms[0] || "", ""];
    },
    addTerm(rule) {
      if (rule.terms.length < LIMITS.maxKeywordTerms) rule.terms.push("");
    },
    removeTerm(rule, index) {
      if (rule.terms.length > LIMITS.minKeywordTerms) rule.terms.splice(index, 1);
    },
    validateDraft(draft = this.draft) {
      if (!draft) return this.$t("agentSkill.validation.noDraft");
      const name = String(draft.skillName || "").trim();
      if (!name) return this.$t("agentSkill.validation.nameRequired");
      if (name.length > LIMITS.maxNameLength) return this.$t("agentSkill.validation.nameTooLong", { max: LIMITS.maxNameLength });
      if (!Array.isArray(draft.triggerRules) || draft.triggerRules.length === 0) return this.$t("agentSkill.validation.ruleRequired");
      if (draft.triggerRules.length > LIMITS.maxRules) return this.$t("agentSkill.validation.ruleTooMany", { max: LIMITS.maxRules });
      for (let index = 0; index < draft.triggerRules.length; index += 1) {
        const rule = draft.triggerRules[index];
        const terms = Array.isArray(rule.terms) ? rule.terms.map((term) => String(term || "").trim()) : [];
        const expected = rule.type === "phrase" ? 1 : null;
        if (!expected && rule.type !== "all_keywords") return this.$t("agentSkill.validation.ruleType", { index: index + 1 });
        if (expected && terms.length !== expected) return this.$t("agentSkill.validation.phraseCount", { index: index + 1 });
        if (rule.type === "all_keywords" && (terms.length < LIMITS.minKeywordTerms || terms.length > LIMITS.maxKeywordTerms)) {
          return this.$t("agentSkill.validation.keywordCount", { index: index + 1, min: LIMITS.minKeywordTerms, max: LIMITS.maxKeywordTerms });
        }
        for (const term of terms) {
          if (term.length < LIMITS.minTermLength || term.length > LIMITS.maxTermLength || !normalizeSkillText(term)) {
            return this.$t("agentSkill.validation.termLength", { index: index + 1, min: LIMITS.minTermLength, max: LIMITS.maxTermLength });
          }
        }
        const normalizedTerms = terms.map(normalizeSkillText);
        if (new Set(normalizedTerms).size !== normalizedTerms.length) {
          return this.$t("agentSkill.validation.duplicateTerm", { index: index + 1 });
        }
      }
      const responseText = String(draft.responseText || "").trim();
      if (!responseText) return this.$t("agentSkill.validation.responseRequired");
      if (responseText.length > LIMITS.maxResponseLength) return this.$t("agentSkill.validation.responseTooLong", { max: LIMITS.maxResponseLength });
      return "";
    },
    saveDraft() {
      const error = this.validateDraft();
      this.validationError = error;
      if (error || this.saving) return;
      if (!this.draft.id && this.skills.length >= LIMITS.maxSkills) {
        this.validationError = this.$t("agentSkill.skillLimit", { max: LIMITS.maxSkills });
        return;
      }
      const payload = skillPayload(this.draft);
      const done = (response) => {
        this.saving = false;
        if (!this.isSuccess(response)) {
          this.$message.error(this.errorMessage(response, "agentSkill.saveFailed"));
          return;
        }
        this.$message.success(this.$t("agentSkill.saveSuccess"));
        this.draft = null;
        this.loadSkills();
      };
      const failed = (errorResponse) => {
        this.saving = false;
        this.$message.error(this.errorMessage(errorResponse, "agentSkill.saveFailed"));
      };
      this.saving = true;
      if (this.draft.id !== undefined && this.draft.id !== null) {
        Api.agent.updateAgentSkill(this.agentId, this.draft.id, payload, done, failed);
      } else {
        Api.agent.createAgentSkill(this.agentId, payload, done, failed);
      }
    },
    toggleSkill(skill, enabled) {
      if (this.updatingId !== null) return;
      this.updatingId = skill.id;
      const payload = { ...skillPayload(skill), enabled };
      Api.agent.updateAgentSkill(this.agentId, skill.id, payload, (response) => {
        this.updatingId = null;
        if (!this.isSuccess(response)) {
          this.$message.error(this.errorMessage(response, "agentSkill.toggleFailed"));
          return;
        }
        skill.enabled = enabled;
        if (this.draft && String(this.draft.id) === String(skill.id)) this.draft.enabled = enabled;
      }, (error) => {
        this.updatingId = null;
        this.$message.error(this.errorMessage(error, "agentSkill.toggleFailed"));
      });
    },
    removeSkill(skill) {
      this.$confirm(
        this.$t("agentSkill.deleteConfirm", { name: skill.skillName }),
        this.$t("agentSkill.deleteTitle"),
        { type: "warning" }
      ).then(() => {
        Api.agent.deleteAgentSkill(this.agentId, skill.id, (response) => {
          if (!this.isSuccess(response)) {
            this.$message.error(this.errorMessage(response, "agentSkill.deleteFailed"));
            return;
          }
          if (this.draft && String(this.draft.id) === String(skill.id)) this.draft = null;
          this.$message.success(this.$t("agentSkill.deleteSuccess"));
          this.loadSkills();
        }, (error) => {
          this.$message.error(this.errorMessage(error, "agentSkill.deleteFailed"));
        });
      }).catch(() => {});
    },
  },
};
</script>

<style lang="scss" scoped>
.restart-notice { margin-bottom: 14px; }
.skill-layout { display: grid; grid-template-columns: 330px 1fr; gap: 16px; min-height: 430px; }
.skill-list-panel, .skill-editor-panel, .match-preview { border: 1px solid #e4e7ed; border-radius: 10px; background: #fff; }
.skill-list-panel, .skill-editor-panel { padding: 14px; }
.panel-heading, .preview-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.panel-heading > div, .preview-heading > div { display: flex; flex-direction: column; min-width: 0; }
.panel-heading strong, .preview-heading strong { color: #303133; line-height: 22px; }
.panel-heading span, .preview-heading span { color: #909399; font-size: 12px; line-height: 18px; }
.skill-list { max-height: 460px; overflow-y: auto; }
.skill-list-item { display: flex; align-items: center; gap: 8px; padding: 11px 10px; margin-bottom: 8px; border: 1px solid #ebeef5; border-radius: 8px; cursor: pointer; transition: .2s; }
.skill-list-item:hover, .skill-list-item.active { border-color: #a9c9ff; background: #f4f8ff; }
.skill-list-main { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.skill-list-main > span { color: #909399; font-size: 12px; margin-top: 3px; }
.skill-name-row { display: flex; align-items: center; gap: 6px; min-width: 0; }
.skill-name-row strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.skill-list-actions { display: flex; align-items: center; gap: 2px; }
.delete-button { color: #f56c6c; }
.limit-warning, .preview-empty { color: #e6a23c; font-size: 12px; }
.basic-fields { display: grid; grid-template-columns: minmax(220px, 1fr) 70px 105px 105px; gap: 10px; align-items: start; }
.basic-fields ::v-deep .el-input-number { width: 100%; }
.rule-label { display: flex; justify-content: space-between; width: 100%; }
.rule-label span:last-child { color: #909399; font-size: 12px; }
.rule-list { display: flex; flex-direction: column; gap: 10px; }
.rule-card { border: 1px solid #e4e7ed; border-radius: 8px; padding: 10px; background: #fafafa; }
.rule-card header { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.rule-card header .el-select { width: 130px; }
.rule-card header > span { flex: 1; color: #909399; font-size: 12px; }
.keyword-list, .rule-actions { display: flex; flex-direction: column; gap: 7px; }
.term-row { display: flex; align-items: center; gap: 7px; }
.rule-actions { flex-direction: row; margin-top: 10px; }
.validation-alert { margin-top: 8px; }
.editor-actions { display: flex; justify-content: flex-end; gap: 8px; }
.match-preview { padding: 14px; margin-top: 16px; }
.preview-result { margin-top: 10px; padding: 10px 12px; border-radius: 7px; background: #f0f9eb; display: flex; flex-direction: column; gap: 4px; }
.preview-result span { color: #67c23a; font-size: 12px; }
@media (max-width: 900px) {
  .skill-layout { grid-template-columns: 1fr; }
  .basic-fields { grid-template-columns: 1fr 80px 1fr 1fr; }
}
</style>
