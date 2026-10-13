<template>
  <div class="person-page">
    <HeaderBar />
    <ResourceCenterNav />

    <main class="person-main">
      <section class="page-hero">
        <div>
          <h1>用户信息</h1>
          <p>这里的“用户信息”是可识别人物档案，不是后台登录账号；人脸需绑定智能体并显式发布后才会同步到设备。</p>
        </div>
        <div class="hero-actions">
          <el-button icon="el-icon-setting" @click="receptionWorkspaceVisible = true">
            接待配置
          </el-button>
          <el-button type="primary" icon="el-icon-plus" :disabled="!canWrite" @click="openCreate">
            新增用户信息
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
            <span>条用户信息</span>
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
          <h3>还没有用户信息</h3>
          <p>先建立基础资料；人脸与声纹可以按需补充。</p>
          <div class="empty-steps">
            <span><b>1</b>新增用户信息</span>
            <i class="el-icon-arrow-right" />
            <span><b>2</b>上传正面照片</span>
            <i class="el-icon-arrow-right" />
            <span><b>3</b>绑定智能体</span>
            <i class="el-icon-arrow-right" />
            <span><b>4</b>显式发布</span>
          </div>
          <el-button v-if="canWrite" type="primary" icon="el-icon-plus" @click="openCreate">新增第一条用户信息</el-button>
        </div>

        <div v-else class="person-groups">
          <section v-for="(group, groupIndex) in groupedProfiles" :key="group.name" class="person-group">
            <header class="person-group__header">
              <div>
                <span class="group-marker" />
                <strong>{{ group.name }}</strong>
                <span>（{{ group.items.length }}）</span>
              </div>
              <button type="button" class="group-toggle" @click="toggleGroup(group.name)">
                {{ collapsedGroups[group.name] ? "展开" : "收起" }}
                <i :class="collapsedGroups[group.name] ? 'el-icon-caret-bottom' : 'el-icon-caret-top'" />
              </button>
            </header>

            <div v-show="!collapsedGroups[group.name]" class="person-grid">
              <button v-if="canWrite && groupIndex === 0" type="button" class="create-card" @click="openCreate">
                <span class="create-card__icon"><i class="el-icon-plus" /></span>
                <span>
                  <strong>添加用户信息</strong>
                  <small>创建新的用户档案</small>
                </span>
                <i class="el-icon-user create-card__illustration" />
              </button>

              <PersonProfileCard
                v-for="profile in group.items"
                :key="profile.id"
                :profile="profile"
                :face-url="faceUrls[profile.id] || ''"
                :can-write="canWrite"
                @edit="openEdit"
                @voiceprint="openVoicePrintSection"
                @reception="openReception"
                @publish="publishProfile"
                @delete="deleteProfile"
              />
            </div>
          </section>
        </div>
      </section>
    </main>

    <el-dialog
      :title="editingId ? '编辑用户信息' : '新增用户信息'"
      :visible.sync="dialogVisible"
      width="1040px"
      custom-class="person-editor-dialog"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving && !voiceOperationBusy"
      :show-close="!saving && !voiceOperationBusy"
      :before-close="beforeEditorClose"
      @close="invalidateEditSession"
      @closed="resetDialog"
    >
      <el-form ref="personForm" :model="form" :rules="rules" label-position="top">
        <div class="dialog-grid">
          <section class="assets-column">
            <div class="asset-section">
              <label class="section-label"><span>*</span> 上传照片</label>
              <el-upload
                class="face-uploader"
                action="#"
                accept="image/jpeg,image/png"
                :auto-upload="false"
                :show-file-list="false"
                :disabled="saving"
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
            </div>

            <PersonVoiceprintPanel
              :key="editingId || 'new-person'"
              class="asset-section"
              :person-id="editingId"
              :person-name="form.name"
              :person-description="form.description"
              :agent-ids="form.agentIds"
              :agents="agents"
              :voice-print="currentVoicePrint"
              :can-write="canWrite && !saving"
              :active="dialogVisible"
              @changed="handleVoicePrintChanged"
              @operation-busy-change="voiceOperationBusy = $event"
            />
          </section>

          <section class="fields-column">
            <div class="two-columns">
              <el-form-item label="姓名" prop="name">
                <el-input v-model.trim="form.name" maxlength="30" show-word-limit :disabled="saving" />
              </el-form-item>
              <el-form-item label="分组">
                <el-input v-model.trim="form.groupName" maxlength="60" placeholder="例如：家人、员工、访客" :disabled="saving" />
              </el-form-item>
              <el-form-item label="性别">
                <el-select v-model="form.gender" :disabled="saving">
                  <el-option label="未知/不填写" value="UNKNOWN" />
                  <el-option label="男" value="MALE" />
                  <el-option label="女" value="FEMALE" />
                </el-select>
              </el-form-item>
              <el-form-item label="生日">
                <el-date-picker v-model="form.birthday" type="date" value-format="yyyy-MM-dd" placeholder="选择生日" :disabled="saving" />
              </el-form-item>
            </div>

            <el-form-item>
              <div class="agent-binding-card">
                <div>
                  <strong>绑定智能体</strong>
                  <span>这里只建立资源归属，不会立即发送照片。</span>
                </div>
                <el-select v-model="form.agentIds" multiple filterable placeholder="选择一个或多个智能体" :disabled="saving">
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
              <el-radio-group v-model="form.recognitionAction" :disabled="saving">
                <el-radio-button label="NONE">只记录</el-radio-button>
                <el-radio-button label="SPEAK_TEXT">识别后说一句话</el-radio-button>
              </el-radio-group>
            </el-form-item>

            <el-form-item v-if="form.recognitionAction === 'SPEAK_TEXT'" label="播报内容" prop="greetingText">
              <el-input
                v-model.trim="form.greetingText"
                maxlength="500"
                show-word-limit
                :disabled="saving"
                placeholder="例如：欢迎回来，需要我帮你做什么？"
              />
            </el-form-item>

            <el-form-item label="介绍">
              <el-input v-model="form.description" type="textarea" :rows="4" maxlength="200" show-word-limit :disabled="saving" />
            </el-form-item>

            <el-form-item label="状态">
              <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="允许发布到智能体人脸库" :disabled="saving" />
            </el-form-item>
          </section>
        </div>
      </el-form>
      <div slot="footer">
        <el-button :disabled="saving || voiceOperationBusy" @click="closeEditor">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canWrite || voiceOperationBusy" @click="saveProfile">
          {{ editingId ? "保存用户信息" : "先保存用户信息" }}
        </el-button>
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
import PersonProfileCard from "@/components/person/PersonProfileCard.vue";
import PersonReceptionDialog from "@/components/person/PersonReceptionDialog.vue";
import PersonVoiceprintPanel from "@/components/person/PersonVoiceprintPanel.vue";
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
    agentIds: [],
    voicePrintReady: false,
    voicePrintId: "",
    voicePrintAgentId: "",
    voicePrintSourceName: "",
    voicePrintDescription: "",
    voicePrintCreateDate: null
  };
}

export default {
  name: "PersonProfileManagement",
  components: {
    HeaderBar,
    PersonProfileCard,
    PersonReceptionDialog,
    PersonVoiceprintPanel,
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
      editSessionGeneration: 0,
      activeSaveId: 0,
      voiceOperationBusy: false,
      profileRequestId: 0,
      faceLoadGeneration: 0,
      faceRequestIds: {},
      destroyed: false,
      receptionPersonVisible: false,
      receptionWorkspaceVisible: false,
      receptionPerson: null,
      collapsedGroups: {},
      canWrite: false
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
    groupedProfiles() {
      const grouped = new Map();
      this.filteredProfiles.forEach(profile => {
        const name = profile.groupName || "未分组";
        if (!grouped.has(name)) grouped.set(name, []);
        grouped.get(name).push(profile);
      });
      return [...grouped.entries()]
        .sort(([left], [right]) => {
          if (left === "未分组") return 1;
          if (right === "未分组") return -1;
          return left.localeCompare(right, "zh-CN");
        })
        .map(([name, items]) => ({ name, items }));
    },
    currentVoicePrint() {
      return {
        ready: Boolean(this.form.voicePrintReady),
        id: this.form.voicePrintId || "",
        agentId: this.form.voicePrintAgentId || "",
        sourceName: this.form.voicePrintSourceName || "",
        description: this.form.voicePrintDescription || "",
        createDate: this.form.voicePrintCreateDate || null
      };
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
    this.loadWorkspacePermission();
    this.loadProfiles();
    this.loadAgents();
    Api.person.capability(() => {}, () => {});
  },
  beforeDestroy() {
    this.destroyed = true;
    this.profileRequestId += 1;
    this.faceLoadGeneration += 1;
    this.invalidateEditSession();
    this.revokeAllFaceUrls();
    this.revokeDialogPreview();
  },
  methods: {
    loadWorkspacePermission() {
      Api.workspace.current(({ data }) => {
        if (this.destroyed || !data || data.code !== 0 || !data.data) return;
        this.canWrite = Boolean(data.data.canWrite);
        localStorage.setItem("currentWorkspaceCanWrite", this.canWrite ? "true" : "false");
      });
    },
    loadProfiles() {
      const requestId = ++this.profileRequestId;
      this.loading = true;
      Api.person.list(this.keyword.trim(), ({ data }) => {
        if (this.destroyed || requestId !== this.profileRequestId) return;
        this.loading = false;
        if (!data || data.code !== 0) {
          this.$message.error((data && data.msg) || "获取用户信息失败");
          return;
        }
        const faceGeneration = ++this.faceLoadGeneration;
        this.revokeAllFaceUrls();
        this.profiles = data.data || [];
        this.profiles.filter(item => item.faceReady).forEach(item => this.loadFace(item, faceGeneration));
      }, () => {
        if (this.destroyed || requestId !== this.profileRequestId) return;
        this.loading = false;
        this.$message.error("获取用户信息失败");
      });
    },
    loadAgents() {
      Api.person.agents(({ data }) => {
        if (data && data.code === 0) {
          this.agents = data.data || [];
        }
      });
    },
    loadFace(profile, generation = this.faceLoadGeneration) {
      const requestId = (this.faceRequestIds[profile.id] || 0) + 1;
      this.$set(this.faceRequestIds, profile.id, requestId);
      Api.person.loadFace(profile.id, (res) => {
        if (this.destroyed
            || generation !== this.faceLoadGeneration
            || requestId !== this.faceRequestIds[profile.id]) return;
        const blob = new Blob([res.data], { type: "image/jpeg" });
        const nextUrl = URL.createObjectURL(blob);
        const previousUrl = this.faceUrls[profile.id];
        this.$set(this.faceUrls, profile.id, nextUrl);
        if (previousUrl && previousUrl !== this.dialogPreviewUrl) URL.revokeObjectURL(previousUrl);
      }, () => {});
    },
    revokeAllFaceUrls() {
      Object.values(this.faceUrls)
        .filter(url => url !== this.dialogPreviewUrl)
        .forEach(url => URL.revokeObjectURL(url));
      this.faceUrls = {};
      this.faceRequestIds = {};
    },
    openCreate() {
      if (!this.canWrite) {
        this.$message.warning("当前工作空间为只读");
        return;
      }
      this.editSessionGeneration += 1;
      this.voiceOperationBusy = false;
      this.editingId = "";
      this.form = emptyForm();
      this.faceFile = null;
      this.revokeDialogPreview();
      this.dialogVisible = true;
    },
    openEdit(profile) {
      if (this.saving) return;
      this.editSessionGeneration += 1;
      this.voiceOperationBusy = false;
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
        agentIds: [...(profile.agentIds || [])],
        voicePrintReady: Boolean(profile.voicePrintReady),
        voicePrintId: profile.voicePrintId || "",
        voicePrintAgentId: profile.voicePrintAgentId || "",
        voicePrintSourceName: profile.voicePrintSourceName || "",
        voicePrintDescription: profile.voicePrintDescription || "",
        voicePrintCreateDate: profile.voicePrintCreateDate || null
      };
      this.faceFile = null;
      this.revokeDialogPreview();
      this.dialogPreviewUrl = this.faceUrls[profile.id] || "";
      this.dialogVisible = true;
    },
    openVoicePrintSection(profile) {
      if (!this.canWrite) return;
      this.openEdit(profile);
    },
    openReception(profile) {
      this.receptionPerson = profile;
      this.receptionPersonVisible = true;
    },
    toggleGroup(groupName) {
      this.$set(this.collapsedGroups, groupName, !this.collapsedGroups[groupName]);
    },
    handleFaceChange(uploadFile) {
      if (this.saving) return;
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
      if (this.saving || this.voiceOperationBusy || !this.canWrite) return;
      const session = {
        generation: this.editSessionGeneration,
        targetId: this.editingId
      };
      this.$refs.personForm.validate(valid => {
        if (!this.isEditSessionActive(session)) return;
        if (!valid) return;
        if (!this.editingId && !this.faceFile) {
          this.$message.warning("请先选择一张清晰正面照片");
          return;
        }
        const context = {
          ...session,
          operationId: ++this.activeSaveId,
          faceFile: this.faceFile,
          previewUrl: this.dialogPreviewUrl
        };
        const creating = !context.targetId;
        this.saving = true;
        const payload = {
          name: this.form.name,
          gender: this.form.gender,
          birthday: this.form.birthday,
          groupName: this.form.groupName,
          description: this.form.description,
          recognitionAction: this.form.recognitionAction,
          greetingText: this.form.greetingText,
          status: this.form.status,
          agentIds: [...(this.form.agentIds || [])]
        };
        const onSaved = ({ data }) => {
          if (!data || data.code !== 0 || !data.data) {
            if (!this.isSaveContextActive(context)) return;
            this.saving = false;
            this.$message.error((data && data.msg) || "保存用户信息失败");
            return;
          }
          const profile = data.data;
          if (context.faceFile) {
            Api.person.uploadFace(profile.id, context.faceFile, ({ data: uploadData }) => {
              if (!this.isSaveContextActive(context)) return;
              if (!uploadData || uploadData.code !== 0) {
                this.handleFaceUploadFailure(
                  profile,
                  (uploadData && uploadData.msg) || "人物资料已保存，但照片上传失败，请重试",
                  context
                );
                return;
              }
              this.finishSave(uploadData.data || profile, creating, context);
            }, () => {
              if (!this.isSaveContextActive(context)) return;
              this.handleFaceUploadFailure(
                profile,
                "人物资料已保存，但照片上传失败，请重试",
                context
              );
            });
          } else if (this.isSaveContextActive(context)) {
            this.finishSave(profile, creating, context);
          }
        };
        const onFailed = err => {
          if (!this.isSaveContextActive(context)) return;
          this.saving = false;
          this.$message.error((err && err.data && err.data.msg) || "保存用户信息失败");
        };
        if (context.targetId) {
          Api.person.update(context.targetId, payload, onSaved, onFailed);
        } else {
          Api.person.create(payload, onSaved, onFailed);
        }
      });
    },
    isEditSessionActive(context) {
      return Boolean(
        context
        && this.dialogVisible
        && context.generation === this.editSessionGeneration
        && context.targetId === this.editingId
      );
    },
    isSaveContextActive(context) {
      return this.isEditSessionActive(context) && context.operationId === this.activeSaveId;
    },
    invalidateEditSession() {
      this.editSessionGeneration += 1;
    },
    beforeEditorClose(done) {
      if (this.voiceOperationBusy) {
        this.$message.warning("声纹操作正在处理，请稍候");
        return;
      }
      if (this.saving) {
        this.$message.warning("用户信息正在保存，请稍候");
        return;
      }
      done();
    },
    closeEditor() {
      if (this.voiceOperationBusy) {
        this.$message.warning("声纹操作正在处理，请稍候");
        return;
      }
      if (!this.saving) this.dialogVisible = false;
    },
    handleFaceUploadFailure(profile, message, context) {
      if (!this.isSaveContextActive(context)) return;
      this.saving = false;
      // 创建资料已经成功，后续重试必须更新同一条记录，不能重复创建人物。
      this.editingId = profile.id;
      this.$message.error(message);
      this.loadProfiles();
    },
    finishSave(profile, keepEditing, context) {
      if (!this.isSaveContextActive(context)) return;
      this.saving = false;
      if (keepEditing && profile && profile.id) {
        this.editingId = profile.id;
        this.form = {
          ...this.form,
          name: profile.name || this.form.name,
          gender: profile.gender || this.form.gender,
          birthday: profile.birthday || this.form.birthday,
          groupName: profile.groupName || this.form.groupName,
          description: profile.description || this.form.description,
          recognitionAction: profile.recognitionAction || this.form.recognitionAction,
          greetingText: profile.greetingText || this.form.greetingText,
          status: profile.status === 0 ? 0 : 1,
          agentIds: [...(profile.agentIds || this.form.agentIds || [])],
          voicePrintReady: Boolean(profile.voicePrintReady),
          voicePrintId: profile.voicePrintId || "",
          voicePrintAgentId: profile.voicePrintAgentId || "",
          voicePrintSourceName: profile.voicePrintSourceName || "",
          voicePrintDescription: profile.voicePrintDescription || "",
          voicePrintCreateDate: profile.voicePrintCreateDate || null
        };
        if (context.previewUrl) this.$set(this.faceUrls, profile.id, context.previewUrl);
        this.faceFile = null;
        this.profiles.push(profile);
        this.$message.success("基础资料和照片已保存，现在可以继续录入或关联声纹；人脸变更仍需显式发布");
        return;
      }
      this.dialogVisible = false;
      this.$message.success("用户信息草稿已保存；人脸变更确认无误后请显式发布");
      this.loadProfiles();
    },
    handleVoicePrintChanged({ personId = "", fields = {}, profile = {}, reload = false } = {}) {
      if (!this.dialogVisible || !personId || personId !== this.editingId) return;
      Object.entries(fields).forEach(([key, value]) => this.$set(this.form, key, value));
      const index = this.profiles.findIndex(item => item.id === personId);
      if (index >= 0) {
        this.$set(this.profiles, index, { ...this.profiles[index], ...fields });
      }
      this.mergeProfileResponse(profile);
      if (reload) this.loadProfiles();
    },
    mergeProfileResponse(profile) {
      if (!profile || !profile.id) return;
      const index = this.profiles.findIndex(item => item.id === profile.id);
      if (index >= 0) this.$set(this.profiles, index, { ...this.profiles[index], ...profile });
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
      if (profile.voicePrintReady) {
        this.$message.warning("该用户已关联声纹，请先在用户信息中解除关联或删除声纹");
        return;
      }
      this.$confirm(
        `删除“${profile.name}”后，相关智能体发布版本会移除此人脸。是否继续？`,
        "删除用户信息",
        { type: "warning", confirmButtonText: "删除", cancelButtonText: "取消" }
      ).then(() => {
        Api.person.remove(profile.id, ({ data }) => {
          if (data && data.code === 0) {
            this.$message.success("已删除用户信息");
            this.loadProfiles();
          } else {
            this.$message.error((data && data.msg) || "删除失败");
          }
        }, err => this.$message.error((err && err.data && err.data.msg) || "删除失败"));
      }).catch(() => {});
    },
    resetDialog() {
      this.voiceOperationBusy = false;
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
  margin: 5px 0 0;
  color: #747d93;
  font-size: 13px;
}

.page-hero h1 {
  margin: 0;
  color: #252c3d;
  font-size: 24px;
  line-height: 32px;
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

.person-groups,
.person-group {
  display: grid;
  gap: 18px;
}

.person-group__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 4px 2px 0;
  color: #737c8e;
  font-size: 13px;
}

.person-group__header > div {
  display: flex;
  align-items: center;
  gap: 7px;
}

.person-group__header strong {
  color: #424a5e;
  font-weight: 600;
}

.group-marker {
  width: 5px;
  height: 18px;
  border-radius: 4px;
  background: #f6af3c;
}

.group-toggle {
  padding: 0;
  border: 0;
  background: transparent;
  color: #7b8495;
  cursor: pointer;
  font: inherit;
}

.person-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 22px;
}

.create-card {
  position: relative;
  display: flex;
  min-height: 205px;
  overflow: hidden;
  align-items: center;
  gap: 16px;
  padding: 28px 30px;
  border: 2px dashed #d9dde5;
  border-radius: 22px;
  background: linear-gradient(135deg, #fff 15%, #fffaf0 100%);
  color: #333b4d;
  text-align: left;
  cursor: pointer;
}

.create-card:hover {
  border-color: #efb957;
  box-shadow: 0 12px 28px rgba(114, 87, 35, 0.09);
}

.create-card > span:not(.create-card__icon) {
  z-index: 1;
  display: grid;
  gap: 6px;
}

.create-card strong {
  font-size: 20px;
}

.create-card small {
  color: #9a9fa9;
  font-size: 13px;
}

.create-card__icon {
  z-index: 1;
  display: grid;
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  place-items: center;
  border-radius: 50%;
  background: #ffd778;
  color: #72520f;
  font-size: 23px;
}

.create-card__illustration {
  position: absolute;
  right: 22px;
  bottom: -15px;
  color: rgba(244, 180, 55, 0.13);
  font-size: 138px;
}

.dialog-grid {
  display: grid;
  grid-template-columns: 390px minmax(0, 1fr);
  gap: 30px;
}

.assets-column {
  display: grid;
  align-content: start;
  gap: 18px;
}

.asset-section {
  min-width: 0;
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
  height: 220px;
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

::v-deep .person-editor-dialog {
  max-width: calc(100vw - 32px);
}

@media (max-width: 1460px) {
  .person-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1120px) {
  .dialog-grid {
    grid-template-columns: 330px minmax(0, 1fr);
  }

  .recognition-summary > span:nth-child(2) {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
