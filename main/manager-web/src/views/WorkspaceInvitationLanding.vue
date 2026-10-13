<template>
  <div class="invite-page">
    <header class="invite-header">
      <div class="brand">
        <img src="@/assets/xiaozhi-logo.png" alt="" class="brand-logo" />
        <img src="@/assets/xiaozhi-ai.png" alt="小智" class="brand-name" />
      </div>
    </header>

    <main class="invite-main">
      <section class="invite-card">
        <div class="invite-icon"><i class="el-icon-user-solid" /></div>
        <h1>工作空间邀请</h1>

        <template v-if="errorMessage">
          <el-alert :title="errorMessage" type="error" :closable="false" show-icon />
          <div class="card-actions">
            <el-button v-if="isAuthenticated && inviteCode" @click="previewInvitation">重试</el-button>
            <el-button type="primary" @click="leaveInviteFlow">返回首页</el-button>
          </div>
        </template>

        <template v-else-if="!isAuthenticated">
          <p class="lead">你收到了一条工作空间邀请。</p>
          <p class="secondary">登录后可查看工作空间和权限信息，确认无误后再加入。链接不会自动加入或消耗。</p>
          <div class="card-actions">
            <el-button type="primary" @click="goToAuth('/login')">登录后查看</el-button>
            <el-button v-if="allowUserRegister" @click="goToAuth('/register')">注册新账号</el-button>
          </div>
          <p v-if="pubConfigLoaded && !allowUserRegister" class="registration-closed">
            当前未开放自助注册，请先使用已有账号登录。
          </p>
        </template>

        <template v-else-if="previewLoading">
          <div class="loading-block">
            <i class="el-icon-loading" />
            <span>正在读取邀请信息…</span>
          </div>
        </template>

        <template v-else-if="invitation">
          <div class="invite-details">
            <div class="detail-row">
              <span>工作空间</span>
              <strong>{{ invitation.workspaceName || "未命名工作空间" }}</strong>
            </div>
            <div class="detail-row">
              <span>加入权限</span>
              <el-tag :type="invitation.role === 'ADMIN' ? 'warning' : 'info'">
                {{ roleLabel(invitation.role) }}
              </el-tag>
            </div>
            <div class="detail-row">
              <span>当前账号</span>
              <strong>{{ currentAccount }}</strong>
            </div>
            <div class="detail-row">
              <span>有效期至</span>
              <strong>{{ formatExpiration(invitation.expiresAt) }}</strong>
            </div>
          </div>

          <el-alert
            v-if="invitation.role === 'ADMIN'"
            title="管理员可编辑工作空间资源并管理成员，请确认邀请来源可信。"
            type="warning"
            :closable="false"
            show-icon
            class="role-alert"
          />
          <p class="secondary">该链接仅限 1 人使用。点击下方按钮后才会正式加入。</p>
          <div class="card-actions">
            <el-button :loading="switchingAccount" :disabled="accepting" @click="switchAccount">
              切换账号
            </el-button>
            <el-button
              type="primary"
              :loading="accepting"
              :disabled="switchingAccount"
              @click="acceptInvitation"
            >
              确认加入
            </el-button>
          </div>
        </template>
      </section>
    </main>
  </div>
</template>

<script>
import Api from "@/apis/api";
import { parseStoredProfile, readSession } from "@/security/session";
import {
  clearPendingWorkspaceInvite,
  normalizeInviteCode,
  readPendingWorkspaceInvite,
  rememberPendingWorkspaceInvite,
} from "@/utils/workspaceInvitation";

export default {
  name: "WorkspaceInvitationLanding",
  data() {
    return {
      inviteCode: "",
      invitation: null,
      previewLoading: false,
      accepting: false,
      switchingAccount: false,
      errorMessage: "",
      pubConfigLoaded: false,
      referrerMeta: null,
      previousReferrerPolicy: null,
      createdReferrerMeta: false,
    };
  },
  computed: {
    isAuthenticated() {
      return Boolean(readSession("token"));
    },
    allowUserRegister() {
      return Boolean(this.$store.state.pubConfig && this.$store.state.pubConfig.allowUserRegister);
    },
    currentAccount() {
      const profile = this.$store.state.userInfo || parseStoredProfile();
      return profile.username || profile.mobile || "当前登录账号";
    },
  },
  created() {
    this.installReferrerPolicy();
    this.captureInvitation();
  },
  mounted() {
    this.$store.dispatch("fetchPubConfig").finally(() => {
      this.pubConfigLoaded = true;
    });
  },
  beforeDestroy() {
    this.restoreReferrerPolicy();
  },
  methods: {
    captureInvitation() {
      const query = this.$route.query || {};
      const hasRouteCode = Object.prototype.hasOwnProperty.call(query, "code");
      if (hasRouteCode) {
        const code = normalizeInviteCode(query.code);
        if (!code) {
          this.stripCodeFromAddressBar();
          clearPendingWorkspaceInvite();
          this.errorMessage = "邀请链接无效，请向邀请人重新获取链接。";
          return;
        }
        this.inviteCode = rememberPendingWorkspaceInvite(code);
        this.stripCodeFromAddressBar();
      } else {
        this.inviteCode = readPendingWorkspaceInvite();
      }

      if (!this.inviteCode) {
        this.errorMessage = "邀请信息不存在或已过期，请向邀请人重新获取链接。";
        return;
      }
      if (!this.isAuthenticated && readPendingWorkspaceInvite() !== this.inviteCode) {
        this.errorMessage = "浏览器无法保存邀请信息，请允许会话存储后重新打开邀请链接。";
        return;
      }
      if (this.isAuthenticated) this.previewInvitation();
    },
    stripCodeFromAddressBar() {
      const replacement = this.$router.replace({ path: "/invite" });
      if (replacement && replacement.catch) replacement.catch(() => {});
    },
    previewInvitation() {
      if (!this.isAuthenticated || !this.inviteCode || this.previewLoading) return;
      this.previewLoading = true;
      this.errorMessage = "";
      Api.workspace.previewInvitation(
        this.inviteCode,
        ({ data }) => {
          this.previewLoading = false;
          this.invitation = data && data.data;
          if (!this.invitation) this.errorMessage = "邀请信息读取失败，请稍后重试。";
        },
        (error) => {
          this.previewLoading = false;
          this.invitation = null;
          this.errorMessage = this.invitationError(error, "邀请信息读取失败，请稍后重试。");
        }
      );
    },
    acceptInvitation() {
      if (!this.invitation || !this.inviteCode || this.accepting || this.switchingAccount) return;
      this.accepting = true;
      Api.workspace.acceptInvitation(
        this.inviteCode,
        ({ data }) => {
          this.accepting = false;
          const workspace = data && data.data;
          if (!workspace || !workspace.id) {
            this.errorMessage = "加入工作空间失败，请稍后重试。";
            return;
          }
          localStorage.setItem("currentWorkspaceId", workspace.id);
          localStorage.setItem("currentWorkspaceCanWrite", workspace.canWrite ? "true" : "false");
          clearPendingWorkspaceInvite();
          this.inviteCode = "";
          this.$message.success("已加入工作空间");
          this.$router.replace("/organization-management");
        },
        (error) => {
          this.accepting = false;
          this.errorMessage = this.invitationError(error, "加入工作空间失败，请稍后重试。");
        }
      );
    },
    goToAuth(path) {
      if (!this.inviteCode) return;
      this.$router.push({ path, query: { redirect: "/invite" } });
    },
    switchAccount() {
      if (this.accepting || this.switchingAccount) return;
      this.switchingAccount = true;
      const logout = this.$store.dispatch("logout");
      if (logout && logout.catch) {
        logout.catch(() => {
          this.switchingAccount = false;
          this.$message.error("切换账号失败，请重试");
        });
      }
    },
    leaveInviteFlow() {
      clearPendingWorkspaceInvite();
      this.inviteCode = "";
      this.$router.replace("/home");
    },
    invitationError(error, fallback) {
      const message = error && error.data && error.data.msg;
      if (!message || (this.inviteCode && String(message).includes(this.inviteCode))) return fallback;
      return message;
    },
    roleLabel(role) {
      return { ADMIN: "管理员", MEMBER: "普通成员" }[role] || role;
    },
    formatExpiration(value) {
      if (!value) return "以服务端校验为准";
      const date = new Date(value);
      return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
    },
    installReferrerPolicy() {
      if (typeof document === "undefined" || !document.head) return;
      let meta = document.querySelector('meta[name="referrer"]');
      if (meta) {
        this.previousReferrerPolicy = meta.getAttribute("content");
      } else {
        meta = document.createElement("meta");
        meta.setAttribute("name", "referrer");
        document.head.appendChild(meta);
        this.createdReferrerMeta = true;
      }
      meta.setAttribute("content", "no-referrer");
      this.referrerMeta = meta;
    },
    restoreReferrerPolicy() {
      if (!this.referrerMeta) return;
      if (this.createdReferrerMeta && this.referrerMeta.parentNode) {
        this.referrerMeta.parentNode.removeChild(this.referrerMeta);
      } else if (this.previousReferrerPolicy == null) {
        this.referrerMeta.removeAttribute("content");
      } else {
        this.referrerMeta.setAttribute("content", this.previousReferrerPolicy);
      }
    },
  },
};
</script>

<style lang="scss" scoped>
.invite-page {
  min-height: 100vh;
  background: linear-gradient(145deg, #e8efff, #f3f1ff);
  color: #30364a;
}

.invite-header {
  height: 76px;
  padding: 0 34px;
  background: rgba(255, 255, 255, 0.88);
  display: flex;
  align-items: center;
  box-sizing: border-box;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-logo {
  width: 42px;
  height: 42px;
}

.brand-name {
  height: 20px;
}

.invite-main {
  min-height: calc(100vh - 76px);
  padding: 72px 20px;
  box-sizing: border-box;
  display: flex;
  justify-content: center;
  align-items: flex-start;
}

.invite-card {
  width: 100%;
  max-width: 520px;
  padding: 38px 42px;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 18px 48px rgba(69, 87, 132, 0.14);
  box-sizing: border-box;
}

.invite-icon {
  width: 58px;
  height: 58px;
  margin: 0 auto 16px;
  border-radius: 18px;
  background: #eef2ff;
  color: #5778ff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
}

h1 {
  margin: 0 0 24px;
  font-size: 26px;
}

.lead {
  margin: 0 0 12px;
  font-size: 18px;
  font-weight: 600;
}

.secondary,
.registration-closed {
  color: #778199;
  font-size: 14px;
  line-height: 1.7;
}

.registration-closed {
  margin: 16px 0 0;
}

.loading-block {
  padding: 32px 0;
  color: #778199;
  display: flex;
  justify-content: center;
  gap: 10px;
}

.invite-details {
  margin-bottom: 18px;
  padding: 4px 18px;
  border-radius: 10px;
  background: #f7f9fd;
  text-align: left;
}

.detail-row {
  min-height: 52px;
  border-bottom: 1px solid #e8ebf2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.detail-row:last-child {
  border-bottom: 0;
}

.detail-row > span {
  color: #778199;
}

.detail-row strong {
  text-align: right;
  word-break: break-word;
}

.role-alert {
  margin-bottom: 16px;
  text-align: left;
}

.card-actions {
  margin-top: 26px;
  display: flex;
  justify-content: center;
  gap: 12px;
}

@media (max-width: 600px) {
  .invite-header {
    height: 64px;
    padding: 0 20px;
  }

  .invite-main {
    min-height: calc(100vh - 64px);
    padding: 34px 14px;
  }

  .invite-card {
    padding: 30px 20px;
  }

  .detail-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
    padding: 12px 0;
  }

  .detail-row strong {
    text-align: left;
  }
}
</style>
