<route lang="jsonc" type="page">
{
  "needLogin": true,
  "layout": "default",
  "style": { "navigationBarTitleText": "切片配置", "backgroundColor": "#f5f7fa" }
}
</route>

<script lang="ts" setup>
import type { ModelOption } from '@/api/agent/types'
import type { KnowledgeBase } from '@/api/knowledge'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { getModelOptions } from '@/api/agent/agent'
import { getDocuments, getKnowledgeBase, updateKnowledgeBase } from '@/api/knowledge'
import { toast } from '@/utils/toast'
import { createLatestRequestGate } from './documentWorkflow'

const datasetId = ref('')
const knowledgeBase = ref<KnowledgeBase | null>(null)
const models = ref<ModelOption[]>([])
const modelIndex = ref(0)
const maxInputChars = ref(12000)
const maxTokens = ref(4000)
const parserConfigValue = ref<Record<string, any>>({})
const loading = ref(false)
const saving = ref(false)
const loadError = ref('')
const documentCount = ref(0)
const loadGate = createLatestRequestGate()
let pageAlive = true

async function load() {
  const requestId = loadGate.next()
  loading.value = true
  loadError.value = ''
  try {
    const [base, modelList, documentPage] = await Promise.all([
      getKnowledgeBase(datasetId.value),
      getModelOptions('LLM'),
      getDocuments(datasetId.value, { pageSize: 1 }),
    ])
    if (!pageAlive || !loadGate.isLatest(requestId))
      return
    knowledgeBase.value = base
    models.value = (modelList || []).filter(isOpenAiCompatibleModel)
    documentCount.value = Number(documentPage.total) || documentPage.list?.length || 0
    try {
      parserConfigValue.value = base.parserConfig ? JSON.parse(base.parserConfig) : {}
    }
    catch {
      parserConfigValue.value = {}
    }
    maxInputChars.value = Number(parserConfigValue.value.llm_max_input_chars) || 12000
    maxTokens.value = Number(parserConfigValue.value.llm_max_tokens) || 4000
    const currentId = String(parserConfigValue.value.llm_model_id || '')
    const index = models.value.findIndex(item => item.id === currentId)
    modelIndex.value = index >= 0 ? index : 0
  }
  catch (error) {
    if (!pageAlive || !loadGate.isLatest(requestId))
      return
    loadError.value = error instanceof Error ? error.message : '配置加载失败'
    toast.error(loadError.value)
  }
  finally {
    if (loadGate.isLatest(requestId))
      loading.value = false
  }
}

async function save() {
  if (!knowledgeBase.value || loading.value || saving.value)
    return
  if (!models.value.length || !models.value[modelIndex.value]?.id) {
    toast.warning('没有可用的 OpenAI 兼容 LLM 切片模型')
    return
  }
  saving.value = true
  try {
    const config = { ...parserConfigValue.value }
    delete config.llm_base_url
    delete config.llm_api_key
    delete config.llm_model
    const existingRetrieval = { ...(config.retrieval_config || {}) }
    const retrievalConfig = {
      mode: existingRetrieval.mode || 'normal',
      keyword_level: Number(existingRetrieval.keyword_level ?? existingRetrieval.semantic_level) || 4,
      keyword_top_k: Number(existingRetrieval.keyword_top_k ?? existingRetrieval.semantic_top_k) || 10,
      keyword_threshold: Number(existingRetrieval.keyword_threshold ?? existingRetrieval.semantic_threshold) || 0.05,
    }
    const parserConfig = JSON.stringify({
      ...config,
      max_chunk_chars: 1200,
      llm_enabled: true,
      llm_model_id: models.value[modelIndex.value].id,
      llm_max_input_chars: Math.min(20000, Math.max(500, Number(maxInputChars.value) || 12000)),
      llm_max_tokens: Math.min(8000, Math.max(200, Number(maxTokens.value) || 4000)),
      retrieval_config: retrievalConfig,
    })
    await updateKnowledgeBase(datasetId.value, { ...knowledgeBase.value, parserConfig })
    if (!pageAlive)
      return
    parserConfigValue.value = JSON.parse(parserConfig)
    toast.success('切片配置已保存')
    if (documentCount.value > 0) {
      uni.showModal({
        title: '重新切片现有文档',
        content: `当前有 ${documentCount.value} 个文档。新配置不会自动改写旧切片，是否现在逐个选择原文件重新切片？`,
        confirmText: '前往处理',
        success: (result) => {
          if (result.confirm)
            openReuploadDocuments()
        },
      })
    }
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '保存失败')
  }
  finally {
    saving.value = false
  }
}

function openReuploadDocuments() {
  if (!knowledgeBase.value)
    return
  uni.redirectTo({
    url: `/pages/knowledge/documents?datasetId=${encodeURIComponent(datasetId.value)}&name=${encodeURIComponent(knowledgeBase.value.name)}&reupload=1`,
  })
}

function isOpenAiCompatibleModel(model: ModelOption) {
  return String(model.adapterCode || '').toLowerCase() === 'openai'
    || String(model.protocol || '').toLowerCase().includes('openai')
}

onLoad((options) => {
  datasetId.value = String(options?.datasetId || '')
  load()
})

onUnload(() => {
  pageAlive = false
  loadGate.invalidate()
})
</script>

<template>
  <view class="page">
    <view v-if="loadError" class="error-banner">
      {{ loadError }}
    </view>
    <view class="form">
      <view class="semantic-only-banner">
        <text class="label">
          仅使用大模型语义切片
        </text>
        <text class="desc">
          模型调用失败时文档上传会失败，不会降级为规则切片。保存后重新上传原文件时生效。
        </text>
      </view>

      <picker mode="selector" :disabled="loading || saving" :range="models" range-key="modelName" :value="modelIndex" @change="modelIndex = Number($event.detail.value)">
        <view class="field picker-field">
          <text class="field-label">
            切片模型
          </text>
          <text class="field-value">
            {{ models[modelIndex]?.modelName || '请选择' }}
          </text>
          <wd-icon name="arrow-down" />
        </view>
      </picker>

      <view class="field">
        <text class="field-label">
          输入字符数
        </text>
        <input v-model.number="maxInputChars" class="number-input" type="number" :disabled="loading || saving">
      </view>
      <view class="field">
        <text class="field-label">
          输出 Token 数
        </text>
        <input v-model.number="maxTokens" class="number-input" type="number" :disabled="loading || saving">
      </view>

      <button class="save" :disabled="loading || saving" @click="save">
        {{ loading ? '加载中...' : saving ? '保存中...' : '保存' }}
      </button>
    </view>
    <view v-if="documentCount > 0" class="reupload-panel">
      <text>现有 {{ documentCount }} 个文档不会自动应用新配置。</text>
      <button class="reupload" :disabled="saving" @click="openReuploadDocuments">
        前往逐个重新切片
      </button>
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
.form {
  padding: 24rpx;
  border: 1rpx solid #e3e8ef;
  border-radius: 12rpx;
  background: #fff;
}
.semantic-only-banner {
  margin-bottom: 12rpx;
  padding: 20rpx;
  border: 1rpx solid #cddcf3;
  border-radius: 10rpx;
  background: #f3f7fd;
}
.error-banner,
.reupload-panel {
  margin-bottom: 18rpx;
  padding: 20rpx 22rpx;
  border: 1rpx solid #ffd5d2;
  border-radius: 10rpx;
  background: #fff3f2;
  color: #b42318;
  font-size: 24rpx;
  line-height: 1.55;
}
.reupload-panel {
  margin: 18rpx 0 0;
  border-color: #cddcf3;
  background: #f3f7fd;
  color: #42516a;
}
.row,
.field {
  min-height: 88rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  border-bottom: 1rpx solid #edf0f4;
}
.label,
.field-label {
  font-size: 27rpx;
  color: #253044;
}
.desc {
  display: block;
  margin-top: 6rpx;
  font-size: 21rpx;
  color: #8791a2;
}
.picker-field {
  justify-content: flex-start;
}
.picker-field .field-label {
  flex: 1;
}
.field-value {
  max-width: 380rpx;
  color: #3977d6;
  font-size: 25rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.number-input {
  width: 220rpx;
  height: 62rpx;
  padding: 0 16rpx;
  border: 1rpx solid #d8dfe8;
  border-radius: 8rpx;
  text-align: right;
  font-size: 26rpx;
  box-sizing: border-box;
}
.save {
  width: 100%;
  height: 70rpx;
  margin: 24rpx 0 0;
  border: 0;
  border-radius: 10rpx;
  background: #3977d6;
  color: #fff;
  font-size: 27rpx;
  line-height: 70rpx;
}
.save::after {
  border: 0;
}
.reupload {
  width: 100%;
  height: 66rpx;
  margin: 16rpx 0 0;
  border: 1rpx solid #3977d6;
  border-radius: 10rpx;
  background: #fff;
  color: #235fae;
  font-size: 25rpx;
  line-height: 66rpx;
}
.reupload::after {
  border: 0;
}
button[disabled] {
  opacity: 0.55;
}
</style>
