<template>
  <div class="person-page">
    <HeaderBar />
    <ResourceCenterNav />

    <main class="person-main">
      <section class="page-hero">
        <div>
          <p>独立维护人物与照片，绑定智能体后再显式发布；设备只增量同步已发布版本。</p>
        </div>
        <div class="hero-actions">
          <el-button icon="el-icon-setting" @click="receptionWorkspaceVisible = true">
            接待配置
          </el-button>
          <el-button type="primary" icon="el-icon-plus" :disabled="!canWrite" @click="openCreate">
            新增人物
          </el-button>
        </div>
      </section>

      <section class="recognition-summary">
        <span class="recognition-mode"><i class="el-icon-cpu" />设备本地识别 · 持久化缓存</span>
        <span>上传只保存草稿；发布后由绑定智能体的设备按版本增量同步。</span>
        <el-tooltip content="服务端当前不接收识别图片，也不会生成或保存人脸特征。" placement="bottom">
          <button type="button" class="learn-more">了解工作原理</button>
        </el-tooltip>
        <el-tag size="small" type="info" effect="plain">服务端识别未启用</el-tag>
      </section>

      <section class="content-shell" v-loading="loading">
        <div class="list-toolbar">
          <div class="list-summary">
            <strong>{{ filteredProfiles.length }}</strong>
            <span>位人物</span>
          </div>
          <div class="list-filters">
            <el-input
              v-model="keyword"
              clearable
              prefix-icon="el-icon-search"
              placeholder="搜索姓名、分组或介绍"
              @keyup.enter.native="loadProfiles"
              @clear="loadProfiles"
            />
            <el-select v-model="activeGroup" placeholder="全部分组">
              <el-option label="全部分组" value="all" />
              <el-option v-for="group in groups" :key="group" :label="group" :value="group" />
            </el-select>
            <el-button icon="el-icon-search" @click="loadProfiles">搜索</el-button>
          </div>
        </div>

        <div v-if="!loading && filteredProfiles.length === 0" class="empty-state">
          <i class="el-icon-user" />
          <h3>还没有人物</h3>
          <p>完成下面四步，智能体的人脸库才会对设备生效。</p>
          <div class="empty-steps">
            <span><b>1</b>新增人物</span>
            <i class="el-icon-arrow-right" />
            <span><b>2</b>上传正面照片</span>
            <i class="el-icon-arrow-right" />
            <span><b>3</b>绑定智能体</span>
            <i class="el-icon-arrow-right" />
            <span><b>4</b>显式发布</span>
          </div>
          <el-button v-if="canWrite" type="primary" icon="el-icon-plus" @click="openCreate">新增第一位人物</el-button>
        </div>

        <div v-else class="person-list">
          <article v-for="profile in filteredProfiles" :key="profile.id" class="person-card">
            <div class="person-photo">
              <img v-if="faceUrls[profile.id]" :src="faceUrls[profile.id]" :alt="`${profile.name}的人脸照片`" />
              <div v-else class="photo-placeholder">
                <i class="el-icon-user" />
              </div>
            </div>
            <div class="person-identity">
              <h3>{{ profile.name }}</h3>
              <span>{{ genderLabel(profile.gender) }}<template v-if="profile.groupName"> · {{ profile.groupName }}</template></span>
              <p>{{ profile.description || "暂无介绍" }}</p>
            </div>
            <div class="person-state">
              <span class="column-label">人脸状态</span>
              <el-tag size="mini" :type="profile.faceReady ? 'success' : 'warning'">
                {{ profile.faceReady ? `已上传 · v${profile.faceVersion}` : "待上传照片" }}
              </el-tag>
            </div>
            <div class="person-devices">
              <span class="column-label">绑定智能体</span>
              <strong>{{ profile.agentNames && profile.agentNames.length ? `${profile.agentNames.length} 个智能体` : "未绑定智能体" }}</strong>
              <small v-if="profile.agentNames && profile.agentNames.length" :title="profile.agentNames.join('、')">
                {{ profile.agentNames.join("、") }}
              </small>
            </div>
            <div class="person-action">
              <span class="column-label">识别后动作</span>
              <strong>{{ actionLabel(profile.recognitionAction) }}</strong>
              <small v-if="profile.recognitionAction === 'SPEAK_TEXT'">{{ profile.greetingText || "未填写播报内容" }}</small>
            </div>
            <div class="person-enabled">
              <span :class="['enabled-badge', { disabled: profile.status !== 1 }]">
                {{ profile.status === 1 ? "启用" : "停用" }}
              </span>
            </div>
            <div v-if="canWrite" class="person-actions">
              <el-button type="text" icon="el-icon-s-custom" @click="openReception(profile)">接待与预约</el-button>
              <el-button
                v-if="profile.unpublishedChanges"
                type="text"
                icon="el-icon-upload2"
                @click="publishProfile(profile)"
              >发布变更</el-button>
              <el-button type="text" icon="el-icon-edit" @click="openEdit(profile)">编辑</el-button>
              <el-dropdown trigger="click" @command="handleCommand(profile, $event)">
                <el-button type="text" icon="el-icon-more" aria-label="更多人物操作" />
                <el-dropdown-menu slot="dropdown">
                  <el-dropdown-item command="delete" class="danger-item">删除人物</el-dropdown-item>
                </el-dropdown-menu>
              </el-dropdown>
            </div>
          </article>
        </div>
      </section>
    </main>

    <el-dialog
      :title="editingId ? '编辑人物' : '新增人物'"
      :visible.sync="dialogVisible"
      width="900px"
      :close-on-click-modal="false"
      @closed="resetDialog"
    >
      <el-form ref="personForm" :model="form" :rules="rules" label-position="top">
        <div class="dialog-grid">
          <section class="face-column">
            <label class="section-label"><span>*</span> 人脸照片</label>
            <el-upload
              class="face-uploader"
              action="#"
              accept="image/jpeg,image/png"
              :auto-upload="false"
              :show-file-list="false"
              :on-change="handleFaceChange"
            >
              <img v-if="dialogPreviewUrl" :src="dialogPreviewUrl" alt="待上传人脸预览" />
              <div v-else class="upload-placeholder">
                <i class="el-icon-upload2" />
                <strong>选择清晰正面照片</strong>
                <span>支持 JPG、PNG，最大 5MB；上传后仅形成未发布资源版本。</span>
              </div>
            </el-upload>
            <div class="privacy-tip">
              <i class="el-icon-lock" />
              <span>上传不会下发；只有显式发布后，绑定智能体的设备才会增量同步。</span>
            </div>
          </section>

          <section class="fields-column">
            <div class="two-columns">
              <el-form-item label="姓名" prop="name">
                <el-input v-model.trim="form.name" maxlength="30" show-word-limit />
              </el-form-item>
              <el-form-item label="分组">
                <el-input v-model.trim="form.groupName" maxlength="60" placeholder="例如：家人、员工、访客" />
              </el-form-item>
              <el-form-item label="性别">
                <el-select v-model="form.gender">
                  <el-option label="未知/不填写" value="UNKNOWN" />
                  <el-option label="男" value="MALE" />
                  <el-option label="女" value="FEMALE" />
                </el-select>
              </el-form-item>
              <el-form-item label="生日">
                <el-date-picker v-model="form.birthday" type="date" value-format="yyyy-MM-dd" placeholder="选择生日" />
              </el-form-item>
            </div>

            <el-form-item>
              <div class="agent-binding-card">
                <div>
                  <strong>绑定智能体</strong>
                  <span>这里只建立资源归属，不会立即发送照片。</span>
                </div>
                <el-select v-model="form.agentIds" multiple filterable placeholder="选择一个或多个智能体">
                  <el-option
                    v-for="agent in agents"
                    :key="agent.id"
                    :label="`${agent.name}（${agent.deviceCount || 0} 台设备）`"
                    :value="agent.id"
                  />
                </el-select>
              </div>
            </el-form-item>

            <el-form-item label="识别后动作" prop="recognitionAction">
              <el-radio-group v-model="form.recognitionAction">
                <el-radio-button label="NONE">只记录</el-radio-button>
                <el-radio-button label="SPEAK_TEXT">识别后说一句话</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <el-form-item v-if="form.recognitionAction === 'SPEAK_TEXT'" label="播报内容" prop="greetingText">
              <el-input
                v-model.trim="form.greetingText"
                maxlength="500"
                show-word-limit
                placeholder="例如：欢迎回来，需要我帮你做什么？"
              />
            </el-form-item>

            <el-form-item label="介绍">
              <el-input v-model="form.description" type="textarea" :rows="4" maxlength="200" show-word-limit />
            </el-form-item>

            <el-form-item label="状态">
              <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="允许发布到智能体人脸库" />
            </el-form-item>
          </section>
        </div>
      </el-form>
      <div slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveProfile">保存人物</el-button>
      </div>
    </el-dialog>

    <PersonReceptionDialog
      :visible.sync="receptionPersonVisible"
      :person="receptionPerson"
      :can-write="canWrite"
    />
    <ReceptionWorkspaceDialog
      :visible.sync="receptionWorkspaceVisible"
      :can-write="canWrite"
    />

    <VersionFooter />
  </div>
</template>

<script>
import Api from "@/apis/api";
import HeaderBar from "@/components/HeaderBar.vue";
import PersonReceptionDialog from "@/components/person/PersonReceptionDialog.vue";
import ReceptionWorkspaceDialog from "@/components/person/ReceptionWorkspaceDialog.vue";
import ResourceCenterNav from "@/components/resource/ResourceCenterNav.vue";
import VersionFooter from "@/components/VersionFooter.vue";

function emptyForm() {
  return {
    name: "",
    gender: "UNKNOWN",
    birthday: null,
    groupName: "",
    description: "",
    recognitionAction: "NONE",
    greetingText: "",
    status: 1,
    agentIds: []
  };
}

export default {
  name: "PersonProfileManagement",
  components: {
    HeaderBar,
    PersonReceptionDialog,
    ReceptionWorkspaceDialog,
    ResourceCenterNav,
    VersionFooter
  },
  data() {
    return {
      loading: false,
      saving: false,
      keyword: "",
      activeGroup: "all",
      profiles: [],
      agents: [],
      faceUrls: {},
      dialogVisible: false,
      editingId: "",
      form: emptyForm(),
      faceFile: null,
      dialogPreviewUrl: "",
      receptionPersonVisible: false,
      receptionWorkspaceVisible: false,
      receptionPerson: null,
      canWrite: localStorage.getItem("currentWorkspaceCanWrite") !== "false"
    };
  },
  computed: {
    groups() {
      return [...new Set(this.profiles.map(item => item.groupName).filter(Boolean))].sort();
    },
    filteredProfiles() {
      if (this.activeGroup === "all") {
        return this.profiles;
      }
      return this.profiles.filter(item => item.groupName === this.activeGroup);
    },
    rules() {
      return {
        name: [{ required: true, message: "请输入姓名", trigger: "blur" }],
        recognitionAction: [{ required: true, message: "请选择识别后动作", trigger: "change" }],
        greetingText: [{
          validator: (_rule, value, callback) => {
            if (this.form.recognitionAction === "SPEAK_TEXT" && !String(value || "").trim()) {
              callback(new Error("请输入设备播报内容"));
              return;
            }
            callback();
          },
          trigger: "blur"
        }]
      };
    }
  },
  created() {
    this.loadProfiles();
    this.loadAgents();
    Api.person.capability(() => {}, () => {});
  },
  beforeDestroy() {
    this.revokeAllFaceUrls();
    this.revokeDialogPreview();
  },
  methods: {
    loadProfiles() {
      this.loading = true;
      Api.person.list(this.keyword.trim(), ({ data }) => {
        this.loading = false;
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "获取人物档案失败");
          return;
        }
        this.revokeAllFaceUrls();
        this.profiles = data.data || [];
        this.profiles.filter(item => item.faceReady).forEach(item => this.loadFace(item));
      }, () => {
        this.loading = false;
        this.$message.error("获取人物档案失败");
      });
    },
    loadAgents() {
      Api.person.agents(({ data }) => {
        if (data && data.code === 0) {
          this.agents = data.data || [];
        }
      });
    },
    loadFace(profile) {
      Api.person.loadFace(profile.id, (res) => {
        const blob = new Blob([res.data], { type: "image/jpeg" });
        this.$set(this.faceUrls, profile.id, URL.createObjectURL(blob));
      }, () => {});
    },
    revokeAllFaceUrls() {
      Object.values(this.faceUrls).forEach(url => URL.revokeObjectURL(url));
      this.faceUrls = {};
    },
    openCreate() {
      if (!this.canWrite) {
        this.$message.warning("当前工作空间为只读");
        return;
      }
      this.editingId = "";
      this.form = emptyForm();
      this.faceFile = null;
      this.revokeDialogPreview();
      this.dialogVisible = true;
    },
    openEdit(profile) {
      this.editingId = profile.id;
      this.form = {
        name: profile.name || "",
        gender: profile.gender || "UNKNOWN",
        birthday: profile.birthday || null,
        groupName: profile.groupName || "",
        description: profile.description || "",
        recognitionAction: profile.recognitionAction || "NONE",
        greetingText: profile.greetingText || "",
        status: profile.status === 0 ? 0 : 1,
        agentIds: [...(profile.agentIds || [])]
      };
      this.faceFile = null;
      this.revokeDialogPreview();
      this.dialogPreviewUrl = this.faceUrls[profile.id] || "";
      this.dialogVisible = true;
    },
    openReception(profile) {
      this.receptionPerson = profile;
      this.receptionPersonVisible = true;
    },
    handleCommand(profile, command) {
      if (command === "edit") {
        this.openEdit(profile);
      } else if (command === "delete") {
        this.deleteProfile(profile);
      }
    },
    handleFaceChange(uploadFile) {
      const raw = uploadFile.raw;
      if (!raw) return;
      if (!/^image\/(jpeg|png)$/.test(raw.type)) {
        this.$message.error("仅支持 JPG、PNG 图片");
        return;
      }
      if (raw.size > 5 * 1024 * 1024) {
        this.$message.error("图片不能超过5MB");
        return;
      }
      this.faceFile = raw;
      this.revokeDialogPreview();
      this.dialogPreviewUrl = URL.createObjectURL(raw);
    },
    saveProfile() {
      this.$refs.personForm.validate(valid => {
        if (!valid) return;
        if (!this.editingId && !this.faceFile) {
          this.$message.warning("请先选择一张清晰正面照片");
          return;
        }
        this.saving = true;
        const payload = { ...this.form };
        const onSaved = ({ data }) => {
          if (!data || data.code !== 0 || !data.data) {
            this.saving = false;
            this.$message.error((data && data.msg) || "保存人物档案失败");
            return;
          }
          const profile = data.data;
          if (this.faceFile) {
            Api.person.uploadFace(profile.id, this.faceFile, ({ data: uploadData }) => {
              this.saving = false;
              if (!uploadData || uploadData.code !== 0) {
                this.handleFaceUploadFailure(
                  profile,
                  (uploadData && uploadData.msg) || "人物资料已保存，但照片上传失败，请重试"
                );
                return;
              }
              this.finishSave();
            }, () => {
              this.handleFaceUploadFailure(profile, "人物资料已保存，但照片上传失败，请重试");
            });
          } else {
            this.finishSave();
          }
        };
        const onFailed = err => {
          this.saving = false;
          this.$message.error((err && err.data && err.data.msg) || "保存人物档案失败");
        };
        if (this.editingId) {
          Api.person.update(this.editingId, payload, onSaved, onFailed);
        } else {
          Api.person.create(payload, onSaved, onFailed);
        }
      });
    },
    handleFaceUploadFailure(profile, message) {
      this.saving = false;
      // 创建资料已经成功，后续重试必须更新同一条记录，不能重复创建人物。
      this.editingId = profile.id;
      this.$message.error(message);
      this.loadProfiles();
    },
    finishSave() {
      this.saving = false;
      this.dialogVisible = false;
      this.$message.success("人物草稿已保存；确认无误后请显式发布");
      this.loadProfiles();
    },
    publishProfile(profile) {
      this.$confirm(
        `发布“${profile.name}”后，在线设备会收到版本更新提示，并仅同步变化的人脸；是否继续？`,
        "发布智能体人脸库变更",
        { type: "warning", confirmButtonText: "发布", cancelButtonText: "取消" }
      ).then(() => {
        Api.person.publish(profile.id, ({ data }) => {
          if (data && data.code === 0) {
            this.$message.success("人脸库版本已发布");
            this.loadProfiles();
          } else this.$message.error((data && data.msg) || "发布失败");
        }, () => this.$message.error("发布失败"));
      }).catch(() => {});
    },
    deleteProfile(profile) {
      this.$confirm(
        `删除“${profile.name}”后，相关智能体发布版本会移除此人脸。是否继续？`,
        "删除人物档案",
        { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
      ).then(() => {
        Api.person.remove(profile.id, ({ data }) => {
          if (data && data.code === 0) {
            this.$message.success("已删除人物档案");
            this.loadProfiles();
          } else {
            this.$message.error((data && data.msg) || "删除失败");
          }
        }, () => this.$message.error("删除失败"));
      }).catch(() => {});
    },
    resetDialog() {
      this.editingId = "";
      this.form = emptyForm();
      this.faceFile = null;
      this.revokeDialogPreview();
      if (this.$refs.personForm) this.$refs.personForm.clearValidate();
    },
    revokeDialogPreview() {
      if (this.dialogPreviewUrl && !Object.values(this.faceUrls).includes(this.dialogPreviewUrl)) {
        URL.revokeObjectURL(this.dialogPreviewUrl);
      }
      this.dialogPreviewUrl = "";
    },
    genderLabel(gender) {
      return gender === "MALE" ? "男" : gender === "FEMALE" ? "女" : "未填写性别";
    },
    actionLabel(action) {
      return action === "SPEAK_TEXT" ? "识别后说一句话" : "只记录";
    }
  }
};
</script>

<style lang="scss" scoped>
.person-page {
  min-width: 900px;
  min-height: 100vh;
  background: linear-gradient(160deg, #f5f7fc 0%, #eef3ff 55%, #fff8eb 100%);
}

.person-main {
  width: calc(100% - 48px);
  max-width: 1680px;
  margin: 0 auto;
  padding: 20px 0 28px;
  box-sizing: border-box;
}

.page-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 14px;
}

.page-hero p {
  margin: 0;
  color: #747d93;
  font-size: 13px;
}

.hero-actions {
  display: flex;
  gap: 10px;
}

.recognition-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 9px 12px;
  border: 1px solid #e4e9f3;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.82);
  color: #7b8496;
  font-size: 13px;
}

.recognition-summary .el-tag {
  margin-left: auto;
}

.recognition-mode {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 6px;
  color: #3f5f94;
  font-weight: 600;
}

.recognition-mode i {
  color: #4f83df;
}

.learn-more {
  flex-shrink: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: #3f79d2;
  cursor: help;
  font: inherit;
}

.agent-binding-card {
  display: grid;
  gap: 10px;
  padding: 12px;
  border: 1px solid #dfe5ef;
  border-radius: 10px;
  background: #fafcff;
}

.agent-binding-card > div {
  display: grid;
  gap: 3px;
}

.agent-binding-card strong {
  color: #354058;
  font-size: 13px;
}

.agent-binding-card span {
  color: #8a94a8;
  font-size: 11px;
}

.agent-binding-card .el-select {
  width: 100%;
}

.content-shell {
  min-height: 360px;
  padding: 18px;
  border: 1px solid rgba(255, 255, 255, 0.94);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: 0 14px 38px rgba(56, 73, 113, 0.09);
}

.list-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid #edf0f5;
}

.list-summary {
  display: flex;
  align-items: baseline;
  gap: 5px;
  color: #8790a4;
  font-size: 13px;
}

.list-summary strong {
  color: #20263a;
  font-size: 18px;
}

.list-filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.list-filters .el-input {
  width: 240px;
}

.list-filters .el-select {
  width: 150px;
}

.empty-state {
  display: flex;
  min-height: 285px;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  color: #929bad;
  text-align: center;
}

.empty-state > i {
  display: grid;
  width: 54px;
  height: 54px;
  place-items: center;
  border-radius: 16px;
  background: #eef3ff;
  color: #5c83dd;
  font-size: 25px;
}

.empty-state h3 {
  margin: 12px 0 3px;
  color: #414c62;
  font-size: 16px;
}

.empty-state p {
  margin: 0 0 14px;
  font-size: 12px;
}

.empty-steps {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 18px;
}

.empty-steps span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #68758c;
  font-size: 12px;
}

.empty-steps b {
  display: inline-grid;
  width: 22px;
  height: 22px;
  place-items: center;
  border-radius: 50%;
  background: #eef3ff;
  color: #4f7dde;
  font-size: 11px;
}

.empty-steps > i {
  color: #bcc4d2;
}

.person-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.person-card {
  display: grid;
  grid-template-columns: 66px minmax(190px, 1.3fr) minmax(120px, 0.7fr) minmax(150px, 0.9fr) minmax(170px, 1fr) 58px minmax(170px, auto);
  min-height: 88px;
  align-items: center;
  gap: 14px;
  padding: 11px 14px;
  border: 1px solid #e8ecf4;
  border-radius: 11px;
  background: #fff;
  box-shadow: 0 3px 10px rgba(53, 67, 99, 0.04);
  transition: 0.2s ease;
}

.person-card:hover {
  border-color: #abc2ef;
  box-shadow: 0 7px 18px rgba(65, 96, 151, 0.1);
}

.person-photo {
  width: 62px;
  height: 62px;
  overflow: hidden;
  border-radius: 10px;
  background: #f2f4f8;
}

.person-photo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.photo-placeholder {
  display: grid;
  height: 100%;
  place-content: center;
  color: #a4acbb;
}

.photo-placeholder i {
  font-size: 25px;
}

.person-identity,
.person-state,
.person-devices,
.person-action {
  min-width: 0;
}

.person-identity h3 {
  margin: 0 0 2px;
  color: #242a3c;
  font-size: 15px;
}

.person-identity > span,
.column-label {
  color: #9299aa;
  font-size: 11px;
}

.person-identity p {
  overflow: hidden;
  margin: 5px 0 0;
  color: #697187;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.person-state,
.person-devices,
.person-action {
  display: grid;
  gap: 4px;
}

.person-state .el-tag {
  width: max-content;
}

.person-devices strong,
.person-action strong {
  overflow: hidden;
  color: #555e73;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.person-devices small,
.person-action small {
  overflow: hidden;
  color: #9aa2b3;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.enabled-badge {
  display: inline-block;
  padding: 3px 8px;
  border-radius: 9px;
  background: #e9f8f1;
  color: #2d9a6b;
  font-size: 11px;
}

.enabled-badge.disabled {
  background: #f0f2f5;
  color: #8d95a4;
}

.person-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
}

.dialog-grid {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 30px;
}

.section-label {
  display: block;
  margin-bottom: 10px;
  color: #41485c;
  font-size: 14px;
  font-weight: 600;
}

.section-label span {
  color: #f56c6c;
}

.face-uploader,
::v-deep .face-uploader .el-upload {
  width: 100%;
}

.face-uploader img,
.upload-placeholder {
  width: 100%;
  height: 300px;
  border: 1px dashed #cbd2df;
  border-radius: 14px;
  background: #f7f8fa;
}

.face-uploader img {
  object-fit: contain;
}

.upload-placeholder {
  display: grid;
  place-content: center;
  gap: 12px;
  padding: 24px;
  box-sizing: border-box;
  color: #9aa2b3;
  text-align: center;
}

.upload-placeholder i {
  color: #d89c26;
  font-size: 36px;
}

.upload-placeholder strong {
  color: #525b70;
}

.upload-placeholder span,
.privacy-tip,
.field-help {
  color: #9098aa;
  font-size: 12px;
  line-height: 18px;
}

.privacy-tip {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  padding: 11px;
  border-radius: 10px;
  background: #fff8e7;
}

.two-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.fields-column .el-select,
.fields-column .el-date-editor {
  width: 100%;
}

.danger-item {
  color: #f56c6c;
}

@media (max-width: 1200px) {
  .person-card {
    grid-template-columns: 66px minmax(170px, 1.2fr) minmax(110px, 0.7fr) minmax(130px, 0.8fr) minmax(140px, 0.9fr) 54px minmax(150px, auto);
  }

  .person-devices small,
  .person-action small {
    display: none;
  }

  .recognition-summary > span:nth-child(2) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
