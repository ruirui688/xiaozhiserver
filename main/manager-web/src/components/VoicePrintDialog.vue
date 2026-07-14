<template>
  <el-dialog :title="title" :visible.sync="visible" width="680px" class="param-dialog-wrapper" :append-to-body="true"
    :close-on-click-modal="false" :key="dialogKey" custom-class="custom-param-dialog" :show-close="false">
    <div class="dialog-container">
      <div class="dialog-header">
        <h2 class="dialog-title">{{ title }}</h2>
        <button class="custom-close-btn" @click="cancel">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M13 1L1 13M1 1L13 13" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          </svg>
        </button>
      </div>

      <el-form :model="form" :rules="rules" ref="form" label-width="96px" label-position="left" class="param-form">
        <el-form-item :label="$t('voicePrintDialog.name')" prop="sourceName" class="form-item">
          <el-input v-model="form.sourceName" :placeholder="$t('voicePrintDialog.enterName')" class="custom-input"></el-input>
        </el-form-item>

        <el-form-item :label="$t('voicePrintDialog.description')" prop="introduce" class="form-item remark-item">
          <el-input type="textarea" v-model="form.introduce" :placeholder="$t('voicePrintDialog.enterDescription')" :rows="3" class="custom-textarea"
            maxlength="100" show-word-limit></el-input>
        </el-form-item>

        <el-form-item v-if="!form.id" :label="$t('voicePrintDialog.collectMethod')" class="form-item">
          <el-radio-group v-model="collectMethod" class="method-group">
            <el-radio-button label="device">{{ $t('voicePrintDialog.deviceCollect') }}</el-radio-button>
            <el-radio-button label="history">{{ $t('voicePrintDialog.historyCollect') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="!form.id && collectMethod === 'device'" :label="$t('voicePrintDialog.device')" class="form-item">
          <el-select v-model="deviceId" :placeholder="$t('voicePrintDialog.selectDevice')" class="custom-select">
            <el-option
              v-for="device in devices"
              :key="device.deviceId"
              :label="device.label"
              :value="device.deviceId">
              <span style="float: left">{{ device.label }}</span>
              <span style="float: right; color: #8492a6; font-size: 13px">{{ device.deviceId }}</span>
            </el-option>
          </el-select>
        </el-form-item>

        <el-form-item v-if="!form.id && collectMethod === 'device'" :label="$t('voicePrintDialog.requiredSamples')" class="form-item">
          <el-input-number v-model="requiredSamples" :min="minSamples" :max="maxSamples" size="mini"></el-input-number>
        </el-form-item>

        <div v-if="!form.id && collectMethod === 'device'" class="enrollment-panel">
          <div class="task-head">
            <div>
              <div class="task-title">{{ $t('voicePrintDialog.enrollmentTask') }}</div>
              <div class="task-subtitle">{{ enrollmentHint }}</div>
            </div>
            <el-tag size="mini" :type="enrollmentTagType">{{ enrollmentStatusText }}</el-tag>
          </div>

          <el-progress :percentage="enrollmentProgress" :status="enrollmentProgressStatus"></el-progress>

          <div class="sample-row">
            <span>{{ $t('voicePrintDialog.collectedSamples', { count: collectedCount, min: requiredSamples, max: maxSamples }) }}</span>
            <span v-if="enrollmentId" class="task-id">{{ $t('voicePrintDialog.taskId') }} {{ enrollmentId }}</span>
          </div>

          <el-alert
            v-if="enrollmentUnsupported"
            type="warning"
            :closable="false"
            class="fallback-alert"
            :title="$t('voicePrintDialog.enrollmentUnavailable')"
            :description="$t('voicePrintDialog.enrollmentUnavailableDesc')">
          </el-alert>

          <div class="device-actions">
            <el-button
              type="primary"
              size="mini"
              :loading="creatingEnrollment"
              :disabled="creatingEnrollment || completingEnrollment"
              @click="startEnrollment">
              {{ enrollmentId ? $t('voicePrintDialog.restartEnrollment') : $t('voicePrintDialog.startEnrollment') }}
            </el-button>
            <el-button
              size="mini"
              :loading="completingEnrollment"
              :disabled="!canCompleteEnrollment"
              @click="completeEnrollment">
              {{ $t('voicePrintDialog.completeEnrollment') }}
            </el-button>
            <el-button size="mini" type="text" @click="collectMethod = 'history'">
              {{ $t('voicePrintDialog.useHistoryFallback') }}
            </el-button>
          </div>
        </div>

        <div v-if="showHistorySamples" class="history-panel">
          <div class="history-head">
            <span>{{ form.id ? $t('voicePrintDialog.replaceSamples') : $t('voicePrintDialog.historyAdvanced') }}</span>
            <el-tag v-if="!form.id && collectMethod === 'device'" size="mini" type="info">{{ $t('voicePrintDialog.optional') }}</el-tag>
          </div>
          <el-form-item :label="$t('voicePrintDialog.voicePrintVector')" prop="audioIds" class="form-item sample-item">
            <el-select
              v-model="form.audioIds"
              multiple
              collapse-tags
              :multiple-limit="5"
              :placeholder="$t('voicePrintDialog.selectVoiceMessage')"
              class="custom-select">
              <el-option v-for="item in valueTypeOptions" :key="item.audioId" :label="item.content" :value="item.audioId">
                <span style="float: left">{{ item.content }}</span>
                <span style="float: right; color: #8492a6; font-size: 13px">
                  <i :class="getAudioIconClass(item.audioId)" @click.stop="playAudio(item.audioId)"
                    class="audio-icon"></i>
                </span>
              </el-option>
            </el-select>
          </el-form-item>
          <div v-if="!form.id && collectMethod === 'device'" class="append-row">
            <el-button
              size="mini"
              :loading="appendingSamples"
              :disabled="!canAppendSamples"
              @click="appendHistorySamples">
              {{ $t('voicePrintDialog.appendHistorySamples') }}
            </el-button>
          </div>
        </div>
      </el-form>

      <div class="dialog-footer">
        <el-button type="primary" @click="submit" class="save-btn" :loading="saving" :disabled="saving || primarySubmitDisabled">
          {{ primarySubmitText }}
        </el-button>
        <el-button @click="cancel" class="cancel-btn">
          {{ $t('voicePrintDialog.cancel') }}
        </el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script>
import api from '@/apis/api';

export default {
  props: {
    title: {
      type: String,
      default: '添加说话人'
    },
    visible: {
      type: Boolean,
      default: false
    },
    agentId: {
      type: String
    },
    devices: {
      type: Array,
      default: () => []
    },
    form: {
      type: Object,
      default: () => ({
        id: null,
        audioId: '',
        audioIds: [],
        sourceName: '',
        introduce: ''
      })
    }
  },
  data() {
    return {
      dialogKey: Date.now(),
      saving: false,
      collectMethod: 'device',
      deviceId: '',
      requiredSamples: 3,
      enrollmentId: '',
      enrollmentStatus: 'idle',
      enrollmentProgress: 0,
      collectedCount: 0,
      creatingEnrollment: false,
      appendingSamples: false,
      completingEnrollment: false,
      enrollmentUnsupported: false,
      pollingTimer: null,
      playingAudioId: null,
      audioElement: null,
      minSamples: 2,
      maxSamples: 5,
      valueTypeOptions: [
        { audioId: '', content: '' }
      ],
      rules: {
        introduce: [
          { required: true, message: '请输入描述', trigger: "blur" }
        ],
        sourceName: [
          { required: true, message: '请输入名称', trigger: "blur" }
        ],
        audioIds: [
          { validator: (rule, value, callback) => this.validateAudioIds(value, callback), trigger: "change" }
        ]
      }
    };
  },
  computed: {
    showHistorySamples() {
      return this.form.id || this.collectMethod === 'history' || this.enrollmentId || this.enrollmentUnsupported;
    },
    enrollmentHint() {
      if (this.enrollmentUnsupported) {
        return this.$t('voicePrintDialog.historyFallbackHint');
      }
      if (!this.enrollmentId) {
        return this.$t('voicePrintDialog.deviceCollectHint');
      }
      if (this.enrollmentStatus === 'collecting') {
        return this.$t('voicePrintDialog.collectingHint');
      }
      if (this.enrollmentStatus === 'ready') {
        return this.$t('voicePrintDialog.readyHint');
      }
      if (this.enrollmentStatus === 'failed') {
        return this.$t('voicePrintDialog.failedHint');
      }
      return this.$t('voicePrintDialog.createdHint');
    },
    enrollmentStatusText() {
      const statusMap = {
        idle: this.$t('voicePrintDialog.statusIdle'),
        collecting: this.$t('voicePrintDialog.statusCollecting'),
        ready: this.$t('voicePrintDialog.statusReady'),
        failed: this.$t('voicePrintDialog.statusFailed')
      };
      return statusMap[this.enrollmentStatus] || statusMap.idle;
    },
    enrollmentTagType() {
      if (this.enrollmentStatus === 'ready') return 'success';
      if (this.enrollmentStatus === 'failed' || this.enrollmentUnsupported) return 'warning';
      if (this.enrollmentStatus === 'collecting') return '';
      return 'info';
    },
    enrollmentProgressStatus() {
      if (this.enrollmentStatus === 'ready') return 'success';
      if (this.enrollmentStatus === 'failed') return 'exception';
      return undefined;
    },
    canCompleteEnrollment() {
      return Boolean(this.enrollmentId) && this.collectedCount >= this.requiredSamples && !this.completingEnrollment;
    },
    canAppendSamples() {
      return Boolean(this.enrollmentId) && Array.isArray(this.form.audioIds) && this.form.audioIds.length > 0 && !this.appendingSamples;
    },
    primarySubmitText() {
      if (!this.form.id && this.collectMethod === 'device') {
        return this.$t('voicePrintDialog.completeEnrollment');
      }
      return this.$t('voicePrintDialog.save');
    },
    primarySubmitDisabled() {
      return !this.form.id && this.collectMethod === 'device' && !this.canCompleteEnrollment;
    }
  },
  methods: {
    validateAudioIds(value, callback) {
      const count = Array.isArray(value) ? value.length : 0;
      if (this.form.id && count === 0) {
        callback();
        return;
      }
      if (!this.form.id && this.collectMethod === 'device') {
        callback();
        return;
      }
      if (count < 2 || count > 5) {
        callback(new Error(this.$t('voicePrintDialog.requiredAudioVector')));
        return;
      }
      callback();
    },
    validateBasicFields(done) {
      const fields = ['sourceName', 'introduce'];
      let pending = fields.length;
      let hasError = false;
      fields.forEach(prop => {
        this.$refs.form.validateField(prop, (error) => {
          if (error) {
            hasError = true;
          }
          pending -= 1;
          if (pending === 0 && !hasError) {
            done();
          }
        });
      });
    },
    normalizeEnrollment(data) {
      const payload = data && data.data ? data.data : data;
      const status = String(payload.status || payload.enrollmentStatus || this.enrollmentStatus || 'collecting').toLowerCase();
      const count = Number(payload.collectedCount || payload.sampleCount || payload.samples || this.collectedCount || 0);
      const requiredSamples = Number(payload.requiredSamples || this.requiredSamples || this.minSamples);
      const progress = payload.progress != null
        ? Number(payload.progress)
        : Math.min(100, Math.round((count / requiredSamples) * 100));
      this.enrollmentId = payload.id || payload.enrollmentId || this.enrollmentId;
      this.requiredSamples = Math.max(this.minSamples, Math.min(this.maxSamples, requiredSamples));
      this.enrollmentStatus = status === 'completed' ? 'ready' : status;
      this.collectedCount = count;
      this.enrollmentProgress = Math.max(0, Math.min(100, progress));
      if (this.collectedCount >= this.requiredSamples && this.enrollmentStatus === 'collecting') {
        this.enrollmentStatus = 'ready';
      }
    },
    markEnrollmentUnsupported() {
      this.enrollmentUnsupported = true;
      this.enrollmentStatus = 'failed';
      this.enrollmentProgress = 0;
      this.stopPolling();
    },
    startEnrollment() {
      this.validateBasicFields(() => {
        if (!this.deviceId) {
          this.$message.warning(this.$t('voicePrintDialog.requiredDevice'));
          return;
        }
        this.creatingEnrollment = true;
        this.enrollmentUnsupported = false;
        const payload = {
          agentId: this.agentId,
          sourceName: this.form.sourceName,
          introduce: this.form.introduce,
          collectMethod: 'device',
          deviceId: this.deviceId,
          requiredSamples: this.requiredSamples
        };
        api.agent.createAgentVoicePrintEnrollment(payload, ({ data }) => {
          this.creatingEnrollment = false;
          if (data.code === 0 || data.code === undefined) {
            this.normalizeEnrollment(data.data || {});
            if (!this.enrollmentId) {
              this.enrollmentStatus = 'collecting';
            }
            this.startPolling();
          } else {
            this.markEnrollmentUnsupported();
          }
        }, () => {
          this.creatingEnrollment = false;
          this.markEnrollmentUnsupported();
        });
      });
    },
    startPolling() {
      this.stopPolling();
      if (!this.enrollmentId) {
        return;
      }
      this.pollingTimer = setInterval(() => {
        api.agent.getAgentVoicePrintEnrollment(this.agentId, this.enrollmentId, ({ data }) => {
          if (data.code === 0 || data.code === undefined) {
            this.normalizeEnrollment(data.data || {});
            if (this.enrollmentStatus === 'ready' || this.enrollmentStatus === 'failed') {
              this.stopPolling();
            }
          }
        }, () => {
          this.stopPolling();
        });
      }, 3000);
    },
    stopPolling() {
      if (this.pollingTimer) {
        clearInterval(this.pollingTimer);
        this.pollingTimer = null;
      }
    },
    appendHistorySamples() {
      if (!this.canAppendSamples) {
        return;
      }
      this.appendingSamples = true;
      api.agent.appendAgentVoicePrintEnrollmentSamples(this.enrollmentId, {
        agentId: this.agentId,
        audioIds: this.form.audioIds
      }, ({ data }) => {
        this.appendingSamples = false;
        if (data.code === 0 || data.code === undefined) {
          this.normalizeEnrollment(data.data || {
            sampleCount: this.collectedCount + this.form.audioIds.length
          });
          this.$message.success(this.$t('voicePrintDialog.appendSuccess'));
        } else {
          this.$message.error(data.msg || this.$t('voicePrintDialog.appendFailed'));
        }
      }, () => {
        this.appendingSamples = false;
        this.$message.warning(this.$t('voicePrintDialog.enrollmentUnavailable'));
      });
    },
    completeEnrollment() {
      this.validateBasicFields(() => {
        if (!this.canCompleteEnrollment) {
          return;
        }
        this.completingEnrollment = true;
        api.agent.completeAgentVoicePrintEnrollment(this.enrollmentId, {
          agentId: this.agentId,
          sourceName: this.form.sourceName,
          introduce: this.form.introduce
        }, ({ data }) => {
          this.completingEnrollment = false;
          if (data.code === 0 || data.code === undefined) {
            this.stopPolling();
            this.$message.success(this.$t('voicePrintDialog.completeSuccess'));
            this.$emit('submit', {
              form: {
                ...this.form,
                enrollmentId: this.enrollmentId,
                collectMethod: 'device',
                completedByEnrollment: true
              },
              done: () => {}
            });
          } else {
            this.$message.error(data.msg || this.$t('voicePrintDialog.completeFailed'));
          }
        }, () => {
          this.completingEnrollment = false;
          this.$message.warning(this.$t('voicePrintDialog.enrollmentUnavailable'));
        });
      });
    },
    getAudioIconClass(audioId) {
      if (this.playingAudioId === audioId) {
        return 'el-icon-loading';
      }
      return 'el-icon-video-play';
    },
    playAudio(audioId) {
      if (this.playingAudioId === audioId) {
        // 如果正在播放当前音频，则停止播放
        if (this.audioElement) {
          this.audioElement.pause();
          this.audioElement = null;
        }
        this.playingAudioId = null;
        return;
      }

      // 停止当前正在播放的音频
      if (this.audioElement) {
        this.audioElement.pause();
        this.audioElement = null;
      }

      // 先获取音频下载ID
      this.playingAudioId = audioId;
      api.agent.getAudioId(audioId, (res) => {
        if (res.data && res.data.data) {
          // 使用获取到的下载ID播放音频
          this.audioElement = new Audio(api.getServiceUrl() + `/agent/play/${res.data.data}`);

          this.audioElement.onended = () => {
            this.playingAudioId = null;
            this.audioElement = null;
          };

          this.audioElement.play();
        }
      });
    },
    submit() {
      if (!this.form.id && this.collectMethod === 'device') {
        this.completeEnrollment();
        return;
      }
      this.$refs.form.validate((valid) => {
        if (valid) {
          this.saving = true; // 开始加载
          this.$emit('submit', {
            form: this.form,
            done: () => {
              this.saving = false; // 加载完成
            }
          });

          setTimeout(() => {
            this.saving = false;
          }, 3000);
        }
      });
    },
    cancel() {
      this.saving = false; // 取消时重置状态
      this.stopPolling();
      this.dialogKey = Date.now();
      this.$emit('cancel');
    },
    resetEnrollmentState() {
      this.collectMethod = this.form.id ? 'history' : 'device';
      this.deviceId = this.devices.length === 1 ? this.devices[0].deviceId : '';
      this.requiredSamples = 3;
      this.enrollmentId = '';
      this.enrollmentStatus = 'idle';
      this.enrollmentProgress = 0;
      this.collectedCount = 0;
      this.creatingEnrollment = false;
      this.appendingSamples = false;
      this.completingEnrollment = false;
      this.enrollmentUnsupported = false;
      this.stopPolling();
    }
  },
  watch: {
    visible(newVal) {
      if (newVal) {
        this.resetEnrollmentState();
        api.agent.getRecentlyFiftyByAgentId(this.agentId, ((data) => {
          this.valueTypeOptions = data.data.data.map(item => ({
            ...item
          }));
        }))
      } else {
        this.stopPolling();
      }
    },
    'form.audioIds': {
      handler(newVal) {
        if (!Array.isArray(newVal) || newVal.length === 0) {
          return;
        }
        newVal.forEach(audioId => {
          if (audioId == null || audioId == "") {
            return;
          }
          if (this.valueTypeOptions.some(item => item.audioId === audioId)) {
            return;
          }
          api.agent.getContentByAudioId(audioId, ((data) => {
            this.valueTypeOptions.push({
              audioId, content: data.data.data
            })
          }))
        });
      },
      deep: true
    },
    'form.audioId'(newVal) {
      if (newVal == null || newVal == "" || Array.isArray(this.form.audioIds)) {
        return
      }
      if (this.valueTypeOptions.some(item => item.audioId === newVal)) {
        return;
      }
      api.agent.getContentByAudioId(newVal, ((data) => {
        this.valueTypeOptions.push({
          audioId: newVal, content: data.data.data
        })
      }))
    }
  },
  beforeDestroy() {
    this.stopPolling();
  }
};
</script>

<style>
.custom-param-dialog {
  border-radius: 16px !important;
  overflow: hidden;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15) !important;
  border: none !important;

  .el-dialog__header {
    display: none;
  }

  .el-dialog__body {
    padding: 0 !important;
    border-radius: 16px;
  }
}
</style>

<style scoped lang="scss">
.audio-icon {
  font-size: 20px;
  cursor: pointer;
  margin: 0 5px;
  color: #1890ff;
}

.param-dialog-wrapper {
  .dialog-container {
    padding: 24px 32px;
    background: linear-gradient(135deg, #f8fafc 0%, #f1f5f9 100%);
  }

  .dialog-header {
    position: relative;
    margin-bottom: 24px;
    text-align: center;
  }

  .dialog-title {
    font-size: 20px;
    color: #1e293b;
    margin: 0;
    padding: 0;
    font-weight: 600;
    letter-spacing: 0.5px;
  }

  .custom-close-btn {
    position: absolute;
    top: -8px;
    right: -8px;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    border: none;
    background: #f1f5f9;
    color: #64748b;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0;
    outline: none;
    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);

    &:hover {
      color: #ffffff;
      background: #ef4444;
      transform: rotate(90deg);
      box-shadow: 0 4px 6px rgba(239, 68, 68, 0.2);
    }

    svg {
      transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    }
  }

  .param-form {
    .form-item {
      margin-bottom: 20px;

      :deep(.el-form-item__label) {
        color: #475569;
        font-weight: 500;
        padding-right: 12px;
        text-align: right;
        font-size: 14px;
        letter-spacing: 0.2px;
      }
    }

    .custom-input {
      :deep(.el-input__inner) {
        background-color: #ffffff;
        border-radius: 8px;
        border: 1px solid #e2e8f0;
        height: 42px;
        padding: 0 14px;
        transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
        font-size: 14px;
        color: #334155;
        box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);

        &:focus {
          border-color: #3b82f6;
          box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.2);
          background-color: #ffffff;
        }

        &::placeholder {
          color: #94a3b8;
          font-weight: 400;
        }
      }
    }

    .custom-select {
      width: 100%;

      :deep(.el-input__inner) {
        background-color: #ffffff;
        border-radius: 8px;
        border: 1px solid #e2e8f0;
        height: 42px;
        transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
        font-size: 14px;
        color: #334155;
        box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);

        &:focus {
          border-color: #3b82f6;
          box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.2);
          background-color: #ffffff;
        }

        &::placeholder {
          color: #94a3b8;
          font-weight: 400;
        }
      }
    }

    .custom-textarea {
      :deep(.el-textarea__inner) {
        background-color: #ffffff;
        border-radius: 8px;
        border: 1px solid #e2e8f0;
        padding: 12px 14px;
        transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
        font-size: 14px;
        color: #334155;
        box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
        line-height: 1.5;

        &:focus {
          border-color: #3b82f6;
          box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.2);
          background-color: #ffffff;
        }

        &::placeholder {
          color: #94a3b8;
          font-weight: 400;
        }
      }
    }

    .remark-item :deep(.el-form-item__label) {
      margin-top: -4px;
    }

    .method-group {
      :deep(.el-radio-button__inner) {
        min-width: 112px;
      }
    }

    .enrollment-panel,
    .history-panel {
      margin: 0 0 18px 96px;
      padding: 14px 16px;
      border: 1px solid #e2e8f0;
      border-radius: 8px;
      background: #ffffff;
    }

    .task-head,
    .history-head,
    .sample-row,
    .device-actions,
    .append-row {
      display: flex;
      align-items: center;
    }

    .task-head,
    .history-head {
      justify-content: space-between;
      margin-bottom: 12px;
    }

    .task-title,
    .history-head {
      color: #1e293b;
      font-size: 14px;
      font-weight: 600;
    }

    .task-subtitle {
      color: #64748b;
      font-size: 12px;
      line-height: 1.5;
      margin-top: 4px;
    }

    .sample-row {
      justify-content: space-between;
      margin-top: 10px;
      color: #64748b;
      font-size: 12px;
    }

    .task-id {
      max-width: 260px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      text-align: right;
    }

    .fallback-alert {
      margin-top: 12px;
    }

    .device-actions {
      gap: 10px;
      margin-top: 14px;
      flex-wrap: wrap;
    }

    .history-head {
      gap: 10px;
    }

    .sample-item {
      margin-bottom: 10px;

      :deep(.el-form-item__label) {
        text-align: left;
      }
    }

    .append-row {
      justify-content: flex-end;
    }
  }

  .dialog-footer {
    display: flex;
    justify-content: center;
    padding: 16px 0 0;
    margin-top: 16px;

    .save-btn {
      width: 120px;
      height: 42px;
      font-size: 14px;
      font-weight: 500;
      border-radius: 8px;
      transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
      background: #3b82f6;
      color: white;
      border: none;
      letter-spacing: 0.5px;
      box-shadow: 0 2px 4px rgba(59, 130, 246, 0.2);

      &:hover {
        background: #2563eb;
        transform: translateY(-1px);
        box-shadow: 0 4px 6px rgba(59, 130, 246, 0.3);
      }

      &:active {
        transform: translateY(0);
        box-shadow: 0 2px 3px rgba(59, 130, 246, 0.2);
      }
    }

    .cancel-btn {
      width: 120px;
      height: 42px;
      font-size: 14px;
      font-weight: 500;
      border-radius: 8px;
      transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
      background: #ffffff;
      color: #64748b;
      border: 1px solid #e2e8f0;
      margin-left: 16px;
      letter-spacing: 0.5px;
      box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);

      &:hover {
        background: #f8fafc;
        color: #475569;
        border-color: #cbd5e1;
        transform: translateY(-1px);
        box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
      }

      &:active {
        transform: translateY(0);
        box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
      }
    }
  }
}
</style>
