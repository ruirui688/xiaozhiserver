<route lang="jsonc" type="page">
{
  "needLogin": true,
  "layout": "default",
  "style": { "navigationBarTitleText": "知识库", "backgroundColor": "#f5f7fa" }
}
</route>

<script lang="ts" setup>
import type { ModelOption } from '@/api/agent/types'
import type { KnowledgeBase } from '@/api/knowledge'
import { computed, ref } from 'vue'
import { getModelOptions } from '@/api/agent/agent'
import { createKnowledgeBase, deleteKnowledgeBase, getKnowledgeBases, getRagModels, updateKnowledgeBase } from '@/api/knowledge'
import { toast } from '@/utils/toast'

const list = ref<KnowledgeBase[]>([])
const loading = ref(false)
const keyword = ref('')
const filteredList = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  return value ? list.value.filter(item => `${item.name} ${item.description || ''}`.toLowerCase().includes(value)) : list.value
})

async function load() {
  loading.value = true
  try {
    const data = await getKnowledgeBases({ pageSize: 100 })
    list.value = data.list || []
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '知识库加载失败')
  }
  finally {
    loading.value = false
  }
}

async function addKnowledgeBase() {
  let models
  try {
    models = await getRagModels()
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : 'RAG 模型加载失败')
    return
  }
  if (!models.length) {
    toast.warning('请先配置可用的 RAG 模型')
    return
  }
  const selected = await new Promise<number | null>((resolve) => {
    uni.showActionSheet({
      itemList: models.map(item => item.modelName || item.name || item.id),
      success: result => resolve(result.tapIndex),
      fail: () => resolve(null),
    })
  })
  if (selected == null)
    return

  let llmModels: ModelOption[]
  try {
    const modelOptions = await getModelOptions('LLM')
    llmModels = (modelOptions || []).filter(isOpenAiCompatibleModel)
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '切片模型加载失败')
    return
  }
  if (!llmModels.length) {
    toast.warning('请先配置并测试通过一个 OpenAI 兼容 LLM 切片模型')
    return
  }
  const selectedLlm = await new Promise<number | null>((resolve) => {
    uni.showActionSheet({
      itemList: llmModels.map(item => item.modelName || item.id),
      success: result => resolve(result.tapIndex),
      fail: () => resolve(null),
    })
  })
  if (selectedLlm == null)
    return

  uni.showModal({
    title: '新建知识库',
    editable: true,
    placeholderText: '知识库名称',
    success: async (result) => {
      const name = String(result.content || '').trim()
      if (!result.confirm || !name)
        return
      try {
        await createKnowledgeBase({
          name,
          description: '',
          status: 1,
          ragModelId: models[selected].id,
          parserConfig: JSON.stringify({
            max_chunk_chars: 1200,
            llm_enabled: true,
            llm_model_id: llmModels[selectedLlm].id,
            llm_max_input_chars: 12000,
            llm_max_tokens: 4000,
          }),
        })
        toast.success('知识库已创建')
        await load()
      }
      catch (error) {
        toast.error(error instanceof Error ? error.message : '创建失败')
      }
    },
  })
}

function isOpenAiCompatibleModel(model: ModelOption) {
  return String(model.adapterCode || '').toLowerCase() === 'openai'
    || String(model.protocol || '').toLowerCase().includes('openai')
}

async function toggleStatus(item: KnowledgeBase, value: boolean) {
  const previous = item.status
  item.status = value ? 1 : 0
  try {
    await updateKnowledgeBase(item.datasetId, { ...item, status: item.status })
  }
  catch (error) {
    item.status = previous
    toast.error(error instanceof Error ? error.message : '状态更新失败')
  }
}

function remove(item: KnowledgeBase) {
  uni.showModal({
    title: '删除知识库',
    content: `将同时删除“${item.name}”中的文档和切片。`,
    confirmColor: '#d93025',
    success: async (result) => {
      if (!result.confirm)
        return
      try {
        await deleteKnowledgeBase(item.datasetId)
        toast.success('已删除')
        await load()
      }
      catch (error) {
        toast.error(error instanceof Error ? error.message : '删除失败')
      }
    },
  })
}

function open(item: KnowledgeBase) {
  uni.navigateTo({
    url: `/pages/knowledge/documents?datasetId=${encodeURIComponent(item.datasetId)}&name=${encodeURIComponent(item.name)}`,
  })
}

function openChunkConfig(item: KnowledgeBase) {
  uni.navigateTo({
    url: `/pages/knowledge/config?datasetId=${encodeURIComponent(item.datasetId)}`,
  })
}

onShow(load)
</script>

<template>
  <view class="page">
    <view class="toolbar">
      <input v-model="keyword" class="search" placeholder="搜索知识库">
      <button class="icon-button" aria-label="新建知识库" @click="addKnowledgeBase">
        <wd-icon name="add" />
      </button>
    </view>

    <view v-if="loading" class="state">
      加载中...
    </view>
    <view v-else-if="filteredList.length === 0" class="state">
      暂无知识库
    </view>
    <view v-else class="list">
      <view v-for="item in filteredList" :key="item.datasetId" class="item" @click="open(item)">
        <view class="item-main">
          <view class="title-row">
            <text class="title">
              {{ item.name }}
            </text>
            <switch :checked="item.status === 1" color="#3977d6" @click.stop @change="toggleStatus(item, $event.detail.value)" />
          </view>
          <text class="description">
            {{ item.description || '未填写描述' }}
          </text>
          <view class="meta">
            <text>{{ item.documentCount || 0 }} 个文档</text>
            <text>{{ item.chunkCount || 0 }} 个切片</text>
          </view>
          <button class="config-button" @click.stop="openChunkConfig(item)">
            切片配置
          </button>
        </view>
        <button class="delete-button" aria-label="删除知识库" @click.stop="remove(item)">
          <wd-icon name="delete" />
        </button>
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
.toolbar {
  display: flex;
  gap: 16rpx;
  margin-bottom: 22rpx;
}
.search {
  height: 76rpx;
  flex: 1;
  padding: 0 24rpx;
  border: 1rpx solid #dce2ea;
  border-radius: 12rpx;
  background: #fff;
  font-size: 28rpx;
  box-sizing: border-box;
}
.icon-button,
.delete-button {
  margin: 0;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 12rpx;
}
.config-button {
  height: 54rpx;
  margin: 16rpx 0 0;
  padding: 0 18rpx;
  border: 1rpx solid #cddcf3;
  border-radius: 8rpx;
  background: #f6f9fe;
  color: #3977d6;
  font-size: 23rpx;
  line-height: 54rpx;
}
.config-button::after {
  border: 0;
}
.icon-button {
  width: 76rpx;
  height: 76rpx;
  background: #3977d6;
  color: #fff;
}
.icon-button::after,
.delete-button::after {
  border: 0;
}
.list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.item {
  display: flex;
  align-items: center;
  gap: 18rpx;
  padding: 24rpx;
  background: #fff;
  border: 1rpx solid #e5e9ef;
  border-radius: 12rpx;
}
.item-main {
  min-width: 0;
  flex: 1;
}
.title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}
.title {
  min-width: 0;
  font-size: 31rpx;
  font-weight: 650;
  color: #172033;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.description {
  display: block;
  margin-top: 10rpx;
  font-size: 25rpx;
  color: #687386;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta {
  display: flex;
  gap: 24rpx;
  margin-top: 14rpx;
  font-size: 23rpx;
  color: #3977d6;
}
.delete-button {
  width: 60rpx;
  height: 60rpx;
  flex-shrink: 0;
  background: #fff1f0;
  color: #d93025;
}
.state {
  padding: 160rpx 20rpx;
  text-align: center;
  font-size: 27rpx;
  color: #8a94a5;
}
</style>
