<template>
  <div class="welcome">
    <HeaderBar />
    <ResourceCenterNav />

    <div class="operation-bar">
      <div class="scope-switch" aria-label="知识库范围">
        <el-radio-group v-model="activeScope" size="small" @change="handleScopeChange">
          <el-radio-button label="MY">我的知识库</el-radio-button>
          <el-radio-button label="OFFICIAL">官方知识库</el-radio-button>
        </el-radio-group>
        <span class="page-subtitle">{{ activeScope === 'MY' ? '当前工作空间拥有的资料' : '所有用户可使用的官方资料' }}</span>
      </div>
      <div class="right-operations">
        <el-input :placeholder="$t('knowledgeBaseManagement.searchPlaceholder')" v-model="searchName" class="search-input"
          @keyup.enter.native="handleSearch" clearable />
        <el-button icon="el-icon-search" @click="handleSearch">{{ $t('knowledgeBaseManagement.search') }}</el-button>
        <el-button v-if="activeScope === 'MY'" type="primary" icon="el-icon-plus" @click="showAddDialog">{{ $t('knowledgeBaseManagement.add') }}</el-button>
      </div>
    </div>

    <div class="main-wrapper">
      <div class="content-panel">
        <div class="content-area">
          <el-card class="params-card" shadow="never">
            <div class="table-toolbar">
              <div class="toolbar-summary">
                <strong>{{ total }}</strong>
                <span>个知识库</span>
              </div>
              <div v-if="activeScope === 'MY'" class="toolbar-actions">
                <el-button size="mini" @click="toggleSelectAll">
                  {{ isAllSelected ? $t('knowledgeBaseManagement.cancelSelectAll') : $t('knowledgeBaseManagement.selectAll') }}
                </el-button>
                <el-button size="mini" type="danger" icon="el-icon-delete" @click="deleteSelectedKnowledgeBase" :disabled="selectedKnowledgeBase.length === 0">
                  {{ $t('knowledgeBaseManagement.delete') }}
                </el-button>
              </div>
            </div>
            <div>
              <el-table ref="paramsTable" :data="knowledgeBaseList" class="transparent-table" v-loading="loading"
                :element-loading-text="$t('common.loading')" element-loading-spinner="el-icon-loading"
                element-loading-background="rgba(255, 255, 255, 0.7)"
                :header-cell-class-name="headerCellClassName" @selection-change="handleSelectionChange">
                <el-table-column v-if="activeScope === 'MY'" type="selection" width="55" align="center" />
                <el-table-column :label="$t('knowledgeBaseManagement.name')" prop="name" align="center">
                  <template slot-scope="scope">
                    <span class="knowledge-base-name">{{ scope.row.name }}</span>
                    <el-tag v-if="scope.row.scope === 'OFFICIAL'" size="mini" type="warning">官方</el-tag>
                  </template>
                </el-table-column>
                <el-table-column :label="$t('knowledgeBaseManagement.description')" prop="description" align="center" show-overflow-tooltip>
                  <template slot-scope="scope">
                    <span>{{ scope.row.description || '-' }}</span>
                  </template>
                </el-table-column>
                <el-table-column :label="$t('knowledgeBaseManagement.documentCount')" align="center">
                  <template slot-scope="scope">
                    <span>{{ scope.row.documentCount || 0 }}</span>
                  </template>
                </el-table-column>
                <el-table-column :label="$t('knowledgeBaseManagement.status')" align="center">
                  <template slot-scope="scope">
                    <el-switch
                      v-model="scope.row.status"
                      :active-value="1"
                      :inactive-value="0"
                      active-color="#13ce66"
                      inactive-color="#909399"
                      :disabled="!scope.row.canEdit"
                      @change="handleStatusChange(scope.row)"
                    ></el-switch>
                  </template>
                </el-table-column>
                <el-table-column :label="$t('knowledgeBaseManagement.createdAt')" prop="createdAt" align="center">
                  <template slot-scope="scope">
                    <span>{{ formatDate(scope.row.createdAt) }}</span>
                  </template>
                </el-table-column>
                <el-table-column :label="$t('knowledgeBaseManagement.operation')" align="center">
                  <template slot-scope="scope">
                    <el-button size="mini" type="text" icon="el-icon-document" @click="showViewDialog(scope.row)">
                      {{ $t('knowledgeBaseManagement.view') }}
                    </el-button>
                    <el-button v-if="scope.row.canEdit" size="mini" type="text" icon="el-icon-edit" @click="showEditDialog(scope.row)">
                      {{ $t('knowledgeBaseManagement.edit') }}
                    </el-button>
                    <el-button v-if="scope.row.canEdit" size="mini" type="text" icon="el-icon-delete" class="danger-action" @click="deleteSingleKnowledgeBase(scope.row)">
                      {{ $t('knowledgeBaseManagement.delete') }}
                    </el-button>
                    <el-button v-if="activeScope === 'MY' && scope.row.canPublishOfficial" size="mini" type="text"
                      icon="el-icon-medal" @click="publishOfficial(scope.row)">设为官方</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>

            <div class="table_bottom">
              <el-pagination
                background
                layout="total, sizes, prev, pager, next, jumper"
                :current-page="currentPage"
                :page-size="pageSize"
                :page-sizes="pageSizeOptions"
                :total="total"
                @size-change="handlePageSizeChange"
                @current-change="handleCurrentPageChange"
              />
            </div>
          </el-card>
        </div>
      </div>
    </div>

    <!-- 新增/编辑知识库对话框 -->
    <knowledge-base-dialog ref="knowledgeBaseDialog" :title="dialogTitle" :visible.sync="dialogVisible" :form="knowledgeBaseForm"
      @submit="handleSubmit" @cancel="dialogVisible = false" />

    <el-footer>
      <version-footer />
    </el-footer>
  </div>
</template>

<script>
import Api from "@/apis/api";
import HeaderBar from "@/components/HeaderBar.vue";
import ResourceCenterNav from "@/components/resource/ResourceCenterNav.vue";
import VersionFooter from "@/components/VersionFooter.vue";
import KnowledgeBaseDialog from "@/components/KnowledgeBaseDialog.vue";

export default {
  components: { HeaderBar, ResourceCenterNav, VersionFooter, KnowledgeBaseDialog },
  data() {
    return {
      searchName: "",
      activeScope: "MY",
      knowledgeBaseList: [],
      currentPage: 1,
      loading: false,
      pageSize: 10,
      pageSizeOptions: [10, 20, 50, 100],
      total: 0,
      dialogVisible: false,
      dialogTitle: "",
      selectedKnowledgeBase: [],
      isAllSelected: false,
      knowledgeBaseForm: {
        id: null,
        datasetId: null,
        name: "",
        description: "",
        status: 1
      }
    };
  },
  created() {
    this.fetchKnowledgeBaseList();
  },
  methods: {
    handlePageSizeChange: function(val) {
      this.pageSize = val;
      this.currentPage = 1;
      this.fetchKnowledgeBaseList();
    },
    fetchKnowledgeBaseList: function() {
      this.loading = true;
      Api.knowledgeBase.getKnowledgeBaseList(
        {
          page: this.currentPage,
          page_size: this.pageSize,
          name: this.searchName,
          scope: this.activeScope,
        },
        (res) => {
          this.loading = false;
          if (res.data && res.data.code === 0) {
            const pageData = res.data.data || {};
            this.knowledgeBaseList = pageData.list || [];
            this.total = pageData.total || 0;
          } else {
            this.$message.error({
              message: res.data?.msg || this.$t('knowledgeBaseManagement.getKnowledgeBaseListFailed'),
              showClose: true
            });
          }
        },
        () => {
          this.loading = false;
          this.$message.error(this.$t('knowledgeBaseManagement.getKnowledgeBaseListFailed'));
        }
      );
    },
    handleSearch: function() {
      this.currentPage = 1;
      this.fetchKnowledgeBaseList();
    },
    handleScopeChange: function() {
      this.currentPage = 1;
      this.selectedKnowledgeBase = [];
      this.isAllSelected = false;
      this.fetchKnowledgeBaseList();
    },
    handleSelectionChange: function(val) {
      this.selectedKnowledgeBase = val;
    },
    toggleSelectAll: function() {
      if (this.isAllSelected) {
        // 取消全选
        this.$refs.paramsTable.clearSelection();
        this.isAllSelected = false;
      } else {
        // 全选
        this.knowledgeBaseList.forEach(row => {
          this.$refs.paramsTable.toggleRowSelection(row, true);
        });
        this.isAllSelected = true;
      }
    },
    headerCellClassName: function({ row, column, rowIndex, columnIndex }) {
      if (columnIndex === 0) {
        return 'header-cell-first';
      }
      return 'header-cell';
    },
    showAddDialog: function() {
      this.dialogTitle = this.$t('knowledgeBaseManagement.addKnowledgeBase');
      this.knowledgeBaseForm = {
        id: null,
        datasetId: null,
        name: "",
        description: "",
        status: 1,
        parserConfig: ""
      };
      this.dialogVisible = true;
    },
    showViewDialog: function(row) {
      // 跳转到上传文件页面，传递知识库ID和名称
      this.$router.push({
        path: '/knowledge-file-upload',
        query: {
          datasetId: row.datasetId,
          knowledgeBaseName: row.name,
          readOnly: row.canEdit ? '0' : '1'
        }
      });
    },
    showEditDialog: function(row) {
      this.dialogTitle = this.$t('knowledgeBaseManagement.editKnowledgeBase');
      this.knowledgeBaseForm = {
        id: row.id,
        datasetId: row.datasetId,
        name: row.name,
        description: row.description || "",
        status: row.status,
        ragModelId: row.ragModelId || null,
        parserConfig: row.parserConfig || ""
      };
      this.dialogVisible = true;
    },
    handleSubmit: function(form) {
      if (form.id) {
        Api.knowledgeBase.updateKnowledgeBase(form.datasetId, form, (res) => {
          if (res.data && res.data.code === 0) {
            this.dialogVisible = false;
            this.fetchKnowledgeBaseList();
            this.$message.success(this.$t('knowledgeBaseManagement.updateSuccess'));
          } else {
            this.$message.error(res.data?.msg || this.$t('knowledgeBaseManagement.updateFailed'));
          }
        }, (err) => {
          if (err && err.data) {
            this.$message.error(err.data.msg || err.msg || this.$t('knowledgeBaseManagement.updateFailed'));
          } else {
            this.$message.error(this.$t('knowledgeBaseManagement.updateFailed'));
          }
        });
      } else {
        const createData = {
          name: form.name,
          description: form.description,
          status: form.status,
          ragModelId: form.ragModelId,
          parserConfig: form.parserConfig
        };
        Api.knowledgeBase.createKnowledgeBase(createData, (res) => {
          if (res.data && res.data.code === 0) {
            this.dialogVisible = false;
            this.fetchKnowledgeBaseList();
            this.$message.success(this.$t('knowledgeBaseManagement.addSuccess'));
          } else {
            this.$message.error(res.data?.msg || this.$t('knowledgeBaseManagement.addFailed'));
          }
        }, (err) => {
          if (err && err.data) {
            this.$message.error(err.data.msg || err.msg || this.$t('knowledgeBaseManagement.addFailed'));
          } else {
            this.$message.error(this.$t('knowledgeBaseManagement.addFailed'));
          }
        });
      }
    },
    deleteSelectedKnowledgeBase: function() {
      if (this.selectedKnowledgeBase.length === 0) {
        this.$message.warning(this.$t('knowledgeBaseManagement.selectKnowledgeBaseFirst'));
        return;
      }

      this.$confirm(
        this.$t('knowledgeBaseManagement.confirmBatchDelete', { count: this.selectedKnowledgeBase.length }),
        this.$t('common.warning'),
        {
          confirmButtonText: this.$t('common.confirm'),
          cancelButtonText: this.$t('common.cancel'),
          type: 'warning'
        }).then(() => {
        const ids = this.selectedKnowledgeBase.map(item => item.datasetId).join(',');
        Api.knowledgeBase.deleteKnowledgeBases(ids, (res) => {
          if (res.data && res.data.code === 0) {
            this.fetchKnowledgeBaseList();
            this.$message.success(this.$t('knowledgeBaseManagement.batchDeleteSuccess', { count: this.selectedKnowledgeBase.length }));
          } else {
            this.$message.error(res.data?.msg || this.$t('knowledgeBaseManagement.deleteFailed'));
          }
        }, (err) => {
          if (err && err.data) {
            this.$message.error(err.data.msg || err.msg || this.$t('knowledgeBaseManagement.deleteFailed'));
          } else {
            this.$message.error(this.$t('knowledgeBaseManagement.deleteFailed'));
          }
        });
      }).catch(() => {
        this.$message({
          type: 'info',
          message: this.$t('knowledgeBaseManagement.operationCancelled'),
          duration: 1000
        });
      });
    },
    deleteSingleKnowledgeBase: function(row) {
      this.$confirm(
        this.$t('knowledgeBaseManagement.confirmBatchDelete', { count: 1 }),
        this.$t('common.warning'),
        {
          confirmButtonText: this.$t('common.confirm'),
          cancelButtonText: this.$t('common.cancel'),
          type: 'warning'
        }).then(() => {
        Api.knowledgeBase.deleteKnowledgeBase(row.datasetId, (res) => {
          if (res.data && res.data.code === 0) {
            this.fetchKnowledgeBaseList();
            this.$message.success(this.$t('knowledgeBaseManagement.batchDeleteSuccess', { count: 1 }));
          } else {
            this.$message.error(res.data?.msg || this.$t('knowledgeBaseManagement.deleteFailed'));
          }
        }, (err) => {
          if (err && err.data) {
            this.$message.error(err.data.msg || err.msg || this.$t('knowledgeBaseManagement.deleteFailed'));
          } else {
            this.$message.error(this.$t('knowledgeBaseManagement.deleteFailed'));
          }
        });
      }).catch(() => {
        this.$message({
          type: 'info',
          message: this.$t('knowledgeBaseManagement.operationCancelled'),
          duration: 1000
        });
      });
    },
    handleStatusChange: function(row) {
      const updateForm = {
        id: row.id,
        datasetId: row.datasetId,
        name: row.name,
        description: row.description,
        status: row.status
      };
      Api.knowledgeBase.updateKnowledgeBase(row.datasetId, updateForm, (res) => {
        if (res.data && res.data.code !== 0) {
          this.fetchKnowledgeBaseList();
          this.$message.error(res.data?.msg || this.$t('knowledgeBaseManagement.updateFailed'));
        } else {
          this.$message.success(this.$t('knowledgeBaseManagement.updateSuccess'));
        }
      }, () => {
        this.fetchKnowledgeBaseList();
        this.$message.error(this.$t('knowledgeBaseManagement.updateFailed'));
      });
    },
    publishOfficial: function(row) {
      this.$confirm(
        `确认将“${row.name}”发布为所有用户可见的官方知识库吗？发布后只有超级管理员团队可以维护。`,
        '发布官方知识库',
        { type: 'warning' }
      ).then(() => {
        Api.knowledgeBase.updatePublicationScope(row.datasetId, 'OFFICIAL', (res) => {
          if (res.data && res.data.code === 0) {
            this.fetchKnowledgeBaseList();
            this.$message.success('已发布为官方知识库');
          } else {
            this.$message.error(res.data?.msg || '发布失败');
          }
        }, (err) => this.$message.error(err?.data?.msg || '发布失败'));
      }).catch(() => {});
    },
    handleCurrentPageChange: function(page) {
      this.currentPage = page;
      this.fetchKnowledgeBaseList();
    },
    formatDate: function(dateString) {
      if (!dateString) return '';
      const date = new Date(dateString);
      return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
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
    background: #f5f7fb;
    overflow: hidden;
}

.main-wrapper {
    flex: 1;
    min-height: 0;
    height: auto;
    margin: 0 22px;
    border-radius: 8px;
    position: relative;
    background: #fff;
    display: flex;
    flex-direction: column;
    border: 1px solid #e4e8f1;
}

.operation-bar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 16px 24px;
}

.scope-switch {
    display: flex;
    align-items: center;
    gap: 14px;
}

.page-subtitle {
    margin: 0;
    font-size: 13px;
    color: #737b91;
}

.right-operations {
    display: flex;
    gap: 10px;
    margin-left: auto;
}

.search-input {
    width: 240px;
}

.content-panel {
    flex: 1;
    display: flex;
    overflow: hidden;
    height: 100%;
    border-radius: 8px;
    background: transparent;
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

.table-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 12px;
    margin-bottom: 12px;
}

.toolbar-summary {
    display: flex;
    align-items: baseline;
    gap: 6px;
    color: #687186;
    font-size: 13px;
}

.toolbar-summary strong {
    color: #2f3548;
    font-size: 20px;
}

.toolbar-actions {
    display: flex;
    gap: 8px;
}

.table_bottom {
    display: flex;
    justify-content: flex-end;
    align-items: center;
    margin-top: auto;
    padding-top: 12px;
    width: 100%;
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
        background: #f8fafc !important;
        color: #2f3548;
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
    color: #3f6fd8 !important;
}

:deep(.el-table .el-button--text:hover) {
    color: #2f58b8 !important;
}

:deep(.el-table .danger-action.el-button--text) {
    color: #c53030 !important;
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

@media (min-width: 1144px) {
    .table_bottom {
        display: flex;
        justify-content: flex-end;
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
