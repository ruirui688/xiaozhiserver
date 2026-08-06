<script lang="ts" setup>
import type { Device, SpeechRouteMode } from '@/api/device'
import { computed, ref, watch } from 'vue'
import { updateDeviceSpeechRouting } from '@/api/device'
import { t } from '@/i18n'
import { createDeviceSpeechRoutingPayload } from '@/utils/deviceSpeechRouting'
import { toast } from '@/utils/toast'

const props = defineProps<{ modelValue: boolean, device: Device | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean], 'saved': [] }>()
const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value),
})
const saving = ref(false)
const inputMode = ref<SpeechRouteMode>('server')
const outputMode = ref<SpeechRouteMode>('server')
const modeColumns = computed(() => [
  { label: t('deviceSpeech.modeServer'), value: 'server' },
  { label: t('deviceSpeech.modeDevice'), value: 'device' },
  { label: t('deviceSpeech.modeAuto'), value: 'auto' },
])

function modeDescription(direction: 'input' | 'output', mode: SpeechRouteMode) {
  return t(`deviceSpeech.${direction}.${mode}`)
}

async function save() {
  if (!props.device || saving.value)
    return
  saving.value = true
  try {
    const payload = createDeviceSpeechRoutingPayload(inputMode.value, outputMode.value)
    await updateDeviceSpeechRouting(props.device.id, payload)
    toast.success(t('deviceSpeech.saveSuccess'))
    visible.value = false
    emit('saved')
  }
  catch (error: any) {
    toast.error(error?.message || t('deviceSpeech.saveFailed'))
  }
  finally {
    saving.value = false
  }
}

watch(() => props.modelValue, (value) => {
  if (!value)
    return
  const payload = createDeviceSpeechRoutingPayload(
    props.device?.speechInputMode,
    props.device?.speechOutputMode,
  )
  inputMode.value = payload.speechInputMode
  outputMode.value = payload.speechOutputMode
})
</script>

<template>
  <wd-popup v-model="visible" position="bottom" custom-style="border-radius:24rpx 24rpx 0 0;">
    <view class="p-[32rpx]">
      <view class="mb-[24rpx] flex items-center justify-between">
        <text class="text-[34rpx] font-semibold">{{ t('deviceSpeech.title') }}</text>
        <wd-icon name="close" size="20" @click="visible = false" />
      </view>
      <view class="mb-[24rpx] rounded-[16rpx] bg-[#eef4ff] p-[20rpx] text-[25rpx] text-[#52627a] leading-[1.6]">
        {{ t('deviceSpeech.compatibilityTip') }}
      </view>
      <view class="mb-[24rpx] rounded-[16rpx] bg-[#f5f7fb] p-[20rpx]">
        <wd-picker v-model="inputMode" :columns="modeColumns" :label="t('deviceSpeech.inputLabel')" />
        <text class="mt-[10rpx] block text-[24rpx] text-[#888] leading-[1.5]">
          {{ modeDescription('input', inputMode) }}
        </text>
      </view>
      <view class="mb-[32rpx] rounded-[16rpx] bg-[#f5f7fb] p-[20rpx]">
        <wd-picker v-model="outputMode" :columns="modeColumns" :label="t('deviceSpeech.outputLabel')" />
        <text class="mt-[10rpx] block text-[24rpx] text-[#888] leading-[1.5]">
          {{ modeDescription('output', outputMode) }}
        </text>
      </view>
      <wd-button block type="primary" :loading="saving" @click="save">
        {{ t('deviceSpeech.save') }}
      </wd-button>
    </view>
  </wd-popup>
</template>
