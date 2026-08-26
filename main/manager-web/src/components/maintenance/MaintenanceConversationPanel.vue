<template>
  <div class="maintenance-workbench">
    <aside class="run-history">
      <div class="history-title">维护记录</div>
      <button v-for="run in runs" :key="run.id" type="button"
        :class="['history-item', { active: run.id === selectedRunId }]" @click="selectRun(run)">
        <span>{{ run.userInstruction || triggerText(run.triggerType) }}</span>
        <small>{{ formatDate(run.createdAt) }} · {{ runStatusText(run.status) }}</small>
      </button>
      <div v-if="!runs.length" class="empty-history">还没有维护记录</div>
    </aside>

    <section class="conversation">
      <div ref="messages" class="messages" v-loading="loading">
        <template v-if="selectedRun">
          <div class="message user-message">
            <div class="avatar">你</div>
            <div class="bubble">{{ selectedRun.userInstruction || defaultInstruction }}</div>
          </div>
          <div class="message agent-message">
            <div class="avatar agent-avatar">DS</div>
            <div class="bubble agent-bubble">
              <div class="agent-heading">
                <strong>{{ agentTitle }}</strong>
                <el-tag size="mini" :type="runStatusType(selectedRun.status)">{{ runStatusText(selectedRun.status) }}</el-tag>
              </div>
              <div v-for="event in events" :key="event.id" class="event-line">
                <i :class="`is-${String(event.level || 'INFO').toLowerCase()}`" />
                <span>{{ formatTime(event.createdAt) }}</span>
                <b>{{ phaseText(event.phase) }}</b>
                <em>{{ event.message }}</em>
              </div>
              <div v-if="!events.length" class="muted">等待后台返回运行进度……</div>
            </div>
          </div>

          <div v-for="proposal in selectedProposals" :key="proposal.id" class="message agent-message">
            <div class="avatar agent-avatar">DS</div>
            <article class="proposal-card">
              <header>
                <div>
                  <span class="candidate-label">{{ actionText(proposal) }}</span>
                  <strong>{{ proposalTitle(proposal) }}</strong>
                </div>
                <el-tag size="mini" :type="proposalStatusType(proposal.status)">{{ proposalStatusText(proposal) }}</el-tag>
              </header>
              <a v-if="proposal.sourceUri" :href="proposal.sourceUri" target="_blank" rel="noopener noreferrer">{{ proposal.sourceUri }}</a>
              <p v-if="proposedText(proposal)" class="proposed-text">{{ proposedText(proposal) }}</p>
              <p>{{ proposal.rationale }}</p>
              <ul v-if="evidence(proposal).length">
                <li v-for="(item, index) in evidence(proposal)" :key="index">{{ item }}</li>
              </ul>
              <footer v-if="proposal.status === 'PENDING'">
                <el-button size="mini" type="primary" @click="review(proposal, true)">{{ approveText(proposal) }}</el-button>
                <el-button size="mini" @click="review(proposal, false)">拒绝</el-button>
              </footer>
            </article>
          </div>
          <div v-if="selectedRun.status === 'SUCCEEDED' && !selectedProposals.length" class="no-candidate">
            本轮没有生成维护提案，可以补充更具体的要求后再次运行。
          </div>
        </template>
        <div v-else class="conversation-empty">
          <strong>直接告诉 Harness 这次需要维护什么</strong>
          <span>{{ emptyHint }}</span>
        </div>
      </div>

      <div class="composer">
        <el-input v-model="instruction" type="textarea" :rows="3" maxlength="2000" show-word-limit
          :placeholder="inputPlaceholder" @keydown.ctrl.enter.native="send" />
        <div class="composer-actions">
          <span>Ctrl + Enter 发送；权限、审核和额度规则不会被指令覆盖。</span>
          <el-button type="primary" :loading="sending" :disabled="!instruction.trim()" @click="send">交给 Harness 处理</el-button>
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
    isKnowledge() { return this.resourceType === 'KNOWLEDGE' },
    agentTitle() { return this.isKnowledge ? '知识库维护智能体' : '长期记忆维护智能体' },
    defaultInstruction() { return this.isKnowledge ? '按照已保存的维护策略检查当前知识来源。' : '按照已保存的维护策略审校当前长期记忆。' },
    emptyHint() { return this.isKnowledge ? '例如：检查当前版本的官方更新公告，排除旧补丁和营销页面。' : '例如：检查矛盾、重复或已经失效的长期记忆，并说明修改理由。' },
    inputPlaceholder() { return this.isKnowledge ? '输入知识维护要求，例如：只查找官网最新版本公告……' : '输入记忆审校要求，例如：检查重复和互相矛盾的事实……' },
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
        this.$message.success('Harness 已收到维护要求')
      }, error => {
        this.sending = false
        this.$message.error((error && error.data && error.data.msg) || (error && error.msg) || '维护任务启动失败')
      })
    },
    review(proposal, approve) {
      Api.maintenance[approve ? 'approve' : 'reject'](this.resourceType, this.resourceId, proposal.id, () => {
        proposal.status = approve && this.isKnowledge && proposal.actionType === 'ADD_SOURCE' ? 'APPLYING' : (approve ? 'APPLIED' : 'REJECTED')
        this.$set(this.proposalStatusById, proposal.id, proposal.status)
        this.$message.success(approve ? this.approvedMessage(proposal) : '已拒绝该提案')
        this.$emit('changed')
        this.refresh()
      }, error => this.$message.error((error && error.data && error.data.msg) || (approve ? '批准失败' : '拒绝失败')))
    },
    evidence(proposal) {
      try { const value = JSON.parse(proposal.evidenceJson || '[]'); return Array.isArray(value) ? value : [] } catch (_) { return [] }
    },
    proposedText(proposal) {
      if (proposal.actionType !== 'UPDATE') return ''
      try { return JSON.parse(proposal.payloadJson || '{}').canonicalText || '' } catch (_) { return '' }
    },
    proposalTitle(proposal) { return proposal.title || proposal.targetId || '未命名对象' },
    actionText(proposal) {
      return ({ ADD_SOURCE: '新增知识来源', REMOVE_SOURCE: '淘汰旧来源', UPDATE: '修正记忆', ARCHIVE: '归档记忆', DELETE: '删除记忆' })[proposal.actionType] || proposal.actionType
    },
    approveText(proposal) {
      return ({ ADD_SOURCE: '批准并入库', REMOVE_SOURCE: '批准移除', UPDATE: '批准修正', ARCHIVE: '批准归档', DELETE: '批准删除' })[proposal.actionType] || '批准'
    },
    approvedMessage(proposal) { return proposal.actionType === 'ADD_SOURCE' ? '已批准，正在抓取并发布' : '提案已批准并应用' },
    startPolling() {
      this.stopPolling()
      this.timer = window.setInterval(() => { this.refresh(); this.loadEvents() }, 1000)
    },
    stopPolling() { if (this.timer) window.clearInterval(this.timer); this.timer = null },
    scrollToBottom() { const box = this.$refs.messages; if (box) box.scrollTop = box.scrollHeight },
    triggerText(value) { return ({ SCHEDULED: '定时维护', MANUAL: '手动维护', INTERACTIVE: '历史交互会话' })[value] || '维护任务' },
    runStatusText(value) { return ({ QUEUED: '排队中', RUNNING: '处理中', SUCCEEDED: '本轮完成', FAILED: '运行失败' })[value] || value },
    runStatusType(value) { return ({ QUEUED: 'info', RUNNING: 'warning', SUCCEEDED: 'success', FAILED: 'danger' })[value] || 'info' },
    proposalStatusText(proposal) { return ({ PENDING: '待审批', APPLYING: '执行中', APPLIED: '已应用', REJECTED: '已拒绝', FAILED: '失败' })[proposal.status] || proposal.status },
    proposalStatusType(value) { return ({ PENDING: 'warning', APPLYING: 'warning', APPLIED: 'success', REJECTED: 'info', FAILED: 'danger' })[value] || 'info' },
    phaseText(value) { return ({ DISCOVERY: this.isKnowledge ? '搜索' : '分析', PROPOSAL: '提案', SOURCE_SYNC: '入库', COMPLETE: '完成' })[value] || value },
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
.avatar { flex: 0 0 34px; width: 34px; height: 34px; border-radius: 10px; display: grid; place-items: center; color: #fff; font-size: 12px; }
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
.proposed-text { padding: 9px 11px; border-left: 3px solid #409eff; background: #f4f8ff; }
.proposal-card ul { padding-left: 20px; } .proposal-card footer { margin-top: 12px; }
.no-candidate, .conversation-empty { color: #909399; text-align: center; padding: 40px 20px; }
.conversation-empty { display: grid; gap: 8px; padding-top: 150px; }
.conversation-empty strong { color: #303133; font-size: 18px; }
.composer { padding: 14px 18px; border-top: 1px solid #e4e7ed; background: #fff; }
.composer-actions { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-top: 9px; color: #909399; font-size: 12px; }
@media (max-width: 900px) { .maintenance-workbench { grid-template-columns: 1fr; } .run-history { display: none; } }
</style>
