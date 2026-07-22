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
import { ref } from 'vue'
import { getModelOptions } from '@/api/agent/agent'
import { getKnowledgeBase, updateKnowledgeBase } from '@/api/knowledge'
import { toast } from '@/utils/toast'

const datasetId = ref('')
const knowledgeBase = ref<KnowledgeBase | null>(null)
const models = ref<ModelOption[]>([])
const modelIndex = ref(0)
const enabled = ref(false)
const maxInputChars = ref(12000)
const maxTokens = ref(4000)
const parserConfigValue = ref<Record<string, any>>({})
const saving = ref(false)

async function load() {
  try {
    const [base, modelList] = await Promise.all([
      getKnowledgeBase(datasetId.value),
      getModelOptions('LLM'),
    ])
    knowledgeBase.value = base
    models.value = modelList || []
    try {
      parserConfigValue.value = base.parserConfig ? JSON.parse(base.parserConfig) : {}
    }
    catch {
      parserConfigValue.value = {}
    }
    enabled.value = Boolean(parserConfigValue.value.llm_enabled)
    maxInputChars.value = Number(parserConfigValue.value.llm_max_input_chars) || 12000
    maxTokens.value = Number(parserConfigValue.value.llm_max_tokens) || 4000
    const currentId = String(parserConfigValue.value.llm_model_id || '')
    const index = models.value.findIndex(item => item.id === currentId)
    modelIndex.value = index >= 0 ? index : 0
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '配置加载失败')
  }
}

async function save() {
  if (!knowledgeBase.value)
    return
  if (enabled.value && !models.value.length) {
    toast.warning('没有可用的 LLM 模型')
    return
  }
  saving.value = true
  try {
    const config = { ...parserConfigValue.value }
    delete config.llm_base_url
    delete config.llm_api_key
    delete config.llm_model
    const parserConfig = JSON.stringify({
      ...config,
      max_chunk_chars: 1200,
      llm_enabled: enabled.value,
      llm_model_id: enabled.value ? models.value[modelIndex.value]?.id || '' : '',
      llm_max_input_chars: Math.min(20000, Math.max(500, Number(maxInputChars.value) || 12000)),
      llm_max_tokens: Math.min(8000, Math.max(200, Number(maxTokens.value) || 4000)),
      retrieval_config: config.retrieval_config || {
        mode: 'normal',
        search_type: 'semantic',
        semantic_level: 4,
        semantic_top_k: 10,
        faq_threshold: 1,
        semantic_threshold: 0.05,
        rerank_enabled: false,
      },
    })
    await updateKnowledgeBase(datasetId.value, { ...knowledgeBase.value, parserConfig })
    parserConfigValue.value = JSON.parse(parserConfig)
    toast.success('切片配置已保存')
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '保存失败')
  }
  finally {
    saving.value = false
  }
}

onLoad((options) => {
  datasetId.value = String(options?.datasetId || '')
  load()
})
</script>

<template>
  <view class="page">
    <view class="form">
      <view class="row">
        <view>
          <text class="label">
            大模型切片
          </text><text class="desc">
            保存后重新上传原文件时生效
          </text>
        </view>
        <switch :checked="enabled" color="#3977d6" @change="enabled = $event.detail.value" />
      </view>

      <picker v-if="enabled" mode="selector" :range="models" range-key="modelName" :value="modelIndex" @change="modelIndex = Number($event.detail.value)">
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

      <view v-if="enabled" class="field">
        <text class="field-label">
          输入字符数
        </text>
        <input v-model.number="maxInputChars" class="number-input" type="number">
      </view>
      <view v-if="enabled" class="field">
        <text class="field-label">
          输出 Token 数
        </text>
        <input v-model.number="maxTokens" class="number-input" type="number">
      </view>

      <button class="save" :disabled="saving" @click="save">
        {{ saving ? '保存中...' : '保存' }}
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
</style>
