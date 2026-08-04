<script lang="ts" setup>
import type { Agent } from '@/api/agent/types'
import type { Device, DeviceScene } from '@/api/device'
import { computed, ref, watch } from 'vue'
import { getAgentList } from '@/api/agent/agent'
import { createDeviceScene, getDeviceScenes, switchDeviceScene } from '@/api/device'
import { toast } from '@/utils/toast'

const props = defineProps<{ modelValue: boolean, device: Device | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean], 'switched': [] }>()
const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value),
})
const loading = ref(false)
const scenes = ref<DeviceScene[]>([])
const agents = ref<Agent[]>([])
const selectedAgentId = ref('')
const sceneName = ref('')
const showCreate = ref(false)
const agentColumns = computed(() => agents.value.map(item => ({ label: item.agentName, value: item.id })))

async function load() {
  if (!props.device)
    return
  loading.value = true
  try {
    const [sceneResult, agentResult] = await Promise.all([
      getDeviceScenes(props.device.id),
      getAgentList(),
    ])
    scenes.value = sceneResult || []
    agents.value = agentResult || []
  }
  catch (error: any) {
    toast.error(error?.message || '获取设备场景失败')
  }
  finally {
    loading.value = false
  }
}

async function activate(scene: DeviceScene) {
  if (!props.device || scene.current || scene.enabled === 0)
    return
  try {
    await switchDeviceScene(props.device.id, scene.id)
    toast.success('场景切换已提交')
    await load()
    emit('switched')
  }
  catch (error: any) {
    toast.error(error?.message || '场景切换失败')
  }
}

async function createScene() {
  if (!props.device || !selectedAgentId.value || !sceneName.value.trim()) {
    toast.error('请选择智能体并填写场景名称')
    return
  }
  try {
    await createDeviceScene(props.device.id, {
      agentId: selectedAgentId.value,
      sceneName: sceneName.value.trim(),
      enabled: 1,
      sort: scenes.value.length,
    })
    selectedAgentId.value = ''
    sceneName.value = ''
    showCreate.value = false
    await load()
  }
  catch (error: any) {
    toast.error(error?.message || '创建设备场景失败')
  }
}

function statusText(status?: string) {
  return ({ REQUESTED: '已请求', SCHEDULED: '等待轮次结束', APPLIED: '已生效',
    FAILED: '切换失败', ROLLED_BACK: '已回滚' } as Record<string, string>)[status || ''] || status || ''
}

watch(() => props.modelValue, (value) => { if (value) load() })
</script>

<template>
  <wd-popup v-model="visible" position="bottom" custom-style="border-radius:24rpx 24rpx 0 0;max-height:82vh;">
    <view class="p-[32rpx]">
      <view class="mb-[24rpx] flex items-center justify-between">
        <text class="text-[34rpx] font-semibold">设备场景</text>
        <view class="flex items-center gap-[20rpx]">
          <text class="text-[28rpx] text-[#336cff]" @click="showCreate = !showCreate">新增</text>
          <wd-icon name="close" size="20" @click="visible = false" />
        </view>
      </view>
      <view v-if="showCreate" class="mb-[24rpx] rounded-[16rpx] bg-[#f5f7fb] p-[20rpx]">
        <wd-picker v-model="selectedAgentId" :columns="agentColumns" label="智能体" />
        <wd-input v-model="sceneName" label="场景名称" placeholder="如英语老师" />
        <wd-button block size="small" type="primary" @click="createScene">创建场景</wd-button>
      </view>
      <view v-if="loading" class="p-[60rpx] text-center"><wd-loading /></view>
      <view v-else-if="!scenes.length" class="p-[60rpx] text-center text-[#999]">暂无场景</view>
      <view v-for="scene in scenes" v-else :key="scene.id"
        class="mb-[16rpx] rounded-[16rpx] bg-[#f5f7fb] p-[24rpx]">
        <view class="flex items-center justify-between">
          <view class="min-w-0 flex-1">
            <view class="flex items-center gap-[12rpx]">
              <text class="truncate text-[30rpx] font-medium">{{ scene.sceneName }}</text>
              <wd-tag v-if="scene.current" type="success">当前</wd-tag>
            </view>
            <text class="mt-[8rpx] block text-[25rpx] text-[#777]">{{ scene.agentName }}</text>
            <text v-if="scene.switchStatus" class="mt-[6rpx] block text-[24rpx] text-[#999]">
              {{ statusText(scene.switchStatus) }}
            </text>
          </view>
          <wd-button size="small" :disabled="scene.current || scene.enabled === 0" @click="activate(scene)">切换</wd-button>
        </view>
      </view>
    </view>
  </wd-popup>
</template>
