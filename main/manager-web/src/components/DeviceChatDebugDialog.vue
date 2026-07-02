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
          <div class="status-tags">
            <el-tag :type="connected ? 'success' : 'info'" size="small">
              {{ $t('deviceDebug.debugSession') }}{{ connected ? $t('deviceDebug.connected') : $t('deviceDebug.disconnected') }}
            </el-tag>
            <el-tag :type="runtimeStatusTagType" size="small">
              {{ $t('deviceDebug.runtimeConnection') }}{{ runtimeStatusText }}
            </el-tag>
            <el-tag :type="mcpStatusTagType" size="small">
              {{ $t('deviceDebug.mcpState') }}{{ mcpStatusText }}
            </el-tag>
          </div>
          <div v-if="runtimeStatusDetail" class="status-detail">
            {{ runtimeStatusDetail }}
          </div>
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
        <chat-message-list
          :messages="messages"
          :connected="connected"
          :empty-chat-text="emptyChatText"
          :playing-audio-clip-id="playingAudioClipId"
          @play-audio="playAudioClip" />
        <chat-input-panel
          :connected="connected"
          :recording="recording"
          :audio-starting="audioStarting"
          :input-placeholder="inputPlaceholder"
          @send="sendText"
          @toggle-recording="toggleRecording" />
      </main>
    </div>
  </el-dialog>
</template>

<script>
import Api from '@/apis/api';
import { OpusDecoder } from 'opus-decoder';
import ChatMessageList from '@/components/device/ChatMessageList';
import ChatInputPanel from '@/components/device/ChatInputPanel';

const INPUT_SAMPLE_RATE = 16000;
const PCM_FRAME_SAMPLES = 960;
const HEARTBEAT_INTERVAL_MS = 25000;
const STATUS_REFRESH_INTERVAL_MS = 3000;
const TOOL_REFRESH_INTERVAL_MS = 3000;
const PENDING_CONFIRM_WINDOW_MS = 10000;

export default {
  name: 'DeviceChatDebugDialog',
  components: {
    ChatMessageList,
    ChatInputPanel
  },
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
      messages: [],
      events: [],
      mcpTools: [],
      mcpToolsStatus: '',
      deviceRuntimeStatus: null,
      statusLoading: false,
      toolsLoading: false,
      toolsRequesting: false,
      heartbeatTimer: null,
      statusRefreshTimer: null,
      toolRefreshTimer: null,
      opusDecoder: null,
      opusDecoderReady: null,
      playbackAudioContext: null,
      playbackCursor: 0,
      activePlaybackSources: [],
      assistantSpeaking: false,
      activeAssistantMessageId: null,
      activeAudioClipId: null,
      audioClips: {},
      playingAudioClipId: null,
      pendingTurns: [],
      turnSequence: 0,
      inputMode: 'idle',
      textRenderTimers: {},
      audioContext: null,
      audioStream: null,
      mediaSource: null,
      processor: null,
      pcmRemainder: new Int16Array(0),
      recording: false,
      audioStarting: false
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
      if (this.mcpToolsStatus) {
        return this.mcpToolsStatus;
      }
      const statusMap = {
        offline: this.$t('deviceDebug.toolsUnavailableOffline'),
        online_no_mcp: this.$t('deviceDebug.statusOnlineNoMcp'),
        mcp_initializing: this.$t('deviceDebug.statusMcpInitializing'),
        runtime_unavailable: this.$t('deviceDebug.statusRuntimeUnavailable')
      };
      return statusMap[this.deviceStatusCode] || this.$t('deviceDebug.noTools');
    },
    shouldFetchDeviceTools() {
      return ['mcp_initializing', 'mcp_ready'].includes(this.deviceStatusCode);
    },
    deviceStatusCode() {
      return (this.deviceRuntimeStatus && this.deviceRuntimeStatus.status) || 'unknown';
    },
    runtimeStatusText() {
      if (this.statusLoading && !this.deviceRuntimeStatus) {
        return this.$t('deviceDebug.statusChecking');
      }
      if (this.deviceStatusCode === 'runtime_unavailable') {
        return this.$t('deviceDebug.statusRuntimeUnavailable');
      }
      if (this.deviceRuntimeStatus && this.deviceRuntimeStatus.runtimeConnected) {
        return this.$t('deviceDebug.connected');
      }
      if (this.deviceStatusCode === 'unknown') {
        return this.$t('deviceDebug.statusUnknown');
      }
      return this.$t('deviceDebug.disconnected');
    },
    runtimeStatusTagType() {
      if (this.deviceStatusCode === 'runtime_unavailable') {
        return 'danger';
      }
      return this.deviceRuntimeStatus && this.deviceRuntimeStatus.runtimeConnected ? 'success' : 'info';
    },
    mcpStatusText() {
      if (this.statusLoading && !this.deviceRuntimeStatus) {
        return this.$t('deviceDebug.statusChecking');
      }
      const statusMap = {
        offline: this.$t('deviceDebug.statusUnavailable'),
        online_no_mcp: this.$t('deviceDebug.statusOnlineNoMcp'),
        mcp_initializing: this.$t('deviceDebug.statusMcpInitializing'),
        mcp_ready: this.$t('deviceDebug.statusMcpReady'),
        runtime_unavailable: this.$t('deviceDebug.statusRuntimeUnavailable')
      };
      return statusMap[this.deviceStatusCode] || this.$t('deviceDebug.statusUnknown');
    },
    mcpStatusTagType() {
      const statusTypeMap = {
        offline: 'info',
        online_no_mcp: 'warning',
        mcp_initializing: 'warning',
        mcp_ready: 'success',
        runtime_unavailable: 'danger'
      };
      return statusTypeMap[this.deviceStatusCode] || 'info';
    },
    runtimeStatusDetail() {
      if (!this.deviceRuntimeStatus) return '';
      const parts = [];
      if (this.deviceRuntimeStatus.message) {
        parts.push(this.deviceRuntimeStatus.message);
      }
      if (this.deviceRuntimeStatus.clientId) {
        parts.push(`clientId: ${this.deviceRuntimeStatus.clientId}`);
      }
      if (this.deviceRuntimeStatus.source) {
        parts.push(`source: ${this.deviceRuntimeStatus.source}`);
      }
      return parts.join(' / ');
    },
    emptyChatText() {
      return this.connected ? this.$t('deviceDebug.empty') : this.$t('deviceDebug.debugConnectHint');
    },
    inputPlaceholder() {
      return this.connected ? this.$t('deviceDebug.inputPlaceholder') : this.$t('deviceDebug.inputConnectPlaceholder');
    }
  },
  watch: {
    visible(val) {
      this.dialogVisible = val;
      if (val) {
        this.resetDialog();
        this.loadTools();
      } else {
        this.closeDebugSession(true);
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
      this.deviceRuntimeStatus = null;
      this.statusLoading = false;
      this.toolsRequesting = false;
      this.pcmRemainder = new Int16Array(0);
      this.activePlaybackSources = [];
      this.assistantSpeaking = false;
      this.activeAssistantMessageId = null;
      this.activeAudioClipId = null;
      this.audioClips = {};
      this.playingAudioClipId = null;
      this.pendingTurns = [];
      this.turnSequence = 0;
      this.inputMode = 'idle';
      Object.values(this.textRenderTimers).forEach(timer => clearTimeout(timer));
      this.textRenderTimers = {};
      this.stopHeartbeat();
      this.stopStatusRefresh();
      this.stopToolRefresh();
      this.releasePlayback();
    },
    handleClose() {
      this.closeDebugSession(true);
      this.dialogVisible = false;
    },
    loadTools() {
      if (!this.deviceId) return;
      this.toolsLoading = true;
      this.fetchDeviceStatus().then(() => {
        if (this.shouldFetchDeviceTools) {
          return this.fetchDeviceTools();
        }
        this.mcpTools = [];
        this.mcpToolsStatus = '';
        return [];
      }).finally(() => {
        this.toolsLoading = false;
      });
      this.startStatusRefresh();
      this.startToolRefresh();
    },
    fetchDeviceStatus() {
      if (!this.deviceId || this.statusLoading) {
        return Promise.resolve(null);
      }
      this.statusLoading = true;
      return new Promise((resolve) => {
        Api.device.getDeviceDebugStatus(this.deviceId, (res) => {
          const data = res && res.data && res.data.data;
          if (res && res.data && res.data.code === 0 && data) {
            this.deviceRuntimeStatus = data;
          } else {
            this.deviceRuntimeStatus = {
              status: 'runtime_unavailable',
              runtimeConnected: false,
              mcpReady: false,
              toolsCount: 0,
              message: (res && res.data && res.data.msg) || this.$t('deviceDebug.statusRuntimeUnavailable')
            };
          }
          this.statusLoading = false;
          resolve(this.deviceRuntimeStatus);
        });
      });
    },
    fetchDeviceTools() {
      if (!this.deviceId) {
        this.mcpTools = [];
        return Promise.resolve([]);
      }
      if (this.toolsRequesting) {
        return Promise.resolve(this.mcpTools);
      }
      this.toolsRequesting = true;
      return new Promise((resolve) => {
        Api.device.getDeviceTools(this.deviceId, (res) => {
          const data = res && res.data && res.data.data;
          const tools = data && Array.isArray(data.tools) ? data.tools : [];
          this.mcpTools = tools;
          this.mcpToolsStatus = this.resolveToolsStatus(res, data, tools);
          this.addEvent(`device mcp tools/list -> ${tools.length}`);
          this.toolsRequesting = false;
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
        this.fetchDeviceStatus();
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
        this.assistantSpeaking = false;
        this.inputMode = 'idle';
        this.stopHeartbeat();
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
    sendText(text) {
      if (!text || !this.connected) return;
      const turn = this.createPendingTurn(text, 'local-text');
      this.addMessage('user', text, {
        pending: true,
        source: 'local-text',
        turnId: turn.id,
        overlap: turn.overlap,
        createdAt: turn.createdAt
      });
      this.sendJson({ type: 'listen', state: 'detect', text });
    },
    createPendingTurn(text, source) {
      this.prunePendingTurns();
      const turn = {
        id: `turn-${Date.now()}-${this.turnSequence++}`,
        text,
        source,
        overlap: this.assistantSpeaking,
        createdAt: Date.now()
      };
      this.pendingTurns.push(turn);
      if (turn.overlap) {
        this.addEvent(`overlap input -> ${this.compactText(text)}`);
      }
      return turn;
    },
    prunePendingTurns() {
      const now = Date.now();
      this.pendingTurns = this.pendingTurns.filter(turn => now - turn.createdAt <= PENDING_CONFIRM_WINDOW_MS);
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
        this.addEvent('debug mcp proxy initialize');
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
        this.addEvent('debug mcp proxy tools/list requested');
        this.respondMcpToolsList(payload);
      } else if (payload.method === 'tools/call') {
        const params = payload.params || {};
        this.addEvent(`debug mcp proxy tools/call -> ${params.name || ''}`);
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
        this.assistantSpeaking = true;
        this.inputMode = 'assistant_speaking';
        this.startNewAudioClip();
        return;
      }
      if (payload.state === 'stop') {
        if (payload.reason === 'abort') {
          this.stopActivePlaybackSources();
        }
        this.assistantSpeaking = false;
        this.inputMode = this.recording ? 'recording' : 'idle';
        this.finishActiveAudioClip();
        return;
      }
      if (payload.text) {
        this.assistantSpeaking = true;
        this.inputMode = 'assistant_speaking';
        const clipId = this.activeAudioClipId || this.startNewAudioClip();
        const message = this.addMessage('assistant', payload.text, { audioClipId: clipId, time: '' });
        this.activeAssistantMessageId = message.id;
        this.attachClipToMessage(clipId, message.id);
        this.markClipTextRendered(clipId);
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
        messageRendered: false,
        pendingPlaybackChunks: [],
        firstAudioStartedAt: null,
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
    markClipTextRendered(clipId) {
      if (this.textRenderTimers[clipId]) {
        clearTimeout(this.textRenderTimers[clipId]);
        delete this.textRenderTimers[clipId];
      }
      this.$nextTick(() => {
        const timer = setTimeout(() => {
          const clip = this.audioClips[clipId];
          if (!clip) return;
          this.$set(clip, 'messageRendered', true);
          this.flushClipPlayback(clipId);
          delete this.textRenderTimers[clipId];
        }, 0);
        this.$set(this.textRenderTimers, clipId, timer);
      });
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
          this.enqueueClipPlayback(clipId, chunk);
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
    enqueueClipPlayback(clipId, chunk) {
      const clip = this.audioClips[clipId];
      if (!clip) return;
      if (!clip.messageRendered) {
        clip.pendingPlaybackChunks.push(chunk);
        return;
      }
      this.startClipPlayback(clip, [chunk]);
    },
    flushClipPlayback(clipId) {
      const clip = this.audioClips[clipId];
      if (!clip || !clip.messageRendered || clip.pendingPlaybackChunks.length === 0) {
        return;
      }
      const chunks = clip.pendingPlaybackChunks.splice(0);
      this.startClipPlayback(clip, chunks);
    },
    startClipPlayback(clip, chunks) {
      if (!clip || !chunks || chunks.length === 0) return;
      if (!clip.firstAudioStartedAt) {
        const firstAudioStartedAt = Date.now();
        this.$set(clip, 'firstAudioStartedAt', firstAudioStartedAt);
        this.updateMessageFirstAudioTime(clip.messageId, firstAudioStartedAt);
      }
      this.playDecodedChunks(chunks, false);
    },
    updateMessageFirstAudioTime(messageId, timestamp) {
      const message = this.messages.find(item => item.id === messageId);
      if (!message) return;
      this.$set(message, 'time', this.formatMessageTime(timestamp));
    },
    formatMessageTime(timestamp) {
      return new Date(timestamp).toLocaleTimeString();
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
        this.activePlaybackSources.push(source);
        source.onended = () => {
          this.activePlaybackSources = this.activePlaybackSources.filter(item => item !== source);
        };
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
      this.stopActivePlaybackSources();
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
    stopActivePlaybackSources() {
      this.activePlaybackSources.forEach(source => {
        try {
          source.stop();
        } catch (e) {
          // The source may have already ended.
        }
      });
      this.activePlaybackSources = [];
      if (this.playbackAudioContext) {
        this.playbackCursor = this.playbackAudioContext.currentTime;
      } else {
        this.playbackCursor = 0;
      }
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
      this.pendingTurns = this.pendingTurns.filter(turn => turn.id !== pending.turnId);
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
        this.sendJson({ type: 'listen', state: 'start', mode: 'auto' });
        this.recording = true;
        this.inputMode = 'recording';
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
      this.inputMode = this.assistantSpeaking ? 'assistant_speaking' : 'idle';
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
    startStatusRefresh() {
      this.stopStatusRefresh();
      this.statusRefreshTimer = setInterval(() => {
        this.fetchDeviceStatus().then(() => {
          if (this.shouldFetchDeviceTools && this.mcpTools.length === 0) {
            this.fetchDeviceTools();
          }
        });
      }, STATUS_REFRESH_INTERVAL_MS);
    },
    stopStatusRefresh() {
      if (this.statusRefreshTimer) {
        clearInterval(this.statusRefreshTimer);
        this.statusRefreshTimer = null;
      }
    },
    startToolRefresh() {
      this.stopToolRefresh();
      if (this.shouldFetchDeviceTools) {
        this.fetchDeviceTools();
      }
      this.toolRefreshTimer = setInterval(() => {
        if (this.shouldFetchDeviceTools && this.deviceStatusCode !== 'mcp_ready') {
          this.fetchDeviceTools();
        }
      }, TOOL_REFRESH_INTERVAL_MS);
    },
    stopToolRefresh() {
      if (this.toolRefreshTimer) {
        clearInterval(this.toolRefreshTimer);
        this.toolRefreshTimer = null;
      }
    },
    disconnectDebug() {
      this.closeDebugSession(false);
      this.fetchDeviceStatus().then(() => {
        if (this.shouldFetchDeviceTools) {
          this.fetchDeviceTools();
        }
      });
    },
    closeDebugSession(stopPolling) {
      this.stopRecording();
      this.stopHeartbeat();
      if (stopPolling) {
        this.stopStatusRefresh();
        this.stopToolRefresh();
      }
      this.finishActiveAudioClip();
      this.releasePlayback();
      if (this.ws) {
        this.ws.close();
      }
      this.ws = null;
      this.connected = false;
      this.connecting = false;
      this.assistantSpeaking = false;
      this.inputMode = 'idle';
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
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
}

.status-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.status-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.status-detail {
  margin-top: 8px;
  color: #606266;
  font-size: 12px;
  line-height: 1.45;
  word-break: break-all;
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
</style>
