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
                width="46"
                align="center"
                :selectable="canSelectForPerformance"
                :cell-class-name="selectionCellClassName"
              ></el-table-column>
              <el-table-column
                :label="$t('modelConfig.modelName')"
                align="left"
                min-width="180"
              >
                <template slot-scope="scope">
                  <div class="model-primary">
                    {{ scope.row.modelName }}
                    <el-tag v-if="isRealtimeModel(scope.row)" size="mini" type="warning">
                      {{ $t("realtimeModel.badge") }}
                    </el-tag>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                :label="$t('modelConfig.vendorProduct')"
                align="left"
                min-width="250"
              >
                <template slot-scope="scope">
                  <div class="catalog-cell">
                    <div class="catalog-main">
                      <span class="catalog-vendor">{{ getVendorName(scope.row) }}</span>
                      <el-tag
                        v-if="shouldShowCatalogStatusTag(scope.row)"
                        size="mini"
                        :type="getCatalogStatusTagType(scope.row)"
                        class="catalog-status-tag"
                      >
                        {{ getCatalogStatusText(scope.row) }}
                      </el-tag>
                    </div>
                    <div v-if="getProductName(scope.row)" class="catalog-sub">
                      {{ getProductName(scope.row) }}
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column :label="$t('modelConfig.isEnabled')" align="center" width="88">
                <template slot-scope="scope">
                  <el-switch
                    v-model="scope.row.isEnabled"
                    class="custom-switch"
                    :active-value="1"
                    :inactive-value="0"
                    :disabled="!canToggleEnabled(scope.row)"
                    @change="handleStatusChange(scope.row, $event)"
                  />
                </template>
              </el-table-column>
              <el-table-column :label="$t('modelConfig.testStatus')" align="center" width="92">
                <template slot-scope="scope">
                  <el-tag
                    size="mini"
                    :type="getTestStatusTagType(scope.row.testStatus)"
                  >
                    {{ getTestStatusText(scope.row.testStatus) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column
                v-if="activeTab === 'tts'"
                :label="$t('modelConfig.voiceManagement')"
                align="center"
                width="100"
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
                width="144"
              >
                <template slot-scope="scope">
                  <div class="row-actions">
                    <el-button
                      type="text"
                      size="mini"
                      :loading="testingModelId === scope.row.id"
                      :disabled="!canRunModelTest(scope.row)"
                      @click="testModel(scope.row)"
                      class="table-action-btn edit-btn"
                    >
                      <i class="el-icon-video-play"></i>
                    </el-button>
                    <el-button
                      type="text"
                      size="mini"
                      @click="editModel(scope.row)"
                      class="table-action-btn edit-btn"
                    >
                      <i class="el-icon-edit"></i>
                    </el-button>
                    <el-button
                      type="text"
                      size="mini"
                      :disabled="!canDuplicateModel(scope.row)"
                      @click="duplicateModel(scope.row)"
                      class="table-action-btn edit-btn"
                    >
                      <i class="el-icon-document-copy"></i>
                    </el-button>
                    <el-button
                      type="text"
                      size="mini"
                      @click="deleteModel(scope.row)"
                      class="table-action-btn delete-btn"
                    >
                      <i class="el-icon-delete"></i>
                    </el-button>
                  </div>
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
                  :disabled="loading || currentPage <= 1"
                  @click="goFirst"
                >
                  {{ $t("modelConfig.firstPage") }}
                </button>
                <button
                  class="pagination-btn"
                  :disabled="loading || currentPage <= 1"
                  @click="goPrev"
                >
                  {{ $t("modelConfig.prevPage") }}
                </button>

                <button
                  v-for="page in visiblePages"
                  :key="page"
                  class="pagination-btn"
                  :class="{ active: page === currentPage }"
                  :disabled="loading || page === currentPage"
                  @click="goToPage(page)"
                >
                  {{ page }}
                </button>

                <button
                  class="pagination-btn"
                  :disabled="loading || currentPage >= pageCount"
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
          <ModelConfigAssistant
            :visible="assistantVisible"
            :context="assistantContext"
            @toggle="toggleAssistant"
            @apply-draft="applyAssistantDraft"
          />
        </div>
      </div>

      <OpsModelPublication ref="publication" @published="loadData" />
      <ModelEditDialog
        ref="editModelDialog"
        :platform-managed="true"
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
      <el-dialog
        :title="$t('modelConfig.testDetailTitle')"
        :visible.sync="testResultDialogVisible"
        width="640px"
        append-to-body
        class="test-result-dialog"
      >
        <div v-if="currentTestResult" class="test-result-content">
          <realtime-model-notice
            :model="currentTestModel"
            context="test"
          />
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
          <div v-if="shouldShowFirstTextMetric(currentTestResult)" class="test-result-row">
            <span class="test-result-label">{{ getFirstTextMetricLabel() }}</span>
            <span>{{ formatResponseTime(currentTestResult.firstTextMs) }}</span>
          </div>
          <div v-if="shouldShowFirstAudioMetric(currentTestResult)" class="test-result-row">
            <span class="test-result-label">{{ $t("modelConfig.firstAudio") }}</span>
            <span>{{ formatResponseTime(currentTestResult.firstAudioMs) }}</span>
          </div>
          <div class="test-result-block">
            <div class="test-result-label">{{ getTestInputLabel(currentTestResult) }}</div>
            <div v-if="currentTestResult.testImageDataUrl" class="test-result-image-wrap">
              <img
                class="test-result-image"
                :src="currentTestResult.testImageDataUrl"
                alt="test image"
              />
            </div>
            <div v-if="shouldShowInputAudio(currentTestResult)" class="test-result-audio-wrap">
              <audio class="test-result-audio" :src="currentTestResult.testAudioDataUrl" controls />
            </div>
            <pre>{{ currentTestResult.testInput || "-" }}</pre>
          </div>
          <div class="test-result-block">
            <div class="test-result-label">{{ getTestOutputLabel(currentTestResult) }}</div>
            <div v-if="shouldShowOutputAudio(currentTestResult)" class="test-result-audio-wrap">
              <div class="test-result-audio-title">
                {{ $t("modelConfig.ttsAudioPreview") }}
              </div>
              <audio class="test-result-audio" :src="currentTestResult.testAudioDataUrl" controls />
            </div>
            <pre>{{ formatTestOutput(currentTestResult.testOutput) }}</pre>
          </div>
        </div>
      </el-dialog>
      <ModelBenchmarkDialog
        :visible.sync="benchmarkDialogVisible"
        :model-type="activeTab"
        :selected-models="selectedModels"
        @testing-change="handleBenchmarkTestingChange"
        @finished="handleBenchmarkFinished"
      />
    </div>
    <el-footer>
      <version-footer />
    </el-footer>
  </div>
</template>

<script>
import Api from "@/apis/api";
import OpsModelPublication from "@/ops/OpsModelPublication.vue";
import { hasPermission } from "@/security/session";
import HeaderBar from "@/components/HeaderBar.vue";
import ModelBenchmarkDialog from "@/components/ModelBenchmarkDialog.vue";
import ModelConfigAssistant from "@/components/ModelConfigAssistant.vue";
import ModelEditDialog from "@/components/ModelEditDialog.vue";
import RealtimeModelNotice from "@/components/model/RealtimeModelNotice.vue";
import TtsModel from "@/components/TtsModel.vue";
import VersionFooter from "@/components/VersionFooter.vue";
import { isRealtimeModel as isRealtimeConversationModel } from "@/utils/realtimeModel";

export default {
  components: {
    OpsModelPublication,
    HeaderBar,
    ModelBenchmarkDialog,
    ModelEditDialog,
    ModelConfigAssistant,
    RealtimeModelNotice,
    TtsModel,
    VersionFooter,
  },
  data() {
    return {
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
      currentTestModel: null,
      benchmarkDialogVisible: false,
      performanceTesting: false,
      catalogItems: [],
      assistantVisible: false,
      assistantTarget: null,
      pendingAssistantDraft: null,
    };
  },

  created() {
    this.loadCatalogItems();
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
    assistantContext() {
      const target = this.assistantTarget || {};
      const params = target.params || {};
      const secrets = target.secrets || {};
      return {
        modelType: this.activeTab.toUpperCase(),
        modelId: target.id || "",
        modelName: target.modelName || "",
        providerCode:
          target.adapterCode ||
          target.providerCode ||
          target.providerType ||
          "",
        adapterCode: target.adapterCode || target.providerCode || "",
        protocol: target.protocol || "",
        upstreamModelId: this.getUpstreamModel(target, ""),
        endpointUrl: target.endpointUrl || target.endpointSummary || "",
        params,
        secrets,
        configJson: {
          ...params,
          ...secrets,
          type: target.adapterCode || target.providerCode || "",
          protocol: target.protocol || "",
          model_name: target.upstreamModelId || "",
          base_url: target.endpointUrl || "",
        },
        docLink: target.docLink || "",
        testMessage: target.testMessage || "",
        pageContext: this.editDialogVisible ? "edit" : "list",
      };
    },
  },

  methods: {
    isRealtimeModel(model) {
      return isRealtimeConversationModel(model);
    },
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
      this.assistantTarget = {
        modelType: this.activeTab.toUpperCase(),
        adapterCode: "",
        protocol: "",
        upstreamModelId: "",
        endpointUrl: "",
        params: {},
        secrets: {},
      };
      this.editModelData = {};
      this.editDialogVisible = true;
    },
    editModel(model) {
      this.assistantTarget = JSON.parse(JSON.stringify(model || {}));
      this.editModelData = JSON.parse(JSON.stringify(model));
      this.editDialogVisible = true;
    },
    duplicateModel(model) {
      if (!this.canDuplicateModel(model)) {
        this.$message.warning(this.$t("modelConfig.unsupportedViewOnly"));
        return;
      }
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
      return hasPermission(this.$store.state.userInfo, model.isEnabled === 1 ? 'model.catalog.write' : 'model.catalog.publish') && (model.isEnabled === 1 || (this.isModelSupportedForActions(model) && model.testStatus === 1));
    },
    canRunModelTest(model) {
      return !this.testingModelId && this.isModelSupportedForActions(model);
    },
    canDuplicateModel(model) {
      return this.isModelSupportedForActions(model);
    },
    canSelectForPerformance(model) {
      return this.isModelSupportedForActions(model);
    },
    normalizeStatus(value) {
      return String(value || "").toUpperCase();
    },
    isUnsupportedCatalogStatus(supportedStatus) {
      const status = this.normalizeStatus(supportedStatus);
      return ["NEEDS_ADAPTER", "DEPRECATED"].includes(status);
    },
    isModelSupportedForActions(model) {
      if (!model) {
        return false;
      }
      return model.selectable !== false && !model.deprecated && !this.isUnsupportedCatalogStatus(model.supportedStatus);
    },
    findCatalogItemForForm(formData = {}, provideCode = "") {
      const templateCode = formData.templateCode || "";
      const adapterCode = formData.adapterCode || formData.providerCode || provideCode || "";
      const vendorName = formData.vendorName || "";
      const productName = formData.productName || "";
      return (this.catalogItems || []).find((item) => {
        if (templateCode && item.templateCode === templateCode) {
          return true;
        }
        if (!templateCode && adapterCode && item.providerCode === adapterCode) {
          if (!vendorName && !productName) {
            return true;
          }
          return (!vendorName || item.vendorName === vendorName) && (!productName || item.productName === productName);
        }
        return false;
      });
    },
    isFormSupportedForSave(formData = {}, provideCode = "") {
      if (this.editModelData && this.editModelData.id && !this.editModelData.duplicateMode) {
        return this.isModelSupportedForActions(this.editModelData);
      }
      const item = this.findCatalogItemForForm(formData, provideCode);
      if (!item) {
        return true;
      }
      return item.selectable !== false && !item.deprecated && !this.isUnsupportedCatalogStatus(item.supportedStatus);
    },
    clearTestingModelState() {
      this.testingModelId = "";
    },
    showTestResult(result) {
      this.currentTestResult = result || null;
      this.testResultDialogVisible = !!result;
      if (result && this.assistantTarget) {
        this.assistantTarget.testMessage = result.message || result.testOutput || "";
      }
    },
    isModelTestVerified(result) {
      return !!result && (result.verified === true || result.testStatus === 1);
    },
    isTtsTestResult(result) {
      return !!result && this.activeTab === "tts";
    },
    shouldShowInputAudio(result) {
      return !!(result && result.testAudioDataUrl && !this.isTtsTestResult(result));
    },
    shouldShowOutputAudio(result) {
      return !!(result && result.testAudioDataUrl && this.isTtsTestResult(result));
    },
    shouldShowFirstTextMetric(result) {
      const supportedType = this.activeTab === "asr" || this.isRealtimeModel(this.currentTestModel);
      return !!(result && supportedType && result.firstTextMs !== null && result.firstTextMs !== undefined && result.firstTextMs !== "");
    },
    shouldShowFirstAudioMetric(result) {
      const supportedType = this.activeTab === "tts" || this.isRealtimeModel(this.currentTestModel);
      return !!(result && supportedType && result.firstAudioMs !== null && result.firstAudioMs !== undefined && result.firstAudioMs !== "");
    },
    getFirstTextMetricLabel() {
      return this.isRealtimeModel(this.currentTestModel)
        ? this.$t("modelConfig.realtimeFirstText")
        : this.$t("modelConfig.firstAsrText");
    },
    getTestInputLabel(result) {
      return this.isTtsTestResult(result)
        ? this.$t("modelConfig.ttsInputText")
        : this.$t("modelConfig.testInput");
    },
    getTestOutputLabel(result) {
      return this.isTtsTestResult(result)
        ? this.$t("modelConfig.ttsOutputAudio")
        : this.$t("modelConfig.testOutput");
    },
    formatResponseTime(responseTimeMs) {
      if (responseTimeMs === null || responseTimeMs === undefined || responseTimeMs === "") {
        return "-";
      }
      return `${responseTimeMs} ms`;
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
    displayValue(value, fallback = this.$t("modelConfig.unknown")) {
      if (value === null || value === undefined || value === "") {
        return fallback;
      }
      return value;
    },
    getConfigValue(model, keys = []) {
      const config = {
        ...((model && model.params) || {}),
        ...((model && model.secrets) || {}),
      };
      for (const key of keys) {
        if (config[key] !== null && config[key] !== undefined && config[key] !== "") {
          return config[key];
        }
      }
      return "";
    },
    getUpstreamModel(model, fallback = this.$t("modelConfig.unknown")) {
      return this.displayValue(
        (model && model.upstreamModelId) ||
          this.getConfigValue(model, ["model_name", "model", "resource_id", "voice", "speaker"]),
        fallback
      );
    },
    getAdapterName(model) {
      return this.displayValue(
        (model && (model.adapterLabel || model.adapterCode || model.providerCode)) ||
          this.getConfigValue(model, ["type"])
      );
    },
    getAccessSummary(model) {
      const access = (model && model.accessModeLabel) || "";
      const protocol = (model && (model.protocolLabel || model.protocol)) || "";
      return [access, protocol, this.getAdapterName(model)].filter(Boolean).join(" · ");
    },
    getCatalogStatusText(model) {
      if (!model || !model.supportedStatus) {
        return "";
      }
      if (model.supportedStatus === "NEEDS_ADAPTER") {
        return "待适配";
      }
      if (model.supportedStatus === "PARTIAL" || model.supportedStatus === "NEEDS_REVIEW") {
        return "待补全";
      }
      if (model.supportedStatus === "DEPRECATED" || model.deprecated) {
        return "不可用";
      }
      if (model.selectable === false) {
        return "不可启用";
      }
      if (model.migrationStatus === "NEEDS_REVIEW") {
        return "待补全";
      }
      return "已适配";
    },
    getCatalogStatusTagType(model) {
      if (!model || !model.supportedStatus) {
        return "info";
      }
      if (model.supportedStatus === "NEEDS_ADAPTER") {
        return "danger";
      }
      if (["PARTIAL", "NEEDS_REVIEW"].includes(model.supportedStatus) || model.migrationStatus === "NEEDS_REVIEW") {
        return "warning";
      }
      if (model.supportedStatus === "DEPRECATED" || model.deprecated) {
        return "info";
      }
      if (model.selectable === false) {
        return "warning";
      }
      return "success";
    },
    shouldShowCatalogStatusTag(model) {
      if (!model) {
        return false;
      }
      return model.selectable === false
        || model.deprecated
        || model.migrationStatus === "NEEDS_REVIEW"
        || ["NEEDS_ADAPTER", "PARTIAL", "NEEDS_REVIEW", "DEPRECATED"].includes(model.supportedStatus);
    },
    getVendorProduct(model) {
      const vendor = this.getVendorName(model, "");
      const product = this.getProductName(model);
      if (vendor && product) {
        return `${vendor} / ${product}`;
      }
      return this.displayValue(vendor || product);
    },
    getVendorName(model, fallback = this.$t("modelConfig.unknown")) {
      const vendor = (model && (model.vendorName || model.platformName)) || "";
      return this.displayValue(vendor, fallback);
    },
    getProductName(model) {
      const product = (model && model.productName) || "";
      return product;
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
      if (!this.canRunModelTest(model)) {
        if (!this.isModelSupportedForActions(model)) {
          this.$message.warning(this.$t("modelConfig.unsupportedCannotTest"));
        }
        return;
      }
      this.currentTestModel = model;
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
      const unsupportedModels = this.selectedModels.filter((model) => !this.canSelectForPerformance(model));
      if (unsupportedModels.length > 0) {
        this.$message.warning(this.$t("modelConfig.unsupportedCannotPerformance"));
        return;
      }
      this.benchmarkDialogVisible = true;
    },
    handleBenchmarkTestingChange(testing) {
      this.performanceTesting = testing;
    },
    handleBenchmarkFinished() {
      this.loadData();
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
      const adapterCode = provideCode || formData.adapterCode || formData.providerCode;

      if (!this.isFormSupportedForSave(formData, adapterCode)) {
        this.$message.error(this.$t("modelConfig.unsupportedCannotSave"));
        done && done();
        return;
      }

      if (this.editModelData.duplicateMode || !id) {
        formData.id = "";
        Api.model.addModel({ modelType, provideCode: adapterCode, formData }, ({ data }) => {
          if (data.code === 0) {
            this.$message.success(
              this.editModelData.duplicateMode
                ? this.$t("modelConfig.duplicateSuccess")
                : this.$t("modelConfig.addSuccess")
            );
            this.loadData();
            this.editDialogVisible = false;
          } else {
            this.$message.error(
              data.msg ||
                (this.editModelData.duplicateMode
                  ? this.$t("modelConfig.duplicateFailed")
                  : this.$t("modelConfig.addFailed"))
            );
          }
          done && done(); // 调用done回调关闭加载状态
        });
      } else {
        Api.model.updateModel({ modelType, provideCode: adapterCode, id, formData }, ({ data }) => {
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
      const adapterCode = provideCode || formData.adapterCode || formData.providerCode;

      if (!this.isFormSupportedForSave(formData, adapterCode)) {
        this.$message.error(this.$t("modelConfig.unsupportedCannotSave"));
        done && done();
        return;
      }

      const testSavedModel = (id) => {
        if (!id) {
          this.clearTestingModelState();
          this.$message.error(this.$t("modelConfig.saveFailed"));
          done && done();
          return;
        }

        this.currentTestModel = { ...formData, id };
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

      if (this.editModelData.duplicateMode || !formData.id) {
        formData.id = "";
        Api.model.addModel({ modelType, provideCode: adapterCode, formData }, ({ data }) => {
          if (data.code === 0) {
            testSavedModel(data.data?.id);
          } else {
            this.$message.error(
              data.msg ||
                (this.editModelData.duplicateMode
                  ? this.$t("modelConfig.duplicateFailed")
                  : this.$t("modelConfig.addFailed"))
            );
            done && done();
          }
        });
        return;
      }

      Api.model.updateModel({ modelType, provideCode: adapterCode, id: formData.id, formData }, ({ data }) => {
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
      const supportedSelection = val.filter((model) => this.canSelectForPerformance(model));
      if (supportedSelection.length !== val.length) {
        this.$nextTick(() => {
          val
            .filter((model) => !this.canSelectForPerformance(model))
            .forEach((model) => this.$refs.modelTable.toggleRowSelection(model, false));
        });
      }
      this.selectedModels = supportedSelection;
      const selectableCount = this.modelList.filter((model) => this.canSelectForPerformance(model)).length;
      this.isAllSelected = selectableCount > 0 && supportedSelection.length === selectableCount;
      if (val.length === 0) {
        this.isAllSelected = false;
      }
    },

    toggleAssistant() {
      this.assistantVisible = !this.assistantVisible;
    },
    applyAssistantDraft(response) {
      const draft = response && response.draftConfigJson;
      if (!draft) {
        this.$message.warning(this.$t("modelConfigAssistant.noDraft"));
        return;
      }
      const target = this.assistantTarget || {};
      const providerCode =
        response.matchedAdapter ||
        draft.type ||
        target.adapterCode ||
        target.providerCode ||
        "";
      const payload = {
        providerCode,
        adapterCode: providerCode,
        upstreamModelId:
          draft.model_name ||
          draft.model ||
          draft.resource_id ||
          draft.voice ||
          target.upstreamModelId ||
          "",
        endpointUrl:
          draft.base_url ||
          draft.api_url ||
          draft.url ||
          draft.host ||
          target.endpointUrl ||
          "",
        protocol: draft.protocol || target.protocol || "",
        configJson: { ...draft, type: providerCode || draft.type },
        params: target.params || {},
        secrets: target.secrets || {},
        docLink: target.docLink || "",
        remark: target.remark || "",
      };

      if (this.editDialogVisible && this.$refs.editModelDialog) {
        this.$refs.editModelDialog.applyAssistantDraft(payload);
        this.$message.success(this.$t("modelConfigAssistant.draftApplied"));
        return;
      }

      if (!this.editDialogVisible) {
        this.addModel();
      }
      this.pendingAssistantDraft = payload;
      this.$nextTick(() => {
        if (this.$refs.editModelDialog) {
          this.$refs.editModelDialog.applyAssistantDraft(this.pendingAssistantDraft);
          this.pendingAssistantDraft = null;
          this.$message.success(this.$t("modelConfigAssistant.draftApplied"));
        }
      });
    },

    // 分页器
    goFirst() {
      this.goToPage(1);
    },
    goPrev() {
      this.goToPage(this.currentPage - 1);
    },
    goNext() {
      this.goToPage(this.currentPage + 1);
    },
    goToPage(page) {
      if (this.loading || !Number.isInteger(page) || page < 1 || page > this.pageCount || page === this.currentPage) return;
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
            isEnabled: model.testStatus === 1 && this.isModelSupportedForActions(model) ? model.isEnabled : 0,
          }));
          this.total = data.data.total;
          this.loadCatalogItems();
        } else {
          this.$message.error(data.msg || this.$t("modelConfig.fetchModelsFailed"));
        }
      });
    },
    loadCatalogItems() {
      Api.model.getModelCatalog({ modelType: this.activeTab }, (res) => {
        const data = res && res.data && res.data.code === 0 ? (res.data.data || []) : [];
        this.catalogItems = data;
      });
    },
    // 处理启用/非启用状态变更
    handleStatusChange(model, newStatus) {
      const normalizedStatus = newStatus ? 1 : 0;
      const originalStatus = normalizedStatus === 1 ? 0 : 1;

      if (normalizedStatus === 1 && !this.isModelSupportedForActions(model)) {
        model.isEnabled = originalStatus;
        this.$message.warning(this.$t("modelConfig.unsupportedCannotEnable"));
        return;
      }

      if (normalizedStatus === 1 && model.testStatus !== 1) {
        model.isEnabled = originalStatus;
        this.$message.warning(this.$t("modelConfig.enableRequiresTestPassed"));
        return;
      }

      if (normalizedStatus === 1) { model.isEnabled = originalStatus; this.$refs.publication.open(model); return; }
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

.test-result-audio-title {
  margin-bottom: 8px;
  color: #606266;
  font-size: 13px;
  line-height: 18px;
}

.test-result-audio {
  display: block;
  width: 100%;
  height: 36px;
}

.model-primary {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #303133;
  font-weight: 600;
  line-height: 22px;
}

.model-secondary {
  color: #909399;
  font-size: 12px;
  line-height: 18px;
  word-break: break-all;
}

.upstream-cell {
  color: #303a52;
  font-weight: 500;
  line-height: 20px;
  word-break: break-word;
}

.catalog-cell {
  min-width: 0;
  text-align: left;
}

.catalog-main {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #252f45;
  font-size: 14px;
  font-weight: 600;
  line-height: 20px;
  min-width: 0;
}

.catalog-vendor {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.catalog-status-tag {
  flex: 0 0 auto;
}

.catalog-sub {
  color: #5e687d;
  font-size: 12px;
  line-height: 17px;
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-actions {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  white-space: nowrap;
}

.table-action-btn {
  width: 24px;
  height: 24px;
  padding: 0 !important;
  margin: 0 !important;
  font-size: 15px;
  line-height: 24px;
}

</style>
