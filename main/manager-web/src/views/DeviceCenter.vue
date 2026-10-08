<template>
  <div class="device-center">
    <HeaderBar />
    <main>
      <div class="title-row"><div><h1>设备管理 <span class="device-count">{{ devices.length }} 台</span></h1><p>用你熟悉的名字，找到每一台设备。</p></div>
        <el-button type="primary" icon="el-icon-plus" :disabled="!canWrite || loading" @click="addVisible = true">添加设备</el-button>
      </div>
      <div class="toolbar">
        <el-input v-model="keyword" clearable prefix-icon="el-icon-search" placeholder="搜索别名" />
        <el-select v-model="agentFilter" clearable filterable placeholder="全部智能体">
          <el-option v-for="agent in agents" :key="agent.id" :value="agent.id" :label="agent.agentName" />
        </el-select>
        <el-radio-group v-model="viewMode" size="small" :disabled="switching" aria-label="设备视图">
          <el-radio-button label="list">列表</el-radio-button><el-radio-button label="cards">卡片</el-radio-button>
        </el-radio-group>
        <span class="filter-count">{{ filteredDevices.length }} 台设备</span>
        <el-button icon="el-icon-refresh" :loading="loading" :disabled="switching" @click="load">刷新</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <div v-loading="loading" class="device-results" :class="viewMode">
        <div v-if="viewMode === 'list'" class="list-heading"><span>设备别名</span><span>使用的智能体</span><span>最近连接</span><span>操作</span></div>
        <div class="device-grid" :class="{ 'is-empty': !filteredDevices.length }">
        <DeviceAgentCard v-for="device in pagedDevices" :list="viewMode === 'list'" :key="device.id" :device="device" :agents="agents"
          :can-write="canWrite && !loading" @busy="setBusy(device.id, $event)" @scenes="openScenes(device)"
          @rename="$refs.aliasDialog.open(device)" @updated="updateDevice(device.id, $event)" />
        </div>
      </div>
      <el-pagination v-if="filteredDevices.length" class="device-pagination" :current-page.sync="currentPage"
        :page-size.sync="pageSize" :page-sizes="[10, 20, 50]" :total="filteredDevices.length"
        :disabled="switching" layout="total, sizes, prev, pager, next" @size-change="currentPage = 1" />
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
    <DeviceAliasDialog ref="aliasDialog" :can-write="canWrite" @saved="updateAlias" />
    <DeviceSceneDialog ref="scenes" @switched="load" />
  </div>
</template>
<script>
import DeviceAliasDialog from '@/components/device/DeviceAliasDialog.vue';
import HeaderBar from '@/components/HeaderBar.vue';
import DeviceAgentCard from '@/components/device/DeviceAgentCard.vue';
import AddDeviceDialog from '@/components/AddDeviceDialog.vue';
import ManualAddDeviceDialog from '@/components/ManualAddDeviceDialog.vue';
import DeviceSceneDialog from '@/components/scene/DeviceSceneDialog.vue';
import { listWorkspaceAgents, listWorkspaceDevices } from '@/utils/deviceCenter';
export default {
  components: { DeviceAliasDialog, HeaderBar, DeviceAgentCard, AddDeviceDialog, ManualAddDeviceDialog, DeviceSceneDialog },
  data() { return { viewMode: 'list', currentPage: 1, pageSize: 10, agents: [], devices: [], keyword: '', agentFilter: '', loading: false, error: '',
    addVisible: false, addAgentId: '', codeVisible: false, manualVisible: false, busyDevices: {}, generation: 0,
    canWrite: localStorage.getItem('currentWorkspaceCanWrite') !== 'false' }; },
  computed: {
    pagedDevices() {
      const start = (this.currentPage - 1) * this.pageSize;
      return this.filteredDevices.slice(start, start + this.pageSize);
    },
    switching() { return Object.values(this.busyDevices).some(Boolean); },
    filteredDevices() {
      const keyword = this.keyword.trim().toLowerCase();
      return this.devices.filter(device => (!this.agentFilter || device.agentId === this.agentFilter)
        && [device.alias, device.macAddress, device.board, device.id].some(value => String(value || '').toLowerCase().includes(keyword)));
    }
  },
  watch: {
    keyword() { this.currentPage = 1; },
    agentFilter() { this.currentPage = 1; },
    'filteredDevices.length'(length) {
      this.currentPage = Math.min(this.currentPage, Math.max(1, Math.ceil(length / this.pageSize)));
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
    updateAlias({ id, alias }) {
      const device = this.devices.find(item => item.id === id);
      if (device) this.$set(device, 'alias', alias);
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
.device-center { min-height: 100vh; background: #f7f8fc; color: #30364a; text-align: left; }
main { max-width: 1280px; margin: 0 auto; padding: 44px 48px 64px; box-sizing: border-box; }
.title-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 36px; }
h1 { display: flex; align-items: center; gap: 12px; font-size: 24px; margin: 0; font-weight: 600; }
.device-count { background: #eceff6; color: #687086; font-size: 13px; font-weight: 400; padding: 4px 10px; border-radius: 8px; }
.title-row p { margin: 8px 0 0; color: #7c8496; font-size: 13px; }
.toolbar { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; padding: 0 0 28px; background: transparent; border-bottom: 1px solid #e9ecf3; margin-bottom: 32px; }
.toolbar > .el-input { width: 300px; }.toolbar .el-select { width: 200px; }
.filter-count { margin-left: auto; color: #7c8496; font-size: 13px; }
.device-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 32px; align-items: start; }
.device-grid.is-empty { min-height: 80px; }
.device-center ::v-deep .el-button--primary { background: #586ff0; border-color: #586ff0; }
.device-center ::v-deep .el-button--primary:hover { background: #4c62de; border-color: #4c62de; }
.device-center ::v-deep .el-button--primary.is-disabled { background: #edf0fb; border-color: #e6eaf8; color: #99a4c8; }
@media (max-width: 900px) { main { padding: 28px 24px; }.toolbar > .el-input, .toolbar .el-select { width: 100%; }.device-grid { grid-template-columns: minmax(0, 1fr); } }
.device-results.list { overflow-x: auto; background: #fff; border: 1px solid #e7eaf2; border-radius: 14px; }
.list .device-grid { display: block; min-width: 868px; }
.list-heading { display: grid; grid-template-columns: minmax(230px, 1.3fr) minmax(260px, 1fr) 160px 90px; gap: 24px; padding: 18px 28px; min-width: 868px; box-sizing: border-box; background: #fafafb; color: #778197; font-size: 13px; }
.device-pagination { margin-top: 24px; text-align: right; overflow-x: auto; }
</style>
