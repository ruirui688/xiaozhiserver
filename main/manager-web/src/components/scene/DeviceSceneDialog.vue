<template>
  <el-dialog title="设备场景" :visible.sync="visible" width="760px" :close-on-click-modal="false">
    <div class="scene-toolbar">
      <span class="device-label">设备：{{ deviceLabel }}</span>
      <el-button type="primary" size="small" icon="el-icon-plus" @click="openCreate">新增场景</el-button>
    </div>
    <div v-loading="loading" class="scene-list">
      <el-empty v-if="!loading && scenes.length === 0" description="暂无场景" />
      <el-card v-for="scene in scenes" :key="scene.id" class="scene-card" shadow="never">
        <div class="scene-main">
          <div>
            <div class="scene-title">
              {{ scene.sceneName }}
              <el-tag v-if="scene.current" size="mini" type="success">当前</el-tag>
              <el-tag v-if="scene.enabled === 0" size="mini" type="info">已禁用</el-tag>
            </div>
            <div class="scene-meta">{{ scene.agentName || scene.agentId }}</div>
            <div v-if="scene.switchAliases && scene.switchAliases.length" class="scene-meta">
              别名：{{ scene.switchAliases.join('、') }}
            </div>
            <div v-if="scene.switchStatus" class="scene-meta">
              切换状态：{{ statusText(scene.switchStatus) }}
              <span v-if="scene.failureCode">（{{ scene.failureCode }}）</span>
            </div>
          </div>
          <div class="scene-actions">
            <el-button size="mini" type="primary" :disabled="scene.current || scene.enabled === 0"
              @click="switchScene(scene)">切换</el-button>
            <el-button size="mini" @click="openEdit(scene)">编辑</el-button>
            <el-button size="mini" type="danger" :disabled="scene.current" @click="removeScene(scene)">删除</el-button>
          </div>
        </div>
      </el-card>
    </div>

    <el-dialog :title="editingId ? '编辑场景' : '新增场景'" :visible.sync="editorVisible"
      width="520px" append-to-body>
      <el-form ref="sceneForm" :model="form" :rules="rules" label-width="90px">
        <el-form-item v-if="!editingId" label="智能体" prop="agentId">
          <el-select v-model="form.agentId" filterable style="width:100%" placeholder="选择同工作空间智能体">
            <el-option v-for="agent in agents" :key="agent.id" :label="agent.agentName" :value="agent.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="场景名称" prop="sceneName">
          <el-input v-model.trim="form.sceneName" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="切换别名">
          <el-select v-model="form.switchAliases" multiple filterable allow-create default-first-option
            style="width:100%" placeholder="最多 10 个，如老师模式">
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" :max="9999" />
        </el-form-item>
        <el-form-item label="允许切换">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="editorVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveScene">保存</el-button>
      </span>
    </el-dialog>
  </el-dialog>
</template>

<script>
import Api from '@/apis/api';

export default {
  name: 'DeviceSceneDialog',
  data() {
    return {
      visible: false, editorVisible: false, loading: false, saving: false,
      device: null, scenes: [], agents: [], editingId: '',
      form: { agentId: '', sceneName: '', switchAliases: [], enabled: 1, sort: 0 },
      rules: {
        agentId: [{ required: true, message: '请选择智能体', trigger: 'change' }],
        sceneName: [{ required: true, message: '请输入场景名称', trigger: 'blur' }]
      }
    };
  },
  computed: {
    deviceLabel() { return (this.device && (this.device.remark || this.device.macAddress)) || '-'; }
  },
  methods: {
    open(device) {
      this.device = device;
      this.visible = true;
      this.loadAgents();
      this.loadScenes();
    },
    loadAgents() {
      Api.agent.getAgentList(({ data }) => { if (data && data.code === 0) this.agents = data.data || []; });
    },
    loadScenes() {
      this.loading = true;
      Api.device.getDeviceScenes(this.device.device_id, ({ data }) => {
        this.loading = false;
        if (data.code === 0) this.scenes = data.data || [];
        else this.$message.error(data.msg || '获取设备场景失败');
      });
    },
    openCreate() {
      this.editingId = '';
      this.form = { agentId: '', sceneName: '', switchAliases: [], enabled: 1, sort: this.scenes.length };
      this.editorVisible = true;
    },
    openEdit(scene) {
      this.editingId = scene.id;
      this.form = { agentId: scene.agentId, sceneName: scene.sceneName,
        switchAliases: [...(scene.switchAliases || [])], enabled: scene.enabled, sort: scene.sort || 0 };
      this.editorVisible = true;
    },
    saveScene() {
      this.$refs.sceneForm.validate(valid => {
        if (!valid || this.form.switchAliases.length > 10) return;
        this.saving = true;
        const done = ({ data }) => {
          this.saving = false;
          if (data.code !== 0) return this.$message.error(data.msg || '保存场景失败');
          this.editorVisible = false; this.loadScenes();
        };
        if (this.editingId) Api.device.updateDeviceScene(this.device.device_id, this.editingId, this.form, done);
        else Api.device.createDeviceScene(this.device.device_id, this.form, done);
      });
    },
    switchScene(scene) {
      Api.device.switchDeviceScene(this.device.device_id, scene.id, ({ data }) => {
        if (data.code !== 0) return this.$message.error(data.msg || '切换场景失败');
        this.$message.success('场景切换已提交'); this.loadScenes(); this.$emit('switched');
      });
    },
    removeScene(scene) {
      this.$confirm(`确认删除场景“${scene.sceneName}”？`, '提示', { type: 'warning' }).then(() => {
        Api.device.deleteDeviceScene(this.device.device_id, scene.id, ({ data }) => {
          if (data.code !== 0) return this.$message.error(data.msg || '删除场景失败');
          this.loadScenes();
        });
      }).catch(() => {});
    },
    statusText(status) {
      return ({ REQUESTED: '已请求', SCHEDULED: '等待轮次结束', APPLIED: '已生效',
        FAILED: '切换失败', ROLLED_BACK: '已回滚' })[status] || status;
    }
  }
};
</script>

<style scoped>
.scene-toolbar,.scene-main{display:flex;justify-content:space-between;align-items:center}.scene-toolbar{margin-bottom:14px}
.device-label{color:#606266}.scene-card{margin-bottom:10px}.scene-title{font-size:16px;font-weight:600;margin-bottom:6px}
.scene-meta{font-size:13px;color:#909399;line-height:22px}.scene-actions{white-space:nowrap}.scene-list{min-height:160px;max-height:480px;overflow:auto}
</style>
