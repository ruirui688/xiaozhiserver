<template>
  <div class="memory-attribution">
    <el-form-item label="可见范围">
      <el-select :value="value.visibility" :disabled="disabled" @input="setVisibility">
        <el-option label="共享记忆" value="shared" />
        <el-option label="指定人物私有" value="private" />
      </el-select>
      <p class="hint">共享记忆可供当前记忆空间内的所有人使用；私有记忆仅在识别到对应人物时使用。</p>
    </el-form-item>
    <el-form-item v-if="value.visibility === 'private'" label="所属人物" required>
      <el-select :value="value.speakerId" :disabled="disabled" placeholder="选择已绑定声纹的人物" filterable @input="update('speakerId', $event)">
        <el-option v-for="speaker in speakers" :key="speaker.speakerId" :value="speaker.speakerId" :label="speaker.label" />
      </el-select>
      <p v-if="!speakers.length" class="hint">请先在智能体中绑定人物声纹。</p>
    </el-form-item>
    <el-form-item v-if="memoryScope === 'DEVICE'" label="所属设备" required>
      <el-select :value="value.deviceId" :disabled="disabled" placeholder="选择设备" filterable @input="update('deviceId', $event)">
        <el-option v-for="device in devices" :key="device.deviceId" :value="device.deviceId" :label="device.label" />
      </el-select>
      <p class="hint">这条记忆仅用于所选设备，不会在同一智能体的其他设备间共享。</p>
    </el-form-item>
  </div>
</template>

<script>
export default {
  name: 'MemoryAttributionFields',
  props: {
    value: { type: Object, required: true },
    memoryScope: { type: String, default: 'AGENT' },
    speakers: { type: Array, default: () => [] },
    devices: { type: Array, default: () => [] },
    disabled: { type: Boolean, default: false }
  },
  methods: {
    update(key, value) { this.$emit('input', { ...this.value, [key]: value }) },
    setVisibility(visibility) {
      this.$emit('input', { ...this.value, visibility, speakerId: visibility === 'shared' ? '' : this.value.speakerId })
    }
  }
}
</script>

<style scoped>
.hint { color: #718096; font-size: 12px; line-height: 1.6; margin: 6px 0 0; }
</style>
