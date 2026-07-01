<template>
  <div>
    <div v-if="report" class="performance-progress-panel">
      <div class="performance-progress-head">
        <span>{{ getPerformanceStageText(report) }}</span>
        <el-popover
          v-if="getMetricNotes(report).length"
          placement="bottom-start"
          width="520"
          trigger="hover"
          popper-class="benchmark-metric-popover"
        >
          <div class="benchmark-metric-popover-content">
            <div
              v-for="(note, index) in getMetricNotes(report)"
              :key="index"
              class="benchmark-metric-note"
            >
              {{ note }}
            </div>
          </div>
          <button slot="reference" type="button" class="benchmark-metric-help">
            <i class="el-icon-info"></i>
            <span>评测口径</span>
          </button>
        </el-popover>
        <strong>{{ getPerformanceProgress(report) }}%</strong>
      </div>
      <el-progress
        :percentage="getPerformanceProgress(report)"
        :status="getPerformanceProgressStatus(report)"
        :stroke-width="8"
      />
      <div class="performance-progress-meta">
        <span>{{ report.summary || getPerformanceDefaultHint(report) }}</span>
        <span v-if="testing">{{ $t("modelConfig.performancePollingHint") }}</span>
      </div>
      <div v-if="report.errorMessage" class="performance-progress-error">
        {{ report.errorMessage }}
      </div>
    </div>
    <div v-if="reportCustomQuestions.length" class="performance-custom-question-panel">
      <div class="performance-question-panel-head">
        <div class="performance-field-label">
          {{ $t("modelConfig.performanceSampleUsed") }}：{{ reportCustomQuestions.length }} 条
        </div>
        <el-button
          v-if="hiddenReportQuestionCount > 0 || reportQuestionsExpanded"
          type="text"
          size="mini"
          @click="reportQuestionsExpanded = !reportQuestionsExpanded"
        >
          {{ reportQuestionsExpanded ? "收起" : "展开全部" }}
        </el-button>
      </div>
      <el-tag
        v-for="(question, index) in visibleReportQuestions"
        :key="`${index}-${question}`"
        size="mini"
        effect="plain"
        class="benchmark-question-tag"
      >
        {{ question }}
      </el-tag>
      <el-tag
        v-if="hiddenReportQuestionCount > 0"
        size="mini"
        type="info"
        effect="plain"
        class="benchmark-question-more"
      >
        还有 {{ hiddenReportQuestionCount }} 条
      </el-tag>
    </div>
    <div v-if="report" class="performance-layout">
      <div class="performance-list">
        <div class="performance-list-head">
          <strong>模型结果</strong>
          <span>{{ performanceResults.length }} 个模型</span>
        </div>
        <button
          v-for="row in performanceResults"
          :key="getPerformanceResultKey(row)"
          type="button"
          class="performance-result-item"
          :class="{ active: getPerformanceResultKey(row) === getPerformanceResultKey(selectedPerformanceResult) }"
          @click="selectPerformanceResult(row)"
        >
          <div class="performance-result-main">
            <div>
              <strong>{{ row.modelName || "-" }}</strong>
              <span>{{ row.providerType || "-" }} / {{ row.testLevelText || row.testLevel || "-" }}</span>
            </div>
            <el-tag size="mini" :type="getResultSuccessTag(row)">
              {{ formatPercent(row.successRate) }}
            </el-tag>
          </div>
          <div class="performance-result-metrics">
            <span>{{ $t("modelConfig.avgLatency") }} {{ formatMeasuredLatency(row, "avgLatencyMs") }}</span>
            <span v-if="isModelType(row, 'llm')">{{ $t("modelConfig.qualityScore") }} {{ formatScore(row.qualityScore) }}</span>
            <span v-if="isModelType(row, 'llm')">P0/P1 {{ row.p0p1FailureCount || 0 }}</span>
            <span v-if="hasMetric(row.firstAudioMs) && isModelType(row, 'tts')">{{ $t("modelConfig.firstAudio") }} {{ formatMetric(row.firstAudioMs, " ms") }}</span>
            <span v-if="isModelType(row, 'tts')">{{ $t("modelConfig.rtf") }} {{ formatMetric(row.rtf, "") }}</span>
            <span v-if="hasMetric(row.ttftMs) && isModelType(row, 'asr')">{{ $t("modelConfig.firstAsrText") }} {{ formatMetric(row.ttftMs, " ms") }}</span>
            <span v-if="isModelType(row, 'asr')">{{ $t("modelConfig.cer") }} {{ formatPercent(row.cer) }}</span>
            <span v-if="isModelType(row, 'asr')">{{ $t("modelConfig.wer") }} {{ formatPercent(row.wer) }}</span>
          </div>
          <p v-if="row.summary">{{ row.summary }}</p>
        </button>
        <el-empty
          v-if="!performanceResults.length"
          :description="getPerformanceDefaultHint(report)"
          :image-size="72"
        />
      </div>
      <div class="performance-detail">
        <div class="performance-detail-title">
          {{ selectedPerformanceResult ? selectedPerformanceResult.modelName : $t("modelConfig.performanceProcess") }}
        </div>
        <div v-if="selectedPerformanceResult" class="performance-detail-summary">
          <div>
            <span>{{ $t("modelConfig.successRate") }}</span>
            <strong>{{ formatPercent(selectedPerformanceResult.successRate) }}</strong>
          </div>
          <div>
            <span>{{ $t("modelConfig.avgLatency") }}</span>
            <strong>{{ formatMeasuredLatency(selectedPerformanceResult, "avgLatencyMs") }}</strong>
          </div>
          <div>
            <span>{{ $t("modelConfig.summary") }}</span>
            <strong>{{ selectedPerformanceResult.summary || "-" }}</strong>
          </div>
          <div v-if="isModelType(selectedPerformanceResult, 'llm')">
            <span>{{ $t("modelConfig.recommendation") }}</span>
            <strong>{{ selectedPerformanceResult.recommendation || "-" }}</strong>
          </div>
          <div v-if="isModelType(selectedPerformanceResult, 'llm')">
            <span>{{ $t("modelConfig.judgeMode") }}</span>
            <strong>{{ formatJudgeMode(selectedPerformanceResult.judgeMode) }}</strong>
          </div>
        </div>
        <div v-if="selectedPerformanceResult && isModelType(selectedPerformanceResult, 'llm')" class="judge-summary">
          <div class="judge-summary-title">{{ $t("modelConfig.dimensionScores") }}</div>
          <div class="judge-chip-list">
            <el-tag
              v-for="item in getTopScoreItems(selectedPerformanceResult.dimensionScores)"
              :key="item.key"
              size="mini"
              effect="plain"
            >
              {{ $t(`modelConfig.dimension.${item.key}`) }}：{{ item.value }}
            </el-tag>
          </div>
          <div class="judge-summary-title">{{ $t("modelConfig.scenarioScores") }}</div>
          <div class="judge-chip-list">
            <el-tag
              v-for="item in getTopScoreItems(selectedPerformanceResult.scenarioScores)"
              :key="item.key"
              size="mini"
              effect="plain"
            >
              {{ item.key }}：{{ item.value }}
            </el-tag>
          </div>
        </div>
        <el-empty
          v-if="!performanceSamples.length"
          :description="getNoPerformanceSamplesText()"
          :image-size="72"
        />
        <div v-else class="performance-chat">
          <div class="performance-sample-picker">
            <el-button
              v-for="sample in performanceSamples"
              :key="sample.iteration"
              size="mini"
              :type="getPerformanceSampleKey(sample) === selectedPerformanceSampleKey ? 'primary' : 'default'"
              plain
              class="performance-sample-chip"
              @click="selectPerformanceSample(sample)"
            >
              {{ $t("modelConfig.performanceRound", { iteration: sample.iteration }) }}
              <span class="performance-chip-meta">
                {{ sample.success ? $t("modelConfig.testSuccess") : $t("modelConfig.testFailed") }}
              </span>
              <span class="performance-chip-meta">{{ formatResponseTime(sample.responseTimeMs) }}</span>
            </el-button>
          </div>
          <div v-if="selectedPerformanceSample" class="performance-chat-turn">
            <div class="performance-turn-meta">
              <span>{{ $t("modelConfig.performanceRound", { iteration: selectedPerformanceSample.iteration }) }}</span>
              <span v-if="selectedPerformanceSample.scenario">{{ selectedPerformanceSample.scenario }}</span>
              <el-tag size="mini" :type="selectedPerformanceSample.success ? 'success' : 'danger'">
                {{ selectedPerformanceSample.success ? $t("modelConfig.testSuccess") : $t("modelConfig.testFailed") }}
              </el-tag>
              <span>{{ formatResponseTime(selectedPerformanceSample.responseTimeMs) }}</span>
            </div>

            <div class="performance-dialogue-flow">
              <div
                v-for="(message, index) in getVisibleSampleMessages(selectedPerformanceSample)"
                :key="`${message.role}-${index}`"
                class="performance-bubble-row"
                :class="message.role === 'assistant' ? 'is-assistant' : 'is-user'"
              >
                <div class="performance-bubble-label">
                  {{ formatDialogueRole(message.role) }}
                </div>
                <div class="performance-bubble">
                  {{ message.content || "-" }}
                </div>
              </div>
              <div
                v-if="getHiddenSampleMessageCount(selectedPerformanceSample) > 0"
                class="performance-dialogue-more"
              >
                还有 {{ getHiddenSampleMessageCount(selectedPerformanceSample) }} 条对话在更多详情中
              </div>
              <div
                v-if="!hasFullSampleConversation(selectedPerformanceSample)"
                class="performance-bubble-row is-assistant is-output"
              >
                <div class="performance-bubble-label">{{ getSampleOutputLabel(selectedPerformanceSample) }}</div>
                <div class="performance-bubble">
                  {{ getSampleAssistantText(selectedPerformanceSample) }}
                </div>
              </div>
            </div>

            <div v-if="hasSampleAudioPreview(selectedPerformanceSample)" class="performance-audio-preview">
              <div v-if="getAsrInputAudioUrl(selectedPerformanceSample)" class="performance-audio-item">
                <div class="performance-field-label">语音样本</div>
                <audio
                  :src="getAsrInputAudioUrl(selectedPerformanceSample)"
                  controls
                  preload="metadata"
                ></audio>
                <span>{{ getInputDetail(selectedPerformanceSample).audio_name || selectedPerformanceSample.inputSummary }}</span>
              </div>
              <div v-if="getTtsOutputAudioUrl(selectedPerformanceSample)" class="performance-audio-item">
                <div class="performance-field-label">合成音频</div>
                <audio
                  :src="getTtsOutputAudioUrl(selectedPerformanceSample)"
                  controls
                  preload="metadata"
                ></audio>
                <span>{{ getTtsPreviewMeta(selectedPerformanceSample) }}</span>
              </div>
            </div>

            <div class="performance-sample-metrics">
              <span v-if="isSampleType(selectedPerformanceSample, 'llm')">
                {{ $t("modelConfig.tokensPerSecond") }} {{ formatMetric(selectedPerformanceSample.tokensPerSecond, "") }}
              </span>
              <span v-if="isSampleType(selectedPerformanceSample, 'llm')">
                {{ $t("modelConfig.qualityScore") }} {{ formatScore(selectedPerformanceSample.qualityScore) }}
              </span>
              <span v-if="hasMetric(selectedPerformanceSample.firstAudioMs) && isSampleType(selectedPerformanceSample, 'tts')">
                {{ $t("modelConfig.firstAudio") }} {{ formatMetric(selectedPerformanceSample.firstAudioMs, " ms") }}
              </span>
              <span v-if="isSampleType(selectedPerformanceSample, 'tts')">
                {{ $t("modelConfig.rtf") }} {{ formatMetric(selectedPerformanceSample.rtf, "") }}
              </span>
              <span v-if="hasMetric(selectedPerformanceSample.ttftMs) && isSampleType(selectedPerformanceSample, 'asr')">
                {{ $t("modelConfig.firstAsrText") }} {{ formatMetric(selectedPerformanceSample.ttftMs, " ms") }}
              </span>
              <span v-if="isSampleType(selectedPerformanceSample, 'asr')">
                {{ $t("modelConfig.cer") }} {{ formatPercent(selectedPerformanceSample.cer) }}
              </span>
              <span v-if="isSampleType(selectedPerformanceSample, 'asr')">
                {{ $t("modelConfig.wer") }} {{ formatPercent(selectedPerformanceSample.wer) }}
              </span>
            </div>

            <div v-if="isSampleType(selectedPerformanceSample, 'llm')" class="judge-sample-detail">
              <div>
                <span>{{ $t("modelConfig.scenario") }}</span>
                <strong>{{ selectedPerformanceSample.scenario || "-" }}</strong>
              </div>
              <div>
                <span>{{ $t("modelConfig.riskLevel") }}</span>
                <strong>{{ selectedPerformanceSample.riskLevel || "-" }}</strong>
              </div>
              <div>
                <span>{{ $t("modelConfig.p0p1Failures") }}</span>
                <strong>{{ formatP0P1(selectedPerformanceSample.p0p1) }}</strong>
              </div>
              <div>
                <span>{{ $t("modelConfig.judgeMode") }}</span>
                <strong>{{ formatJudgeMode(selectedPerformanceSample.judgeMode) }}</strong>
              </div>
            </div>

            <div v-if="isSampleType(selectedPerformanceSample, 'llm')" class="judge-score-panel">
              <div class="judge-summary-title">{{ $t("modelConfig.dimensionScores") }}</div>
              <div class="judge-chip-list">
                <el-tag
                  v-for="item in getTopScoreItems(getOutputDetail(selectedPerformanceSample).scores)"
                  :key="item.key"
                  size="mini"
                  effect="plain"
                >
                  {{ $t(`modelConfig.dimension.${item.key}`) }}：{{ item.value }}
                </el-tag>
              </div>
            </div>

            <el-collapse
              v-if="hasSampleLongDetail(selectedPerformanceSample)"
              class="performance-json-detail"
            >
              <el-collapse-item title="更多详情" name="detail">
                <div v-if="getHiddenSampleMessages(selectedPerformanceSample).length" class="performance-detail-block">
                  <div class="performance-field-label">完整对话</div>
                  <div class="performance-dialogue-flow is-compact">
                    <div
                      v-for="(message, index) in getSampleMessages(selectedPerformanceSample)"
                      :key="`full-${message.role}-${index}`"
                      class="performance-bubble-row"
                      :class="message.role === 'assistant' ? 'is-assistant' : 'is-user'"
                    >
                      <div class="performance-bubble-label">
                        {{ formatDialogueRole(message.role) }}
                      </div>
                      <div class="performance-bubble">
                        {{ message.content || "-" }}
                      </div>
                    </div>
                  </div>
                </div>
                <div
                  v-if="getSampleContextItems(selectedPerformanceSample).length"
                  class="performance-detail-block"
                >
                  <div class="performance-field-label">上下文</div>
                  <div class="performance-context-panel is-compact">
                    <div
                      v-for="item in getSampleContextItems(selectedPerformanceSample)"
                      :key="item.label"
                      class="performance-context-item"
                    >
                      <span>{{ item.label }}</span>
                      <p>{{ item.value }}</p>
                    </div>
                  </div>
                </div>
                <div
                  v-if="getSampleExpectedBehavior(selectedPerformanceSample)"
                  class="performance-chat-reference"
                >
                  <strong>{{ $t("modelConfig.expectedBehavior") }}</strong>
                  <p>{{ getSampleExpectedBehavior(selectedPerformanceSample) }}</p>
                </div>
                <div
                  v-if="isSampleType(selectedPerformanceSample, 'llm') && hasJudgeReasons(selectedPerformanceSample)"
                  class="judge-reasons"
                >
                  <div class="performance-field-label">{{ $t("modelConfig.judgeReasons") }}</div>
                  <pre>{{ formatJudgeReasons(selectedPerformanceSample.outputDetail) }}</pre>
                </div>
                <div class="performance-sample-grid">
                  <div v-if="hasObjectValue(selectedPerformanceSample.inputDetail)">
                    <div class="performance-field-label">{{ $t("modelConfig.testInput") }}</div>
                    <pre>{{ formatJsonDetail(selectedPerformanceSample.inputDetail) }}</pre>
                  </div>
                  <div v-if="hasObjectValue(selectedPerformanceSample.outputDetail)">
                    <div class="performance-field-label">{{ $t("modelConfig.testOutput") }}</div>
                    <pre>{{ formatJsonDetail(selectedPerformanceSample.outputDetail) }}</pre>
                  </div>
                </div>
              </el-collapse-item>
            </el-collapse>
            <div
              v-if="selectedPerformanceSample.message && selectedPerformanceSample.message !== 'OK'"
              class="performance-message"
            >
              {{ selectedPerformanceSample.message }}
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
const COMPACT_QUESTION_LIMIT = 6;
const COMPACT_MESSAGE_LIMIT = 4;
const COMPACT_SCORE_LIMIT = 6;

export default {
  name: "BenchmarkResultPanel",
  props: {
    report: {
      type: Object,
      default: null,
    },
    testing: {
      type: Boolean,
      default: false,
    },
    selectedResultId: {
      type: String,
      default: "",
    },
    selectedSampleKey: {
      type: String,
      default: "",
    },
  },
  data() {
    return {
      reportQuestionsExpanded: false,
      selectedPerformanceResultId: this.selectedResultId || "",
      selectedPerformanceSampleKey: this.selectedSampleKey || "",
    };
  },
  computed: {
    performanceResults() {
      return (this.report && this.report.results) || [];
    },
    selectedPerformanceResult() {
      const results = this.performanceResults;
      if (!results.length) return null;
      return (
        results.find((item) => this.getPerformanceResultKey(item) === this.selectedPerformanceResultId) ||
        results[0]
      );
    },
    performanceSamples() {
      return (this.selectedPerformanceResult && this.selectedPerformanceResult.samples) || [];
    },
    selectedPerformanceSample() {
      const samples = this.performanceSamples;
      if (!samples.length) return null;
      return (
        samples.find((item) => this.getPerformanceSampleKey(item) === this.selectedPerformanceSampleKey) ||
        samples[0]
      );
    },
    reportCustomQuestions() {
      return this.getReportCustomQuestions(this.report);
    },
    visibleReportQuestions() {
      return this.getCompactList(this.reportCustomQuestions, this.reportQuestionsExpanded);
    },
    hiddenReportQuestionCount() {
      return this.getHiddenListCount(this.reportCustomQuestions, this.reportQuestionsExpanded);
    },
  },
  watch: {
    selectedResultId(value) {
      this.selectedPerformanceResultId = value || "";
    },
    selectedSampleKey(value) {
      this.selectedPerformanceSampleKey = value || "";
    },
    report() {
      // Sync selection when report changes
      this.syncSelectedPerformanceResult();
    },
  },
  methods: {
    selectPerformanceResult(row) {
      this.selectedPerformanceResultId = this.getPerformanceResultKey(row);
      this.$emit("update:selected-result-id", this.selectedPerformanceResultId);
      this.syncSelectedPerformanceSample();
    },
    selectPerformanceSample(sample) {
      this.selectedPerformanceSampleKey = this.getPerformanceSampleKey(sample);
      this.$emit("update:selected-sample-key", this.selectedPerformanceSampleKey);
    },
    syncSelectedPerformanceResult() {
      const results = this.performanceResults;
      if (!results.length) {
        this.selectedPerformanceResultId = "";
        this.selectedPerformanceSampleKey = "";
        return;
      }
      const exists = results.some(
        (item) => this.getPerformanceResultKey(item) === this.selectedPerformanceResultId
      );
      if (!exists) {
        this.selectedPerformanceResultId = this.getPerformanceResultKey(results[0]);
      }
      this.syncSelectedPerformanceSample();
    },
    syncSelectedPerformanceSample() {
      const samples = this.performanceSamples;
      if (!samples.length) {
        this.selectedPerformanceSampleKey = "";
        return;
      }
      const exists = samples.some(
        (item) => this.getPerformanceSampleKey(item) === this.selectedPerformanceSampleKey
      );
      if (!exists) {
        this.selectedPerformanceSampleKey = this.getPerformanceSampleKey(samples[0]);
      }
    },
    getPerformanceResultKey(row) {
      if (!row) return "";
      return row.modelId || `${row.modelName || ""}-${row.providerType || ""}`;
    },
    getPerformanceSampleKey(sample) {
      if (!sample) return "";
      return String(sample.iteration || "");
    },
    formatResponseTime(responseTimeMs) {
      if (responseTimeMs === null || responseTimeMs === undefined || responseTimeMs === "") return "-";
      return `${responseTimeMs} ms`;
    },
    formatBenchmarkStatus(status) {
      if (!status) return "-";
      const key = `modelConfig.benchmarkStatus.${status}`;
      const translated = this.$t(key);
      return translated === key ? status : translated;
    },
    formatPercent(value) {
      if (value === null || value === undefined || value === "") return "-";
      return `${Math.round(Number(value) * 10000) / 100}%`;
    },
    formatScore(value) {
      if (value === null || value === undefined || value === "") return "-";
      return `${Math.round(Number(value) * 100) / 100}`;
    },
    formatMeasuredLatency(row, field) {
      if (!row || !row.measured) return this.$t("modelConfig.notMeasured");
      const value = row[field];
      if (value === null || value === undefined || value === "") return "-";
      return `${value} ms`;
    },
    formatMetric(value, unit) {
      if (value === null || value === undefined || value === "") return "-";
      return `${value}${unit}`;
    },
    hasMetric(value) {
      return value !== null && value !== undefined && value !== "";
    },
    formatP0P1(value) {
      if (!Array.isArray(value) || value.length === 0) return this.$t("modelConfig.none");
      return value.join(", ");
    },
    formatJudgeMode(value) {
      if (!value) return "-";
      return String(value)
        .split(",")
        .map((item) => this.$t(`modelConfig.judgeMode.${item}`))
        .join(" / ");
    },
    formatJudgeReasons(outputDetail) {
      const reasons = outputDetail && outputDetail.reasons;
      if (!reasons || typeof reasons !== "object") return "-";
      return Object.keys(reasons)
        .map((key) => `${this.$t(`modelConfig.dimension.${key}`)}：${reasons[key]}`)
        .join("\n");
    },
    getReportCustomQuestions(report) {
      const questions = report && report.customQuestions;
      return Array.isArray(questions) ? questions.filter((item) => item) : [];
    },
    getCompactList(items, expanded, limit = COMPACT_QUESTION_LIMIT) {
      const list = Array.isArray(items) ? items : [];
      return expanded ? list : list.slice(0, limit);
    },
    getHiddenListCount(items, expanded, limit = COMPACT_QUESTION_LIMIT) {
      const list = Array.isArray(items) ? items : [];
      return expanded ? 0 : Math.max(0, list.length - limit);
    },
    getInputDetail(sample) {
      return (sample && sample.inputDetail) || {};
    },
    getOutputDetail(sample) {
      return (sample && sample.outputDetail) || {};
    },
    getSampleMessages(sample) {
      const inputDetail = this.getInputDetail(sample);
      const outputDetail = this.getOutputDetail(sample);
      const conversation = Array.isArray(outputDetail.conversation) ? outputDetail.conversation : [];
      if (conversation.length) {
        return conversation.filter((item) => item && item.role !== "system");
      }
      const messages = Array.isArray(inputDetail.messages) ? inputDetail.messages : [];
      if (messages.length) {
        return messages.filter((item) => item && item.role !== "system");
      }
      if (this.isSampleType(sample, "tts")) {
        return [
          {
            role: "user",
            content: inputDetail.text || (sample && sample.inputSummary) || "-",
          },
        ];
      }
      return [
        {
          role: "user",
          content: (sample && sample.inputSummary) || "-",
        },
      ];
    },
    getVisibleSampleMessages(sample) {
      return this.getSampleMessages(sample).slice(0, COMPACT_MESSAGE_LIMIT);
    },
    getHiddenSampleMessages(sample) {
      return this.getSampleMessages(sample).slice(COMPACT_MESSAGE_LIMIT);
    },
    getHiddenSampleMessageCount(sample) {
      return this.getHiddenSampleMessages(sample).length;
    },
    hasFullSampleConversation(sample) {
      const outputDetail = this.getOutputDetail(sample);
      return Array.isArray(outputDetail.conversation) && outputDetail.conversation.length > 0;
    },
    getSampleAssistantText(sample) {
      const outputDetail = this.getOutputDetail(sample);
      if (this.isSampleType(sample, "asr")) {
        return outputDetail.recognized_text || (sample && sample.outputSummary) || "-";
      }
      if (this.isSampleType(sample, "tts")) {
        const audioBytes = outputDetail.audio_bytes || 0;
        return audioBytes ? `已生成音频，${audioBytes} 字节` : (sample && sample.outputSummary) || "-";
      }
      return outputDetail.assistant_text || (sample && sample.outputSummary) || "-";
    },
    getSampleOutputLabel(sample) {
      if (this.isSampleType(sample, "asr")) return this.$t("modelConfig.asrTextOutput");
      if (this.isSampleType(sample, "tts")) return this.$t("modelConfig.ttsAudioOutput");
      return this.$t("modelConfig.llmReply");
    },
    hasSampleAudioPreview(sample) {
      return !!(this.getAsrInputAudioUrl(sample) || this.getTtsOutputAudioUrl(sample));
    },
    getAsrInputAudioUrl(sample) {
      const inputDetail = this.getInputDetail(sample);
      return this.isSampleType(sample, "asr") ? (inputDetail.audio_data_url || "") : "";
    },
    getTtsOutputAudioUrl(sample) {
      const outputDetail = this.getOutputDetail(sample);
      return this.isSampleType(sample, "tts") ? (outputDetail.audio_data_url || "") : "";
    },
    getTtsPreviewMeta(sample) {
      const outputDetail = this.getOutputDetail(sample);
      const format = outputDetail.audio_preview_format || outputDetail.output_file_type || "audio";
      const bytes = outputDetail.audio_bytes || 0;
      return bytes ? `${format} / ${bytes} 字节` : format;
    },
    getSampleExpectedBehavior(sample) {
      const inputDetail = this.getInputDetail(sample);
      return inputDetail.expected_behavior || (sample && sample.referenceOutput) || "";
    },
    getSampleContextItems(sample) {
      const inputDetail = this.getInputDetail(sample);
      const items = [];
      if (inputDetail.rag_context) {
        items.push({ label: this.$t("modelConfig.ragContext"), value: inputDetail.rag_context });
      }
      if (inputDetail.memory_context) {
        items.push({ label: this.$t("modelConfig.memoryContext"), value: inputDetail.memory_context });
      }
      const rules = Array.isArray(inputDetail.safety_rules) ? inputDetail.safety_rules : [];
      if (rules.length) {
        items.push({ label: this.$t("modelConfig.safetyRules"), value: rules.join("；") });
      }
      return items;
    },
    formatDialogueRole(role) {
      const key = `modelConfig.dialogueRole.${role}`;
      const translated = this.$t(key);
      return translated === key ? role : translated;
    },
    hasObjectValue(value) {
      return !!value && typeof value === "object" && Object.keys(value).length > 0;
    },
    hasSampleDetail(sample) {
      return this.hasObjectValue(sample && sample.inputDetail) || this.hasObjectValue(sample && sample.outputDetail);
    },
    hasJudgeReasons(sample) {
      const outputDetail = this.getOutputDetail(sample);
      return !!(outputDetail && outputDetail.reasons && typeof outputDetail.reasons === "object");
    },
    hasSampleLongDetail(sample) {
      return (
        this.getHiddenSampleMessageCount(sample) > 0 ||
        this.getSampleContextItems(sample).length > 0 ||
        !!this.getSampleExpectedBehavior(sample) ||
        this.hasJudgeReasons(sample) ||
        this.hasSampleDetail(sample)
      );
    },
    formatJsonDetail(value) {
      if (!value) return "-";
      if (typeof value === "string") return value;
      return JSON.stringify(value, null, 2);
    },
    isModelType(row, type) {
      const benchmarkModelType = this.getBenchmarkModelType(row);
      if (type === "llm") return benchmarkModelType === "llm" || benchmarkModelType === "slm";
      return benchmarkModelType === type;
    },
    isSampleType(sample, type) {
      const sampleType = String((sample && (sample.sampleType || sample.testLevel)) || "").toLowerCase();
      if (type === "llm") return sampleType.includes("llm") || this.isModelType(this.selectedPerformanceResult, "llm");
      return sampleType.includes(type) || this.isModelType(this.selectedPerformanceResult, type);
    },
    getBenchmarkModelType(row) {
      return String((row && row.modelType) || "").toLowerCase();
    },
    formatScoreMap(value) {
      if (!value || typeof value !== "object") return [];
      return Object.keys(value).map((key) => ({ key, value: this.formatScore(value[key]) }));
    },
    getTopScoreItems(value) {
      return this.formatScoreMap(value).slice(0, COMPACT_SCORE_LIMIT);
    },
    getMetricNotes(report) {
      return Array.isArray(report && report.metricNotes) ? report.metricNotes.filter(Boolean) : [];
    },
    getPerformanceStageText(report) {
      const status = report && report.status;
      if (status === "SUBMITTING") return this.$t("modelConfig.performanceSubmitting");
      return this.formatBenchmarkStatus(status);
    },
    getPerformanceDefaultHint(report) {
      const status = report && report.status;
      if (status === "SUBMITTING") return this.$t("modelConfig.performanceSubmittingHint");
      if (status === "PENDING") return this.$t("modelConfig.performancePendingHint");
      if (status === "RUNNING") return this.$t("modelConfig.performanceRunningHint");
      if (status === "SUCCEEDED") return this.$t("modelConfig.performanceSucceededHint");
      if (status === "FAILED") return this.$t("modelConfig.performanceFailedHint");
      return this.$t("modelConfig.performanceWaitingHint");
    },
    getPerformanceProgress(report) {
      const progress = Number((report && report.progress) || 0);
      if (Number.isNaN(progress)) return 0;
      return Math.max(0, Math.min(100, Math.round(progress)));
    },
    getPerformanceProgressStatus(report) {
      const status = report && report.status;
      if (status === "SUCCEEDED") return "success";
      if (status === "FAILED" || status === "CANCELLED") return "exception";
      return undefined;
    },
    getResultSuccessTag(row) {
      const successRate = Number(row && row.successRate);
      if (Number.isNaN(successRate)) return "info";
      if (successRate >= 0.95) return "success";
      if (successRate > 0) return "warning";
      return "danger";
    },
    getNoPerformanceSamplesText() {
      if (this.testing) return this.$t("modelConfig.performanceNoSamplesRunning");
      return this.$t("modelConfig.noPerformanceSamples");
    },
  },
};
</script>

<style lang="scss" scoped>
.benchmark-metric-help {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: none;
  background: transparent;
  color: #909399;
  font-size: 12px;
  line-height: 1;
  cursor: help;
}

.benchmark-metric-help:hover,
.benchmark-metric-help:focus {
  color: #409eff;
  outline: none;
}

.performance-progress-panel {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #f7f9fc;
}

.performance-progress-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  color: #303133;
  font-size: 13px;
  font-weight: 600;
}

.performance-progress-head > strong {
  margin-left: auto;
}

.performance-progress-meta {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
  color: #606266;
  font-size: 12px;
  line-height: 1.5;
}

.performance-progress-error {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 4px;
  background: #fef0f0;
  color: #f56c6c;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

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

.performance-custom-question-panel .performance-field-label {
  margin-bottom: 0;
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

.performance-layout {
  display: grid;
  grid-template-columns: minmax(360px, 0.75fr) minmax(560px, 1.25fr);
  gap: 14px;
  align-items: start;
}

.performance-list {
  display: grid;
  gap: 8px;
}

.performance-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #303133;
}

.performance-list-head span {
  color: #909399;
  font-size: 12px;
}

.performance-result-item {
  width: 100%;
  padding: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  background: #fff;
  text-align: left;
  cursor: pointer;
}

.performance-result-item.active {
  border-color: #409eff;
  background: #f5faff;
}

.performance-result-main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.performance-result-main strong,
.performance-result-main span {
  display: block;
}

.performance-result-main strong {
  color: #303133;
  font-size: 14px;
  line-height: 1.4;
  word-break: break-word;
}

.performance-result-main span {
  margin-top: 2px;
  color: #909399;
  font-size: 12px;
}

.performance-result-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-top: 10px;
  color: #606266;
  font-size: 12px;
  line-height: 1.4;
}

.performance-result-item p {
  margin: 8px 0 0;
  color: #606266;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-word;
}

.performance-detail {
  border: 1px solid #edf0f5;
  border-radius: 4px;
  padding: 14px;
  background: #f7f9fc;
}

.performance-detail-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.performance-detail-summary {
  display: grid;
  grid-template-columns: 1fr;
  gap: 6px;
  color: #606266;
  font-size: 12px;
  margin-bottom: 8px;
}

.performance-detail-summary > div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.performance-detail-summary strong {
  min-width: 0;
  text-align: right;
  word-break: break-word;
}

.judge-summary {
  margin-bottom: 10px;
  padding: 8px 0;
  border-top: 1px solid #edf0f5;
  border-bottom: 1px solid #edf0f5;
}

.judge-summary-title {
  color: #606266;
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 6px;
}

.judge-chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}

.judge-sample-detail {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 10px;
  padding: 8px 0;
  border-top: 1px solid #edf0f5;
  border-bottom: 1px solid #edf0f5;
  color: #606266;
  font-size: 12px;
}

.judge-sample-detail > div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  min-width: 0;
}

.judge-sample-detail strong {
  min-width: 0;
  text-align: right;
  word-break: break-word;
}

.judge-reasons pre {
  margin: 0;
  padding: 8px;
  max-height: 120px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  background: #fff;
  border: 1px solid #edf0f5;
  border-radius: 4px;
  color: #303133;
  line-height: 1.5;
}

.performance-chat {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.performance-sample-picker {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.performance-sample-chip {
  flex: 0 0 auto;
}

.performance-chip-meta {
  margin-left: 4px;
  color: #909399;
}

.performance-chat-turn {
  padding-top: 2px;
}

.performance-context-panel {
  display: grid;
  grid-template-columns: 1fr;
  gap: 8px;
  margin-bottom: 10px;
  padding: 10px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.performance-context-panel.is-compact {
  margin-bottom: 0;
  padding: 0;
  border: none;
  background: transparent;
}

.performance-context-item span {
  display: block;
  margin-bottom: 3px;
  color: #909399;
  font-size: 12px;
}

.performance-context-item p {
  margin: 0;
  color: #303133;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.performance-dialogue-flow {
  padding: 10px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.performance-dialogue-flow.is-compact {
  padding: 0;
  border: none;
  background: transparent;
}

.performance-turn-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
  color: #606266;
  font-size: 12px;
}

.performance-bubble-row {
  display: flex;
  flex-direction: column;
  margin-bottom: 8px;
}

.performance-bubble-row.is-user {
  align-items: flex-start;
}

.performance-bubble-row.is-assistant {
  align-items: flex-end;
}

.performance-bubble-row.is-output .performance-bubble-label {
  color: #409eff;
}

.performance-bubble-label {
  margin-bottom: 3px;
  color: #909399;
  font-size: 12px;
}

.performance-bubble {
  max-width: 92%;
  padding: 9px 11px;
  border-radius: 6px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  color: #303133;
  font-size: 13px;
}

.performance-dialogue-more {
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
  text-align: center;
}

.performance-bubble-row.is-user .performance-bubble {
  background: #fff;
  border: 1px solid #e4e7ed;
}

.performance-bubble-row.is-assistant .performance-bubble {
  background: #ecf5ff;
  border: 1px solid #c6e2ff;
}

.performance-sample-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.performance-field-label {
  color: #909399;
  font-size: 12px;
  margin-bottom: 4px;
}

.performance-chat-reference {
  margin-top: 8px;
  padding: 10px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
  color: #606266;
  line-height: 1.55;
  font-size: 13px;
}

.performance-chat-reference strong {
  display: block;
  margin-bottom: 4px;
  color: #303133;
}

.performance-chat-reference p {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.performance-sample-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}

.performance-audio-preview {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 10px;
  margin-top: 10px;
}

.performance-audio-item {
  padding: 10px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.performance-audio-item audio {
  display: block;
  width: 100%;
  height: 36px;
}

.performance-audio-item span {
  display: block;
  margin-top: 6px;
  color: #606266;
  font-size: 12px;
  line-height: 1.4;
  word-break: break-word;
}

.judge-score-panel {
  margin-top: 8px;
  padding: 8px 0 0;
  border-top: 1px solid #edf0f5;
}

.performance-json-detail {
  margin-top: 8px;
}

.performance-detail-block {
  margin-bottom: 10px;
}

.performance-json-detail ::v-deep .el-collapse-item__header {
  height: 32px;
  line-height: 32px;
  border: none;
  color: #606266;
  font-size: 12px;
}

.performance-json-detail ::v-deep .el-collapse-item__wrap {
  border-bottom: none;
}

.performance-message {
  margin-top: 8px;
  color: #f56c6c;
  font-size: 12px;
  word-break: break-word;
}

@media (max-width: 1100px) {
  .performance-layout {
    grid-template-columns: 1fr;
  }

  .performance-detail-summary > div,
  .judge-sample-detail > div {
    display: grid;
    grid-template-columns: 92px minmax(0, 1fr);
  }

  .performance-detail-summary strong,
  .judge-sample-detail strong {
    text-align: left;
  }
}

@media (max-width: 640px) {
  .performance-question-panel-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .judge-sample-detail,
  .performance-sample-grid {
    grid-template-columns: 1fr;
  }

  .performance-bubble {
    max-width: 100%;
  }
}
</style>

<style lang="scss">
.benchmark-metric-popover {
  max-width: calc(100vw - 32px);
}

.benchmark-metric-popover-content {
  display: grid;
  gap: 6px;
  color: #606266;
  font-size: 12px;
  line-height: 1.6;
}

.benchmark-metric-note {
  word-break: break-word;
}
</style>
