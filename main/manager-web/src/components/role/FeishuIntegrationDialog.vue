<template>
  <el-dialog
    title="飞书协作"
    :visible.sync="dialogVisible"
    width="780px"
    :close-on-click-modal="false"
    @open="loadConfig"
  >
    <div v-loading="configLoading" class="feishu-dialog-body">
      <div class="integration-status" :class="connectionStatusClass">
        <div class="status-copy">
          <span class="status-icon"><i :class="connectionStatusIcon" /></span>
          <div>
            <div class="status-title">{{ connectionStatusTitle }}</div>
            <div class="status-description">{{ connectionStatusDescription }}</div>
          </div>
        </div>
        <div class="master-switch">
          <span>允许设备使用飞书</span>
          <el-switch v-model="form.enabled" />
        </div>
      </div>

      <el-alert
        v-if="secretConfigured && form.enabled && !anyActionEnabled"
        class="state-alert"
        title="飞书已启用，但还没有允许机器人执行任何能力"
        description="请在第 3 步至少开启一项能力并保存。"
        type="warning"
        :closable="false"
        show-icon
      />

      <el-form
        ref="form"
        :model="form"
        :rules="rules"
        label-position="top"
        class="feishu-form"
      >
        <section class="setup-section">
          <div class="section-heading">
            <span class="step-number">1</span>
            <div>
              <h3>连接飞书应用</h3>
              <p>填写飞书开放平台企业自建应用的凭据，用于查询机器人所在群聊和执行已授权能力。</p>
            </div>
            <el-tag v-if="secretConfigured" type="success" size="small">凭据已保存</el-tag>
          </div>

          <div class="credential-grid">
            <el-form-item label="App ID" prop="appId">
              <el-input
                v-model.trim="form.appId"
                maxlength="128"
                placeholder="cli_xxxxxxxxxxxxxxxx"
                autocomplete="off"
              />
            </el-form-item>
            <el-form-item label="App Secret" prop="appSecret">
              <el-input
                v-model="form.appSecret"
                type="password"
                maxlength="512"
                autocomplete="new-password"
                :placeholder="secretPlaceholder"
              />
            </el-form-item>
          </div>

          <div class="section-actions">
            <el-button
              type="primary"
              plain
              icon="el-icon-connection"
              :loading="testing"
              @click="testConnection"
            >测试连接并查询群聊</el-button>
            <span>测试不会自动保存，也不会自动开启任何能力。</span>
          </div>
        </section>

        <section class="setup-section">
          <div class="section-heading">
            <span class="step-number">2</span>
            <div>
              <h3>选择机器人工作的群</h3>
              <p>群聊由飞书开放平台实时返回。系统不预置、不自动选择，只保存你明确选择的 chat_id。</p>
            </div>
          </div>

          <el-form-item prop="defaultChatId" class="chat-form-item">
            <div class="field-with-action">
              <el-select
                v-model="form.defaultChatId"
                filterable
                clearable
                :loading="chatLoading"
                placeholder="请选择机器人已经加入的群聊"
                no-data-text="暂无群聊，请先测试连接"
              >
                <el-option
                  v-for="chat in chats"
                  :key="chat.chatId"
                  :label="chat.name || chat.chatId"
                  :value="chat.chatId"
                />
              </el-select>
              <el-button
                icon="el-icon-refresh"
                :loading="chatLoading"
                :disabled="!secretConfigured"
                title="从飞书重新查询机器人所在群聊"
                @click="loadChats"
              >刷新群聊</el-button>
            </div>
          </el-form-item>

          <div v-if="form.defaultChatId" class="selected-target">
            <i class="el-icon-chat-dot-round" />
            <div>
              <strong>当前目标：{{ selectedChatName }}</strong>
              <span>会议纪要、主动群消息和群消息读取都使用这个群。</span>
            </div>
          </div>
          <div v-if="chatSelectionDirty" class="field-warning">目标群已变更，请先保存，再发送测试消息。</div>

          <div class="section-actions">
            <el-button
              icon="el-icon-message"
              :disabled="testMessageDisabled"
              :loading="messageTesting"
              :title="testMessageHint"
              @click="sendTestMessage"
            >向已保存的目标群发送测试消息</el-button>
            <span>{{ testMessageHint }}</span>
          </div>
        </section>

        <section class="setup-section">
          <div class="section-heading">
            <span class="step-number">3</span>
            <div>
              <h3>选择机器人可以做什么</h3>
              <p>所有能力默认关闭。只有开启并保存后，设备和主 LLM 才能使用对应的飞书接口。</p>
            </div>
            <el-tag size="small">已开启 {{ enabledActionsCount }} 项</el-tag>
          </div>

          <div class="action-grid">
            <div class="action-card" :class="{ active: form.meetingReportEnabled }">
              <div class="action-copy">
                <strong>自动发送会议纪要</strong>
                <span>会议结束并生成完整纪要后，自动投递到目标群。</span>
                <small>需要目标群</small>
              </div>
              <el-switch v-model="form.meetingReportEnabled" />
            </div>
            <div class="action-card" :class="{ active: form.messageSendEnabled }">
              <div class="action-copy">
                <strong>按用户要求发送群消息</strong>
                <span>用户口头要求发消息时，主 LLM 才能调用飞书发送工具。</span>
                <small>需要目标群</small>
              </div>
              <el-switch v-model="form.messageSendEnabled" />
            </div>
            <div class="action-card" :class="{ active: form.messageReadEnabled }">
              <div class="action-copy">
                <strong>读取目标群消息</strong>
                <span>允许主 LLM 读取最近群消息并按用户要求总结。</span>
                <small>需要目标群和飞书消息读取权限</small>
              </div>
              <el-switch v-model="form.messageReadEnabled" />
            </div>
            <div class="action-card" :class="{ active: form.taskCreateEnabled }">
              <div class="action-copy">
                <strong>创建飞书任务</strong>
                <span>允许主 LLM 根据用户要求创建任务。</span>
                <small>负责人 Open ID 可选</small>
              </div>
              <el-switch v-model="form.taskCreateEnabled" />
            </div>
            <div class="action-card" :class="{ active: form.taskReadEnabled }">
              <div class="action-copy">
                <strong>读取飞书任务</strong>
                <span>允许主 LLM 查询应用可见的任务列表。</span>
                <small>需要飞书任务读取权限</small>
              </div>
              <el-switch v-model="form.taskReadEnabled" />
            </div>
            <div class="action-card" :class="{ active: form.calendarCreateEnabled }">
              <div class="action-copy">
                <strong>创建飞书日程</strong>
                <span>允许主 LLM 根据用户要求创建日历事件。</span>
                <small>需要指定日历 ID</small>
              </div>
              <el-switch v-model="form.calendarCreateEnabled" />
            </div>
          </div>

          <div v-if="form.taskCreateEnabled || form.calendarCreateEnabled" class="advanced-fields">
            <el-form-item v-if="form.taskCreateEnabled" label="默认任务负责人 Open ID">
              <el-input
                v-model.trim="form.taskAssigneeOpenId"
                maxlength="128"
                placeholder="可选，例如 ou_xxxxxxxxxxxxxxxx"
              />
            </el-form-item>
            <el-form-item v-if="form.calendarCreateEnabled" label="目标日历 ID">
              <el-input
                v-model.trim="form.calendarId"
                maxlength="255"
                placeholder="飞书日历 calendar_id"
              />
            </el-form-item>
          </div>
        </section>
      </el-form>
    </div>

    <div slot="footer" class="dialog-footer">
      <el-button
        v-if="secretConfigured"
        type="danger"
        plain
        icon="el-icon-delete"
        @click="removeBinding"
      >解除飞书绑定</el-button>
      <span class="footer-spacer" />
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveConfig">保存飞书配置</el-button>
    </div>
  </el-dialog>
</template>

<script>
import Api from "@/apis/api";

const emptyForm = () => ({
  appId: "",
  appSecret: "",
  defaultChatId: "",
  calendarId: "",
  taskAssigneeOpenId: "",
  enabled: false,
  meetingReportEnabled: false,
  messageSendEnabled: false,
  messageReadEnabled: false,
  taskReadEnabled: false,
  taskCreateEnabled: false,
  calendarCreateEnabled: false,
});

export default {
  name: "FeishuIntegrationDialog",
  props: {
    visible: { type: Boolean, default: false },
    agentId: { type: String, required: true },
  },
  data() {
    const validateSecret = (rule, value, callback) => {
      const secretMissing = !String(value || "").trim();
      const appChanged = Boolean(
        this.secretConfigured &&
        this.savedAppId &&
        this.form.appId !== this.savedAppId
      );
      if ((!this.secretConfigured || appChanged) && secretMissing) {
        callback(new Error(appChanged ? "修改App ID时必须填写对应的App Secret" : "请输入App Secret"));
        return;
      }
      callback();
    };
    const validateDefaultChat = (rule, value, callback) => {
      if (this.groupActionEnabled && !String(value || "").trim()) {
        callback(new Error("开启会议纪要或群消息能力前，请明确选择默认群聊"));
        return;
      }
      callback();
    };
    return {
      form: emptyForm(),
      chats: [],
      secretConfigured: false,
      savedAppId: "",
      savedDefaultChatId: "",
      savedFormSignature: "",
      configLoading: false,
      saving: false,
      testing: false,
      chatLoading: false,
      messageTesting: false,
      rules: {
        appId: [
          { required: true, message: "请输入App ID", trigger: "blur" },
          { pattern: /^cli_[A-Za-z0-9]+$/, message: "App ID格式无效", trigger: "blur" },
        ],
        appSecret: [{ validator: validateSecret, trigger: "blur" }],
        defaultChatId: [{ validator: validateDefaultChat, trigger: "change" }],
      },
    };
  },
  computed: {
    dialogVisible: {
      get() {
        return this.visible;
      },
      set(value) {
        this.$emit("update:visible", value);
      },
    },
    secretPlaceholder() {
      return this.secretConfigured ? "已加密保存，留空表示不修改" : "请输入App Secret";
    },
    connectionStatusTitle() {
      if (this.hasUnsavedChanges) return "配置有未保存变更";
      if (!this.secretConfigured) return "尚未连接飞书应用";
      return this.form.enabled ? "设备已可使用飞书" : "飞书配置已保存，设备暂未启用";
    },
    connectionStatusDescription() {
      if (this.hasUnsavedChanges) {
        return "当前页面修改尚未生效；保存后，设备重新连接时会读取新配置。";
      }
      if (!this.secretConfigured) {
        return "先填写应用凭据并测试连接，再选择目标群和机器人能力。";
      }
      if (!this.form.enabled) {
        return "配置和目标群会保留，但设备不会执行飞书动作，也不会自动发送会议纪要。";
      }
      return `目标群：${this.selectedChatName || "尚未选择"}；已允许 ${this.enabledActionsCount} 项能力。`;
    },
    connectionStatusClass() {
      if (this.hasUnsavedChanges) return "unsaved";
      if (!this.secretConfigured) return "pending";
      return this.form.enabled ? "enabled" : "disabled";
    },
    connectionStatusIcon() {
      if (this.hasUnsavedChanges) return "el-icon-edit";
      if (!this.secretConfigured) return "el-icon-link";
      return this.form.enabled ? "el-icon-circle-check" : "el-icon-video-pause";
    },
    selectedChatName() {
      const selected = this.chats.find(
        (chat) => chat.chatId === this.form.defaultChatId
      );
      return selected ? selected.name || selected.chatId : this.form.defaultChatId;
    },
    anyActionEnabled() {
      return this.enabledActionsCount > 0;
    },
    hasUnsavedChanges() {
      return Boolean(
        this.savedFormSignature &&
        this.formSignature(this.form) !== this.savedFormSignature
      );
    },
    enabledActionsCount() {
      return [
        this.form.meetingReportEnabled,
        this.form.messageSendEnabled,
        this.form.messageReadEnabled,
        this.form.taskReadEnabled,
        this.form.taskCreateEnabled,
        this.form.calendarCreateEnabled,
      ].filter(Boolean).length;
    },
    groupActionEnabled() {
      return Boolean(
        this.form.meetingReportEnabled ||
        this.form.messageSendEnabled ||
        this.form.messageReadEnabled
      );
    },
    chatSelectionDirty() {
      return Boolean(
        this.secretConfigured &&
        this.form.defaultChatId !== this.savedDefaultChatId
      );
    },
    savedConfigDirty() {
      return Boolean(
        this.form.appId !== this.savedAppId ||
        String(this.form.appSecret || "").trim() ||
        this.chatSelectionDirty
      );
    },
    testMessageDisabled() {
      return Boolean(
        !this.secretConfigured ||
        !this.savedDefaultChatId ||
        this.savedConfigDirty
      );
    },
    testMessageHint() {
      if (!this.secretConfigured) return "请先保存飞书应用配置";
      if (!this.savedDefaultChatId) return "请先选择并保存目标群";
      if (this.savedConfigDirty) return "页面存在未保存变更，请先保存";
      return "测试使用已保存的凭据和目标群，不要求开启设备使用开关";
    },
  },
  methods: {
    loadConfig() {
      if (!this.agentId) return;
      const agentId = this.agentId;
      this.form = emptyForm();
      this.chats = [];
      this.secretConfigured = false;
      this.savedAppId = "";
      this.savedDefaultChatId = "";
      this.savedFormSignature = "";
      this.configLoading = true;
      Api.agent.getAgentFeishuConfig(agentId, ({ data }) => {
        if (agentId !== this.agentId) return;
        this.configLoading = false;
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "读取飞书配置失败");
          return;
        }
        const config = data.data || {};
        const editableConfig = { ...config };
        delete editableConfig.secretConfigured;
        this.form = { ...emptyForm(), ...editableConfig, appSecret: "" };
        this.secretConfigured = Boolean(config.secretConfigured);
        this.savedAppId = this.form.appId;
        this.savedDefaultChatId = this.form.defaultChatId;
        this.savedFormSignature = this.formSignature(this.form);
        if (this.secretConfigured) this.loadChats(false);
      });
    },
    payload() {
      const {
        appId,
        appSecret,
        defaultChatId,
        calendarId,
        taskAssigneeOpenId,
        enabled,
        meetingReportEnabled,
        messageSendEnabled,
        messageReadEnabled,
        taskReadEnabled,
        taskCreateEnabled,
        calendarCreateEnabled,
      } = this.form;
      return {
        appId,
        appSecret: appSecret || "",
        defaultChatId,
        calendarId,
        taskAssigneeOpenId,
        enabled,
        meetingReportEnabled,
        messageSendEnabled,
        messageReadEnabled,
        taskReadEnabled,
        taskCreateEnabled,
        calendarCreateEnabled,
      };
    },
    validate(callback) {
      this.$refs.form.validate((valid) => {
        if (valid) callback();
      });
    },
    validateCredentials(callback) {
      this.$refs.form.validateField("appId", (appIdError) => {
        if (appIdError) return;
        this.$refs.form.validateField("appSecret", (secretError) => {
          if (!secretError) callback();
        });
      });
    },
    testConnection() {
      this.validateCredentials(() => {
        this.testing = true;
        Api.agent.testAgentFeishuConfig(this.agentId, this.payload(), ({ data }) => {
          this.testing = false;
          if (!data || data.code !== 0) {
            this.$message.error((data && data.msg) || "飞书连接失败");
            return;
          }
          this.applyChats(data.data || [], true);
          this.$message.success("飞书连接成功");
        });
      });
    },
    saveConfig() {
      this.validate(() => {
        this.saving = true;
        Api.agent.saveAgentFeishuConfig(this.agentId, this.payload(), ({ data }) => {
          this.saving = false;
          if (!data || data.code !== 0) {
            this.$message.error((data && data.msg) || "保存飞书配置失败");
            return;
          }
          this.form.appSecret = "";
          this.secretConfigured = true;
          this.savedAppId = this.form.appId;
          this.savedDefaultChatId = this.form.defaultChatId;
          this.savedFormSignature = this.formSignature(this.form);
          this.$message.success("飞书配置已保存");
          this.loadChats(false);
        });
      });
    },
    loadChats(showMessage = true) {
      if (!this.secretConfigured) {
        if (showMessage) this.$message.warning("请先保存飞书配置");
        return;
      }
      const agentId = this.agentId;
      this.chatLoading = true;
      Api.agent.getAgentFeishuChats(agentId, ({ data }) => {
        if (agentId !== this.agentId) return;
        this.chatLoading = false;
        if (!data || data.code !== 0) {
          if (showMessage) this.$message.error((data && data.msg) || "读取群聊失败");
          return;
        }
        this.applyChats(data.data || [], false);
        if (showMessage) this.$message.success("群聊列表已刷新");
      });
    },
    applyChats(chats, fromCurrentCredentials) {
      this.chats = Array.isArray(chats) ? chats : [];
      if (!this.form.defaultChatId) return;
      const selectedExists = this.chats.some(
        (chat) => chat.chatId === this.form.defaultChatId
      );
      if (selectedExists) return;
      this.form.defaultChatId = "";
      const source = fromCurrentCredentials ? "当前凭据" : "机器人当前";
      this.$message.warning(`${source}可访问的群聊中不包含原选择，请重新选择并保存`);
    },
    sendTestMessage() {
      this.messageTesting = true;
      Api.agent.sendAgentFeishuTestMessage(this.agentId, ({ data }) => {
        this.messageTesting = false;
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "测试消息发送失败");
          return;
        }
        this.$message.success("测试消息已发送");
      });
    },
    removeBinding() {
      this.$confirm("解除当前智能体的飞书绑定？", "解除绑定", { type: "warning" })
        .then(() => {
          Api.agent.deleteAgentFeishuConfig(this.agentId, ({ data }) => {
            if (!data || data.code !== 0) {
              this.$message.error((data && data.msg) || "解除绑定失败");
              return;
            }
            this.form = emptyForm();
            this.chats = [];
            this.secretConfigured = false;
            this.savedAppId = "";
            this.savedDefaultChatId = "";
            this.savedFormSignature = this.formSignature(this.form);
            this.$message.success("飞书绑定已解除");
          });
        })
        .catch(() => {});
    },
    formSignature(form) {
      return JSON.stringify({
        appId: form.appId || "",
        appSecretChanged: Boolean(String(form.appSecret || "").trim()),
        defaultChatId: form.defaultChatId || "",
        calendarId: form.calendarId || "",
        taskAssigneeOpenId: form.taskAssigneeOpenId || "",
        enabled: Boolean(form.enabled),
        meetingReportEnabled: Boolean(form.meetingReportEnabled),
        messageSendEnabled: Boolean(form.messageSendEnabled),
        messageReadEnabled: Boolean(form.messageReadEnabled),
        taskReadEnabled: Boolean(form.taskReadEnabled),
        taskCreateEnabled: Boolean(form.taskCreateEnabled),
        calendarCreateEnabled: Boolean(form.calendarCreateEnabled),
      });
    },
  },
};
</script>

<style lang="scss" scoped>
.feishu-dialog-body {
  max-height: 68vh;
  padding: 2px 4px 4px;
  overflow-y: auto;
}

.integration-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 18px 20px;
  margin-bottom: 16px;
  border: 1px solid #dcdfe6;
  border-radius: 12px;
  background: #f8fafc;
}

.integration-status.enabled {
  border-color: #b3e19d;
  background: #f0f9eb;
}

.integration-status.disabled {
  border-color: #c6e2ff;
  background: #ecf5ff;
}

.integration-status.unsaved {
  border-color: #f3d19e;
  background: #fdf6ec;
}

.status-copy {
  display: flex;
  align-items: center;
  min-width: 0;
}

.status-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 42px;
  height: 42px;
  margin-right: 12px;
  border-radius: 50%;
  color: #409eff;
  background: #ffffff;
  font-size: 22px;
}

.status-title {
  margin-bottom: 4px;
  color: #303133;
  font-size: 16px;
  font-weight: 600;
}

.status-description {
  color: #606266;
  line-height: 20px;
  font-size: 13px;
}

.master-switch {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 10px;
  color: #303133;
  font-weight: 500;
}

.state-alert {
  margin-bottom: 16px;
}

.setup-section {
  padding: 20px;
  margin-bottom: 16px;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  background: #ffffff;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 18px;
}

.section-heading > div {
  min-width: 0;
  flex: 1;
}

.section-heading h3 {
  margin: 0 0 5px;
  color: #303133;
  font-size: 16px;
}

.section-heading p {
  margin: 0;
  color: #909399;
  line-height: 20px;
  font-size: 13px;
}

.step-number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 28px;
  height: 28px;
  border-radius: 50%;
  color: #ffffff;
  background: #409eff;
  font-weight: 600;
}

.credential-grid,
.advanced-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.feishu-form ::v-deep .el-form-item {
  margin-bottom: 14px;
}

.feishu-form ::v-deep .el-form-item__label {
  padding-bottom: 6px;
  color: #606266;
  line-height: 20px;
  font-weight: 500;
}

.chat-form-item {
  margin-bottom: 10px !important;
}

.field-with-action {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
}

.field-with-action .el-select {
  width: 100%;
}

.selected-target {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  margin-top: 4px;
  border-radius: 8px;
  color: #409eff;
  background: #ecf5ff;
}

.selected-target i {
  font-size: 22px;
}

.selected-target div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.selected-target span {
  color: #606266;
  font-size: 12px;
}

.field-warning {
  margin-top: 8px;
  color: #e6a23c;
  line-height: 20px;
  font-size: 12px;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}

.section-actions span {
  color: #909399;
  line-height: 18px;
  font-size: 12px;
}

.action-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.action-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  min-height: 104px;
  padding: 15px;
  border: 1px solid #dcdfe6;
  border-radius: 10px;
  color: #303133;
  background: #ffffff;
  transition: border-color 0.2s, background-color 0.2s;
}

.action-card:hover {
  border-color: #a0cfff;
}

.action-card.active {
  border-color: #409eff;
  background: #f4f9ff;
}

.action-copy {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.action-copy strong {
  font-size: 14px;
}

.action-copy span {
  color: #606266;
  line-height: 18px;
  font-size: 12px;
}

.action-copy small {
  color: #909399;
  line-height: 16px;
}

.advanced-fields {
  padding-top: 18px;
  margin-top: 18px;
  border-top: 1px dashed #dcdfe6;
}

.dialog-footer {
  display: flex;
  align-items: center;
  width: 100%;
}

.footer-spacer {
  flex: 1;
}

@media (max-width: 760px) {
  .integration-status,
  .section-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .credential-grid,
  .advanced-fields,
  .action-grid {
    grid-template-columns: 1fr;
  }

  .master-switch {
    width: 100%;
    justify-content: space-between;
  }
}
</style>
