<route lang="jsonc" type="page">
{
  "needLogin": true,
  "layout": "default",
  "style": { "navigationBarTitleText": "知识库文档", "backgroundColor": "#f5f7fa" }
}
</route>

<script lang="ts" setup>
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { useKnowledgeDocuments } from './useKnowledgeDocuments'

const {
  documents,
  loading,
  uploading,
  selectedDocument,
  chunks,
  retrievalQuestion,
  retrievalHits,
  retrievalLoading,
  loadError,
  workflowError,
  reuploadMode,
  processingCount,
  loadDocuments,
  chooseFile,
  parse,
  viewChunks,
  remove,
  retrievalTest,
  isBusy,
  isDocumentProcessing,
  documentProgressPercent,
  documentStatusText,
  normalizeDocumentStatus,
  initialize,
  activate,
  deactivate,
} = useKnowledgeDocuments()

onLoad(options => initialize(options))
onShow(activate)
onHide(deactivate)
onUnload(deactivate)
</script>

<template>
  <view class="page">
    <view class="actions">
      <button class="primary" :disabled="uploading" @click="chooseFile()">
        <wd-icon name="upload" /> 上传文档
      </button>
      <button class="secondary" :disabled="loading" @click="loadDocuments()">
        <wd-icon name="refresh" /> 刷新
      </button>
    </view>

    <view v-if="reuploadMode" class="workflow-notice">
      <text class="workflow-title">
        重新切片现有文档
      </text>
      <text>逐个点击“重新切片”并选择原文件。新文档解析完成后才会删除旧文档。</text>
    </view>

    <view v-if="processingCount" class="processing-notice">
      {{ processingCount }} 个文档正在解析，状态会自动更新
    </view>
    <view v-if="loadError" class="error-banner">
      状态更新失败：{{ loadError }}
    </view>
    <view v-if="workflowError" class="error-banner">
      {{ workflowError }}
    </view>

    <view v-if="loading" class="state">
      加载中...
    </view>
    <view v-else-if="documents.length === 0" class="state">
      暂无文档
    </view>
    <view v-else class="documents">
      <view v-for="document in documents" :key="document.id" class="document">
        <view class="document-head">
          <text class="document-name">
            {{ document.name }}
          </text>
          <text class="status" :class="normalizeDocumentStatus(document.run).toLowerCase()">
            {{ documentStatusText(document) }}
          </text>
        </view>
        <view class="meta">
          <text>{{ document.chunkCount || 0 }} 个切片</text><text>{{ document.tokenCount || 0 }} tokens</text>
        </view>
        <view v-if="isDocumentProcessing(document)" class="progress-track">
          <view class="progress-value" :style="{ width: `${documentProgressPercent(document)}%` }" />
        </view>
        <text v-if="document.error" class="error">
          {{ document.error }}
        </text>
        <view class="row-actions">
          <button :disabled="isBusy(document.id) || isDocumentProcessing(document)" @click="parse(document)">
            {{ isDocumentProcessing(document) ? '解析中' : '解析' }}
          </button>
          <button :disabled="isBusy(document.id)" @click="viewChunks(document)">
            查看切片
          </button>
          <button v-if="reuploadMode" :disabled="isBusy(document.id) || uploading" @click="chooseFile(document)">
            重新切片
          </button>
          <button class="danger" :disabled="isBusy(document.id)" @click="remove(document)">
            <wd-icon name="delete" />
          </button>
        </view>
      </view>
    </view>

    <view v-if="selectedDocument" class="section">
      <view class="section-head">
        <text>切片 · {{ selectedDocument.name }}</text><text class="count">
          {{ chunks.length }}
        </text>
      </view>
      <view v-if="chunks.length === 0" class="inline-state">
        暂无切片
      </view>
      <view v-for="(chunk, index) in chunks" :key="chunk.id" class="chunk">
        <text class="chunk-index">
          #{{ index + 1 }}
        </text>
        <text class="chunk-content">
          {{ chunk.content }}
        </text>
      </view>
    </view>

    <view class="section">
      <text class="section-title">
        召回测试
      </text>
      <textarea v-model="retrievalQuestion" class="question" placeholder="输入一个问题" :maxlength="500" />
      <button class="primary full" :disabled="retrievalLoading" @click="retrievalTest">
        {{ retrievalLoading ? '检索中...' : '开始检索' }}
      </button>
      <view v-for="(hit, index) in retrievalHits" :key="hit.id" class="chunk hit">
        <view class="hit-head">
          <text>#{{ index + 1 }} {{ hit.documentName || '' }}</text><text>{{ hit.similarity == null ? '-' : `${(hit.similarity * 100).toFixed(1)}%` }}</text>
        </view>
        <text class="chunk-content">
          {{ hit.content }}
        </text>
      </view>
    </view>
  </view>
</template>

<style lang="scss" scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  background: #f5f7fa;
  box-sizing: border-box;
}
.actions,
.row-actions {
  display: flex;
  gap: 14rpx;
}
button {
  height: 68rpx;
  margin: 0;
  padding: 0 22rpx;
  border: 1rpx solid #d8dfe8;
  border-radius: 10rpx;
  background: #fff;
  font-size: 25rpx;
  line-height: 68rpx;
}
button::after {
  border: 0;
}
button[disabled] {
  opacity: 0.55;
}
.primary {
  background: #3977d6;
  border-color: #3977d6;
  color: #fff;
}
.secondary {
  color: #354052;
}
.danger {
  width: 68rpx;
  padding: 0;
  color: #d93025;
  background: #fff1f0;
  border-color: #ffe1df;
}
.full {
  width: 100%;
  margin-top: 16rpx;
}
.workflow-notice,
.processing-notice,
.error-banner {
  margin-top: 18rpx;
  padding: 20rpx 22rpx;
  border: 1rpx solid #cddcf3;
  border-radius: 10rpx;
  background: #f3f7fd;
  color: #42516a;
  font-size: 24rpx;
  line-height: 1.55;
}
.workflow-title {
  display: block;
  margin-bottom: 4rpx;
  color: #235fae;
  font-size: 26rpx;
  font-weight: 650;
}
.processing-notice {
  color: #235fae;
}
.error-banner {
  border-color: #ffd5d2;
  background: #fff3f2;
  color: #b42318;
}
.documents {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-top: 22rpx;
}
.document,
.section {
  padding: 24rpx;
  background: #fff;
  border: 1rpx solid #e3e8ef;
  border-radius: 12rpx;
}
.document-head,
.section-head,
.hit-head {
  display: flex;
  justify-content: space-between;
  gap: 16rpx;
}
.document-name {
  min-width: 0;
  flex: 1;
  font-size: 29rpx;
  font-weight: 650;
  color: #172033;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.status {
  flex-shrink: 0;
  font-size: 22rpx;
  color: #687386;
}
.status.done {
  color: #16834b;
}
.status.fail {
  color: #d93025;
}
.status.running {
  color: #3977d6;
}
.meta {
  display: flex;
  gap: 20rpx;
  margin: 14rpx 0 18rpx;
  font-size: 23rpx;
  color: #687386;
}
.progress-track {
  height: 8rpx;
  margin: -6rpx 0 18rpx;
  overflow: hidden;
  border-radius: 4rpx;
  background: #e5eaf1;
}
.progress-value {
  height: 100%;
  min-width: 4rpx;
  border-radius: inherit;
  background: #3977d6;
  transition: width 0.2s ease;
}
.error {
  display: block;
  margin: -4rpx 0 18rpx;
  color: #d93025;
  font-size: 23rpx;
}
.section {
  margin-top: 24rpx;
}
.section-title,
.section-head {
  font-size: 29rpx;
  font-weight: 650;
  color: #172033;
}
.count {
  color: #3977d6;
}
.chunk {
  margin-top: 16rpx;
  padding: 18rpx;
  border-left: 6rpx solid #3977d6;
  background: #f7f9fc;
}
.chunk-index {
  display: block;
  margin-bottom: 8rpx;
  color: #3977d6;
  font-size: 22rpx;
}
.chunk-content {
  display: block;
  color: #354052;
  font-size: 25rpx;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-all;
}
.question {
  width: 100%;
  height: 150rpx;
  margin-top: 18rpx;
  padding: 18rpx;
  border: 1rpx solid #d8dfe8;
  border-radius: 10rpx;
  font-size: 26rpx;
  box-sizing: border-box;
}
.hit-head {
  margin-bottom: 10rpx;
  color: #3977d6;
  font-size: 23rpx;
}
.state,
.inline-state {
  padding: 120rpx 20rpx;
  text-align: center;
  font-size: 26rpx;
  color: #8a94a5;
}
.inline-state {
  padding: 36rpx 0 10rpx;
}
</style>
