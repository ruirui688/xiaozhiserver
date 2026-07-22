<route lang="jsonc" type="page">
{
  "needLogin": true,
  "layout": "default",
  "style": { "navigationBarTitleText": "知识库文档", "backgroundColor": "#f5f7fa" }
}
</route>

<script lang="ts" setup>
import type { KnowledgeChunk, KnowledgeDocument, RetrievalHit } from '@/api/knowledge'
import { ref } from 'vue'
import { deleteDocument, getChunks, getDocuments, parseDocuments, runRetrievalTest, uploadKnowledgeDocument } from '@/api/knowledge'
import { toast } from '@/utils/toast'

const allowedExtensions = ['doc', 'docx', 'pdf', 'txt', 'md', 'mdx', 'csv', 'xls', 'xlsx', 'ppt', 'pptx', 'json', 'jsonl', 'qa']
const datasetId = ref('')
const documents = ref<KnowledgeDocument[]>([])
const loading = ref(false)
const busyId = ref('')
const selectedDocument = ref<KnowledgeDocument | null>(null)
const chunks = ref<KnowledgeChunk[]>([])
const retrievalQuestion = ref('')
const retrievalHits = ref<RetrievalHit[]>([])
const retrievalLoading = ref(false)

async function loadDocuments() {
  if (!datasetId.value)
    return
  loading.value = true
  try {
    const data = await getDocuments(datasetId.value, { pageSize: 100 })
    documents.value = data.list || []
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '文档加载失败')
  }
  finally {
    loading.value = false
  }
}

function chooseFile() {
  const chooser = (uni as any).chooseMessageFile
  if (!chooser) {
    toast.warning('当前平台不支持选择文档')
    return
  }
  chooser({
    count: 1,
    type: 'file',
    success: async (result: { tempFiles: Array<{ path: string, name: string, size: number }> }) => {
      const file = result.tempFiles[0]
      const extension = file.name.split('.').pop()?.toLowerCase() || ''
      if (!allowedExtensions.includes(extension)) {
        toast.warning('不支持此文件类型')
        return
      }
      if (file.size > 10 * 1024 * 1024) {
        toast.warning('文件不能超过 10MB')
        return
      }
      try {
        uni.showLoading({ title: '上传中', mask: true })
        const document = await uploadKnowledgeDocument(datasetId.value, file.path, file.name)
        toast.success('上传成功')
        await loadDocuments()
        if (document?.id)
          await parse(document)
      }
      catch (error) {
        toast.error(error instanceof Error ? error.message : '上传失败')
      }
      finally {
        uni.hideLoading()
      }
    },
  })
}

async function parse(document: KnowledgeDocument) {
  busyId.value = document.id
  try {
    await parseDocuments(datasetId.value, [document.id])
    toast.success('已提交解析')
    await loadDocuments()
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '解析失败')
  }
  finally {
    busyId.value = ''
  }
}

async function viewChunks(document: KnowledgeDocument) {
  busyId.value = document.id
  try {
    const data = await getChunks(datasetId.value, document.id, { pageSize: 100 })
    selectedDocument.value = document
    chunks.value = data.chunks || []
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '切片加载失败')
  }
  finally {
    busyId.value = ''
  }
}

function remove(document: KnowledgeDocument) {
  uni.showModal({
    title: '删除文档',
    content: `确定删除“${document.name}”及其切片？`,
    confirmColor: '#d93025',
    success: async (result) => {
      if (!result.confirm)
        return
      try {
        await deleteDocument(datasetId.value, document.id)
        if (selectedDocument.value?.id === document.id) {
          selectedDocument.value = null
          chunks.value = []
        }
        await loadDocuments()
      }
      catch (error) {
        toast.error(error instanceof Error ? error.message : '删除失败')
      }
    },
  })
}

async function retrievalTest() {
  const question = retrievalQuestion.value.trim()
  if (!question) {
    toast.warning('请输入召回问题')
    return
  }
  retrievalLoading.value = true
  try {
    const data = await runRetrievalTest(datasetId.value, question)
    retrievalHits.value = data.chunks || []
    if (!retrievalHits.value.length)
      toast.info('没有召回切片')
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '召回测试失败')
  }
  finally {
    retrievalLoading.value = false
  }
}

function statusText(document: KnowledgeDocument) {
  return ({ UNSTART: '未解析', RUNNING: '解析中', DONE: '已完成', FAIL: '失败', CANCEL: '已取消' } as Record<string, string>)[document.run || ''] || document.run || '未解析'
}

onLoad((options) => {
  datasetId.value = String(options?.datasetId || '')
  if (options?.name)
    uni.setNavigationBarTitle({ title: decodeURIComponent(String(options.name)) })
  loadDocuments()
})
</script>

<template>
  <view class="page">
    <view class="actions">
      <button class="primary" @click="chooseFile">
        <wd-icon name="upload" /> 上传文档
      </button>
      <button class="secondary" @click="loadDocuments">
        <wd-icon name="refresh" /> 刷新
      </button>
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
          <text class="status" :class="document.run?.toLowerCase()">
            {{ statusText(document) }}
          </text>
        </view>
        <view class="meta">
          <text>{{ document.chunkCount || 0 }} 个切片</text><text>{{ document.tokenCount || 0 }} tokens</text>
        </view>
        <text v-if="document.error" class="error">
          {{ document.error }}
        </text>
        <view class="row-actions">
          <button :disabled="busyId === document.id" @click="parse(document)">
            解析
          </button>
          <button :disabled="busyId === document.id" @click="viewChunks(document)">
            查看切片
          </button>
          <button class="danger" @click="remove(document)">
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
