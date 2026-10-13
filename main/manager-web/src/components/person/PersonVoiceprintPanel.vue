<template>
  <div class="person-voiceprint">
    <div class="voiceprint-section__heading">
      <div>
        <label class="section-label">声纹信息</label>
        <p>通过已绑定智能体的在线设备完成录音，后续交互可识别当前用户。</p>
      </div>
      <el-tag size="mini" :type="voicePrint.ready ? 'success' : 'info'">
        {{ voicePrint.ready ? "已录入" : "未录入" }}
      </el-tag>
    </div>

    <div v-if="voicePrint.ready" class="voiceprint-summary">
      <i class="el-icon-microphone" />
      <div>
        <strong>{{ voicePrint.sourceName || personName }}</strong>
        <span>{{ voicePrint.description || "已关联声纹信息" }}</span>
        <small v-if="voicePrint.createDate">录入时间：{{ formatDate(voicePrint.createDate) }}</small>
      </div>
    </div>
    <div v-else class="voiceprint-empty">
      <i class="el-icon-upload2" />
      <span>{{ personId ? "尚未录入声纹" : "请先保存基础资料，再录入声纹" }}</span>
    </div>

    <el-select
      v-if="personId && agentOptions.length > 1"
      v-model="selectedAgentId"
      size="small"
      class="voice-agent-select"
      placeholder="选择用于录音的智能体"
      :disabled="busy || voiceDialogVisible"
    >
      <el-option v-for="agent in agentOptions" :key="agent.id" :label="agent.name" :value="agent.id" />
    </el-select>

    <p v-if="personId && !agentOptions.length" class="voiceprint-warning">
      请先为用户绑定至少一个智能体并保存，才能录入新声纹；仍可关联工作空间内已有声纹。
    </p>

    <div v-if="canWrite && personId" class="voiceprint-actions">
      <template v-if="!voicePrint.ready">
        <el-button
          size="small"
          type="primary"
          plain
          :disabled="!selectedAgentId || busy"
          :loading="loadingDevices"
          @click="startEnrollment"
        >录入新声纹</el-button>
        <el-button
          size="small"
          :disabled="busy"
          :loading="loadingCandidates"
          @click="openExistingVoicePrints"
        >关联已有声纹</el-button>
      </template>
      <template v-else>
        <el-button size="small" type="primary" plain :disabled="busy" @click="editVoicePrint">
          编辑声纹资料
        </el-button>
        <el-button size="small" type="text" :disabled="busy" @click="unbindVoicePrint">解除关联</el-button>
        <el-button
          size="small"
          type="text"
          class="delete-voiceprint"
          :loading="deleting"
          :disabled="busy && !deleting"
          @click="deleteVoicePrint"
        >删除声纹</el-button>
      </template>
    </div>

    <VoicePrintDialog
      :key="voiceDialogKey"
      :title="voiceDialogTitle"
      :visible="voiceDialogVisible"
      :agent-id="dialogAgentId"
      :devices="deviceOptions"
      :form="voiceForm"
      @completed="handleEnrollmentCompleted"
      @submit="handleVoiceSubmit"
      @cancel="closeVoiceDialog"
      @busy-change="handleEnrollmentBusyChange"
    />

    <el-dialog
      title="关联已有声纹"
      :visible.sync="existingDialogVisible"
      width="520px"
      append-to-body
      :close-on-click-modal="false"
    >
      <p class="existing-hint">仅显示当前工作空间内，尚未归属人物或已归属当前用户的声纹。</p>
      <el-radio-group v-if="availableVoicePrints.length" v-model="selectedVoicePrintId" class="existing-list">
        <el-radio
          v-for="item in availableVoicePrints"
          :key="item.id"
          :label="item.id"
          border
          class="existing-item"
        >
          <strong>{{ item.sourceName }}</strong>
          <span>{{ item.introduce || "暂无描述" }}</span>
          <small>{{ agentLabel(item.agentId) }}</small>
        </el-radio>
      </el-radio-group>
      <el-empty v-else description="没有可关联的声纹" :image-size="72" />
      <div slot="footer">
        <el-button @click="existingDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :disabled="!selectedVoicePrintId"
          :loading="binding"
          @click="bindSelectedVoicePrint"
        >确认关联</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import Api from "@/apis/api";
import VoicePrintDialog from "@/components/VoicePrintDialog.vue";

function emptyVoiceForm() {
  return {
    id: null,
    audioId: "",
    audioIds: [],
    sourceName: "",
    introduce: ""
  };
}

export default {
  name: "PersonVoiceprintPanel",
  components: { VoicePrintDialog },
  props: {
    personId: {
      type: String,
      default: ""
    },
    personName: {
      type: String,
      default: ""
    },
    personDescription: {
      type: String,
      default: ""
    },
    agentIds: {
      type: Array,
      default: () => []
    },
    agents: {
      type: Array,
      default: () => []
    },
    voicePrint: {
      type: Object,
      default: () => ({ ready: false })
    },
    canWrite: {
      type: Boolean,
      default: false
    },
    active: {
      type: Boolean,
      default: true
    }
  },
  data() {
    return {
      selectedAgentId: "",
      deviceOptions: [],
      loadingDevices: false,
      voiceDialogVisible: false,
      voiceDialogKey: 0,
      voiceDialogContext: null,
      dialogAgentId: "",
      voiceDialogTitle: "录入声纹",
      voiceForm: emptyVoiceForm(),
      existingDialogVisible: false,
      loadingCandidates: false,
      availableVoicePrints: [],
      selectedVoicePrintId: "",
      binding: false,
      updating: false,
      deleting: false,
      voiceOperationBusy: false,
      contextGeneration: 0
    };
  },
  computed: {
    agentOptions() {
      const ids = [...new Set([
        ...(this.agentIds || []),
        this.voicePrint.agentId
      ].filter(Boolean))];
      return ids.map(id => {
        const agent = this.agents.find(item => item.id === id);
        return agent || { id, name: id };
      });
    },
    busy() {
      return this.loadingDevices
        || this.loadingCandidates
        || this.binding
        || this.updating
        || this.deleting
        || this.voiceOperationBusy;
    }
  },
  watch: {
    personId: {
      immediate: true,
      handler(value, oldValue) {
        if (value !== oldValue) this.invalidateContext();
        this.syncSelectedAgent();
      }
    },
    active: {
      immediate: true,
      handler(value, oldValue) {
        if (value !== oldValue) this.invalidateContext();
        if (value) this.syncSelectedAgent();
      }
    },
    agentOptions: {
      deep: true,
      handler() {
        this.syncSelectedAgent();
      }
    },
    "voicePrint.agentId"() {
      this.syncSelectedAgent();
    }
  },
  beforeDestroy() {
    this.invalidateContext();
  },
  methods: {
    invalidateContext() {
      this.contextGeneration += 1;
      this.voiceDialogVisible = false;
      this.existingDialogVisible = false;
      this.voiceDialogContext = null;
      this.dialogAgentId = "";
      this.voiceDialogKey += 1;
      this.deviceOptions = [];
      this.availableVoicePrints = [];
      this.selectedVoicePrintId = "";
      this.loadingDevices = false;
      this.loadingCandidates = false;
      this.binding = false;
      this.updating = false;
      this.deleting = false;
      this.setVoiceOperationBusy(false);
    },
    captureContext(extra = {}) {
      return {
        personId: this.personId,
        generation: this.contextGeneration,
        ...extra
      };
    },
    isContextActive(context) {
      return Boolean(
        context
        && context.personId
        && this.active
        && context.personId === this.personId
        && context.generation === this.contextGeneration
      );
    },
    emitChanged(context, payload) {
      if (!this.isContextActive(context)) return;
      this.$emit("changed", { personId: context.personId, ...payload });
    },
    openVoiceDialog(context, title, form) {
      if (!this.isContextActive(context)) return;
      this.voiceDialogContext = context;
      this.dialogAgentId = context.agentId || this.voicePrint.agentId || this.selectedAgentId;
      this.voiceDialogKey += 1;
      this.voiceDialogTitle = title;
      this.voiceForm = form;
      this.voiceDialogVisible = true;
    },
    closeVoiceDialog({ preserveOperationBusy = false } = {}) {
      if (this.voiceOperationBusy && !preserveOperationBusy) return;
      this.voiceDialogVisible = false;
      this.voiceDialogContext = null;
      this.dialogAgentId = "";
      if (!preserveOperationBusy) this.setVoiceOperationBusy(false);
      this.voiceDialogKey += 1;
    },
    setVoiceOperationBusy(value) {
      const nextValue = Boolean(value);
      if (this.voiceOperationBusy === nextValue) return;
      this.voiceOperationBusy = nextValue;
      this.$emit("operation-busy-change", nextValue);
    },
    handleEnrollmentBusyChange(value) {
      if (!this.isContextActive(this.voiceDialogContext)) return;
      this.setVoiceOperationBusy(value);
    },
    syncSelectedAgent() {
      const preferred = this.voicePrint.agentId;
      const ids = this.agentOptions.map(item => item.id);
      if (preferred && ids.includes(preferred)) {
        this.selectedAgentId = preferred;
      } else if (!ids.includes(this.selectedAgentId)) {
        this.selectedAgentId = ids[0] || "";
      }
    },
    startEnrollment() {
      if (this.loadingDevices) return;
      if (!this.selectedAgentId) {
        this.$message.warning("请先选择用于录音的智能体");
        return;
      }
      const agentId = this.selectedAgentId;
      const context = this.captureContext({ agentId });
      const personName = this.personName;
      const personDescription = this.personDescription;
      this.loadingDevices = true;
      Api.device.getAgentBindDevices(agentId, ({ data }) => {
        if (!this.isContextActive(context)) return;
        this.loadingDevices = false;
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "获取智能体设备失败，暂时无法录入声纹");
          return;
        }
        this.deviceOptions = (data.data || []).map(device => ({
          deviceId: device.macAddress || device.id,
          label: device.alias || device.macAddress || device.id,
          board: device.board || ""
        })).filter(device => device.deviceId);
        if (!this.deviceOptions.length) {
          this.$message.warning("该智能体没有可用的绑定设备，请先绑定并唤醒设备");
          return;
        }
        this.openVoiceDialog(context, "录入声纹", {
          ...emptyVoiceForm(),
          sourceName: personName,
          introduce: personDescription
        });
      });
    },
    editVoicePrint() {
      const context = this.captureContext({
        agentId: this.voicePrint.agentId || this.selectedAgentId
      });
      this.openVoiceDialog(context, "编辑声纹资料", {
        ...emptyVoiceForm(),
        id: this.voicePrint.id,
        sourceName: this.voicePrint.sourceName || this.personName,
        introduce: this.voicePrint.description || ""
      });
    },
    handleEnrollmentCompleted(payload) {
      const context = this.voiceDialogContext;
      if (!this.isContextActive(context)) return;
      this.setVoiceOperationBusy(true);
      this.closeVoiceDialog({ preserveOperationBusy: true });
      if (!payload || !payload.voicePrintId) {
        this.setVoiceOperationBusy(false);
        this.$message.error("声纹已完成注册，但未返回可关联的声纹 ID");
        return;
      }
      this.bindVoicePrint(payload.voicePrintId, {
        agentId: context.agentId || payload.agentId || this.selectedAgentId,
        sourceName: payload.sourceName,
        description: payload.introduce
      }, context);
    },
    handleVoiceSubmit({ form, done }) {
      if (form.completedByEnrollment) {
        done && done();
        return;
      }
      if (!form.id) {
        done && done();
        return;
      }
      if (this.updating) return;
      const context = this.voiceDialogContext;
      if (!this.isContextActive(context)) {
        done && done();
        return;
      }
      const agentId = context.agentId || this.voicePrint.agentId || this.selectedAgentId;
      const createDate = this.voicePrint.createDate || null;
      this.updating = true;
      this.setVoiceOperationBusy(true);
      Api.agent.updateAgentVoicePrint({
        id: form.id,
        sourceName: form.sourceName,
        introduce: form.introduce
      }, ({ data }) => {
        done && done();
        if (!this.isContextActive(context)) return;
        this.updating = false;
        this.setVoiceOperationBusy(false);
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "更新声纹资料失败");
          return;
        }
        this.closeVoiceDialog();
        this.emitChanged(context, {
          fields: {
            voicePrintReady: true,
            voicePrintId: form.id,
            voicePrintAgentId: agentId,
            voicePrintSourceName: form.sourceName,
            voicePrintDescription: form.introduce,
            voicePrintCreateDate: createDate
          }
        });
        this.$message.success("声纹资料已更新");
      }, err => {
        done && done();
        if (!this.isContextActive(context)) return;
        this.updating = false;
        this.setVoiceOperationBusy(false);
        this.$message.error((err && err.data && err.data.msg) || "更新声纹资料失败");
      });
    },
    openExistingVoicePrints() {
      if (this.loadingCandidates) return;
      const context = this.captureContext();
      if (!this.isContextActive(context)) return;
      this.loadingCandidates = true;
      this.availableVoicePrints = [];
      this.selectedVoicePrintId = "";
      Api.person.voicePrintOptions(context.personId, ({ data }) => {
        if (!this.isContextActive(context)) return;
        this.loadingCandidates = false;
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "获取已有声纹失败");
          return;
        }
        const candidates = new Map();
        (data.data || []).forEach(item => {
          if (item && item.id && !candidates.has(item.id)) candidates.set(item.id, item);
        });
        this.availableVoicePrints = [...candidates.values()]
          .filter(item => !item.personId || item.personId === context.personId)
          .sort((left, right) => String(left.sourceName || "").localeCompare(String(right.sourceName || ""), "zh-CN"));
        this.existingDialogVisible = true;
      }, err => {
        if (!this.isContextActive(context)) return;
        this.loadingCandidates = false;
        this.$message.error((err && err.data && err.data.msg) || "获取已有声纹失败");
      });
    },
    bindSelectedVoicePrint() {
      const selected = this.availableVoicePrints.find(item => item.id === this.selectedVoicePrintId);
      if (!selected) return;
      this.bindVoicePrint(selected.id, {
        agentId: selected.agentId,
        sourceName: selected.sourceName,
        description: selected.introduce,
        createDate: selected.createDate
      }, this.captureContext());
    },
    bindVoicePrint(voicePrintId, fallback, context = this.captureContext()) {
      if (!this.isContextActive(context)) return;
      this.binding = true;
      this.setVoiceOperationBusy(true);
      Api.person.bindVoicePrint(context.personId, voicePrintId, ({ data }) => {
        if (!this.isContextActive(context)) return;
        this.binding = false;
        this.setVoiceOperationBusy(false);
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "声纹已录入但尚未关联，请点击“关联已有声纹”重试");
          return;
        }
        const profile = data.data || {};
        this.existingDialogVisible = false;
        this.emitChanged(context, {
          profile,
          fields: this.voiceFieldsFromProfile(profile, {
            ready: true,
            id: voicePrintId,
            ...fallback
          })
        });
        this.$message.success("声纹已关联到当前用户信息");
      }, err => {
        if (!this.isContextActive(context)) return;
        this.binding = false;
        this.setVoiceOperationBusy(false);
        this.$message.error((err && err.data && err.data.msg) || "声纹已录入但尚未关联，请点击“关联已有声纹”重试");
      });
    },
    unbindVoicePrint() {
      const context = this.captureContext();
      this.$confirm(
        "解除后只移除用户信息与声纹的关联，不会删除远程声纹或智能体绑定。是否继续？",
        "解除声纹关联",
        { type: "warning", confirmButtonText: "解除关联", cancelButtonText: "取消" }
      ).then(() => {
        if (!this.isContextActive(context)) return;
        this.binding = true;
        this.setVoiceOperationBusy(true);
        Api.person.unbindVoicePrint(context.personId, ({ data }) => {
          if (!this.isContextActive(context)) return;
          this.binding = false;
          this.setVoiceOperationBusy(false);
          if (!data || data.code !== 0) {
            this.$message.error((data && data.msg) || "解除声纹关联失败");
            return;
          }
          this.emitChanged(context, { profile: data.data || {}, fields: this.emptyVoiceFields() });
          this.$message.success("已解除声纹关联");
        }, err => {
          if (!this.isContextActive(context)) return;
          this.binding = false;
          this.setVoiceOperationBusy(false);
          this.$message.error((err && err.data && err.data.msg) || "解除声纹关联失败");
        });
      }).catch(() => {});
    },
    deleteVoicePrint() {
      if (!this.voicePrint.id || this.deleting) return;
      const context = this.captureContext();
      const voicePrintId = this.voicePrint.id;
      this.$confirm(
        "删除会同时移除远程声纹特征、所有智能体绑定以及当前用户关联，且无法恢复。是否继续？",
        "删除声纹",
        { type: "warning", confirmButtonText: "删除声纹", cancelButtonText: "取消" }
      ).then(() => {
        if (!this.isContextActive(context)) return;
        this.deleting = true;
        this.setVoiceOperationBusy(true);
        Api.agent.deleteAgentVoicePrint(voicePrintId, response => {
          if (!this.isContextActive(context)) return;
          this.deleting = false;
          this.setVoiceOperationBusy(false);
          const data = response && response.data;
          if (!data || data.code !== 0) {
            this.$message.error({
              message: (data && data.msg) || "删除声纹失败，请重试",
              showClose: true
            });
            return;
          }
          this.emitChanged(context, { fields: this.emptyVoiceFields() });
          this.$message.success("声纹已删除");
        });
      }).catch(() => {});
    },
    voiceFieldsFromProfile(profile, fallback) {
      return {
        voicePrintReady: profile.voicePrintReady !== undefined ? profile.voicePrintReady : Boolean(fallback.ready),
        voicePrintId: profile.voicePrintId || fallback.id || "",
        voicePrintAgentId: profile.voicePrintAgentId || fallback.agentId || "",
        voicePrintSourceName: profile.voicePrintSourceName || fallback.sourceName || this.personName,
        voicePrintDescription: profile.voicePrintDescription || fallback.description || "",
        voicePrintCreateDate: profile.voicePrintCreateDate || fallback.createDate || new Date().toISOString()
      };
    },
    emptyVoiceFields() {
      return {
        voicePrintReady: false,
        voicePrintId: "",
        voicePrintAgentId: "",
        voicePrintSourceName: "",
        voicePrintDescription: "",
        voicePrintCreateDate: null
      };
    },
    agentLabel(agentId) {
      const agent = this.agents.find(item => item.id === agentId);
      return agent ? agent.name : (agentId || "未知智能体");
    },
    formatDate(value) {
      const text = String(value || "");
      if (/^\d{4}-\d{2}-\d{2}/.test(text)) return text.slice(0, 10);
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) return "";
      const month = String(date.getMonth() + 1).padStart(2, "0");
      const day = String(date.getDate()).padStart(2, "0");
      return `${date.getFullYear()}-${month}-${day}`;
    }
  }
};
</script>

<style lang="scss" scoped>
.person-voiceprint {
  padding: 16px;
  border: 1px solid #e2e7f0;
  border-radius: 14px;
  background: #fafbfd;
}

.voiceprint-section__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.section-label {
  display: block;
  margin-bottom: 5px;
  color: #41485c;
  font-size: 14px;
  font-weight: 600;
}

.voiceprint-section__heading p,
.existing-hint {
  margin: 0;
  color: #8d96a8;
  font-size: 11px;
  line-height: 17px;
}

.voiceprint-summary,
.voiceprint-empty {
  display: flex;
  min-height: 78px;
  align-items: center;
  gap: 12px;
  margin-top: 13px;
  padding: 13px;
  box-sizing: border-box;
  border: 1px dashed #cdd5e2;
  border-radius: 11px;
  background: #fff;
}

.voiceprint-summary > i,
.voiceprint-empty > i {
  color: #4aa66e;
  font-size: 24px;
}

.voiceprint-empty {
  color: #929bac;
  font-size: 12px;
}

.voiceprint-empty > i {
  color: #9da7b8;
}

.voiceprint-summary > div {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.voiceprint-summary strong {
  color: #384156;
  font-size: 13px;
}

.voiceprint-summary span,
.voiceprint-summary small {
  overflow: hidden;
  color: #8992a3;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.voice-agent-select {
  width: 100%;
  margin-top: 12px;
}

.voiceprint-warning {
  margin: 10px 0 0;
  color: #b27a20;
  font-size: 11px;
  line-height: 17px;
}

.voiceprint-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.voiceprint-actions .el-button {
  margin: 0;
}

.delete-voiceprint {
  color: #e35c5c;
}

.existing-hint {
  margin-bottom: 14px;
}

.existing-list {
  display: grid;
  gap: 10px;
  width: 100%;
}

.existing-item {
  display: flex;
  width: 100%;
  height: auto;
  min-height: 68px;
  align-items: center;
  margin: 0 !important;
  padding: 12px 14px;
  box-sizing: border-box;
}

::v-deep .existing-item .el-radio__label {
  display: grid;
  min-width: 0;
  gap: 3px;
  white-space: normal;
}

.existing-item strong {
  color: #30384b;
}

.existing-item span,
.existing-item small {
  color: #8790a1;
  font-size: 12px;
}
</style>
