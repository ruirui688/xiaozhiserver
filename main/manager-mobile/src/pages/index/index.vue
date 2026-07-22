<route lang="jsonc" type="page">
{
  "layout": "tabbar",
  "style": {
    // 'custom' 表示开启自定义导航栏，默认 'default'
    "navigationStyle": "custom",
    "navigationBarTitleText": "首页"
  }
}
</route>

<script lang="ts" setup>
import type { Agent } from '@/api/agent/types'
import { computed, onMounted, ref } from 'vue'
// 在组件挂载后设置导航栏标题
import { useMessage } from 'wot-design-uni'
import useZPaging from 'z-paging/components/z-paging/js/hooks/useZPaging.js'
import { createAgent, deleteAgent, getAgentList } from '@/api/agent/agent'
import { t } from '@/i18n'
import { parseDateTime } from '@/utils'
import { getStoredAccessToken } from '@/utils/auth'
import { getSafeAreaInsets } from '@/utils/system'
import { toast } from '@/utils/toast'

defineOptions({
  name: 'Home',
})

const safeAreaInsets = getSafeAreaInsets()

// 智能体数据
const agentList = ref<Agent[]>([])
const pagingRef = ref()
useZPaging(pagingRef)
// 消息组件
const message = useMessage()

const totalDeviceCount = computed(() =>
  agentList.value.reduce((total, agent) => total + (Number(agent.deviceCount) || 0), 0),
)

const activeAgentCount = computed(() =>
  agentList.value.filter(agent => Boolean(agent.lastConnectedAt)).length,
)

const latestActiveAgent = computed(() => {
  return [...agentList.value]
    .filter(agent => agent.lastConnectedAt)
    .sort((a, b) => parseDateTime(b.lastConnectedAt).getTime() - parseDateTime(a.lastConnectedAt).getTime())[0]
})

const overviewItems = computed(() => [
  {
    label: t('home.totalAgents'),
    value: agentList.value.length,
    icon: 'user',
  },
  {
    label: t('home.totalDevices'),
    value: totalDeviceCount.value,
    icon: 'phone',
  },
  {
    label: t('home.activeAgents'),
    value: activeAgentCount.value,
    icon: 'time',
  },
])

const quickActions = computed(() => [
  {
    label: t('home.quickCreateAgent'),
    desc: t('home.quickCreateAgentDesc'),
    icon: 'add',
    action: openCreateDialog,
  },
  {
    label: '知识库',
    desc: '管理文档、切片和召回测试',
    icon: 'books',
    action: () => uni.navigateTo({ url: '/pages/knowledge/index' }),
  },
  {
    label: '长期记忆',
    desc: '查看和维护智能体记忆',
    icon: 'view',
    action: () => uni.navigateTo({ url: '/pages/memory/index' }),
  },
])

function hasToken() {
  return Boolean(getStoredAccessToken())
}

function goToLogin() {
  uni.reLaunch({ url: '/pages/login/index' })
}

function completePaging(data: Agent[]) {
  pagingRef.value?.complete(data)?.catch((error: unknown) => {
    console.warn('z-paging complete ignored:', error)
  })
}

function refreshPaging() {
  pagingRef.value?.refresh()?.catch((error: unknown) => {
    console.warn('z-paging refresh ignored:', error)
  })
}

function reloadPaging() {
  pagingRef.value?.reload()?.catch((error: unknown) => {
    console.warn('z-paging reload ignored:', error)
  })
}

// z-paging查询列表数据
async function queryList(pageNo: number, pageSize: number) {
  if (!hasToken()) {
    agentList.value = []
    completePaging([])
    goToLogin()
    return
  }

  try {
    console.log('z-paging获取智能体列表')

    const response = await getAgentList()

    // 更新本地列表
    agentList.value = response

    // 直接返回全部数据，不需要分页处理
    completePaging(response)
  }
  catch (error) {
    console.error('获取智能体列表失败:', error)
    agentList.value = []
    completePaging([])
  }
}

// 创建智能体
async function handleCreateAgent(agentName: string) {
  try {
    await createAgent({ agentName: agentName.trim() })
    // 创建成功后刷新列表
    reloadPaging()
    toast.success(`${t('home.agentName')}"${agentName}"${t('message.saveSuccess')}`)
  }
  catch (error: any) {
    console.error('创建智能体失败:', error)
    const errorMessage = error?.message || t('message.saveFail')
    toast.error(errorMessage)
  }
}

// 删除智能体
async function handleDeleteAgent(agent: Agent) {
  try {
    await deleteAgent(agent.id)
    // 删除成功后刷新列表
    reloadPaging()
    toast.success(`${t('home.agentName')}${t('message.deleteSuccess')}`)
  }
  catch (error: any) {
    console.error('删除智能体失败:', error)
    const errorMessage = error?.message || t('message.deleteFail')
    toast.error(errorMessage)
  }
}

// 进入编辑页面
function goToEditAgent(agent: Agent) {
  // 传递智能体ID到编辑页面
  uni.navigateTo({
    url: `/pages/agent/index?agentId=${agent.id}`,
  })
}

// 点击卡片进入编辑
function handleCardClick(agent: Agent) {
  goToEditAgent(agent)
}

// 打开创建对话框
function openCreateDialog() {
  if (!hasToken()) {
    goToLogin()
    return
  }

  message
    .prompt({
      title: t('home.dialogTitle'),
      msg: '',
      inputPlaceholder: t('home.inputPlaceholder'),
      inputValue: '',
      inputPattern: /^.{1,64}$/,
      inputError: t('home.createError'),
      confirmButtonText: t('home.createNow'),
      cancelButtonText: t('common.cancel'),
    })
    .then(async (result: any) => {
      if (result.value && String(result.value).trim()) {
        await handleCreateAgent(String(result.value).trim())
      }
    })
    .catch(() => {
      // 用户取消操作
    })
}

// 格式化时间
function formatTime(timeStr: string) {
  const date = parseDateTime(timeStr)
  if (Number.isNaN(date.getTime()))
    return timeStr

  const now = new Date()
  const diff = now.getTime() - date.getTime()

  if (diff < 60000)
    return t('home.justNow')
  if (diff < 3600000)
    return `${Math.floor(diff / 60000)}${t('home.minutesAgo')}`
  if (diff < 86400000)
    return `${Math.floor(diff / 3600000)}${t('home.hoursAgo')}`
  return `${Math.floor(diff / 86400000)}${t('home.daysAgo')}`
}

// 页面显示时刷新列表
onShow(() => {
  console.log('首页 onShow，刷新智能体列表')
  refreshPaging()
})

onMounted(() => {
  uni.setNavigationBarTitle({
    title: t('home.pageTitle'),
  })
})
</script>

<template>
  <z-paging
    ref="pagingRef" v-model="agentList" :refresher-enabled="true" :auto-show-back-to-top="true"
    :loading-more-enabled="false" :show-loading-more="false" :hide-empty-view="false" :empty-view-text="t('home.emptyState')"
    empty-view-img="" :refresher-threshold="80" :call-network-reject="false" :back-to-top-style="{
      backgroundColor: '#fff',
      borderRadius: '50%',
      width: '56px',
      height: '56px',
    }" @query="queryList"
  >
    <!-- 固定在顶部的横幅区域 -->
    <template #top>
      <view class="banner-section" :style="{ paddingTop: `${safeAreaInsets?.top + 100}rpx` }">
        <view class="banner-content">
          <view class="welcome-info">
            <text class="greeting">
              {{ t('home.greeting') }}
            </text>
            <text class="subtitle">
              {{ t('home.subtitle') }} <text class="highlight">
                {{ t('home.wonderfulDay') }}
              </text>
            </text>
          </view>
          <view class="wave-decoration">
            <!-- 添加波浪装饰 -->
            <view class="wave" />
            <view class="wave wave-2" />
          </view>
        </view>
      </view>

      <!-- 内容区域开始标识 -->
      <view class="content-section-header" />
    </template>

    <!-- 智能体卡片列表 -->
    <view class="overview-panel">
      <view class="overview-grid">
        <view v-for="item in overviewItems" :key="item.label" class="overview-item">
          <view class="overview-icon">
            <wd-icon :name="item.icon" />
          </view>
          <view class="overview-copy">
            <text class="overview-value">
              {{ item.value }}
            </text>
            <text class="overview-label">
              {{ item.label }}
            </text>
          </view>
        </view>
      </view>

      <view v-if="latestActiveAgent" class="latest-strip" @click="handleCardClick(latestActiveAgent)">
        <view class="latest-copy">
          <text class="latest-label">
            {{ t('home.latestActiveAgent') }}
          </text>
          <text class="latest-name">
            {{ latestActiveAgent.agentName }}
          </text>
        </view>
        <text class="latest-time">
          {{ formatTime(latestActiveAgent.lastConnectedAt || '') }}
        </text>
      </view>
    </view>

    <view class="quick-panel">
      <view
        v-for="action in quickActions"
        :key="action.label"
        class="quick-item"
        @click="action.action()"
      >
        <view class="quick-icon">
          <wd-icon :name="action.icon" />
        </view>
        <view class="quick-copy">
          <text class="quick-title">
            {{ action.label }}
          </text>
          <text class="quick-desc">
            {{ action.desc }}
          </text>
        </view>
        <wd-icon name="arrow-right" custom-class="quick-arrow" />
      </view>
    </view>

    <view class="section-heading">
      <text class="section-title">
        {{ t('home.agentList') }}
      </text>
      <text class="section-count">
        {{ agentList.length }} {{ t('home.agentUnit') }}
      </text>
    </view>

    <view class="agent-list">
      <view v-for="agent in agentList" :key="agent.id" class="agent-item">
        <wd-swipe-action>
          <view class="simple-card" @click="handleCardClick(agent)">
            <view class="card-content">
              <view class="card-main">
                <view class="agent-title">
                  <text class="agent-name">
                    {{ agent.agentName }}
                  </text>
                </view>

                <view class="model-info">
                  <text class="model-text">
                    {{ t('home.languageModel') }}： {{ agent.llmModelName }}
                  </text>
                  <text class="model-text">
                    {{ t('home.voiceModel') }}： {{ agent.ttsModelName }} ({{ agent.ttsVoiceName }})
                  </text>
                </view>

                <view class="stats-row">
                  <view class="stat-chip">
                    <wd-icon name="phone" custom-class="chip-icon" />
                    <text class="chip-text">
                      {{ t('home.deviceManagement') }}({{ agent.deviceCount }})
                    </text>
                  </view>
                  <view v-if="agent.lastConnectedAt" class="stat-chip">
                    <wd-icon name="time" custom-class="chip-icon" />
                    <text class="chip-text">
                      {{ t('home.lastConversation') }}{{ formatTime(agent.lastConnectedAt) }}
                    </text>
                  </view>
                  <text v-if="agent.tags" class="flex-1 truncate text-right text-[22rpx] text-[#666]">
                    {{ agent.tags.map(tag => tag.tagName).join(',') }}
                  </text>
                </view>
              </view>

              <wd-icon name="arrow-right" custom-class="arrow-icon" />
            </view>
          </view>

          <template #right>
            <view class="swipe-actions">
              <view class="action-btn delete-btn" @click.stop="handleDeleteAgent(agent)">
                <wd-icon name="delete" />
                <text>{{ t('home.delete') }}</text>
              </view>
            </view>
          </template>
        </wd-swipe-action>
      </view>
    </view>

    <!-- 自定义空状态 -->
    <template #empty>
      <view class="empty-state">
        <wd-icon name="robot" custom-class="empty-icon" />
        <text class="empty-text">
          {{ t('home.emptyState') }}
        </text>
        <text class="empty-desc">
          {{ t('home.createFirstAgent') }}
        </text>
      </view>
    </template>

    <!-- FAB 新增按钮 -->
    <wd-fab type="primary" icon="add" :draggable="true" :expandable="false" @click="openCreateDialog" />

    <!-- MessageBox 组件 -->
    <wd-message-box />
  </z-paging>
</template>

<style lang="scss" scoped>
.banner-section {
  background: linear-gradient(145deg, #9ebbfc, #6baaff, #9ebbfc, #f5f8fd);
  position: relative;
  padding: 40rpx 40rpx 80rpx 40rpx;
  overflow: hidden;

  .banner-content {
    position: relative;
    z-index: 2;
  }

  .header-actions {
    position: absolute;
    top: -50rpx;
    right: 0;
    display: flex;
    gap: 32rpx;

    .filter-icon,
    .setting-icon {
      color: white;
      cursor: pointer;
      transition: opacity 0.2s ease;

      &:active {
        opacity: 0.7;
      }
    }
  }

  .welcome-info {
    .greeting {
      display: block;
      font-size: 48rpx;
      font-weight: 700;
      color: #ffffff;
      margin-bottom: 16rpx;
      text-shadow: 0 2rpx 4rpx rgba(0, 0, 0, 0.1);
    }

    .subtitle {
      display: block;
      font-size: 32rpx;
      color: rgba(255, 255, 255, 0.9);
      margin-bottom: 12rpx;
      font-weight: 500;

      .highlight {
        color: #ffd700;
        font-weight: 600;
      }
    }

    .english-subtitle {
      display: block;
      font-size: 24rpx;
      color: rgba(255, 255, 255, 0.7);
      font-style: italic;
    }
  }

  .wave-decoration {
    position: absolute;
    top: 0;
    right: -100rpx;
    width: 400rpx;
    height: 100%;
    opacity: 0.1;
    pointer-events: none;

    .wave {
      position: absolute;
      width: 200%;
      height: 200%;
      background: radial-gradient(circle, rgba(255, 255, 255, 0.3) 0%, transparent 70%);
      border-radius: 50%;
      animation: float 6s ease-in-out infinite;

      &.wave-2 {
        top: 20%;
        right: 20%;
        animation-delay: -3s;
        opacity: 0.5;
      }
    }
  }
}

@keyframes float {
  0%,
  100% {
    transform: translateY(0px) rotate(0deg);
  }

  50% {
    transform: translateY(-30rpx) rotate(180deg);
  }
}

// 内容区域开始标识，创建白色背景过渡
.content-section-header {
  background: #ffffff;
  border-radius: 32rpx 32rpx 0 0;
  margin-top: -32rpx;
  height: 32rpx;
  position: relative;
  z-index: 1;
}

// z-paging内容区域样式
:deep(.z-paging-content) {
  background: #ffffff;
  padding: 0 0 40rpx 0;
}

.agent-list {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  padding: 0 20rpx;
}

.overview-panel {
  padding: 0 20rpx 20rpx;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12rpx;
}

.overview-item {
  min-height: 136rpx;
  padding: 20rpx 14rpx;
  background: #ffffff;
  border: 1rpx solid #edf0f5;
  border-radius: 16rpx;
  box-shadow: 0 4rpx 18rpx rgba(22, 33, 55, 0.05);
  box-sizing: border-box;
}

.overview-icon {
  width: 48rpx;
  height: 48rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12rpx;
  color: #3977d6;
  background: #edf5ff;
  border-radius: 14rpx;
}

.overview-copy {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.overview-value {
  font-size: 36rpx;
  line-height: 44rpx;
  font-weight: 700;
  color: #162137;
}

.overview-label {
  font-size: 22rpx;
  line-height: 30rpx;
  color: #6b7280;
  word-break: keep-all;
}

.latest-strip {
  min-height: 88rpx;
  margin-top: 14rpx;
  padding: 18rpx 20rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
  background: #f8fbff;
  border: 1rpx solid #e3eefc;
  border-radius: 16rpx;
  box-sizing: border-box;

  &:active {
    background: #eef6ff;
  }
}

.latest-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.latest-label {
  font-size: 22rpx;
  line-height: 30rpx;
  color: #6b7280;
}

.latest-name {
  max-width: 420rpx;
  font-size: 28rpx;
  line-height: 36rpx;
  font-weight: 600;
  color: #162137;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.latest-time {
  flex-shrink: 0;
  font-size: 24rpx;
  line-height: 32rpx;
  color: #3977d6;
}

.quick-panel {
  display: flex;
  flex-direction: column;
  gap: 14rpx;
  padding: 0 20rpx 24rpx;
}

.quick-item {
  min-height: 108rpx;
  padding: 18rpx 20rpx;
  display: flex;
  align-items: center;
  gap: 18rpx;
  background: #ffffff;
  border: 1rpx solid #edf0f5;
  border-radius: 16rpx;
  box-shadow: 0 4rpx 18rpx rgba(22, 33, 55, 0.05);
  box-sizing: border-box;

  &:active {
    background: #f8f9fb;
  }
}

.quick-icon {
  width: 56rpx;
  height: 56rpx;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #3977d6;
  background: #edf5ff;
  border-radius: 16rpx;
}

.quick-copy {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}

.quick-title {
  font-size: 28rpx;
  line-height: 36rpx;
  font-weight: 600;
  color: #162137;
}

.quick-desc {
  font-size: 22rpx;
  line-height: 30rpx;
  color: #6b7280;
}

:deep(.quick-arrow) {
  flex-shrink: 0;
  font-size: 24rpx;
  color: #a8b0bd;
}

.section-heading {
  padding: 0 24rpx 16rpx;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
}

.section-title {
  font-size: 30rpx;
  line-height: 38rpx;
  font-weight: 700;
  color: #162137;
}

.section-count {
  flex-shrink: 0;
  font-size: 24rpx;
  line-height: 32rpx;
  color: #6b7280;
}

.agent-item {
  :deep(.wd-swipe-action) {
    border-radius: 16rpx;
    overflow: hidden;
    box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);
    border: 1rpx solid #f0f0f0;
  }
}

.simple-card {
  background: #ffffff;
  padding: 24rpx;
  cursor: pointer;
  transition: all 0.2s ease;

  &:active {
    background: #f8f9fa;
  }

  .card-content {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .card-main {
    flex: 1;
    width: 100%;
  }

  .agent-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12rpx;

    .agent-name {
      font-size: 32rpx;
      font-weight: 600;
      color: #1a1a1a;
    }
  }

  .model-info {
    margin-bottom: 16rpx;

    .model-text {
      display: block;
      font-size: 24rpx;
      color: #666666;
      line-height: 1.5;
      margin-bottom: 4rpx;

      &:last-child {
        margin-bottom: 0;
      }
    }
  }

  .stats-row {
    width: 100%;
    display: flex;
    align-items: center;
    gap: 12rpx;
    flex-wrap: wrap;

    .stat-chip {
      display: flex;
      align-items: center;
      padding: 6rpx 12rpx;
      background: #f8f9fa;
      border-radius: 20rpx;
      border: 1rpx solid #eaeaea;

      :deep(.chip-icon) {
        font-size: 20rpx;
        color: #666666;
        margin-right: 6rpx;
      }

      .chip-text {
        font-size: 22rpx;
        color: #666666;
      }
    }
  }

  :deep(.arrow-icon) {
    font-size: 24rpx;
    color: #c7c7cc;
    margin-left: 16rpx;
  }
}

.swipe-actions {
  display: flex;
  height: 100%;

  .action-btn {
    width: 120rpx;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8rpx;
    color: #ffffff;
    font-size: 24rpx;
    font-weight: 500;
    transition: all 0.3s ease;

    &.edit-btn {
      background: #1890ff;

      &:active {
        background: #096dd9;
      }
    }

    &.delete-btn {
      background: #ff4d4f;

      &:active {
        background: #d9363e;
      }
    }

    :deep(.wd-icon) {
      font-size: 32rpx;
    }
  }
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 100rpx 40rpx;
  text-align: center;

  :deep(.empty-icon) {
    font-size: 120rpx;
    color: #d9d9d9;
    margin-bottom: 32rpx;
  }

  .empty-text {
    font-size: 32rpx;
    color: #666666;
    margin-bottom: 16rpx;
    font-weight: 500;
  }

  .empty-desc {
    font-size: 26rpx;
    color: #999999;
    line-height: 1.5;
  }
}

@keyframes pulse {
  0% {
    opacity: 1;
  }

  50% {
    opacity: 0.5;
  }

  100% {
    opacity: 1;
  }
}

.filter-actions {
  padding: 32rpx;
  text-align: center;
  border-top: 1rpx solid #eeeeee;
}
</style>
