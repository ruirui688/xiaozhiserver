<template>
  <el-dialog :title="`${$t('knowledgeFileUpload.viewSlices')} - ${documentName}`"
    :visible.sync="internalVisible" width="1200px" class="slice-dialog" @close="handleClose">
    <div class="slice-management">
      <!-- 切片列表 -->
      <div class="slice-list-section">
        <!-- 切片内容卡片式布局 -->
        <div v-loading="sliceLoading" class="slice-content-container">
          <div v-if="sliceList.length > 0" class="slice-cards-container">
            <div v-for="(slice, index) in sliceList" :key="index" class="slice-card">
              <div class="slice-header-info">
                <p><strong>{{ $t('knowledgeFileUpload.slice') }} {{ (sliceCurrentPage - 1) * slicePageSize + index + 1
                    }}</strong></p>
              </div>
              <div class="slice-card-content">
                <div class="content-text">{{ slice.content }}</div>
              </div>
            </div>
          </div>
          <div v-else class="no-slice-data">
            <el-alert type="info" :title="$t('knowledgeFileUpload.noSliceData')"></el-alert>
          </div>
        </div>

        <!-- 切片分页 -->
        <div class="slice-pagination" style="margin-top: 20px; text-align: right;">
          <div class="custom-pagination">
            <!-- 条/页选择器 -->
            <el-select v-model="slicePageSize" @change="handleSliceSizeChange" class="page-size-select"
              :popper-append-to-body="false">
              <el-option v-for="item in pageSizeOptions" :key="item"
                :label="`${item}${$t('knowledgeFileUpload.itemsPerPage')}`" :value="item">
              </el-option>
            </el-select>

            <!-- 首页按钮 -->
            <button class="pagination-btn" :disabled="sliceCurrentPage === 1" @click="goToSliceFirstPage">
              {{ $t('knowledgeFileUpload.firstPage') }}
            </button>

            <!-- 上一页按钮 -->
            <button class="pagination-btn" :disabled="sliceCurrentPage === 1" @click="goToSlicePrevPage">
              {{ $t('knowledgeFileUpload.prevPage') }}
            </button>

            <!-- 页码按钮 -->
            <button v-for="page in sliceVisiblePages" :key="page" class="pagination-btn"
              :class="{ active: page === sliceCurrentPage }" @click="goToSlicePage(page)">
              {{ page }}
            </button>

            <!-- 下一页按钮 -->
            <button class="pagination-btn" :disabled="sliceCurrentPage === slicePageCount" @click="goToSliceNextPage">
              {{ $t('knowledgeFileUpload.nextPage') }}
            </button>

            <!-- 总记录数 -->
            <span class="total-text">{{ $t('knowledgeFileUpload.totalRecords', { total: sliceTotal }) }}</span>
          </div>
        </div>
      </div>
    </div>
  </el-dialog>
</template>

<script>
import KnowledgeBaseAPI from "@/apis/module/knowledgeBase";

export default {
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    datasetId: {
      type: String,
      default: ''
    },
    documentId: {
      type: String,
      default: ''
    },
    documentName: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      internalVisible: false,
      sliceList: [],
      sliceLoading: false,
      sliceCurrentPage: 1,
      slicePageSize: 10,
      sliceTotal: 0,
      sliceSearchKeyword: '',
      pageSizeOptions: [10, 20, 50, 100]
    };
  },
  computed: {
    slicePageCount() {
      return Math.ceil(this.sliceTotal / this.slicePageSize);
    },
    sliceVisiblePages() {
      const pages = [];
      const maxVisible = 3;
      let start = Math.max(1, this.sliceCurrentPage - 1);
      let end = Math.min(this.slicePageCount, start + maxVisible - 1);

      if (end - start + 1 < maxVisible) {
        start = Math.max(1, end - maxVisible + 1);
      }

      for (let i = start; i <= end; i++) {
        pages.push(i);
      }
      return pages;
    }
  },
  watch: {
    visible(val) {
      this.internalVisible = val;
      if (val && this.documentId) {
        this.sliceCurrentPage = 1;
        this.fetchSlices();
      }
    },
    internalVisible(val) {
      if (!val) {
        this.$emit('close');
      }
    },
    documentId(val) {
      if (val && this.internalVisible) {
        this.sliceCurrentPage = 1;
        this.fetchSlices();
      }
    }
  },
  methods: {
    fetchSlices() {
      if (!this.documentId) return;

      this.sliceLoading = true;

      const params = {
        page: this.sliceCurrentPage,
        page_size: this.slicePageSize
      };

      if (this.sliceSearchKeyword) {
        params.keywords = this.sliceSearchKeyword;
      }

      KnowledgeBaseAPI.listChunks(this.datasetId, this.documentId, params,
        ({ data }) => {
          this.sliceLoading = false;
          if (data && data.code === 0) {
            this.parseSliceData(data.data);
          } else {
            this.$message.error(data?.msg || '获取切片列表失败');
            this.sliceList = [];
            this.sliceTotal = 0;
          }
        },
        (err) => {
          this.sliceLoading = false;
          if (err && err.data) {
            this.$message.error(err.data.msg || err.msg || '获取切片列表失败');
          } else {
            this.$message.error('获取切片列表失败');
          }
          console.error('获取切片列表失败:', err);
          this.sliceList = [];
          this.sliceTotal = 0;
        }
      );
    },

    parseSliceData(data) {
      try {
        if (data && data.list) {
          this.sliceList = data.list;
          this.sliceTotal = data.total || data.list.length;
        } else if (data && data.chunks && Array.isArray(data.chunks)) {
          this.sliceList = data.chunks;
          this.sliceTotal = data.total || data.chunks.length;
        } else if (data && Array.isArray(data)) {
          this.sliceList = data;
          this.sliceTotal = data.length;
        } else {
          this.sliceList = [];
          this.sliceTotal = 0;
        }
      } catch (error) {
        console.error('解析切片数据失败:', error);
        this.sliceList = [];
        this.sliceTotal = 0;
      }
    },

    handleSliceSizeChange(pageSize) {
      this.slicePageSize = pageSize;
      this.sliceCurrentPage = 1;
      this.fetchSlices();
    },

    goToSliceFirstPage() {
      if (this.sliceCurrentPage !== 1) {
        this.sliceCurrentPage = 1;
        this.fetchSlices();
      }
    },

    goToSlicePrevPage() {
      if (this.sliceCurrentPage > 1) {
        this.sliceCurrentPage--;
        this.fetchSlices();
      }
    },

    goToSlicePage(page) {
      if (page !== this.sliceCurrentPage) {
        this.sliceCurrentPage = page;
        this.fetchSlices();
      }
    },

    goToSliceNextPage() {
      if (this.sliceCurrentPage < this.slicePageCount) {
        this.sliceCurrentPage++;
        this.fetchSlices();
      }
    },

    handleClose() {
      this.sliceList = [];
      this.sliceTotal = 0;
      this.sliceCurrentPage = 1;
    }
  }
};
</script>

<style lang="scss" scoped>
/* 切片管理弹窗固定容器大小 */
.slice-dialog {
  ::v-deep .el-dialog__wrapper {
    display: block !important;
  }

  ::v-deep .el-dialog::-webkit-scrollbar {
    width: 8px;
    height: 8px;
  }

  ::v-deep .el-dialog::-webkit-scrollbar-track {
    background: #f8f9fa;
    border-radius: 4px;
  }

  ::v-deep .el-dialog::-webkit-scrollbar-thumb {
    background: #f0f0f0;
    border-radius: 4px;
    border: 1px solid #e8e8e8;
  }

  ::v-deep .el-dialog::-webkit-scrollbar-thumb:hover {
    background: #e8e8e8;
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

  :deep(.el-dialog__body) {
    height: calc(100% - 90px) !important;
    max-height: calc(100% - 90px) !important;
    overflow: hidden;
    padding: 15px 20px;
  }

  .slice-management {
    height: 100%;
    display: flex;
    flex-direction: column;
  }

  .slice-list-section {
    flex: 1;
    display: flex;
    flex-direction: column;
    height: 100%;
    overflow: hidden;
  }

  .slice-content-container {
    flex: 1;
    height: 100%;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .slice-cards-container {
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

  .slice-card {
    background-color: #f8f9fa;
    border: 1px solid #e4e7ed;
    border-radius: 8px;
    padding: 16px;
    margin-bottom: 16px;

    &:last-child {
      margin-bottom: 0;
    }

    .slice-header-info {
      margin-bottom: 12px;

      p {
        margin: 0;
        font-size: 14px;
        line-height: 1.5;

        strong {
          color: #303133;
          font-weight: 600;
        }
      }
    }

    .slice-card-content {
      background-color: white;
      border: 1px solid #f0f0f0;
      border-radius: 4px;
      padding: 12px;
      max-height: 280px;
      min-height: 120px;
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

      .content-text {
        font-size: 14px;
        line-height: 1.6;
        text-align: left;
        color: #333;
        word-wrap: break-word;
        white-space: pre-wrap;
        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
        white-space: pre-wrap;
        word-break: break-word;
        overflow-wrap: break-word;

        p {
          margin: 0 0 12px 0;
          line-height: 1.6;

          &:last-child {
            margin-bottom: 0;
          }
        }

        ul, ol {
          margin: 8px 0;
          padding-left: 24px;

          li {
            margin-bottom: 4px;
            line-height: 1.5;
          }
        }

        h1, h2, h3, h4, h5, h6 {
          margin: 16px 0 8px 0;
          font-weight: 600;
          line-height: 1.4;
        }

        h1 { font-size: 18px; }
        h2 { font-size: 16px; }
        h3 { font-size: 15px; }
        h4, h5, h6 { font-size: 14px; }

        strong, b {
          font-weight: 600;
          color: #1a1a1a;
        }

        code {
          background-color: #f5f5f5;
          padding: 2px 4px;
          border-radius: 3px;
          font-family: 'Courier New', monospace;
          font-size: 13px;
        }

        blockquote {
          margin: 12px 0;
          padding: 8px 12px;
          border-left: 4px solid #e0e0e0;
          background-color: #f9f9f9;
          font-style: italic;
          color: #666;
        }
      }
    }
  }

  .no-slice-data {
    flex: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
  }
}

.slice-pagination {
  text-align: right;
  margin-top: 20px;

  .custom-pagination {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    gap: 5px;
  }
}

.pagination-btn {
  min-width: 28px;
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

  &.active {
    background: #5f70f3 !important;
    color: #ffffff !important;
    border-color: #5f70f3 !important;

    &:hover {
      background: #6d7cf5 !important;
    }
  }
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

.total-text {
  margin-left: 10px;
  color: #606266;
  font-size: 14px;
}
</style>
