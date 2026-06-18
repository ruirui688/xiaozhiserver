<template>
  <div class="welcome">
    <HeaderBar />

    <div class="operation-bar">
      <h2 class="page-title">{{ $t("modelConfig." + activeTab) }}</h2>
      <div class="action-group">
        <div class="search-group">
          <el-radio-group
            v-model="viewMode"
            size="mini"
            class="view-mode-switch"
            @change="handleViewModeChange"
          >
            <el-radio-button label="config">
              {{ $t("modelConfig.configView") }}
            </el-radio-button>
            <el-radio-button label="catalog">
              {{ $t("modelConfig.catalogView") }}
            </el-radio-button>
          </el-radio-group>
          <el-input
            :placeholder="$t('modelConfig.searchPlaceholder')"
            v-model="search"
            class="search-input"
            clearable
            @keyup.enter.native="handleSearch"
            style="width: 240px"
          />
          <el-select
            v-if="viewMode === 'config'"
            v-model="testStatusFilter"
            :placeholder="$t('modelConfig.testStatusFilter')"
            class="filter-select"
            clearable
            @change="handleSearch"
          >
            <el-option
              v-for="item in testStatusOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-select
            v-model="adapterFilter"
            :placeholder="$t('modelConfig.adapterFilter')"
            class="filter-select"
            filterable
            clearable
            @change="handleSearch"
          >
            <el-option
              v-for="item in adapterOptions"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
          <el-select
            v-model="platformFilter"
            :placeholder="$t('modelConfig.platformFilter')"
            class="filter-select"
            filterable
            clearable
            @change="handleSearch"
          >
            <el-option
              v-for="item in platformOptions"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
          <el-select
            v-if="viewMode === 'catalog'"
            v-model="capabilityFilter"
            :placeholder="$t('modelConfig.capabilityFilter')"
            class="filter-select"
            filterable
            clearable
            @change="handleSearch"
          >
            <el-option
              v-for="item in capabilityOptions"
              :key="item"
              :label="item"
              :value="item"
            />
          </el-select>
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
              :data="activeTableData"
              class="transparent-table"
              header-row-class-name="table-header"
              :header-cell-class-name="headerCellClassName"
              @selection-change="handleSelectionChange"
            >
              <el-table-column
                v-if="viewMode === 'config'"
                type="selection"
                width="55"
                align="center"
                :cell-class-name="selectionCellClassName"
              ></el-table-column>
              <el-table-column
                :label="$t('modelConfig.modelId')"
                align="center"
                width="150"
                show-overflow-tooltip
              >
                <template slot-scope="scope">
                  {{ viewMode === "catalog" ? getCatalogId(scope.row) : scope.row.id }}
                </template>
              </el-table-column>
              <el-table-column
                :label="$t('modelConfig.modelName')"
                align="center"
                min-width="150"
                show-overflow-tooltip
              >
                <template slot-scope="scope">
                  {{ viewMode === "catalog" ? getCatalogName(scope.row) : scope.row.modelName }}
                </template>
              </el-table-column>
              <el-table-column
                :label="$t('modelConfig.upstreamModel')"
                align="center"
                min-width="150"
                show-overflow-tooltip
              >
                <template slot-scope="scope">
                  {{ getUpstreamModel(scope.row) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="$t('modelConfig.platformVendor')"
                align="center"
                min-width="130"
                show-overflow-tooltip
              >
                <template slot-scope="scope">
                  {{ getPlatformVendor(scope.row) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="$t('modelConfig.productSeries')"
                align="center"
                min-width="130"
                show-overflow-tooltip
              >
                <template slot-scope="scope">
                  {{ getProductSeries(scope.row) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="$t('modelConfig.provider')"
                align="center"
                min-width="130"
                show-overflow-tooltip
              >
                <template slot-scope="scope">
                  {{ getModelAdapter(scope.row) }}
                </template>
              </el-table-column>
              <el-table-column
                :label="$t('modelConfig.capabilities')"
                align="center"
                min-width="160"
              >
                <template slot-scope="scope">
                  <div class="capability-tags">
                    <el-tag
                      v-for="tag in getCapabilityTags(scope.row)"
                      :key="tag"
                      size="mini"
                      effect="plain"
                    >
                      {{ tag }}
                    </el-tag>
                    <span v-if="!getCapabilityTags(scope.row).length">-</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                v-if="viewMode === 'catalog'"
                :label="$t('modelConfig.templateCount')"
                align="center"
                width="110"
              >
                <template slot-scope="scope">
                  {{ getCatalogTemplateCount(scope.row) }}
                </template>
              </el-table-column>
              <el-table-column :label="$t('modelConfig.isEnabled')" align="center">
                <template slot-scope="scope">
                  <span v-if="viewMode === 'catalog'">-</span>
                  <el-tooltip
                    v-else
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
                  <span v-if="viewMode === 'catalog'">-</span>
                  <el-tooltip
                    v-else
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
                v-if="viewMode === 'config' && activeTab === 'tts'"
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
                v-if="viewMode === 'config'"
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
              <el-table-column
                v-if="viewMode === 'catalog'"
                :label="$t('modelConfig.action')"
                align="center"
                width="120px"
              >
                <template slot-scope="scope">
                  <el-button
                    type="text"
                    size="mini"
                    :loading="templateLoading && selectedCatalogId === getCatalogId(scope.row)"
                    @click="openTemplateDialog(scope.row)"
                    class="edit-btn"
                  >
                    {{ $t("modelConfig.viewTemplates") }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-alert
              v-if="viewMode === 'catalog' && catalogError"
              class="catalog-error"
              type="warning"
              :closable="false"
              show-icon
              :title="catalogError"
            />
            <div class="table-footer">
              <div class="batch-actions">
                <el-button
                  v-if="viewMode === 'config'"
                  size="mini"
                  type="primary"
                  @click="selectAll"
                >
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
                  v-if="viewMode === 'config'"
                  size="mini"
                  type="danger"
                  icon="el-icon-delete"
                  @click="batchDelete"
                >
                  {{ $t("modelConfig.delete") }}
                </el-button>
                <el-button
                  v-if="viewMode === 'config'"
                  size="mini"
                  type="warning"
                  icon="el-icon-data-analysis"
                  :loading="performanceTesting"
                  @click="runPerformanceTest"
                >
                  {{ $t("modelConfig.performanceTest") }}
                </el-button>
                <el-button
                  size="mini"
                  type="info"
                  icon="el-icon-view"
                  :loading="migrationPreviewLoading"
                  @click="openMigrationPreview"
                >
                  {{ $t("modelConfig.migrationPreview") }}
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
                  $t("modelConfig.totalRecords", { total: displayTotal })
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
            <el-tag size="mini" :type="currentTestResult.success ? 'success' : 'danger'">
              {{
                currentTestResult.success
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
          <div class="test-result-row" v-if="currentTestResult.testLevelText">
            <span class="test-result-label">{{ $t("modelConfig.testLevel") }}</span>
            <span>{{ currentTestResult.testLevelText }}</span>
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
        width="1060px"
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
          />
          <span>{{ $t("modelConfig.performanceConcurrency") }}</span>
          <el-input-number
            v-model="performanceConcurrency"
            :min="1"
            :max="8"
            size="mini"
            controls-position="right"
          />
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
            </div>
            <el-empty
              v-if="!performanceSamples.length"
              :description="$t('modelConfig.noPerformanceSamples')"
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
                  <el-tag
                    size="mini"
                    :type="selectedPerformanceSample.success ? 'success' : 'danger'"
                  >
                    {{ selectedPerformanceSample.success ? $t("modelConfig.testSuccess") : $t("modelConfig.testFailed") }}
                  </el-tag>
                  <span>{{ formatResponseTime(selectedPerformanceSample.responseTimeMs) }}</span>
                </div>
                <div class="performance-bubble-row is-user">
                  <div class="performance-bubble-label">{{ getSampleInputLabel(selectedPerformanceSample) }}</div>
                  <div class="performance-bubble">
                    {{ selectedPerformanceSample.inputSummary || "-" }}
                  </div>
                </div>
                <div class="performance-bubble-row is-assistant">
                  <div class="performance-bubble-label">{{ getSampleOutputLabel(selectedPerformanceSample) }}</div>
                  <div class="performance-bubble">
                    {{ selectedPerformanceSample.outputSummary || "-" }}
                  </div>
                </div>
                <div
                  v-if="selectedPerformanceSample.referenceOutput"
                  class="performance-chat-reference"
                >
                  {{ $t("modelConfig.referenceOutput") }}：{{ selectedPerformanceSample.referenceOutput }}
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
        :title="$t('modelConfig.migrationPreviewTitle')"
        :visible.sync="migrationPreviewDialogVisible"
        width="920px"
        append-to-body
        class="migration-preview-dialog"
      >
        <el-alert
          v-if="migrationPreviewError"
          type="warning"
          :closable="false"
          show-icon
          :title="migrationPreviewError"
          class="migration-preview-alert"
        />
        <div v-if="migrationPreviewData" class="migration-preview-content">
          <div class="migration-summary">
            <div>
              <span>{{ $t("modelConfig.previewTotal") }}</span>
              <strong>{{ getMigrationTotal(migrationPreviewData) }}</strong>
            </div>
            <div>
              <span>{{ $t("modelConfig.previewMatched") }}</span>
              <strong>{{ getMigrationMatched(migrationPreviewData) }}</strong>
            </div>
            <div>
              <span>{{ $t("modelConfig.previewWarnings") }}</span>
              <strong>{{ getMigrationWarnings(migrationPreviewData) }}</strong>
            </div>
          </div>
          <el-table
            :data="migrationPreviewRows"
            size="mini"
            border
            class="migration-preview-table"
          >
            <el-table-column
              prop="modelName"
              :label="$t('modelConfig.modelName')"
              min-width="150"
              show-overflow-tooltip
            />
            <el-table-column
              prop="provider"
              :label="$t('modelConfig.provider')"
              width="130"
              show-overflow-tooltip
            />
            <el-table-column
              prop="targetCatalog"
              :label="$t('modelConfig.targetCatalog')"
              min-width="160"
              show-overflow-tooltip
            />
            <el-table-column
              prop="targetTemplate"
              :label="$t('modelConfig.targetTemplate')"
              min-width="160"
              show-overflow-tooltip
            />
            <el-table-column
              prop="statusText"
              :label="$t('modelConfig.previewStatus')"
              width="110"
            />
            <el-table-column
              prop="message"
              :label="$t('modelConfig.summary')"
              min-width="190"
              show-overflow-tooltip
            />
          </el-table>
          <div v-if="hasObjectValue(migrationPreviewData)" class="migration-raw">
            <el-collapse>
              <el-collapse-item :title="$t('modelConfig.performanceRawDetail')" name="raw">
                <pre>{{ formatJsonDetail(migrationPreviewData) }}</pre>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>
        <el-empty
          v-else-if="!migrationPreviewLoading"
          :description="$t('modelConfig.noMigrationPreview')"
          :image-size="72"
        />
      </el-dialog>
      <el-dialog
        :title="$t('modelConfig.templateDialogTitle')"
        :visible.sync="templateDialogVisible"
        width="820px"
        append-to-body
        class="catalog-template-dialog"
      >
        <div class="template-dialog-header">
          <span>{{ selectedCatalogName || "-" }}</span>
          <el-tag size="mini" effect="plain">{{ selectedCatalogProvider || "-" }}</el-tag>
        </div>
        <el-alert
          v-if="templateError"
          type="warning"
          :closable="false"
          show-icon
          :title="templateError"
          class="migration-preview-alert"
        />
        <el-table
          v-loading="templateLoading"
          :data="normalizedTemplateList"
          size="mini"
          border
          class="template-table"
        >
          <el-table-column
            prop="_templateId"
            :label="$t('modelConfigDialog.templateId')"
            min-width="130"
            show-overflow-tooltip
          />
          <el-table-column
            prop="_templateName"
            :label="$t('modelConfigDialog.templateName')"
            min-width="150"
            show-overflow-tooltip
          />
          <el-table-column
            prop="_upstreamModel"
            :label="$t('modelConfig.upstreamModel')"
            min-width="150"
            show-overflow-tooltip
          />
          <el-table-column
            prop="_summary"
            :label="$t('modelConfig.summary')"
            min-width="190"
            show-overflow-tooltip
          />
        </el-table>
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
      viewMode: "config",
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
      testStatusFilter: "",
      adapterFilter: "",
      platformFilter: "",
      capabilityFilter: "",
      catalogList: [],
      catalogLoading: false,
      catalogError: "",
      selectedModelConfig: {},
      testingModelId: "",
      testResultDialogVisible: false,
      currentTestResult: null,
      performanceDialogVisible: false,
      performanceTesting: false,
      performanceIterations: 3,
      performanceConcurrency: 3,
      performanceReport: null,
      performanceTaskId: "",
      performancePollTimer: null,
      selectedPerformanceResultId: "",
      selectedPerformanceSampleKey: "",
      migrationPreviewDialogVisible: false,
      migrationPreviewLoading: false,
      migrationPreviewData: null,
      migrationPreviewError: "",
      templateDialogVisible: false,
      templateLoading: false,
      templateError: "",
      templateList: [],
      selectedCatalogId: "",
      selectedCatalogName: "",
      selectedCatalogProvider: "",
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
      return Math.max(1, Math.ceil(this.displayTotal / this.pageSize));
    },
    hasClientFilters() {
      return !!(
        String(this.search || "").trim() ||
        (this.testStatusFilter !== "" &&
          this.testStatusFilter !== null &&
          this.testStatusFilter !== undefined) ||
        this.adapterFilter ||
        this.platformFilter
      );
    },
    displayTotal() {
      if (this.viewMode === "catalog") {
        return this.filteredCatalogList.length;
      }
      return this.hasClientFilters ? this.filteredModelList.length : this.total;
    },
    activeTableData() {
      return this.viewMode === "catalog" ? this.visibleCatalogList : this.visibleModelList;
    },
    testStatusOptions() {
      return [
        { label: this.$t("modelConfig.untested"), value: 0 },
        { label: this.$t("modelConfig.testPassed"), value: 1 },
        { label: this.$t("modelConfig.testFailed"), value: 2 },
      ];
    },
    enrichedModelList() {
      return this.modelList.map((model) => ({
        ...model,
        _adapter: this.getModelAdapter(model),
        _platform: this.getPlatformVendor(model),
        _upstreamModel: this.getUpstreamModel(model),
        _capabilityTags: this.getCapabilityTags(model),
      }));
    },
    filteredModelList() {
      const keyword = String(this.search || "").trim().toLowerCase();
      return this.enrichedModelList.filter((model) => {
        const matchesKeyword =
          !keyword ||
          [
            model.modelName,
            model._upstreamModel,
            model._platform,
            model._adapter,
            model.modelCode,
            model.id,
          ]
            .filter(Boolean)
            .some((value) => String(value).toLowerCase().includes(keyword));
        const matchesStatus =
          this.testStatusFilter === "" ||
          this.testStatusFilter === null ||
          this.testStatusFilter === undefined ||
          model.testStatus === this.testStatusFilter;
        const matchesAdapter = !this.adapterFilter || model._adapter === this.adapterFilter;
        const matchesPlatform = !this.platformFilter || model._platform === this.platformFilter;
        return matchesKeyword && matchesStatus && matchesAdapter && matchesPlatform;
      });
    },
    normalizedCatalogList() {
      return this.catalogList.map((item) => this.normalizeCatalogItem(item));
    },
    filteredCatalogList() {
      const keyword = String(this.search || "").trim().toLowerCase();
      return this.normalizedCatalogList.filter((item) => {
        const matchesKeyword =
          !keyword ||
          [
            item._catalogId,
            item._catalogName,
            item._upstreamModel,
            item._platform,
            item._adapter,
            ...(item._capabilityTags || []),
          ]
            .filter(Boolean)
            .some((value) => String(value).toLowerCase().includes(keyword));
        const matchesAdapter = !this.adapterFilter || item._adapter === this.adapterFilter;
        const matchesPlatform = !this.platformFilter || item._platform === this.platformFilter;
        const matchesCapability =
          !this.capabilityFilter || (item._capabilityTags || []).includes(this.capabilityFilter);
        return matchesKeyword && matchesAdapter && matchesPlatform && matchesCapability;
      });
    },
    visibleCatalogList() {
      const start = (this.currentPage - 1) * this.pageSize;
      return this.filteredCatalogList.slice(start, start + this.pageSize);
    },
    visibleModelList() {
      if (!this.hasClientFilters) {
        return this.filteredModelList;
      }
      const start = (this.currentPage - 1) * this.pageSize;
      return this.filteredModelList.slice(start, start + this.pageSize);
    },
    adapterOptions() {
      const source =
        this.viewMode === "catalog" ? this.normalizedCatalogList : this.enrichedModelList;
      return this.uniqueOptions(source.map((item) => item._adapter));
    },
    platformOptions() {
      const source =
        this.viewMode === "catalog" ? this.normalizedCatalogList : this.enrichedModelList;
      return this.uniqueOptions(source.map((item) => item._platform));
    },
    capabilityOptions() {
      return this.uniqueOptions(
        this.normalizedCatalogList.reduce(
          (result, item) => result.concat(item._capabilityTags || []),
          []
        )
      );
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
    migrationPreviewRows() {
      if (!this.migrationPreviewData) {
        return [];
      }
      const rows = this.firstArrayValue(this.migrationPreviewData, [
        "items",
        "rows",
        "list",
        "plans",
        "migrations",
        "details",
      ]);
      const previewRows = Array.isArray(this.migrationPreviewData)
        ? this.migrationPreviewData
        : rows;
      return previewRows.map((row) => ({
        ...row,
        modelName: this.firstValue(row, [
          "modelName",
          "sourceModelName",
          "configName",
          "name",
        ]) || "-",
        provider: this.firstValue(row, [
          "provider",
          "providerCode",
          "adapter",
          "sourceProvider",
        ]) || "-",
        targetCatalog: this.firstValue(row, [
          "templateCode",
          "targetCatalogName",
          "catalogName",
          "catalogId",
          "targetCatalogId",
        ]) || "-",
        targetTemplate: this.firstValue(row, [
          "templateName",
          "templateCode",
          "targetTemplateName",
          "templateId",
          "targetTemplateId",
        ]) || "-",
        statusText:
          this.firstValue(row, ["statusText", "status", "action", "migrationStatus"]) || "-",
        message: this.firstValue(row, ["message", "reason", "warning", "summary"]) || "-",
      }));
    },
    normalizedTemplateList() {
      return this.templateList.map((item) => ({
        ...item,
        _templateId: this.firstValue(item, ["templateCode", "id", "templateId", "code"]) || "-",
        _templateName:
          this.firstValue(item, ["name", "templateName", "displayName", "title"]) ||
          [item.vendorName, item.productName, item.accessModeLabel].filter(Boolean).join(" / ") ||
          "-",
        _upstreamModel:
          this.firstValue(item, [
            "upstreamResourceLabel",
            "upstreamModelField",
            "upstreamModel",
            "upstreamModelId",
            "model",
            "modelId",
            "modelName",
          ]) || "-",
        _summary:
          this.firstValue(item, ["summary", "description", "remark", "message"]) || "-",
      }));
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
      if (this.viewMode === "config" && columnIndex === 0) {
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
    handleViewModeChange() {
      this.currentPage = 1;
      this.selectedModels = [];
      this.isAllSelected = false;
      if (this.viewMode === "catalog") {
        this.testStatusFilter = "";
      }
      this.loadData();
    },
    handleSearch() {
      this.currentPage = 1;
      this.loadData();
    },
    uniqueOptions(values) {
      return Array.from(new Set(values.filter((value) => value && value !== "-")));
    },
    parseConfigJson(row) {
      const configJson = row && row.configJson;
      if (!configJson) {
        return {};
      }
      if (typeof configJson === "string") {
        try {
          return JSON.parse(configJson);
        } catch (e) {
          return {};
        }
      }
      return configJson;
    },
    firstValue(source, keys) {
      for (const key of keys) {
        const value = source && source[key];
        if (value !== undefined && value !== null && String(value).trim() !== "") {
          return value;
        }
      }
      return "";
    },
    getModelAdapter(row) {
      if (row && row._isCatalogItem) {
        return row._adapter || this.$t("modelConfig.unknown");
      }
      const configJson = this.parseConfigJson(row);
      return (
        this.firstValue(row, ["adapterLabel", "adapterName", "adapter", "providerName", "providerCode"]) ||
        this.firstValue(configJson, ["type", "provider", "adapter", "provider_code"]) ||
        this.$t("modelConfig.unknown")
      );
    },
    getUpstreamModel(row) {
      if (row && row._isCatalogItem) {
        return row._upstreamModel || "-";
      }
      const configJson = this.parseConfigJson(row);
      return (
        this.firstValue(row, ["upstreamModelId", "upstreamModel", "upstreamModelName", "realModelName"]) ||
        this.firstValue(configJson, [
          "model_name",
          "model",
          "model_id",
          "voice",
          "speaker",
          "resource_id",
          "llm_model",
          "llm",
        ]) ||
        "-"
      );
    },
    getPlatformVendor(row) {
      if (row && row._isCatalogItem) {
        return row._platform || "-";
      }
      const configJson = this.parseConfigJson(row);
      const explicitValue =
        this.firstValue(row, ["platform", "vendor", "platformName", "vendorName"]) ||
        this.firstValue(configJson, ["platform", "vendor", "provider_name", "company"]);
      if (explicitValue) {
        return explicitValue;
      }
      const adapter = String(this.getModelAdapter(row) || "").toLowerCase();
      const baseUrl = String(
        this.firstValue(configJson, ["base_url", "url", "host", "ws_url", "api_url"])
      ).toLowerCase();
      const modelName = String(this.getUpstreamModel(row) || "").toLowerCase();
      const haystack = `${adapter} ${baseUrl} ${modelName}`;
      const knownPlatforms = [
        ["funasr", "本地/自部署"],
        ["sherpa", "本地/自部署"],
        ["sensevoice", "本地/自部署"],
        ["vosk", "本地/自部署"],
        ["ollama", "本地/自部署"],
        ["xinference", "本地/自部署"],
        ["lm-studio", "本地/自部署"],
        ["localhost", "本地/自部署"],
        ["127.0.0.1", "本地/自部署"],
        ["0.0.0.0", "本地/自部署"],
        ["dashscope", "阿里云"],
        ["aliyun", "阿里云"],
        ["qwen", "阿里云"],
        ["doubao", "火山引擎"],
        ["volc", "火山引擎"],
        ["bytedance", "火山引擎"],
        ["openai", "OpenAI"],
        ["deepseek", "DeepSeek"],
        ["minimax", "MiniMax"],
        ["xfyun", "讯飞开放平台"],
        ["iflytek", "讯飞开放平台"],
        ["tencent", "腾讯云"],
        ["baidu", "百度智能云"],
        ["fishspeech", "Fish Speech"],
        ["gpt_sovits", "GPT-SoVITS"],
      ];
      const matched = knownPlatforms.find(([keyword]) => haystack.includes(keyword));
      return matched ? matched[1] : "-";
    },
    getProductSeries(row) {
      if (row && row._isCatalogItem) {
        return row._product || "-";
      }
      const configJson = this.parseConfigJson(row);
      const explicitValue =
        this.firstValue(row, ["productName", "product", "seriesName", "capabilityLabel"]) ||
        this.firstValue(configJson, ["product", "series", "platform_product"]);
      if (explicitValue) {
        return explicitValue;
      }
      const adapter = String(this.getModelAdapter(row) || "").toLowerCase();
      const baseUrl = String(
        this.firstValue(configJson, ["base_url", "url", "host", "ws_url", "api_url"])
      ).toLowerCase();
      const modelName = String(this.getUpstreamModel(row) || "").toLowerCase();
      const providerCode = String(row && row.providerCode || "").toLowerCase();
      const haystack = `${adapter} ${baseUrl} ${modelName} ${providerCode}`;
      const knownProducts = [
        ["paraformer", "百炼 Paraformer"],
        ["dashscope", "百炼"],
        ["qwen", "通义千问"],
        ["doubao", "豆包"],
        ["seedasr", "Seed ASR"],
        ["seed-tts", "Seed TTS"],
        ["bigasr", "豆包大模型 ASR"],
        ["bigtts", "豆包大模型 TTS"],
        ["spark", "星火"],
        ["xfyun", "讯飞语音"],
        ["funasr", "FunASR"],
        ["sherpa", "Sherpa-ONNX"],
        ["sensevoice", "SenseVoice"],
        ["vosk", "Vosk"],
        ["ollama", "Ollama"],
        ["xinference", "Xinference"],
        ["fishspeech", "Fish Speech"],
        ["gpt_sovits", "GPT-SoVITS"],
      ];
      const matched = knownProducts.find(([keyword]) => haystack.includes(keyword));
      return matched ? matched[1] : "-";
    },
    getCapabilityTags(row) {
      if (this.viewMode === "catalog" || (row && row._isCatalogItem)) {
        return row._capabilityTags || this.normalizeCatalogItem(row)._capabilityTags || [];
      }
      const configJson = this.parseConfigJson(row);
      const rawTags =
        this.firstValue(row, ["modelTags", "tags", "capabilities", "labels"]) ||
        this.firstValue(configJson, ["tags", "capabilities", "labels"]);
      let tags = [];
      if (Array.isArray(rawTags)) {
        tags = rawTags;
      } else if (typeof rawTags === "string" && rawTags.trim()) {
        tags = rawTags.split(/[,，\s/]+/);
      }

      const generatedTags = [];
      const adapter = String(this.getModelAdapter(row) || "").toLowerCase();
      const modelType = String((row && row.modelType) || this.activeTab || "").toLowerCase();
      const hasValue = (keys) => keys.some((key) => configJson[key] !== undefined && configJson[key] !== "");

      if (modelType) {
        generatedTags.push(modelType.toUpperCase());
      }
      if (hasValue(["base_url", "url", "host", "ws_url"])) {
        generatedTags.push("API");
      }
      if (hasValue(["api_key", "access_token", "secret_key", "secret_id", "appid"])) {
        generatedTags.push("凭据");
      }
      if (hasValue(["temperature", "top_p", "top_k", "max_tokens", "speed", "volume", "pitch"])) {
        generatedTags.push("参数");
      }
      if (adapter.includes("stream") || hasValue(["enable_ws_reuse", "ws_url"])) {
        generatedTags.push("流式");
      }
      if (hasValue(["voice", "speaker"])) {
        generatedTags.push("音色");
      }
      if (hasValue(["prompt", "system_prompt"])) {
        generatedTags.push("提示词");
      }

      return this.uniqueOptions([...tags, ...generatedTags].map((tag) => String(tag).trim())).slice(0, 4);
    },
    normalizeCatalogItem(item) {
      const arrayTags = this.firstArrayValue(item, [
        "capabilities",
        "modelTags",
        "tags",
        "labels",
        "features",
      ]);
      const rawTags =
        arrayTags.length > 0
          ? arrayTags
          : String(this.firstValue(item, ["capabilityLabel", "capability", "accessModeLabel", "deploymentLabel", "tag", "label"]) || "")
              .split(/[,，\s/]+/)
              .filter(Boolean);
      const templates = this.firstArrayValue(item, ["templates", "templateList", "modelTemplates"]);
      return {
        ...item,
        _isCatalogItem: true,
        _catalogId: this.firstValue(item, ["templateCode", "id", "catalogId", "code", "modelCode"]) || "-",
        _catalogName:
          this.firstValue(item, [
            "name",
            "catalogName",
            "modelName",
            "displayName",
            "title",
          ]) ||
          [item.vendorName, item.productName, item.accessModeLabel].filter(Boolean).join(" / ") ||
          "-",
        _adapter:
          this.firstValue(item, [
            "provider",
            "providerCode",
            "adapter",
            "adapterCode",
            "providerName",
            "adapterName",
          ]) || this.$t("modelConfig.unknown"),
        _platform:
          this.firstValue(item, [
            "platform",
            "platformName",
            "vendor",
            "vendorName",
            "company",
          ]) || "-",
        _product:
          this.firstValue(item, [
            "productName",
            "product",
            "seriesName",
            "capabilityLabel",
          ]) || "-",
        _upstreamModel:
          this.firstValue(item, [
            "upstreamResourceLabel",
            "upstreamModelField",
            "upstreamModel",
            "upstreamModelId",
            "model",
            "modelId",
            "modelName",
          ]) || "-",
        _capabilityTags: this.uniqueOptions(rawTags.map((tag) => String(tag).trim())).slice(0, 6),
        _templateCount:
          this.firstValue(item, ["templateCount", "templatesCount"]) ||
          (templates ? templates.length : "-"),
      };
    },
    getCatalogId(row) {
      return (row && row._catalogId) || this.normalizeCatalogItem(row)._catalogId;
    },
    getCatalogName(row) {
      return (row && row._catalogName) || this.normalizeCatalogItem(row)._catalogName;
    },
    getCatalogTemplateCount(row) {
      return (row && row._templateCount) || this.normalizeCatalogItem(row)._templateCount;
    },
    firstArrayValue(source, keys) {
      for (const key of keys) {
        const value = source && source[key];
        if (Array.isArray(value)) {
          return value;
        }
      }
      return [];
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
    formatBenchmarkStatus(status) {
      if (!status) {
        return "-";
      }
      const key = `modelConfig.benchmarkStatus.${status}`;
      const translated = this.$t(key);
      return translated === key ? status : translated;
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
    formatProcessSteps(steps) {
      if (!steps || !steps.length) {
        return "-";
      }
      return steps.join(" / ");
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
      if (result.success) {
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
      this.selectedPerformanceResultId = "";
      this.selectedPerformanceSampleKey = "";
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
        status: "PENDING",
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
        },
        ({ data }) => {
          if (data.code === 0) {
            this.performanceTaskId = data.data.taskId;
            this.performanceReport = {
              ...this.performanceReport,
              id: this.performanceTaskId,
              status: data.data.status || "PENDING",
            };
            this.$message.success(this.$t("modelConfig.performanceTestStarted"));
            this.pollPerformanceTask();
          } else {
            this.performanceTesting = false;
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
          if (data.data?.success) {
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
      if (this.viewMode !== "config") {
        this.selectedModels = [];
        this.isAllSelected = false;
        return;
      }
      this.selectedModels = val;
      this.isAllSelected = val.length === this.visibleModelList.length;
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
      if (this.viewMode === "catalog") {
        this.loadCatalogData();
        return;
      }
      this.loading = true; // 开始加载
      const params = {
        modelType: this.activeTab,
        modelName: this.hasClientFilters ? "" : this.search,
        page: this.hasClientFilters ? 1 : this.currentPage,
        limit: this.hasClientFilters ? 1000 : this.pageSize,
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
    loadCatalogData() {
      this.loading = true;
      this.catalogLoading = true;
      this.catalogError = "";
      Api.model.getModelCatalog(
        {
          modelType: this.activeTab,
          keyword: this.search,
          provider: this.adapterFilter,
          platform: this.platformFilter,
          capability: this.capabilityFilter,
        },
        ({ data }) => {
          this.loading = false;
          this.catalogLoading = false;
          if (data.code === 0 || data.code === "success" || data.code === undefined) {
            const payload = data.data || [];
            this.catalogList = Array.isArray(payload)
              ? payload
              : payload.list || payload.items || payload.catalogs || [];
            this.catalogError = "";
          } else {
            this.catalogList = [];
            this.catalogError = data.msg || this.$t("modelConfig.catalogUnavailable");
          }
        }
      );
    },
    openMigrationPreview() {
      if (this.migrationPreviewLoading) {
        return;
      }
      this.migrationPreviewDialogVisible = true;
      this.migrationPreviewLoading = true;
      this.migrationPreviewError = "";
      this.migrationPreviewData = null;
      const selectedModelIds =
        this.viewMode === "config" ? this.selectedModels.map((model) => model.id) : [];
      Api.model.previewModelMigration(
        {
          modelType: this.activeTab,
          modelIds: selectedModelIds,
          keyword: this.search,
        },
        ({ data }) => {
          this.migrationPreviewLoading = false;
          if (data.code === 0 || data.code === "success" || data.code === undefined) {
            this.migrationPreviewData = data.data || {};
          } else {
            this.migrationPreviewError =
              data.msg || this.$t("modelConfig.migrationPreviewUnavailable");
          }
        }
      );
    },
    openTemplateDialog(row) {
      const catalog = this.normalizeCatalogItem(row);
      this.templateDialogVisible = true;
      this.templateLoading = true;
      this.templateError = "";
      this.templateList = [];
      this.selectedCatalogId = catalog._catalogId;
      this.selectedCatalogName = catalog._catalogName;
      this.selectedCatalogProvider = catalog._adapter;
      Api.model.getModelTemplates(
        {
          modelType: this.activeTab,
          catalogId: catalog._catalogId === "-" ? "" : catalog._catalogId,
          provider: catalog._adapter === this.$t("modelConfig.unknown") ? "" : catalog._adapter,
        },
        ({ data }) => {
          this.templateLoading = false;
          if (data.code === 0 || data.code === "success" || data.code === undefined) {
            const payload = data.data || [];
            this.templateList = Array.isArray(payload)
              ? payload
              : payload.list || payload.items || payload.templates || [];
          } else {
            this.templateError = data.msg || this.$t("modelConfig.templatesUnavailable");
          }
        }
      );
    },
    getMigrationTotal(data) {
      if (Array.isArray(data)) {
        return data.length;
      }
      return (
        this.firstValue(data, ["total", "totalCount", "modelCount"]) ||
        this.migrationPreviewRows.length ||
        0
      );
    },
    getMigrationMatched(data) {
      return this.firstValue(data, ["matched", "matchedCount", "matchCount"]) || 0;
    },
    getMigrationWarnings(data) {
      const warnings = this.firstArrayValue(data, ["warnings", "warningList"]);
      return (
        this.firstValue(data, ["warningsCount", "warningCount", "unmatchedCount"]) ||
        warnings.length ||
        0
      );
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
  flex-wrap: wrap;
  justify-content: flex-end;
}

.search-input {
  width: 240px;
}

.filter-select {
  width: 132px;
}

.view-mode-switch {
  flex-shrink: 0;
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

.capability-tags {
  display: flex;
  justify-content: center;
  gap: 4px;
  flex-wrap: wrap;
  min-height: 24px;
}

.catalog-error {
  margin: 10px 0;
}

.migration-preview-alert {
  margin-bottom: 12px;
}

.migration-preview-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.migration-summary {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.migration-summary > div {
  display: flex;
  justify-content: space-between;
  padding: 10px 12px;
  border: 1px solid #edf0f5;
  border-radius: 4px;
  background: #f7f9fc;
  color: #606266;
}

.migration-summary strong {
  color: #303133;
}

.migration-preview-table {
  width: 100%;
}

.template-dialog-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  color: #303133;
  font-weight: 600;
}

.template-table {
  width: 100%;
}

.migration-raw pre {
  max-height: 260px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  background: #f7f9fc;
  border-radius: 4px;
  color: #303133;
  line-height: 1.5;
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

.performance-layout {
  display: grid;
  grid-template-columns: minmax(560px, 1fr) 380px;
  gap: 12px;
  align-items: start;
}

.performance-table {
  width: 100%;
}

.performance-detail {
  border: 1px solid #edf0f5;
  border-radius: 4px;
  max-height: 500px;
  overflow: auto;
  padding: 12px;
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

.performance-turn-meta {
  display: flex;
  align-items: center;
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

.performance-bubble-label {
  margin-bottom: 3px;
  color: #909399;
  font-size: 12px;
}

.performance-bubble {
  max-width: 86%;
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
  color: #909399;
  font-size: 12px;
}

.performance-sample-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 10px;
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
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
