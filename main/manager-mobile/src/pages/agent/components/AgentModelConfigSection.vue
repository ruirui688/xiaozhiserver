<script lang="ts" setup>
import { t } from '@/i18n'

type ModelConfigKey = 'vad' | 'asr' | 'llm' | 'slm' | 'vllm' | 'intent' | 'memory' | 'report'

interface Props {
  displayNames: Record<ModelConfigKey, string>
  showReport: boolean
}

defineOptions({
  name: 'AgentModelConfigSection',
})

defineProps<Props>()
const emit = defineEmits<{
  select: [type: ModelConfigKey]
}>()

const configItems: Array<{ type: ModelConfigKey, labelKey: string }> = [
  { type: 'vad', labelKey: 'agent.vad' },
  { type: 'asr', labelKey: 'agent.asr' },
  { type: 'llm', labelKey: 'agent.llm' },
  { type: 'slm', labelKey: 'agent.slm' },
  { type: 'vllm', labelKey: 'agent.vllm' },
  { type: 'intent', labelKey: 'agent.intent' },
  { type: 'memory', labelKey: 'agent.memory' },
  { type: 'report', labelKey: 'agent.reportMode' },
]
</script>

<template>
  <view>
    <view class="pb-[20rpx]">
      <text class="text-[32rpx] text-[#232338] font-bold">
        {{ t('agent.modelConfig') }}
      </text>
    </view>

    <view class="mb-[24rpx] border border-[#eeeeee] rounded-[20rpx] bg-[#fbfbfb] p-[24rpx]" style="box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.04);">
      <view class="flex flex-col gap-[16rpx]">
        <view
          v-for="item in configItems"
          v-show="item.type !== 'report' || showReport"
          :key="item.type"
          class="flex cursor-pointer items-center justify-between border border-[#eeeeee] rounded-[12rpx] bg-[#f5f7fb] p-[20rpx] transition-all duration-300 active:bg-[#eef3ff]"
          @click="emit('select', item.type)"
        >
          <text class="text-[28rpx] text-[#232338] font-medium">
            {{ t(item.labelKey) }}
          </text>
          <text class="mx-[16rpx] flex-1 text-right text-[26rpx] text-[#65686f]">
            {{ displayNames[item.type] }}
          </text>
          <wd-icon name="arrow-right" custom-class="text-[20rpx] text-[#9d9ea3]" />
        </view>
      </view>
    </view>
  </view>
</template>
