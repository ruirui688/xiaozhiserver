<template>
  <div class="welcome">
    <HeaderBar />

    <div class="operation-bar">
      <div class="left-operations">
        <el-button class="btn-back" type="text" @click="$router.back()">
          &lt;
        </el-button>
        <h2 class="knowledge-base-title">{{ knowledgeBaseName }}</h2>
      </div>
      <div class="right-operations">
        <el-input :placeholder="$t('knowledgeFileUpload.searchPlaceholder')" v-model="searchName" class="search-input"
          @keyup.enter.native="handleSearch" clearable />
        <el-button class="btn-search" @click="handleSearch">{{ $t('knowledgeFileUpload.search') }}</el-button>
      </div>
    </div>

    <div class="main-wrapper">
      <div class="content-panel">
        <div class="content-area">
          <el-card class="params-card" shadow="never">
            <div class="table-wrapper">
              <el-table ref="fileTable" :data="fileList" class="transparent-table" v-loading="loading"
                element-loading-text="Loading" element-loading-spinner="el-icon-loading"
                element-loading-background="rgba(255, 255, 255, 0.7)" :header-cell-class-name="headerCellClassName"
                @selection-change="handleSelectionChange">
                <el-table-column v-if="!readOnly" type="selection" width="55" align="center"
                  :selectable="canSelectDocument"></el-table-column>
                <el-table-column :label="$t('knowledgeFileUpload.documentName')" prop="name" align="left">
                  <template slot-scope="scope">
                    <span class="document-name">{{ scope.row.name }}</span>
                  </template>
                </el-table-column>
                <el-table-column :label="$t('knowledgeFileUpload.uploadTime')" prop="createdAt" align="center"
                  width="300">
                  <template slot-scope="scope">
                    <span>{{ formatDate(scope.row.createdAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column :label="$t('knowledgeFileUpload.status')" align="center" width="240">
                  <template slot-scope="scope">
                    <div class="document-status-cell">
                      <el-tag :type="getParseStatusType(scope.row.parseStatusCode)" size="small">
                        {{ getParseStatusText(scope.row.parseStatusCode) }}
                      </el-tag>
                      <template v-if="scope.row.parseStatusCode === 1">
                        <span class="status-detail">{{ getProcessingStage(scope.row) }}</span>
                        <el-progress :percentage="getProgressPercent(scope.row.progress)" :show-text="false"
                          :stroke-width="5"></el-progress>
                      </template>
                      <el-tooltip v-else-if="scope.row.parseStatusCode === 4 && scope.row.error"
                        :content="scope.row.error" placement="top" effect="dark">
                        <span class="status-detail status-error">{{ scope.row.error }}</span>
                      </el-tooltip>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column :label="$t('knowledgeFileUpload.sliceCount')" align="center" width="100">
                  <template slot-scope="scope">
                    <span>{{ scope.row.sliceCount || 0 }}</span>
                  </template>
                </el-table-column>
                <el-table-column :label="$t('knowledgeFileUpload.operation')" align="center" width="200">
                  <template slot-scope="scope">
                    <el-button size="mini" type="text" @click="handleParse(scope.row)"
                      :disabled="scope.row.parseStatusCode === 1 || scope.row.parseStatusCode === 3 || scope.row.parseStatusCode === 4"
                      v-if="!readOnly && scope.row.sourceType !== 'local_knowledge' && (!scope.row.sliceCount || scope.row.sliceCount <= 0)">
                      {{ $t('knowledgeFileUpload.parse') }}
                    </el-button>
                    <el-button size="mini" type="text" @click="handleRetryUpload(scope.row)"
                      v-if="!readOnly && scope.row.sourceType === 'local_knowledge' && scope.row.parseStatusCode === 4">
                      {{ $t('knowledgeFileUpload.reupload') }}
                    </el-button>
                    <el-button size="mini" type="text" @click="handleViewSlices(scope.row)"
                      v-if="scope.row.sliceCount && scope.row.sliceCount > 0">
                      {{ readOnly ? '查看内容' : '管理内容' }}
                    </el-button>
                    <el-button v-if="!readOnly" size="mini" type="text" @click="handleDelete(scope.row)"
                      :disabled="scope.row.parseStatusCode === 1">
                      {{ $t('knowledgeFileUpload.delete') }}
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="table_bottom">
              <div class="ctrl_btn">
                <el-button v-if="!readOnly" type="primary" @click="handleSelectAll">
                  {{ isAllSelected ? $t('knowledgeFileUpload.deselectAll') : $t('knowledgeFileUpload.selectAll') }}
                </el-button>
                <el-button v-if="!readOnly" type="success" @click="showUploadDialog">
                  {{ $t('knowledgeFileUpload.add') }}
                </el-button>
                <el-button v-if="!readOnly" type="warning" icon="el-icon-connection" @click="sourcePanelVisible = true">
                  动态数据源
                </el-button>
                <el-button v-if="!readOnly" type="danger" @click="handleBatchDelete" :disabled="selectedFiles.length === 0">
                  {{ $t('knowledgeFileUpload.batchDelete') }}
                </el-button>
                <el-button type="primary" icon="el-icon-search" @click="showRetrievalTestDialog"
                  style="background-color: #409EFF; border-color: #409EFF;">
                  {{ $t('knowledgeFileUpload.retrievalTest') }}
                </el-button>
              </div>
              <div class="custom-pagination">
                <el-select v-model="pageSize" @change="handlePageSizeChange" class="page-size-select">
                  <el-option v-for="item in pageSizeOptions" :key="item"
                    :label="`${item}${$t('knowledgeFileUpload.itemsPerPage')}`" :value="item">
                  </el-option>
                </el-select>
                <button class="pagination-btn" :disabled="currentPage === 1" @click="goFirst">
                  {{ $t('knowledgeFileUpload.firstPage') }}
                </button>
                <button class="pagination-btn" :disabled="currentPage === 1" @click="goPrev">
                  {{ $t('knowledgeFileUpload.prevPage') }}
                </button>
                <button v-for="page in visiblePages" :key="page" class="pagination-btn"
                  :class="{ active: page === currentPage }" @click="goToPage(page)">
                  {{ page }}
                </button>
                <button class="pagination-btn" :disabled="currentPage === pageCount" @click="goNext">
                  {{ $t('knowledgeFileUpload.nextPage') }}
                </button>
                <span class="total-text">{{ $t('knowledgeFileUpload.totalRecords', { total }) }}</span>
              </div>
            </div>
          </el-card>
        </div>
      </div>
    </div>

    <!-- 上传文档对话框 -->
    <file-upload-panel
      :visible="uploadDialogVisible"
      :dataset-id="datasetId"
      :upload-url="uploadUrl"
      @close="uploadDialogVisible = false"
      @upload-complete="handleUploadComplete" />

    <knowledge-source-panel
      :visible="sourcePanelVisible"
      :dataset-id="datasetId"
      @close="sourcePanelVisible = false"
      @documents-changed="fetchFileList" />

    <!-- 召回测试弹窗 -->
    <el-dialog :title="$t('knowledgeFileUpload.retrievalTest')" :visible.sync="retrievalTestDialogVisible"
      width="1200px" class="retrieval-test-dialog">
      <div class="retrieval-test-form">
        <el-form :model="retrievalTestForm" label-width="80px" @submit.native.prevent="runRetrievalTest">
          <el-form-item :label="$t('knowledgeFileUpload.testQuestion')" required>
            <el-input v-model="retrievalTestForm.question"
              :placeholder="$t('knowledgeFileUpload.testQuestionPlaceholder')" style="width: 100%; max-height: 80px;"
              @keyup.enter.native="runRetrievalTest">
            </el-input>
          </el-form-item>
        </el-form>

        <div class="retrieval-test-actions" style="text-align: center;">
          <el-button type="primary" @click="runRetrievalTest" :loading="retrievalTestLoading">
            {{ $t('knowledgeFileUpload.runTest') }}
          </el-button>
          <el-button @click="retrievalTestDialogVisible = false">{{ $t('knowledgeFileUpload.cancel') }}</el-button>
        </div>

        <div v-if="retrievalTestResult" class="retrieval-test-result">
          <h4>{{ $t('knowledgeFileUpload.testResult') }}</h4>
          <el-card v-if="retrievalTestResult.chunks && retrievalTestResult.chunks.length > 0">
            <div class="result-chunk-container">
              <div v-for="(chunk, index) in retrievalTestResult.chunks" :key="index" class="result-chunk">
                <p><strong>{{ $t('knowledgeFileUpload.slice') }} {{ index + 1 }}</strong></p>
                <div class="similarity-scores">
                  <div class="score-item">
                    <span class="score-label">{{ $t('knowledgeFileUpload.comprehensiveSimilarity') }}</span>
                    <span class="score-value">{{ (chunk.similarity || 0).toFixed(4) }}</span>
                  </div>
                </div>
                <div class="chunk-content">
                  <p><strong>{{ $t('knowledgeFileUpload.content') }}</strong></p>
                  <p>{{ chunk.content }}</p>
                </div>
                <el-divider v-if="index < retrievalTestResult.chunks.length - 1"></el-divider>
              </div>
            </div>
          </el-card>
          <el-alert v-else type="info" :title="$t('knowledgeFileUpload.noRelatedSlices')"></el-alert>
        </div>
      </div>
    </el-dialog>

    <el-footer>
      <version-footer />
    </el-footer>
  </div>
</template>

<script>
import Api from "@/apis/api";
import KnowledgeBaseAPI from "@/apis/module/knowledgeBase";
import HeaderBar from "@/components/HeaderBar.vue";
import VersionFooter from "@/components/VersionFooter.vue";
import FileUploadPanel from "@/components/knowledge/FileUploadPanel.vue";
import KnowledgeSourcePanel from "@/components/knowledge/KnowledgeSourcePanel.vue";

export default {
  components: { HeaderBar, VersionFooter, FileUploadPanel, KnowledgeSourcePanel },
  data() {
    return {
      datasetId: '',
      knowledgeBaseName: '',
      readOnly: false,
      searchName: "",
      fileList: [],
      selectedFiles: [],
      currentPage: 1,
      loading: false,
      pageSize: 10,
      pageSizeOptions: [10, 20, 50, 100],
      total: 0,
      uploadDialogVisible: false,
      sourcePanelVisible: false,
      uploadUrl: '',
      isAllSelected: false,

      // 召回测试相关数据
      retrievalTestDialogVisible: false,
      retrievalTestForm: {
        question: ''
      },
      retrievalTestResult: null,
      retrievalTestLoading: false,

      // 状态轮询相关数据
      statusPollingTimer: null,
      statusPollingInterval: 3000
    };
  },
  created() {
    this.datasetId = this.$route.query.datasetId || '';
    this.knowledgeBaseName = this.$route.query.knowledgeBaseName || '';
    this.readOnly = this.$route.query.readOnly === '1';
    this.uploadUrl = `${Api.getServiceUrl()}/api/v1/documents/upload`;
    this.fetchFileList();
    if (this.$route.query.retrieval === '1') this.showRetrievalTestDialog();
  },

  beforeDestroy() {
    this.stopStatusPolling();
  },
  computed: {
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
    }
  },
  methods: {
    goBack() {
      this.$router.push('/knowledge-base-management');
    },
    handlePageSizeChange: function (val) {
      this.pageSize = val;
      this.currentPage = 1;
      this.fetchFileList();
    },
    fetchFileList: function () {
      this.loading = true;
      const params = {
        page: this.currentPage,
        page_size: this.pageSize,
        name: this.searchName
      };

      KnowledgeBaseAPI.getDocumentList(this.datasetId, params,
        async ({ data }) => {
          this.loading = false;
          if (data && data.code === 0) {
            this.fileList = data.data.list;
            this.total = data.data.total;

            await this.fetchSliceCountsForDocuments();

            this.startStatusPolling();
          } else {
            this.$message.error(data?.msg || this.$t('knowledgeFileUpload.getListFailed'));
            this.fileList = [];
            this.total = 0;
          }
        },
        (err) => {
          this.loading = false;
          console.log('Error callback received:', err);
          if (err && err.data) {
            console.log('后端返回错误消息:', err.data.msg || err.msg);
            this.$message.error(err.data.msg || err.msg || this.$t('knowledgeFileUpload.getListFailed'));
          } else {
            this.$message.error(this.$t('knowledgeFileUpload.getListFailed'));
          }
          console.error('获取文档列表失败:', err);
          this.fileList = [];
          this.total = 0;
        }
      );
    },

    // 启动文档状态轮询
    startStatusPolling: function () {
      if (this.statusPollingTimer) {
        return;
      }

      const hasProcessingDocuments = this.fileList.some(document =>
        document.parseStatusCode === 1
      );

      if (!hasProcessingDocuments) {
        return;
      }

      this.pollDocumentStatus();

      this.statusPollingTimer = setInterval(() => {
        this.pollDocumentStatus();
      }, this.statusPollingInterval);
    },

    stopStatusPolling: function () {
      if (this.statusPollingTimer) {
        clearInterval(this.statusPollingTimer);
        this.statusPollingTimer = null;
      }
    },

    pollDocumentStatus: async function () {
      try {
        const params = {
          page: this.currentPage,
          page_size: this.pageSize,
          name: this.searchName
        };

        const response = await new Promise((resolve, reject) => {
          KnowledgeBaseAPI.getDocumentList(this.datasetId, params,
            ({ data }) => resolve(data),
            (err) => reject(err)
          );
        });

        if (response && response.code === 0) {
          const updatedFileList = response.data.list;
          this.updateDocumentStatuses(updatedFileList);

          const hasProcessingDocuments = updatedFileList.some(document =>
            document.parseStatusCode === 1
          );

          if (!hasProcessingDocuments) {
            this.stopStatusPolling();
          }
        }
      } catch (error) {
        console.warn('轮询文档状态失败:', error);
      }
    },

    updateDocumentStatuses: function (updatedFileList) {
      let hasChanges = false;

      updatedFileList.forEach(updatedDoc => {
        const existingDoc = this.fileList.find(doc => doc.id === updatedDoc.id);
        if (existingDoc) {
          const previousStatus = existingDoc.parseStatusCode;
          Object.assign(existingDoc, updatedDoc);
          hasChanges = true;

          if (previousStatus !== updatedDoc.parseStatusCode && updatedDoc.parseStatusCode === 3) {
            this.fetchSliceCountForSingleDocument(updatedDoc.id);
            this.$notify({
              title: this.$t('knowledgeFileUpload.chunkingCompletedTitle'),
              message: this.$t('knowledgeFileUpload.chunkingCompletedDescription', {
                name: updatedDoc.name,
                count: updatedDoc.chunkCount || 0
              }),
              type: 'success',
              duration: 8000
            });
          } else if (previousStatus !== updatedDoc.parseStatusCode && updatedDoc.parseStatusCode === 4) {
            this.$notify({
              title: this.$t('knowledgeFileUpload.chunkingFailedTitle'),
              message: `${updatedDoc.name}：${updatedDoc.error || this.$t('knowledgeFileUpload.uploadFailed')}`,
              type: 'error',
              duration: 0
            });
          }
        }
      });

      if (hasChanges) {
        this.$forceUpdate();
      }
    },

    fetchSliceCountsForDocuments: async function () {
      if (!this.fileList || this.fileList.length === 0) {
        return;
      }

      for (const document of this.fileList) {
        this.fetchSliceCountForSingleDocument(document.id);
      }
    },

    fetchSliceCountForSingleDocument: function (documentId) {
      const document = this.fileList.find(doc => doc.id === documentId);
      if (!document) {
        return;
      }

      const params = {
        page: 1,
        page_size: 1
      };

      KnowledgeBaseAPI.listChunks(this.datasetId, documentId, params,
        ({ data }) => {
          if (data && data.code === 0) {
            const sliceCount = data.data.total || 0;
            this.$set(document, 'sliceCount', sliceCount);
            this.$forceUpdate();
          }
        },
        (err) => {
          console.warn(`获取文档 ${document.name} 切片数量失败:`, err);
        }
      );
    },

    smartRefreshSliceCount: function (documentId) {
      const document = this.fileList.find(doc => doc.id === documentId);
      if (!document) {
        return;
      }

      setTimeout(() => {
        this.fetchSliceCountForSingleDocument(documentId);
      }, 2000);
    },

    handleSearch: function () {
      this.currentPage = 1;
      this.fetchFileList();
    },
    headerCellClassName: function ({ row, column, rowIndex, columnIndex }) {
      if (columnIndex === 0) {
        return 'header-cell-first';
      }
      return 'header-cell';
    },
    showUploadDialog: function () {
      this.uploadDialogVisible = true;
    },

    handleUploadComplete: function () {
      this.fetchFileList();
    },

    handleRetryUpload: function () {
      this.uploadDialogVisible = true;
      this.$message.info(this.$t('knowledgeFileUpload.reuploadHint'));
    },

    handleParse: function (row) {
      this.$confirm(this.$t('knowledgeFileUpload.confirmParse'), this.$t('warning'), {
        confirmButtonText: this.$t('knowledgeFileUpload.confirm'),
        cancelButtonText: this.$t('knowledgeFileUpload.cancel'),
        type: 'warning'
      }).then(() => {
        KnowledgeBaseAPI.parseDocument(this.datasetId, row.id,
          ({ data }) => {
            if (data && data.code === 0) {
              this.$message.success('请求已提交，解析中');

              const document = this.fileList.find(doc => doc.id === row.id);
              if (document) {
                document.parseStatusCode = 1;
                this.$forceUpdate();
              }

              this.startStatusPolling();
              this.smartRefreshSliceCount(row.id);
            } else {
              this.$message.error(data?.msg || this.$t('knowledgeFileUpload.parseFailed'));
            }
          },
          (err) => {
            if (err && err.data) {
              this.$message.error(err.data.msg || err.msg || this.$t('knowledgeFileUpload.parseFailed'));
            } else {
              this.$message.error(this.$t('knowledgeFileUpload.parseFailed'));
            }
            console.error('解析文档失败:', err);
          }
        );
      }).catch(() => {
        this.$message.info(this.$t('knowledgeFileUpload.parseCancelled'));
      });
    },
    handleViewSlices: function (row) {
      this.$router.push({ name: 'KnowledgeContentManagement', query: {
        datasetId: this.datasetId, documentId: row.id, documentName: row.name,
        knowledgeBaseName: this.knowledgeBaseName, readOnly: this.readOnly ? '1' : '0'
      } });
    },
    handleDelete: function (row) {
      this.$confirm(this.$t('knowledgeFileUpload.confirmDelete'), this.$t('warning'), {
        confirmButtonText: this.$t('knowledgeFileUpload.confirm'),
        cancelButtonText: this.$t('knowledgeFileUpload.cancel'),
        type: 'warning'
      }).then(() => {
        KnowledgeBaseAPI.deleteDocument(this.datasetId, row.id,
          ({ data }) => {
            if (data && data.code === 0) {
              this.$message.success(this.$t('knowledgeFileUpload.deleteSuccess'));
              this.fetchFileList();
            } else {
              this.$message.error(data?.msg || this.$t('knowledgeFileUpload.deleteFailed'));
            }
          },
          (err) => {
            if (err && err.data) {
              this.$message.error(err.data.msg || err.msg || this.$t('knowledgeFileUpload.deleteFailed'));
            } else {
              this.$message.error(this.$t('knowledgeFileUpload.deleteFailed'));
            }
            console.error('删除文档失败:', err);
          }
        );
      }).catch(() => {
        this.$message.info(this.$t('knowledgeFileUpload.deleteCancelled'));
      });
    },
    handleSelectionChange: function (selection) {
      this.selectedFiles = selection;
    },
    canSelectDocument: function (document) {
      return document.parseStatusCode !== 1;
    },
    handleSelectAll: function () {
      if (this.isAllSelected) {
        this.$refs.fileTable.clearSelection();
        this.isAllSelected = false;
      } else {
        this.$refs.fileTable.clearSelection();
        this.fileList.forEach(row => {
          if (this.canSelectDocument(row)) {
            this.$refs.fileTable.toggleRowSelection(row, true);
          }
        });
        this.isAllSelected = true;
      }
    },
    handleBatchDelete: function () {
      if (this.selectedFiles.length === 0) {
        this.$message.warning(this.$t('knowledgeFileUpload.selectFilesFirst'));
        return;
      }

      this.$confirm(this.$t('knowledgeFileUpload.confirmBatchDelete', { count: this.selectedFiles.length }), this.$t('warning'), {
        confirmButtonText: this.$t('knowledgeFileUpload.confirm'),
        cancelButtonText: this.$t('knowledgeFileUpload.cancel'),
        type: 'warning'
      }).then(() => {
        const deletePromises = this.selectedFiles.map(file => {
          return new Promise((resolve, reject) => {
            KnowledgeBaseAPI.deleteDocument(this.datasetId, file.id,
              ({ data }) => {
                if (data && data.code === 0) {
                  resolve();
                } else {
                  reject(data?.msg || this.$t('knowledgeFileUpload.deleteFailed'));
                }
              },
              (err) => {
                if (err && err.data) {
                  reject(err.data.msg || err.msg || this.$t('knowledgeFileUpload.deleteFailed'));
                } else {
                  reject(this.$t('knowledgeFileUpload.deleteFailed'));
                }
                console.error('删除文档失败:', err);
              }
            );
          });
        });

        Promise.all(deletePromises)
          .then(() => {
            this.$message.success(this.$t('knowledgeFileUpload.batchDeleteSuccess', { count: this.selectedFiles.length }));
            this.selectedFiles = [];
            this.fetchFileList();
          })
          .catch((error) => {
            this.$message.error(error || this.$t('knowledgeFileUpload.batchDeleteFailed'));
          });
      }).catch(() => {
        this.$message.info(this.$t('knowledgeFileUpload.deleteCancelled'));
      });
    },
    getParseStatusType: function (parseStatusCode) {
      switch (parseStatusCode) {
        case 0: return 'info';
        case 1: return 'primary';
        case 2: return 'warning';
        case 3: return 'success';
        case 4: return 'danger';
        default: return 'info';
      }
    },
    getParseStatusText: function (parseStatusCode) {
      switch (parseStatusCode) {
        case 0: return this.$t('knowledgeFileUpload.statusNotStarted');
        case 1: return this.$t('knowledgeFileUpload.statusProcessing');
        case 2: return this.$t('knowledgeFileUpload.statusCancelled');
        case 3: return this.$t('knowledgeFileUpload.statusCompleted');
        case 4: return this.$t('knowledgeFileUpload.statusFailed');
        default: return this.$t('knowledgeFileUpload.statusNotStarted');
      }
    },
    getProgressPercent: function (progress) {
      const value = Number(progress);
      if (!Number.isFinite(value)) return 0;
      return Math.max(0, Math.min(100, Math.round(value * 100)));
    },
    getProcessingStage: function (document) {
      const percent = this.getProgressPercent(document.progress);
      if (percent <= 5) {
        return this.$t('knowledgeFileUpload.statusQueued');
      }
      if (document.sourceType !== 'local_knowledge') {
        return this.$t('knowledgeFileUpload.statusRemoteParsing', { percent });
      }
      return this.$t('knowledgeFileUpload.statusSemanticChunking', { percent });
    },
    goToPage: function (page) {
      if (page !== this.currentPage) {
        this.currentPage = page;
        this.fetchFileList();
      }
    },
    goFirst: function () {
      if (this.currentPage !== 1) {
        this.currentPage = 1;
        this.fetchFileList();
      }
    },
    goPrev: function () {
      if (this.currentPage > 1) {
        this.currentPage--;
        this.fetchFileList();
      }
    },
    goNext: function () {
      if (this.currentPage < this.pageCount) {
        this.currentPage++;
        this.fetchFileList();
      }
    },
    formatDate: function (dateString) {
      if (!dateString) return '';
      const date = new Date(dateString);
      return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
    },

    // 召回测试相关方法
    showRetrievalTestDialog: function () {
      this.retrievalTestForm = {
        question: ''
      };
      this.retrievalTestResult = null;
      this.retrievalTestDialogVisible = true;
    },

    runRetrievalTest: function () {
      if (!this.retrievalTestForm.question.trim()) {
        this.$message.error(this.$t('knowledgeFileUpload.testQuestionRequired'));
        return;
      }

      this.retrievalTestLoading = true;
      this.retrievalTestResult = null;

      const requestData = {
        question: this.retrievalTestForm.question.trim()
      };

      KnowledgeBaseAPI.retrievalTest(this.datasetId, requestData,
        ({ data }) => {
          this.retrievalTestLoading = false;
          if (data && data.code === 0) {
            this.retrievalTestResult = data.data || data;
            this.$message.success('召回测试完成');
          } else {
            this.$message.error(data?.msg || '召回测试失败');
          }
        },
        (err) => {
          this.retrievalTestLoading = false;
          if (err && err.data) {
            this.$message.error(err.data.msg || err.msg || '召回测试失败');
          } else {
            this.$message.error('召回测试失败');
          }
          console.error('召回测试失败:', err);
        }
      );
    },

    handleRetrievalTestDialogClose: function () {
      this.retrievalTestDialogVisible = false;
      this.retrievalTestResult = null;
    }
  }
};
</script>

<style lang="scss" scoped>
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
  overflow: hidden;
}

.main-wrapper {
  height: calc(100vh - 63px - 35px - 58px);
  margin: 0 22px;
  border-radius: 15px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  position: relative;
  background: rgba(237, 242, 255, 0.5);
  display: flex;
  flex-direction: column;
}

.operation-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
}

.left-operations {
  display: flex;
  align-items: center;
  gap: 16px;
}

.btn-back {
  font-size: 20px;
  font-weight: bold;
  color: #606266;
  padding: 8px 12px;
  border-radius: 4px;
  transition: all 0.3s ease;
}

.btn-back:hover {
  background-color: #f5f7fa;
  color: #409eff;
}

.knowledge-base-title {
  font-size: 24px;
  margin: 0;
  color: #303133;
  font-weight: 600;
}

.right-operations {
  display: flex;
  gap: 10px;
  margin-left: auto;
}

.search-input {
  width: 240px;
}

.btn-search {
  background: linear-gradient(135deg, #6b8cff, #a966ff);
  border: none;
  color: white;
}

.content-panel {
  flex: 1;
  display: flex;
  overflow: hidden;
  height: 100%;
  border-radius: 15px;
  background: transparent;
  border: 1px solid #fff;
}

.content-area {
  flex: 1;
  height: 100%;
  min-width: 600px;
  overflow: auto;
  background-color: white;
  display: flex;
  flex-direction: column;
}

.params-card {
  background: white;
  flex: 1;
  display: flex;
  flex-direction: column;
  border: none;
  box-shadow: none;
  overflow: hidden;

  ::v-deep .el-card__body {
    padding: 15px;
    display: flex;
    flex-direction: column;
    flex: 1;
    overflow: hidden;
  }
}

.table_bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: auto;
  padding-bottom: 10px;
  width: 100%;
}

.ctrl_btn {
  display: flex;
  gap: 8px;
  padding-left: 26px;

  .el-button {
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

    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 4px 8px rgba(0, 0, 0, 0.15);
    }
  }

  .el-button--primary {
    background: #5f70f3;
    color: white;
  }

  .el-button--danger {
    background: #fd5b63;
    color: white;
  }
}

.custom-pagination {
  display: flex;
  align-items: center;
  gap: 5px;

  .el-select {
    margin-right: 8px;
  }

  .pagination-btn:first-child,
  .pagination-btn:nth-child(2),
  .pagination-btn:nth-last-child(2),
  .pagination-btn:nth-child(3) {
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

  .pagination-btn:not(:first-child):not(:nth-child(3)):not(:nth-child(2)):not(:nth-last-child(2)) {
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
}

.total-text {
  margin-left: 10px;
  color: #606266;
  font-size: 14px;
}

.page-size-select {
  width: 100px;
  margin-right: 10px;

  :deep(.el-input__inner) {
    height: 32px;
    line-height: 32px;
    border-radius: 4px;
    border: 1px solid #e4e7ed;
    background: #dee7ff;
    color: #606266;
    font-size: 14px;
  }

  :deep(.el-input__suffix) {
    right: 6px;
    width: 15px;
    height: 20px;
    display: flex;
    justify-content: center;
    align-items: center;
    top: 6px;
    border-radius: 4px;
  }

  :deep(.el-input__suffix-inner) {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
  }

  :deep(.el-icon-arrow-up:before) {
    content: "";
    display: inline-block;
    border-left: 6px solid transparent;
    border-right: 6px solid transparent;
    border-top: 9px solid #606266;
    position: relative;
    transform: rotate(0deg);
    transition: transform 0.3s;
  }
}

.document-status-cell {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 200px;
  margin: 0 auto;

  .status-detail {
    color: #909399;
    font-size: 12px;
    line-height: 16px;
  }

  .status-error {
    color: #f56c6c;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    cursor: help;
  }
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

:deep(.el-table .el-button--text) {
  color: #7079aa !important;
}

:deep(.el-table .el-button--text:hover) {
  color: #5a64b5 !important;
}

:deep(.el-checkbox__inner) {
  background-color: #ffffff !important;
  border-color: #cccccc !important;
}

:deep(.el-checkbox__inner:hover) {
  border-color: #cccccc !important;
}

:deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background-color: #5f70f3 !important;
  border-color: #5f70f3 !important;
}

:deep(.el-loading-mask) {
  background-color: rgba(255, 255, 255, 0.6) !important;
  backdrop-filter: blur(2px);
}

:deep(.el-loading-spinner .path) {
  stroke: #6b8cff;
}

:deep(.el-table__empty-block) {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  min-height: 300px;
}

.el-table {
  --table-max-height: calc(100vh - 40vh);
  max-height: var(--table-max-height);
  flex: 1;

  .el-table__body-wrapper {
    max-height: calc(var(--table-max-height) - 40px);
  }
}

.slice-dialog-content {
  max-height: 70vh;
  overflow-y: auto;
}

/* 召回测试弹窗样式 */
.retrieval-test-dialog {
  ::v-deep .el-dialog__wrapper {
    display: block !important;
  }

  ::v-deep .el-dialog {
    position: absolute !important;
    top: 50% !important;
    left: 50% !important;
    transform: translate(-50%, -50%) !important;
    margin: 0 !important;
    width: 1200px !important;
    height: 90vh !important;
    max-height: 90vh !important;
    min-height: 90vh !important;
  }

  ::v-deep .el-dialog__body {
    height: calc(100% - 90px) !important;
    max-height: calc(100% - 90px) !important;
    overflow: hidden;
    padding: 15px 25px;
    display: flex;
    flex-direction: column;
  }
}

.retrieval-test-form {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.retrieval-test-result {
  margin-top: 20px;
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;

  h4 {
    margin: 0 0 12px 0;
    font-size: 16px;
    font-weight: 600;
    color: #303133;
    flex-shrink: 0;
  }

  :deep(.el-card) {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;

    .el-card__body {
      flex: 1;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      padding: 0;
    }
  }
}

.result-chunk-container {
  flex: 1;
  overflow-y: auto;
  max-height: 100%;
  padding: 16px;

  &::-webkit-scrollbar {
    width: 6px;
    height: 6px;
  }

  &::-webkit-scrollbar-track {
    background: #fafafa;
    border-radius: 3px;
  }

  &::-webkit-scrollbar-thumb {
    background: #e0e0e0;
    border-radius: 3px;
    border: 1px solid #f0f0f0;
  }

  &::-webkit-scrollbar-thumb:hover {
    background: #d0d0d0;
  }
}

.result-chunk {
  background-color: #f8f9fa;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;

  &:last-child {
    margin-bottom: 0;
  }

  p {
    margin: 0 0 8px 0;
    font-size: 14px;
    line-height: 1.5;

    &:last-child {
      margin-bottom: 0;
    }

    strong {
      color: #303133;
      font-weight: 600;
    }
  }

  .similarity-scores {
    display: flex;
    gap: 16px;
    margin-bottom: 12px;

    .score-item {
      display: flex;
      flex-direction: column;

      .score-label {
        font-size: 12px;
        color: #909399;
        margin-bottom: 4px;
      }

      .score-value {
        font-size: 14px;
        font-weight: 600;
        color: #409eff;
      }
    }
  }

  .chunk-content {
    background-color: white;
    border: 1px solid #f0f0f0;
    border-radius: 4px;
    padding: 12px;
    margin-top: 8px;
    max-height: 120px;
    overflow-y: auto;

    &::-webkit-scrollbar {
      width: 4px;
    }

    &::-webkit-scrollbar-track {
      background: #fafafa;
    }

    &::-webkit-scrollbar-thumb {
      background: #e0e0e0;
      border-radius: 2px;
    }

    &::-webkit-scrollbar-thumb:hover {
      background: #d0d0d0;
    }
  }

  :deep(.el-divider) {
    margin: 12px 0;
  }
}

:deep(.el-dialog) {
  border-radius: 16px !important;
  overflow: hidden;
}

@media (min-width: 1144px) {
  .table_bottom {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: auto;
  }

  :deep(.transparent-table) {
    .el-table__body tr {
      td {
        padding-top: 16px;
        padding-bottom: 16px;
      }

      &+tr {
        margin-top: 10px;
      }
    }
  }
}
</style>
