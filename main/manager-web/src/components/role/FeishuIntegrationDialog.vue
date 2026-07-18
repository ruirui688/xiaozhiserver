<template>
  <el-dialog
    title="飞书集成"
    :visible.sync="dialogVisible"
    width="680px"
    :close-on-click-modal="false"
    @open="loadConfig"
  >
    <el-form
      ref="form"
      v-loading="configLoading"
      :model="form"
      :rules="rules"
      label-width="160px"
      class="feishu-form"
    >
      <el-form-item label="启用飞书运行时">
        <el-switch v-model="form.enabled" />
        <div class="field-help">关闭时只保存配置，设备运行时不会执行飞书动作或自动发送会议纪要。</div>
      </el-form-item>

      <el-alert
        v-if="secretConfigured && !form.enabled"
        class="runtime-alert"
        title="飞书配置已保存，但运行时尚未启用"
        description="你仍可查询群聊、修改配置和发送测试消息；设备运行时不会向飞书发送消息。"
        type="info"
        :closable="false"
        show-icon
      />

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

      <el-form-item label="默认群聊（飞书查询）" prop="defaultChatId">
        <div class="field-with-action">
          <el-select
            v-model="form.defaultChatId"
            filterable
            clearable
            :loading="chatLoading"
            placeholder="从飞书查询机器人所在群聊"
            no-data-text="暂无查询结果，请先测试连接或刷新"
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
            title="从飞书重新查询群聊"
            @click="loadChats"
          />
        </div>
        <div class="field-help">列表来自飞书开放平台，系统不预置群聊，也不会自动选择；仅保存你明确选择的 chat_id。</div>
        <div v-if="chatSelectionDirty" class="field-warning">群聊选择尚未保存，保存后才能向该群发送测试消息。</div>
      </el-form-item>

      <el-divider content-position="left">运行时动作权限</el-divider>

      <div class="permission-grid">
        <label class="permission-item">
          <span>会议纪要自动发送</span>
          <el-switch v-model="form.meetingReportEnabled" />
        </label>
        <label class="permission-item">
          <span>主动发送群消息</span>
          <el-switch v-model="form.messageSendEnabled" />
        </label>
        <label class="permission-item">
          <span>读取默认群消息</span>
          <el-switch v-model="form.messageReadEnabled" />
        </label>
        <label class="permission-item">
          <span>创建飞书任务</span>
          <el-switch v-model="form.taskCreateEnabled" />
        </label>
        <label class="permission-item">
          <span>读取飞书任务</span>
          <el-switch v-model="form.taskReadEnabled" />
        </label>
        <label class="permission-item">
          <span>创建飞书日程</span>
          <el-switch v-model="form.calendarCreateEnabled" />
        </label>
      </div>

      <el-form-item v-if="form.taskCreateEnabled" label="任务负责人 Open ID">
        <el-input
          v-model.trim="form.taskAssigneeOpenId"
          maxlength="128"
          placeholder="ou_xxxxxxxxxxxxxxxx"
        />
      </el-form-item>

      <el-form-item v-if="form.calendarCreateEnabled" label="日历 ID">
        <el-input
          v-model.trim="form.calendarId"
          maxlength="255"
          placeholder="飞书日历 calendar_id"
        />
      </el-form-item>
    </el-form>

    <div slot="footer" class="dialog-footer">
      <el-button
        v-if="secretConfigured"
        type="danger"
        plain
        icon="el-icon-delete"
        @click="removeBinding"
      >解除绑定</el-button>
      <span class="footer-spacer" />
      <el-button
        icon="el-icon-connection"
        :loading="testing"
        @click="testConnection"
      >测试连接</el-button>
      <el-button
        icon="el-icon-message"
        :disabled="testMessageDisabled"
        :loading="messageTesting"
        title="使用已保存的飞书配置发送"
        @click="sendTestMessage"
      >发送测试消息（已保存配置）</el-button>
      <el-button type="primary" :loading="saving" @click="saveConfig">保存</el-button>
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
            this.$message.success("飞书绑定已解除");
          });
        })
        .catch(() => {});
    },
  },
};
</script>

<style lang="scss" scoped>
.feishu-form {
  padding-right: 12px;
}

.runtime-alert {
  margin: 0 0 20px 160px;
  width: calc(100% - 160px);
}

.field-help,
.field-warning {
  margin-top: 6px;
  line-height: 20px;
  font-size: 12px;
}

.field-help {
  color: #909399;
}

.field-warning {
  color: #e6a23c;
}

.field-with-action {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 40px;
  gap: 8px;
}

.field-with-action .el-select {
  width: 100%;
}

.permission-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 24px;
  margin: 0 0 20px 160px;
}

.permission-item {
  min-height: 34px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #303133;
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
  .permission-grid {
    grid-template-columns: 1fr;
  }

  .runtime-alert {
    margin-left: 0;
    width: 100%;
  }
}
</style>
