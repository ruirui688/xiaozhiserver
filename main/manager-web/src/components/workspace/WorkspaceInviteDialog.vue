<template>
  <el-dialog
    title="邀请成员"
    :visible="visible"
    width="560px"
    class="workspace-invite-dialog"
    :close-on-click-modal="false"
    @close="closeDialog"
    @closed="resetLinks"
  >
    <p class="dialog-hint">选择成员权限并复制邀请链接。每条链接仅限 1 人使用，24 小时后失效。</p>

    <div
      v-for="option in roleOptions"
      :key="option.role"
      class="role-option"
      :class="{ selected: selectedRole === option.role }"
      @click="selectedRole = option.role"
    >
      <el-radio v-model="selectedRole" :label="option.role">
        <span class="role-name">{{ option.label }}</span>
      </el-radio>
      <p class="role-description">{{ option.description }}</p>
      <p v-if="option.role === 'ADMIN'" class="role-warning">管理员可修改资源和成员，请只发给可信用户。</p>
      <el-button
        class="copy-button"
        :type="selectedRole === option.role ? 'primary' : 'default'"
        size="small"
        :loading="creatingRole === option.role || copyingRole === option.role"
        :disabled="buttonDisabled(option.role)"
        @click.stop="createOrCopy(option.role)"
      >
        {{ links[option.role] ? "再次复制" : "复制链接" }}
      </el-button>
    </div>

    <div v-if="manualRole" class="manual-copy" role="alert">
      <div class="manual-copy-title">浏览器未能自动复制，请手动复制下面的完整链接：</div>
      <el-input
        :value="links[manualRole]"
        readonly
        aria-label="工作空间邀请链接"
        @focus="selectManualLink"
      />
    </div>

    <span slot="footer">
      <el-button @click="closeDialog">关闭</el-button>
    </span>
  </el-dialog>
</template>

<script>
import Api from "@/apis/api";
import { buildWorkspaceInviteLink } from "@/utils/workspaceInvitation";

export default {
  name: "WorkspaceInviteDialog",
  props: {
    visible: {
      type: Boolean,
      default: false,
    },
    workspaceId: {
      type: String,
      default: "",
    },
    occupiedSlots: {
      type: Number,
      default: 0,
    },
    maxMembers: {
      type: Number,
      default: 5,
    },
  },
  data() {
    return {
      selectedRole: "MEMBER",
      creatingRole: "",
      copyingRole: "",
      manualRole: "",
      links: {
        ADMIN: "",
        MEMBER: "",
      },
      roleOptions: [
        {
          role: "ADMIN",
          label: "管理员",
          description: "可管理成员，并编辑工作空间内的智能体、设备和资源。",
        },
        {
          role: "MEMBER",
          label: "普通成员",
          description: "可查看并使用工作空间内的智能体、设备和资源。",
        },
      ],
    };
  },
  methods: {
    buttonDisabled(role) {
      if (this.creatingRole || this.copyingRole || !this.workspaceId) return true;
      return !this.links[role] && this.occupiedSlots >= this.maxMembers;
    },
    createOrCopy(role) {
      this.selectedRole = role;
      if (this.links[role]) {
        this.copyLink(role);
        return;
      }
      if (this.buttonDisabled(role)) {
        if (this.occupiedSlots >= this.maxMembers) {
          this.$message.warning("工作空间名额已满，请先撤销待接受邀请或移除成员");
        }
        return;
      }

      this.creatingRole = role;
      Api.workspace.invite(
        this.workspaceId,
        { role },
        ({ data }) => {
          this.creatingRole = "";
          const code = data && data.data && data.data.code;
          const link = buildWorkspaceInviteLink(code);
          if (!link) {
            this.$message.error("邀请链接生成失败，请重试");
            return;
          }
          this.links[role] = link;
          this.$emit("created");
          this.copyLink(role);
        },
        (error) => {
          this.creatingRole = "";
          const message = error && error.data && error.data.msg;
          this.$message.error(message || "邀请链接生成失败，请稍后重试");
        }
      );
    },
    async copyLink(role) {
      const link = this.links[role];
      if (!link || this.copyingRole) return;
      this.copyingRole = role;
      try {
        await this.writeClipboard(link);
        this.manualRole = "";
        this.$message.success("邀请链接已复制，有效期 24 小时");
      } catch (_) {
        this.manualRole = role;
        this.$message.warning("自动复制失败，请手动复制邀请链接");
        this.$nextTick(() => {
          const input = this.$el.querySelector(".manual-copy input");
          if (input) {
            input.focus();
            input.select();
          }
        });
      } finally {
        this.copyingRole = "";
      }
    },
    async writeClipboard(text) {
      if (typeof navigator !== "undefined" && navigator.clipboard && navigator.clipboard.writeText) {
        try {
          await navigator.clipboard.writeText(text);
          return;
        } catch (_) {
          // Continue with the legacy copy fallback below.
        }
      }
      if (typeof document === "undefined" || !document.body || !document.execCommand) {
        throw new Error("clipboard unavailable");
      }
      const textarea = document.createElement("textarea");
      textarea.value = text;
      textarea.setAttribute("readonly", "readonly");
      textarea.style.position = "fixed";
      textarea.style.opacity = "0";
      document.body.appendChild(textarea);
      textarea.select();
      let copied = false;
      try {
        copied = document.execCommand("copy");
      } finally {
        document.body.removeChild(textarea);
      }
      if (!copied) throw new Error("clipboard unavailable");
    },
    selectManualLink(event) {
      if (event && event.target && event.target.select) event.target.select();
    },
    closeDialog() {
      this.$emit("update:visible", false);
    },
    resetLinks() {
      this.selectedRole = "MEMBER";
      this.creatingRole = "";
      this.copyingRole = "";
      this.manualRole = "";
      this.links.ADMIN = "";
      this.links.MEMBER = "";
    },
  },
};
</script>

<style lang="scss" scoped>
.dialog-hint {
  margin: -4px 0 16px;
  color: #778199;
  line-height: 1.6;
}

.role-option {
  position: relative;
  min-height: 92px;
  margin-bottom: 12px;
  padding: 18px 132px 16px 18px;
  border: 1px solid #dfe4ef;
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.2s, background-color 0.2s;
}

.role-option:hover,
.role-option.selected {
  border-color: #7890ff;
  background: #f7f9ff;
}

.role-name {
  color: #30364a;
  font-weight: 600;
}

.role-description,
.role-warning {
  margin: 8px 0 0 25px;
  color: #778199;
  font-size: 13px;
  line-height: 1.5;
}

.role-warning {
  color: #e6a23c;
}

.copy-button {
  position: absolute;
  top: 50%;
  right: 18px;
  min-width: 92px;
  transform: translateY(-50%);
}

.manual-copy {
  margin-top: 16px;
  padding: 12px;
  border: 1px solid #f3d19e;
  border-radius: 8px;
  background: #fdf6ec;
  text-align: left;
}

.manual-copy-title {
  margin-bottom: 8px;
  color: #8a6424;
  font-size: 13px;
}

@media (max-width: 640px) {
  .role-option {
    padding-right: 18px;
    padding-bottom: 62px;
  }

  .copy-button {
    top: auto;
    right: 18px;
    bottom: 14px;
    transform: none;
  }
}
</style>
