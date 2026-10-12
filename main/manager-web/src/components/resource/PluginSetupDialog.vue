<template>
  <el-dialog class="plugin-setup-dialog" :title="presentation.setupLabel || '前往配置'" :visible.sync="dialogVisible"
    width="min(540px, calc(100vw - 32px))" top="6vh" append-to-body :close-on-click-modal="false">
    <p class="setup-title">{{ presentation.title || (plugin && plugin.name) }}</p>
    <ol class="setup-steps">
      <li v-for="(step, index) in presentation.setupSteps" :key="index">{{ step }}</li>
    </ol>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <div v-if="error" class="setup-retry"><el-button size="small" @click="loadAgents">重新加载</el-button></div>
    <div v-else v-loading="loading" class="setup-agents">
      <el-alert v-if="!presentation.setupTarget" title="该条目没有可直接打开的配置入口，请查看功能说明。"
        type="info" :closable="false" show-icon />
      <el-alert v-else-if="!loading && !canWrite" title="当前工作空间为只读，请联系管理员开通编辑权限。"
        type="warning" :closable="false" show-icon />
      <el-empty v-else-if="!loading && !agents.length" description="当前工作空间没有可配置的智能体，请先在智能体中心创建。" />
      <el-form v-else label-position="top">
        <el-form-item label="选择要配置的智能体">
          <el-select v-model="selectedAgentId" filterable placeholder="请选择智能体" :disabled="loading || !canWrite">
            <el-option v-for="agent in agents" :key="agent.id" :value="agent.id"
              :label="agent.agentName || agent.name || '未命名智能体'" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="setup-note">前往后由你选择并保存，不会自动启用功能。</p>
    </div>
    <span slot="footer">
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :disabled="!canNavigate" @click="goToSetup">{{ presentation.setupLabel || '前往配置' }}</el-button>
    </span>
  </el-dialog>
</template>

<script>
import Api from '@/apis/api';
import { pluginPresentation } from '@/utils/pluginPresentation';
import { pluginSetupQuery, readSetupWorkspace, setupWorkspaceId } from '@/mixins/rolePluginNavigation';

export default {
  name: 'PluginSetupDialog',
  props: {
    visible: { type: Boolean, default: false },
    plugin: { type: Object, default: null },
  },
  data() {
    return { agents: [], selectedAgentId: '', loading: false, error: '', canWrite: false, loadSequence: 0, loadedWorkspaceId: '' };
  },
  computed: {
    dialogVisible: {
      get() { return this.visible; },
      set(value) { this.$emit('update:visible', value); },
    },
    presentation() { return this.plugin ? pluginPresentation(this.plugin) : {}; },
    targetQuery() {
      return pluginSetupQuery({ agentId: this.selectedAgentId, setup: this.presentation.setupTarget,
        plugin: this.plugin?.providerCode || '' });
    },
    canNavigate() {
      return this.visible && !this.loading && !this.error && this.canWrite && Boolean(this.targetQuery)
        && this.loadedWorkspaceId === setupWorkspaceId()
        && this.agents.some(agent => agent.id === this.selectedAgentId);
    },
  },
  watch: {
    visible: {
      immediate: true,
      handler(value) {
        if (value) this.loadAgents();
        else this.loadSequence += 1;
      },
    },
    plugin() { if (this.visible) this.loadAgents(); },
  },
  beforeDestroy() { this.loadSequence += 1; },
  methods: {
    async loadAgents() {
      const sequence = ++this.loadSequence;
      const workspaceId = setupWorkspaceId();
      this.agents = [];
      this.selectedAgentId = '';
      this.error = '';
      this.canWrite = false;
      this.loadedWorkspaceId = '';
      if (!this.presentation.setupTarget) {
        this.loading = false;
        return;
      }
      this.loading = true;
      try {
        const [workspace, agents] = await Promise.all([
          readSetupWorkspace(),
          new Promise((resolve, reject) => Api.agent.getAgentList(({ data }) => {
            if (data?.code === 0 && Array.isArray(data.data)) resolve(data.data);
            else reject(new Error('无法加载智能体列表'));
          }, reject)),
        ]);
        if (sequence !== this.loadSequence || !this.visible) return;
        if (workspaceId !== setupWorkspaceId()) {
          this.error = '工作空间已切换，请重新加载智能体列表。';
          return;
        }
        this.loadedWorkspaceId = workspaceId;
        this.agents = agents.filter(agent => agent && typeof agent.id === 'string' && agent.id);
        this.canWrite = workspace.canWrite === true;
      } catch (_) {
        if (sequence !== this.loadSequence || !this.visible) return;
        this.error = workspaceId !== setupWorkspaceId()
          ? '工作空间已切换，请重新加载智能体列表。' : '智能体列表或配置权限加载失败，请重试。';
      } finally {
        if (sequence === this.loadSequence) this.loading = false;
      }
    },
    async goToSetup() {
      if (this.loadedWorkspaceId !== setupWorkspaceId()) {
        this.error = '工作空间已切换，请重新加载智能体列表。';
        return;
      }
      if (!this.canNavigate) return;
      try {
        await this.$router.push({ path: '/role-config', query: this.targetQuery });
        this.dialogVisible = false;
      } catch (error) {
        if (error?.name !== 'NavigationDuplicated') this.error = '无法打开配置页面，请重试。';
      }
    },
  },
};
</script>

<style scoped>
.plugin-setup-dialog ::v-deep .el-dialog { display: flex; flex-direction: column; max-height: 88vh; margin-bottom: 6vh; }
.plugin-setup-dialog ::v-deep .el-dialog__body { overflow-y: auto; min-height: 0; }
.plugin-setup-dialog ::v-deep .el-dialog__header,
.plugin-setup-dialog ::v-deep .el-dialog__footer { flex-shrink: 0; }
.setup-title { margin-top: 0; color: #303133; font-size: 16px; font-weight: 600; }
.setup-steps { padding-left: 22px; line-height: 1.8; }
.setup-agents { min-height: 100px; margin-top: 18px; }
.setup-agents .el-select { width: 100%; }
.setup-note { margin-bottom: 0; color: #606266; font-size: 12px; }
.setup-retry { margin-top: 12px; }
</style>
