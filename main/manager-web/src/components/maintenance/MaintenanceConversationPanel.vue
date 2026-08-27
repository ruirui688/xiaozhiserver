<template>
  <div class="maintenance-workbench">
    <aside class="conversation-list">
      <div class="list-heading">
        <strong>维护会话</strong>
        <el-button type="text" icon="el-icon-plus" @click="createConversation">新建</el-button>
      </div>
      <el-checkbox v-model="includeArchived" class="archived-switch" @change="refreshConversations(false)">显示已隐藏会话</el-checkbox>
      <div v-for="conversation in conversations" :key="conversation.id"
        :class="['conversation-item', { active: conversation.id === selectedConversationId }]"
        @click="selectConversation(conversation)">
        <div class="conversation-title-row">
          <span>{{ conversation.title }}</span>
          <el-tag v-if="conversation.archived" size="mini" type="info">已隐藏</el-tag>
        </div>
        <small>{{ originText(conversation.originType) }} · {{ formatDate(conversation.lastMessageAt) }}</small>
        <div v-if="conversation.id === selectedConversationId" class="conversation-actions" @click.stop>
          <el-button type="text" size="mini" @click="renameConversation(conversation)">重命名</el-button>
          <el-button type="text" size="mini" @click="toggleArchive(conversation)">{{ conversation.archived ? '恢复' : '隐藏' }}</el-button>
          <el-button type="text" size="mini" class="danger-action" @click="deleteConversation(conversation)">删除</el-button>
        </div>
      </div>
      <div v-if="!conversations.length" class="empty-list">还没有维护会话</div>
    </aside>

    <section class="conversation-pane">
      <div class="pane-title">
        <strong>{{ selectedConversation ? selectedConversation.title : 'Harness 维护' }}</strong>
        <span v-if="selectedConversation">{{ originText(selectedConversation.originType) }}</span>
      </div>
      <div ref="messages" class="messages" v-loading="loading">
        <template v-if="selectedConversation">
          <div v-for="message in messages" :key="message.id" class="turn-block">
            <div v-if="message.role === 'USER' || message.role === 'SYSTEM'" class="message user-message">
              <div class="avatar">{{ message.role === 'SYSTEM' ? '自动' : '你' }}</div>
              <div class="bubble">{{ message.content }}</div>
            </div>

            <div v-if="message.runId && runFor(message.runId) && message.role !== 'ASSISTANT'" class="message agent-message">
              <div class="avatar agent-avatar">DS</div>
              <div class="bubble agent-bubble">
                <div class="agent-heading">
                  <strong>{{ agentTitle }}</strong>
                  <el-tag size="mini" :type="runStatusType(runFor(message.runId).status)">{{ runStatusText(runFor(message.runId).status) }}</el-tag>
                </div>
                <div v-for="event in eventsForRun(message.runId)" :key="event.id" class="event-line">
                  <i :class="`is-${String(event.level || 'INFO').toLowerCase()}`" />
                  <span>{{ formatTime(event.createdAt) }}</span><b>{{ phaseText(event.phase) }}</b><em>{{ event.message }}</em>
                </div>
                <div v-if="!eventsForRun(message.runId).length" class="muted">等待后台返回运行进度……</div>
              </div>
            </div>

            <div v-for="proposal in proposalsForRun(message.role === 'ASSISTANT' ? '' : message.runId)" :key="proposal.id" class="message agent-message">
              <div class="avatar agent-avatar">DS</div>
              <article class="proposal-card">
                <header>
                  <div><span class="candidate-label">{{ actionText(proposal) }}</span><strong>{{ proposalTitle(proposal) }}</strong></div>
                  <el-tag size="mini" :type="proposalStatusType(proposal.status)">{{ proposalStatusText(proposal) }}</el-tag>
                </header>
                <a v-if="proposal.sourceUri" :href="proposal.sourceUri" target="_blank" rel="noopener noreferrer">{{ proposal.sourceUri }}</a>
                <p v-if="proposedText(proposal)" class="proposed-text">{{ proposedText(proposal) }}</p>
                <p>{{ proposal.rationale }}</p>
                <ul v-if="evidence(proposal).length"><li v-for="(item, index) in evidence(proposal)" :key="index">{{ item }}</li></ul>
                <footer v-if="proposal.status === 'PENDING'">
                  <el-button size="mini" type="primary" @click="review(proposal, true)">{{ approveText(proposal) }}</el-button>
                  <el-button size="mini" @click="review(proposal, false)">拒绝</el-button>
                </footer>
              </article>
            </div>

            <div v-if="message.role === 'ASSISTANT'" class="message agent-message">
              <div class="avatar agent-avatar">DS</div><div class="bubble assistant-message">{{ message.content }}</div>
            </div>
          </div>
          <div v-if="!messages.length" class="conversation-empty">
            <strong>这是一个新的维护会话</strong><span>发送第一条要求后，后续消息会继续使用本会话的完整可见历史。</span>
          </div>
        </template>
        <div v-else class="conversation-empty">
          <strong>新建或选择一个维护会话</strong><span>定时维护、手动维护和人工会话都会出现在左侧。</span>
        </div>
      </div>

      <div class="composer">
        <el-input v-model="instruction" type="textarea" :rows="3" maxlength="8000" show-word-limit
          :disabled="!selectedConversation || Boolean(selectedConversation.archived)" :placeholder="inputPlaceholder"
          @keydown.ctrl.enter.native="send" />
        <div class="composer-actions">
          <span>{{ composerHint }}</span>
          <el-button type="primary" :loading="sending" :disabled="!canSend" @click="send">交给 Harness 处理</el-button>
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
      conversations: [], messages: [], runs: [], proposals: [], events: [], selectedConversationId: '',
      includeArchived: false, instruction: '', loading: false, sending: false,
      refreshingList: false, refreshingContent: false, timer: null, proposalStatusById: {}
    }
  },
  computed: {
    isKnowledge() { return this.resourceType === 'KNOWLEDGE' },
    agentTitle() { return this.isKnowledge ? '知识库维护智能体' : '长期记忆维护智能体' },
    inputPlaceholder() { return this.isKnowledge ? '继续当前会话，例如：只查找官网最新版本公告……' : '继续当前会话，例如：检查重复和互相矛盾的事实……' },
    selectedConversation() { return this.conversations.find(item => item.id === this.selectedConversationId) || null },
    canSend() { return Boolean(this.selectedConversation && !this.selectedConversation.archived && this.instruction.trim() && !this.sending) },
    composerHint() {
      if (this.selectedConversation && this.selectedConversation.archived) return '请先恢复该会话再继续发送。'
      return 'Ctrl + Enter 发送；本会话的可见消息会作为后续维护上下文。'
    }
  },
  watch: { active(value) { if (value) this.open(); else this.stopPolling() } },
  beforeDestroy() { this.stopPolling() },
  methods: {
    payload(res) { return (res && res.data && res.data.data) || (res && res.data) || [] },
    fail(error, fallback) { this.$message.error((error && error.data && error.data.msg) || (error && error.msg) || fallback) },
    open() { this.refreshConversations(false); this.startPolling() },
    refreshConversations(silent = true) {
      if (this.refreshingList) return
      this.refreshingList = true
      if (!silent) this.loading = true
      Api.maintenance.conversations(this.resourceType, this.resourceId, this.includeArchived, res => {
        this.refreshingList = false
        this.conversations = this.payload(res) || []
        if (!this.conversations.some(item => item.id === this.selectedConversationId)) {
          this.selectedConversationId = this.conversations.length ? this.conversations[0].id : ''
          this.clearContent()
        }
        if (this.selectedConversationId) this.refreshSelected(silent); else this.loading = false
      }, error => { this.refreshingList = false; this.loading = false; if (!silent) this.fail(error, '加载维护会话失败') })
    },
    selectConversation(conversation) {
      if (!conversation || conversation.id === this.selectedConversationId) return
      this.selectedConversationId = conversation.id; this.clearContent(); this.refreshSelected(false)
    },
    clearContent() { this.messages = []; this.runs = []; this.proposals = []; this.events = [] },
    refreshSelected(silent = true) {
      const conversationId = this.selectedConversationId
      if (!conversationId || this.refreshingContent) return
      const followLatest = !silent || this.isNearBottom()
      this.refreshingContent = true
      if (!silent) this.loading = true
      let pending = 4
      const done = () => {
        pending -= 1
        if (!pending) {
          this.refreshingContent = false; this.loading = false
          if (followLatest && conversationId === this.selectedConversationId) this.$nextTick(this.scrollToBottom)
          if (conversationId !== this.selectedConversationId) this.refreshSelected(false)
        }
      }
      const accept = handler => res => { if (conversationId === this.selectedConversationId) handler(this.payload(res) || []); done() }
      Api.maintenance.conversationMessages(this.resourceType, this.resourceId, conversationId, accept(value => { this.messages = value }), done)
      Api.maintenance.conversationRuns(this.resourceType, this.resourceId, conversationId, accept(value => { this.runs = value }), done)
      Api.maintenance.conversationEvents(this.resourceType, this.resourceId, conversationId, 0, accept(value => { this.events = value }), done)
      Api.maintenance.conversationProposals(this.resourceType, this.resourceId, conversationId, accept(value => {
        value.forEach(item => {
          const previous = this.proposalStatusById[item.id]
          if (previous === 'APPLYING' && item.status === 'APPLIED') this.$emit('changed')
          this.$set(this.proposalStatusById, item.id, item.status)
        })
        this.proposals = value
      }), done)
    },
    createConversation() {
      this.$prompt('输入会话名称', '新建维护会话', { inputValue: '新会话', inputPattern: /\S+/, inputErrorMessage: '会话名称不能为空' })
        .then(({ value }) => Api.maintenance.createConversation(this.resourceType, this.resourceId, { title: value }, res => {
          const conversation = this.payload(res); this.includeArchived = false
          this.conversations = [conversation].concat(this.conversations); this.selectConversation(conversation)
        }, error => this.fail(error, '新建会话失败'))).catch(() => {})
    },
    renameConversation(conversation) {
      this.$prompt('输入新的会话名称', '重命名会话', { inputValue: conversation.title, inputPattern: /\S+/, inputErrorMessage: '会话名称不能为空' })
        .then(({ value }) => Api.maintenance.updateConversation(this.resourceType, this.resourceId, conversation.id, { title: value }, res => {
          Object.assign(conversation, this.payload(res)); this.$message.success('会话已重命名')
        }, error => this.fail(error, '重命名失败'))).catch(() => {})
    },
    toggleArchive(conversation) {
      const restoring = Boolean(conversation.archived)
      Api.maintenance.updateConversation(this.resourceType, this.resourceId, conversation.id, { archived: !restoring }, () => {
        this.$message.success(restoring ? '会话已恢复' : '会话已隐藏'); this.refreshConversations(false)
      }, error => this.fail(error, '更新会话状态失败'))
    },
    deleteConversation(conversation) {
      this.$confirm(`删除会话“${conversation.title}”？已批准的知识或记忆变更不会回滚。`, '删除维护会话', { type: 'warning' })
        .then(() => Api.maintenance.deleteConversation(this.resourceType, this.resourceId, conversation.id, () => {
          this.$message.success('会话已删除'); this.refreshConversations(false)
        }, error => this.fail(error, '删除会话失败'))).catch(() => {})
    },
    send() {
      if (!this.canSend) return
      const instruction = this.instruction.trim(); const conversationId = this.selectedConversationId; this.sending = true
      Api.maintenance.runConversation(this.resourceType, this.resourceId, conversationId, { instruction }, () => {
        this.instruction = ''; this.sending = false; this.$message.success('Harness 已收到本会话的新消息')
        this.refreshConversations(); this.refreshSelected(false)
      }, error => { this.sending = false; this.fail(error, '维护任务启动失败') })
    },
    review(proposal, approve) {
      Api.maintenance[approve ? 'approve' : 'reject'](this.resourceType, this.resourceId, proposal.id, () => {
        proposal.status = approve && this.isKnowledge && proposal.actionType === 'ADD_SOURCE' ? 'APPLYING' : (approve ? 'APPLIED' : 'REJECTED')
        this.$set(this.proposalStatusById, proposal.id, proposal.status)
        this.$message.success(approve ? this.approvedMessage(proposal) : '已拒绝该提案'); this.$emit('changed'); this.refreshSelected()
      }, error => this.fail(error, approve ? '批准失败' : '拒绝失败'))
    },
    runFor(runId) { return this.runs.find(item => item.id === runId) || null },
    eventsForRun(runId) { return this.events.filter(item => item.runId === runId) },
    proposalsForRun(runId) { return runId ? this.proposals.filter(item => item.runId === runId) : [] },
    evidence(proposal) { try { const value = JSON.parse(proposal.evidenceJson || '[]'); return Array.isArray(value) ? value : [] } catch (_) { return [] } },
    proposedText(proposal) { if (proposal.actionType !== 'UPDATE') return ''; try { return JSON.parse(proposal.payloadJson || '{}').canonicalText || '' } catch (_) { return '' } },
    proposalTitle(proposal) { return proposal.title || proposal.targetId || '未命名对象' },
    actionText(proposal) { return ({ ADD_SOURCE: '新增知识来源', REMOVE_SOURCE: '淘汰旧来源', UPDATE: '修正记忆', ARCHIVE: '归档记忆', DELETE: '删除记忆' })[proposal.actionType] || proposal.actionType },
    approveText(proposal) { return ({ ADD_SOURCE: '批准并入库', REMOVE_SOURCE: '批准移除', UPDATE: '批准修正', ARCHIVE: '批准归档', DELETE: '批准删除' })[proposal.actionType] || '批准' },
    approvedMessage(proposal) { return proposal.actionType === 'ADD_SOURCE' ? '已批准，正在抓取并发布' : '提案已批准并应用' },
    startPolling() { this.stopPolling(); this.timer = window.setInterval(() => { this.refreshConversations(); this.refreshSelected() }, 1000) },
    stopPolling() { if (this.timer) window.clearInterval(this.timer); this.timer = null },
    isNearBottom() {
      const box = this.$refs.messages
      return !box || box.scrollHeight - box.scrollTop - box.clientHeight < 80
    },
    scrollToBottom() { const box = this.$refs.messages; if (box) box.scrollTop = box.scrollHeight },
    originText(value) { return ({ USER: '人工会话', MANUAL: '手动维护', SCHEDULED: '定时维护' })[value] || '维护会话' },
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
.maintenance-workbench { display:grid; grid-template-columns:270px minmax(0,1fr); min-height:650px; border:1px solid #e4e7ed; border-radius:12px; overflow:hidden; background:#f5f7fb; }
.conversation-list { padding:14px 10px; border-right:1px solid #e4e7ed; background:#fff; overflow:auto; }
.list-heading,.conversation-title-row,.pane-title { display:flex; justify-content:space-between; align-items:center; gap:8px; }
.list-heading { padding:0 10px 8px; }.archived-switch { padding:0 10px 12px; }
.conversation-item { border-radius:9px; padding:10px; margin-bottom:6px; cursor:pointer; color:#303133; }
.conversation-item:hover,.conversation-item.active { background:#ecf5ff; color:#2768d7; }
.conversation-title-row span { overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }.conversation-item small { display:block; margin-top:5px; color:#909399; }
.conversation-actions { display:flex; gap:8px; margin-top:4px; }.conversation-actions .el-button { margin:0; }.danger-action { color:#f56c6c!important; }.empty-list { padding:35px 10px; text-align:center; color:#909399; }
.conversation-pane { display:grid; grid-template-rows:auto minmax(0,1fr) auto; min-width:0; }.pane-title { min-height:42px; padding:0 20px; border-bottom:1px solid #e4e7ed; background:#fff; }.pane-title span { color:#909399; font-size:12px; }
.messages { height:480px; overflow:auto; padding:22px; }.turn-block { margin-bottom:4px; }.message { display:flex; gap:10px; margin-bottom:16px; }.user-message { justify-content:flex-end; }.user-message .avatar { order:2; background:#409eff; }
.avatar { flex:0 0 38px; width:38px; height:38px; border-radius:10px; display:grid; place-items:center; color:#fff; font-size:12px; }.agent-avatar { background:linear-gradient(135deg,#5b7cff,#8b5cf6); }
.bubble { max-width:78%; padding:12px 14px; border-radius:12px 4px 12px 12px; background:#409eff; color:#fff; line-height:1.65; white-space:pre-wrap; overflow-wrap:anywhere; }
.agent-bubble,.assistant-message { width:min(760px,88%); max-width:none; border-radius:4px 12px 12px; background:#fff; color:#303133; box-shadow:0 2px 10px rgba(31,45,61,.06); }.agent-heading { display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; }
.event-line { display:grid; grid-template-columns:8px 68px 45px minmax(0,1fr); gap:7px; align-items:center; min-height:28px; font-size:12px; }.event-line i { width:7px; height:7px; border-radius:50%; background:#409eff; }.event-line i.is-success { background:#67c23a; }.event-line i.is-error { background:#f56c6c; }.event-line span,.muted { color:#909399; }.event-line b { color:#606266; }.event-line em { font-style:normal; }
.proposal-card { width:min(760px,88%); padding:14px 16px; border:1px solid #dfe7f5; border-radius:4px 12px 12px; background:#fff; }.proposal-card header { display:flex; justify-content:space-between; gap:12px; }.proposal-card header strong { display:block; margin-top:5px; }.candidate-label { color:#7a8aa0; font-size:12px; }.proposal-card a { display:block; margin:9px 0; color:#409eff; overflow-wrap:anywhere; }.proposal-card p,.proposal-card li { color:#606266; line-height:1.6; }.proposed-text { padding:9px 11px; border-left:3px solid #409eff; background:#f4f8ff; }.proposal-card ul { padding-left:20px; }.proposal-card footer { margin-top:12px; }
.conversation-empty { display:grid; gap:8px; padding:150px 20px 40px; text-align:center; color:#909399; }.conversation-empty strong { color:#303133; font-size:18px; }.composer { padding:14px 18px; border-top:1px solid #e4e7ed; background:#fff; }.composer-actions { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-top:9px; color:#909399; font-size:12px; }
@media (max-width:900px) { .maintenance-workbench { grid-template-columns:1fr; }.conversation-list { max-height:210px; border-right:0; border-bottom:1px solid #e4e7ed; }.messages { height:410px; } }
</style>
