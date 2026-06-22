<template>
  <div class="welcome">
    <HeaderBar />

    <div class="operation-bar">
      <h2 class="page-title">{{ $t("modelConfig." + activeTab) }}</h2>
      <div class="action-group">
        <div class="search-group">
          <el-input
            :placeholder="$t('modelConfig.searchPlaceholder')"
            v-model="search"
            class="search-input"
            clearable
            @keyup.enter.native="handleSearch"
            style="width: 240px"
          />
          <el-button class="btn-search" @click="handleSearch">
            {{ $t("modelConfig.search") }}
          </el-button>
        </div>
      </div>
    </div>

    <!-- 主体内容 -->
    <div class="main-wrapper">
      <div class="content-panel">
        <!-- 左侧导航 -->
        <el-menu
          :default-active="activeTab"
          class="nav-panel"
          @select="handleMenuSelect"
          style="background-size: cover; background-position: center"
        >
          <el-menu-item index="vad">
            <span class="menu-text">{{ $t("modelConfig.vad") }}</span>
          </el-menu-item>
          <el-menu-item index="asr">
            <span class="menu-text">{{ $t("modelConfig.asr") }}</span>
          </el-menu-item>
          <el-menu-item index="llm">
            <span class="menu-text">{{ $t("modelConfig.llm") }}</span>
          </el-menu-item>
          <el-menu-item index="slm">
            <span class="menu-text">{{ $t("modelConfig.slm") }}</span>
          </el-menu-item>
          <el-menu-item index="vllm">
            <span class="menu-text">{{ $t("modelConfig.vllm") }}</span>
          </el-menu-item>
          <el-menu-item index="intent">
            <span class="menu-text">{{ $t("modelConfig.intent") }}</span>
          </el-menu-item>
          <el-menu-item index="tts">
            <span class="menu-text">{{ $t("modelConfig.tts") }}</span>
          </el-menu-item>
          <el-menu-item index="memory">
            <span class="menu-text">{{ $t("modelConfig.memory") }}</span>
          </el-menu-item>
          <el-menu-item index="rag">
            <span class="menu-text">{{ $t("modelConfig.rag") }}</span>
          </el-menu-item>
        </el-menu>

        <!-- 右侧内容 -->
        <div class="content-area">
          <el-card class="model-card" shadow="never">
            <el-table
              ref="modelTable"
              style="width: 100%"
              v-loading="loading"
              :element-loading-text="$t('modelConfig.loading')"
              element-loading-spinner="el-icon-loading"
              element-loading-background="rgba(255, 255, 255, 0.7)"
              :header-cell-style="{ background: 'transparent' }"
              :data="modelList"
              class="transparent-table"
              header-row-class-name="table-header"
              :header-cell-class-name="headerCellClassName"
              @selection-change="handleSelectionChange"
            >
              <el-table-column
                type="selection"
                width="55"
                align="center"
                :cell-class-name="selectionCellClassName"
              ></el-table-column>
              <el-table-column
                :label="$t('modelConfig.modelId')"
                prop="id"
                align="center"
              ></el-table-column>
              <el-table-column
                :label="$t('modelConfig.modelName')"
                prop="modelName"
                align="center"
              ></el-table-column>
              <el-table-column :label="$t('modelConfig.provider')" align="center">
                <template slot-scope="scope">
                  {{ scope.row.configJson.type || $t("modelConfig.unknown") }}
                </template>
              </el-table-column>
              <el-table-column :label="$t('modelConfig.isEnabled')" align="center">
                <template slot-scope="scope">
                  <el-tooltip
                    :disabled="canToggleEnabled(scope.row)"
                    :content="$t('modelConfig.enableRequiresTestPassed')"
                    placement="top"
                    effect="light"
                  >
                    <el-switch
                      v-model="scope.row.isEnabled"
                      class="custom-switch"
                      :active-value="1"
                      :inactive-value="0"
                      :disabled="!canToggleEnabled(scope.row)"
                      @change="handleStatusChange(scope.row, $event)"
                    />
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column :label="$t('modelConfig.testStatus')" align="center" width="120px">
                <template slot-scope="scope">
                  <el-tooltip
                    :disabled="!scope.row.testMessage"
                    :content="scope.row.testMessage"
                    placement="top"
                    effect="light"
                  >
                    <el-tag
                      size="mini"
                      :type="getTestStatusTagType(scope.row.testStatus)"
                    >
                      {{ getTestStatusText(scope.row.testStatus) }}
                    </el-tag>
                  </el-tooltip>
                </template>
              </el-table-column>
              <el-table-column
                v-if="activeTab === 'tts'"
                :label="$t('modelConfig.voiceManagement')"
                align="center"
              >
                <template slot-scope="scope">
                  <el-button
                    type="text"
                    size="mini"
                    @click="openTtsDialog(scope.row)"
                    class="voice-management-btn"
                  >
                    {{ $t("modelConfig.voiceManagement") }}
                  </el-button>
                </template>
              </el-table-column>
              <el-table-column
                :label="$t('modelConfig.action')"
                align="center"
                width="260px"
              >
                <template slot-scope="scope">
                  <el-button
                    type="text"
                    size="mini"
                    :loading="testingModelId === scope.row.id"
                    @click="testModel(scope.row)"
                    class="edit-btn"
                  >
                    {{ $t("modelConfig.test") }}
                  </el-button>
                  <el-button
                    type="text"
                    size="mini"
                    @click="editModel(scope.row)"
                    class="edit-btn"
                  >
                    {{ $t("modelConfig.edit") }}
                  </el-button>
                  <el-button
                    type="text"
                    size="mini"
                    @click="duplicateModel(scope.row)"
                    class="edit-btn"
                  >
                    {{ $t("modelConfig.duplicate") }}
                  </el-button>
                  <el-button
                    type="text"
                    size="mini"
                    @click="deleteModel(scope.row)"
                    class="delete-btn"
                  >
                    {{ $t("modelConfig.delete") }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="table-footer">
              <div class="batch-actions">
                <el-button size="mini" type="primary" @click="selectAll">
                  {{
                    isAllSelected
                      ? $t("modelConfig.deselectAll")
                      : $t("modelConfig.selectAll")
                  }}
                </el-button>
                <el-button type="success" size="mini" @click="addModel" class="add-btn">
                  {{ $t("modelConfig.add") }}
                </el-button>
                <el-button
                  size="mini"
                  type="danger"
                  icon="el-icon-delete"
                  @click="batchDelete"
                >
                  {{ $t("modelConfig.delete") }}
                </el-button>
                <el-button
                  size="mini"
                  type="warning"
                  icon="el-icon-data-analysis"
                  :loading="performanceTesting"
                  @click="runPerformanceTest"
                >
                  {{ $t("modelConfig.performanceTest") }}
                </el-button>
              </div>
              <div class="custom-pagination">
                <el-select
                  v-model="pageSize"
                  @change="handlePageSizeChange"
                  class="page-size-select"
                >
                  <el-option
                    v-for="item in pageSizeOptions"
                    :key="item"
                    :label="$t('modelConfig.itemsPerPage', { items: item })"
                    :value="item"
                  >
                  </el-option>
                </el-select>

                <button
                  class="pagination-btn"
                  :disabled="currentPage === 1"
                  @click="goFirst"
                >
                  {{ $t("modelConfig.firstPage") }}
                </button>
                <button
                  class="pagination-btn"
                  :disabled="currentPage === 1"
                  @click="goPrev"
                >
                  {{ $t("modelConfig.prevPage") }}
                </button>

                <button
                  v-for="page in visiblePages"
                  :key="page"
                  class="pagination-btn"
                  :class="{ active: page === currentPage }"
                  @click="goToPage(page)"
                >
                  {{ page }}
                </button>

                <button
                  class="pagination-btn"
                  :disabled="currentPage === pageCount"
                  @click="goNext"
                >
                  {{ $t("modelConfig.nextPage") }}
                </button>
                <span class="total-text">{{
                  $t("modelConfig.totalRecords", { total })
                }}</span>
              </div>
            </div>
          </el-card>
        </div>
      </div>

      <ModelEditDialog
        :modelType="activeTab"
        :visible.sync="editDialogVisible"
        :modelData="editModelData"
        @save="handleModelSave"
        @test="handleModelSaveAndTest"
      />
      <TtsModel
        :visible.sync="ttsDialogVisible"
        :ttsModelId="selectedTtsModelId"
        :modelConfig="selectedModelConfig"
      />
      <AddModelDialog
        :modelType="activeTab"
        :visible.sync="addDialogVisible"
        @confirm="handleAddConfirm"
      />
      <el-dialog
        :title="$t('modelConfig.testDetailTitle')"
        :visible.sync="testResultDialogVisible"
        width="640px"
        append-to-body
        class="test-result-dialog"
      >
        <div v-if="currentTestResult" class="test-result-content">
          <div class="test-result-row">
            <span class="test-result-label">{{ $t("modelConfig.testResult") }}</span>
            <el-tag size="mini" :type="isModelTestVerified(currentTestResult) ? 'success' : 'danger'">
              {{
                isModelTestVerified(currentTestResult)
                  ? $t("modelConfig.testPassed")
                  : $t("modelConfig.testFailed")
              }}
            </el-tag>
            <span class="test-result-message">{{ currentTestResult.message || "-" }}</span>
          </div>
          <div class="test-result-row">
            <span class="test-result-label">{{ $t("modelConfig.responseTime") }}</span>
            <span>{{ formatResponseTime(currentTestResult.responseTimeMs) }}</span>
          </div>
          <div class="test-result-block">
            <div class="test-result-label">{{ $t("modelConfig.testInput") }}</div>
            <div v-if="currentTestResult.testImageDataUrl" class="test-result-image-wrap">
              <img
                class="test-result-image"
                :src="currentTestResult.testImageDataUrl"
                alt="test image"
              />
            </div>
            <div v-if="currentTestResult.testAudioDataUrl" class="test-result-audio-wrap">
              <audio class="test-result-audio" :src="currentTestResult.testAudioDataUrl" controls />
            </div>
            <pre>{{ currentTestResult.testInput || "-" }}</pre>
          </div>
          <div class="test-result-block">
            <div class="test-result-label">{{ $t("modelConfig.testOutput") }}</div>
            <pre>{{ formatTestOutput(currentTestResult.testOutput) }}</pre>
          </div>
        </div>
      </el-dialog>
      <el-dialog
        :title="$t('modelConfig.performanceTestTitle')"
        :visible.sync="performanceDialogVisible"
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
          <el-button
            size="mini"
            icon="el-icon-time"
            @click="openPerformanceHistory"
          >
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
        <div class="performance-judge-config">
          <div class="performance-judge-config-title">
            <span>{{ $t("modelConfig.judgeConfigTitle") }}</span>
            <small>{{ $t("modelConfig.judgeConfigHint") }}</small>
          </div>
          <div class="performance-judge-config-grid">
            <el-input
              v-model="performanceJudgeBaseUrl"
              size="mini"
              :disabled="performanceTesting"
              :placeholder="$t('modelConfig.judgeBaseUrl')"
            >
              <template slot="prepend">{{ $t("modelConfig.judgeBaseUrl") }}</template>
            </el-input>
            <el-input
              v-model="performanceJudgeModel"
              size="mini"
              :disabled="performanceTesting"
              :placeholder="$t('modelConfig.judgeModel')"
            >
              <template slot="prepend">{{ $t("modelConfig.judgeModel") }}</template>
            </el-input>
            <el-input
              v-model="performanceJudgeApiKey"
              size="mini"
              show-password
              clearable
              :disabled="performanceTesting"
              :placeholder="$t('modelConfig.judgeApiKeyPlaceholder')"
            >
              <template slot="prepend">{{ $t("modelConfig.judgeApiKey") }}</template>
            </el-input>
            <div class="performance-judge-timeout">
              <span>{{ $t("modelConfig.judgeTimeout") }}</span>
              <el-input-number
                v-model="performanceJudgeTimeout"
                :min="5"
                :max="180"
                size="mini"
                controls-position="right"
                :disabled="performanceTesting"
              />
            </div>
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
          <div
            v-if="performanceReport.errorMessage"
            class="performance-progress-error"
          >
            {{ performanceReport.errorMessage }}
          </div>
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
              <el-table-column
                prop="modelName"
                :label="$t('modelConfig.modelName')"
                min-width="150"
              />
              <el-table-column
                prop="providerType"
                :label="$t('modelConfig.provider')"
                width="110"
              />
              <el-table-column
                prop="testLevelText"
                :label="$t('modelConfig.testLevel')"
                width="110"
              />
              <el-table-column
                :label="$t('modelConfig.successRate')"
                width="90"
              >
                <template slot-scope="scope">
                  {{ formatPercent(scope.row.successRate) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="$t('modelConfig.avgLatency')"
                width="110"
              >
                <template slot-scope="scope">
                  {{ formatMeasuredLatency(scope.row, "avgLatencyMs") }}
                </template>
              </el-table-column>
              <el-table-column
                v-if="shouldShowPerformanceColumn('llm')"
                :label="$t('modelConfig.ttft')"
                width="95"
              >
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
                  <el-tag
                    size="mini"
                    :type="scope.row.p0p1FailureCount > 0 ? 'danger' : 'success'"
                  >
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
              <el-table-column
                v-if="shouldShowPerformanceColumn('tts')"
                :label="$t('modelConfig.firstAudio')"
                width="100"
              >
                <template slot-scope="scope">
                  {{ formatMetric(scope.row.firstAudioMs, " ms") }}
                </template>
              </el-table-column>
              <el-table-column
                v-if="shouldShowPerformanceColumn('tts')"
                :label="$t('modelConfig.rtf')"
                width="80"
              >
                <template slot-scope="scope">
                  {{ formatMetric(scope.row.rtf, "") }}
                </template>
              </el-table-column>
              <el-table-column
                v-if="shouldShowPerformanceColumn('asr')"
                :label="$t('modelConfig.cer')"
                width="80"
              >
                <template slot-scope="scope">
                  {{ formatPercent(scope.row.cer) }}
                </template>
              </el-table-column>
              <el-table-column
                v-if="shouldShowPerformanceColumn('asr')"
                :label="$t('modelConfig.wer')"
                width="80"
              >
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
            <div
              v-if="selectedPerformanceResult && isModelType(selectedPerformanceResult, 'llm')"
              class="judge-summary"
            >
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
                  <span class="performance-chip-meta">{{ sample.success ? $t("modelConfig.testSuccess") : $t("modelConfig.testFailed") }}</span>
                  <span class="performance-chip-meta">{{ formatResponseTime(sample.responseTimeMs) }}</span>
                </el-button>
              </div>
              <div v-if="selectedPerformanceSample" class="performance-chat-turn">
                <div class="performance-turn-meta">
                  <span>{{ $t("modelConfig.performanceRound", { iteration: selectedPerformanceSample.iteration }) }}</span>
                  <span v-if="selectedPerformanceSample.scenario">{{ selectedPerformanceSample.scenario }}</span>
                  <el-tag
                    size="mini"
                    :type="selectedPerformanceSample.success ? 'success' : 'danger'"
                  >
                    {{ selectedPerformanceSample.success ? $t("modelConfig.testSuccess") : $t("modelConfig.testFailed") }}
                  </el-tag>
                  <span>{{ formatResponseTime(selectedPerformanceSample.responseTimeMs) }}</span>
                </div>

                <div
                  v-if="getSampleContextItems(selectedPerformanceSample).length"
                  class="performance-context-panel"
                >
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
                  <div class="performance-bubble-row is-assistant is-output">
                    <div class="performance-bubble-label">{{ getSampleOutputLabel(selectedPerformanceSample) }}</div>
                    <div class="performance-bubble">
                      {{ getSampleAssistantText(selectedPerformanceSample) }}
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

                <div class="performance-sample-metrics">
                  <span v-if="isSampleType(selectedPerformanceSample, 'llm')">{{ $t("modelConfig.ttft") }} {{ formatMetric(selectedPerformanceSample.ttftMs, " ms") }}</span>
                  <span v-if="isSampleType(selectedPerformanceSample, 'llm')">{{ $t("modelConfig.tokensPerSecond") }} {{ formatMetric(selectedPerformanceSample.tokensPerSecond, "") }}</span>
                  <span v-if="isSampleType(selectedPerformanceSample, 'llm')">{{ $t("modelConfig.qualityScore") }} {{ formatScore(selectedPerformanceSample.qualityScore) }}</span>
                  <span v-if="isSampleType(selectedPerformanceSample, 'tts')">{{ $t("modelConfig.firstAudio") }} {{ formatMetric(selectedPerformanceSample.firstAudioMs, " ms") }}</span>
                  <span v-if="isSampleType(selectedPerformanceSample, 'tts')">{{ $t("modelConfig.rtf") }} {{ formatMetric(selectedPerformanceSample.rtf, "") }}</span>
                  <span v-if="isSampleType(selectedPerformanceSample, 'asr')">{{ $t("modelConfig.cer") }} {{ formatPercent(selectedPerformanceSample.cer) }}</span>
                  <span v-if="isSampleType(selectedPerformanceSample, 'asr')">{{ $t("modelConfig.wer") }} {{ formatPercent(selectedPerformanceSample.wer) }}</span>
                </div>

                <div
                  v-if="isSampleType(selectedPerformanceSample, 'llm')"
                  class="judge-sample-detail"
                >
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

                <div
                  v-if="isSampleType(selectedPerformanceSample, 'llm')"
                  class="judge-score-panel"
                >
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
                <el-collapse
                  v-if="hasSampleDetail(selectedPerformanceSample)"
                  class="performance-json-detail"
                >
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
          <div
            v-for="(note, index) in performanceReport.metricNotes || []"
            :key="index"
          >
            {{ note }}
          </div>
        </div>
        <span slot="footer" class="dialog-footer">
          <el-button @click="performanceDialogVisible = false">
            {{ $t("common.cancel") }}
          </el-button>
          <el-button
            type="primary"
            :loading="performanceTesting"
            @click="submitPerformanceTest"
          >
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
          <el-table-column
            :label="$t('modelConfig.historyCreateTime')"
            width="155"
          >
            <template slot-scope="scope">
              {{ formatDateTime(scope.row.createDate) }}
            </template>
          </el-table-column>
          <el-table-column
            :label="$t('modelConfig.historyStatus')"
            width="95"
          >
            <template slot-scope="scope">
              <el-tag
                size="mini"
                :type="getBenchmarkStatusTagType(scope.row.status)"
              >
                {{ formatBenchmarkStatus(scope.row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            :label="$t('modelConfig.historyModelCount')"
            width="90"
          >
            <template slot-scope="scope">
              {{ getPerformanceTotal(scope.row) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="iterations"
            :label="$t('modelConfig.performanceIterations')"
            width="90"
          />
          <el-table-column
            prop="concurrency"
            :label="$t('modelConfig.performanceConcurrency')"
            width="110"
          />
          <el-table-column
            :label="$t('modelConfig.responseTime')"
            width="110"
          >
            <template slot-scope="scope">
              {{ formatResponseTime(scope.row.durationMs) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="summary"
            :label="$t('modelConfig.summary')"
            min-width="260"
            show-overflow-tooltip
          />
          <el-table-column
            :label="$t('modelConfig.historyOperation')"
            width="100"
          >
            <template slot-scope="scope">
              <el-button
                type="text"
                size="mini"
                @click="viewPerformanceHistory(scope.row)"
              >
                {{ $t("modelConfig.viewHistoryDetail") }}
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
          <el-button
            type="primary"
            :loading="performanceHistoryLoading"
            @click="loadPerformanceHistory"
          >
            {{ $t("modelConfig.refreshHistory") }}
          </el-button>
        </span>
      </el-dialog>
    </div>
    <el-footer>
      <version-footer />
    </el-footer>
  </div>
</template>

<script>
import Api from "@/apis/api";
import AddModelDialog from "@/components/AddModelDialog.vue";
import HeaderBar from "@/components/HeaderBar.vue";
import ModelEditDialog from "@/components/ModelEditDialog.vue";
import TtsModel from "@/components/TtsModel.vue";
import VersionFooter from "@/components/VersionFooter.vue";
export default {
  components: { HeaderBar, ModelEditDialog, TtsModel, AddModelDialog, VersionFooter },
  data() {
    return {
      addDialogVisible: false,
      activeTab: "llm",
      search: "",
      editDialogVisible: false,
      editModelData: {},
      ttsDialogVisible: false,
      selectedTtsModelId: "",
      modelList: [],
      pageSizeOptions: [10, 20, 50, 100],
      currentPage: 1,
      pageSize: 10,
      total: 0,
      selectedModels: [],
      isAllSelected: false,
      loading: false,
      selectedModelConfig: {},
      testingModelId: "",
      testResultDialogVisible: false,
      currentTestResult: null,
      performanceDialogVisible: false,
      performanceTesting: false,
      performanceIterations: 1,
      performanceConcurrency: 1,
      performanceJudgeBaseUrl: "https://api.deepseek.com",
      performanceJudgeModel: "deepseek-v4-pro",
      performanceJudgeApiKey: "",
      performanceJudgeTimeout: 60,
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

  created() {
    this.loadData();
  },

  mounted() {
    // 在组件挂载后确保表头翻译文本正确显示
    setTimeout(() => {
      this.updateSelectionHeaderText();
    }, 100);
  },

  updated() {
    // 在组件更新后重新设置表头翻译文本
    this.updateSelectionHeaderText();
  },

  beforeDestroy() {
    this.stopPerformancePolling();
  },

  computed: {
    modelTypeText() {
      return (
        this.$t("modelConfig." + this.activeTab) || this.$t("modelConfig.modelConfig")
      );
    },
    pageCount() {
      return Math.ceil(this.total / this.pageSize);
    },
    visiblePages() {
      const pages = [];
      const maxVisible = 3;
      let start = Math.max(1, this.currentPage - 1);
      let end = Math.min(this.pageCount, start + maxVisible - 1);

      if (end - start + 1 < maxVisible) {
        start = Math.max(1, end - maxVisible + 1);
      }

      for (let i = start; i <= end; i++) {
        pages.push(i);
      }
      return pages;
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

  methods: {
    // 更新选择列表头翻译文本
    updateSelectionHeaderText() {
      const thElement = document.querySelector(`.el-table__header th:nth-child(1) .cell`);
      if (thElement) {
        thElement.setAttribute("data-content", this.$t("modelConfig.select"));
      }
    },
    handlePageSizeChange(val) {
      this.pageSize = val;
      this.currentPage = 1;
      this.loadData();
    },
    openTtsDialog(row) {
      this.selectedTtsModelId = row.id;
      this.selectedModelConfig = row;
      this.ttsDialogVisible = true;
    },
    headerCellClassName({ column, columnIndex }) {
      if (columnIndex === 0) {
        return "custom-selection-header";
      }
      return "";
    },
    selectionCellClassName({ row, column, rowIndex, columnIndex }) {
      // 只对表头行设置data-content
      if (rowIndex === undefined) {
        // 使用setTimeout确保DOM已经渲染完成
        setTimeout(() => {
          const thElement = document.querySelector(
            `.el-table__header th:nth-child(1) .cell`
          );
          if (thElement) {
            thElement.setAttribute("data-content", this.$t("modelConfig.select"));
          }
        }, 0);
      }
      return "";
    },
    handleMenuSelect(index) {
      this.activeTab = index;
      this.currentPage = 1; // 重置到第一页
      this.pageSize = 10; // 可选：重置每页条数
      this.loadData();
    },
    handleSearch() {
      this.currentPage = 1;
      this.loadData();
    },
    // 批量删除
    batchDelete() {
      if (this.selectedModels.length === 0) {
        this.$message.warning(this.$t("modelConfig.selectModelsFirst"));
        return;
      }

      this.$confirm(this.$t("modelConfig.confirmBatchDelete"), this.$t("message.info"), {
        confirmButtonText: this.$t("common.confirm"),
        cancelButtonText: this.$t("common.cancel"),
        type: "warning",
      })
        .then(() => {
          const deletePromises = this.selectedModels.map(
            (model) =>
              new Promise((resolve) => {
                Api.model.deleteModel(model.id, ({ data }) => resolve(data.code === 0));
              })
          );

          Promise.all(deletePromises).then((results) => {
            if (results.every(Boolean)) {
              this.$message.success({
                message: this.$t("modelConfig.batchDeleteSuccess"),
                showClose: true,
              });
              this.loadData();
            } else {
              this.$message.error({
                message: this.$t("modelConfig.partialDeleteFailed"),
                showClose: true,
              });
            }
          });
        })
        .catch(() => {
          this.$message.info(this.$t("modelConfig.deleteCancelled"));
        });
    },
    addModel() {
      this.addDialogVisible = true;
    },
    editModel(model) {
      this.editModelData = JSON.parse(JSON.stringify(model));
      this.editDialogVisible = true;
    },
    duplicateModel(model) {
      this.editModelData = JSON.parse(JSON.stringify(model));
      this.editModelData.duplicateMode = true;
      this.editDialogVisible = true;
    },
    getTestStatusText(status) {
      if (status === 1) {
        return this.$t("modelConfig.testPassed");
      }
      if (status === 2) {
        return this.$t("modelConfig.testFailed");
      }
      return this.$t("modelConfig.untested");
    },
    getTestStatusTagType(status) {
      if (status === 1) {
        return "success";
      }
      if (status === 2) {
        return "danger";
      }
      return "info";
    },
    canToggleEnabled(model) {
      return model.isEnabled === 1 || model.testStatus === 1;
    },
    clearTestingModelState() {
      this.testingModelId = "";
    },
    showTestResult(result) {
      this.currentTestResult = result || null;
      this.testResultDialogVisible = !!result;
    },
    isModelTestVerified(result) {
      return !!result && (result.verified === true || result.testStatus === 1);
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
      return String((row && row.modelType) || this.activeTab || "").toLowerCase();
    },
    shouldShowPerformanceColumn(type) {
      const results = this.performanceResults;
      if (!results.length) {
        return this.activeTab === type;
      }
      return results.some((row) => this.isModelType(row, type));
    },
    isModelType(row, type) {
      const modelType = this.getBenchmarkModelType(row);
      if (type === "llm") {
        return modelType === "llm" || modelType === "slm";
      }
      return modelType === type;
    },
    isSampleType(sample, type) {
      const sampleType = String((sample && (sample.sampleType || sample.testLevel)) || "").toLowerCase();
      if (type === "llm") {
        return sampleType.includes("llm") || this.isModelType(this.selectedPerformanceResult, "llm");
      }
      return sampleType.includes(type) || this.isModelType(this.selectedPerformanceResult, type);
    },
    getSampleInputLabel(sample) {
      if (this.isSampleType(sample, "asr")) {
        return this.$t("modelConfig.asrAudioInput");
      }
      if (this.isSampleType(sample, "tts")) {
        return this.$t("modelConfig.ttsTextInput");
      }
      return this.$t("modelConfig.llmPrompt");
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
    formatTestOutput(output) {
      if (!output) {
        return "-";
      }
      if (typeof output !== "string") {
        return JSON.stringify(output, null, 2);
      }
      try {
        return JSON.stringify(JSON.parse(output), null, 2);
      } catch (e) {
        return output;
      }
    },
    handleTestModelResponse(data) {
      const result = data.data || {
        success: data.code === 0,
        message: data.msg,
      };
      this.showTestResult(result);
      if (this.isModelTestVerified(result)) {
        this.$message.success(result.message || this.$t("modelConfig.testSuccess"));
      } else {
        this.$message.error(result.message || data.msg || this.$t("modelConfig.testFailedMessage"));
      }
    },
    testModel(model) {
      if (this.testingModelId) {
        return;
      }
      this.testingModelId = model.id;
      Api.model.testModel(model.id, ({ data }) => {
        this.clearTestingModelState();
        this.handleTestModelResponse(data);
        this.loadData();
      });
    },
    runPerformanceTest() {
      if (this.selectedModels.length === 0) {
        this.$message.warning(this.$t("modelConfig.selectModelsForPerformance"));
        return;
      }
      this.performanceDialogVisible = true;
      this.performanceReport = null;
      this.performanceIterations = 1;
      this.performanceConcurrency = Math.max(1, Math.min(this.selectedModels.length, 8));
      this.selectedPerformanceResultId = "";
      this.selectedPerformanceSampleKey = "";
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
          modelType: this.activeTab,
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
        this.performanceDialogVisible = true;
        this.performanceHistoryVisible = false;
        this.syncSelectedPerformanceResult(this.performanceReport);
        const status = data.data.status;
        if (status !== "SUCCEEDED" && status !== "FAILED" && status !== "CANCELLED") {
          this.performanceTesting = true;
          this.pollPerformanceTask();
        }
      });
    },
    submitPerformanceTest() {
      if (this.performanceTesting) {
        return;
      }
      const modelIds = this.selectedModels.map((model) => model.id);
      if (modelIds.length === 0) {
        this.$message.warning(this.$t("modelConfig.selectModelsForPerformance"));
        return;
      }
      this.stopPerformancePolling();
      this.performanceTesting = true;
      this.performanceReport = {
        status: "SUBMITTING",
        progress: 1,
        summary: this.$t("modelConfig.performanceSubmittingHint"),
        modelIds,
        iterations: this.performanceIterations,
        concurrency: this.performanceConcurrency,
        results: [],
      };
      this.syncSelectedPerformanceResult(this.performanceReport);
      Api.model.performanceTest(
        {
          modelType: this.activeTab,
          modelIds,
          iterations: this.performanceIterations,
          concurrency: this.performanceConcurrency,
          judgeBaseUrl: this.performanceJudgeBaseUrl,
          judgeModel: this.performanceJudgeModel,
          judgeApiKey: this.performanceJudgeApiKey,
          judgeTimeout: this.performanceJudgeTimeout,
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
        };
        this.syncSelectedPerformanceResult(this.performanceReport);
        const status = data.data.status;
        if (status === "SUCCEEDED" || status === "FAILED" || status === "CANCELLED") {
          this.performanceTesting = false;
          this.stopPerformancePolling();
          if (status === "SUCCEEDED") {
            this.$message.success(this.$t("modelConfig.performanceTestFinished"));
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
    // 删除单个模型
    deleteModel(model) {
      this.$confirm(this.$t("modelConfig.confirmDelete"), this.$t("message.info"), {
        confirmButtonText: this.$t("common.confirm"),
        cancelButtonText: this.$t("common.cancel"),
        type: "warning",
      })
        .then(() => {
          Api.model.deleteModel(model.id, ({ data }) => {
            if (data.code === 0) {
              this.$message.success({
                message: this.$t("modelConfig.deleteSuccess"),
                showClose: true,
              });
              this.loadData();
            } else {
              this.$message.error({
                message: data.msg || this.$t("modelConfig.deleteFailed"),
                showClose: true,
              });
            }
          });
        })
        .catch(() => {
          this.$message.info(this.$t("modelConfig.deleteCancelled"));
        });
    },
    handleCurrentChange(page) {
      this.currentPage = page;
      this.$refs.modelTable.clearSelection();
    },
    handleModelSave({ provideCode, formData, done }) {
      const modelType = this.activeTab;
      const id = formData.id;

      if (this.editModelData.duplicateMode) {
        formData.id = "";
        Api.model.addModel({ modelType, provideCode, formData }, ({ data }) => {
          if (data.code === 0) {
            this.$message.success(this.$t("modelConfig.duplicateSuccess"));
            this.loadData();
            this.editDialogVisible = false;
          } else {
            this.$message.error(data.msg || this.$t("modelConfig.duplicateFailed"));
          }
          done && done(); // 调用done回调关闭加载状态
        });
      } else {
        Api.model.updateModel({ modelType, provideCode, id, formData }, ({ data }) => {
          if (data.code === 0) {
            this.$message.success(this.$t("modelConfig.saveSuccess"));
            this.loadData();
            this.editDialogVisible = false;
          } else {
            this.$message.error(data.msg || this.$t("modelConfig.saveFailed"));
          }
          done && done(); // 调用done回调关闭加载状态
        });
      }
    },
    handleModelSaveAndTest({ provideCode, formData, done }) {
      const modelType = this.activeTab;
      const testSavedModel = (id) => {
        if (!id) {
          this.clearTestingModelState();
          this.$message.error(this.$t("modelConfig.saveFailed"));
          done && done();
          return;
        }

        this.testingModelId = id;
        Api.model.testModel(id, ({ data }) => {
          this.clearTestingModelState();
          this.handleTestModelResponse(data);
          if (this.isModelTestVerified(data.data)) {
            this.loadData();
            this.editDialogVisible = false;
          } else {
            this.loadData();
          }
          done && done();
        });
      };

      if (this.editModelData.duplicateMode) {
        formData.id = "";
        Api.model.addModel({ modelType, provideCode, formData }, ({ data }) => {
          if (data.code === 0) {
            testSavedModel(data.data?.id);
          } else {
            this.$message.error(data.msg || this.$t("modelConfig.duplicateFailed"));
            done && done();
          }
        });
        return;
      }

      Api.model.updateModel({ modelType, provideCode, id: formData.id, formData }, ({ data }) => {
        if (data.code === 0) {
          testSavedModel(formData.id);
        } else {
          this.$message.error(data.msg || this.$t("modelConfig.saveFailed"));
          done && done();
        }
      });
    },
    selectAll() {
      if (this.isAllSelected) {
        this.$refs.modelTable.clearSelection();
      } else {
        this.$refs.modelTable.toggleAllSelection();
      }
    },
    handleSelectionChange(val) {
      this.selectedModels = val;
      this.isAllSelected = val.length === this.modelList.length;
      if (val.length === 0) {
        this.isAllSelected = false;
      }
    },

    // 新增模型配置
    handleAddConfirm(newModel) {
      const params = {
        modelType: this.activeTab,
        provideCode: newModel.provideCode,
        formData: {
          ...newModel,
          isDefault: newModel.isDefault ? 1 : 0,
          isEnabled: newModel.isEnabled ? 1 : 0,
          configJson: newModel.configJson,
        },
      };

      Api.model.addModel(params, ({ data }) => {
        if (data.code === 0) {
          this.$message.success({
            message: this.$t("modelConfig.addSuccess"),
            showClose: true,
          });
          this.loadData();
        } else {
          this.$message.error({
            message: data.msg || this.$t("modelConfig.addFailed"),
            showClose: true,
          });
        }
      });
    },

    // 分页器
    goFirst() {
      this.currentPage = 1;
      this.loadData();
    },
    goPrev() {
      if (this.currentPage > 1) {
        this.currentPage--;
        this.loadData();
      }
    },
    goNext() {
      if (this.currentPage < this.pageCount) {
        this.currentPage++;
        this.loadData();
      }
    },
    goToPage(page) {
      this.currentPage = page;
      this.loadData();
    },

    // 获取模型配置列表
    loadData() {
      this.loading = true; // 开始加载
      const params = {
        modelType: this.activeTab,
        modelName: this.search,
        page: this.currentPage,
        limit: this.pageSize,
      };

      Api.model.getModelList(params, ({ data }) => {
        this.loading = false; // 结束加载
        if (data.code === 0) {
          this.modelList = data.data.list.map((model) => ({
            ...model,
            isEnabled: model.testStatus === 1 ? model.isEnabled : 0,
          }));
          this.total = data.data.total;
        } else {
          this.$message.error(data.msg || this.$t("modelConfig.fetchModelsFailed"));
        }
      });
    },
    // 处理启用/非启用状态变更
    handleStatusChange(model, newStatus) {
      const normalizedStatus = newStatus ? 1 : 0;
      const originalStatus = normalizedStatus === 1 ? 0 : 1;

      if (normalizedStatus === 1 && model.testStatus !== 1) {
        model.isEnabled = originalStatus;
        this.$message.warning(this.$t("modelConfig.enableRequiresTestPassed"));
        return;
      }

      Api.model.updateModelStatus(model.id, normalizedStatus, ({ data }) => {
        if (data.code === 0) {
          this.$message.success(
            normalizedStatus === 1
              ? this.$t("modelConfig.enableSuccess")
              : this.$t("modelConfig.disableSuccess")
          );
          model.isEnabled = normalizedStatus;
          this.loadData();
        } else {
          model.isEnabled = originalStatus;
          this.$message.error(data.msg || this.$t("modelConfig.operationFailed"));
        }
      });
    },
  },
};
</script>

<style lang="scss" scoped>
.el-switch {
  height: 23px;
}

::v-deep .el-table tr {
  background: transparent;
}

.welcome {
  min-width: 900px;
  min-height: 506px;
  height: 100vh;
  display: flex;
  position: relative;
  flex-direction: column;
  background-size: cover;
  background: linear-gradient(to bottom right, #dce8ff, #e4eeff, #e6cbfd) center;
  -webkit-background-size: cover;
  -o-background-size: cover;
}

.main-wrapper {
  // 顶部 63px 底部 35px 查询72px
  height: calc(100vh - 63px - 35px - 72px);
  margin: 0 22px;
  border-radius: 15px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  position: relative;
  background: rgba(237, 242, 255, 0.5);
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
}

.content-panel {
  flex: 1;
  display: flex;
  overflow: hidden;
  height: 100%;
  border-radius: 15px;
  background: transparent;
}

.nav-panel {
  min-width: 242px;
  height: 100%;
  border-right: 1px solid #ebeef5;
  background: linear-gradient(
      120deg,
      rgba(107, 140, 255, 0.3) 0%,
      rgba(169, 102, 255, 0.3) 25%,
      transparent 60%
    ),
    url("../assets/model/model.png") no-repeat center / cover;
  padding: 16px 0;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}

.nav-panel .el-menu-item {
  height: 50px;
  background: #e9f0ff;
  line-height: 50px;
  border-radius: 4px 0 0 4px !important;
  transition: all 0.3s;
  display: flex !important;
  justify-content: flex-end;
  padding-right: 12px !important;
  width: fit-content;
  margin: 8px 0 8px auto;
  min-width: unset;
}

.nav-panel .el-menu-item.is-active {
  background: #5778ff;
  position: relative;
  padding-left: 40px !important;
}

.nav-panel .el-menu-item.is-active::before {
  content: "";
  position: absolute;
  left: 15px;
  top: 50%;
  transform: translateY(-50%);
  width: 13px;
  height: 13px;
  background: #fff;
  border-radius: 50%;
  box-shadow: 0 0 4px rgba(64, 158, 255, 0.5);
}

.menu-text {
  font-size: 14px;
  color: #606266;
  text-align: right;
  width: 100%;
  padding-right: 8px;
}

.content-area {
  flex: 1;
  padding: 24px 24px 0;
  height: 100%;
  min-width: 600px;
  overflow: hidden;
  background-color: white;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}

.action-group {
  display: flex;
  align-items: center;
  gap: 16px;
}

.search-group {
  display: flex;
  gap: 10px;
}

.search-input {
  width: 240px;
}

.btn-search {
  background: linear-gradient(135deg, #6b8cff, #a966ff);
  border: none;
  color: white;
}

.btn-search:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}

::v-deep .search-input .el-input__inner {
  border-radius: 4px;
  border: 1px solid #dcdfe6;
  background-color: white;
  transition: border-color 0.2s;
}

::v-deep .page-size-select {
  width: 100px;
  margin-right: 8px;
}

::v-deep .page-size-select .el-input__inner {
  height: 32px;
  line-height: 32px;
  border-radius: 4px;
  border: 1px solid #e4e7ed;
  background: #dee7ff;
  color: #606266;
  font-size: 14px;
}

::v-deep .page-size-select .el-input__suffix {
  right: 6px;
  width: 15px;
  height: 20px;
  display: flex;
  justify-content: center;
  align-items: center;
  top: 6px;
  border-radius: 4px;
}

::v-deep .page-size-select .el-input__suffix-inner {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
}

::v-deep .page-size-select .el-icon-arrow-up:before {
  content: "";
  display: inline-block;
  border-left: 6px solid transparent;
  border-right: 6px solid transparent;
  border-top: 9px solid #606266;
  position: relative;
  transform: rotate(0deg);
  transition: transform 0.3s;
}

::v-deep .search-input .el-input__inner:focus {
  border-color: #6b8cff;
  outline: none;
}

// .data-table {
//   border-radius: 6px;
//   overflow: hidden;
//   background-color: transparent !important;
// }

// .data-table ::v-deep .el-table__row {
//   background-color: transparent !important;
// }

.table-header th {
  background-color: transparent !important;
  color: #606266;
  font-weight: 600;
}

.table-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  // padding: 16px 0;
  width: 100%;
  flex-shrink: 0;
  min-height: 60px;
  background: white;
}

.batch-actions {
  display: flex;
  gap: 8px;
}

.batch-actions .el-button {
  min-width: 72px;
  height: 32px;
  padding: 7px 12px 7px 10px;
  font-size: 12px;
  border-radius: 4px;
  line-height: 1;
  font-weight: 500;
  border: none;
  transition: all 0.3s ease;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.1);
}

.batch-actions .el-button:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.15);
}

.batch-actions .el-button--primary {
  background: #5f70f3 !important;
  color: white;
}

.batch-actions .el-button--success {
  background: #5bc98c;
  color: white;
}

.batch-actions .el-button--danger {
  background: #fd5b63;
  color: white;
}

.batch-actions .el-button:first-child {
  background: linear-gradient(135deg, #409eff, #6b8cff);
  border: none;
  color: white;
}

.batch-actions .el-button:first-child:hover {
  background: linear-gradient(135deg, #3a8ee6, #5a7cff);
}

.el-table th ::v-deep .el-table__cell {
  overflow: hidden;
  -webkit-user-select: none;
  -moz-user-select: none;
  user-select: none;
  background-color: transparent !important;
}

::v-deep .el-table .custom-selection-header .cell .el-checkbox__inner {
  display: none !important;
}

::v-deep .el-table .custom-selection-header .cell::before {
  content: attr(data-content);
  display: block;
  text-align: center;
  line-height: 32px;
  /* 设置合适的行高，确保文本完整显示 */
  color: black;
  margin-top: 0;
  /* 移除可能导致偏移的上边距 */
  height: 32px;
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 100%;
}

.custom-selection-header .cell {
  position: relative;
}

/* 已移除可能影响文本显示的空伪元素 */

::v-deep .el-table__body .el-checkbox__inner {
  display: inline-block !important;
  background: #ffffff;
}

::v-deep .el-table thead th:not(:first-child) .cell {
  color: #303133 !important;
}

::v-deep .nav-panel .el-menu-item.is-active .menu-text {
  color: #fff !important;
}


.el-button img {
  height: 1em;
  vertical-align: middle;
  padding-right: 2px;
  padding-bottom: 2px;
}

::v-deep .el-checkbox__inner {
  border-color: #cfcfcf !important;
  transition: all 0.2s ease-in-out;
}

::v-deep .el-checkbox__input.is-checked .el-checkbox__inner {
  background-color: #5f70f3;
  border-color: #5f70f3;
}

.voice-management-btn {
  background: #9db3ea;
  color: white;
  min-width: 68px;
  line-height: 14px;
  white-space: nowrap;
  transition: all 0.3s;
  border-radius: 10px;
}

.voice-management-btn:hover {
  background: #8aa2e0;
  /* 悬停时颜色加深 */
  transform: scale(1.05);
}

::v-deep .el-table .el-table-column--selection .cell {
  padding-left: 15px !important;
}

::v-deep .el-table .el-table__fixed-right .cell {
  padding-right: 15px !important;
}

.edit-btn,
.delete-btn {
  margin: 0 8px;
  color: #7079aa !important;
}

::v-deep .el-table .cell {
  padding-left: 10px;
  padding-right: 10px;
}

/* 分页器 */
.custom-pagination {
  display: flex;
  align-items: center;
  gap: 8px;

  /* 导航按钮样式 (首页、上一页、下一页) */
  .pagination-btn:first-child,
  .pagination-btn:nth-child(2),
  .pagination-btn:nth-child(3),
  .pagination-btn:nth-last-child(2) {
    min-width: 60px;
    height: 32px;
    padding: 0 12px;
    border-radius: 4px;
    border: 1px solid #e4e7ed;
    background: #dee7ff;
    color: #606266;
    font-size: 14px;
    cursor: pointer;
    transition: all 0.3s ease;

    &:hover {
      background: #d7dce6;
    }

    &:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
  }

  /* 数字按钮样式 */
  .pagination-btn:not(:first-child):not(:nth-child(2)):not(:nth-child(3)):not(:nth-last-child(2)) {
    min-width: 28px;
    height: 32px;
    padding: 0;
    border-radius: 4px;
    border: 1px solid transparent;
    background: transparent;
    color: #606266;
    font-size: 14px;
    cursor: pointer;
    transition: all 0.3s ease;

    &:hover {
      background: rgba(245, 247, 250, 0.3);
    }
  }

  .pagination-btn.active {
    background: #5f70f3 !important;
    color: #ffffff !important;
    border-color: #5f70f3 !important;

    &:hover {
      background: #6d7cf5 !important;
    }
  }

  .total-text {
    color: #909399;
    font-size: 14px;
    margin-left: 10px;
  }
}

.model-card {
  background: white;
  flex: 1;
  display: flex;
  flex-direction: column;
  border: none;
  box-shadow: none;
  overflow: hidden;
}

.model-card ::v-deep .el-card__body {
  padding: 0;
  display: flex;
  flex-direction: column;
  flex: 1;
  overflow: hidden;
}
:deep(.transparent-table) {
    background: white;
    flex: 1;
    width: 100%;
    display: flex;
    flex-direction: column;

    .el-table__body-wrapper {
        flex: 1;
        overflow-y: auto;
        max-height: none !important;
    }

    .el-table__header-wrapper {
        flex-shrink: 0;
    }

    .el-table__header th {
        background: white !important;
        color: black;
        font-weight: 600;
        height: 40px;
        padding: 8px 0;
        font-size: 14px;
        border-bottom: 1px solid #e4e7ed;
    }

    .el-table__body tr {
        background-color: white;

        td {
            border-top: 1px solid rgba(0, 0, 0, 0.04);
            border-bottom: 1px solid rgba(0, 0, 0, 0.04);
            padding: 8px 0;
            height: 40px;
            color: #606266;
            font-size: 14px;
        }
    }

    .el-table__row:hover>td {
        background-color: #f5f7fa !important;
    }

    &::before {
        display: none;
    }
}


::v-deep .el-loading-mask {
  background-color: rgba(255, 255, 255, 0.6) !important;
  backdrop-filter: blur(2px);
}

::v-deep .el-loading-spinner .circular {
  width: 28px;
  height: 28px;
}

::v-deep .el-loading-spinner .path {
  stroke: #6b8cff;
}

::v-deep .el-loading-text {
  color: #6b8cff !important;
  font-size: 14px;
  margin-top: 8px;
}

.test-result-content {
  display: flex;
  flex-direction: column;
  gap: 14px;
  color: #303133;
}

.test-result-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 28px;
}

.test-result-label {
  min-width: 72px;
  color: #606266;
  font-weight: 600;
}

.test-result-message {
  color: #606266;
  line-height: 1.5;
}

.test-result-block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.test-result-block pre {
  max-height: 220px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #f7f9fc;
  color: #303133;
  font-size: 13px;
  line-height: 1.6;
}

.test-result-image-wrap {
  width: 100%;
  max-height: 320px;
  overflow: auto;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #f7f9fc;
}

.test-result-image {
  display: block;
  max-width: 100%;
  max-height: 300px;
  margin: 0 auto;
  object-fit: contain;
}

.test-result-audio-wrap {
  padding: 10px 12px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background: #f7f9fc;
}

.test-result-audio {
  display: block;
  width: 100%;
  height: 36px;
}

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

.performance-judge-config-grid {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 180px minmax(240px, 1fr) 150px;
  gap: 8px;
  align-items: center;
}

.performance-judge-timeout {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #606266;
  font-size: 12px;
}

.performance-judge-timeout ::v-deep .el-input-number {
  width: 94px;
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

.performance-sample pre,
.performance-reference pre {
  margin: 0;
  padding: 8px;
  min-height: 54px;
  max-height: 140px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  background: #f5f7fa;
  color: #303133;
  border-radius: 4px;
  font-family: inherit;
  line-height: 1.5;
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
