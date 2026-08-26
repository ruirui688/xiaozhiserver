<template>
  <el-dialog
    custom-class="agent-runtime-monitor-dialog"
    :visible="visible"
    width="900px"
    append-to-body
    :close-on-click-modal="false"
    @close="$emit('update:visible', false)"
  >
    <template slot="title">
      <div class="monitor-title">
        <span class="monitor-title__icon"><i class="el-icon-monitor" /></span>
        <div>
          <strong>{{ agentName }} · 运行监控</strong>
          <small>只展示当前智能体可见的运行摘要，不暴露服务器原始日志</small>
        </div>
        <span :class="['live-pill', { active: refreshing }]">
          <i class="el-icon-refresh" /> {{ refreshing ? '正在刷新' : '自动刷新中' }}
        </span>
      </div>
    </template>

    <div v-loading="loading" class="monitor-body">
      <div class="monitor-stats">
        <div class="stat-card"><small>绑定设备</small><strong>{{ deviceCount }}</strong><span>设备连接需以设备端状态为准</span></div>
        <div class="stat-card"><small>会话总数</small><strong>{{ totalSessions }}</strong><span>最近一次同步：{{ refreshedAt || '—' }}</span></div>
        <div :class="['stat-card', 'stat-card--status', statusClass]"><small>最近运行状态</small><strong>{{ statusText }}</strong><span>{{ lastConnectedText }}</span></div>
      </div>

      <div class="monitor-toolbar">
        <div><strong>会话运行记录</strong><span>每 5 秒自动刷新</span></div>
        <el-button size="mini" icon="el-icon-refresh" :loading="loading" @click="refresh">立即刷新</el-button>
      </div>

      <el-table :data="sessions" class="runtime-table" empty-text="暂无会话运行记录" max-height="390">
        <el-table-column label="时间" width="190">
          <template slot-scope="scope">{{ formatTime(scope.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="事件" min-width="280">
          <template slot-scope="scope">
            <div class="event-cell"><span class="event-dot" />{{ scope.row.title || '智能体会话' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="chatCount" label="消息数" width="100" />
        <el-table-column label="状态" width="110"><template><el-tag size="mini" type="success">已记录</el-tag></template></el-table-column>
      </el-table>

      <div class="monitor-note"><i class="el-icon-info" /> 这里显示的是管理端已记录的会话活动，不等同于容器 stdout；用户正文、Token 和模型密钥不会进入运行监控。</div>
    </div>
  </el-dialog>
</template>

<script>
export default {
  name: 'AgentRuntimeMonitorDialog',
  props: {
    visible: { type: Boolean, default: false },
    agent: { type: Object, default: () => ({}) },
    sessions: { type: Array, default: () => [] },
    totalSessions: { type: Number, default: 0 },
    refreshedAt: { type: String, default: '' },
    loading: { type: Boolean, default: false },
    refreshing: { type: Boolean, default: false }
  },
  computed: {
    agentName() { return this.agent.agentName || '智能体'; },
    deviceCount() { return Number(this.agent.deviceCount) || 0; },
    lastConnectedText() {
      return this.agent.lastConnectedAt ? `最近连接：${this.formatTime(this.agent.lastConnectedAt)}` : '暂无连接记录';
    },
    statusClass() {
      if (!this.agent.lastConnectedAt) return 'status-idle';
      const age = Date.now() - new Date(this.agent.lastConnectedAt).getTime();
      return age <= 10 * 60 * 1000 ? 'status-active' : 'status-idle';
    },
    statusText() { return this.statusClass === 'status-active' ? '近期活跃' : '等待设备'; }
  },
  methods: {
    formatTime(value) {
      if (!value) return '—';
      const date = new Date(value);
      if (Number.isNaN(date.getTime())) return String(value);
      return date.toLocaleString('zh-CN', { hour12: false });
    },
    refresh() { this.$emit('refresh'); }
  }
}
</script>

<style scoped>
.monitor-title { display: flex; align-items: center; gap: 12px; }
.monitor-title__icon { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 11px; color: #5578ef; background: #eaf0ff; font-size: 19px; }
.monitor-title div { display: flex; flex-direction: column; gap: 4px; }
.monitor-title strong { color: #303852; font-size: 19px; }
.monitor-title small { color: #929bad; font-size: 11px; font-weight: 400; }
.live-pill { margin-left: auto; padding: 6px 10px; color: #949caf; border-radius: 12px; background: #f2f4f8; font-size: 11px; }
.live-pill.active { color: #248f60; background: #e7f8ef; }
.monitor-body { min-height: 500px; }
.monitor-stats { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin: 4px 0 24px; }
.stat-card { padding: 17px 18px; border: 1px solid #e8edf6; border-radius: 14px; background: #f8faff; }
.stat-card small, .stat-card span { display: block; color: #8e97aa; font-size: 11px; }
.stat-card strong { display: block; margin: 7px 0 4px; color: #3f5fc6; font-size: 25px; }
.stat-card--status strong { color: #248f60; font-size: 20px; }
.stat-card--status.status-idle strong { color: #9a7a25; }
.monitor-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.monitor-toolbar div { display: flex; align-items: baseline; gap: 10px; }
.monitor-toolbar strong { color: #3b4560; }
.monitor-toolbar span { color: #9ba3b5; font-size: 11px; }
.runtime-table { border: 1px solid #edf0f6; border-radius: 12px; overflow: hidden; }
.event-cell { display: flex; align-items: center; gap: 8px; color: #525e78; }
.event-dot { width: 7px; height: 7px; border-radius: 50%; background: #5578ef; }
.monitor-note { margin-top: 15px; padding: 11px 13px; color: #8c95a7; background: #f8f9fc; border-radius: 9px; font-size: 11px; line-height: 1.6; }
.monitor-note i { margin-right: 5px; color: #7390e8; }
@media (max-width: 700px) { .monitor-stats { grid-template-columns: 1fr; } .monitor-title small { display: none; } }
</style>

<style>
.agent-runtime-monitor-dialog { border-radius: 18px; }
.agent-runtime-monitor-dialog .el-dialog__body { padding-top: 5px; }
</style>
