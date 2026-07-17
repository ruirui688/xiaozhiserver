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
      :model="form"
      :rules="rules"
      label-width="130px"
      class="feishu-form"
    >
      <el-form-item label="启用状态">
        <el-switch v-model="form.enabled" />
      </el-form-item>

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

      <el-form-item label="默认群聊" prop="defaultChatId">
        <div class="field-with-action">
          <el-select
            v-model="form.defaultChatId"
            filterable
            clearable
            placeholder="选择机器人所在群聊"
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
            title="刷新群聊"
            @click="loadChats"
          />
        </div>
      </el-form-item>

      <el-divider content-position="left">自动化与权限</el-divider>

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
        :disabled="!secretConfigured || !form.defaultChatId"
        :loading="messageTesting"
        @click="sendTestMessage"
      >发送测试消息</el-button>
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
  enabled: true,
  meetingReportEnabled: true,
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
      if (!this.secretConfigured && !String(value || "").trim()) {
        callback(new Error("请输入App Secret"));
        return;
      }
      callback();
    };
    return {
      form: emptyForm(),
      chats: [],
      secretConfigured: false,
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
  },
  methods: {
    loadConfig() {
      if (!this.agentId) return;
      Api.agent.getAgentFeishuConfig(this.agentId, ({ data }) => {
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "读取飞书配置失败");
          return;
        }
        const config = data.data || {};
        const editableConfig = { ...config };
        delete editableConfig.secretConfigured;
        this.form = { ...emptyForm(), ...editableConfig, appSecret: "" };
        this.secretConfigured = Boolean(config.secretConfigured);
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
    testConnection() {
      this.validate(() => {
        this.testing = true;
        Api.agent.testAgentFeishuConfig(this.agentId, this.payload(), ({ data }) => {
          this.testing = false;
          if (!data || data.code !== 0) {
            this.$message.error((data && data.msg) || "飞书连接失败");
            return;
          }
          this.chats = data.data || [];
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
      this.chatLoading = true;
      Api.agent.getAgentFeishuChats(this.agentId, ({ data }) => {
        this.chatLoading = false;
        if (!data || data.code !== 0) {
          if (showMessage) this.$message.error((data && data.msg) || "读取群聊失败");
          return;
        }
        this.chats = data.data || [];
        if (showMessage) this.$message.success("群聊列表已刷新");
      });
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
  margin: 0 0 20px 130px;
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
}
</style>
