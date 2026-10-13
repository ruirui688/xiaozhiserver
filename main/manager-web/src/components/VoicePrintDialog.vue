<template>
  <el-dialog :title="title" :visible="visible" width="680px" class="param-dialog-wrapper" :append-to-body="true"
    :close-on-click-modal="false" :close-on-press-escape="!saving && !completingEnrollment"
    :before-close="beforeDialogClose" :key="dialogKey" custom-class="custom-param-dialog" :show-close="false">
    <div class="dialog-container">
      <div class="dialog-header">
        <h2 class="dialog-title">{{ title }}</h2>
        <button class="custom-close-btn" :disabled="saving || completingEnrollment" @click="cancel">
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

        <el-form-item v-if="!form.id" :label="$t('voicePrintDialog.device')" class="form-item">
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

        <el-form-item v-if="!form.id" :label="$t('voicePrintDialog.requiredSamples')" class="form-item">
          <div class="single-sample-value">{{ $t('voicePrintDialog.singleSampleValue') }}</div>
        </el-form-item>

        <div v-if="!form.id" class="sample-text-panel">
          <div class="sample-text-head">{{ $t('voicePrintDialog.sampleTextTitle') }}</div>
          <div class="sample-text-content">{{ $t('voicePrintDialog.sampleTextContent') }}</div>
          <div class="sample-text-tip">{{ $t('voicePrintDialog.sampleTextTip') }}</div>
        </div>

        <div v-if="!form.id" class="enrollment-panel">
          <div class="task-head">
            <div>
              <div class="task-title">{{ $t('voicePrintDialog.enrollmentTask') }}</div>
              <div class="task-subtitle">{{ enrollmentHint }}</div>
            </div>
            <el-tag size="mini" :type="enrollmentTagType">{{ enrollmentStatusText }}</el-tag>
          </div>

          <el-progress :percentage="enrollmentProgress" :status="enrollmentProgressStatus"></el-progress>

          <div class="sample-row">
            <span>{{ $t('voicePrintDialog.collectedSamples', { count: collectedCount, total: requiredSamples }) }}</span>
            <span v-if="enrollmentId" class="task-id">{{ $t('voicePrintDialog.taskId') }} {{ enrollmentId }}</span>
          </div>

          <el-alert
            v-if="enrollmentUnsupported"
              type="warning"
              :closable="false"
              class="fallback-alert"
              :title="$t('voicePrintDialog.enrollmentUnavailable')"
              :description="enrollmentErrorMessage || $t('voicePrintDialog.enrollmentUnavailableDesc')">
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
          </div>
        </div>
      </el-form>

      <div class="dialog-footer">
        <el-button v-if="form.id" type="primary" @click="submit" class="save-btn" :loading="saving" :disabled="saving || primarySubmitDisabled">
          {{ primarySubmitText }}
        </el-button>
        <el-button @click="cancel" class="cancel-btn" :disabled="saving || completingEnrollment">
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
      deviceId: '',
      requiredSamples: 1,
      enrollmentId: '',
      enrollmentStatus: 'idle',
      enrollmentProgress: 0,
      collectedCount: 0,
      creatingEnrollment: false,
      completingEnrollment: false,
      registrationSubmitted: false,
      enrollmentUnsupported: false,
      enrollmentErrorMessage: '',
      pollingTimer: null,
      lifecycleGeneration: 0,
      enrollmentGeneration: 0,
      enrollmentContext: null,
      destroyed: false,
      minSamples: 1,
      maxSamples: 1,
      rules: {
        introduce: [
          { required: true, message: '请输入描述', trigger: "blur" }
        ],
        sourceName: [
          { required: true, message: '请输入名称', trigger: "blur" }
        ]
      }
    };
  },
  computed: {
    enrollmentHint() {
      if (this.enrollmentUnsupported) {
        return this.$t('voicePrintDialog.failedHint');
      }
      if (!this.enrollmentId) {
        return this.$t('voicePrintDialog.deviceCollectHint');
      }
      if (this.enrollmentStatus === 'collecting') {
        return this.$t('voicePrintDialog.collectingHint');
      }
      if (this.completingEnrollment) {
        return this.$t('voicePrintDialog.registeringHint');
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
        registering: this.$t('voicePrintDialog.statusRegistering'),
        ready: this.$t('voicePrintDialog.statusReady'),
        failed: this.$t('voicePrintDialog.statusFailed')
      };
      if (this.completingEnrollment) {
        return statusMap.registering;
      }
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
      return Boolean(this.enrollmentId)
        && this.enrollmentStatus === 'ready'
        && !this.completingEnrollment
        && !this.registrationSubmitted;
    },
    primarySubmitText() {
      if (!this.form.id) {
        return this.$t('voicePrintDialog.completeEnrollment');
      }
      return this.$t('voicePrintDialog.save');
    },
    primarySubmitDisabled() {
      return !this.form.id && !this.canCompleteEnrollment;
    }
  },
  methods: {
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
      const payload = (data && data.data ? data.data : data) || {};
      const status = String(payload.status || payload.enrollmentStatus || this.enrollmentStatus || 'collecting').toLowerCase();
      const count = Number(payload.collectedCount || payload.sampleCount || payload.samples || this.collectedCount || 0);
      const requiredSamples = Number(payload.requiredSamples || this.requiredSamples || this.minSamples);
      const progress = payload.progress != null
        ? Number(payload.progress)
        : Math.min(100, Math.round((count / requiredSamples) * 100));
      this.enrollmentId = payload.id || payload.enrollmentId || this.enrollmentId;
      this.requiredSamples = Math.max(this.minSamples, Math.min(this.maxSamples, requiredSamples));
      this.enrollmentStatus = status === 'completed'
        ? 'ready'
        : (['cancelled', 'expired'].includes(status) ? 'failed' : status);
      this.collectedCount = count;
      this.enrollmentProgress = Math.max(0, Math.min(100, progress));
      if (this.collectedCount >= this.requiredSamples && this.enrollmentStatus === 'collecting') {
        this.enrollmentStatus = 'ready';
      }
    },
    markEnrollmentUnsupported(error) {
      this.enrollmentUnsupported = true;
      this.enrollmentErrorMessage = this.extractResponseMessage(
        error,
        this.$t('voicePrintDialog.enrollmentUnavailableDesc')
      );
      this.enrollmentStatus = 'failed';
      this.enrollmentProgress = 0;
      this.registrationSubmitted = false;
      this.stopPolling();
    },
    extractResponseMessage(error, fallback) {
      return (error && error.data && (error.data.msg || error.data.message))
        || (error && (error.msg || error.message))
        || fallback;
    },
    formatRegistrationResult(result) {
      const quality = result && result.quality;
      if (!quality) {
        return this.$t('voicePrintDialog.completeSuccess');
      }
      const parts = [];
      if (quality.score !== undefined && quality.score !== null) {
        parts.push(`质量分 ${quality.score}`);
      }
      if (quality.level) {
        parts.push(`等级 ${quality.level}`);
      }
      if (quality.effective_speech_seconds !== undefined && quality.effective_speech_seconds !== null) {
        parts.push(`有效语音 ${quality.effective_speech_seconds}s`);
      }
      return parts.length
        ? `${this.$t('voicePrintDialog.completeSuccess')}，${parts.join('，')}`
        : this.$t('voicePrintDialog.completeSuccess');
    },
    isLifecycleActive(generation) {
      return !this.destroyed
        && this.visible
        && generation === this.lifecycleGeneration;
    },
    beginEnrollmentOperation(agentId) {
      this.stopPolling();
      const context = {
        lifecycleGeneration: this.lifecycleGeneration,
        enrollmentGeneration: ++this.enrollmentGeneration,
        agentId
      };
      this.enrollmentContext = context;
      return context;
    },
    isEnrollmentContextActive(context) {
      return Boolean(
        context
        && this.isLifecycleActive(context.lifecycleGeneration)
        && context === this.enrollmentContext
        && context.enrollmentGeneration === this.enrollmentGeneration
        && context.agentId === this.agentId
      );
    },
    extractEnrollmentId(data) {
      const payload = (data && data.data ? data.data : data) || {};
      return payload.id || payload.enrollmentId || '';
    },
    cancelEnrollmentById(enrollmentId) {
      if (!enrollmentId) return;
      api.agent.cancelAgentVoicePrintEnrollment(enrollmentId, () => {}, () => {});
    },
    cancelStaleEnrollment(data) {
      this.cancelEnrollmentById(this.extractEnrollmentId(data));
    },
    activateEnrollmentLifecycle() {
      this.lifecycleGeneration += 1;
      this.enrollmentGeneration += 1;
      this.enrollmentContext = null;
      this.resetEnrollmentState();
    },
    deactivateEnrollmentLifecycle() {
      const wasCompleting = this.completingEnrollment;
      const shouldCancel = !this.registrationSubmitted
        && this.enrollmentId
        && ['collecting', 'ready'].includes(this.enrollmentStatus);
      const enrollmentId = shouldCancel ? this.enrollmentId : '';
      this.lifecycleGeneration += 1;
      this.enrollmentGeneration += 1;
      this.enrollmentContext = null;
      this.stopPolling();
      this.enrollmentId = '';
      this.creatingEnrollment = false;
      this.completingEnrollment = false;
      if (enrollmentId) this.cancelEnrollmentById(enrollmentId);
      if (wasCompleting) this.$emit('busy-change', false);
    },
    startEnrollment() {
      if (this.creatingEnrollment || this.completingEnrollment) return;
      const lifecycleGeneration = this.lifecycleGeneration;
      this.validateBasicFields(() => {
        if (!this.isLifecycleActive(lifecycleGeneration)) return;
        if (!this.deviceId) {
          this.$message.warning(this.$t('voicePrintDialog.requiredDevice'));
          return;
        }
        const agentId = this.agentId;
        const payload = {
          agentId,
          sourceName: this.form.sourceName,
          introduce: this.form.introduce,
          deviceId: this.deviceId,
          requiredSamples: this.requiredSamples
        };
        const context = this.beginEnrollmentOperation(agentId);
        if (this.enrollmentId && ['collecting', 'ready'].includes(this.enrollmentStatus)) {
          const previousId = this.enrollmentId;
          this.enrollmentId = '';
          this.creatingEnrollment = true;
          api.agent.cancelAgentVoicePrintEnrollment(previousId, () => {
            if (this.isEnrollmentContextActive(context)) this.createEnrollment(payload, context);
          }, () => {
            if (this.isEnrollmentContextActive(context)) this.createEnrollment(payload, context);
          });
          return;
        }
        this.createEnrollment(payload, context);
      });
    },
    createEnrollment(payload, context) {
      if (!this.isEnrollmentContextActive(context)) return;
      this.creatingEnrollment = true;
      this.stopPolling();
      this.enrollmentUnsupported = false;
      this.enrollmentErrorMessage = '';
      this.registrationSubmitted = false;
      this.enrollmentId = '';
      this.enrollmentStatus = 'collecting';
      this.enrollmentProgress = 0;
      this.collectedCount = 0;
      api.agent.createAgentVoicePrintEnrollment(payload, ({ data }) => {
        if (!this.isEnrollmentContextActive(context)) {
          if (!data || data.code === 0 || data.code === undefined) this.cancelStaleEnrollment(data);
          return;
        }
        this.creatingEnrollment = false;
        if (data && (data.code === 0 || data.code === undefined)) {
          this.normalizeEnrollment(data.data || {});
          if (!this.enrollmentId) this.enrollmentStatus = 'collecting';
          this.startPolling(context);
        } else {
          this.markEnrollmentUnsupported(data);
        }
      }, (error) => {
        if (!this.isEnrollmentContextActive(context)) return;
        this.creatingEnrollment = false;
        this.markEnrollmentUnsupported(error);
      });
    },
    startPolling(context) {
      this.stopPolling();
      if (!this.isEnrollmentContextActive(context) || !this.enrollmentId) return;
      const enrollmentId = this.enrollmentId;
      this.pollingTimer = setInterval(() => {
        if (!this.isEnrollmentContextActive(context)) return;
        api.agent.getAgentVoicePrintEnrollment(context.agentId, enrollmentId, ({ data }) => {
          if (!this.isEnrollmentContextActive(context)) return;
          if (data && (data.code === 0 || data.code === undefined)) {
            this.normalizeEnrollment(data.data || {});
            if (this.enrollmentStatus === 'ready') {
              this.stopPolling();
              this.completeEnrollment(context);
            } else if (this.enrollmentStatus === 'failed') {
              this.stopPolling();
            }
          }
        }, () => {
          if (!this.isEnrollmentContextActive(context)) return;
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
    cancelActiveEnrollment() {
      if (!this.registrationSubmitted
          && this.enrollmentId
          && ['collecting', 'ready'].includes(this.enrollmentStatus)) {
        const enrollmentId = this.enrollmentId;
        this.enrollmentId = '';
        this.cancelEnrollmentById(enrollmentId);
      }
    },
    completeEnrollment(context = this.enrollmentContext) {
      this.validateBasicFields(() => {
        if (!this.isEnrollmentContextActive(context) || !this.canCompleteEnrollment) return;
        const enrollmentId = this.enrollmentId;
        const sourceName = this.form.sourceName;
        const introduce = this.form.introduce;
        this.completingEnrollment = true;
        this.registrationSubmitted = true;
        this.$emit('busy-change', true);
        api.agent.completeAgentVoicePrintEnrollment(enrollmentId, {
          agentId: context.agentId,
          sourceName,
          introduce
        }, ({ data }) => {
          if (!this.isEnrollmentContextActive(context)) return;
          if (data && (data.code === 0 || data.code === undefined)) {
            this.stopPolling();
            const result = data.data && data.data.registrationResult;
            const voicePrintId = data.data && data.data.voicePrintId;
            this.$message.success(this.formatRegistrationResult(result));
            this.$emit('completed', {
              voicePrintId,
              agentId: context.agentId,
              sourceName,
              introduce
            });
            this.$emit('submit', {
              form: {
                ...this.form,
                enrollmentId,
                voicePrintId,
                completedByEnrollment: true
              },
              done: () => {}
            });
            this.completingEnrollment = false;
            this.$emit('busy-change', false);
          } else {
            this.completingEnrollment = false;
            this.$emit('busy-change', false);
            this.registrationSubmitted = false;
            this.enrollmentStatus = 'failed';
            this.$message.error((data && data.msg) || this.$t('voicePrintDialog.completeFailed'));
          }
        }, (err) => {
          if (!this.isEnrollmentContextActive(context)) return;
          this.completingEnrollment = false;
          this.$emit('busy-change', false);
          this.registrationSubmitted = false;
          this.enrollmentStatus = 'failed';
          this.$message.error(this.extractResponseMessage(err, this.$t('voicePrintDialog.completeFailed')));
        });
      });
    },
    submit() {
      if (!this.form.id) {
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
        }
      });
    },
    beforeDialogClose(done) {
      if (this.saving || this.completingEnrollment) return;
      this.cancel();
      if (done) done(false);
    },
    cancel() {
      if (this.saving || this.completingEnrollment) return;
      this.saving = false; // 取消时重置状态
      this.deactivateEnrollmentLifecycle();
      this.dialogKey = Date.now();
      this.$emit('cancel');
    },
    resetEnrollmentState() {
      this.deviceId = this.devices.length === 1 ? this.devices[0].deviceId : '';
      this.requiredSamples = 1;
      this.enrollmentId = '';
      this.enrollmentStatus = 'idle';
      this.enrollmentProgress = 0;
      this.collectedCount = 0;
      this.creatingEnrollment = false;
      this.completingEnrollment = false;
      this.registrationSubmitted = false;
      this.enrollmentUnsupported = false;
      this.enrollmentErrorMessage = '';
      this.stopPolling();
    }
  },
  watch: {
    visible: {
      immediate: true,
      handler(newVal) {
        if (newVal) {
          this.activateEnrollmentLifecycle();
        } else {
          this.deactivateEnrollmentLifecycle();
        }
      }
    }
  },
  beforeDestroy() {
    this.destroyed = true;
    this.deactivateEnrollmentLifecycle();
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

    .single-sample-value {
      height: 42px;
      display: flex;
      align-items: center;
      color: #334155;
      font-size: 14px;
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

    .sample-text-panel,
    .enrollment-panel {
      margin: 0 0 18px 96px;
      padding: 14px 16px;
      border: 1px solid #e2e8f0;
      border-radius: 8px;
      background: #ffffff;
    }

    .sample-text-panel {
      border-color: #bfdbfe;
      background: #eff6ff;
    }

    .sample-text-head {
      color: #1e293b;
      font-size: 14px;
      font-weight: 600;
      margin-bottom: 8px;
    }

    .sample-text-content {
      color: #334155;
      font-size: 14px;
      line-height: 1.7;
    }

    .sample-text-tip {
      margin-top: 8px;
      color: #64748b;
      font-size: 12px;
      line-height: 1.5;
    }

    .task-head,
    .sample-row,
    .device-actions {
      display: flex;
      align-items: center;
    }

    .task-head {
      justify-content: space-between;
      margin-bottom: 12px;
    }

    .task-title {
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
