<template>
  <div class="input-bar">
    <el-input
      v-model="inputText"
      :placeholder="inputPlaceholder"
      @keyup.enter.native="handleSend"
      :disabled="!connected"
      clearable />
    <el-button type="primary" icon="el-icon-position" @click="handleSend" :disabled="!connected || !inputText.trim()">
      {{ $t('deviceDebug.send') }}
    </el-button>
    <el-button
      :type="recording ? 'danger' : 'default'"
      :icon="recording ? 'el-icon-video-pause' : 'el-icon-microphone'"
      @click="$emit('toggle-recording')"
      :disabled="!connected || audioStarting">
      {{ recording ? $t('deviceDebug.stopVoice') : $t('deviceDebug.voiceInput') }}
    </el-button>
  </div>
</template>

<script>
export default {
  name: 'ChatInputPanel',
  props: {
    connected: {
      type: Boolean,
      default: false
    },
    recording: {
      type: Boolean,
      default: false
    },
    audioStarting: {
      type: Boolean,
      default: false
    },
    inputPlaceholder: {
      type: String,
      default: ''
    }
  },
  data() {
    return {
      inputText: ''
    };
  },
  methods: {
    handleSend() {
      const text = this.inputText.trim();
      if (!text) return;
      this.$emit('send', text);
      this.inputText = '';
    }
  }
};
</script>

<style scoped>
.input-bar {
  display: flex;
  gap: 10px;
  padding: 14px 16px;
  border-top: 1px solid #ebeef5;
  background: #fff;
}

.input-bar .el-input {
  flex: 1;
}
</style>
