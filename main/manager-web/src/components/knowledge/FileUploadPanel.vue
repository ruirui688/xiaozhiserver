<template>
  <el-dialog :title="$t('knowledgeFileUpload.uploadDocument')" :visible.sync="internalVisible" width="800px"
    @close="handleClose">
    <el-alert :title="$t('knowledgeFileUpload.semanticChunkingNoticeTitle')"
      :description="$t('knowledgeFileUpload.semanticChunkingNoticeDescription')" type="info" show-icon
      :closable="false" class="upload-state-alert"></el-alert>

    <el-alert v-if="submitResult" :title="submitResult.title" :description="submitResult.description"
      :type="submitResult.type" show-icon :closable="false" class="upload-state-alert"></el-alert>

    <el-upload class="document-uploader" drag :action="uploadUrl" :auto-upload="false" :on-change="handleFileChange"
      :multiple="true" :show-file-list="false" :disabled="uploading"
      accept=".doc,.docx,.pdf,.txt,.md,.mdx,.csv,.xls,.xlsx,.ppt,.pptx,.json,.jsonl,.qa">
      <i class="el-icon-upload"></i>
      <div class="el-upload__text">{{ $t('knowledgeFileUpload.dragOrClick') }}</div>
      <div class="el-upload__tip">{{ $t('knowledgeFileUpload.uploadTip') }}</div>
    </el-upload>

    <!-- 已选择文件列表 -->
    <div class="selected-files-section" v-if="selectedFilesList.length > 0">
      <h4>{{ $t('knowledgeFileUpload.selectedFiles') }} ({{ selectedFilesList.length }})</h4>
      <div class="selected-files-list">
        <div v-for="(file, index) in selectedFilesList" :key="index" class="selected-file-item">
          <div class="file-info">
            <i class="el-icon-document"></i>
            <span class="file-name">{{ file.name }}</span>
            <span class="file-size">{{ formatFileSize(file.size) }}</span>
          </div>
          <el-button type="text" class="remove-btn" :disabled="uploading" @click="removeSelectedFile(index)">
            <i class="el-icon-close"></i>
          </el-button>
        </div>
      </div>
    </div>

    <div slot="footer" class="dialog-footer">
      <el-button @click="closeDialog">{{ $t('knowledgeFileUpload.close') }}</el-button>
      <el-button type="primary" @click="handleBatchUploadSubmit" :loading="uploading"
        :disabled="selectedFilesList.length === 0 || uploading">
        {{ uploading ? $t('knowledgeFileUpload.submitting') : $t('knowledgeFileUpload.submitForChunking') }}
        {{ selectedFilesList.length > 0 ?
          `(${selectedFilesList.length}${$t('knowledgeFileUpload.itemsPerPage').replace('条/页', '个文件')})` : '' }}
      </el-button>
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
    uploadUrl: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      internalVisible: false,
      selectedFilesList: [],
      uploading: false,
      submitResult: null
    };
  },
  watch: {
    visible(val) {
      this.internalVisible = val;
      if (val) {
        this.selectedFilesList = [];
        this.uploading = false;
        this.submitResult = null;
      }
    },
    internalVisible(val) {
      if (!val) {
        this.$emit('close');
      }
    }
  },
  methods: {
    handleFileChange(file, fileList) {
      if (!file || !file.raw) return;

      // 文件上传前的验证
      const isLt10M = file.size / 1024 / 1024 < 10;
      if (!isLt10M) {
        this.$message.error('文件大小不能超过10MB!');
        return;
      }

      // 添加到已选择文件列表
      this.submitResult = null;
      this.selectedFilesList.push({
        name: file.name,
        size: file.size,
        raw: file.raw
      });
    },

    removeSelectedFile(index) {
      this.selectedFilesList.splice(index, 1);
    },

    formatFileSize(bytes) {
      if (bytes === 0) return '0 B';
      const k = 1024;
      const sizes = ['B', 'KB', 'MB', 'GB'];
      const i = Math.floor(Math.log(bytes) / Math.log(k));
      return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    },

    handleBatchUploadSubmit() {
      if (this.selectedFilesList.length === 0) {
        this.$message.error('请选择要上传的文件');
        return;
      }

      this.uploading = true;
      this.submitResult = null;

      const uploadPromises = this.selectedFilesList.map(file => {
        return new Promise((resolve, reject) => {
          const formData = new FormData();
          formData.append('file', file.raw);

          KnowledgeBaseAPI.uploadDocument(this.datasetId, formData,
            ({ data }) => {
              if (data && data.code === 0) {
                resolve({
                  success: true,
                  fileName: file.name,
                  document: data.data || null
                });
              } else {
                reject({ success: false, fileName: file.name, error: data?.msg || this.$t('knowledgeFileUpload.uploadFailed') });
              }
            },
            (err) => {
              if (err && err.data) {
                reject({ success: false, fileName: file.name, error: err.data.msg || err.msg || this.$t('knowledgeFileUpload.uploadFailed') });
              } else {
                reject({ success: false, fileName: file.name, error: this.$t('knowledgeFileUpload.uploadFailed') });
              }
              console.error('上传文档失败:', err);
            }
          );
        });
      });

      Promise.all(uploadPromises.map(p => p.catch(e => e)))
        .then(results => {
          this.uploading = false;

          const successCount = results.filter(r => r.success).length;
          const failedCount = results.filter(r => !r.success).length;
          const failedResults = results.filter(r => !r.success);

          if (successCount > 0) {
            this.$emit('upload-complete', {
              acceptedCount: successCount,
              documents: results.filter(r => r.success).map(r => r.document).filter(Boolean)
            });
          }

          if (failedCount > 0) {
            const failedFiles = failedResults.map(r => `${r.fileName}：${r.error}`);
            this.submitResult = {
              type: successCount > 0 ? 'warning' : 'error',
              title: successCount > 0
                ? this.$t('knowledgeFileUpload.partialSubmitTitle')
                : this.$t('knowledgeFileUpload.submitFailedTitle'),
              description: failedFiles.join('；')
            };
            const failedNames = new Set(failedResults.map(r => r.fileName));
            this.selectedFilesList = this.selectedFilesList.filter(file => failedNames.has(file.name));
          }

          if (successCount > 0 && failedCount === 0) {
            this.internalVisible = false;
            this.$notify({
              title: this.$t('knowledgeFileUpload.submittedTitle'),
              message: this.$t('knowledgeFileUpload.submittedDescription', { count: successCount }),
              type: 'success',
              duration: 8000
            });
          } else if (successCount > 0) {
            this.$notify({
              title: this.$t('knowledgeFileUpload.partialSubmitTitle'),
              message: this.$t('knowledgeFileUpload.partialSubmitDescription', {
                success: successCount,
                failed: failedCount
              }),
              type: 'warning',
              duration: 8000
            });
          } else if (failedCount > 0) {
            this.$notify({
              title: this.$t('knowledgeFileUpload.submitFailedTitle'),
              message: failedResults.map(r => `${r.fileName}：${r.error}`).join('；'),
              type: 'error',
              duration: 0
            });
          }
        })
        .catch(error => {
          this.uploading = false;
          this.$message.error('批量上传失败');
          console.error('批量上传失败:', error);
        });
    },

    closeDialog() {
      this.internalVisible = false;
    },

    handleClose() {
      this.selectedFilesList = [];
      this.uploading = false;
      this.submitResult = null;
    }
  }
};
</script>

<style lang="scss" scoped>
/* 拖拽上传区域样式 */
.upload-state-alert {
  margin-bottom: 16px;
}

.document-uploader {
  :deep(.el-upload-dragger) {
    width: 600px;
    height: 200px;
    min-height: 200px;
    border: 2px dashed #c0c4cc;
    border-radius: 16px;
    cursor: pointer;
    position: relative;
    overflow: hidden;
    transition: border-color 0.3s ease;

    &:hover {
      border-color: #409eff;
    }

    .el-icon-upload {
      font-size: 48px;
      color: #c0c4cc;
      margin: 20px 0 16px;
      line-height: 1;
    }

    .el-upload__text {
      font-size: 16px;
      color: #606266;
      margin-bottom: 8px;
      line-height: 1.5;
    }

    .el-upload__tip {
      font-size: 14px;
      color: #909399;
      line-height: 1.5;
    }
  }
}

/* 已选择文件列表样式 */
.selected-files-section {
  margin-top: 20px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 16px;
  background-color: #f8f9fa;

  h4 {
    margin: 0 0 12px 0;
    font-size: 14px;
    font-weight: 600;
    color: #606266;
  }
}

.selected-files-list {
  max-height: 200px;
  overflow-y: auto;
}

.selected-file-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background-color: white;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  margin-bottom: 8px;

  &:last-child {
    margin-bottom: 0;
  }

  .file-info {
    display: flex;
    align-items: center;
    flex: 1;

    .el-icon-document {
      color: #409eff;
      margin-right: 8px;
      font-size: 16px;
    }

    .file-name {
      font-size: 14px;
      color: #303133;
      margin-right: 12px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      max-width: 300px;
    }

    .file-size {
      font-size: 12px;
      color: #909399;
    }
  }

  .remove-btn {
    color: #f56c6c;
    padding: 4px;

    &:hover {
      color: #f78989;
      background-color: #fef0f0;
      border-radius: 4px;
    }
  }
}

:deep(.el-dialog) {
  border-radius: 16px !important;
  overflow: hidden;
}
</style>
