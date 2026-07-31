<template>
  <el-dialog
    :title="$t('deviceAudioPreview.title')"
    :visible.sync="visible"
    width="620px"
    append-to-body
    :close-on-click-modal="false"
    @close="dispose"
  >
    <div class="preview-dialog">
      <div class="device-summary">
        <div>
          <span class="summary-label">{{ $t('deviceAudioPreview.device') }}</span>
          <span>{{ device.model || '-' }}</span>
        </div>
        <div>
          <span class="summary-label">{{ $t('device.macAddress') }}</span>
          <span>{{ maskedMacAddress }}</span>
        </div>
      </div>

      <div class="status-row">
        <span class="status-dot" :class="statusClass"></span>
        <span>{{ statusText }}</span>
      </div>

      <div class="waveform-shell">
        <canvas ref="waveform" class="waveform"></canvas>
        <div v-if="status !== 'playing'" class="waveform-placeholder">
          {{ $t('deviceAudioPreview.waitingHint') }}
        </div>
      </div>

      <div class="volume-row">
        <i class="el-icon-headset"></i>
        <span>{{ $t('deviceAudioPreview.volume') }}</span>
        <el-slider v-model="volume" :min="0" :max="100" @input="applyVolume"></el-slider>
        <span class="volume-value">{{ volume }}%</span>
      </div>

      <el-alert
        :title="$t('deviceAudioPreview.notice')"
        type="info"
        :closable="false"
        show-icon
      />
    </div>
  </el-dialog>
</template>

<script>
import { OpusDecoder } from 'opus-decoder';
import Api from '@/apis/api';

const RETRY_DELAYS = [1000, 2000, 5000, 10000];

export default {
  name: 'DeviceAudioPreviewDialog',
  data() {
    return {
      visible: false,
      device: {},
      status: 'idle',
      volume: 80,
      websocket: null,
      audioContext: null,
      gainNode: null,
      analyserNode: null,
      decoder: null,
      decoderSampleRate: null,
      decodeChain: Promise.resolve(),
      nextPlayTime: 0,
      activeSources: new Set(),
      reconnectTimer: null,
      retryIndex: 0,
      connectionGeneration: 0,
      intentionalClose: false,
      waveformFrame: null,
      streamStopTimer: null,
    };
  },
  computed: {
    maskedMacAddress() {
      const value = this.device.macAddress || '';
      if (value.length < 8) return value || '-';
      return `${value.slice(0, 5)} **:** ${value.slice(-5)}`;
    },
    statusText() {
      const key = `deviceAudioPreview.status.${this.status}`;
      return this.$t(key);
    },
    statusClass() {
      return `status-${this.status}`;
    },
  },
  methods: {
    open(device) {
      this.dispose();
      this.device = { ...device };
      this.visible = true;
      this.intentionalClose = false;
      this.status = 'connecting';
      this.retryIndex = 0;
      this.ensureAudioContext();
      this.$nextTick(() => this.startWaveform());
      this.connect();
    },
    ensureAudioContext() {
      const AudioContextClass = window.AudioContext || window.webkitAudioContext;
      if (!AudioContextClass) {
        this.status = 'unsupported';
        return;
      }
      this.audioContext = new AudioContextClass();
      this.gainNode = this.audioContext.createGain();
      this.analyserNode = this.audioContext.createAnalyser();
      this.analyserNode.fftSize = 1024;
      this.gainNode.connect(this.analyserNode);
      this.analyserNode.connect(this.audioContext.destination);
      this.applyVolume();
      this.audioContext.resume().catch(() => {
        this.status = 'autoplayBlocked';
      });
    },
    connect() {
      if (!this.visible || this.intentionalClose || !this.audioContext) return;
      const generation = ++this.connectionGeneration;
      this.status = this.retryIndex > 0 ? 'reconnecting' : 'connecting';
      Api.device.createAudioPreviewTicket(this.device.device_id, ({ data }) => {
        if (!this.visible || generation !== this.connectionGeneration) return;
        if (!data || data.code !== 0 || !data.data) {
          this.status = 'error';
          this.scheduleReconnect();
          return;
        }
        this.openWebSocket(data.data, generation);
      });
    },
    openWebSocket(ticket, generation) {
      let websocket;
      try {
        websocket = new WebSocket(ticket.websocketUrl);
      } catch (error) {
        this.status = 'error';
        this.scheduleReconnect();
        return;
      }
      this.websocket = websocket;
      websocket.binaryType = 'arraybuffer';
      websocket.onopen = () => {
        if (!this.visible || generation !== this.connectionGeneration) {
          websocket.close();
          return;
        }
        websocket.send(JSON.stringify({
          type: 'auth',
          deviceId: ticket.deviceId,
          clientId: ticket.clientId,
          token: ticket.token,
        }));
        ticket.token = '';
      };
      websocket.onmessage = event => {
        if (!this.visible || generation !== this.connectionGeneration) return;
        if (typeof event.data === 'string') {
          this.handleControlMessage(event.data);
        } else {
          this.enqueueOpusFrame(event.data);
        }
      };
      websocket.onerror = () => {
        if (!this.intentionalClose) this.status = 'error';
      };
      websocket.onclose = event => {
        if (this.websocket === websocket) this.websocket = null;
        if (!this.visible || this.intentionalClose || generation !== this.connectionGeneration) return;
        if (event.code === 4409) {
          this.status = 'busy';
          return;
        }
        this.status = 'disconnected';
        this.scheduleReconnect();
      };
    },
    handleControlMessage(rawMessage) {
      let message;
      try {
        message = JSON.parse(rawMessage);
      } catch (error) {
        this.status = 'error';
        return;
      }
      if (message.type === 'ready') {
        this.retryIndex = 0;
        this.status = 'waiting';
      } else if (message.type === 'stream_start') {
        this.clearStreamStopTimer();
        const generation = this.connectionGeneration;
        this.decodeChain = this.decodeChain
          .then(() => {
            if (this.visible && generation === this.connectionGeneration) {
              return this.resetDecoder(message.sampleRate);
            }
            return undefined;
          })
          .catch(() => {
            if (this.visible) this.status = 'decodeError';
          });
        this.status = 'buffering';
      } else if (message.type === 'stream_stop') {
        this.scheduleWaitingStatus();
      } else if (message.type === 'discontinuity') {
        const generation = this.connectionGeneration;
        this.decodeChain = this.decodeChain.then(() => {
          if (this.visible && generation === this.connectionGeneration) {
            return this.resetPlaybackAfterGap();
          }
          return undefined;
        });
      } else if (message.type === 'listener_busy') {
        this.status = 'busy';
      } else if (message.type === 'error') {
        this.status = 'error';
      }
    },
    enqueueOpusFrame(frame) {
      const bytes = frame instanceof ArrayBuffer ? new Uint8Array(frame) : new Uint8Array(frame || []);
      if (!bytes.length) return;
      const generation = this.connectionGeneration;
      this.decodeChain = this.decodeChain
        .then(() => {
          if (this.visible && generation === this.connectionGeneration) {
            return this.decodeAndPlay(bytes);
          }
          return undefined;
        })
        .catch(() => {
          if (this.visible) this.status = 'decodeError';
        });
    },
    async resetDecoder(sampleRate) {
      const resolvedRate = Number(sampleRate) || 16000;
      this.stopScheduledAudio();
      if (this.decoder) {
        this.releaseDecoder(this.decoder);
      }
      this.decoder = new OpusDecoder({
        sampleRate: resolvedRate,
        channels: 1,
        streamCount: 1,
        coupledStreamCount: 0,
        channelMappingTable: [0],
      });
      const decoder = this.decoder;
      this.decoderSampleRate = resolvedRate;
      await decoder.ready;
      if (!this.visible || this.decoder !== decoder || !this.audioContext) {
        if (this.decoder === decoder) {
          this.decoder = null;
          this.releaseDecoder(decoder);
        }
        return;
      }
      this.nextPlayTime = this.audioContext ? this.audioContext.currentTime + 0.08 : 0;
    },
    async decodeAndPlay(bytes) {
      if (!this.visible || !this.audioContext) return;
      if (!this.decoder) {
        await this.resetDecoder(16000);
      }
      if (!this.decoder || !this.visible || !this.audioContext) return;
      const decoded = this.decoder.decodeFrame(bytes);
      if (!decoded || !decoded.samplesDecoded || !decoded.channelData.length || !this.audioContext) return;
      if (decoded.errors && decoded.errors.length) {
        throw new Error('opus decode failed');
      }
      const buffer = this.audioContext.createBuffer(
        decoded.channelData.length,
        decoded.samplesDecoded,
        decoded.sampleRate || this.decoderSampleRate
      );
      decoded.channelData.forEach((channel, index) => buffer.copyToChannel(channel, index));

      const source = this.audioContext.createBufferSource();
      source.buffer = buffer;
      source.connect(this.gainNode);
      const now = this.audioContext.currentTime;
      if (this.nextPlayTime < now || this.nextPlayTime - now > 1.5) {
        this.stopScheduledAudio();
        this.nextPlayTime = now + 0.08;
      }
      const startAt = Math.max(this.nextPlayTime, now + 0.02);
      source.start(startAt);
      this.nextPlayTime = startAt + buffer.duration;
      this.activeSources.add(source);
      source.onended = () => this.activeSources.delete(source);
      this.status = 'playing';
    },
    async resetPlaybackAfterGap() {
      this.stopScheduledAudio();
      if (this.decoder) await this.decoder.reset();
      this.nextPlayTime = this.audioContext ? this.audioContext.currentTime + 0.08 : 0;
      this.status = 'buffering';
    },
    scheduleWaitingStatus() {
      this.clearStreamStopTimer();
      const remaining = this.audioContext
        ? Math.max(0, this.nextPlayTime - this.audioContext.currentTime)
        : 0;
      this.streamStopTimer = window.setTimeout(() => {
        if (this.visible && this.status === 'playing') this.status = 'waiting';
      }, Math.ceil(remaining * 1000) + 80);
    },
    clearStreamStopTimer() {
      if (this.streamStopTimer) {
        window.clearTimeout(this.streamStopTimer);
        this.streamStopTimer = null;
      }
    },
    scheduleReconnect() {
      if (!this.visible || this.intentionalClose || this.reconnectTimer) return;
      const delay = RETRY_DELAYS[Math.min(this.retryIndex, RETRY_DELAYS.length - 1)];
      this.retryIndex += 1;
      this.reconnectTimer = window.setTimeout(() => {
        this.reconnectTimer = null;
        this.connect();
      }, delay);
    },
    applyVolume() {
      if (this.gainNode) this.gainNode.gain.value = this.volume / 100;
    },
    stopScheduledAudio() {
      this.activeSources.forEach(source => {
        try {
          source.stop();
        } catch (error) {
          // Source may already have ended.
        }
      });
      this.activeSources.clear();
      this.nextPlayTime = 0;
    },
    releaseDecoder(decoder) {
      if (!decoder) return;
      Promise.resolve(decoder.ready)
        .then(() => decoder.free())
        .catch(() => {});
    },
    startWaveform() {
      const canvas = this.$refs.waveform;
      if (!canvas) return;
      const draw = () => {
        if (!this.visible) return;
        const rect = canvas.getBoundingClientRect();
        const ratio = window.devicePixelRatio || 1;
        const width = Math.max(1, Math.floor(rect.width * ratio));
        const height = Math.max(1, Math.floor(rect.height * ratio));
        if (canvas.width !== width || canvas.height !== height) {
          canvas.width = width;
          canvas.height = height;
        }
        const context = canvas.getContext('2d');
        context.clearRect(0, 0, width, height);
        context.strokeStyle = '#6684ff';
        context.lineWidth = Math.max(1, ratio * 1.5);
        context.beginPath();
        const values = new Uint8Array(this.analyserNode ? this.analyserNode.fftSize : 128);
        if (this.analyserNode) this.analyserNode.getByteTimeDomainData(values);
        values.forEach((value, index) => {
          const x = index * width / Math.max(1, values.length - 1);
          const y = (value / 255) * height;
          if (index === 0) context.moveTo(x, y);
          else context.lineTo(x, y);
        });
        context.stroke();
        this.waveformFrame = window.requestAnimationFrame(draw);
      };
      draw();
    },
    dispose() {
      this.intentionalClose = true;
      this.connectionGeneration += 1;
      this.clearStreamStopTimer();
      if (this.reconnectTimer) {
        window.clearTimeout(this.reconnectTimer);
        this.reconnectTimer = null;
      }
      if (this.waveformFrame) {
        window.cancelAnimationFrame(this.waveformFrame);
        this.waveformFrame = null;
      }
      if (this.websocket) {
        const websocket = this.websocket;
        this.websocket = null;
        websocket.onclose = null;
        websocket.close();
      }
      this.stopScheduledAudio();
      if (this.decoder) {
        this.releaseDecoder(this.decoder);
        this.decoder = null;
      }
      if (this.audioContext) {
        this.audioContext.close().catch(() => {});
        this.audioContext = null;
      }
      this.gainNode = null;
      this.analyserNode = null;
      this.decoderSampleRate = null;
      this.decodeChain = Promise.resolve();
      this.status = 'idle';
    },
  },
  beforeDestroy() {
    this.dispose();
  },
};
</script>

<style scoped>
.preview-dialog {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.device-summary {
  display: flex;
  justify-content: space-between;
  padding: 12px 16px;
  border-radius: 8px;
  background: #f4f7ff;
  color: #4f5f7a;
}

.summary-label {
  margin-right: 8px;
  color: #9099aa;
}

.status-row {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 24px;
  color: #526078;
}

.status-dot {
  width: 9px;
  height: 9px;
  margin-right: 8px;
  border-radius: 50%;
  background: #909399;
}

.status-playing { background: #36c98f; box-shadow: 0 0 0 5px rgba(54, 201, 143, 0.14); }
.status-waiting, .status-buffering { background: #6684ff; }
.status-connecting, .status-reconnecting { background: #e6a23c; }
.status-error, .status-disconnected, .status-decodeError, .status-busy, .status-unsupported, .status-autoplayBlocked { background: #f56c6c; }

.waveform-shell {
  position: relative;
  height: 180px;
  overflow: hidden;
  border: 1px solid #dfe6f5;
  border-radius: 10px;
  background: linear-gradient(180deg, #f9fbff, #eef3ff);
}

.waveform {
  width: 100%;
  height: 100%;
}

.waveform-placeholder {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 18px;
  text-align: center;
  color: #9aa5b6;
  pointer-events: none;
}

.volume-row {
  display: grid;
  grid-template-columns: 20px 42px 1fr 48px;
  gap: 10px;
  align-items: center;
  color: #536079;
}

.volume-value {
  text-align: right;
  color: #8792a6;
}
</style>
