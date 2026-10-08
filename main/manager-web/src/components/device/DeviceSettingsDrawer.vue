<template>
  <el-drawer title="设备设置" :visible.sync="visible" size="min(480px, 95vw)" :wrapper-closable="!saving" :before-close="close">
    <div v-if="device" class="settings-body">
      <h2>{{ device.alias || '未命名设备' }}</h2>
      <p class="settings-agent">使用的智能体：{{ device.agentName || '未配置' }}</p>
      <section><h3>设备信息</h3><dl><dt>型号</dt><dd>{{ device.board || '未知型号' }}</dd><dt>固件版本</dt><dd>{{ device.appVersion || '暂无信息' }}</dd><dt>MAC 地址</dt><dd><MacAddressMask :mac-address="device.macAddress" /></dd></dl></section>
      <section><h3>语音与场景</h3><div class="settings-links">
        <el-button @click="$refs.audio.open(row)">真实音频</el-button>
        <el-button :disabled="!canWrite || saving" @click="$refs.speech.open(row)">语音链路</el-button>
        <el-button :disabled="!canWrite || saving" @click="$refs.scenes.open(row)">场景管理</el-button>
      </div></section>
      <section class="settings-switch"><div><h3>固件自动升级</h3><p>允许设备自动更新固件</p></div><el-switch :value="device.autoUpdate === 1" :disabled="!canWrite || saving" @change="updateOta" /></section>
      <p v-if="error" class="settings-error" role="alert">{{ error }}</p>
      <section><h3>解除关联</h3><p>解绑后，需要重新绑定才能继续使用。</p><el-button type="danger" plain :disabled="!canWrite || saving" @click="unbind">解绑设备</el-button></section>
    </div>
    <DeviceAudioPreviewDialog ref="audio" />
    <DeviceSpeechSettingsDialog ref="speech" @saved="speechSaved" />
    <DeviceSceneDialog ref="scenes" @switched="visible = false; $emit('refresh')" />
  </el-drawer>
</template>
<script>
import Api from '@/apis/api';
import { deviceRequest } from '@/utils/deviceCenter';
import MacAddressMask from '@/components/MacAddressMask.vue';
import DeviceAudioPreviewDialog from '@/components/DeviceAudioPreviewDialog.vue';
import DeviceSpeechSettingsDialog from '@/components/device/DeviceSpeechSettingsDialog.vue';
import DeviceSceneDialog from '@/components/scene/DeviceSceneDialog.vue';
export default {
  components: { MacAddressMask, DeviceAudioPreviewDialog, DeviceSpeechSettingsDialog, DeviceSceneDialog },
  props: { canWrite: Boolean },
  data() { return { visible: false, device: null, saving: false, error: '' }; },
  computed: { row() { return this.device ? { ...this.device, device_id: this.device.id, remark: this.device.alias } : {}; } },
  methods: {
    open(device) { if (this.saving) return; this.device = device; this.error = ''; this.visible = true; },
    close(done) { if (!this.saving) done(); },
    async updateOta(value) {
      if (!this.canWrite || this.saving) return;
      this.saving = true; this.error = '';
      try { await deviceRequest(done => Api.device.updateDeviceInfo(this.device.id, { autoUpdate: value ? 1 : 0 }, done)); this.$set(this.device, 'autoUpdate', value ? 1 : 0); }
      catch (error) { this.error = error.message; }
      finally { this.saving = false; }
    },
    speechSaved({ speechInputMode, speechOutputMode }) { this.$set(this.device, 'speechInputMode', speechInputMode); this.$set(this.device, 'speechOutputMode', speechOutputMode); },
    async unbind() {
      if (!this.canWrite || this.saving) return;
      try { await this.$confirm(`确认解绑“${this.device.alias || '未命名设备'}”？`, '解绑设备', { type: 'warning', confirmButtonText: '确认解绑', cancelButtonText: '取消' }); }
      catch (_) { return; }
      if (!this.canWrite || this.saving) return;
      this.saving = true; this.error = '';
      try { await deviceRequest(done => Api.device.unbindDevice(this.device.id, done)); this.visible = false; this.$emit('refresh'); }
      catch (error) { this.error = error.message; }
      finally { this.saving = false; }
    }
  }
};
</script>
<style scoped>
.settings-body { padding: 0 28px 28px; overflow-y: auto; height: calc(100vh - 90px); box-sizing: border-box; }.settings-body h2 { margin: 4px 0 12px; color: #343a4d; overflow-wrap: anywhere; }.settings-agent { color: #8b92a1; }.settings-body section { border-top: 1px solid #edf0f5; margin-top: 26px; padding-top: 20px; }.settings-body h3 { font-size: 14px; font-weight: 500; margin: 0 0 16px; }.settings-body p { font-size: 13px; line-height: 1.7; color: #8b92a1; }dl { display: grid; grid-template-columns: 90px 1fr; gap: 16px; font-size: 13px; }dt { color: #969dab; }dd { margin: 0; overflow-wrap: anywhere; }.settings-links { display: flex; flex-wrap: wrap; gap: 10px; }.settings-links .el-button { margin: 0; }.settings-switch { display: flex; justify-content: space-between; align-items: center; }.settings-body .settings-error { color: #c44d4d; }
</style>
