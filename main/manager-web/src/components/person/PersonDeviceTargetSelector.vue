<template>
  <div class="device-target-selector">
    <div class="target-card">
      <div class="target-card__header">
        <div>
          <strong>人脸下发目标</strong>
          <span>已选择 {{ selectedDevices.length }} / {{ maxSelection }} 台</span>
        </div>
        <el-button size="small" type="primary" plain icon="el-icon-s-operation" @click="openSelector">
          选择设备
        </el-button>
      </div>

      <div v-if="selectedDevices.length" class="target-preview">
        <div v-for="device in previewDevices" :key="device.id" class="target-preview__row">
          <div class="device-name">
            <i class="el-icon-monitor" />
            <span>{{ displayName(device) }}</span>
            <el-tag v-if="!device.alias" size="mini" type="warning" effect="plain">未设置别名</el-tag>
          </div>
          <span>{{ agentName(device) }}</span>
          <code>{{ device.macAddress || "无 MAC" }}</code>
        </div>
        <button v-if="selectedDevices.length > previewLimit" type="button" class="more-targets" @click="openSelector">
          还有 {{ selectedDevices.length - previewLimit }} 台设备，点击查看全部
        </button>
      </div>
      <div v-else class="target-empty" @click="openSelector">
        <i class="el-icon-location-outline" />
        <div>
          <strong>尚未选择下发设备</strong>
          <span>照片不会下发；点击选择具体设备或按绑定智能体批量筛选。</span>
        </div>
      </div>

      <div class="target-card__footer">
        <i class="el-icon-info" />
        <span>只向这里列出的设备下发。状态只展示“最后连接时间”，不把它冒充为实时在线或已同步。</span>
      </div>
    </div>

    <el-dialog
      title="选择人脸下发设备"
      :visible.sync="selectorVisible"
      width="880px"
      top="4vh"
      append-to-body
      :close-on-click-modal="false"
      custom-class="person-device-selector-dialog"
    >
      <div class="selector-intro">
        <div>
          <strong>先认设备，再选范围</strong>
          <span>优先按设备别名和绑定智能体定位；MAC 只作为最后核对信息。</span>
        </div>
        <el-button type="text" @click="openDeviceManagement">去设备管理设置别名 <i class="el-icon-top-right" /></el-button>
      </div>

      <div class="selector-toolbar">
        <el-input
          v-model.trim="keyword"
          clearable
          prefix-icon="el-icon-search"
          placeholder="搜索设备别名、MAC、型号或固件"
        />
        <el-select v-model="agentFilter" placeholder="全部绑定智能体">
          <el-option label="全部绑定智能体" value="all" />
          <el-option v-for="agent in agentOptions" :key="agent.value" :label="agent.label" :value="agent.value" />
          <el-option label="未绑定智能体" value="unbound" />
        </el-select>
        <el-button :disabled="!filteredDevices.length" @click="selectFiltered">选择当前筛选结果</el-button>
      </div>

      <div class="selector-content">
        <section class="device-results">
          <div class="result-heading">
            <span>匹配 {{ filteredDevices.length }} 台设备</span>
            <small v-if="filteredDevices.length > visibleLimit">仅展示前 {{ visibleLimit }} 台，请继续缩小筛选范围</small>
          </div>
          <div v-if="!visibleDevices.length" class="device-results__empty">
            <i class="el-icon-search" />
            <span>没有匹配设备，请更换搜索词或筛选条件。</span>
          </div>
          <div v-else class="device-list">
            <label v-for="device in visibleDevices" :key="device.id" class="device-row">
              <el-checkbox :value="draftDeviceIds.includes(device.id)" @change="toggleDevice(device.id, $event)" />
              <div class="device-row__identity">
                <div>
                  <strong>{{ displayName(device) }}</strong>
                  <el-tag v-if="!device.alias" size="mini" type="warning" effect="plain">未命名</el-tag>
                </div>
                <code>{{ device.macAddress || "无 MAC" }}</code>
              </div>
              <div class="device-row__binding">
                <span>{{ agentName(device) }}</span>
                <small>{{ hardwareLabel(device) }}</small>
              </div>
              <div class="device-row__activity">
                <span>最后连接</span>
                <small>{{ lastConnectedLabel(device.lastConnectedAt) }}</small>
              </div>
            </label>
          </div>
        </section>

        <aside class="selected-targets">
          <div class="selected-targets__heading">
            <div>
              <strong>本次下发范围</strong>
              <span>{{ draftDeviceIds.length }} / {{ maxSelection }} 台</span>
            </div>
            <el-button type="text" :disabled="!draftDeviceIds.length" @click="draftDeviceIds = []">清空</el-button>
          </div>
          <div v-if="!draftSelectedDevices.length" class="selected-targets__empty">尚未选择设备</div>
          <div v-else class="selected-target-list">
            <div v-for="device in draftSelectedDevices" :key="device.id" class="selected-target-row">
              <div>
                <strong>{{ displayName(device) }}</strong>
                <small>{{ agentName(device) }}</small>
              </div>
              <button type="button" aria-label="移除设备" @click="toggleDevice(device.id, false)">×</button>
            </div>
          </div>
        </aside>
      </div>

      <div slot="footer" class="selector-footer">
        <span>保存人物后，照片只会授权给右侧列出的设备。</span>
        <div>
          <el-button @click="selectorVisible = false">取消</el-button>
          <el-button type="primary" @click="confirmSelection">确认选择 {{ draftDeviceIds.length }} 台</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import {
  deviceAgentName,
  deviceDisplayName,
  matchesDeviceTarget,
  sortDeviceTargets
} from "@/utils/personDeviceTargets";

export default {
  name: "PersonDeviceTargetSelector",
  props: {
    value: { type: Array, default: () => [] },
    devices: { type: Array, default: () => [] },
    maxSelection: { type: Number, default: 50 }
  },
  data() {
    return {
      selectorVisible: false,
      keyword: "",
      agentFilter: "all",
      draftDeviceIds: [],
      previewLimit: 3,
      visibleLimit: 200
    };
  },
  computed: {
    sortedDevices() {
      return sortDeviceTargets(this.devices);
    },
    deviceMap() {
      return new Map(this.devices.map(device => [device.id, device]));
    },
    selectedDevices() {
      return this.value.map(id => this.deviceMap.get(id)).filter(Boolean);
    },
    previewDevices() {
      return this.selectedDevices.slice(0, this.previewLimit);
    },
    filteredDevices() {
      return this.sortedDevices.filter(device => matchesDeviceTarget(device, this.keyword, this.agentFilter));
    },
    visibleDevices() {
      return this.filteredDevices.slice(0, this.visibleLimit);
    },
    draftSelectedDevices() {
      return this.draftDeviceIds.map(id => this.deviceMap.get(id)).filter(Boolean);
    },
    agentOptions() {
      const options = new Map();
      this.devices.forEach(device => {
        if (device.agentId) {
          options.set(device.agentId, device.agentName || `智能体 ${device.agentId.slice(-6)}`);
        }
      });
      return [...options.entries()]
        .map(([value, label]) => ({ value, label }))
        .sort((left, right) => left.label.localeCompare(right.label, "zh-CN"));
    }
  },
  methods: {
    displayName: deviceDisplayName,
    agentName: deviceAgentName,
    openSelector() {
      this.keyword = "";
      this.agentFilter = "all";
      this.draftDeviceIds = this.value.filter(id => this.deviceMap.has(id)).slice(0, this.maxSelection);
      this.selectorVisible = true;
    },
    toggleDevice(deviceId, checked) {
      if (checked) {
        if (this.draftDeviceIds.includes(deviceId)) return;
        if (this.draftDeviceIds.length >= this.maxSelection) {
          this.$message.warning(`单个人物最多选择 ${this.maxSelection} 台设备`);
          return;
        }
        this.draftDeviceIds.push(deviceId);
        return;
      }
      this.draftDeviceIds = this.draftDeviceIds.filter(id => id !== deviceId);
    },
    selectFiltered() {
      const merged = [...this.draftDeviceIds];
      this.filteredDevices.forEach(device => {
        if (merged.length < this.maxSelection && !merged.includes(device.id)) merged.push(device.id);
      });
      const truncated = merged.length === this.maxSelection
        && this.filteredDevices.some(device => !merged.includes(device.id));
      this.draftDeviceIds = merged;
      if (truncated) this.$message.warning(`最多选择 ${this.maxSelection} 台设备，请缩小筛选范围`);
    },
    confirmSelection() {
      this.$emit("input", [...this.draftDeviceIds]);
      this.selectorVisible = false;
    },
    hardwareLabel(device) {
      return [device.board, device.appVersion ? `固件 ${device.appVersion}` : ""].filter(Boolean).join(" · ") || "未上报硬件信息";
    },
    lastConnectedLabel(value) {
      if (!value) return "从未上报";
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) return "时间未知";
      return date.toLocaleString("zh-CN", {
        year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"
      });
    },
    openDeviceManagement() {
      const resolved = this.$router.resolve({ path: "/device-management" });
      window.open(resolved.href, "_blank", "noopener");
    }
  }
};
</script>

<style lang="scss" scoped>
.target-card {
  overflow: hidden;
  border: 1px solid #dfe5ef;
  border-radius: 10px;
  background: #fff;
}

.target-card__header,
.target-card__footer,
.target-preview__row,
.selector-intro,
.selector-toolbar,
.result-heading,
.selected-targets__heading,
.selector-footer {
  display: flex;
  align-items: center;
}

.target-card__header {
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid #edf0f5;
}

.target-card__header > div {
  display: grid;
  gap: 2px;
}

.target-card__header strong,
.selector-intro strong,
.selected-targets__heading strong {
  color: #354058;
  font-size: 13px;
}

.target-card__header span,
.selector-intro span,
.selected-targets__heading span {
  color: #8a94a8;
  font-size: 11px;
}

.target-preview {
  padding: 6px 14px;
}

.target-preview__row {
  min-height: 38px;
  gap: 10px;
  border-bottom: 1px solid #f0f2f6;
  color: #6d7689;
  font-size: 11px;
}

.target-preview__row:last-of-type {
  border-bottom: 0;
}

.target-preview__row .device-name {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 6px;
  color: #3c465b;
  font-size: 12px;
  font-weight: 600;
}

.target-preview__row code,
.device-row__identity code {
  color: #78849a;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 10px;
}

.more-targets {
  padding: 7px 0;
  border: 0;
  background: transparent;
  color: #367bd6;
  cursor: pointer;
  font-size: 11px;
}

.target-empty {
  display: flex;
  min-height: 72px;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  cursor: pointer;
}

.target-empty > i {
  color: #88a6d9;
  font-size: 23px;
}

.target-empty > div {
  display: grid;
  gap: 3px;
}

.target-empty strong {
  color: #59657a;
  font-size: 12px;
}

.target-empty span,
.target-card__footer {
  color: #929bad;
  font-size: 11px;
}

.target-card__footer {
  gap: 6px;
  padding: 8px 14px;
  background: #f7f9fc;
}

.selector-intro {
  justify-content: space-between;
  gap: 20px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #f5f8fd;
}

.selector-intro > div {
  display: grid;
  gap: 2px;
}

.selector-toolbar {
  gap: 8px;
  margin: 12px 0;
}

.selector-toolbar .el-input {
  flex: 1;
}

.selector-toolbar .el-select {
  width: 190px;
}

.selector-content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 250px;
  min-height: 360px;
  overflow: hidden;
  border: 1px solid #e2e7f0;
  border-radius: 10px;
}

.device-results {
  min-width: 0;
  border-right: 1px solid #e2e7f0;
}

.result-heading,
.selected-targets__heading {
  min-height: 42px;
  justify-content: space-between;
  padding: 0 12px;
  border-bottom: 1px solid #edf0f5;
  color: #5f697d;
  font-size: 12px;
}

.result-heading small {
  color: #d29328;
}

.device-list,
.selected-target-list {
  max-height: 360px;
  overflow-y: auto;
}

.device-row {
  display: grid;
  grid-template-columns: 24px minmax(155px, 1.15fr) minmax(135px, 1fr) 112px;
  min-height: 60px;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border-bottom: 1px solid #f0f2f6;
  cursor: pointer;
}

.device-row:hover {
  background: #f8faff;
}

.device-row__identity,
.device-row__binding,
.device-row__activity {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.device-row__identity > div {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 5px;
}

.device-row__identity strong {
  overflow: hidden;
  color: #3b455a;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-row__binding span,
.device-row__activity span {
  overflow: hidden;
  color: #566176;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.device-row__binding small,
.device-row__activity small {
  color: #99a1b1;
  font-size: 10px;
}

.device-results__empty,
.selected-targets__empty {
  display: grid;
  min-height: 300px;
  place-content: center;
  gap: 8px;
  color: #9aa3b4;
  font-size: 12px;
  text-align: center;
}

.device-results__empty i {
  font-size: 25px;
}

.selected-targets__heading > div {
  display: grid;
  gap: 2px;
}

.selected-target-row {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 11px;
  border-bottom: 1px solid #f0f2f6;
}

.selected-target-row > div {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.selected-target-row strong,
.selected-target-row small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selected-target-row strong {
  color: #4a556a;
  font-size: 11px;
}

.selected-target-row small {
  color: #9aa2b2;
  font-size: 10px;
}

.selected-target-row button {
  border: 0;
  background: transparent;
  color: #a4adbc;
  cursor: pointer;
  font-size: 17px;
}

.selector-footer {
  justify-content: space-between;
  gap: 16px;
}

.selector-footer > span {
  color: #8993a6;
  font-size: 11px;
}

@media (max-width: 900px) {
  ::v-deep .person-device-selector-dialog {
    width: calc(100vw - 32px) !important;
  }

  .selector-content {
    grid-template-columns: 1fr;
  }

  .selected-targets {
    display: none;
  }
}
</style>
