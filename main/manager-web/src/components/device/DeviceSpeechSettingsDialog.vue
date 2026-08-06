<template>
  <el-dialog
    :title="$t('deviceSpeech.title')"
    :visible.sync="visible"
    width="560px"
    :close-on-click-modal="false"
  >
    <div class="device-summary">
      {{ $t('deviceSpeech.device') }}：{{ deviceLabel }}
    </div>
    <el-alert
      :title="$t('deviceSpeech.compatibilityTip')"
      type="info"
      :closable="false"
      show-icon
      class="speech-tip"
    />
    <el-form label-position="top">
      <el-form-item :label="$t('deviceSpeech.inputLabel')">
        <el-radio-group v-model="form.speechInputMode">
          <el-radio-button v-for="option in modeOptions" :key="`input-${option.value}`" :label="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
        <div class="mode-description">{{ modeDescription(form.speechInputMode, 'input') }}</div>
      </el-form-item>
      <el-form-item :label="$t('deviceSpeech.outputLabel')">
        <el-radio-group v-model="form.speechOutputMode">
          <el-radio-button v-for="option in modeOptions" :key="`output-${option.value}`" :label="option.value">
            {{ option.label }}
          </el-radio-button>
        </el-radio-group>
        <div class="mode-description">{{ modeDescription(form.speechOutputMode, 'output') }}</div>
      </el-form-item>
    </el-form>
    <span slot="footer">
      <el-button @click="visible = false">{{ $t('button.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="save">{{ $t('button.save') }}</el-button>
    </span>
  </el-dialog>
</template>

<script>
import Api from '@/apis/api'
import { createDeviceSpeechRoutingPayload } from '@/utils/deviceSpeechRouting'

export default {
  name: 'DeviceSpeechSettingsDialog',
  data() {
    return {
      visible: false,
      saving: false,
      device: null,
      form: createDeviceSpeechRoutingPayload()
    }
  },
  computed: {
    deviceLabel() {
      return (this.device && (this.device.remark || this.device.macAddress)) || '-'
    },
    modeOptions() {
      return [
        { value: 'server', label: this.$t('deviceSpeech.modeServer') },
        { value: 'device', label: this.$t('deviceSpeech.modeDevice') },
        { value: 'auto', label: this.$t('deviceSpeech.modeAuto') }
      ]
    }
  },
  methods: {
    open(device) {
      this.device = device
      this.form = createDeviceSpeechRoutingPayload(device.speechInputMode, device.speechOutputMode)
      this.visible = true
    },
    modeDescription(mode, direction) {
      return this.$t(`deviceSpeech.${direction}.${mode}`)
    },
    save() {
      if (!this.device || this.saving) return
      const payload = createDeviceSpeechRoutingPayload(this.form.speechInputMode, this.form.speechOutputMode)
      this.saving = true
      Api.device.updateDeviceInfo(this.device.device_id, payload, ({ data }) => {
        this.saving = false
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || this.$t('deviceSpeech.saveFailed'))
          return
        }
        this.$message.success(this.$t('deviceSpeech.saveSuccess'))
        this.visible = false
        this.$emit('saved', { deviceId: this.device.device_id, ...payload })
      })
    }
  }
}
</script>

<style scoped>
.device-summary { margin-bottom: 14px; color: #606266; }
.speech-tip { margin-bottom: 18px; }
.mode-description { margin-top: 8px; color: #909399; font-size: 13px; line-height: 20px; }
</style>
