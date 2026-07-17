<template>
  <el-dialog
    :title="`${pluginName || '插件'}共享配置`"
    :visible.sync="dialogVisible"
    width="560px"
    append-to-body
    :close-on-click-modal="false"
    @open="loadConfig"
  >
    <el-form ref="form" :model="form" :rules="rules" label-width="110px">
      <template v-if="providerCode === 'get_weather'">
        <el-form-item label="API Host" prop="api_host">
          <el-input
            v-model.trim="form.api_host"
            maxlength="255"
            placeholder="控制台分配的 xxx.qweatherapi.com"
          />
        </el-form-item>
        <el-form-item label="API Key" prop="api_key">
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
        <el-form-item label="API Key" prop="api_key">
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
          this.$message.success("共享配置已保存，将应用到当前用户的智能体");
          this.$emit("saved", this.providerCode);
          this.dialogVisible = false;
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
