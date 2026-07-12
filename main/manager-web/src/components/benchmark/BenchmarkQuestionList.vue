<template>
  <div v-if="normalizedQuestions.length" class="performance-custom-question-panel">
    <div class="performance-question-panel-head">
      <div class="performance-field-label">
        {{ $t("modelConfig.performanceSampleUsed") }}：{{ normalizedQuestions.length }} 条
      </div>
      <el-button
        v-if="hiddenQuestionCount > 0 || expanded"
        type="text"
        size="mini"
        @click="expanded = !expanded"
      >
        {{ expanded ? "收起" : "展开全部" }}
      </el-button>
    </div>
    <el-tag
      v-for="(question, index) in visibleQuestions"
      :key="`${index}-${question}`"
      size="mini"
      effect="plain"
      class="benchmark-question-tag"
    >
      {{ question }}
    </el-tag>
    <el-tag
      v-if="hiddenQuestionCount > 0"
      size="mini"
      type="info"
      effect="plain"
      class="benchmark-question-more"
    >
      还有 {{ hiddenQuestionCount }} 条
    </el-tag>
  </div>
</template>

<script>
const COMPACT_QUESTION_LIMIT = 6;

export default {
  name: "BenchmarkQuestionList",
  props: {
    questions: {
      type: Array,
      default: () => [],
    },
  },
  data() {
    return {
      expanded: false,
    };
  },
  computed: {
    normalizedQuestions() {
      return Array.isArray(this.questions) ? this.questions.filter((item) => item) : [];
    },
    visibleQuestions() {
      return this.expanded
        ? this.normalizedQuestions
        : this.normalizedQuestions.slice(0, COMPACT_QUESTION_LIMIT);
    },
    hiddenQuestionCount() {
      return this.expanded
        ? 0
        : Math.max(0, this.normalizedQuestions.length - COMPACT_QUESTION_LIMIT);
    },
  },
};
</script>

<style lang="scss" scoped>
.performance-custom-question-panel {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.performance-field-label {
  margin-bottom: 0;
  color: #909399;
  font-size: 12px;
}

.performance-question-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-basis: 100%;
  gap: 8px;
}

.benchmark-question-tag,
.benchmark-question-more {
  max-width: 100%;
  height: auto;
  min-height: 24px;
  line-height: 18px;
  padding-top: 2px;
  padding-bottom: 2px;
  white-space: normal;
  word-break: break-word;
}

@media (max-width: 640px) {
  .performance-question-panel-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
