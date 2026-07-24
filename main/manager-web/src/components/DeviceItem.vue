<template>
  <div class="device-item">
    <div style="display: flex;justify-content: space-between;">
    <el-tooltip :content="device.agentName" placement="top" effect="light">
      <div class="device-item-title">
        {{ device.agentName }}
      </div>
    </el-tooltip>
      <div>
        <img v-if="canWrite" src="@/assets/home/delete.png" alt="" style="width: 18px;height: 18px;margin-right: 10px;"
          @click.stop="handleDelete" />
        <el-tooltip class="item" effect="light" :content="device.systemPrompt" placement="top"
          popper-class="device-item-tooltip"> 
          <img src="@/assets/home/info.png" alt="" style="width: 18px;height: 18px;" />
        </el-tooltip>
      </div>
    </div>
    <div class="device-name">
      {{ $t('home.languageModel') }}：{{ device.llmModelName }}
    </div>
    <div class="device-name">
      {{ $t('home.voiceModel') }}：{{ device.ttsModelName }} ({{ device.ttsVoiceName }})
    </div>
    <div class="storage-usage">
      <div class="storage-usage-label">
        <span>{{ $t('home.chatStorage') }}</span>
        <span>{{ formatBytes(device.storageBytes) }} / {{ formatBytes(device.storageLimitBytes) }}</span>
      </div>
      <el-progress :percentage="storagePercentage" :stroke-width="6" :show-text="false"
        :color="storageProgressColor" />
    </div>
    <div class="device-actions">
      <div v-if="canWrite" class="settings-btn" @click="handleConfigure">
        {{ $t('home.configureRole') }}
      </div>
      <div v-if="canWrite && featureStatus.voiceprintRecognition" class="settings-btn" @click="handleVoicePrint">
        {{ $t('home.voiceprintRecognition') }}
      </div>
      <div class="settings-btn" @click="handleDeviceManage">
        {{ $t('home.deviceManagement') }}({{ device.deviceCount }})
      </div>
      <div :class="['settings-btn', { 'disabled-btn': !isMemoryEnabled }]"
        @click="handleChatHistory">
        <el-tooltip effect="light" v-if="!isMemoryEnabled" :content="$t('home.enableMemory')" placement="top">
          <span>{{ $t('home.chatHistory') }}</span>
        </el-tooltip>
        <span v-else>{{ $t('home.chatHistory') }}</span>
      </div>
      <div v-if="isGovernedMemory" class="settings-btn"
        @click="handleMemoryManage">
        长期记忆
      </div>
    </div>
    <div class="version-info">
      <div>{{ $t('home.lastConversation') }}：{{ formattedLastConnectedTime }}</div>
      <el-tooltip :content="tags.join()" placement="top" effect="light">
        <div class="version-info-scroll">
          {{ tags.join() }}
        </div>
      </el-tooltip>
    </div>
  </div>
</template>

<script>
import i18n from '@/i18n';

export default {
  name: 'DeviceItem',
  props: {
    device: { type: Object, required: true },
    featureStatus: { 
      type: Object, 
      default: () => ({
        voiceprintRecognition: false,
        voiceClone: false,
        knowledgeBase: false
      })
    },
    canWrite: { type: Boolean, default: true }
  },
  data() {
    return { switchValue: false }
  },
  computed: {
    formattedLastConnectedTime() {
      if (!this.device.lastConnectedAt) return this.$t('home.noConversation');

      const lastTime = new Date(this.device.lastConnectedAt);
      const now = new Date();
      const diffMinutes = Math.floor((now - lastTime) / (1000 * 60));

      if (diffMinutes <= 1) {
        return this.$t('home.justNow');
      } else if (diffMinutes < 60) {
        return this.$t('home.minutesAgo', { minutes: diffMinutes });
      } else if (diffMinutes < 24 * 60) {
        const hours = Math.floor(diffMinutes / 60);
        const minutes = diffMinutes % 60;
        return this.$t('home.hoursAgo', { hours, minutes });
      } else {
        return this.device.lastConnectedAt;
      }
    },
    tags() {
      if (!this.device.tags) return [];
      return this.device.tags.map((tag) => tag.tagName);
    },
    isMemoryEnabled() {
      return Boolean(this.device.memModelId && this.device.memModelId !== 'Memory_nomem');
    },
    isGovernedMemory() {
      return this.device.memModelId === 'Memory_managed_memory';
    },
    storagePercentage() {
      const used = Math.max(Number(this.device.storageBytes) || 0, 0);
      const limit = Math.max(Number(this.device.storageLimitBytes) || 0, 0);
      if (!limit) return 0;
      return Math.min(Math.round((used / limit) * 100), 100);
    },
    storageProgressColor() {
      if (this.storagePercentage >= 100) return '#f56c6c';
      if (this.storagePercentage >= 80) return '#e6a23c';
      return '#5778ff';
    }
  },
  methods: {
    formatBytes(bytes) {
      const value = Number(bytes) || 0;
      if (value < 1024) return `${value} B`;
      if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
      if (value < 1024 * 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
      return `${(value / (1024 * 1024 * 1024)).toFixed(1)} GB`;
    },
    handleDelete() {
      this.$emit('delete', this.device.agentId)
    },
    handleConfigure() {
      this.$router.push({ path: '/role-config', query: { agentId: this.device.agentId } });
    },
    handleVoicePrint() {
      this.$router.push({ path: '/voice-print', query: { agentId: this.device.agentId } });
    },
    handleDeviceManage() {
      this.$router.push({ path: '/device-management', query: { agentId: this.device.agentId } });
    },
    handleChatHistory() {
      if (!this.isMemoryEnabled) {
        return
      }
      this.$emit('chat-history', { agentId: this.device.agentId, agentName: this.device.agentName })
    },
    handleMemoryManage() {
      if (!this.isGovernedMemory) {
        return
      }
      this.$router.push({ path: '/memory-management', query: { agentId: this.device.agentId, agentName: this.device.agentName } });
    }
  },
}
</script>
<style lang="scss" scoped>
.device-item {
  width: 342px;
  border-radius: 20px;
  background: #fafcfe;
  padding: 22px 22px 14px;
  box-sizing: border-box;
  &-title {
    flex: 1;
    font-weight: bold;
    font-size: 18px;
    color: #3d4566;
    text-align: left;
    text-overflow: ellipsis;
    white-space: nowrap;
    overflow: hidden;
  }
}

.device-name {
  margin: 7px 0 10px;
  font-weight: 400;
  font-size: 11px;
  color: #3d4566;
  text-align: left;
}

.storage-usage {
  margin: 7px 0 12px;
}

.storage-usage-label {
  display: flex;
  justify-content: space-between;
  margin-bottom: 5px;
  color: #697592;
  font-size: 11px;
}

.settings-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  font-weight: 500;
  font-size: 12px;
  color: #5778ff;
  background: #e6ebff;
  width: auto;
  padding: 0 12px;
  height: 21px;
  line-height: 21px;
  cursor: pointer;
  border-radius: 14px;
  white-space: nowrap;
}

.device-actions {
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  align-items: center;
  gap: 7px 8px;
  min-height: 49px;
}

.version-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
  font-size: 12px;
  color: #979db1;
  font-weight: 400;
  &-scroll {
    margin-left: 20px;
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    text-wrap: nowrap;
    text-align: right;
  }
}

.more-tag {
  cursor: pointer;
  flex-shrink: 0;
}

.all-tags-popover {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.disabled-btn {
  background: #e6e6e6;
  color: #999;
  cursor: not-allowed;
}
</style>

<style>
.device-item-tooltip {
  max-height: 60vh !important;
  max-width: 400px !important;
  overflow-y: auto !important;
  scrollbar-width: thin;
  word-break: break-word;
}

.device-item-tooltip .popper__arrow {
  display: none !important;
}

.device-item-tooltip[x-placement^="top"] .popper__arrow {
  border-top-color: transparent !important;
}

.device-item-tooltip[x-placement^="bottom"] .popper__arrow {
  border-bottom-color: transparent !important;
}
</style>
