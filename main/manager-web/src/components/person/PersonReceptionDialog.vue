<template>
  <el-dialog
    :title="person ? `${person.name} · 接待资料与预约` : '接待资料与预约'"
    :visible.sync="dialogVisible"
    width="860px"
    :close-on-click-modal="false"
    @open="load"
  >
    <el-form label-position="top" :model="form" v-loading="loading">
      <div class="two-columns">
        <el-form-item label="客户阶段">
          <el-select v-model="form.customerStage">
            <el-option label="新客户" value="NEW" />
            <el-option label="回访客户" value="RETURNING" />
            <el-option label="长期客户" value="LONG_TERM" />
            <el-option label="VIP" value="VIP" />
          </el-select>
        </el-form-item>
        <el-form-item label="公司名称">
          <el-input v-model.trim="form.companyName" maxlength="120" />
        </el-form-item>
      </div>
      <el-form-item label="爱好（每行一项）">
        <el-input v-model="form.hobbiesText" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="餐饮偏好、预算、忌口（每行一项）">
        <el-input v-model="form.foodPreferencesText" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="座位偏好（每行一项）">
        <el-input v-model="form.seatPreferencesText" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="普通接待备注">
        <el-input v-model="form.receptionNotes" type="textarea" maxlength="500" show-word-limit />
      </el-form-item>
      <el-form-item label="敏感需求（仅在授权 SENSITIVE 后下发）">
        <el-input v-model="form.sensitiveNotes" type="textarea" maxlength="500" show-word-limit />
      </el-form-item>
      <el-form-item label="允许用途">
        <el-checkbox-group v-model="form.consentScopes">
          <el-checkbox label="RECEPTION">迎宾上下文</el-checkbox>
          <el-checkbox label="RECOMMENDATION">推荐</el-checkbox>
          <el-checkbox label="MEMORY">长期记忆</el-checkbox>
          <el-checkbox label="SENSITIVE">敏感需求</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-button type="primary" :disabled="!canWrite" :loading="saving" @click="saveProfile">
        保存接待资料
      </el-button>
    </el-form>

    <el-divider>预约</el-divider>
    <el-table :data="appointments" size="small" empty-text="暂无预约">
      <el-table-column prop="startAt" label="开始" min-width="150" />
      <el-table-column prop="endAt" label="结束" min-width="150" />
      <el-table-column prop="partySize" label="人数" width="70" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column prop="seatRequest" label="座位需求" min-width="120" />
      <el-table-column v-if="canWrite" label="操作" width="70">
        <template slot-scope="scope">
          <el-button type="text" class="danger" @click="removeAppointment(scope.row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-form v-if="canWrite" class="appointment-form" label-position="top" :model="appointmentForm">
      <div class="two-columns">
        <el-form-item label="开始时间">
          <el-date-picker v-model="appointmentForm.startAt" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="appointmentForm.endAt" type="datetime" value-format="yyyy-MM-dd HH:mm:ss" />
        </el-form-item>
        <el-form-item label="人数">
          <el-input-number v-model="appointmentForm.partySize" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="座位需求">
          <el-input v-model.trim="appointmentForm.seatRequest" maxlength="200" />
        </el-form-item>
      </div>
      <el-button :loading="savingAppointment" @click="createAppointment">新增预约</el-button>
    </el-form>
  </el-dialog>
</template>

<script>
import Api from "@/apis/api";

function emptyProfile() {
  return {
    customerStage: "NEW",
    companyName: "",
    hobbiesText: "",
    foodPreferencesText: "",
    seatPreferencesText: "",
    receptionNotes: "",
    sensitiveNotes: "",
    consentScopes: [],
    status: 1
  };
}

function lines(value) {
  return String(value || "").split(/\r?\n/).map(item => item.trim()).filter(Boolean);
}

function jsonLines(value) {
  if (!value) return "";
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    return Array.isArray(parsed) ? parsed.join("\n") : "";
  } catch (_error) {
    return "";
  }
}

export default {
  name: "PersonReceptionDialog",
  props: {
    visible: { type: Boolean, default: false },
    person: { type: Object, default: null },
    canWrite: { type: Boolean, default: false }
  },
  data() {
    return {
      loading: false,
      saving: false,
      savingAppointment: false,
      form: emptyProfile(),
      appointments: [],
      appointmentForm: {
        startAt: "",
        endAt: "",
        partySize: 1,
        status: "CONFIRMED",
        seatRequest: "",
        notes: ""
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
      if (!this.person) return;
      this.loading = true;
      Api.person.receptionProfile(this.person.id, ({ data }) => {
        const profile = data && data.code === 0 ? data.data : null;
        this.form = profile ? {
          customerStage: profile.customerStage || "NEW",
          companyName: profile.companyName || "",
          hobbiesText: jsonLines(profile.hobbiesJson),
          foodPreferencesText: jsonLines(profile.foodPreferencesJson),
          seatPreferencesText: jsonLines(profile.seatPreferencesJson),
          receptionNotes: profile.receptionNotes || "",
          sensitiveNotes: profile.sensitiveNotes || "",
          consentScopes: String(profile.consentScope || "").split(",").filter(Boolean),
          status: profile.status === 0 ? 0 : 1
        } : emptyProfile();
        this.loading = false;
      }, () => {
        this.loading = false;
        this.$message.error("获取接待资料失败");
      });
      this.loadAppointments();
    },
    loadAppointments() {
      Api.person.appointments(this.person.id, ({ data }) => {
        this.appointments = data && data.code === 0 ? (data.data || []) : [];
      }, () => this.$message.error("获取预约失败"));
    },
    saveProfile() {
      this.saving = true;
      Api.person.saveReceptionProfile(this.person.id, {
        customerStage: this.form.customerStage,
        companyName: this.form.companyName,
        hobbies: lines(this.form.hobbiesText),
        foodPreferences: lines(this.form.foodPreferencesText),
        seatPreferences: lines(this.form.seatPreferencesText),
        receptionNotes: this.form.receptionNotes,
        sensitiveNotes: this.form.sensitiveNotes,
        consentScopes: this.form.consentScopes,
        status: this.form.status
      }, ({ data }) => {
        this.saving = false;
        if (data && data.code === 0) this.$message.success("接待资料已保存");
        else this.$message.error((data && data.msg) || "保存失败");
      }, () => {
        this.saving = false;
        this.$message.error("保存接待资料失败");
      });
    },
    createAppointment() {
      if (!this.appointmentForm.startAt || !this.appointmentForm.endAt) {
        this.$message.warning("请选择预约开始和结束时间");
        return;
      }
      this.savingAppointment = true;
      Api.person.createAppointment(this.person.id, this.appointmentForm, ({ data }) => {
        this.savingAppointment = false;
        if (data && data.code === 0) {
          this.$message.success("预约已创建");
          this.loadAppointments();
        } else this.$message.error((data && data.msg) || "创建预约失败");
      }, () => {
        this.savingAppointment = false;
        this.$message.error("创建预约失败");
      });
    },
    removeAppointment(item) {
      Api.person.removeAppointment(item.id, ({ data }) => {
        if (data && data.code === 0) this.loadAppointments();
        else this.$message.error((data && data.msg) || "删除预约失败");
      }, () => this.$message.error("删除预约失败"));
    }
  }
};
</script>

<style scoped>
.two-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.appointment-form { margin-top: 18px; padding-top: 12px; border-top: 1px solid #ebeef5; }
.danger { color: #f56c6c; }
</style>
