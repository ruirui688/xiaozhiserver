<template>
  <div ref="messagePane" class="message-pane">
    <div v-if="messages.length === 0" class="empty-chat">
      {{ emptyChatText }}
    </div>
    <div v-for="message in messages" :key="message.id" class="chat-row" :class="message.role">
      <img class="chat-avatar" :src="message.role === 'assistant' ? botAvatar : userAvatar" />
      <div class="chat-bubble">
        <div v-if="message.content" class="chat-text">{{ message.content }}</div>
        <div v-if="message.imageUrl" class="chat-image-wrapper">
          <el-image
            class="chat-image"
            :src="message.imageUrl"
            :preview-src-list="[message.imageUrl]"
            fit="cover">
            <div slot="error" class="image-error">
              {{ message.imageUrl }}
            </div>
          </el-image>
        </div>
        <div class="chat-meta">
          <span class="chat-time">{{ message.time }}</span>
          <i
            v-if="message.audioClipId"
            :class="getAudioIconClass(message.audioClipId)"
            class="chat-audio-icon"
            @click="$emit('play-audio', message.audioClipId)">
          </i>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'ChatMessageList',
  props: {
    messages: {
      type: Array,
      required: true
    },
    connected: {
      type: Boolean,
      default: false
    },
    emptyChatText: {
      type: String,
      default: ''
    },
    playingAudioClipId: {
      type: String,
      default: null
    }
  },
  data() {
    return {
      botAvatar: require('@/assets/xiaozhi-logo.png'),
      userAvatar: require('@/assets/user-avatar1.png')
    };
  },
  methods: {
    getAudioIconClass(clipId) {
      return this.playingAudioClipId === clipId ? 'el-icon-loading' : 'el-icon-video-play';
    }
  },
  watch: {
    messages: {
      handler() {
        this.$nextTick(() => {
          const pane = this.$refs.messagePane;
          if (pane) pane.scrollTop = pane.scrollHeight;
        });
      },
      deep: true
    }
  }
};
</script>

<style scoped>
.message-pane {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  background: #fff;
}

.empty-chat {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
}

.chat-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 18px;
}

.chat-row.user {
  flex-direction: row-reverse;
}

.chat-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
}

.chat-bubble {
  max-width: 68%;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f2f3f5;
  color: #303133;
  line-height: 20px;
  text-align: left;
}

.chat-row.user .chat-bubble {
  background: #1890ff;
  color: white;
}

.chat-text {
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-image-wrapper {
  margin-top: 6px;
}

.chat-image {
  display: block;
  width: 220px;
  max-width: 100%;
  height: 150px;
  border-radius: 6px;
  overflow: hidden;
  cursor: zoom-in;
  background-color: #f5f7fa;
}

.image-error {
  width: 220px;
  max-width: 100%;
  min-height: 80px;
  box-sizing: border-box;
  padding: 12px;
  color: #909399;
  word-break: break-all;
  background-color: #f5f7fa;
}

.chat-meta {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-row.user .chat-meta {
  justify-content: flex-end;
}

.chat-time {
  font-size: 11px;
  color: #909399;
}

.chat-row.user .chat-time {
  color: rgba(255, 255, 255, 0.75);
}

.chat-audio-icon {
  color: #1890ff;
  font-size: 17px;
  cursor: pointer;
}

.chat-row.user .chat-audio-icon {
  color: rgba(255, 255, 255, 0.85);
}
</style>
