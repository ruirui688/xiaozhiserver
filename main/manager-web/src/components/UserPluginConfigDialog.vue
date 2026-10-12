<template>
  <el-dialog
    :title="`${pluginName || '插件'}服务配置`"
    :visible.sync="dialogVisible"
    width="560px"
    top="6vh"
    class="plugin-service-dialog"
    append-to-body
    :close-on-click-modal="false"
    @open="loadConfig"
  >
    <el-alert type="info" :closable="false" show-icon class="setup-guidance"
      title="服务配置按账号保存。团队中其他账号创建的智能体，需由所属账号配置。保存后还需在智能体的功能管理中添加插件；测试连接只检查服务配置。" />
    <el-form ref="form" :model="form" :rules="rules" label-width="110px">
      <template v-if="providerCode === 'get_weather'">
        <el-form-item label="和风服务地址" prop="api_host">
          <el-input
            v-model.trim="form.api_host"
            maxlength="255"
            placeholder="控制台分配的 xxx.qweatherapi.com"
          />
        </el-form-item>
        <el-form-item label="服务密钥" prop="api_key">
          <el-input
            v-model="form.api_key"
            type="password"
            maxlength="512"
            autocomplete="new-password"
            :placeholder="secretPlaceholder"
          />
        </el-form-item>
        <el-form-item label="默认城市" prop="default_location">
          <el-input v-model.trim="form.default_location" maxlength="100" placeholder="广州" />
        </el-form-item>
      </template>

      <template v-else-if="providerCode === 'web_search'">
        <el-form-item label="搜索源" prop="provider">
          <el-select v-model="form.provider" style="width: 100%">
            <el-option label="秘塔搜索 Metaso" value="metaso" />
            <el-option label="Tavily" value="tavily" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务密钥" prop="api_key">
          <el-input
            v-model="form.api_key"
            type="password"
            maxlength="512"
            autocomplete="new-password"
            :placeholder="secretPlaceholder"
          />
        </el-form-item>
      </template>
    </el-form>

    <span slot="footer" class="dialog-footer">
      <el-button
        v-if="configured"
        type="danger"
        plain
        icon="el-icon-delete"
        @click="removeConfig"
      >删除共享配置</el-button>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button plain :loading="testing" @click="testConfig">测试连接</el-button>
      <el-button type="primary" :loading="saving" @click="saveConfig">保存</el-button>
    </span>
  </el-dialog>
</template>

<script>
import Api from "@/apis/api";

const emptyForm = (providerCode) => providerCode === "web_search"
  ? { provider: "metaso", api_key: "" }
  : { api_host: "", api_key: "", default_location: "广州" };

export default {
  name: "UserPluginConfigDialog",
  props: {
    visible: { type: Boolean, default: false },
    providerCode: { type: String, default: "" },
    pluginName: { type: String, default: "" },
  },
  data() {
    const validateSecret = (rule, value, callback) => {
      if (!this.secretConfigured && !String(value || "").trim()) {
        callback(new Error("请输入API Key"));
        return;
      }
      callback();
    };
    return {
      form: emptyForm(""),
      configured: false,
      secretConfigured: false,
      saving: false,
      testing: false,
      rules: {
        api_host: [{ required: true, message: "请输入API Host", trigger: "blur" }],
        api_key: [{ validator: validateSecret, trigger: "blur" }],
        default_location: [{ required: true, message: "请输入默认城市", trigger: "blur" }],
        provider: [{ required: true, message: "请选择搜索源", trigger: "change" }],
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
      return this.secretConfigured ? "已加密保存，留空表示不修改" : "请输入API Key";
    },
  },
  methods: {
    loadConfig() {
      if (!this.providerCode) return;
      this.form = emptyForm(this.providerCode);
      this.configured = false;
      this.secretConfigured = false;
      Api.user.getPluginConfig(this.providerCode, ({ data }) => {
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "读取共享配置失败");
          return;
        }
        const result = data.data || {};
        this.configured = Boolean(result.configured);
        this.secretConfigured = Boolean(result.secretConfigured);
        this.form = {
          ...emptyForm(this.providerCode),
          ...(result.config || {}),
          api_key: "",
        };
      });
    },
    saveConfig() {
      this.$refs.form.validate((valid) => {
        if (!valid) return;
        this.saving = true;
        Api.user.savePluginConfig(this.providerCode, this.form, ({ data }) => {
          this.saving = false;
          if (!data || data.code !== 0) {
            this.$message.error((data && data.msg) || "保存共享配置失败");
            return;
          }
          this.$message.success("服务配置已保存，请确认目标智能体已添加此插件");
          this.$emit("saved", this.providerCode);
          this.dialogVisible = false;
        });
      });
    },
    testConfig() {
      this.$refs.form.validate((valid) => {
        if (!valid) return;
        this.testing = true;
        Api.user.testPluginConfig(this.providerCode, this.form, ({ data }) => {
          this.testing = false;
          if (!data || data.code !== 0) {
            this.$message.error((data && data.msg) || "插件测试失败");
            return;
          }
          const result = data.data || {};
          if (!result.success) {
            this.$message.error(result.message || "插件测试失败");
            return;
          }
          this.$message.success(result.message || "插件测试通过");
        });
      });
    },
    removeConfig() {
      this.$confirm("删除后，使用该功能的智能体将无法获得共享密钥。", "删除共享配置", {
        type: "warning",
      }).then(() => {
        Api.user.deletePluginConfig(this.providerCode, ({ data }) => {
          if (!data || data.code !== 0) {
            this.$message.error((data && data.msg) || "删除共享配置失败");
            return;
          }
          this.$message.success("共享配置已删除");
          this.$emit("saved", this.providerCode);
          this.dialogVisible = false;
        });
      }).catch(() => {});
    },
  },
};
</script>

<style scoped>
.setup-guidance { margin-bottom: 20px; }
.plugin-service-dialog ::v-deep .el-dialog { max-width: calc(100vw - 32px); max-height: 88vh; display: flex; flex-direction: column; }
.plugin-service-dialog ::v-deep .el-dialog__body { min-height: 0; overflow-y: auto; }
.plugin-service-dialog ::v-deep .el-dialog__header,
.plugin-service-dialog ::v-deep .el-dialog__footer { flex-shrink: 0; }
</style>
