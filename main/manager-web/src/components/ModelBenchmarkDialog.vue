<template>
  <div>
    <el-dialog
      :title="$t('modelConfig.performanceTestTitle')"
      :visible.sync="localVisible"
      width="1280px"
      append-to-body
      class="performance-result-dialog"
    >
      <div class="performance-toolbar">
        <span>{{ $t("modelConfig.performanceIterations") }}</span>
        <el-input-number
          v-model="performanceIterations"
          :min="1"
          :max="10"
          size="mini"
          controls-position="right"
          :disabled="performanceTesting"
        />
        <span>{{ $t("modelConfig.performanceConcurrency") }}</span>
        <el-input-number
          v-model="performanceConcurrency"
          :min="1"
          :max="8"
          size="mini"
          controls-position="right"
          :disabled="performanceTesting"
        />
        <el-button size="mini" icon="el-icon-time" @click="openPerformanceHistory">
          {{ $t("modelConfig.performanceHistory") }}
        </el-button>
        <span v-if="performanceReport" class="performance-summary">
          {{
            $t("modelConfig.performanceSummary", {
              status: formatBenchmarkStatus(performanceReport.status),
              total: getPerformanceTotal(performanceReport),
              duration: formatResponseTime(performanceReport.durationMs),
            })
          }}
        </span>
      </div>
      <div class="performance-sample-config">
        <div class="performance-sample-config-title">
          <span>{{ $t("modelConfig.performanceSampleTitle") }}</span>
          <small>{{ $t("modelConfig.performanceSampleHint") }}</small>
        </div>
        <el-input
          v-model="performanceCustomQuestionsText"
          type="textarea"
          :rows="4"
          :maxlength="2000"
          show-word-limit
          :disabled="performanceTesting"
          :placeholder="$t('modelConfig.performanceSamplePlaceholder')"
        />
      </div>
      <div class="performance-judge-config">
        <div class="performance-judge-config-title">
          <span>{{ $t("modelConfig.judgeConfigTitle") }}</span>
          <div class="performance-judge-config-actions">
            <small>{{ $t("modelConfig.judgeConfigHint") }}</small>
            <el-button
              size="mini"
              plain
              icon="el-icon-refresh-left"
              :disabled="performanceTesting"
              @click="clearPerformanceJudgeConfig"
            >
              {{ $t("modelConfig.clearJudgeConfig") }}
            </el-button>
          </div>
        </div>
        <div class="performance-judge-model-row">
          <span class="performance-judge-label">{{ $t("modelConfig.judgeModel") }}</span>
          <el-select
            v-model="performanceJudgeModelId"
            size="mini"
            clearable
            filterable
            :disabled="performanceTesting"
            :placeholder="$t('modelConfig.judgeModelPlaceholder')"
          >
            <el-option
              v-for="item in performanceJudgeModels"
              :key="item.id"
              :label="formatJudgeModelOption(item)"
              :value="item.id"
            />
          </el-select>
          <el-button
            size="mini"
            plain
            icon="el-icon-refresh"
            :loading="performanceJudgeModelsLoading"
            :disabled="performanceTesting"
            @click="loadPerformanceJudgeModels"
          >
            {{ $t("modelConfig.refreshJudgeModels") }}
          </el-button>
        </div>
        <div class="performance-judge-model-row performance-agent-row">
          <span class="performance-judge-label">{{ $t("modelConfig.performanceAgent") }}</span>
          <el-select
            v-model="performanceAgentId"
            size="mini"
            clearable
            filterable
            :disabled="performanceTesting"
            :placeholder="$t('modelConfig.performanceAgentPlaceholder')"
            @change="handlePerformanceAgentChange"
          >
            <el-option
              v-for="item in performanceAgents"
              :key="item.id"
              :label="formatPerformanceAgentOption(item)"
              :value="item.id"
            />
          </el-select>
          <el-button
            size="mini"
            plain
            icon="el-icon-refresh"
            :loading="performanceAgentsLoading"
            :disabled="performanceTesting"
            @click="loadPerformanceAgents"
          >
            {{ $t("modelConfig.refreshAgents") }}
          </el-button>
        </div>
        <div class="performance-judge-model-row performance-agent-row">
          <span class="performance-judge-label">{{ $t("modelConfig.performanceDevice") }}</span>
          <el-select
            v-model="performanceDeviceId"
            size="mini"
            clearable
            filterable
            :disabled="performanceTesting || !performanceAgentId"
            :placeholder="$t('modelConfig.performanceDevicePlaceholder')"
          >
            <el-option
              v-for="item in performanceDevices"
              :key="item.id"
              :label="formatPerformanceDeviceOption(item)"
              :value="item.id"
            />
          </el-select>
          <el-button
            size="mini"
            plain
            icon="el-icon-refresh"
            :loading="performanceDevicesLoading"
            :disabled="performanceTesting || !performanceAgentId"
            @click="loadPerformanceDevices"
          >
            {{ $t("modelConfig.refreshDevices") }}
          </el-button>
        </div>
      </div>
      <div v-if="performanceReport" class="performance-progress-panel">
        <div class="performance-progress-head">
          <span>{{ getPerformanceStageText(performanceReport) }}</span>
          <strong>{{ getPerformanceProgress(performanceReport) }}%</strong>
        </div>
        <el-progress
          :percentage="getPerformanceProgress(performanceReport)"
          :status="getPerformanceProgressStatus(performanceReport)"
          :stroke-width="8"
        />
        <div class="performance-progress-meta">
          <span>{{ performanceReport.summary || getPerformanceDefaultHint(performanceReport) }}</span>
          <span v-if="performanceTesting">{{ $t("modelConfig.performancePollingHint") }}</span>
        </div>
        <div v-if="performanceReport.errorMessage" class="performance-progress-error">
          {{ performanceReport.errorMessage }}
        </div>
      </div>
      <div v-if="getReportCustomQuestions(performanceReport).length" class="performance-custom-question-panel">
        <div class="performance-field-label">{{ $t("modelConfig.performanceSampleUsed") }}</div>
        <el-tag
          v-for="(question, index) in getReportCustomQuestions(performanceReport)"
          :key="`${index}-${question}`"
          size="mini"
          effect="plain"
        >
          {{ question }}
        </el-tag>
      </div>
      <div v-if="performanceReport" class="performance-layout">
        <div class="performance-list">
          <el-table
            :data="performanceResults"
            size="mini"
            border
            highlight-current-row
            class="performance-table"
            @row-click="selectPerformanceResult"
          >
            <el-table-column prop="modelName" :label="$t('modelConfig.modelName')" min-width="150" />
            <el-table-column prop="providerType" :label="$t('modelConfig.provider')" width="110" />
            <el-table-column prop="testLevelText" :label="$t('modelConfig.testLevel')" width="110" />
            <el-table-column :label="$t('modelConfig.successRate')" width="90">
              <template slot-scope="scope">
                {{ formatPercent(scope.row.successRate) }}
              </template>
            </el-table-column>
            <el-table-column :label="$t('modelConfig.avgLatency')" width="110">
              <template slot-scope="scope">
                {{ formatMeasuredLatency(scope.row, "avgLatencyMs") }}
              </template>
            </el-table-column>
            <el-table-column v-if="shouldShowPerformanceColumn('llm')" :label="$t('modelConfig.ttft')" width="95">
              <template slot-scope="scope">
                {{ formatMetric(scope.row.ttftMs, " ms") }}
              </template>
            </el-table-column>
            <el-table-column
              v-if="shouldShowPerformanceColumn('llm')"
              :label="$t('modelConfig.tokensPerSecond')"
              width="105"
            >
              <template slot-scope="scope">
                {{ formatMetric(scope.row.tokensPerSecond, "") }}
              </template>
            </el-table-column>
            <el-table-column
              v-if="shouldShowPerformanceColumn('llm')"
              :label="$t('modelConfig.qualityScore')"
              width="90"
            >
              <template slot-scope="scope">
                {{ formatScore(scope.row.qualityScore) }}
              </template>
            </el-table-column>
            <el-table-column
              v-if="shouldShowPerformanceColumn('llm')"
              :label="$t('modelConfig.p0p1Failures')"
              width="90"
            >
              <template slot-scope="scope">
                <el-tag size="mini" :type="scope.row.p0p1FailureCount > 0 ? 'danger' : 'success'">
                  {{ scope.row.p0p1FailureCount || 0 }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              v-if="shouldShowPerformanceColumn('llm')"
              :label="$t('modelConfig.judgeMode')"
              width="110"
            >
              <template slot-scope="scope">
                {{ formatJudgeMode(scope.row.judgeMode) }}
              </template>
            </el-table-column>
            <el-table-column v-if="shouldShowPerformanceColumn('tts')" :label="$t('modelConfig.firstAudio')" width="100">
              <template slot-scope="scope">
                {{ formatMetric(scope.row.firstAudioMs, " ms") }}
              </template>
            </el-table-column>
            <el-table-column v-if="shouldShowPerformanceColumn('tts')" :label="$t('modelConfig.rtf')" width="80">
              <template slot-scope="scope">
                {{ formatMetric(scope.row.rtf, "") }}
              </template>
            </el-table-column>
            <el-table-column v-if="shouldShowPerformanceColumn('asr')" :label="$t('modelConfig.cer')" width="80">
              <template slot-scope="scope">
                {{ formatPercent(scope.row.cer) }}
              </template>
            </el-table-column>
            <el-table-column v-if="shouldShowPerformanceColumn('asr')" :label="$t('modelConfig.wer')" width="80">
              <template slot-scope="scope">
                {{ formatPercent(scope.row.wer) }}
              </template>
            </el-table-column>
          </el-table>
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
                v-for="item in formatScoreMap(selectedPerformanceResult.dimensionScores)"
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
                v-for="item in formatScoreMap(selectedPerformanceResult.scenarioScores)"
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

              <div v-if="getSampleContextItems(selectedPerformanceSample).length" class="performance-context-panel">
                <div
                  v-for="item in getSampleContextItems(selectedPerformanceSample)"
                  :key="item.label"
                  class="performance-context-item"
                >
                  <span>{{ item.label }}</span>
                  <p>{{ item.value }}</p>
                </div>
              </div>

              <div class="performance-dialogue-flow">
                <div
                  v-for="(message, index) in getSampleMessages(selectedPerformanceSample)"
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
                  v-if="!hasFullSampleConversation(selectedPerformanceSample)"
                  class="performance-bubble-row is-assistant is-output"
                >
                  <div class="performance-bubble-label">{{ getSampleOutputLabel(selectedPerformanceSample) }}</div>
                  <div class="performance-bubble">
                    {{ getSampleAssistantText(selectedPerformanceSample) }}
                  </div>
                </div>
              </div>

              <div v-if="getSampleExpectedBehavior(selectedPerformanceSample)" class="performance-chat-reference">
                <strong>{{ $t("modelConfig.expectedBehavior") }}</strong>
                <p>{{ getSampleExpectedBehavior(selectedPerformanceSample) }}</p>
              </div>

              <div class="performance-sample-metrics">
                <span v-if="isSampleType(selectedPerformanceSample, 'llm')">
                  {{ $t("modelConfig.ttft") }} {{ formatMetric(selectedPerformanceSample.ttftMs, " ms") }}
                </span>
                <span v-if="isSampleType(selectedPerformanceSample, 'llm')">
                  {{ $t("modelConfig.tokensPerSecond") }} {{ formatMetric(selectedPerformanceSample.tokensPerSecond, "") }}
                </span>
                <span v-if="isSampleType(selectedPerformanceSample, 'llm')">
                  {{ $t("modelConfig.qualityScore") }} {{ formatScore(selectedPerformanceSample.qualityScore) }}
                </span>
                <span v-if="isSampleType(selectedPerformanceSample, 'tts')">
                  {{ $t("modelConfig.firstAudio") }} {{ formatMetric(selectedPerformanceSample.firstAudioMs, " ms") }}
                </span>
                <span v-if="isSampleType(selectedPerformanceSample, 'tts')">
                  {{ $t("modelConfig.rtf") }} {{ formatMetric(selectedPerformanceSample.rtf, "") }}
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
                    v-for="item in formatScoreMap(getOutputDetail(selectedPerformanceSample).scores)"
                    :key="item.key"
                    size="mini"
                    effect="plain"
                  >
                    {{ $t(`modelConfig.dimension.${item.key}`) }}：{{ item.value }}
                  </el-tag>
                </div>
              </div>

              <div
                v-if="isSampleType(selectedPerformanceSample, 'llm') && selectedPerformanceSample.outputDetail"
                class="judge-reasons"
              >
                <div class="performance-field-label">{{ $t("modelConfig.judgeReasons") }}</div>
                <pre>{{ formatJudgeReasons(selectedPerformanceSample.outputDetail) }}</pre>
              </div>
              <el-collapse v-if="hasSampleDetail(selectedPerformanceSample)" class="performance-json-detail">
                <el-collapse-item :title="$t('modelConfig.performanceRawDetail')" name="detail">
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
      <div v-if="performanceReport" class="metric-notes">
        <div v-for="(note, index) in performanceReport.metricNotes || []" :key="index">
          {{ note }}
        </div>
      </div>
      <span slot="footer" class="dialog-footer">
        <el-button @click="localVisible = false">
          {{ $t("common.cancel") }}
        </el-button>
        <el-button type="primary" :loading="performanceTesting" @click="submitPerformanceTest">
          {{ $t("modelConfig.runPerformanceTest") }}
        </el-button>
      </span>
    </el-dialog>
    <el-dialog
      :title="$t('modelConfig.performanceHistoryTitle')"
      :visible.sync="performanceHistoryVisible"
      width="1120px"
      append-to-body
      class="performance-history-dialog"
    >
      <el-table
        v-loading="performanceHistoryLoading"
        :data="performanceHistory"
        size="mini"
        border
        class="performance-history-table"
      >
        <el-table-column :label="$t('modelConfig.historyCreateTime')" width="155">
          <template slot-scope="scope">
            {{ formatDateTime(scope.row.createDate) }}
          </template>
        </el-table-column>
        <el-table-column :label="$t('modelConfig.historyStatus')" width="95">
          <template slot-scope="scope">
            <el-tag size="mini" :type="getBenchmarkStatusTagType(scope.row.status)">
              {{ formatBenchmarkStatus(scope.row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('modelConfig.historyModelCount')" width="90">
          <template slot-scope="scope">
            {{ getPerformanceTotal(scope.row) }}
          </template>
        </el-table-column>
        <el-table-column prop="iterations" :label="$t('modelConfig.performanceIterations')" width="90" />
        <el-table-column prop="concurrency" :label="$t('modelConfig.performanceConcurrency')" width="110" />
        <el-table-column :label="$t('modelConfig.responseTime')" width="110">
          <template slot-scope="scope">
            {{ formatResponseTime(scope.row.durationMs) }}
          </template>
        </el-table-column>
        <el-table-column prop="summary" :label="$t('modelConfig.summary')" min-width="260" show-overflow-tooltip />
        <el-table-column :label="$t('modelConfig.historyOperation')" width="145">
          <template slot-scope="scope">
            <el-button type="text" size="mini" @click="viewPerformanceHistory(scope.row)">
              {{ $t("modelConfig.viewHistoryDetail") }}
            </el-button>
            <el-button type="text" size="mini" class="history-delete-btn" @click="deletePerformanceHistory(scope.row)">
              {{ $t("common.delete") }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!performanceHistoryLoading && !performanceHistory.length"
        :description="$t('modelConfig.noPerformanceHistory')"
        :image-size="72"
      />
      <span slot="footer" class="dialog-footer">
        <el-button @click="performanceHistoryVisible = false">
          {{ $t("modelConfig.closeHistory") }}
        </el-button>
        <el-button type="primary" :loading="performanceHistoryLoading" @click="loadPerformanceHistory">
          {{ $t("modelConfig.refreshHistory") }}
        </el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import Api from "@/apis/api";

const PERFORMANCE_JUDGE_CONFIG_STORAGE_KEY = "xiaozhi.performanceJudgeConfig";
const DEFAULT_PERFORMANCE_JUDGE_CONFIG = {
  modelId: "",
  agentId: "",
  deviceId: "",
};

export default {
  name: "ModelBenchmarkDialog",
  props: {
    visible: {
      type: Boolean,
      default: false,
    },
    modelType: {
      type: String,
      default: "llm",
    },
    selectedModels: {
      type: Array,
      default: () => [],
    },
  },
  data() {
    return {
      performanceTesting: false,
      performanceIterations: 1,
      performanceConcurrency: 1,
      performanceJudgeModelId: DEFAULT_PERFORMANCE_JUDGE_CONFIG.modelId,
      performanceJudgeModels: [],
      performanceJudgeModelsLoading: false,
      performanceAgentId: DEFAULT_PERFORMANCE_JUDGE_CONFIG.agentId,
      performanceAgents: [],
      performanceAgentsLoading: false,
      performanceDeviceId: DEFAULT_PERFORMANCE_JUDGE_CONFIG.deviceId,
      performanceDevices: [],
      performanceDevicesLoading: false,
      performanceCustomQuestionsText: "",
      performanceReport: null,
      performanceTaskId: "",
      performancePollTimer: null,
      selectedPerformanceResultId: "",
      selectedPerformanceSampleKey: "",
      performanceHistoryVisible: false,
      performanceHistoryLoading: false,
      performanceHistory: [],
    };
  },
  computed: {
    localVisible: {
      get() {
        return this.visible;
      },
      set(value) {
        this.$emit("update:visible", value);
      },
    },
    performanceResults() {
      return (this.performanceReport && this.performanceReport.results) || [];
    },
    selectedPerformanceResult() {
      const results = this.performanceResults;
      if (!results.length) {
        return null;
      }
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
      if (!samples.length) {
        return null;
      }
      return (
        samples.find((item) => this.getPerformanceSampleKey(item) === this.selectedPerformanceSampleKey) ||
        samples[0]
      );
    },
  },
  watch: {
    visible(value) {
      if (value) {
        this.openBenchmarkDialog();
      }
    },
    performanceTesting(value) {
      this.$emit("testing-change", value);
    },
  },
  created() {
    this.loadPerformanceJudgeConfig();
    this.loadPerformanceJudgeModels();
    this.loadPerformanceAgents();
  },
  beforeDestroy() {
    this.stopPerformancePolling();
  },
  methods: {
    openBenchmarkDialog() {
      this.performanceReport = null;
      this.performanceIterations = 1;
      this.performanceConcurrency = Math.max(1, Math.min(this.selectedModels.length, 8));
      this.selectedPerformanceResultId = "";
      this.selectedPerformanceSampleKey = "";
    },
    formatResponseTime(responseTimeMs) {
      if (responseTimeMs === null || responseTimeMs === undefined || responseTimeMs === "") {
        return "-";
      }
      return `${responseTimeMs} ms`;
    },
    formatPercent(value) {
      if (value === null || value === undefined || value === "") {
        return "-";
      }
      return `${Math.round(Number(value) * 10000) / 100}%`;
    },
    formatMeasuredLatency(row, field) {
      if (!row || !row.measured) {
        return this.$t("modelConfig.notMeasured");
      }
      const value = row[field];
      if (value === null || value === undefined || value === "") {
        return "-";
      }
      return `${value} ms`;
    },
    formatMetric(value, unit) {
      if (value === null || value === undefined || value === "") {
        return "-";
      }
      return `${value}${unit}`;
    },
    formatScore(value) {
      if (value === null || value === undefined || value === "") {
        return "-";
      }
      return `${Math.round(Number(value) * 100) / 100}`;
    },
    formatScoreMap(value) {
      if (!value || typeof value !== "object") {
        return [];
      }
      return Object.keys(value).map((key) => ({
        key,
        value: this.formatScore(value[key]),
      }));
    },
    formatJudgeMode(value) {
      if (!value) {
        return "-";
      }
      return String(value)
        .split(",")
        .map((item) => this.$t(`modelConfig.judgeMode.${item}`))
        .join(" / ");
    },
    parsePerformanceCustomQuestions() {
      return String(this.performanceCustomQuestionsText || "")
        .split(/\r?\n/)
        .map((item) => item.trim())
        .filter((item) => item.length > 0);
    },
    getReportCustomQuestions(report) {
      const questions = report && report.customQuestions;
      return Array.isArray(questions) ? questions.filter((item) => item) : [];
    },
    formatP0P1(value) {
      if (!Array.isArray(value) || value.length === 0) {
        return this.$t("modelConfig.none");
      }
      return value.join(", ");
    },
    formatJudgeReasons(outputDetail) {
      const reasons = outputDetail && outputDetail.reasons;
      if (!reasons || typeof reasons !== "object") {
        return "-";
      }
      return Object.keys(reasons)
        .map((key) => `${this.$t(`modelConfig.dimension.${key}`)}：${reasons[key]}`)
        .join("\n");
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
      const conversation = Array.isArray(outputDetail.conversation)
        ? outputDetail.conversation
        : [];
      if (conversation.length) {
        return conversation.filter((item) => item && item.role !== "system");
      }
      const messages = Array.isArray(inputDetail.messages) ? inputDetail.messages : [];
      if (messages.length) {
        return messages.filter((item) => item && item.role !== "system");
      }
      return [
        {
          role: "user",
          content: (sample && sample.inputSummary) || "-",
        },
      ];
    },
    hasFullSampleConversation(sample) {
      const outputDetail = this.getOutputDetail(sample);
      return Array.isArray(outputDetail.conversation) && outputDetail.conversation.length > 0;
    },
    getSampleAssistantText(sample) {
      const outputDetail = this.getOutputDetail(sample);
      return outputDetail.assistant_text || (sample && sample.outputSummary) || "-";
    },
    getSampleExpectedBehavior(sample) {
      const inputDetail = this.getInputDetail(sample);
      return inputDetail.expected_behavior || (sample && sample.referenceOutput) || "";
    },
    getSampleContextItems(sample) {
      const inputDetail = this.getInputDetail(sample);
      const items = [];
      if (inputDetail.rag_context) {
        items.push({
          label: this.$t("modelConfig.ragContext"),
          value: inputDetail.rag_context,
        });
      }
      if (inputDetail.memory_context) {
        items.push({
          label: this.$t("modelConfig.memoryContext"),
          value: inputDetail.memory_context,
        });
      }
      const rules = Array.isArray(inputDetail.safety_rules) ? inputDetail.safety_rules : [];
      if (rules.length) {
        items.push({
          label: this.$t("modelConfig.safetyRules"),
          value: rules.join("；"),
        });
      }
      return items;
    },
    formatDialogueRole(role) {
      const key = `modelConfig.dialogueRole.${role}`;
      const translated = this.$t(key);
      return translated === key ? role : translated;
    },
    formatBenchmarkStatus(status) {
      if (!status) {
        return "-";
      }
      const key = `modelConfig.benchmarkStatus.${status}`;
      const translated = this.$t(key);
      return translated === key ? status : translated;
    },
    getPerformanceProgress(report) {
      const progress = Number((report && report.progress) || 0);
      if (Number.isNaN(progress)) {
        return 0;
      }
      return Math.max(0, Math.min(100, Math.round(progress)));
    },
    getPerformanceProgressStatus(report) {
      const status = report && report.status;
      if (status === "SUCCEEDED") {
        return "success";
      }
      if (status === "FAILED" || status === "CANCELLED") {
        return "exception";
      }
      return undefined;
    },
    getBenchmarkStatusTagType(status) {
      if (status === "SUCCEEDED") {
        return "success";
      }
      if (status === "FAILED" || status === "CANCELLED") {
        return "danger";
      }
      if (status === "RUNNING") {
        return "warning";
      }
      return "info";
    },
    getPerformanceStageText(report) {
      const status = report && report.status;
      if (status === "SUBMITTING") {
        return this.$t("modelConfig.performanceSubmitting");
      }
      return this.formatBenchmarkStatus(status);
    },
    getPerformanceDefaultHint(report) {
      const status = report && report.status;
      if (status === "SUBMITTING") {
        return this.$t("modelConfig.performanceSubmittingHint");
      }
      if (status === "PENDING") {
        return this.$t("modelConfig.performancePendingHint");
      }
      if (status === "RUNNING") {
        return this.$t("modelConfig.performanceRunningHint");
      }
      if (status === "SUCCEEDED") {
        return this.$t("modelConfig.performanceSucceededHint");
      }
      if (status === "FAILED") {
        return this.$t("modelConfig.performanceFailedHint");
      }
      return this.$t("modelConfig.performanceWaitingHint");
    },
    formatDateTime(value) {
      if (!value) {
        return "-";
      }
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) {
        return String(value);
      }
      const pad = (item) => String(item).padStart(2, "0");
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
    },
    getNoPerformanceSamplesText() {
      if (this.performanceTesting) {
        return this.$t("modelConfig.performanceNoSamplesRunning");
      }
      return this.$t("modelConfig.noPerformanceSamples");
    },
    getPerformanceTotal(report) {
      if (!report) {
        return 0;
      }
      const modelIds = report.modelIds || [];
      if (modelIds.length > 0) {
        return modelIds.length;
      }
      return (report.results || []).length;
    },
    getPerformanceResultKey(row) {
      if (!row) {
        return "";
      }
      return row.modelId || `${row.modelName || ""}-${row.providerType || ""}`;
    },
    selectPerformanceResult(row) {
      this.selectedPerformanceResultId = this.getPerformanceResultKey(row);
      this.syncSelectedPerformanceSample(row);
    },
    syncSelectedPerformanceResult(report) {
      const results = (report && report.results) || [];
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
      this.syncSelectedPerformanceSample(this.selectedPerformanceResult);
    },
    getPerformanceSampleKey(sample) {
      if (!sample) {
        return "";
      }
      return String(sample.iteration || "");
    },
    selectPerformanceSample(sample) {
      this.selectedPerformanceSampleKey = this.getPerformanceSampleKey(sample);
    },
    syncSelectedPerformanceSample(result) {
      const samples = (result && result.samples) || [];
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
    getBenchmarkModelType(row) {
      return String((row && row.modelType) || this.modelType || "").toLowerCase();
    },
    shouldShowPerformanceColumn(type) {
      const results = this.performanceResults;
      if (!results.length) {
        return this.modelType === type;
      }
      return results.some((row) => this.isModelType(row, type));
    },
    isModelType(row, type) {
      const benchmarkModelType = this.getBenchmarkModelType(row);
      if (type === "llm") {
        return benchmarkModelType === "llm" || benchmarkModelType === "slm";
      }
      return benchmarkModelType === type;
    },
    isSampleType(sample, type) {
      const sampleType = String((sample && (sample.sampleType || sample.testLevel)) || "").toLowerCase();
      if (type === "llm") {
        return sampleType.includes("llm") || this.isModelType(this.selectedPerformanceResult, "llm");
      }
      return sampleType.includes(type) || this.isModelType(this.selectedPerformanceResult, type);
    },
    getSampleOutputLabel(sample) {
      if (this.isSampleType(sample, "asr")) {
        return this.$t("modelConfig.asrTextOutput");
      }
      if (this.isSampleType(sample, "tts")) {
        return this.$t("modelConfig.ttsAudioOutput");
      }
      return this.$t("modelConfig.llmReply");
    },
    hasObjectValue(value) {
      return !!value && typeof value === "object" && Object.keys(value).length > 0;
    },
    hasSampleDetail(sample) {
      return this.hasObjectValue(sample && sample.inputDetail) || this.hasObjectValue(sample && sample.outputDetail);
    },
    formatJsonDetail(value) {
      if (!value) {
        return "-";
      }
      if (typeof value === "string") {
        return value;
      }
      return JSON.stringify(value, null, 2);
    },
    loadPerformanceJudgeConfig() {
      let stored = {};
      try {
        stored = JSON.parse(window.localStorage.getItem(PERFORMANCE_JUDGE_CONFIG_STORAGE_KEY) || "{}");
      } catch (error) {
        stored = {};
      }
      const config = {
        ...DEFAULT_PERFORMANCE_JUDGE_CONFIG,
        ...stored,
      };
      this.performanceJudgeModelId = config.modelId || DEFAULT_PERFORMANCE_JUDGE_CONFIG.modelId;
      this.performanceAgentId = config.agentId || DEFAULT_PERFORMANCE_JUDGE_CONFIG.agentId;
      this.performanceDeviceId = config.deviceId || DEFAULT_PERFORMANCE_JUDGE_CONFIG.deviceId;
    },
    savePerformanceJudgeConfig(silent = false) {
      const config = {
        modelId: this.performanceJudgeModelId || "",
        agentId: this.performanceAgentId || "",
        deviceId: this.performanceDeviceId || "",
      };
      window.localStorage.setItem(PERFORMANCE_JUDGE_CONFIG_STORAGE_KEY, JSON.stringify(config));
      this.performanceJudgeModelId = config.modelId;
      this.performanceAgentId = config.agentId;
      this.performanceDeviceId = config.deviceId;
      if (!silent) {
        this.$message.success(this.$t("modelConfig.saveJudgeConfigSuccess"));
      }
    },
    clearPerformanceJudgeConfig() {
      window.localStorage.removeItem(PERFORMANCE_JUDGE_CONFIG_STORAGE_KEY);
      this.performanceJudgeModelId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.modelId;
      this.performanceAgentId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.agentId;
      this.performanceDeviceId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.deviceId;
      this.performanceDevices = [];
      this.$message.success(this.$t("modelConfig.clearJudgeConfigSuccess"));
    },
    loadPerformanceJudgeModels() {
      if (this.performanceJudgeModelsLoading) {
        return;
      }
      this.performanceJudgeModelsLoading = true;
      const loadTypes = ["llm", "slm"];
      Promise.all(loadTypes.map(
        (modelType) =>
          new Promise((resolve) => {
            Api.model.getModelList(
              {
                modelType,
                modelName: "",
                page: 1,
                limit: 100,
              },
              ({ data }) => {
                resolve(data.code === 0 ? this.filterPerformanceJudgeModels(data.data.list || []) : []);
              }
            );
          })
      )).then((groups) => {
        this.performanceJudgeModelsLoading = false;
        this.performanceJudgeModels = groups.flat();
        if (this.performanceJudgeModelId
          && !this.performanceJudgeModels.some((item) => item.id === this.performanceJudgeModelId)) {
          this.performanceJudgeModelId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.modelId;
          this.savePerformanceJudgeConfig(true);
        }
      });
    },
    filterPerformanceJudgeModels(models) {
      return models.filter((item) => {
        const modelType = String(item.modelType || "").toLowerCase();
        return (modelType === "llm" || modelType === "slm")
          && Number(item.isEnabled) === 1
          && Number(item.testStatus) === 1
          && item.id
          && item.modelName;
      });
    },
    formatJudgeModelOption(item) {
      if (!item) {
        return "-";
      }
      const configuredModelId = item.modelCode || item.id || "";
      return configuredModelId ? `${item.modelName} / ${configuredModelId}` : item.modelName;
    },
    loadPerformanceAgents() {
      if (this.performanceAgentsLoading) {
        return;
      }
      this.performanceAgentsLoading = true;
      Api.agent.getAgentList(({ data }) => {
        this.performanceAgentsLoading = false;
        if (data.code === 0) {
          this.performanceAgents = data.data || [];
          if (this.performanceAgentId
            && !this.performanceAgents.some((item) => item.id === this.performanceAgentId)) {
            this.performanceAgentId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.agentId;
            this.performanceDeviceId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.deviceId;
            this.performanceDevices = [];
            this.savePerformanceJudgeConfig(true);
          } else if (this.performanceAgentId) {
            this.loadPerformanceDevices();
          }
        } else {
          this.$message.error(data.msg || this.$t("modelConfig.fetchAgentsFailed"));
        }
      });
    },
    formatPerformanceAgentOption(item) {
      if (!item) {
        return "-";
      }
      return item.agentName || item.agentCode || item.id || "-";
    },
    handlePerformanceAgentChange() {
      this.performanceDeviceId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.deviceId;
      this.performanceDevices = [];
      this.savePerformanceJudgeConfig(true);
      if (this.performanceAgentId) {
        this.loadPerformanceDevices();
      }
    },
    loadPerformanceDevices() {
      if (this.performanceDevicesLoading || !this.performanceAgentId) {
        return;
      }
      this.performanceDevicesLoading = true;
      Api.device.getAgentBindDevices(this.performanceAgentId, ({ data }) => {
        this.performanceDevicesLoading = false;
        if (data.code === 0) {
          this.performanceDevices = data.data || [];
          if (this.performanceDeviceId
            && !this.performanceDevices.some((item) => item.id === this.performanceDeviceId)) {
            this.performanceDeviceId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.deviceId;
            this.savePerformanceJudgeConfig(true);
          }
        } else {
          this.$message.error(data.msg || this.$t("modelConfig.fetchDevicesFailed"));
        }
      });
    },
    formatPerformanceDeviceOption(item) {
      if (!item) {
        return "-";
      }
      const alias = item.alias || item.deviceType || item.board || this.$t("modelConfig.unnamedDevice");
      const mac = item.macAddress || item.id || "";
      return mac ? `${alias} / ${mac}` : alias;
    },
    openPerformanceHistory() {
      this.performanceHistoryVisible = true;
      this.loadPerformanceHistory();
    },
    loadPerformanceHistory() {
      if (this.performanceHistoryLoading) {
        return;
      }
      this.performanceHistoryLoading = true;
      Api.model.benchmarkHistory(
        {
          modelType: this.modelType,
          limit: 20,
        },
        ({ data }) => {
          this.performanceHistoryLoading = false;
          if (data.code === 0) {
            this.performanceHistory = data.data || [];
          } else {
            this.$message.error(data.msg || this.$t("modelConfig.performanceHistoryFailed"));
          }
        }
      );
    },
    viewPerformanceHistory(row) {
      if (!row || !row.id) {
        return;
      }
      this.stopPerformancePolling();
      this.performanceTesting = false;
      this.performanceTaskId = row.id;
      Api.model.benchmarkTask(row.id, ({ data }) => {
        if (data.code !== 0) {
          this.$message.error(data.msg || this.$t("modelConfig.performanceHistoryFailed"));
          return;
        }
        this.performanceReport = data.data;
        this.localVisible = true;
        this.performanceHistoryVisible = false;
        this.syncSelectedPerformanceResult(this.performanceReport);
        const status = data.data.status;
        if (status !== "SUCCEEDED" && status !== "FAILED" && status !== "CANCELLED") {
          this.performanceTesting = true;
          this.pollPerformanceTask();
        }
      });
    },
    deletePerformanceHistory(row) {
      if (!row || !row.id) {
        return;
      }
      this.$confirm(
        this.$t("modelConfig.confirmDeletePerformanceHistory"),
        this.$t("message.info"),
        {
          confirmButtonText: this.$t("common.confirm"),
          cancelButtonText: this.$t("common.cancel"),
          type: "warning",
        }
      )
        .then(() => {
          Api.model.deleteBenchmarkTask(row.id, ({ data }) => {
            if (data.code === 0) {
              this.$message.success(this.$t("modelConfig.deletePerformanceHistorySuccess"));
              if (this.performanceTaskId === row.id) {
                this.stopPerformancePolling();
                this.performanceTesting = false;
                this.performanceTaskId = "";
                this.performanceReport = null;
                this.selectedPerformanceResultId = "";
                this.selectedPerformanceSampleKey = "";
              }
              this.loadPerformanceHistory();
            } else {
              this.$message.error(data.msg || this.$t("modelConfig.deletePerformanceHistoryFailed"));
            }
          });
        })
        .catch(() => {
          this.$message.info(this.$t("common.deleteCancelled"));
        });
    },
    submitPerformanceTest() {
      if (this.performanceTesting) {
        return;
      }
      const modelIds = this.selectedModels.map((model) => model.id);
      const customQuestions = this.parsePerformanceCustomQuestions();
      if (modelIds.length === 0) {
        this.$message.warning(this.$t("modelConfig.selectModelsForPerformance"));
        return;
      }
      this.stopPerformancePolling();
      this.savePerformanceJudgeConfig(true);
      this.performanceTesting = true;
      this.performanceReport = {
        status: "SUBMITTING",
        progress: 1,
        summary: this.$t("modelConfig.performanceSubmittingHint"),
        modelIds,
        iterations: this.performanceIterations,
        concurrency: this.performanceConcurrency,
        agentId: this.performanceAgentId,
        deviceId: this.performanceDeviceId,
        customQuestions,
        results: [],
      };
      this.syncSelectedPerformanceResult(this.performanceReport);
      Api.model.performanceTest(
        {
          modelType: this.modelType,
          modelIds,
          iterations: this.performanceIterations,
          concurrency: this.performanceConcurrency,
          judgeModelId: this.performanceJudgeModelId,
          agentId: this.performanceAgentId,
          deviceId: this.performanceDeviceId,
          customQuestions,
        },
        ({ data }) => {
          if (data.code === 0) {
            this.performanceTaskId = data.data.taskId;
            this.performanceReport = {
              ...this.performanceReport,
              id: this.performanceTaskId,
              status: data.data.status || "PENDING",
              progress: 5,
              summary: this.$t("modelConfig.performancePendingHint"),
            };
            this.$message.success(this.$t("modelConfig.performanceTestStarted"));
            this.pollPerformanceTask();
          } else {
            this.performanceTesting = false;
            this.performanceReport = {
              ...this.performanceReport,
              status: "FAILED",
              progress: 100,
              summary: this.$t("modelConfig.performanceFailedHint"),
              errorMessage: data.msg || this.$t("modelConfig.performanceTestFailed"),
            };
            this.$message.error(data.msg || this.$t("modelConfig.performanceTestFailed"));
          }
        }
      );
    },
    pollPerformanceTask() {
      if (!this.performanceTaskId) {
        return;
      }
      Api.model.benchmarkTask(this.performanceTaskId, ({ data }) => {
        if (data.code !== 0) {
          this.performanceTesting = false;
          this.stopPerformancePolling();
          this.performanceReport = {
            ...this.performanceReport,
            status: "FAILED",
            progress: 100,
            summary: this.$t("modelConfig.performanceFailedHint"),
            errorMessage: data.msg || this.$t("modelConfig.performanceTestFailed"),
          };
          this.$message.error(data.msg || this.$t("modelConfig.performanceTestFailed"));
          return;
        }
        this.performanceReport = {
          ...data.data,
          modelIds: (data.data.modelIds || []).length
            ? data.data.modelIds
            : (this.performanceReport || {}).modelIds,
          customQuestions: this.getReportCustomQuestions(data.data).length
            ? this.getReportCustomQuestions(data.data)
            : this.getReportCustomQuestions(this.performanceReport),
        };
        this.syncSelectedPerformanceResult(this.performanceReport);
        const status = data.data.status;
        if (status === "SUCCEEDED" || status === "FAILED" || status === "CANCELLED") {
          this.performanceTesting = false;
          this.stopPerformancePolling();
          if (status === "SUCCEEDED") {
            this.$message.success(this.$t("modelConfig.performanceTestFinished"));
            this.$emit("finished", this.performanceReport);
          } else {
            this.$message.error(data.data.errorMessage || this.$t("modelConfig.performanceTestFailed"));
          }
          return;
        }
        this.performancePollTimer = window.setTimeout(() => this.pollPerformanceTask(), 2000);
      });
    },
    stopPerformancePolling() {
      if (this.performancePollTimer) {
        window.clearTimeout(this.performancePollTimer);
        this.performancePollTimer = null;
      }
    },
  },
};
</script>

<style lang="scss" scoped>
.performance-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 12px;
}

.performance-summary {
  color: #606266;
  margin-left: auto;
}

.performance-sample-config {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.performance-sample-config-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.performance-sample-config-title span {
  color: #303133;
  font-size: 13px;
  font-weight: 600;
}

.performance-sample-config-title small {
  color: #909399;
  line-height: 1.4;
  text-align: right;
}

.performance-judge-config {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.performance-judge-config-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.performance-judge-config-title span {
  color: #303133;
  font-size: 13px;
  font-weight: 600;
}

.performance-judge-config-title small {
  color: #909399;
  line-height: 1.4;
  text-align: right;
}

.performance-judge-config-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.performance-judge-model-row {
  display: grid;
  grid-template-columns: 80px minmax(280px, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.performance-agent-row {
  margin-top: 8px;
}

.performance-judge-label {
  color: #606266;
  font-size: 12px;
}

.performance-judge-model-row ::v-deep .el-select {
  width: 100%;
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
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  color: #303133;
  font-size: 13px;
  font-weight: 600;
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
  flex-basis: 100%;
}

.performance-layout {
  display: grid;
  grid-template-columns: minmax(500px, 0.9fr) minmax(560px, 1.1fr);
  gap: 14px;
  align-items: start;
}

.performance-table {
  width: 100%;
}

.performance-history-table {
  width: 100%;
}

.history-delete-btn {
  color: #f56c6c;
}

.performance-history-dialog ::v-deep .el-dialog__body {
  max-height: 70vh;
  overflow: auto;
}

.performance-detail {
  border: 1px solid #edf0f5;
  border-radius: 4px;
  max-height: 640px;
  overflow: auto;
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
}

.judge-summary {
  margin-bottom: 10px;
  padding: 10px;
  border: 1px solid #edf0f5;
  border-radius: 4px;
  background: #fff;
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
  padding: 8px;
  border: 1px solid #edf0f5;
  border-radius: 4px;
  background: #fff;
  color: #606266;
  font-size: 12px;
}

.judge-sample-detail > div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
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

.judge-score-panel {
  margin-top: 8px;
  padding: 10px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #fff;
}

.performance-json-detail {
  margin-top: 8px;
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

.metric-notes {
  margin-top: 12px;
  padding: 10px 12px;
  background: #f5f7fa;
  color: #606266;
  line-height: 1.7;
  font-size: 12px;
  border-radius: 4px;
}

@media (max-width: 1100px) {
  .performance-layout {
    grid-template-columns: 1fr;
  }
}
</style>
