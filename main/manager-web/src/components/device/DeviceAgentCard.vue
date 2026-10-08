<template>
  <article class="device-card" :aria-busy="busy">
    <div class="device-identity"><img src="@/assets/device-avatars/xiaozhi-logo1.png" alt="" />
      <div><h2>{{ device.alias || device.macAddress || device.id }}</h2><span>{{ device.board || '未知型号' }}</span></div>
    </div>
    <div class="device-meta">MAC：<MacAddressMask :mac-address="device.macAddress" /></div>
    <div class="device-meta">最近连接：{{ lastConnection }}</div>
    <div class="agent-label">当前配置：{{ currentAgentName }}</div>
    <div class="switch-controls">
      <el-select v-model="targetId" filterable placeholder="选择智能体" :disabled="busy || !canWrite">
        <el-option v-for="agent in agents" :key="agent.id" :label="agent.agentName" :value="agent.id" />
      </el-select>
      <el-button type="primary" :loading="busy" :disabled="!canWrite || !targetId || targetId === currentAgentId" @click="switchAgent">切换</el-button>
    </div>
    <p v-if="status" role="status" :class="{ failure: failed }">{{ status }}</p>
    <div class="actions">
      <el-button type="text" :disabled="busy" @click="refreshStatus">刷新切换状态</el-button>
      <el-button type="text" :disabled="busy || !canWrite" @click="$emit('scenes')">场景管理</el-button>
      <el-button type="text" :disabled="busy" @click="$router.push({ path: '/device-management', query: { agentId: currentAgentId } })">设备设置</el-button>
    </div>
  </article>
</template>
<script>
import Api from '@/apis/api';
import MacAddressMask from '@/components/MacAddressMask.vue';
import { deviceRequest, switchDeviceAgent, sceneStatusText } from '@/utils/deviceCenter';
export default {
  components: { MacAddressMask },
  props: { device: { type: Object, required: true }, agents: { type: Array, default: () => [] }, canWrite: Boolean },
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
.device-card{background:#fff;border:1px solid #ebedf6;border-radius:20px;padding:24px;box-shadow:0 8px 28px #38447708;min-width:0}
.device-identity{display:flex;align-items:center;gap:14px;margin-bottom:24px}.device-identity img{width:64px;height:64px;object-fit:contain;border-radius:16px;background:#f4f5ff}
h2{font-size:18px;margin:0 0 6px;overflow-wrap:anywhere}.device-identity span,.device-meta{color:#818799;font-size:13px}.device-meta{margin:10px 0;overflow-wrap:anywhere}
.agent-label{margin:20px 0 10px;font-weight:600;color:#5962a5}.switch-controls{display:flex;gap:8px}.switch-controls .el-select{min-width:0;flex:1}
p{font-size:13px;color:#677191;line-height:1.6}.failure{color:#c65252}.actions{display:flex;flex-wrap:wrap;gap:10px;margin-top:14px}.actions .el-button{margin:0}
</style>
