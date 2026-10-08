<template>
  <div class="device-center">
    <HeaderBar />
    <main>
      <div class="title-row"><div><h1>设备管理</h1><p>管理当前工作空间的设备，直接切换设备使用的智能体。</p></div>
        <el-button icon="el-icon-refresh" :loading="loading" :disabled="switching" @click="load">刷新</el-button>
      </div>
      <div class="toolbar">
        <span>共 {{ devices.length }} 台设备</span>
        <el-input v-model="keyword" clearable prefix-icon="el-icon-search" placeholder="搜索设备名称 / MAC / 型号" />
        <el-select v-model="agentFilter" clearable filterable placeholder="全部智能体">
          <el-option v-for="agent in agents" :key="agent.id" :value="agent.id" :label="agent.agentName" />
        </el-select>
        <el-button type="primary" icon="el-icon-plus" :disabled="!canWrite || loading" @click="addVisible = true">添加设备</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <div v-loading="loading" class="device-grid">
        <button class="add-card" :disabled="!canWrite || loading" @click="addVisible = true">
          <i class="el-icon-circle-plus-outline" /><strong>添加新设备</strong><span>选择智能体后绑定设备</span>
        </button>
        <DeviceAgentCard v-for="device in filteredDevices" :key="device.id" :device="device" :agents="agents"
          :can-write="canWrite && !loading" @busy="setBusy(device.id, $event)" @scenes="openScenes(device)"
          @updated="updateDevice(device.id, $event)" />
      </div>
      <el-empty v-if="!loading && !error && !filteredDevices.length" description="暂无匹配设备" />
    </main>
    <el-dialog title="添加设备" :visible.sync="addVisible" width="440px">
      <p>选择设备首次绑定的智能体</p>
      <el-select v-model="addAgentId" filterable placeholder="请选择智能体" style="width:100%">
        <el-option v-for="agent in agents" :key="agent.id" :value="agent.id" :label="agent.agentName" />
      </el-select>
      <p v-if="!agents.length">请先在智能体中心创建智能体。</p>
      <span slot="footer">
        <el-button :disabled="!addAgentId || !canWrite" @click="openAdd('manual')">手动添加</el-button>
        <el-button type="primary" :disabled="!addAgentId || !canWrite" @click="openAdd('code')">验证码绑定</el-button>
      </span>
    </el-dialog>
    <AddDeviceDialog v-if="codeVisible" :visible.sync="codeVisible" :agent-id="addAgentId" @refresh="load" />
    <ManualAddDeviceDialog v-if="manualVisible" :visible.sync="manualVisible" :agent-id="addAgentId" @refresh="load" />
    <DeviceSceneDialog ref="scenes" @switched="load" />
  </div>
</template>
<script>
import HeaderBar from '@/components/HeaderBar.vue';
import DeviceAgentCard from '@/components/device/DeviceAgentCard.vue';
import AddDeviceDialog from '@/components/AddDeviceDialog.vue';
import ManualAddDeviceDialog from '@/components/ManualAddDeviceDialog.vue';
import DeviceSceneDialog from '@/components/scene/DeviceSceneDialog.vue';
import { listWorkspaceAgents, listWorkspaceDevices } from '@/utils/deviceCenter';
export default {
  components: { HeaderBar, DeviceAgentCard, AddDeviceDialog, ManualAddDeviceDialog, DeviceSceneDialog },
  data() { return { agents: [], devices: [], keyword: '', agentFilter: '', loading: false, error: '',
    addVisible: false, addAgentId: '', codeVisible: false, manualVisible: false, busyDevices: {}, generation: 0,
    canWrite: localStorage.getItem('currentWorkspaceCanWrite') !== 'false' }; },
  computed: {
    switching() { return Object.values(this.busyDevices).some(Boolean); },
    filteredDevices() {
      const keyword = this.keyword.trim().toLowerCase();
      return this.devices.filter(device => (!this.agentFilter || device.agentId === this.agentFilter)
        && [device.alias, device.macAddress, device.board, device.id].some(value => String(value || '').toLowerCase().includes(keyword)));
    }
  },
  created() { this.load(); },
  beforeDestroy() { this.generation++; },
  methods: {
    async load() {
      const generation = ++this.generation;
      this.loading = true; this.error = '';
      try {
        const agents = await listWorkspaceAgents();
        const devices = await listWorkspaceDevices(agents || []);
        if (generation !== this.generation) return;
        this.agents = agents || []; this.devices = devices;
      } catch (error) { if (generation === this.generation) this.error = error.message; }
      finally { if (generation === this.generation) this.loading = false; }
    },
    setBusy(id, busy) { this.$set(this.busyDevices, id, busy); },
    updateDevice(id, scene) {
      const device = this.devices.find(item => item.id === id);
      if (device && scene) { device.agentId = scene.agentId; device.agentName = scene.agentName; }
    },
    openScenes(device) { this.$refs.scenes.open({ ...device, device_id: device.id, remark: device.alias }); },
    openAdd(mode) { this.addVisible = false; this.codeVisible = mode === 'code'; this.manualVisible = mode === 'manual'; }
  }
};
</script>
<style scoped>
.device-center{min-height:100vh;background:linear-gradient(130deg,#eef3ff,#f6f1fc);color:#30364a}
main{max-width:1440px;margin:auto;padding:28px}.title-row,.toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px}
h1{font-size:26px;margin:0}p{color:#777e91}.toolbar{margin:24px 0;flex-wrap:wrap}.toolbar .el-input{width:280px}.toolbar>span{margin-right:auto}
.device-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:22px;margin-top:20px;min-height:260px}
.add-card{border:2px dashed #b7bcf5;border-radius:20px;background:rgba(255,255,255,.65);color:#656ec9;min-height:310px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:18px;cursor:pointer;font:inherit}
.add-card i{font-size:44px}.add-card strong{font-size:22px}.add-card:disabled{opacity:.5;cursor:not-allowed}
@media(max-width:600px){main{padding:16px}.toolbar .el-input,.toolbar .el-select{width:100%}}
</style>
