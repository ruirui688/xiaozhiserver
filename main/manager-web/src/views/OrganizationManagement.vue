<template>
  <div class="organization-page">
    <HeaderBar />
    <el-main class="organization-main">
      <div class="page-toolbar">
        <h2>组织管理</h2>
        <div class="toolbar-actions">
          <el-input v-model="inviteCode" size="small" placeholder="输入邀请码加入工作空间" />
          <el-button size="small" @click="acceptInvitation">加入</el-button>
        </div>
      </div>

      <div class="organization-layout">
        <aside class="workspace-list">
          <div
            v-for="workspace in workspaceList"
            :key="workspace.id"
            class="workspace-item"
            :class="{ active: workspace.id === currentWorkspaceId }"
            @click="selectWorkspace(workspace.id)"
          >
            <div class="workspace-name">{{ workspace.name }}</div>
            <el-tag size="mini" :type="roleTagType(workspace.currentUserRole)">
              {{ roleLabel(workspace.currentUserRole) }}
            </el-tag>
          </div>
        </aside>

        <section class="member-panel">
          <div class="member-header">
            <div>
              <h3>{{ currentWorkspace ? currentWorkspace.name : '工作空间' }}</h3>
              <span class="member-hint">
                名额 {{ occupiedSlots }}/5（正式成员 {{ members.length }}，待接受 {{ pendingInvitations.length }}），共享智能体、设备、记忆、聊天记录、知识库和声纹等业务资源
              </span>
            </div>
            <div class="member-actions">
              <el-button v-if="canLeaveWorkspace" size="small" @click="leaveWorkspace">退出工作空间</el-button>
              <el-button
                v-if="canWrite"
                size="small"
                type="primary"
                :disabled="occupiedSlots >= maxMembers"
                @click="showInviteDialog = true"
              >
                邀请成员
              </el-button>
            </div>
          </div>

          <el-table :data="members" v-loading="loading" class="member-table">
            <el-table-column prop="username" label="成员" />
            <el-table-column label="角色" width="160">
              <template slot-scope="scope">
                <el-select
                  v-if="canChangeRole(scope.row)"
                  v-model="scope.row.role"
                  size="mini"
                  @change="updateRole(scope.row)"
                >
                  <el-option v-if="isOwner" label="所有者" value="OWNER" />
                  <el-option label="管理员" value="ADMIN" />
                  <el-option label="普通成员" value="MEMBER" />
                </el-select>
                <el-tag v-else size="mini" :type="roleTagType(scope.row.role)">
                  {{ roleLabel(scope.row.role) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="joinedAt" label="加入时间" width="220" />
            <el-table-column v-if="canWrite" label="操作" width="120">
              <template slot-scope="scope">
                <el-button
                  v-if="canRemoveMember(scope.row)"
                  type="text"
                  size="mini"
                  @click="removeMember(scope.row)"
                >
                  移除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <div v-if="pendingInvitations.length" class="invitation-panel">
            <h4>待接受邀请</h4>
            <el-table :data="pendingInvitations" size="mini">
              <el-table-column prop="inviteeUsername" label="用户" />
              <el-table-column label="角色" width="120">
                <template slot-scope="scope">{{ roleLabel(scope.row.role) }}</template>
              </el-table-column>
              <el-table-column prop="expiresAt" label="有效期至" width="220" />
              <el-table-column v-if="canWrite" label="操作" width="90">
                <template slot-scope="scope">
                  <el-button type="text" size="mini" @click="revokeInvitation(scope.row)">撤销</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </section>
      </div>
    </el-main>

    <el-dialog title="邀请成员" :visible.sync="showInviteDialog" width="460px">
      <el-form label-width="80px">
        <el-form-item label="用户名">
          <el-input v-model="inviteForm.username" placeholder="请输入已注册用户名" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="inviteForm.role">
            <el-option label="管理员" value="ADMIN" />
            <el-option label="普通成员" value="MEMBER" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="lastInviteCode" class="invite-code">
        邀请码：{{ lastInviteCode }}
      </div>
      <span slot="footer">
        <el-button @click="showInviteDialog = false">关闭</el-button>
        <el-button type="primary" @click="inviteMember">生成邀请</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import Api from "@/apis/api";
import HeaderBar from "@/components/HeaderBar.vue";

export default {
  name: "OrganizationManagement",
  components: { HeaderBar },
  data() {
    return {
      workspaceList: [],
      currentWorkspaceId: localStorage.getItem("currentWorkspaceId") || "",
      members: [],
      pendingInvitations: [],
      maxMembers: 5,
      loading: false,
      showInviteDialog: false,
      inviteCode: "",
      inviteForm: {
        username: "",
        role: "MEMBER",
      },
      lastInviteCode: "",
    };
  },
  computed: {
    currentWorkspace() {
      return this.workspaceList.find(item => item.id === this.currentWorkspaceId);
    },
    canWrite() {
      return this.currentWorkspace && this.currentWorkspace.canWrite;
    },
    isOwner() {
      return this.currentWorkspace && this.currentWorkspace.currentUserRole === "OWNER";
    },
    canLeaveWorkspace() {
      return this.currentWorkspace && this.currentWorkspace.currentUserRole !== "OWNER";
    },
    currentUserId() {
      const userInfo = this.$store.state.userInfo || {};
      return userInfo.id || userInfo.userid || userInfo.userId;
    },
    occupiedSlots() {
      return this.members.length + this.pendingInvitations.length;
    },
  },
  mounted() {
    this.loadWorkspaces();
  },
  methods: {
    loadWorkspaces() {
      Api.workspace.list(({ data }) => {
        if (data.code !== 0) return;
        this.workspaceList = data.data || [];
        if (!this.currentWorkspaceId && this.workspaceList.length > 0) {
          this.selectWorkspace(this.workspaceList[0].id);
          return;
        }
        if (this.currentWorkspaceId) {
          this.loadMembers();
        }
      });
    },
    selectWorkspace(id) {
      this.currentWorkspaceId = id;
      localStorage.setItem("currentWorkspaceId", id);
      this.loadMembers();
    },
    loadMembers() {
      if (!this.currentWorkspaceId) return;
      this.loading = true;
      Api.workspace.members(this.currentWorkspaceId, ({ data }) => {
        this.loading = false;
        if (data.code === 0) {
          this.members = data.data || [];
        }
      });
      if (!this.canWrite) {
        this.pendingInvitations = [];
        return;
      }
      Api.workspace.invitations(this.currentWorkspaceId, ({ data }) => {
        if (data.code === 0) {
          this.pendingInvitations = data.data || [];
        }
      });
    },
    inviteMember() {
      if (!this.inviteForm.username.trim()) {
        this.$message.warning("请输入用户名");
        return;
      }
      if (this.occupiedSlots >= this.maxMembers) {
        this.$message.warning("工作空间名额已满，请先撤销待接受邀请或移除成员");
        return;
      }
      Api.workspace.invite(this.currentWorkspaceId, this.inviteForm, ({ data }) => {
        if (data.code === 0) {
          this.lastInviteCode = data.data.code;
          this.$message.success("邀请已生成");
          this.loadMembers();
        }
      });
    },
    revokeInvitation(invitation) {
      this.$confirm(`确定撤销对 ${invitation.inviteeUsername} 的邀请吗？`, "提示", { type: "warning" }).then(() => {
        Api.workspace.revokeInvitation(this.currentWorkspaceId, invitation.id, ({ data }) => {
          if (data.code === 0) {
            this.$message.success("邀请已撤销");
            this.loadMembers();
          }
        });
      }).catch(() => {});
    },
    acceptInvitation() {
      if (!this.inviteCode.trim()) {
        this.$message.warning("请输入邀请码");
        return;
      }
      Api.workspace.accept(this.inviteCode.trim(), ({ data }) => {
        if (data.code === 0) {
          this.inviteCode = "";
          this.selectWorkspace(data.data.id);
          this.loadWorkspaces();
        }
      });
    },
    updateRole(member) {
      Api.workspace.updateRole(this.currentWorkspaceId, member.userId, member.role, ({ data }) => {
        if (data.code === 0) {
          this.$message.success("角色已更新");
          this.loadMembers();
        }
      });
    },
    leaveWorkspace() {
      if (!this.currentWorkspaceId) return;
      this.$confirm("确定退出当前工作空间吗？", "提示", { type: "warning" }).then(() => {
        Api.workspace.leave(this.currentWorkspaceId, ({ data }) => {
          if (data.code === 0) {
            this.$message.success("已退出工作空间");
            localStorage.removeItem("currentWorkspaceId");
            this.currentWorkspaceId = "";
            this.loadWorkspaces();
          }
        });
      }).catch(() => {});
    },
    removeMember(member) {
      this.$confirm(`确定移除 ${member.username} 吗？`, "提示", { type: "warning" }).then(() => {
        Api.workspace.removeMember(this.currentWorkspaceId, member.userId, ({ data }) => {
          if (data.code === 0) {
            this.$message.success("成员已移除");
            this.loadMembers();
          }
        });
      }).catch(() => {});
    },
    canChangeRole(member) {
      return this.isOwner && !this.isSelf(member);
    },
    canRemoveMember(member) {
      if (!this.canWrite) return false;
      if (this.isSelf(member)) return false;
      if (this.isOwner) return true;
      return member.role !== "OWNER";
    },
    isSelf(member) {
      return String(member.userId) === String(this.currentUserId);
    },
    roleLabel(role) {
      return { OWNER: "所有者", ADMIN: "管理员", MEMBER: "普通成员" }[role] || role;
    },
    roleTagType(role) {
      return { OWNER: "danger", ADMIN: "warning", MEMBER: "info" }[role] || "info";
    },
  },
};
</script>

<style scoped>
.organization-page {
  min-width: 900px;
  min-height: 100vh;
  background: linear-gradient(145deg, #e6eeff, #eff0ff);
}

.organization-main {
  padding: 24px;
}

.page-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}

.page-toolbar h2 {
  margin: 0;
  color: #30364a;
}

.toolbar-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.toolbar-actions .el-input {
  width: 240px;
}

.organization-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 18px;
}

.workspace-list,
.member-panel {
  background: #fff;
  border-radius: 12px;
  padding: 16px;
  box-shadow: 0 10px 30px rgba(79, 102, 150, 0.08);
}

.workspace-item {
  padding: 12px;
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.workspace-item.active {
  background: #eef3ff;
}

.workspace-name {
  font-weight: 600;
  color: #30364a;
}

.member-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.member-actions {
  display: flex;
  gap: 8px;
}

.member-header h3 {
  margin: 0 0 6px;
}

.member-hint {
  color: #778199;
  font-size: 12px;
}

.invite-code {
  background: #f5f7fb;
  border-radius: 6px;
  padding: 10px;
  color: #30364a;
}

.invitation-panel {
  margin-top: 20px;
}

.invitation-panel h4 {
  margin: 0 0 10px;
  color: #30364a;
}
</style>
