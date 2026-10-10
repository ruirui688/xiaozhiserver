<template>
  <div class="welcome">
    <HeaderBar />

    <!-- Operation bar -->
    <div class="operation-bar">
      <div>
        <h2 class="page-title">记忆管理</h2>
        <div class="page-subtitle">{{ agentName || agentId }}</div>
      </div>
      <div class="right-operations">
        <el-select v-if="memoryScope === 'DEVICE'" v-model="rebuildDeviceId" size="small" placeholder="选择重建设备" :disabled="maintenanceBusy || !canWrite" style="width:170px">
          <el-option v-for="device in memoryDevices" :key="device.deviceId" :label="device.label" :value="device.deviceId" />
        </el-select>
        <el-input-number
          v-model="rebuildSessionLimit"
          size="small"
          :min="1"
          :max="50"
          :controls="false"
          :disabled="maintenanceBusy || !canWrite"
          class="session-limit-input"
        />
        <el-button size="small" icon="el-icon-view" :loading="rebuildLoading && maintenanceOperation === '预览重建'" :disabled="maintenanceBusy || !canWrite" @click="handleRebuildPreview">预览重建</el-button>
        <el-button size="small" type="primary" icon="el-icon-magic-stick" :loading="rebuildLoading || serverRebuildActive" :disabled="maintenanceBusy || !canWrite" @click="handleRebuild">重建记忆</el-button>
        <el-button size="small" icon="el-icon-view" :loading="reviewLoading && maintenanceOperation === '预览重审'" :disabled="maintenanceBusy || !canWrite" @click="handleReviewPreview">预览重审</el-button>
        <el-button size="small" type="primary" icon="el-icon-refresh" :loading="reviewLoading && maintenanceOperation === '重审记忆'" :disabled="maintenanceBusy || !canWrite" @click="handleReview">重审记忆</el-button>
        <el-button size="small" type="danger" icon="el-icon-delete" :loading="clearLoading" :disabled="maintenanceBusy || !canWrite" @click="handleClear">清空记忆</el-button>
        <el-button size="small" icon="el-icon-edit" :disabled="maintenanceBusy || !canWrite" @click="handleRemember">记住</el-button>
        <el-button size="small" icon="el-icon-delete" :disabled="maintenanceBusy || !canWrite" @click="showForget = !showForget">忘记</el-button>
        <el-button size="small" icon="el-icon-refresh" @click="fetch" :loading="loading">刷新</el-button>
        <el-button size="small" icon="el-icon-back" @click="$router.push('/')">返回</el-button>
      </div>
    </div>

    <el-alert
      v-if="maintenanceBusy || rebuildTask"
      class="maintenance-alert"
      :title="maintenanceStatusText"
      :type="maintenanceAlertType"
      :closable="false"
      show-icon
    />
    <el-progress
      v-if="serverRebuildActive"
      class="maintenance-progress"
      :percentage="rebuildTask.progress"
      :stroke-width="8"
      :show-text="false"
    />

    <transition name="slide-down">
      <div class="quick-action-bar" v-if="showForget">
        <el-form label-width="80px" size="small" :disabled="maintenanceBusy || !canWrite">
          <MemoryAttributionFields v-model="forgetAttribution" :memory-scope="memoryScope" :devices="memoryDevices" :speakers="memorySpeakers" :disabled="maintenanceBusy || !canWrite" />
          <el-form-item label="忘记内容">
            <el-input v-model="forgetText" type="textarea" :rows="2" placeholder="描述要停止使用并避免再次学习的信息" />
            <p class="action-hint">遗忘会处理选定范围的事实并记录禁止再次学习的规则。原始聊天和审计记录不会被物理擦除。</p>
          </el-form-item>
          <el-form-item><el-button type="danger" :loading="forgetLoading" @click="handleForget">确认忘记</el-button></el-form-item>
        </el-form>
      </div>
    </transition>

    <MemoryOverview :facts="listedFacts" :candidates="candidates" :tombstones="activeTombstones"
      :policy="memoryPolicy" :loading="loading" :now="visualNow" @navigate="activeTab = $event" />

    <!-- Main content area with tabs -->
    <div class="main-wrapper">
      <div class="content-panel">
        <div class="content-area">
          <el-card class="params-card" shadow="never">
            <el-tabs v-model="activeTab" type="border-card" @tab-click="handleTabClick">
              <el-tab-pane label="记忆策略" name="policy">
                <MemoryPolicyPanel :agent-id="agentId" :busy="maintenanceBusy" @loaded="memoryPolicy = $event" @saved="fetch" />
              </el-tab-pane>
              <el-tab-pane label="会话整理任务" name="session-tasks" lazy>
                <MemorySessionTasks :agent-id="agentId" :can-write="canWrite" :busy="maintenanceBusy" />
              </el-tab-pane>
              <el-tab-pane name="list">
                <span slot="label">
                  记忆列表
                  <el-tag size="mini" type="info" style="margin-left:4px">{{ activeFacts.length }}</el-tag>
                </span>
                <MemoryList
                  :facts="listedFacts"
                  :policy="memoryPolicy"
                  :now="visualNow"
                  :can-write="canWrite && !maintenanceBusy"
                  :loading="loading"
                  @open-detail="handleOpenDetail"
                  @edit="handleEdit"
                  @archive="handleArchive"
                  @delete="handleDelete"
                  @batch-archive="handleBatchArchive"
                  @batch-delete="handleBatchDelete"
                  @refresh="fetch"
                />
              </el-tab-pane>

              <el-tab-pane name="graph">
                <span slot="label">图谱视图</span>
                <MemoryGraph
                  :facts="listedFacts"
                  :tombstones="activeTombstones"
                  :agentName="agentName"
                  :agent-id="agentId"
                  :policy="memoryPolicy"
                  :active="activeTab === 'graph'"
                  :now="visualNow"
                  :loading="loading"
                  @open-detail="handleOpenDetail"
                />
              </el-tab-pane>

              <el-tab-pane name="recall">
                <span slot="label">实际注入预览</span>
                <MemoryRecallTest :agent-id="agentId" :policy="memoryPolicy" :revision="memoryRevision" />
              </el-tab-pane>

              <el-tab-pane name="timeline">
                <span slot="label">
                  时间线
                  <el-tag v-if="dashboard.revisions && dashboard.revisions.length" size="mini" type="info" style="margin-left:4px">{{ dashboard.revisions.length }}</el-tag>
                </span>
                <MemoryTimeline
                  :revisions="dashboard.revisions || []"
                  :facts="listedFacts"
                  :loading="loading"
                  @open-detail="handleOpenDetail"
                />
              </el-tab-pane>

              <el-tab-pane name="candidates">
                <span slot="label">
                  候选记忆
                  <el-tag v-if="candidates.length" size="mini" type="warning" style="margin-left:4px">{{ candidates.length }}</el-tag>
                </span>
                <MemoryCandidates
                  :can-write="canWrite && !maintenanceBusy"
                  :candidates="candidates"
                  :loading="loading"
                  @confirm="handleConfirm"
                  @reject="handleReject"
                />
              </el-tab-pane>
            </el-tabs>
          </el-card>
        </div>
      </div>
    </div>

    <!-- Detail panel -->
    <MemoryDetailPanel
      :can-write="canWrite && !maintenanceBusy"
      :visible="detailVisible"
      :memory="detailItem"
      :item-type="detailType"
      :policy="memoryPolicy"
      :now="visualNow"
      @close="detailVisible = false"
      @edit="handleEdit"
      @archive="handleArchive"
      @delete="handleDelete"
    />

    <!-- Edit dialog -->
    <MemoryEditDialog
      :can-write="canWrite"
      :saving="rememberLoading"
      :memory-scope="memoryScope"
      :devices="memoryDevices"
      :speakers="memorySpeakers"
      :visible.sync="editVisible"
      :memory="editItem"
      :memory-id="editMemoryId"
      :existing-types="existingTypes"
      :existing-categories="categories"
      @save="handleSaveEdit"
      @cancel="editVisible = false"
    />

    <el-dialog
      :title="previewDialogTitle"
      :visible.sync="rebuildPreviewVisible"
      width="860px"
      append-to-body
      :close-on-click-modal="!maintenanceBusy"
      :close-on-press-escape="!maintenanceBusy"
    >
      <div class="rebuild-summary" v-if="rebuildPreviewResult">
        <el-tag size="mini" type="info">会话 {{ rebuildPreviewResult.sessionsRead || 0 }}</el-tag>
        <el-tag size="mini" type="warning">动作 {{ previewActionsProposed }}</el-tag>
        <el-tag v-if="previewMode === 'rebuild'" size="mini">最近 {{ rebuildPreviewResult.sessionLimit || rebuildSessionLimit }} 个</el-tag>
        <el-tag v-else size="mini">通用生命周期</el-tag>
      </div>
      <el-table
        :data="previewCandidates"
        size="small"
        max-height="420"
        empty-text="维护模型未返回需要应用的记忆动作"
      >
        <el-table-column label="动作" width="90">
          <template slot-scope="{row}">{{ candidateAction(row) }}</template>
        </el-table-column>
        <el-table-column label="内容" prop="canonicalText" min-width="280" show-overflow-tooltip />
        <el-table-column label="分类" width="120">
          <template slot-scope="{row}">{{ row.category || '未分类' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template slot-scope="{row}">{{ row.memoryType || '长期记忆' }}</template>
        </el-table-column>
        <el-table-column label="置信度" width="90">
          <template slot-scope="{row}">{{ pct(row.confidence) }}%</template>
        </el-table-column>
        <el-table-column label="重要性" width="90">
          <template slot-scope="{row}">{{ pct(row.importance) }}%</template>
        </el-table-column>
      </el-table>
      <span slot="footer">
        <el-button size="small" @click="rebuildPreviewVisible = false">关闭</el-button>
        <el-button v-if="previewMode === 'rebuild'" size="small" type="primary" :loading="rebuildLoading && maintenanceOperation === '重建记忆'" :disabled="maintenanceBusy || !canWrite" @click="handleRebuild">按此配置重建</el-button>
        <el-button v-else size="small" type="primary" :loading="reviewLoading && maintenanceOperation === '重审记忆'" :disabled="maintenanceBusy || !canWrite" @click="handleReview">执行重审</el-button>
      </span>
    </el-dialog>

    <el-footer><VersionFooter /></el-footer>
  </div>
</template>

<script>
import HeaderBar from '@/components/HeaderBar.vue'
import VersionFooter from '@/components/VersionFooter.vue'
import MemoryList from './memory/MemoryList.vue'
import MemoryGraph from './memory/MemoryGraph.vue'
import MemoryRecallTest from './memory/MemoryRecallTest.vue'
import MemoryTimeline from './memory/MemoryTimeline.vue'
import MemoryCandidates from './memory/MemoryCandidates.vue'
import MemoryDetailPanel from './memory/MemoryDetailPanel.vue'
import MemoryEditDialog from './memory/MemoryEditDialog.vue'
import MemoryPolicyPanel from './memory/MemoryPolicyPanel.vue'
import MemoryAttributionFields from './memory/MemoryAttributionFields.vue'
import MemorySessionTasks from './memory/MemorySessionTasks.vue'
import MemoryOverview from './memory/MemoryOverview.vue'
import { memoryStatus, memoryIdentity } from './memory/memoryVisualState.mjs'
import { attributionOf, attributionError } from './memory/memoryPolicy.mjs'
import Api from '@/apis/api'
import {
  canRecoverRebuildTask,
  isActiveRebuildTask,
  normalizeRebuildTask,
  rebuildTaskAlertType,
  rebuildTaskPollDelay,
  rebuildTaskStatusText
} from './memory/memoryRebuildTaskState.mjs'

export default {
  name: 'MemoryManagement',
  components: {
    HeaderBar,
    VersionFooter,
    MemoryList,
    MemoryGraph,
    MemoryRecallTest,
    MemoryTimeline,
    MemoryCandidates,
    MemoryDetailPanel,
    MemoryEditDialog,
    MemoryPolicyPanel,
    MemoryAttributionFields,
    MemorySessionTasks,
    MemoryOverview
  },
  data() {
    return {
      agentId: '',
      agentName: '',
      loading: false,
      dashboard: {},
      memoryPolicy: null,
      memoryRevision: 0,
      visualNow: Date.now(),
      visualClock: null,
      forgetAttribution: attributionOf(),
      activeTab: 'list',
      // Quick remember/forget
      showForget: false,
      forgetText: '',
      rememberLoading: false,
      forgetLoading: false,
      // Detail panel
      detailVisible: false,
      detailItem: {},
      detailType: 'fact',
      // Edit dialog
      editVisible: false,
      editItem: {},
      editMemoryId: '',
      // Rebuild
      rebuildSessionLimit: 15,
      rebuildDeviceId: '',
      rebuildMode: 'clear_then_rebuild',
      rebuildLoading: false,
      reviewLoading: false,
      clearLoading: false,
      rebuildPreviewVisible: false,
      rebuildPreviewResult: null,
      previewMode: 'rebuild',
      maintenanceOperation: '',
      maintenanceRecoveryPending: false,
      maintenanceRecoveryTimer: null,
      rebuildTask: null,
      rebuildTaskPollTimer: null,
      rebuildTaskPollFailures: 0,
      notifiedRebuildTaskId: ''
    }
  },
  computed: {
    listedFacts() { return (this.dashboard.facts || []).filter(f => f.status !== 'deleted') },
    activeFacts() { return this.listedFacts.filter(f => memoryStatus(f, this.visualNow) === 'active') },
    canWrite() { return Boolean(this.memoryPolicy && this.memoryPolicy.canWrite === true) },
    memoryScope() { return this.memoryPolicy && this.memoryPolicy.memoryScope || 'AGENT' },
    memoryDevices() { return this.memoryPolicy && this.memoryPolicy.devices || [] },
    memorySpeakers() { return this.memoryPolicy && this.memoryPolicy.speakers || [] },
    activeTombstones() {
      return (this.dashboard.tombstones || []).filter(t => t.status === 'active')
    },
    candidates() {
      return (this.dashboard.candidates || []).filter(c => c.status === 'pending')
    },
    categories() {
      return [...new Set(this.activeFacts.map(f => f.category || '未分类'))]
    },
    existingTypes() {
      return [...new Set(this.activeFacts.map(f => f.memoryType).filter(Boolean))]
    },
    previewCandidates() {
      return ((this.rebuildPreviewResult && this.rebuildPreviewResult.candidates) || [])
        .map(this.decorateMemoryIdentity)
    },
    previewDialogTitle() {
      return this.previewMode === 'review' ? '重审预览' : '重建预览'
    },
    previewActionsProposed() {
      const result = this.rebuildPreviewResult || {}
      if (result.actionsProposed !== undefined && result.actionsProposed !== null) {
        return this.toCount(result.actionsProposed)
      }
      return this.previewCandidates.length
    },
    maintenanceBusy() {
      return this.rebuildLoading || this.reviewLoading || this.clearLoading ||
        this.rememberLoading || this.forgetLoading || this.maintenanceRecoveryPending ||
        this.serverRebuildActive
    },
    serverRebuildActive() {
      return isActiveRebuildTask(this.rebuildTask)
    },
    maintenanceAlertType() {
      if (this.maintenanceRecoveryPending) return 'warning'
      return this.rebuildTask ? rebuildTaskAlertType(this.rebuildTask) : 'info'
    },
    maintenanceStatusText() {
      if (this.rebuildTask) {
        return rebuildTaskStatusText(this.rebuildTask)
      }
      if (this.maintenanceRecoveryPending) {
        return '请求结果尚未确认，后台可能仍在执行。维护操作已暂时锁定，页面将在稍后自动刷新；也可点击“刷新”确认最新状态。'
      }
      if (this.maintenanceOperation === '重建记忆' || this.maintenanceOperation === '重审记忆') {
        return `${this.maintenanceOperation}：维护模型处理中，完成前不会替换旧记忆，请勿重复提交。`
      }
      return `${this.maintenanceOperation || '记忆维护'}处理中，请勿重复提交。`
    }
  },
  mounted() {
    this.agentId = this.$route.query.agentId || ''
    this.agentName = this.$route.query.agentName || ''
    if (!this.agentId) {
      this.$message.error('缺少智能体ID')
      this.$router.push('/')
      return
    }
    this.fetch()
    this.restoreRebuildTask()
    this.visualClock = setInterval(() => { this.visualNow = Date.now() }, 30000)
  },
  beforeDestroy() {
    clearInterval(this.visualClock)
    if (this.maintenanceRecoveryTimer) {
      clearTimeout(this.maintenanceRecoveryTimer)
      this.maintenanceRecoveryTimer = null
    }
    this.stopRebuildTaskPolling()
  },
  methods: {
    fetch() {
      this.visualNow = Date.now()
      this.memoryRevision += 1
      this.loading = true
      Api.memory.dashboard(this.agentId, (res) => {
        const data = (res && res.data && res.data.data) ? res.data.data : (res && res.data) || this.dashboard
        this.dashboard = {
          ...data,
          facts: (data.facts || []).map(this.decorateMemoryIdentity),
          candidates: (data.candidates || []).map(this.decorateMemoryIdentity)
        }
        this.loading = false
        this.completeMaintenanceRecovery()
      }, (error) => {
        this.loading = false
        this.$message.error(this.requestErrorMessage(error, '刷新记忆列表失败，请稍后重试'))
      }, (error) => {
        this.loading = false
        this.$message.error(this.requestErrorMessage(error, '刷新记忆列表失败，请稍后重试'))
      })
    },

    unwrapResult(res) {
      return (res && res.data && res.data.data) ? res.data.data : (res && res.data) || {}
    },

    decorateMemoryIdentity(item) {
      const identity = memoryIdentity(item, this.memoryPolicy)
      return {
        ...item,
        speakerId: identity.speakerId,
        speakerDisplay: identity.speakerLabel
      }
    },

    handleTabClick() {
      this.detailVisible = false
    },

    // --- Detail panel ---
    handleOpenDetail(item, type) {
      if (!item) return
      this.detailItem = item
      this.detailType = item.tombstoneId || type === 'tombstone' || type === 'tomb' ? 'tomb' : item.candidateId ? 'candidate' : 'fact'
      this.detailVisible = true
    },

    // --- Edit ---
    handleEdit(item) {
      if (!this.canWrite || this.maintenanceBusy) return
      this.editItem = item
      this.editMemoryId = item.memoryId || ''
      this.editVisible = true
    },
    handleSaveEdit(memoryId, formData) {
      if (!this.beginMaintenance(memoryId ? '编辑记忆' : '记住', 'rememberLoading')) return
      const done = () => {
        this.finishMaintenance('rememberLoading')
        this.$message.success('记忆已保存')
        this.editVisible = false
        this.detailVisible = false
        this.fetch()
      }
      const uncertain = error => this.handleUncertainMaintenanceFailure('保存记忆', 'rememberLoading', error)
      const failed = error => this.handleMaintenanceFailure('保存记忆', 'rememberLoading', error)
      if (memoryId) Api.memory.updateFact(this.agentId, memoryId, formData, done, uncertain, failed)
      else Api.memory.createFact(this.agentId, formData, done, uncertain, failed)
    },

    // --- Archive ---
    handleArchive(item) {
      if (!this.canWrite || this.maintenanceBusy) return
      this.$confirm('确定归档这条记忆吗？归档后停止用于对话，事实与审计记录仍保留。', '提示', { type: 'warning' }).then(() => {
        Api.memory.archiveFact(this.agentId, item.memoryId, () => {
          this.$message.success('已归档')
          this.detailVisible = false
          this.fetch()
        })
      }).catch(() => {})
    },

    // --- Delete ---
    handleDelete(item) {
      if (!this.canWrite || this.maintenanceBusy) return
      this.$confirm('确定删除这条记忆吗？它将停止用于对话，并阻止相同事实再次写入。原始聊天与审计仍保留；如需忘记整个主题，请使用“忘记”。', '警告', { type: 'warning', confirmButtonClass: 'el-button--danger' }).then(() => {
        Api.memory.deleteFact(this.agentId, item.memoryId, () => {
          this.$message.success('已删除')
          this.detailVisible = false
          this.fetch()
        })
      }).catch(() => {})
    },

    // --- Batch operations ---
    handleBatchArchive(facts) {
      if (!this.canWrite || this.maintenanceBusy) return
      if (!facts.length) return
      this.$confirm(`确定归档选中的 ${facts.length} 条记忆吗？`, '批量归档', { type: 'warning' }).then(() => {
        let done = 0
        const total = facts.length
        facts.forEach(f => {
          Api.memory.archiveFact(this.agentId, f.memoryId, () => {
            done++
            if (done >= total) { this.$message.success(`已归档 ${total} 条记忆`); this.fetch() }
          })
        })
      }).catch(() => {})
    },
    handleBatchDelete(facts) {
      if (!this.canWrite || this.maintenanceBusy) return
      if (!facts.length) return
      this.$confirm(`确定删除选中的 ${facts.length} 条记忆吗？停止用于对话后，原始聊天与审计记录仍保留。`, '批量删除', { type: 'warning', confirmButtonClass: 'el-button--danger' }).then(() => {
        let done = 0
        const total = facts.length
        facts.forEach(f => {
          Api.memory.deleteFact(this.agentId, f.memoryId, () => {
            done++
            if (done >= total) { this.$message.success(`已删除 ${total} 条记忆`); this.fetch() }
          })
        })
      }).catch(() => {})
    },

    // --- Candidates ---
    handleConfirm(candidate) {
      if (!this.canWrite || this.maintenanceBusy) return
      Api.memory.confirmCandidate(this.agentId, candidate.candidateId, () => {
        this.$message.success('已确认候选记忆')
        this.fetch()
      })
    },
    handleReject(candidate) {
      if (!this.canWrite || this.maintenanceBusy) return
      Api.memory.rejectCandidate(this.agentId, candidate.candidateId, () => {
        this.$message.success('已拒绝候选记忆')
        this.fetch()
      })
    },

    // --- Clear / rebuild ---
    rebuildPayload(dryRun) {
      return {
        sessionLimit: this.rebuildSessionLimit,
        mode: this.rebuildMode,
        dryRun,
        ...(this.memoryScope === 'DEVICE' ? { deviceId: this.rebuildDeviceId } : {})
      }
    },
    validateRebuildDevice() {
      if (this.memoryScope !== 'DEVICE' || this.memoryDevices.some(device => device.deviceId === this.rebuildDeviceId)) return true
      this.$message.warning('设备隔离模式下，请先选择要重建记忆的设备')
      return false
    },
    toCount(value) {
      const number = Number(value)
      return Number.isFinite(number) && number > 0 ? Math.floor(number) : 0
    },
    requestErrorMessage(error, fallback) {
      return (error && error.data && error.data.msg) ||
        (error && error.message) || fallback
    },
    restoreRebuildTask() {
      Api.memory.latestRebuildTask(this.agentId, (res) => {
        const task = this.unwrapResult(res)
        if (task && task.taskId) {
          this.applyRebuildTask(task, false)
        }
      }, () => {}, () => {})
    },
    applyRebuildTask(task, notifyCompletion) {
      const normalized = normalizeRebuildTask(task)
      if (!normalized) return
      this.rebuildTask = normalized
      this.rebuildLoading = false
      if (isActiveRebuildTask(normalized)) {
        this.maintenanceOperation = '重建记忆'
        this.scheduleRebuildTaskPoll(2000)
        return
      }
      this.stopRebuildTaskPolling()
      this.maintenanceOperation = ''
      this.rebuildPreviewVisible = false
      if (notifyCompletion && this.notifiedRebuildTaskId !== normalized.taskId) {
        this.notifiedRebuildTaskId = normalized.taskId
        if (normalized.status === 'SUCCEEDED') {
          this.showRebuildOutcome(normalized.result || {})
        } else {
          this.$message.error(rebuildTaskStatusText(normalized))
        }
      }
      this.fetch()
    },
    scheduleRebuildTaskPoll(delay) {
      if (!this.serverRebuildActive || this.rebuildTaskPollTimer) return
      this.rebuildTaskPollTimer = setTimeout(() => {
        this.rebuildTaskPollTimer = null
        this.pollRebuildTask()
      }, delay)
    },
    pollRebuildTask() {
      if (!this.serverRebuildActive || !this.rebuildTask.taskId) return
      Api.memory.rebuildTask(this.agentId, this.rebuildTask.taskId, (res) => {
        this.rebuildTaskPollFailures = 0
        this.applyRebuildTask(this.unwrapResult(res), true)
      }, () => {
        this.rebuildTaskPollFailures += 1
        this.scheduleRebuildTaskPoll(rebuildTaskPollDelay(this.rebuildTaskPollFailures))
      }, () => {
        this.rebuildTaskPollFailures += 1
        this.scheduleRebuildTaskPoll(rebuildTaskPollDelay(this.rebuildTaskPollFailures))
      })
    },
    stopRebuildTaskPolling() {
      if (this.rebuildTaskPollTimer) {
        clearTimeout(this.rebuildTaskPollTimer)
        this.rebuildTaskPollTimer = null
      }
    },
    recoverRebuildStart(error) {
      Api.memory.latestRebuildTask(this.agentId, (res) => {
        const task = this.unwrapResult(res)
        if (canRecoverRebuildTask(task)) {
          this.applyRebuildTask(task, false)
          this.$message.warning('启动响应中断，但服务端重建任务仍在执行，已恢复任务状态')
          return
        }
        this.handleMaintenanceFailure('重建记忆', 'rebuildLoading', error)
      }, () => {
        this.handleUncertainMaintenanceFailure('重建记忆', 'rebuildLoading', error)
      }, () => {
        this.handleMaintenanceFailure('重建记忆', 'rebuildLoading', error)
      })
    },
    beginMaintenance(operation, loadingKey) {
      if (!this.canWrite) return false
      if (this.maintenanceBusy) {
        this.$message.warning('已有记忆维护任务正在处理，请等待完成或刷新确认状态')
        return false
      }
      this.maintenanceOperation = operation
      this[loadingKey] = true
      return true
    },
    finishMaintenance(loadingKey) {
      this[loadingKey] = false
      if (!this.maintenanceRecoveryPending) {
        this.maintenanceOperation = ''
      }
    },
    handlePreviewFailure(operation, loadingKey, error) {
      this.finishMaintenance(loadingKey)
      this.$message.error(this.requestErrorMessage(error, `${operation}失败，请稍后重试`))
    },
    handleMaintenanceFailure(operation, loadingKey, error) {
      this.finishMaintenance(loadingKey)
      this.$message.error(this.requestErrorMessage(error, `${operation}失败，原有记忆未被替换`))
      this.fetch()
    },
    handleUncertainMaintenanceFailure(operation, loadingKey, error) {
      this[loadingKey] = false
      this.maintenanceOperation = operation
      this.maintenanceRecoveryPending = true
      const reason = this.requestErrorMessage(error, '网络中断或请求超时')
      this.$message({
        type: 'warning',
        duration: 10000,
        message: `${operation}未取得可确认结果：${reason}。后台可能仍在执行，请勿立即重复提交；页面将在 30 秒后自动刷新。`
      })
      if (this.maintenanceRecoveryTimer) {
        clearTimeout(this.maintenanceRecoveryTimer)
      }
      this.maintenanceRecoveryTimer = setTimeout(() => {
        this.maintenanceRecoveryTimer = null
        this.fetch()
      }, 30000)
    },
    completeMaintenanceRecovery() {
      if (!this.maintenanceRecoveryPending) return
      this.maintenanceRecoveryPending = false
      this.maintenanceOperation = ''
      if (this.maintenanceRecoveryTimer) {
        clearTimeout(this.maintenanceRecoveryTimer)
        this.maintenanceRecoveryTimer = null
      }
      this.$message.info('已刷新记忆列表，请根据当前数据确认维护结果')
    },
    showRebuildOutcome(data) {
      const sessionsRead = this.toCount(data.sessionsRead)
      const actionsProposed = this.toCount(data.actionsProposed)
      const factsChanged = this.toCount(data.factsChanged)
      if (sessionsRead === 0) {
        this.$message.warning('重建结束：未读取到可处理会话，当前记忆未变更')
      } else if (actionsProposed === 0) {
        this.$message.warning(`重建结束：读取 ${sessionsRead} 个会话，维护模型未提出记忆动作，当前记忆未变更`)
      } else if (factsChanged === 0) {
        this.$message.warning(`重建结束：读取 ${sessionsRead} 个会话，提出 ${actionsProposed} 条动作，但未产生事实变更`)
      } else {
        this.$message.success(`重建完成：读取 ${sessionsRead} 个会话，提出 ${actionsProposed} 条动作，实际变更 ${factsChanged} 条事实`)
      }
    },
    showReviewOutcome(data) {
      const actionsProposed = this.toCount(data.actionsProposed)
      const factsChanged = this.toCount(data.factsChanged)
      if (actionsProposed === 0) {
        this.$message.warning('重审结束：维护模型未提出记忆动作，当前记忆未变更')
      } else if (factsChanged === 0) {
        this.$message.warning(`重审结束：提出 ${actionsProposed} 条动作，但未产生事实变更`)
      } else {
        this.$message.success(`重审完成：提出 ${actionsProposed} 条动作，实际变更 ${factsChanged} 条事实`)
      }
    },
    handleClear() {
      this.$confirm('确定清空当前智能体的事实和候选记忆吗？现有遗忘规则会保留，并阻止被清除的相同事实重新写入。原始聊天与审计记录仍保留。', '清空记忆', {
        type: 'warning',
        confirmButtonClass: 'el-button--danger'
      }).then(() => {
        if (!this.beginMaintenance('清空记忆', 'clearLoading')) return
        Api.memory.clear(this.agentId, (res) => {
          const data = this.unwrapResult(res)
          this.$message.success(`已清空 ${data.factsCleared || 0} 条事实记忆`)
          this.finishMaintenance('clearLoading')
          this.fetch()
        }, (error) => {
          this.handleUncertainMaintenanceFailure('清空记忆', 'clearLoading', error)
        }, (error) => {
          this.handleMaintenanceFailure('清空记忆', 'clearLoading', error)
        })
      }).catch(() => {
        this.clearLoading = false
      })
    },
    handleRebuildPreview() {
      if (!this.validateRebuildDevice()) return
      if (!this.beginMaintenance('预览重建', 'rebuildLoading')) return
      this.previewMode = 'rebuild'
      Api.memory.rebuildPreview(this.agentId, this.rebuildPayload(true), (res) => {
        this.rebuildPreviewResult = this.unwrapResult(res)
        this.rebuildPreviewVisible = true
        this.finishMaintenance('rebuildLoading')
      }, (error) => {
        this.handlePreviewFailure('预览重建', 'rebuildLoading', error)
      }, (error) => {
        this.handlePreviewFailure('预览重建', 'rebuildLoading', error)
      })
    },
    handleRebuild() {
      if (!this.validateRebuildDevice()) return
      this.$confirm(`确定按最近 ${this.rebuildSessionLimit} 个会话重新生成记忆吗？维护模型成功返回动作后才会替换当前记忆。`, '重建记忆', {
        type: 'warning',
        confirmButtonClass: 'el-button--primary'
      }).then(() => {
        if (!this.beginMaintenance('重建记忆', 'rebuildLoading')) return
        Api.memory.rebuild(this.agentId, this.rebuildPayload(false), (res) => {
          this.applyRebuildTask(this.unwrapResult(res), false)
        }, (error) => {
          this.recoverRebuildStart(error)
        }, (error) => {
          this.handleMaintenanceFailure('重建记忆', 'rebuildLoading', error)
        })
      }).catch(() => {
        this.rebuildLoading = false
      })
    },
    handleReviewPreview() {
      if (!this.beginMaintenance('预览重审', 'reviewLoading')) return
      this.previewMode = 'review'
      Api.memory.reviewPreview(this.agentId, (res) => {
        this.rebuildPreviewResult = this.unwrapResult(res)
        this.rebuildPreviewVisible = true
        this.finishMaintenance('reviewLoading')
      }, (error) => {
        this.handlePreviewFailure('预览重审', 'reviewLoading', error)
      }, (error) => {
        this.handlePreviewFailure('预览重审', 'reviewLoading', error)
      })
    },
    handleReview() {
      this.$confirm('确定让维护模型按通用生命周期原则重审当前生效记忆吗？不会使用业务关键词规则。', '重审记忆', {
        type: 'warning',
        confirmButtonClass: 'el-button--primary'
      }).then(() => {
        if (!this.beginMaintenance('重审记忆', 'reviewLoading')) return
        Api.memory.review(this.agentId, (res) => {
          const data = this.unwrapResult(res)
          this.showReviewOutcome(data)
          this.finishMaintenance('reviewLoading')
          this.rebuildPreviewVisible = false
          this.fetch()
        }, (error) => {
          this.handleUncertainMaintenanceFailure('重审记忆', 'reviewLoading', error)
        }, (error) => {
          this.handleMaintenanceFailure('重审记忆', 'reviewLoading', error)
        })
      }).catch(() => {
        this.reviewLoading = false
      })
    },
    candidateAction(row) {
      try {
        const payload = JSON.parse(row.payload || '{}')
        return payload.action || 'ADD'
      } catch (e) {
        return 'ADD'
      }
    },
    pct(v) {
      return Math.round((Number(v) || 0) * 100)
    },

    // --- Quick remember/forget ---
    handleRemember() {
      if (!this.canWrite || this.maintenanceBusy) return
      this.editMemoryId = ''
      this.editItem = { canonicalText: '', category: '手动记录', memoryType: '长期记忆' }
      this.editVisible = true
    },
    handleForget() {
      if (!this.forgetText.trim()) return
      const attributionIssue = attributionError(this.forgetAttribution, this.memoryScope)
      if (attributionIssue) { this.$message.warning(attributionIssue); return }
      if (!this.beginMaintenance('忘记', 'forgetLoading')) return
      Api.memory.forget(this.agentId, { text: this.forgetText.trim(), reason: '管理端手动忘记', deviceId: this.forgetAttribution.deviceId, speakerId: this.forgetAttribution.speakerId, shared: this.forgetAttribution.visibility === 'shared' }, () => {
        this.finishMaintenance('forgetLoading')
        this.$message.success('忘记操作已完成')
        this.forgetText = ''
        this.showForget = false
        this.fetch()
      }, (error) => {
        this.handleUncertainMaintenanceFailure('忘记', 'forgetLoading', error)
      }, (error) => {
        this.handlePreviewFailure('忘记', 'forgetLoading', error)
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.welcome {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.operation-bar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 16px 24px 0;
  flex-shrink: 0;
  gap: 20px;
  > div:first-child { min-width: 160px; max-width: 260px; }
  .page-title {
    font-size: 22px;
    font-weight: 800;
    color: #1a202c;
    margin: 0;
    letter-spacing: -0.5px;
  }
  .page-subtitle {
    font-size: 13px;
    color: #718096;
    margin-top: 2px;
  }
  .right-operations {
    display: flex;
    gap: 8px;
    align-items: center;
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .session-limit-input {
    width: 76px;
  }
}

.quick-action-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 24px;
  background: #f7f8fc;
  border-bottom: 1px solid #e8ecf4;
  flex-shrink: 0;
}

.action-hint { color: #718096; font-size: 12px; line-height: 1.6; }

.maintenance-alert {
  margin: 10px 24px 0;
  width: auto;
  flex-shrink: 0;
}

.maintenance-progress {
  margin: 4px 24px 0;
  flex-shrink: 0;
}

.memory-overview { margin: 14px 24px; flex-shrink: 0; }

.rebuild-summary {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.main-wrapper {
  flex: 1;
  display: flex;
  padding: 0 24px 12px;
  min-height: 0;
  .content-panel {
    flex: 1;
    display: flex;
    min-width: 0;
  }
  .content-area {
    flex: 1;
    overflow: auto;
    background: #fff;
    border-radius: 10px;
    border: 1px solid #e8ecf4;
    box-shadow: 0 2px 8px rgba(0,0,0,.04);
  }
  .params-card {
    border: none;
    box-shadow: none;
    ::v-deep .el-card__body {
      padding: 12px 16px 16px;
    }
  }
}

::v-deep .el-tabs--border-card {
  border: none;
  box-shadow: none;
  .el-tabs__header {
    background: #f7f8fc;
    border-bottom: 1px solid #e8ecf4;
    margin-bottom: 0;
  }
  .el-tabs__content {
    padding: 12px 0 0;
  }
}

.el-footer {
  height: auto !important;
  padding: 8px 24px;
  flex-shrink: 0;
}

// Transitions
.slide-down-enter-active, .slide-down-leave-active {
  transition: all .25s ease;
}
.slide-down-enter, .slide-down-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

@media (max-width: 768px) {
  .operation-bar {
    flex-wrap: wrap;
    gap: 8px;
  }
}
</style>
