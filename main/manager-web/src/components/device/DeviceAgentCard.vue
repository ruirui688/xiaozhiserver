<template>
  <article class="device-card" :class="{ 'device-row': list, 'is-unnamed': !device.alias }" :aria-busy="busy">
    <div class="device-identity">
      <div class="device-avatar" aria-hidden="true">{{ device.alias ? device.alias.slice(0, 1) : '待' }}</div>
      <div class="device-heading">
        <div class="device-name-line">
          <h2 :title="device.alias || '未命名设备'">{{ device.alias || '未命名设备' }}</h2>
          <el-button v-if="canWrite && device.alias" class="rename-device" type="text" icon="el-icon-edit"
            aria-label="修改设备别名" title="修改设备别名" @click="$emit('rename')" />
        </div>
        <el-button v-if="!device.alias && canWrite" class="name-prompt" type="text" @click="$emit('rename')">给设备起名 <i class="el-icon-right" /></el-button>
        <span v-else-if="!device.alias" class="unnamed-hint">等待管理员命名</span>
      </div>
    </div>
    <div class="agent-panel">
      <div v-if="!editingAgent" class="agent-summary">
        <div class="agent-caption"><span v-if="!list" class="agent-caption-label">使用的智能体</span><strong>{{ currentAgentName || '未配置' }}</strong></div>
        <el-button v-if="canWrite" class="switch-link" type="text" :disabled="busy" :loading="busy" @click="beginAgentEdit">切换</el-button>
      </div>
      <div v-else class="agent-editor">
        <p class="switch-context">当前：{{ currentAgentName || '未配置' }}<span>切换到</span></p>
        <div class="switch-controls">
          <el-select v-model="targetId" filterable size="small" placeholder="选择新的智能体" :disabled="busy || !canWrite" aria-label="切换到新的智能体">
            <el-option v-for="agent in agents" :key="agent.id" :label="agent.agentName" :value="agent.id" :disabled="agent.id === currentAgentId" />
          </el-select>
          <el-button type="primary" size="small" :loading="busy" :disabled="!canWrite || !targetId || targetId === currentAgentId" @click="switchAgent">确认切换</el-button>
          <el-button type="text" :disabled="busy" @click="cancelAgentEdit">取消</el-button>
        </div>
      </div>
      <el-button v-if="failed && !busy" type="text" size="small" @click="refreshStatus">刷新状态</el-button>
      <p v-if="status && (failed || busy || editingAgent)" class="switch-status" role="status" :class="{ failure: failed }">{{ status }}</p>
    </div>
    <div class="device-meta"><span>最近连接</span><span>{{ lastConnection }}</span></div>
    <div class="device-actions">
      <el-button type="text" :disabled="busy" @click="$emit('settings')">设置</el-button>
    </div>
  </article>
</template>
<script>
import Api from '@/apis/api';
import { deviceRequest, switchDeviceAgent, sceneStatusText } from '@/utils/deviceCenter';
export default {
  props: { list: Boolean, device: { type: Object, required: true }, agents: { type: Array, default: () => [] }, canWrite: Boolean },
  data() { return { editingAgent: false, targetId: this.device.agentId, currentAgentId: this.device.agentId,
    currentAgentName: this.device.agentName, busy: false, status: '', failed: false, generation: 0, timer: null }; },
  computed: {
    lastConnection() {
      const date = new Date(Number(this.device.lastConnectedAtTimestamp));
      return this.device.lastConnectedAtTimestamp && !isNaN(date.getTime()) ? date.toLocaleString() : '暂无记录';
    }
  },
  watch: {
    'device.agentId'(id) { this.currentAgentId = id; if (!this.busy) this.targetId = id; },
    'device.agentName'(name) { this.currentAgentName = name; }
  },
  mounted() { this.refreshStatus(); },
  beforeDestroy() { this.generation++; clearTimeout(this.timer); this.$emit('busy', false); },
  methods: {
    beginAgentEdit() {
      if (this.busy || !this.canWrite) return;
      this.targetId = ''; this.editingAgent = true;
    },
    cancelAgentEdit() {
      if (this.busy) return;
      this.targetId = this.currentAgentId; this.editingAgent = false;
    },
    setBusy(value) { this.busy = value; this.$emit('busy', value); },
    applyScenes(scenes) {
      const current = scenes.find(scene => scene.current);
      if (current) {
        this.currentAgentId = current.agentId; this.currentAgentName = current.agentName;
        this.$emit('updated', current);
      }
      const latest = scenes.find(scene => scene.switchStatus);
      if (latest) {
        this.status = sceneStatusText(latest.switchStatus);
        this.failed = ['FAILED', 'ROLLED_BACK'].includes(latest.switchStatus);
      }
      return latest;
    },
    async refreshStatus() {
      if (this.busy) return;
      const generation = ++this.generation;
      try {
        const scenes = await deviceRequest(done => Api.device.getDeviceScenes(this.device.id, done));
        if (generation !== this.generation) return;
        const latest = this.applyScenes(scenes || []);
        if (latest && ['REQUESTED', 'SCHEDULED'].includes(latest.switchStatus)) {
          this.setBusy(true); this.poll(generation, 0);
        }
      } catch (error) { if (generation === this.generation) { this.status = error.message; this.failed = true; } }
    },
    async switchAgent() {
      if (this.busy || !this.canWrite || this.targetId === this.currentAgentId) return;
      const agent = this.agents.find(item => item.id === this.targetId);
      if (!agent) return;
      const generation = ++this.generation;
      clearTimeout(this.timer); this.setBusy(true); this.failed = false;
      try {
        const result = await switchDeviceAgent(this.device.id, agent);
        if (generation !== this.generation) return;
        this.status = sceneStatusText(result.switchStatus || 'REQUESTED');
        // Re-read the authoritative scene and audit, including synchronous rollback.
        await this.poll(generation, 0);
      } catch (error) {
        if (generation !== this.generation) return;
        this.status = error.message;
        this.failed = true; this.setBusy(false);
      }
    },
    async poll(generation, attempt) {
      try {
        const scenes = await deviceRequest(done => Api.device.getDeviceScenes(this.device.id, done));
        if (generation !== this.generation) return;
        const latest = this.applyScenes(scenes || []);
        if (latest && ['REQUESTED', 'SCHEDULED'].includes(latest.switchStatus) && attempt < 40) {
          this.timer = setTimeout(() => this.poll(generation, attempt + 1), 3000);
          return;
        }
        if (latest && ['REQUESTED', 'SCHEDULED'].includes(latest.switchStatus)) this.status += '，请稍后刷新确认';
        this.targetId = this.currentAgentId; this.setBusy(false);
        if (latest && latest.switchStatus === 'APPLIED') this.editingAgent = false;
      } catch (error) {
        if (generation !== this.generation) return;
        this.status = `读取切换结果失败：${error.message}；请刷新确认`; this.failed = true; this.setBusy(false);
      }
    }
  }
};
</script>
<style scoped>
.device-card { position: relative; display: grid; grid-template-columns: 1fr auto; gap: 24px; min-width: 0; padding: 28px; border: 1px solid #e9eaf0; border-radius: 20px; background: #fff; text-align: left; transition: border-color .18s ease, box-shadow .18s ease; }
.device-card:hover { border-color: #d5d9e6; box-shadow: 0 8px 28px rgba(36, 45, 70, .04); }
.device-identity { display: flex; align-items: center; gap: 16px; min-width: 0; grid-column: 1 / -1; }
.device-avatar { display: flex; align-items: center; justify-content: center; width: 52px; height: 52px; flex-shrink: 0; background: #eeedf9; border: 1px solid #e5e3f3; border-radius: 16px; color: #7260aa; font-size: 22px; font-weight: 500; }
.is-unnamed .device-avatar { background: #f5f3ef; border-color: #eeebe4; color: #a59a87; font-size: 16px; }
.device-heading { min-width: 0; }.device-name-line { display: flex; align-items: center; gap: 10px; }
h2 { margin: 0; min-width: 0; color: #282c3b; font-size: 21px; line-height: 1.5; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.is-unnamed h2 { color: #737782; font-weight: 400; }
.rename-device { padding: 5px; color: #8d94a6; flex-shrink: 0; }.name-prompt { padding: 4px 0 0; color: #7765af; font-size: 12px; }.unnamed-hint { color: #999; font-size: 12px; }
.agent-panel { grid-column: 1 / -1; min-width: 0; }.agent-summary { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 18px 20px; background: #f8f8fb; border-radius: 12px; }
.agent-caption { min-width: 0; display: flex; flex-direction: column; gap: 8px; }.agent-caption-label { font-size: 12px; color: #8c91a0; }.agent-caption strong { font-size: 16px; font-weight: 500; color: #464b60; overflow-wrap: anywhere; }
.switch-link { color: #7765af; font-size: 13px; }.switch-controls { display: flex; align-items: center; gap: 10px; }.switch-controls .el-select { min-width: 0; flex: 1; }.switch-controls .el-button { margin: 0; }
.switch-context { margin: 0 0 10px; color: #858b9b; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.switch-context span { display: block; margin-top: 8px; color: #51596d; }
.switch-controls { flex-wrap: wrap; }.switch-controls .el-select { flex-basis: 140px; }
.switch-status { margin: 10px 0 0; color: #8990a3; font-size: 12px; line-height: 1.6; }.failure { color: #bd5656; }
.device-meta { display: flex; flex-direction: column; gap: 6px; color: #9096a5; font-size: 12px; justify-content: center; }
.device-actions { display: flex; align-items: center; gap: 18px; }.device-actions .el-button { padding: 8px 0; color: #8a90a0; font-size: 13px; }.device-actions .el-button:hover { color: #7765af; }
.hardware-details h3 { margin: 0 0 16px; font-size: 15px; overflow-wrap: anywhere; }.hardware-details dt { color: #9198a7; font-size: 12px; margin-top: 12px; }.hardware-details dd { margin: 6px 0 0; color: #51596d; word-break: break-all; }
.device-row { grid-template-columns: minmax(230px, 1.3fr) minmax(260px, 1fr) 160px 90px; gap: 24px; align-items: center; border: 0; border-bottom: 1px solid #f0f1f5; border-radius: 0; padding: 22px 28px; }
.device-row:last-child { border-bottom: 0; }.device-row:hover { background: #fcfcfe; box-shadow: none; }
.device-row .device-identity { grid-column: 1; }.device-row h2 { font-size: 17px; }.device-row .device-avatar { width: 42px; height: 42px; border-radius: 13px; font-size: 18px; }
.device-row .agent-panel { grid-column: 2; }.device-row .agent-summary { padding: 0; background: transparent; }.device-row .agent-caption-label { display: none; }.device-row .agent-caption strong { font-size: 14px; }
.device-row .device-meta { grid-column: 3; }.device-row .device-meta>span:first-child { display: none; }.device-row .device-actions { grid-column: 4; }
@media (max-width: 600px) { .device-card:not(.device-row) { padding: 22px; } }
</style>
