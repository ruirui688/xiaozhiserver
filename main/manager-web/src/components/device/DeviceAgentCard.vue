<template>
  <article class="device-card" :class="{ 'device-row': list }" :aria-busy="busy">
    <div class="device-identity"><div class="device-icon" aria-hidden="true"><i class="el-icon-monitor" /></div>
      <div class="device-heading"><div class="device-name-line"><h2 :title="device.alias || device.board">{{ device.alias || device.board || '未命名设备' }}</h2><el-button v-if="canWrite" class="rename-device" type="text" icon="el-icon-edit" aria-label="修改设备别名" title="修改设备别名" @click="$emit('rename')" /></div><span>MAC <MacAddressMask :mac-address="device.macAddress" /></span></div>
    </div>
    <div class="device-meta"><span>最近连接</span><span>{{ lastConnection }}</span></div>
    <div class="agent-panel">
    <div class="agent-label"><span>当前智能体</span><strong>{{ currentAgentName || '未配置' }}</strong></div>
    <div class="switch-controls">
      <el-select v-model="targetId" filterable :size="list ? 'small' : 'medium'" placeholder="选择要切换的智能体" :disabled="busy || !canWrite">
        <el-option v-for="agent in agents" :key="agent.id" :label="agent.agentName" :value="agent.id" />
      </el-select>
      <el-button type="primary" :size="list ? 'small' : 'medium'" :loading="busy" :disabled="!canWrite || !targetId || targetId === currentAgentId" @click="switchAgent">{{ busy ? '切换中' : targetId === currentAgentId ? '使用中' : '切换' }}</el-button>
    </div>
    </div>
    <p v-if="status" role="status" :class="{ failure: failed }">{{ status }}</p>
    <div v-if="!list" class="actions">
      <el-tooltip content="刷新切换状态" placement="top"><el-button class="status-refresh" type="text" icon="el-icon-refresh" aria-label="刷新切换状态" :disabled="busy" @click="refreshStatus" /></el-tooltip>
      <el-button type="text" :disabled="busy || !canWrite" @click="$emit('scenes')">场景管理</el-button>
      <el-button type="text" :disabled="busy" @click="$router.push({ path: '/device-management', query: { agentId: currentAgentId } })">设备设置</el-button>
    </div>
    <el-dropdown v-if="list" class="row-actions" trigger="click" :disabled="busy">
      <el-button type="text" :disabled="busy" aria-label="更多设备操作">更多<i class="el-icon-arrow-down el-icon--right" /></el-button>
      <el-dropdown-menu slot="dropdown">
        <el-dropdown-item @click.native="refreshStatus">刷新切换状态</el-dropdown-item>
        <el-dropdown-item :disabled="!canWrite" @click.native="canWrite && $emit('scenes')">场景管理</el-dropdown-item>
        <el-dropdown-item @click.native="$router.push({ path: '/device-management', query: { agentId: currentAgentId } })">设备设置</el-dropdown-item>
      </el-dropdown-menu>
    </el-dropdown>
  </article>
</template>
<script>
import Api from '@/apis/api';
import MacAddressMask from '@/components/MacAddressMask.vue';
import { deviceRequest, switchDeviceAgent, sceneStatusText } from '@/utils/deviceCenter';
export default {
  components: { MacAddressMask },
  props: { list: Boolean, device: { type: Object, required: true }, agents: { type: Array, default: () => [] }, canWrite: Boolean },
  data() { return { targetId: this.device.agentId, currentAgentId: this.device.agentId,
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
        this.status = `${error.message}；请刷新确认。新关联的场景可在场景管理中查看。`;
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
      } catch (error) {
        if (generation !== this.generation) return;
        this.status = '读取切换结果失败，请刷新确认'; this.failed = true; this.setBusy(false);
      }
    }
  }
};
</script>
<style scoped>
.device-card { background: #fff; border: 1px solid #e7eaf2; border-radius: 24px; padding: 32px; min-width: 0; text-align: left; transition: border-color .15s ease, box-shadow .15s ease; }
.device-card:hover { border-color: #d7ddec; box-shadow: 0 4px 18px rgba(35, 45, 75, .04); }
.device-identity { display: flex; align-items: center; gap: 18px; margin-bottom: 28px; }
.device-icon { display: flex; align-items: center; justify-content: center; width: 60px; height: 60px; flex-shrink: 0; border-radius: 18px; background: #f0f3fc; color: #677bc5; font-size: 28px; }
.device-heading { min-width: 0; }h2 { font-size: 19px; font-weight: 600; margin: 0 0 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.device-heading>span { color: #858da0; font-size: 13px; }
.device-meta { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; color: #858da0; font-size: 13px; }
.agent-panel { margin-top: 28px; padding: 22px; border-radius: 16px; background: #f7f8fc; }
.agent-label { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin: 0 0 18px; font-size: 13px; color: #858da0; }
.agent-label strong { font-size: 17px; font-weight: 500; color: #39435c; overflow-wrap: anywhere; }
.switch-controls { display: flex; gap: 12px; }.switch-controls .el-select { min-width: 0; flex: 1; }.switch-controls .el-button { min-width: 86px; }
p { font-size: 12px; color: #677191; line-height: 1.6; margin: 12px 0 0; }.failure { color: #c65252; }
.actions { display: flex; align-items: center; gap: 16px; margin-top: 24px; padding-top: 18px; border-top: 1px solid #f0f2f7; }
.actions .el-button { margin: 0; font-size: 13px; color: #73809a; }.actions .el-button:hover { color: #586ff0; }.actions .el-button.is-disabled { color: #c0c4cc; }
.actions .status-refresh { margin-right: auto; font-size: 16px; padding: 6px; }
@media (max-width: 600px) { .device-card { padding: 24px; }.agent-panel { padding: 16px; }h2 { font-size: 17px; } }
.device-row { display: grid; grid-template-columns: minmax(220px, 1.2fr) minmax(280px, 1fr) 170px 70px; gap: 0 24px; align-items: center; border: 0; border-bottom: 1px solid #edf0f5; border-radius: 0; padding: 20px 24px; }
.device-row:last-child { border-bottom: 0; }.device-row:hover { background: #fafbfe; box-shadow: none; }
.device-row .device-identity { grid-column: 1; grid-row: 1; margin: 0; gap: 12px; }
.device-row .device-icon { width: 38px; height: 38px; border-radius: 10px; font-size: 19px; }
.device-row h2 { font-size: 14px; margin-bottom: 7px; }
.device-row .device-heading>span { font-size: 12px; }
.device-row .agent-panel { grid-column: 2; grid-row: 1; margin: 0; padding: 0; background: transparent; }
.device-row .agent-label { justify-content: flex-start; gap: 8px; margin: 0 0 7px; font-size: 11px; }.device-row .agent-label strong { font-size: 12px; }
.device-row .device-meta { grid-column: 3; grid-row: 1; font-size: 12px; }.device-row .device-meta>span:first-child { display: none; }
.device-row .switch-controls { gap: 8px; }.device-row .switch-controls .el-button { min-width: 70px; }
.device-row .row-actions { grid-column: 4; grid-row: 1; }.row-actions .el-button { color: #68758f; }
.device-row p { grid-column: 2 / -1; margin: 8px 0 0; }
.device-name-line { display: flex; align-items: baseline; gap: 8px; }.device-name-line h2 { min-width: 0; }.rename-device { flex-shrink: 0; padding: 0; color: #8995ad; }.rename-device:hover { color: #586ff0; }
</style>
