<template>
  <el-dialog title="门口接待配置" :visible.sync="dialogVisible" width="900px" @open="load">
    <el-form label-position="top" :model="form" v-loading="loading">
      <div class="two-columns">
        <el-form-item label="公司名称"><el-input v-model.trim="form.companyName" maxlength="120" /></el-form-item>
        <el-form-item label="服务员说明"><el-input v-model.trim="form.serviceContactText" maxlength="200" /></el-form-item>
      </div>
      <el-form-item label="公司介绍（最多60字）">
        <el-input v-model="form.companyIntroText" type="textarea" maxlength="60" show-word-limit />
      </el-form-item>
      <el-form-item label="点餐二维码内容">
        <el-input v-model.trim="form.orderQrContent" maxlength="1024" />
      </el-form-item>
      <el-alert type="info" :closable="false" title="以下字段使用 JSON 数组/对象；保存前会校验格式。" />
      <el-form-item label="FAQ JSON（最多100项）"><el-input v-model="form.faqText" type="textarea" :rows="5" /></el-form-item>
      <el-form-item label="餐品 JSON"><el-input v-model="form.productsText" type="textarea" :rows="5" /></el-form-item>
      <el-form-item label="语义座位 JSON"><el-input v-model="form.seatsText" type="textarea" :rows="5" /></el-form-item>
      <el-form-item label="舞台流程 JSON"><el-input v-model="form.stageShowText" type="textarea" :rows="4" /></el-form-item>
    </el-form>
    <div slot="footer">
      <el-button @click="dialogVisible = false">关闭</el-button>
      <el-button type="primary" :disabled="!canWrite" :loading="saving" @click="save">保存配置</el-button>
    </div>
  </el-dialog>
</template>

<script>
import Api from "@/apis/api";

function pretty(value, fallback) {
  if (!value) return JSON.stringify(fallback, null, 2);
  try { return JSON.stringify(typeof value === "string" ? JSON.parse(value) : value, null, 2); }
  catch (_error) { return JSON.stringify(fallback, null, 2); }
}

export default {
  name: "ReceptionWorkspaceDialog",
  props: {
    visible: { type: Boolean, default: false },
    canWrite: { type: Boolean, default: false }
  },
  data() {
    return {
      loading: false,
      saving: false,
      form: {
        companyName: "", companyIntroText: "", orderQrContent: "", serviceContactText: "",
        faqText: "[]", productsText: "[]", seatsText: "[]", stageShowText: "{}"
      }
    };
  },
  computed: {
    dialogVisible: {
      get() { return this.visible; },
      set(value) { this.$emit("update:visible", value); }
    }
  },
  methods: {
    load() {
      this.loading = true;
      Api.person.receptionWorkspaceConfig(({ data }) => {
        const value = data && data.code === 0 && data.data ? data.data : {};
        this.form = {
          companyName: value.companyName || "",
          companyIntroText: value.companyIntroText || "",
          orderQrContent: value.orderQrContent || "",
          serviceContactText: value.serviceContactText || "",
          faqText: pretty(value.faqJson, []),
          productsText: pretty(value.productsJson, []),
          seatsText: pretty(value.seatsJson, []),
          stageShowText: pretty(value.stageShowJson, {})
        };
        this.loading = false;
      }, () => {
        this.loading = false;
        this.$message.error("获取接待配置失败");
      });
    },
    save() {
      let faq; let products; let seats; let stageShow;
      try {
        faq = JSON.parse(this.form.faqText || "[]");
        products = JSON.parse(this.form.productsText || "[]");
        seats = JSON.parse(this.form.seatsText || "[]");
        stageShow = JSON.parse(this.form.stageShowText || "{}");
        if (!Array.isArray(faq) || !Array.isArray(products) || !Array.isArray(seats) || Array.isArray(stageShow)) {
          throw new Error("JSON 类型错误");
        }
      } catch (error) {
        this.$message.error(`接待配置 JSON 无效：${error.message}`);
        return;
      }
      this.saving = true;
      Api.person.saveReceptionWorkspaceConfig({
        companyName: this.form.companyName,
        companyIntroText: this.form.companyIntroText,
        orderQrContent: this.form.orderQrContent,
        serviceContactText: this.form.serviceContactText,
        faq, products, seats, stageShow, status: 1
      }, ({ data }) => {
        this.saving = false;
        if (data && data.code === 0) this.$message.success("接待配置已保存");
        else this.$message.error((data && data.msg) || "保存失败");
      }, () => {
        this.saving = false;
        this.$message.error("保存接待配置失败");
      });
    }
  }
};
</script>

<style scoped>
.two-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
</style>
