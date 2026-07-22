<route lang="jsonc" type="page">
{
  "needLogin": true,
  "layout": "default",
  "style": { "navigationBarTitleText": "长期记忆", "backgroundColor": "#f5f7fa" }
}
</route>

<script lang="ts" setup>
import type { Agent } from '@/api/agent/types'
import type { MemoryFact } from '@/api/memory'
import { computed, ref } from 'vue'
import { getAgentList } from '@/api/agent/agent'
import { archiveMemory, createMemoryFact, deleteMemory, forget, getMemoryDashboard } from '@/api/memory'
import { toast } from '@/utils/toast'

const agents = ref<Agent[]>([])
const agentIndex = ref(0)
const facts = ref<MemoryFact[]>([])
const loading = ref(false)
const operation = ref<'remember' | 'forget'>('remember')
const text = ref('')
const submitting = ref(false)
const activeFacts = computed(() => facts.value.filter(item => item.status !== 'deleted' && item.status !== 'archived'))
const currentAgent = computed(() => agents.value[agentIndex.value])

async function loadAgents() {
  try {
    agents.value = await getAgentList()
    if (agents.value.length)
      await loadMemories()
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '智能体加载失败')
  }
}

async function loadMemories() {
  if (!currentAgent.value)
    return
  loading.value = true
  try {
    const dashboard = await getMemoryDashboard(currentAgent.value.id)
    facts.value = dashboard.facts || []
  }
  catch (error) {
    facts.value = []
    toast.error(error instanceof Error ? error.message : '记忆加载失败')
  }
  finally {
    loading.value = false
  }
}

async function changeAgent(event: { detail: { value: number | string } }) {
  agentIndex.value = Number(event.detail.value)
  await loadMemories()
}

async function submit() {
  const value = text.value.trim()
  if (!value || !currentAgent.value) {
    toast.warning('请输入记忆内容')
    return
  }
  submitting.value = true
  try {
    if (operation.value === 'remember')
      await createMemoryFact(currentAgent.value.id, value)
    else
      await forget(currentAgent.value.id, value)
    text.value = ''
    toast.success(operation.value === 'remember' ? '已记住' : '已提交忘记')
    await loadMemories()
  }
  catch (error) {
    toast.error(error instanceof Error ? error.message : '操作失败')
  }
  finally {
    submitting.value = false
  }
}

function confirmAction(title: string, content: string, action: () => Promise<unknown>) {
  uni.showModal({
    title,
    content,
    confirmColor: '#d93025',
    success: async (result) => {
      if (!result.confirm || !currentAgent.value)
        return
      try {
        await action()
        await loadMemories()
      }
      catch (error) {
        toast.error(error instanceof Error ? error.message : '操作失败')
      }
    },
  })
}

function archive(item: MemoryFact) {
  confirmAction('归档记忆', item.canonicalText, () => archiveMemory(currentAgent.value.id, item.memoryId))
}

function remove(item: MemoryFact) {
  confirmAction('删除记忆', item.canonicalText, () => deleteMemory(currentAgent.value.id, item.memoryId))
}

onLoad(loadAgents)
</script>

<template>
  <view class="page">
    <picker v-if="agents.length" mode="selector" :range="agents" range-key="agentName" :value="agentIndex" @change="changeAgent">
      <view class="agent-picker">
        <view>
          <text class="label">
            当前智能体
          </text><text class="agent-name">
            {{ currentAgent?.agentName }}
          </text>
        </view>
        <wd-icon name="arrow-down" />
      </view>
    </picker>
    <view v-else class="state">
      暂无智能体
    </view>

    <view v-if="agents.length" class="editor">
      <view class="segments">
        <button :class="{ active: operation === 'remember' }" @click="operation = 'remember'">
          记住
        </button>
        <button :class="{ active: operation === 'forget' }" @click="operation = 'forget'">
          忘记
        </button>
      </view>
      <textarea v-model="text" class="input" :placeholder="operation === 'remember' ? '输入需要长期保存的事实' : '输入需要忘记的内容'" :maxlength="2000" />
      <button class="submit" :disabled="submitting" @click="submit">
        {{ submitting ? '处理中...' : '确认' }}
      </button>
    </view>

    <view v-if="agents.length" class="heading">
      <text>生效记忆</text><text class="count">
        {{ activeFacts.length }}
      </text>
    </view>
    <view v-if="loading" class="state">
      加载中...
    </view>
    <view v-else-if="agents.length && activeFacts.length === 0" class="state">
      暂无生效记忆
    </view>
    <view v-else class="facts">
      <view v-for="item in activeFacts" :key="item.memoryId" class="fact">
        <view class="fact-tags">
          <text>{{ item.category || '未分类' }}</text>
          <text>{{ item.scopeType === 'speaker' ? '私有' : '共享' }}</text>
          <text v-if="item.memoryTier">
            {{ item.memoryTier }}
          </text>
        </view>
        <text class="fact-text">
          {{ item.canonicalText }}
        </text>
        <view class="fact-actions">
          <button @click="archive(item)">
            归档
          </button>
          <button class="danger" @click="remove(item)">
            <wd-icon name="delete" />
          </button>
        </view>
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
.agent-picker,
.editor,
.fact {
  padding: 24rpx;
  background: #fff;
  border: 1rpx solid #e3e8ef;
  border-radius: 12rpx;
}
.agent-picker {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.label {
  display: block;
  margin-bottom: 6rpx;
  font-size: 22rpx;
  color: #788396;
}
.agent-name {
  font-size: 30rpx;
  font-weight: 650;
  color: #172033;
}
.editor {
  margin-top: 18rpx;
}
.segments {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8rpx;
  padding: 6rpx;
  border-radius: 10rpx;
  background: #eef1f5;
}
.segments button {
  height: 60rpx;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 8rpx;
  background: transparent;
  color: #667084;
  font-size: 26rpx;
  line-height: 60rpx;
}
.segments button::after {
  border: 0;
}
.segments button.active {
  background: #fff;
  color: #3977d6;
  font-weight: 650;
  box-shadow: 0 2rpx 8rpx rgba(28, 39, 61, 0.08);
}
.input {
  width: 100%;
  height: 160rpx;
  margin-top: 18rpx;
  padding: 18rpx;
  border: 1rpx solid #d8dfe8;
  border-radius: 10rpx;
  font-size: 26rpx;
  box-sizing: border-box;
}
.submit {
  width: 100%;
  height: 70rpx;
  margin: 18rpx 0 0;
  border: 0;
  border-radius: 10rpx;
  background: #3977d6;
  color: #fff;
  font-size: 27rpx;
  line-height: 70rpx;
}
.submit::after {
  border: 0;
}
.heading {
  display: flex;
  justify-content: space-between;
  margin: 28rpx 4rpx 14rpx;
  font-size: 29rpx;
  font-weight: 650;
  color: #172033;
}
.count {
  color: #3977d6;
}
.facts {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}
.fact-tags {
  display: flex;
  gap: 10rpx;
  flex-wrap: wrap;
}
.fact-tags text {
  padding: 4rpx 12rpx;
  border-radius: 6rpx;
  background: #edf4ff;
  color: #3977d6;
  font-size: 21rpx;
}
.fact-text {
  display: block;
  margin-top: 16rpx;
  font-size: 27rpx;
  line-height: 1.65;
  color: #2c3749;
  white-space: pre-wrap;
  word-break: break-all;
}
.fact-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12rpx;
  margin-top: 18rpx;
}
.fact-actions button {
  height: 58rpx;
  margin: 0;
  padding: 0 20rpx;
  border: 1rpx solid #d8dfe8;
  border-radius: 8rpx;
  background: #fff;
  color: #596478;
  font-size: 23rpx;
  line-height: 58rpx;
}
.fact-actions button::after {
  border: 0;
}
.fact-actions .danger {
  width: 58rpx;
  padding: 0;
  color: #d93025;
  background: #fff1f0;
  border-color: #ffe1df;
}
.state {
  padding: 100rpx 20rpx;
  text-align: center;
  color: #8a94a5;
  font-size: 26rpx;
}
</style>
