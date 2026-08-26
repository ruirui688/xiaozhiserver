<template>
  <div class="maintenance-workbench">
    <aside class="run-history">
      <div class="history-title">维护会话</div>
      <button v-for="run in runs" :key="run.id" type="button"
        :class="['history-item', { active: run.id === selectedRunId }]" @click="selectRun(run)">
        <span>{{ run.userInstruction || triggerText(run.triggerType) }}</span>
        <small>{{ formatDate(run.createdAt) }} · {{ runStatusText(run.status) }}</small>
      </button>
      <div v-if="!runs.length" class="empty-history">还没有维护会话</div>
    </aside>

    <section class="conversation">
      <div ref="messages" class="messages" v-loading="loading">
        <template v-if="selectedRun">
          <div class="message user-message">
            <div class="avatar">你</div>
            <div class="bubble">{{ selectedRun.userInstruction || '按照已保存的维护提示词执行一次知识发现。' }}</div>
          </div>
          <div class="message agent-message">
            <div class="avatar agent-avatar">DS</div>
            <div class="bubble agent-bubble">
              <div class="agent-heading">
                <strong>知识维护智能体</strong>
                <el-tag size="mini" :type="runStatusType(selectedRun.status)">{{ runStatusText(selectedRun.status) }}</el-tag>
              </div>
              <div v-for="event in events" :key="event.id" class="event-line">
                <i :class="`is-${String(event.level || 'INFO').toLowerCase()}`" />
                <span>{{ formatTime(event.createdAt) }}</span>
                <b>{{ phaseText(event.phase) }}</b>
                <em>{{ event.message }}</em>
              </div>
              <div v-if="!events.length" class="muted">等待后台返回第一条运行事件……</div>
            </div>
          </div>

          <div v-for="proposal in selectedProposals" :key="proposal.id" class="message agent-message proposal-message">
            <div class="avatar agent-avatar">DS</div>
            <article class="proposal-card">
              <header>
                <div><span class="candidate-label">{{ proposal.actionType === 'REMOVE_SOURCE' ? '建议淘汰旧来源' : '候选知识来源' }}</span><strong>{{ proposal.title || '未命名网页' }}</strong></div>
                <el-tag size="mini" :type="proposalStatusType(proposal.status)">{{ proposalStatusText(proposal) }}</el-tag>
              </header>
              <a v-if="proposal.sourceUri" :href="proposal.sourceUri" target="_blank" rel="noopener noreferrer">{{ proposal.sourceUri }}</a>
              <p>{{ proposal.rationale }}</p>
              <ul v-if="evidence(proposal).length">
                <li v-for="(item, index) in evidence(proposal)" :key="index">{{ item }}</li>
              </ul>
              <footer v-if="proposal.status === 'PENDING'">
                <el-button size="mini" :type="proposal.actionType === 'REMOVE_SOURCE' ? 'danger' : 'primary'" @click="review(proposal, true)">{{ proposal.actionType === 'REMOVE_SOURCE' ? '批准移除旧来源' : '批准并入库' }}</el-button>
                <el-button size="mini" @click="review(proposal, false)">拒绝</el-button>
              </footer>
            </article>
          </div>
          <div v-if="selectedRun.status === 'SUCCEEDED' && !selectedProposals.length" class="no-candidate">本轮没有找到符合要求的新来源。你可以在下方补充更具体的版本、时间或来源要求。</div>
        </template>
        <div v-else class="conversation-empty">
          <strong>直接告诉维护智能体你需要什么</strong>
          <span>例如：只查找当前赛季官方版本公告，排除旧补丁、赛事投票和营销活动页面。</span>
        </div>
      </div>

      <div class="composer">
        <el-input v-model="instruction" type="textarea" :rows="3" maxlength="2000" show-word-limit
          placeholder="输入本次维护要求，例如：重新检查 18.1 当前赛季，只保留官网最新版本和强化符文数值调整……"
          @keydown.ctrl.enter.native="send" />
        <div class="composer-actions">
          <span>Ctrl + Enter 发送；系统安全规则、审批和配额不会被对话指令覆盖。</span>
          <el-button type="primary" :loading="sending" :disabled="!instruction.trim()" @click="send">发送并运行</el-button>
        </div>
      </div>
    </section>
  </div>
</template>

<script>
import Api from '@/apis/api'

export default {
  name: 'MaintenanceConversationPanel',
  props: {
    active: { type: Boolean, default: false },
    resourceType: { type: String, required: true },
    resourceId: { type: String, required: true }
  },
  data() {
    return {
      runs: [], proposals: [], events: [], selectedRunId: '', lastEventId: 0,
      instruction: '', loading: false, sending: false, refreshing: false, eventsLoading: false,
      timer: null, proposalStatusById: {}
    }
  },
  computed: {
    selectedRun() { return this.runs.find(item => item.id === this.selectedRunId) || null },
    selectedProposals() { return this.proposals.filter(item => item.runId === this.selectedRunId) }
  },
  watch: {
    active(value) { if (value) this.open(); else this.stopPolling() }
  },
  beforeDestroy() { this.stopPolling() },
  methods: {
    payload(res) { return (res && res.data && res.data.data) || (res && res.data) || [] },
    open() { this.refresh(false); this.startPolling() },
    refresh(silent = true) {
      if (this.refreshing) return
      this.refreshing = true
      if (!silent) this.loading = true
      let pending = 2
      const done = () => {
        pending -= 1
        if (!pending) { this.loading = false; this.refreshing = false }
      }
      Api.maintenance.runs(this.resourceType, this.resourceId, res => {
        this.runs = this.payload(res) || []
        if (!this.selectedRunId && this.runs.length) this.selectRun(this.runs[0])
        done()
      }, done)
      Api.maintenance.proposals(this.resourceType, this.resourceId, res => {
        const next = this.payload(res) || []
        next.forEach(item => {
          const previous = this.proposalStatusById[item.id]
          if (previous === 'APPLYING' && item.status === 'APPLIED') this.$emit('changed')
          this.$set(this.proposalStatusById, item.id, item.status)
        })
        this.proposals = next
        done()
      }, done)
    },
    selectRun(run) {
      if (!run || !run.id) return
      if (run.id !== this.selectedRunId) {
        this.selectedRunId = run.id
        this.events = []
        this.lastEventId = 0
      }
      this.loadEvents()
    },
    loadEvents() {
      if (!this.selectedRunId || this.eventsLoading) return
      const runId = this.selectedRunId
      this.eventsLoading = true
      Api.maintenance.runEvents(this.resourceType, this.resourceId, runId, this.lastEventId, res => {
        this.eventsLoading = false
        if (runId !== this.selectedRunId) return
        const next = this.payload(res) || []
        if (next.length) {
          const known = new Set(this.events.map(item => item.id))
          this.events = this.events.concat(next.filter(item => !known.has(item.id)))
          this.lastEventId = this.events[this.events.length - 1].id
          this.$nextTick(this.scrollToBottom)
        }
      }, () => { this.eventsLoading = false })
    },
    send() {
      const instruction = this.instruction.trim()
      if (!instruction || this.sending) return
      this.sending = true
      Api.maintenance.run(this.resourceType, this.resourceId, { instruction }, res => {
        const run = this.payload(res)
        this.instruction = ''
        this.sending = false
        this.runs = [run].concat(this.runs.filter(item => item.id !== run.id))
        this.selectRun(run)
        this.$message.success('维护智能体已收到指令')
      }, error => {
        this.sending = false
        this.$message.error((error && error.data && error.data.msg) || '发送维护指令失败')
      })
    },
    review(proposal, approve) {
      Api.maintenance[approve ? 'approve' : 'reject'](this.resourceType, this.resourceId, proposal.id, () => {
        proposal.status = approve && this.resourceType === 'KNOWLEDGE' && proposal.actionType === 'ADD_SOURCE' ? 'APPLYING' : (approve ? 'APPLIED' : 'REJECTED')
        this.$set(this.proposalStatusById, proposal.id, proposal.status)
        this.$message.success(approve
          ? (proposal.actionType === 'REMOVE_SOURCE' ? '旧来源及其动态文档已移除' : '已批准，正在抓取并发布')
          : '已拒绝该候选来源')
        this.refresh()
      }, () => this.$message.error(approve ? '批准失败' : '拒绝失败'))
    },
    evidence(proposal) {
      try { const value = JSON.parse(proposal.evidenceJson || '[]'); return Array.isArray(value) ? value : [] } catch (_) { return [] }
    },
    startPolling() {
      this.stopPolling()
      this.timer = window.setInterval(() => { this.refresh(); this.loadEvents() }, 1000)
    },
    stopPolling() { if (this.timer) window.clearInterval(this.timer); this.timer = null },
    scrollToBottom() { const box = this.$refs.messages; if (box) box.scrollTop = box.scrollHeight },
    triggerText(value) { return value === 'SCHEDULED' ? '定时维护' : '手动维护' },
    runStatusText(value) { return ({ QUEUED: '排队中', RUNNING: '正在查找', SUCCEEDED: '本轮完成', FAILED: '运行失败' })[value] || value },
    runStatusType(value) { return ({ QUEUED: 'info', RUNNING: 'warning', SUCCEEDED: 'success', FAILED: 'danger' })[value] || 'info' },
    proposalStatusText(proposal) {
      const value = proposal && proposal.status
      if (proposal && proposal.actionType === 'REMOVE_SOURCE') {
        return ({ PENDING: '待审批', APPLIED: '已移除', REJECTED: '已保留', FAILED: '移除失败' })[value] || value
      }
      return ({ PENDING: '待审批', APPLYING: '同步入库中', APPLIED: '已入库', REJECTED: '已拒绝', FAILED: '入库失败' })[value] || value
    },
    proposalStatusType(value) { return ({ PENDING: 'warning', APPLYING: 'warning', APPLIED: 'success', REJECTED: 'info', FAILED: 'danger' })[value] || 'info' },
    phaseText(value) { return ({ DISCOVERY: '搜索', PROPOSAL: '候选', SOURCE_SYNC: '入库', COMPLETE: '完成' })[value] || value },
    formatTime(value) { const date = new Date(value); return Number.isNaN(date.getTime()) ? '--:--:--' : date.toLocaleTimeString('zh-CN', { hour12: false }) },
    formatDate(value) { const date = new Date(value); return Number.isNaN(date.getTime()) ? '' : `${date.getMonth() + 1}-${date.getDate()} ${this.formatTime(value)}` }
  }
}
</script>

<style scoped>
.maintenance-workbench { display: grid; grid-template-columns: 220px minmax(0, 1fr); min-height: 610px; border: 1px solid #e4e7ed; border-radius: 12px; overflow: hidden; background: #f5f7fb; }
.run-history { padding: 14px 10px; border-right: 1px solid #e4e7ed; background: #fff; overflow: auto; }
.history-title { padding: 4px 10px 12px; font-weight: 600; color: #303133; }
.history-item { width: 100%; border: 0; border-radius: 8px; padding: 10px; margin-bottom: 6px; text-align: left; background: transparent; cursor: pointer; color: #303133; }
.history-item:hover, .history-item.active { background: #ecf5ff; color: #2768d7; }
.history-item span, .history-item small { display: block; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.history-item small { margin-top: 5px; color: #909399; }
.empty-history { padding: 30px 10px; color: #909399; text-align: center; }
.conversation { display: grid; grid-template-rows: minmax(0, 1fr) auto; min-width: 0; }
.messages { height: 470px; overflow: auto; padding: 22px; }
.message { display: flex; gap: 10px; margin-bottom: 16px; }
.user-message { justify-content: flex-end; }
.user-message .avatar { order: 2; background: #409eff; }
.avatar { flex: 0 0 34px; width: 34px; height: 34px; border-radius: 10px; display: grid; place-items: center; background: #fff; color: #fff; font-size: 12px; }
.agent-avatar { background: linear-gradient(135deg, #5b7cff, #8b5cf6); }
.bubble { max-width: 78%; padding: 12px 14px; border-radius: 12px 4px 12px 12px; background: #409eff; color: #fff; line-height: 1.6; white-space: pre-wrap; }
.agent-bubble { width: min(760px, 88%); max-width: none; border-radius: 4px 12px 12px; background: #fff; color: #303133; box-shadow: 0 2px 10px rgba(31, 45, 61, .06); }
.agent-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.event-line { display: grid; grid-template-columns: 8px 68px 45px minmax(0, 1fr); gap: 7px; align-items: center; min-height: 28px; font-size: 12px; }
.event-line i { width: 7px; height: 7px; border-radius: 50%; background: #409eff; }
.event-line i.is-success { background: #67c23a; } .event-line i.is-error { background: #f56c6c; }
.event-line span, .muted { color: #909399; } .event-line b { color: #606266; } .event-line em { font-style: normal; }
.proposal-card { width: min(760px, 88%); padding: 14px 16px; border: 1px solid #dfe7f5; border-radius: 4px 12px 12px; background: #fff; }
.proposal-card header { display: flex; justify-content: space-between; gap: 12px; }
.proposal-card header strong { display: block; margin-top: 5px; }
.candidate-label { color: #7a8aa0; font-size: 12px; }
.proposal-card a { display: block; margin: 9px 0; color: #409eff; overflow-wrap: anywhere; }
.proposal-card p, .proposal-card li { color: #606266; line-height: 1.6; }
.proposal-card ul { padding-left: 20px; } .proposal-card footer { margin-top: 12px; }
.no-candidate, .conversation-empty { color: #909399; text-align: center; padding: 40px 20px; }
.conversation-empty { display: grid; gap: 8px; padding-top: 150px; }
.conversation-empty strong { color: #303133; font-size: 18px; }
.composer { padding: 14px 18px; border-top: 1px solid #e4e7ed; background: #fff; }
.composer-actions { display: flex; justify-content: space-between; align-items: center; margin-top: 9px; color: #909399; font-size: 12px; }
@media (max-width: 900px) { .maintenance-workbench { grid-template-columns: 1fr; } .run-history { display: none; } }
</style>
