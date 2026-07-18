<template>
  <el-dialog
    title="飞书机器人"
    :visible.sync="dialogVisible"
    width="900px"
    top="5vh"
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
          <div>
            <strong>设备使用飞书</strong>
            <span>总开关</span>
          </div>
          <el-switch v-model="form.enabled" />
        </div>
      </div>

      <el-alert
        v-if="secretConfigured && form.enabled && !anyActionEnabled"
        class="state-alert"
        title="总开关已打开，但还没有启用任何动作或自动化"
        type="warning"
        :closable="false"
        show-icon
      />

      <el-form ref="form" :model="form" :rules="rules" label-position="top" class="feishu-form">
        <el-tabs v-model="activeTab" type="border-card" class="config-tabs">
          <el-tab-pane name="connection">
            <span slot="label"><i class="el-icon-link" /> 应用连接</span>
            <div class="tab-intro">
              <div>
                <h3>连接企业自建应用</h3>
                <p>凭据只用于服务端调用飞书开放平台，App Secret 加密保存且不会回显。</p>
              </div>
              <el-tag v-if="secretConfigured" type="success" size="small">已保存</el-tag>
            </div>
            <div class="credential-grid">
              <el-form-item label="App ID" prop="appId">
                <el-input v-model.trim="form.appId" maxlength="128" placeholder="cli_xxxxxxxxxxxxxxxx" autocomplete="off" />
              </el-form-item>
              <el-form-item label="App Secret" prop="appSecret">
                <el-input v-model="form.appSecret" type="password" maxlength="512" autocomplete="new-password" :placeholder="secretPlaceholder" />
              </el-form-item>
            </div>
            <div class="inline-action">
              <el-button type="primary" icon="el-icon-connection" :loading="testing" @click="testConnection">
                测试连接并获取群聊
              </el-button>
              <span>测试不会保存配置，也不会自动开启权限。</span>
            </div>
          </el-tab-pane>

          <el-tab-pane name="group">
            <span slot="label"><i class="el-icon-chat-dot-round" /> 群聊与播报</span>
            <div class="tab-intro">
              <div>
                <h3>目标群聊</h3>
                <p>列表来自飞书实时查询。系统不预置、不自动选择群聊。</p>
              </div>
            </div>
            <el-form-item label="机器人工作的群" prop="defaultChatId" class="chat-form-item">
              <div class="field-with-action">
                <el-select v-model="form.defaultChatId" filterable clearable :loading="chatLoading" placeholder="请选择机器人已经加入的群聊" no-data-text="暂无群聊，请先测试连接">
                  <el-option v-for="chat in chats" :key="chat.chatId" :label="chat.name || chat.chatId" :value="chat.chatId" />
                </el-select>
                <el-button icon="el-icon-refresh" :loading="chatLoading" :disabled="!secretConfigured" @click="loadChats">刷新</el-button>
              </div>
            </el-form-item>
            <div v-if="form.defaultChatId" class="selected-target">
              <i class="el-icon-position" />
              <div><strong>{{ selectedChatName }}</strong><span>{{ form.defaultChatId }}</span></div>
            </div>
            <div v-if="chatSelectionDirty" class="field-warning">目标群已修改，保存后才会生效。</div>
            <div class="inline-action group-test-action">
              <el-button icon="el-icon-message" :disabled="testMessageDisabled" :loading="messageTesting" @click="sendTestMessage">
                发送测试消息
              </el-button>
              <span>{{ testMessageHint }}</span>
            </div>

            <div class="automation-panel" :class="{ active: form.messageBroadcastEnabled }">
              <div class="automation-heading">
                <span class="automation-icon"><i class="el-icon-bell" /></span>
                <div>
                  <strong>群里有新消息时，主动向设备播报</strong>
                  <p>这是实时事件订阅，不是“按用户要求读取消息”。仅向当前智能体在线且空闲的设备播报，会议中不会插播。</p>
                </div>
                <el-switch v-model="form.messageBroadcastEnabled" />
              </div>
              <div v-if="form.messageBroadcastEnabled" class="event-setup">
                <el-alert
                  title="先填写 Verification Token 并保存，再把下面的地址填入飞书事件订阅并订阅 im.message.receive_v1。当前回调不启用 Encrypt Key 加密。"
                  type="info"
                  :closable="false"
                  show-icon
                />
                <el-form-item label="事件订阅请求地址">
                  <el-input :value="eventCallbackUrl" readonly>
                    <el-button slot="append" icon="el-icon-document-copy" @click="copyEventCallbackUrl">复制</el-button>
                  </el-input>
                </el-form-item>
                <el-form-item label="Verification Token" prop="eventVerificationToken">
                  <el-input
                    v-model="form.eventVerificationToken"
                    type="password"
                    maxlength="512"
                    autocomplete="new-password"
                    :placeholder="eventVerificationTokenConfigured ? '已加密保存，留空表示不修改' : '飞书事件订阅页面中的 Verification Token'"
                  />
                </el-form-item>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane name="permissions">
            <span slot="label"><i class="el-icon-setting" /> 能力权限</span>
            <div class="tab-intro">
              <div>
                <h3>机器人可以做什么</h3>
                <p>所有能力独立授权。不开启就不会向主 LLM 提供对应工具。</p>
              </div>
              <el-tag size="small">已开启 {{ enabledActionsCount }} 项</el-tag>
            </div>
            <div class="permission-list">
              <div class="permission-row">
                <span class="permission-icon blue"><i class="el-icon-document" /></span>
                <div><strong>会议结束后发送纪要</strong><p>生成书面纪要后自动投递到目标群。</p></div>
                <el-switch v-model="form.meetingReportEnabled" />
              </div>
              <div class="permission-row">
                <span class="permission-icon green"><i class="el-icon-s-promotion" /></span>
                <div><strong>按用户要求发送群消息</strong><p>用户明确要求发送时，主 LLM 可以调用发送工具。</p></div>
                <el-switch v-model="form.messageSendEnabled" />
              </div>
              <div class="permission-row">
                <span class="permission-icon purple"><i class="el-icon-search" /></span>
                <div><strong>按用户要求查询群消息</strong><p>用户询问时读取最近消息并交给主 LLM 总结，不会自动监听。</p></div>
                <el-switch v-model="form.messageReadEnabled" />
              </div>
              <div class="permission-row">
                <span class="permission-icon orange"><i class="el-icon-circle-plus-outline" /></span>
                <div><strong>创建飞书任务</strong><p>允许主 LLM 根据用户要求创建任务。</p></div>
                <el-switch v-model="form.taskCreateEnabled" />
              </div>
              <div class="permission-row">
                <span class="permission-icon gray"><i class="el-icon-tickets" /></span>
                <div><strong>查询飞书任务</strong><p>允许主 LLM 查询应用可见的任务。</p></div>
                <el-switch v-model="form.taskReadEnabled" />
              </div>
              <div class="permission-row">
                <span class="permission-icon red"><i class="el-icon-date" /></span>
                <div><strong>创建飞书日程</strong><p>允许主 LLM 在指定日历中创建事件。</p></div>
                <el-switch v-model="form.calendarCreateEnabled" />
              </div>
            </div>
            <div v-if="form.taskCreateEnabled || form.calendarCreateEnabled" class="advanced-fields">
              <el-form-item v-if="form.taskCreateEnabled" label="默认任务负责人 Open ID">
                <el-input v-model.trim="form.taskAssigneeOpenId" maxlength="128" placeholder="可选，例如 ou_xxxxxxxxxxxxxxxx" />
              </el-form-item>
              <el-form-item v-if="form.calendarCreateEnabled" label="目标日历 ID">
                <el-input v-model.trim="form.calendarId" maxlength="255" placeholder="飞书日历 calendar_id" />
              </el-form-item>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-form>
    </div>

    <div slot="footer" class="dialog-footer">
      <el-button v-if="secretConfigured" type="danger" plain icon="el-icon-delete" @click="removeBinding">解除绑定</el-button>
      <span class="footer-spacer" />
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveConfig">保存配置</el-button>
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
  eventVerificationToken: "",
  enabled: false,
  meetingReportEnabled: false,
  messageSendEnabled: false,
  messageReadEnabled: false,
  messageBroadcastEnabled: false,
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
    const validateEventToken = (rule, value, callback) => {
      const appChanged = Boolean(
        this.secretConfigured &&
        this.savedAppId &&
        this.form.appId !== this.savedAppId
      );
      if (
        this.form.messageBroadcastEnabled &&
        (!this.eventVerificationTokenConfigured || appChanged) &&
        !String(value || "").trim()
      ) {
        callback(new Error("开启群消息到达播报前，请填写 Verification Token"));
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
      eventVerificationTokenConfigured: false,
      activeTab: "connection",
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
        eventVerificationToken: [{ validator: validateEventToken, trigger: "blur" }],
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
        this.form.messageBroadcastEnabled,
        this.form.taskReadEnabled,
        this.form.taskCreateEnabled,
        this.form.calendarCreateEnabled,
      ].filter(Boolean).length;
    },
    groupActionEnabled() {
      return Boolean(
        this.form.meetingReportEnabled ||
        this.form.messageSendEnabled ||
        this.form.messageReadEnabled ||
        this.form.messageBroadcastEnabled
      );
    },
    eventCallbackUrl() {
      return Api.agent.getAgentFeishuEventCallbackUrl(this.agentId);
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
      this.eventVerificationTokenConfigured = false;
      this.activeTab = "connection";
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
        delete editableConfig.eventVerificationTokenConfigured;
        this.form = {
          ...emptyForm(),
          ...editableConfig,
          appSecret: "",
          eventVerificationToken: "",
        };
        this.secretConfigured = Boolean(config.secretConfigured);
        this.eventVerificationTokenConfigured = Boolean(
          config.eventVerificationTokenConfigured
        );
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
        eventVerificationToken,
        enabled,
        meetingReportEnabled,
        messageSendEnabled,
        messageReadEnabled,
        messageBroadcastEnabled,
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
        eventVerificationToken: eventVerificationToken || "",
        enabled,
        meetingReportEnabled,
        messageSendEnabled,
        messageReadEnabled,
        messageBroadcastEnabled,
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
        const appChanged = this.form.appId !== this.savedAppId;
        this.saving = true;
        Api.agent.saveAgentFeishuConfig(this.agentId, this.payload(), ({ data }) => {
          this.saving = false;
          if (!data || data.code !== 0) {
            this.$message.error((data && data.msg) || "保存飞书配置失败");
            return;
          }
          this.form.appSecret = "";
          if (String(this.form.eventVerificationToken || "").trim()) {
            this.eventVerificationTokenConfigured = true;
          } else if (appChanged) {
            this.eventVerificationTokenConfigured = false;
          }
          this.form.eventVerificationToken = "";
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
            this.eventVerificationTokenConfigured = false;
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
        eventVerificationTokenChanged: Boolean(
          String(form.eventVerificationToken || "").trim()
        ),
        defaultChatId: form.defaultChatId || "",
        calendarId: form.calendarId || "",
        taskAssigneeOpenId: form.taskAssigneeOpenId || "",
        enabled: Boolean(form.enabled),
        meetingReportEnabled: Boolean(form.meetingReportEnabled),
        messageSendEnabled: Boolean(form.messageSendEnabled),
        messageReadEnabled: Boolean(form.messageReadEnabled),
        messageBroadcastEnabled: Boolean(form.messageBroadcastEnabled),
        taskReadEnabled: Boolean(form.taskReadEnabled),
        taskCreateEnabled: Boolean(form.taskCreateEnabled),
        calendarCreateEnabled: Boolean(form.calendarCreateEnabled),
      });
    },
    copyEventCallbackUrl() {
      navigator.clipboard.writeText(this.eventCallbackUrl).then(
        () => this.$message.success("事件回调地址已复制"),
        () => this.$message.error("复制失败，请手动复制")
      );
    },
  },
};
</script>

<style lang="scss" scoped>
.feishu-dialog-body {
  min-height: 520px;
}

.integration-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 14px 18px;
  margin-bottom: 14px;
  border: 1px solid #e4e7ed;
  border-radius: 10px;
  background: linear-gradient(135deg, #f8fbff 0%, #ffffff 100%);
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
  flex: 0 0 38px;
  height: 38px;
  margin-right: 12px;
  border-radius: 50%;
  color: #409eff;
  background: #ffffff;
  box-shadow: 0 3px 10px rgba(64, 158, 255, 0.12);
  font-size: 19px;
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
  gap: 14px;
  padding-left: 18px;
  border-left: 1px solid #ebeef5;
}

.master-switch > div {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}

.master-switch strong {
  color: #303133;
  font-size: 13px;
}

.master-switch span {
  color: #909399;
  font-size: 11px;
}

.state-alert {
  margin-bottom: 16px;
}

.config-tabs {
  border-radius: 10px;
  box-shadow: none;
  overflow: hidden;
}

.config-tabs ::v-deep .el-tabs__header {
  background: #f7f8fa;
}

.config-tabs ::v-deep .el-tabs__item {
  height: 48px;
  line-height: 48px;
  color: #606266;
  font-weight: 500;
}

.config-tabs ::v-deep .el-tabs__item.is-active {
  color: #409eff;
  background: #ffffff;
}

.config-tabs ::v-deep .el-tabs__content {
  height: 410px;
  padding: 22px 24px;
  overflow-y: auto;
}

.tab-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}

.tab-intro h3 {
  margin: 0 0 5px;
  color: #303133;
  font-size: 16px;
}

.tab-intro p {
  margin: 0;
  color: #909399;
  line-height: 20px;
  font-size: 13px;
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
  margin-bottom: 12px !important;
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
  padding: 10px 12px;
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

.inline-action {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.inline-action span {
  color: #909399;
  line-height: 18px;
  font-size: 12px;
}

.group-test-action {
  padding-bottom: 20px;
  border-bottom: 1px solid #ebeef5;
}

.automation-panel {
  padding: 16px;
  margin-top: 18px;
  border: 1px solid #e4e7ed;
  border-radius: 10px;
  background: #fafafa;
  transition: border-color 0.2s, background-color 0.2s;
}

.automation-panel.active {
  border-color: #a0cfff;
  background: #f4f9ff;
}

.automation-heading {
  display: flex;
  align-items: center;
  gap: 12px;
}

.automation-heading > div {
  min-width: 0;
  flex: 1;
}

.automation-heading strong {
  color: #303133;
  font-size: 14px;
}

.automation-heading p {
  margin: 4px 0 0;
  color: #909399;
  line-height: 18px;
  font-size: 12px;
}

.automation-icon,
.permission-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 34px;
  height: 34px;
  border-radius: 8px;
  color: #409eff;
  background: #ecf5ff;
  font-size: 17px;
}

.event-setup {
  padding-top: 16px;
  margin-top: 16px;
  border-top: 1px dashed #c6e2ff;
}

.event-setup .el-alert {
  margin-bottom: 14px;
}

.permission-list {
  border: 1px solid #ebeef5;
  border-radius: 10px;
  overflow: hidden;
}

.permission-row {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  min-height: 64px;
  padding: 10px 14px;
  border-bottom: 1px solid #ebeef5;
  background: #ffffff;
}

.permission-row:last-child {
  border-bottom: none;
}

.permission-row:hover {
  background: #fafcff;
}

.permission-row strong {
  color: #303133;
  font-size: 14px;
}

.permission-row p {
  margin: 3px 0 0;
  color: #909399;
  font-size: 12px;
}

.permission-icon.green { color: #67c23a; background: #f0f9eb; }
.permission-icon.purple { color: #8b5cf6; background: #f5f3ff; }
.permission-icon.orange { color: #e6a23c; background: #fdf6ec; }
.permission-icon.gray { color: #606266; background: #f4f4f5; }
.permission-icon.red { color: #f56c6c; background: #fef0f0; }
.permission-icon.blue { color: #409eff; background: #ecf5ff; }

.advanced-fields {
  padding-top: 16px;
  margin-top: 16px;
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
  .inline-action {
    align-items: flex-start;
    flex-direction: column;
  }

  .credential-grid,
  .advanced-fields {
    grid-template-columns: 1fr;
  }

  .master-switch {
    width: 100%;
    justify-content: space-between;
    padding-left: 0;
    border-left: none;
  }

  .master-switch > div {
    align-items: flex-start;
  }
}
</style>
