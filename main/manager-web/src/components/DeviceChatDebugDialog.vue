<template>
  <el-dialog
    :title="$t('deviceDebug.title')"
    :visible.sync="dialogVisible"
    width="86%"
    custom-class="device-chat-debug-dialog"
    :before-close="handleClose">
    <div class="debug-shell">
      <aside class="debug-side">
        <div class="status-block">
          <div class="status-title">{{ deviceLabel }}</div>
          <el-tag :type="connected ? 'success' : 'info'" size="small">
            {{ connected ? $t('deviceDebug.connected') : $t('deviceDebug.disconnected') }}
          </el-tag>
        </div>
        <div class="side-actions">
          <el-button size="mini" type="primary" :loading="connecting" @click="connectDebug" :disabled="connected">
            {{ $t('deviceDebug.connect') }}
          </el-button>
          <el-button size="mini" @click="disconnectDebug" :disabled="!connected">
            {{ $t('deviceDebug.disconnect') }}
          </el-button>
        </div>
        <div class="side-section">
          <div class="side-heading">{{ $t('deviceDebug.mcpTools') }}</div>
          <div v-if="toolsLoading" class="side-muted">{{ $t('deviceDebug.loadingTools') }}</div>
          <div v-else-if="mcpTools.length === 0" class="side-muted">{{ mcpToolsStatusText }}</div>
          <div v-else class="tool-list">
            <el-tag v-for="tool in mcpTools" :key="tool.name || tool" size="mini">
              {{ tool.name || tool }}
            </el-tag>
          </div>
        </div>
        <div class="side-section raw-section">
          <div class="side-heading">{{ $t('deviceDebug.events') }}</div>
          <div class="event-list">
            <div v-for="event in events" :key="event.id" class="event-row">
              <span>{{ event.text }}</span>
              <i
                v-if="event.audioClipId"
                :class="getAudioIconClass(event.audioClipId)"
                class="event-audio-icon"
                @click="playAudioClip(event.audioClipId)">
              </i>
            </div>
          </div>
        </div>
      </aside>
      <main class="debug-main">
        <div ref="messagePane" class="message-pane">
          <div v-if="messages.length === 0" class="empty-chat">
            {{ $t('deviceDebug.empty') }}
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
                  @click="playAudioClip(message.audioClipId)">
                </i>
              </div>
            </div>
          </div>
        </div>
        <div class="input-bar">
          <el-input
            v-model="inputText"
            :placeholder="$t('deviceDebug.inputPlaceholder')"
            @keyup.enter.native="sendText"
            :disabled="!connected"
            clearable />
          <el-button type="primary" icon="el-icon-position" @click="sendText" :disabled="!connected || !inputText.trim()">
            {{ $t('deviceDebug.send') }}
          </el-button>
          <el-button
            :type="recording ? 'danger' : 'default'"
            :icon="recording ? 'el-icon-video-pause' : 'el-icon-microphone'"
            @click="toggleRecording"
            :disabled="!connected || audioStarting">
            {{ recording ? $t('deviceDebug.stopVoice') : $t('deviceDebug.voiceInput') }}
          </el-button>
        </div>
      </main>
    </div>
  </el-dialog>
</template>

<script>
import Api from '@/apis/api';
import { OpusDecoder } from 'opus-decoder';

const INPUT_SAMPLE_RATE = 16000;
const PCM_FRAME_SAMPLES = 960;
const HEARTBEAT_INTERVAL_MS = 25000;
const TOOL_REFRESH_INTERVAL_MS = 10000;
const PENDING_CONFIRM_WINDOW_MS = 10000;

export default {
  name: 'DeviceChatDebugDialog',
  props: {
    visible: {
      type: Boolean,
      default: false
    },
    device: {
      type: Object,
      default: () => ({})
    }
  },
  data() {
    return {
      dialogVisible: false,
      debugInfo: null,
      ws: null,
      connected: false,
      connecting: false,
      inputText: '',
      messages: [],
      events: [],
      mcpTools: [],
      mcpToolsStatus: '',
      toolsLoading: false,
      heartbeatTimer: null,
      toolRefreshTimer: null,
      opusDecoder: null,
      opusDecoderReady: null,
      playbackAudioContext: null,
      playbackCursor: 0,
      activeAssistantMessageId: null,
      activeAudioClipId: null,
      audioClips: {},
      playingAudioClipId: null,
      audioContext: null,
      audioStream: null,
      mediaSource: null,
      processor: null,
      pcmRemainder: new Int16Array(0),
      recording: false,
      audioStarting: false,
      botAvatar: require('@/assets/xiaozhi-logo.png'),
      userAvatar: require('@/assets/user-avatar1.png')
    };
  },
  computed: {
    deviceId() {
      return this.device.device_id || this.device.id || '';
    },
    deviceLabel() {
      return this.device.remark || this.device.macAddress || this.$t('deviceDebug.device');
    },
    mcpToolsStatusText() {
      return this.mcpToolsStatus || this.$t('deviceDebug.noTools');
    }
  },
  watch: {
    visible(val) {
      this.dialogVisible = val;
      if (val) {
        this.resetDialog();
        this.loadTools();
      } else {
        this.disconnectDebug();
      }
    },
    dialogVisible(val) {
      if (!val) {
        this.$emit('update:visible', false);
      }
    }
  },
  methods: {
    resetDialog() {
      this.debugInfo = null;
      this.messages = [];
      this.events = [];
      this.mcpTools = [];
      this.mcpToolsStatus = '';
      this.inputText = '';
      this.pcmRemainder = new Int16Array(0);
      this.activeAssistantMessageId = null;
      this.activeAudioClipId = null;
      this.audioClips = {};
      this.playingAudioClipId = null;
      this.stopHeartbeat();
      this.stopToolRefresh();
      this.releasePlayback();
    },
    handleClose() {
      this.disconnectDebug();
      this.dialogVisible = false;
    },
    loadTools() {
      if (!this.deviceId) return;
      this.toolsLoading = true;
      this.fetchDeviceTools().finally(() => {
        this.toolsLoading = false;
      });
    },
    fetchDeviceTools() {
      if (!this.deviceId) {
        this.mcpTools = [];
        return Promise.resolve([]);
      }
      return new Promise((resolve) => {
        Api.device.getDeviceTools(this.deviceId, (res) => {
          const data = res && res.data && res.data.data;
          const tools = data && Array.isArray(data.tools) ? data.tools : [];
          this.mcpTools = tools;
          this.mcpToolsStatus = this.resolveToolsStatus(res, data, tools);
          this.addEvent(`mcp tools/list -> ${tools.length}`);
          resolve(tools);
        });
      });
    },
    resolveToolsStatus(res, data, tools) {
      if (tools.length > 0) return '';
      if (!res || !res.data) return this.$t('deviceDebug.noTools');
      if (res.data.code !== 0) return res.data.msg || this.$t('deviceDebug.noTools');
      if (data && data.message) return data.message;
      if (data && data.ready === false) return this.$t('deviceDebug.mcpNotReady');
      return this.$t('deviceDebug.noTools');
    },
    connectDebug() {
      if (!this.deviceId || this.connected || this.connecting) return;
      this.connecting = true;
      Api.device.getDeviceDebugInfo(this.deviceId, (res) => {
        const data = res && res.data && res.data.data;
        if (!data || !data.websocketUrl) {
          this.connecting = false;
          this.$message.error((res && res.data && res.data.msg) || this.$t('deviceDebug.connectFailed'));
          return;
        }
        this.debugInfo = data;
        this.openWebSocket(data);
      });
    },
    openWebSocket(info) {
      const url = this.buildDebugUrl(info);
      this.addEvent(`connect ${url}`);
      this.ws = new WebSocket(url);
      this.ws.binaryType = 'arraybuffer';
      this.ws.onopen = () => {
        this.connected = true;
        this.connecting = false;
        this.sendHello();
        this.startHeartbeat();
        this.startToolRefresh();
        this.addEvent('websocket opened');
      };
      this.ws.onmessage = (event) => this.handleSocketMessage(event.data);
      this.ws.onerror = () => {
        this.connecting = false;
        this.$message.error(this.$t('deviceDebug.connectFailed'));
        this.addEvent('websocket error');
      };
      this.ws.onclose = () => {
        this.connected = false;
        this.connecting = false;
        this.stopHeartbeat();
        this.stopToolRefresh();
        this.finishActiveAudioClip();
        this.stopRecording();
        this.addEvent('websocket closed');
      };
    },
    buildDebugUrl(info) {
      const url = new URL(info.websocketUrl);
      url.searchParams.set('device-id', info.macAddress);
      url.searchParams.set('client-id', info.clientId || info.deviceId || info.macAddress);
      if (info.token) {
        url.searchParams.set('authorization', `Bearer ${info.token}`);
      }
      return url.toString();
    },
    sendHello() {
      this.sendJson({
        type: 'hello',
        version: 1,
        transport: 'websocket',
        audio_params: {
          format: 'pcm',
          sample_rate: INPUT_SAMPLE_RATE,
          channels: 1,
          frame_duration: 60
        },
        features: {
          mcp: true,
          emoji: true
        }
      });
    },
    sendText() {
      const text = this.inputText.trim();
      if (!text || !this.connected) return;
      this.addMessage('user', text, { pending: true, source: 'local-text', createdAt: Date.now() });
      this.sendJson({ type: 'listen', state: 'detect', text });
      this.inputText = '';
    },
    sendJson(payload) {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
      this.ws.send(JSON.stringify(payload));
      this.addEvent(`send ${payload.type}/${payload.state || payload.method || ''}`);
    },
    handleSocketMessage(data) {
      if (typeof data !== 'string') {
        this.handleAudioFrame(data);
        return;
      }
      this.addEvent(data);
      let payload;
      try {
        payload = JSON.parse(data);
      } catch (e) {
        return;
      }
      if (payload.type === 'stt' && payload.text) {
        if (!this.confirmPendingUserMessage(payload.text)) {
          this.addMessage('user', payload.text);
        }
      } else if (payload.type === 'tts') {
        this.handleTtsMessage(payload);
      } else if (payload.type === 'display' && payload.text) {
        this.addMessage('assistant', payload.text);
      } else if (payload.type === 'mcp') {
        this.handleMcpMessage(payload.payload);
      }
    },
    handleMcpMessage(payload) {
      if (!payload || !payload.method) return;
      if (payload.method === 'initialize') {
        this.addEvent('mcp initialize');
        this.sendJson({
          type: 'mcp',
          payload: {
            jsonrpc: '2.0',
            id: payload.id,
            result: {
              protocolVersion: '2024-11-05',
              capabilities: {},
              serverInfo: { name: 'ManagerWebDebug', version: '1.0.0' }
            }
          }
        });
      } else if (payload.method === 'tools/list') {
        this.addEvent('mcp tools/list requested');
        this.respondMcpToolsList(payload);
      } else if (payload.method === 'tools/call') {
        const params = payload.params || {};
        this.addEvent(`mcp tools/call -> ${params.name || ''}`);
        this.forwardToolCall(payload);
      }
    },
    respondMcpToolsList(payload) {
      this.fetchDeviceTools().then((tools) => {
        this.sendJson({
          type: 'mcp',
          payload: {
            jsonrpc: '2.0',
            id: payload.id,
            result: { tools }
          }
        });
      });
    },
    forwardToolCall(payload) {
      const params = payload.params || {};
      Api.device.callDeviceTool(this.deviceId, {
        name: params.name,
        arguments: params.arguments || {}
      }, (res) => {
        const ok = res && res.data && res.data.code === 0;
        if (!ok) {
          this.addEvent(`mcp tools/call error <- ${params.name || ''}`);
          this.sendJson({
            type: 'mcp',
            payload: {
              jsonrpc: '2.0',
              id: payload.id,
              error: {
                code: -32000,
                message: (res && res.data && res.data.msg) || this.$t('deviceDebug.toolCallFailed')
              }
            }
          });
          return;
        }
        const result = res.data.data;
        const imageUrl = this.extractImageFromValue(result);
        this.addEvent(`mcp tools/call result <- ${params.name || ''}: ${this.compactText(result)}`);
        if (imageUrl) {
          this.addMessage('assistant', '', { imageUrl });
        }
        this.sendJson({
          type: 'mcp',
          payload: {
            jsonrpc: '2.0',
            id: payload.id,
            result: {
              content: [{
                type: 'text',
                text: typeof result === 'string' ? result : JSON.stringify(result ?? '')
              }]
            }
          }
        });
      });
    },
    handleTtsMessage(payload) {
      if (payload.state === 'start') {
        this.startNewAudioClip();
        return;
      }
      if (payload.state === 'stop') {
        this.finishActiveAudioClip();
        return;
      }
      if (payload.text) {
        const clipId = this.activeAudioClipId || this.startNewAudioClip();
        const message = this.addMessage('assistant', payload.text, { audioClipId: clipId });
        this.activeAssistantMessageId = message.id;
        this.attachClipToMessage(clipId, message.id);
      }
    },
    startNewAudioClip() {
      const clipId = `${Date.now()}-${Math.random()}`;
      this.activeAudioClipId = clipId;
      this.activeAssistantMessageId = null;
      this.$set(this.audioClips, clipId, {
        id: clipId,
        chunks: [],
        bytes: 0,
        frames: 0,
        messageId: null,
        completed: false
      });
      return clipId;
    },
    finishActiveAudioClip() {
      const clip = this.activeAudioClipId ? this.audioClips[this.activeAudioClipId] : null;
      if (clip && !clip.completed && clip.frames > 0) {
        this.$set(clip, 'completed', true);
        this.addEvent(`tts audio ${clip.frames} frames / ${clip.bytes} bytes`, { audioClipId: clip.id });
      }
      this.activeAudioClipId = null;
      this.activeAssistantMessageId = null;
    },
    attachClipToMessage(clipId, messageId) {
      const clip = this.audioClips[clipId];
      if (clip) {
        this.$set(clip, 'messageId', messageId);
      }
      const message = this.messages.find(item => item.id === messageId);
      if (message && !message.audioClipId) {
        this.$set(message, 'audioClipId', clipId);
      }
    },
    async ensureOpusDecoder() {
      if (!this.opusDecoder) {
        this.opusDecoder = new OpusDecoder({
          sampleRate: INPUT_SAMPLE_RATE,
          channels: 1,
          streamCount: 1,
          coupledStreamCount: 0,
          channelMappingTable: [0]
        });
        this.opusDecoderReady = this.opusDecoder.ready;
      }
      await this.opusDecoderReady;
      return this.opusDecoder;
    },
    ensurePlaybackContext() {
      if (!this.playbackAudioContext) {
        this.playbackAudioContext = new (window.AudioContext || window.webkitAudioContext)();
      }
      if (this.playbackAudioContext.state === 'suspended') {
        this.playbackAudioContext.resume();
      }
      return this.playbackAudioContext;
    },
    async handleAudioFrame(data) {
      const frame = new Uint8Array(data);
      const clipId = this.activeAudioClipId || this.startNewAudioClip();
      const clip = this.audioClips[clipId];
      clip.frames += 1;
      clip.bytes += frame.byteLength;

      try {
        const decoder = await this.ensureOpusDecoder();
        const decoded = decoder.decodeFrame(frame);
        if (decoded && decoded.samplesDecoded > 0) {
          const chunk = this.cloneDecodedAudio(decoded);
          clip.chunks.push(chunk);
          this.playDecodedChunks([chunk], false);
        }
      } catch (e) {
        this.addEvent(`audio decode failed: ${e.message || e}`);
      }
    },
    cloneDecodedAudio(decoded) {
      return {
        sampleRate: decoded.sampleRate,
        channelData: decoded.channelData.map(channel => new Float32Array(channel))
      };
    },
    playDecodedChunks(chunks, resetCursor = true) {
      if (!chunks || chunks.length === 0) return;
      const context = this.ensurePlaybackContext();
      if (resetCursor) {
        this.playbackCursor = context.currentTime;
      }
      chunks.forEach(chunk => {
        const channels = chunk.channelData.length || 1;
        const frameCount = chunk.channelData[0] ? chunk.channelData[0].length : 0;
        if (!frameCount) return;
        const buffer = context.createBuffer(channels, frameCount, chunk.sampleRate || INPUT_SAMPLE_RATE);
        chunk.channelData.forEach((channel, index) => {
          if (index < buffer.numberOfChannels) {
            buffer.copyToChannel(channel, index);
          }
        });
        const source = context.createBufferSource();
        source.buffer = buffer;
        source.connect(context.destination);
        const startAt = Math.max(context.currentTime, this.playbackCursor);
        source.start(startAt);
        this.playbackCursor = startAt + buffer.duration;
      });
    },
    playAudioClip(clipId) {
      const clip = this.audioClips[clipId];
      if (!clip || !clip.chunks || clip.chunks.length === 0) return;
      this.playingAudioClipId = clipId;
      this.playDecodedChunks(clip.chunks, true);
      const duration = clip.chunks.reduce((sum, chunk) => {
        const length = chunk.channelData[0] ? chunk.channelData[0].length : 0;
        return sum + length / (chunk.sampleRate || INPUT_SAMPLE_RATE);
      }, 0);
      setTimeout(() => {
        if (this.playingAudioClipId === clipId) {
          this.playingAudioClipId = null;
        }
      }, Math.max(300, duration * 1000));
    },
    getAudioIconClass(clipId) {
      return this.playingAudioClipId === clipId ? 'el-icon-loading' : 'el-icon-video-play';
    },
    releasePlayback() {
      if (this.playbackAudioContext) {
        this.playbackAudioContext.close();
        this.playbackAudioContext = null;
      }
      if (this.opusDecoder) {
        this.opusDecoder.free();
        this.opusDecoder = null;
        this.opusDecoderReady = null;
      }
      this.playbackCursor = 0;
    },
    addMessage(role, content, extra = {}) {
      const message = {
        id: `${Date.now()}-${Math.random()}`,
        role,
        content,
        time: new Date().toLocaleTimeString(),
        ...extra
      };
      this.messages.push(message);
      this.$nextTick(() => {
        const pane = this.$refs.messagePane;
        if (pane) pane.scrollTop = pane.scrollHeight;
      });
      return message;
    },
    confirmPendingUserMessage(content) {
      const now = Date.now();
      const pending = [...this.messages].reverse().find(message =>
        message.role === 'user' &&
        message.pending &&
        message.content === content &&
        now - (message.createdAt || 0) <= PENDING_CONFIRM_WINDOW_MS
      );
      if (!pending) return false;
      this.$set(pending, 'pending', false);
      pending.time = new Date().toLocaleTimeString();
      return true;
    },
    addEvent(text, extra = {}) {
      const eventText = typeof text === 'string' ? text : JSON.stringify(text);
      this.events.unshift({
        id: `${Date.now()}-${Math.random()}`,
        text: eventText.length > 180 ? `${eventText.slice(0, 180)}...` : eventText,
        ...extra
      });
      this.events = this.events.slice(0, 80);
    },
    compactText(value) {
      const text = typeof value === 'string' ? value : JSON.stringify(value ?? '');
      return text.length > 80 ? `${text.slice(0, 80)}...` : text;
    },
    extractImageFromValue(value) {
      const parsed = this.parseJsonLikeValue(value);
      if (!parsed) {
        return '';
      }

      if (typeof parsed === 'string') {
        return this.isImageText(parsed) ? parsed.trim() : '';
      }

      if (Array.isArray(parsed)) {
        for (const item of parsed) {
          const src = this.extractImageFromValue(item);
          if (src) return src;
        }
        return '';
      }

      if (typeof parsed === 'object') {
        const direct = this.getImageSrc(parsed);
        if (direct) {
          return direct;
        }

        const candidateKeys = [
          'result',
          'data',
          'image',
          'images',
          'photo',
          'photos',
          'payload',
          'content',
          'url'
        ];
        for (const key of candidateKeys) {
          const src = this.extractImageFromValue(parsed[key]);
          if (src) return src;
        }
      }

      return '';
    },
    parseJsonLikeValue(value) {
      if (typeof value !== 'string') {
        return value;
      }
      const trimmed = value.trim();
      if (!trimmed) {
        return '';
      }
      if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) {
        return trimmed;
      }
      try {
        return JSON.parse(trimmed);
      } catch (e) {
        return trimmed;
      }
    },
    getImageSrc(value) {
      if (!value || typeof value !== 'object') {
        return '';
      }

      if (value.type === 'image_url' && value.image_url) {
        return typeof value.image_url === 'string' ? value.image_url : value.image_url.url;
      }

      if (value.type === 'image' || value.type === 'image_url') {
        return value.url || value.src || value.imageUrl || value.image_url || value.data || '';
      }

      return value.imageUrl || value.image_url || value.url || value.src || '';
    },
    isImageText(text) {
      if (!text || typeof text !== 'string') {
        return false;
      }
      return /^(data:image\/[a-zA-Z+.-]+;base64,|blob:|https?:\/\/.+\.(png|jpe?g|gif|webp|bmp|svg)(\?.*)?$)/i.test(text.trim());
    },
    async toggleRecording() {
      if (this.recording) {
        this.stopRecording();
      } else {
        await this.startRecording();
      }
    },
    async startRecording() {
      if (!this.connected || this.audioStarting) return;
      this.audioStarting = true;
      try {
        this.audioStream = await navigator.mediaDevices.getUserMedia({ audio: true });
        this.audioContext = new (window.AudioContext || window.webkitAudioContext)();
        this.mediaSource = this.audioContext.createMediaStreamSource(this.audioStream);
        this.processor = this.audioContext.createScriptProcessor(4096, 1, 1);
        this.processor.onaudioprocess = (event) => {
          const input = event.inputBuffer.getChannelData(0);
          const pcm = this.floatTo16BitPcm(this.resample(input, this.audioContext.sampleRate, INPUT_SAMPLE_RATE));
          this.sendPcmFrames(pcm);
        };
        this.mediaSource.connect(this.processor);
        this.processor.connect(this.audioContext.destination);
        this.pcmRemainder = new Int16Array(0);
        this.sendJson({ type: 'listen', state: 'start', mode: 'manual' });
        this.recording = true;
        this.addMessage('user', this.$t('deviceDebug.voiceRecording'));
      } catch (e) {
        this.$message.error(this.$t('deviceDebug.micFailed'));
      } finally {
        this.audioStarting = false;
      }
    },
    stopRecording() {
      if (!this.recording && !this.audioStream) return;
      if (this.processor) {
        this.processor.disconnect();
        this.processor.onaudioprocess = null;
      }
      if (this.mediaSource) {
        this.mediaSource.disconnect();
      }
      if (this.audioStream) {
        this.audioStream.getTracks().forEach(track => track.stop());
      }
      if (this.audioContext) {
        this.audioContext.close();
      }
      this.processor = null;
      this.mediaSource = null;
      this.audioStream = null;
      this.audioContext = null;
      this.recording = false;
      this.flushPcmRemainder();
      if (this.connected) {
        this.sendJson({ type: 'listen', state: 'stop' });
      }
    },
    resample(input, fromRate, toRate) {
      if (fromRate === toRate) return input;
      const ratio = fromRate / toRate;
      const outputLength = Math.floor(input.length / ratio);
      const output = new Float32Array(outputLength);
      for (let i = 0; i < outputLength; i++) {
        output[i] = input[Math.floor(i * ratio)] || 0;
      }
      return output;
    },
    floatTo16BitPcm(float32) {
      const pcm = new Int16Array(float32.length);
      for (let i = 0; i < float32.length; i++) {
        const sample = Math.max(-1, Math.min(1, float32[i]));
        pcm[i] = sample < 0 ? sample * 0x8000 : sample * 0x7fff;
      }
      return pcm;
    },
    sendPcmFrames(pcm) {
      const merged = new Int16Array(this.pcmRemainder.length + pcm.length);
      merged.set(this.pcmRemainder);
      merged.set(pcm, this.pcmRemainder.length);
      let offset = 0;
      while (offset + PCM_FRAME_SAMPLES <= merged.length) {
        this.sendPcmFrame(merged.slice(offset, offset + PCM_FRAME_SAMPLES));
        offset += PCM_FRAME_SAMPLES;
      }
      this.pcmRemainder = merged.slice(offset);
    },
    flushPcmRemainder() {
      if (this.pcmRemainder.length > 0) {
        this.sendPcmFrame(this.pcmRemainder);
        this.pcmRemainder = new Int16Array(0);
      }
    },
    sendPcmFrame(frame) {
      if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
      this.ws.send(frame.buffer);
    },
    startHeartbeat() {
      this.stopHeartbeat();
      this.heartbeatTimer = setInterval(() => {
        this.sendJson({ type: 'ping' });
      }, HEARTBEAT_INTERVAL_MS);
    },
    stopHeartbeat() {
      if (this.heartbeatTimer) {
        clearInterval(this.heartbeatTimer);
        this.heartbeatTimer = null;
      }
    },
    startToolRefresh() {
      this.stopToolRefresh();
      this.fetchDeviceTools();
      this.toolRefreshTimer = setInterval(() => {
        this.fetchDeviceTools();
      }, TOOL_REFRESH_INTERVAL_MS);
    },
    stopToolRefresh() {
      if (this.toolRefreshTimer) {
        clearInterval(this.toolRefreshTimer);
        this.toolRefreshTimer = null;
      }
    },
    disconnectDebug() {
      this.stopRecording();
      this.stopHeartbeat();
      this.stopToolRefresh();
      this.finishActiveAudioClip();
      this.releasePlayback();
      if (this.ws) {
        this.ws.close();
      }
      this.ws = null;
      this.connected = false;
      this.connecting = false;
    }
  }
};
</script>

<style scoped>
.debug-shell {
  display: flex;
  height: 68vh;
  min-height: 520px;
  border-top: 1px solid #ebeef5;
}

.debug-side {
  width: 300px;
  padding: 16px;
  border-right: 1px solid #ebeef5;
  background: #f8fbff;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}

.status-block {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.status-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.side-actions {
  display: flex;
  gap: 8px;
  margin: 14px 0 18px;
}

.side-section {
  margin-bottom: 18px;
}

.raw-section {
  flex: 1;
  min-height: 0;
}

.side-heading {
  font-size: 13px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 8px;
}

.side-muted {
  color: #909399;
  font-size: 13px;
  padding: 10px 0;
}

.tool-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.event-list {
  height: calc(100% - 24px);
  overflow-y: auto;
  font-size: 12px;
  line-height: 18px;
  color: #606266;
}

.event-row {
  padding: 6px 0;
  border-bottom: 1px solid #edf1f7;
  word-break: break-word;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.event-row span {
  min-width: 0;
}

.event-audio-icon {
  flex: 0 0 auto;
  color: #1890ff;
  font-size: 16px;
  cursor: pointer;
}

.debug-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

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
