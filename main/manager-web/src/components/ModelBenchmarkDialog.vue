<template>
  <div>
    <el-dialog
      :title="$t('modelConfig.performanceTestTitle')"
      :visible.sync="localVisible"
      width="1280px"
      append-to-body
      class="performance-result-dialog"
    >
      <BenchmarkConfigPanel
        ref="configPanel"
        :model-type="modelType"
        :disabled="performanceTesting"
        :iterations="performanceIterations"
        :concurrency="performanceConcurrency"
        :selected-judge-model-id="performanceJudgeModelId"
        :selected-agent-id="performanceAgentId"
        :selected-device-id="performanceDeviceId"
        :report="performanceReport"
        @show-history="openPerformanceHistory"
        @update:iterations="performanceIterations = $event"
        @update:concurrency="performanceConcurrency = $event"
        @update:judge-model="onUpdateJudgeModel"
        @update:agent="onUpdateAgent"
        @update:device="onUpdateDevice"
        @clear-judge="onClearJudge"
      />
      <BenchmarkResultPanel
        ref="resultPanel"
        :report="performanceReport"
        :testing="performanceTesting"
      />
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
import BenchmarkConfigPanel from "./BenchmarkConfigPanel.vue";
import BenchmarkResultPanel from "./BenchmarkResultPanel.vue";

const PERFORMANCE_JUDGE_CONFIG_STORAGE_KEY = "xiaozhi.performanceJudgeConfig";
const DEFAULT_PERFORMANCE_JUDGE_CONFIG = {
  modelId: "",
  agentId: "",
  deviceId: "",
};

export default {
  name: "ModelBenchmarkDialog",
  components: {
    BenchmarkConfigPanel,
    BenchmarkResultPanel,
  },
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
      performanceAgentId: DEFAULT_PERFORMANCE_JUDGE_CONFIG.agentId,
      performanceDeviceId: DEFAULT_PERFORMANCE_JUDGE_CONFIG.deviceId,
      performanceReport: null,
      performanceTaskId: "",
      performancePollTimer: null,
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
    normalizedModelType() {
      return String(this.modelType || "").toLowerCase();
    },
    isLlmBenchmark() {
      return this.normalizedModelType === "llm" || this.normalizedModelType === "slm";
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
    if (this.isLlmBenchmark) {
      this.loadPerformanceJudgeConfig();
    }
  },
  beforeDestroy() {
    this.stopPerformancePolling();
  },
  methods: {
    openBenchmarkDialog() {
      this.performanceReport = null;
      this.performanceIterations = 1;
      this.performanceConcurrency = Math.max(1, Math.min(this.selectedModels.length, 8));
      if (this.isLlmBenchmark) {
        this.loadPerformanceJudgeConfig();
      } else {
        this.performanceJudgeModelId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.modelId;
        this.performanceAgentId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.agentId;
        this.performanceDeviceId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.deviceId;
      }
    },

    onUpdateJudgeModel(val) {
      this.performanceJudgeModelId = val || "";
      this.savePerformanceJudgeConfig(true);
    },
    onUpdateAgent(val) {
      this.performanceAgentId = val || "";
      this.performanceDeviceId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.deviceId;
      this.savePerformanceJudgeConfig(true);
    },
    onUpdateDevice(val) {
      this.performanceDeviceId = val || "";
      this.savePerformanceJudgeConfig(true);
    },
    onClearJudge() {
      this.performanceJudgeModelId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.modelId;
      this.performanceAgentId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.agentId;
      this.performanceDeviceId = DEFAULT_PERFORMANCE_JUDGE_CONFIG.deviceId;
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
    getPerformanceTotal(report) {
      if (!report) return 0;
      const modelIds = report.modelIds || [];
      if (modelIds.length > 0) return modelIds.length;
      return (report.results || []).length;
    },
    getReportCustomQuestions(report) {
      const questions = report && report.customQuestions;
      return Array.isArray(questions) ? questions.filter((item) => item) : [];
    },
    getBenchmarkStatusTagType(status) {
      if (status === "SUCCEEDED") return "success";
      if (status === "FAILED" || status === "CANCELLED") return "danger";
      if (status === "RUNNING") return "warning";
      return "info";
    },
    loadPerformanceJudgeConfig() {
      let stored = {};
      try {
        stored = JSON.parse(window.localStorage.getItem(PERFORMANCE_JUDGE_CONFIG_STORAGE_KEY) || "{}");
      } catch (error) {
        stored = {};
      }
      const config = { ...DEFAULT_PERFORMANCE_JUDGE_CONFIG, ...stored };
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

    formatDateTime(value) {
      if (!value) return "-";
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) return String(value);
      const pad = (item) => String(item).padStart(2, "0");
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
    },
    openPerformanceHistory() {
      this.performanceHistoryVisible = true;
      this.loadPerformanceHistory();
    },
    loadPerformanceHistory() {
      if (this.performanceHistoryLoading) return;
      this.performanceHistoryLoading = true;
      Api.model.benchmarkHistory(
        { modelType: this.modelType, limit: 20 },
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
      if (!row || !row.id) return;
      this.stopPerformancePolling();
      this.performanceTesting = false;
      this.performanceTaskId = row.id;
      Api.model.benchmarkTask(row.id, ({ data }) => {
        if (data.code !== 0) {
          this.$message.error(data.msg || this.$t("modelConfig.performanceHistoryFailed"));
          return;
        }
        this.performanceReport = data.data;
        this.performanceHistoryVisible = false;
        const status = data.data.status;
        if (status !== "SUCCEEDED" && status !== "FAILED" && status !== "CANCELLED") {
          this.performanceTesting = true;
          this.pollPerformanceTask();
        }
      });
    },
    deletePerformanceHistory(row) {
      if (!row || !row.id) return;
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
      if (this.performanceTesting) return;
      const modelIds = this.selectedModels.map((model) => model.id);
      const customQuestions = this.$refs.configPanel
        ? this.$refs.configPanel.parsePerformanceCustomQuestions()
        : [];
      if (modelIds.length === 0) {
        this.$message.warning(this.$t("modelConfig.selectModelsForPerformance"));
        return;
      }
      this.stopPerformancePolling();
      if (this.isLlmBenchmark) {
        this.savePerformanceJudgeConfig(true);
      }
      this.performanceTesting = true;
      this.performanceReport = {
        status: "SUBMITTING",
        progress: 1,
        summary: this.$t("modelConfig.performanceSubmittingHint"),
        modelIds,
        iterations: this.performanceIterations,
        concurrency: this.performanceConcurrency,
        agentId: this.isLlmBenchmark ? this.performanceAgentId : "",
        deviceId: this.isLlmBenchmark ? this.performanceDeviceId : "",
        customQuestions: this.isLlmBenchmark ? customQuestions : [],
        results: [],
      };
      const request = {
        modelType: this.modelType,
        modelIds,
        iterations: this.performanceIterations,
        concurrency: this.performanceConcurrency,
      };
      if (this.isLlmBenchmark) {
        request.judgeModelId = this.performanceJudgeModelId;
        request.agentId = this.performanceAgentId;
        request.deviceId = this.performanceDeviceId;
        request.customQuestions = customQuestions;
      }
      Api.model.performanceTest(request, ({ data }) => {
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
      });
    },
    pollPerformanceTask() {
      if (!this.performanceTaskId) return;
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
.performance-result-dialog ::v-deep .el-dialog {
  max-width: calc(100vw - 24px);
}

.performance-result-dialog ::v-deep .el-dialog__body {
  max-height: 72vh;
  overflow: auto;
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
</style>
