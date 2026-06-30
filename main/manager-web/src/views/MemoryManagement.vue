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
        <el-button size="small" icon="el-icon-edit" @click="showRemember = !showRemember">记住</el-button>
        <el-button size="small" icon="el-icon-delete" @click="showForget = !showForget">忘记</el-button>
        <el-button size="small" icon="el-icon-refresh" @click="fetch" :loading="loading">刷新</el-button>
        <el-button size="small" icon="el-icon-back" @click="$router.push('/')">返回</el-button>
      </div>
    </div>

    <!-- Quick remember/forget panel -->
    <transition name="slide-down">
      <div class="quick-action-bar" v-if="showRemember || showForget">
        <template v-if="showRemember">
          <el-input v-model="rememberText" placeholder="输入需要记住的内容…" size="small" clearable @keyup.enter.native="handleRemember" style="width:320px" />
          <el-button type="primary" size="small" icon="el-icon-plus" @click="handleRemember">记住</el-button>
        </template>
        <template v-if="showForget">
          <el-input v-model="forgetText" placeholder="输入需要忘记的内容…" size="small" clearable @keyup.enter.native="handleForget" style="width:320px;margin-left:16px" />
          <el-button type="danger" size="small" icon="el-icon-delete" @click="handleForget">忘记</el-button>
        </template>
      </div>
    </transition>

    <!-- Stats cards -->
    <div class="stats-row">
      <div class="stat-card">
        <em>{{ activeFacts.length }}</em>
        <span>事实记忆</span>
      </div>
      <div class="stat-card stat-card--auto">
        <em>{{ activeFacts.filter(f => f.confirmationState === 'auto').length }}</em>
        <span>自动抽取</span>
      </div>
      <div class="stat-card">
        <em>{{ categories.length }}</em>
        <span>记忆分类</span>
      </div>
      <div class="stat-card stat-card--warn">
        <em>{{ activeTombstones.length }}</em>
        <span>忘记规则</span>
      </div>
      <div class="stat-card stat-card--info">
        <em>{{ candidates.length }}</em>
        <span>待确认</span>
      </div>
    </div>

    <!-- Main content area with tabs -->
    <div class="main-wrapper">
      <div class="content-panel">
        <div class="content-area">
          <el-card class="params-card" shadow="never">
            <el-tabs v-model="activeTab" type="border-card" @tab-click="handleTabClick">
              <el-tab-pane name="list">
                <span slot="label">
                  记忆列表
                  <el-tag size="mini" type="info" style="margin-left:4px">{{ activeFacts.length }}</el-tag>
                </span>
                <MemoryList
                  :facts="activeFacts"
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
                  :facts="activeFacts"
                  :tombstones="activeTombstones"
                  :agentName="agentName"
                  :loading="loading"
                  @open-detail="handleOpenDetail"
                />
              </el-tab-pane>

              <el-tab-pane name="recall">
                <span slot="label">召回测试</span>
                <MemoryRecallTest
                  :facts="activeFacts"
                  @open-detail="handleOpenDetail"
                />
              </el-tab-pane>

              <el-tab-pane name="timeline">
                <span slot="label">
                  时间线
                  <el-tag v-if="dashboard.revisions && dashboard.revisions.length" size="mini" type="info" style="margin-left:4px">{{ dashboard.revisions.length }}</el-tag>
                </span>
                <MemoryTimeline
                  :revisions="dashboard.revisions || []"
                  :facts="activeFacts"
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
      :visible="detailVisible"
      :memory="detailItem"
      :item-type="detailType"
      @close="detailVisible = false"
      @edit="handleEdit"
      @archive="handleArchive"
      @delete="handleDelete"
    />

    <!-- Edit dialog -->
    <MemoryEditDialog
      :visible.sync="editVisible"
      :memory="editItem"
      :memory-id="editMemoryId"
      :existing-types="existingTypes"
      :existing-categories="categories"
      @save="handleSaveEdit"
      @cancel="editVisible = false"
    />

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
import Api from '@/apis/api'

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
    MemoryEditDialog
  },
  data() {
    return {
      agentId: '',
      agentName: '',
      loading: false,
      dashboard: {},
      activeTab: 'list',
      // Quick remember/forget
      showRemember: false,
      showForget: false,
      rememberText: '',
      forgetText: '',
      // Detail panel
      detailVisible: false,
      detailItem: {},
      detailType: 'fact',
      // Edit dialog
      editVisible: false,
      editItem: {},
      editMemoryId: ''
    }
  },
  computed: {
    activeFacts() {
      return (this.dashboard.facts || []).filter(f => f.status !== 'deleted')
    },
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
  },
  methods: {
    fetch() {
      this.loading = true
      Api.memory.dashboard(this.agentId, (res) => {
        this.dashboard = (res && res.data && res.data.data) ? res.data.data : (res && res.data) || this.dashboard
        this.loading = false
      }, () => {
        this.loading = false
      })
    },

    handleTabClick() {
      this.detailVisible = false
    },

    // --- Detail panel ---
    handleOpenDetail(item) {
      this.detailItem = item
      this.detailType = 'fact'
      this.detailVisible = true
    },

    // --- Edit ---
    handleEdit(item) {
      this.editItem = item
      this.editMemoryId = item.memoryId || ''
      this.editVisible = true
    },
    handleSaveEdit(memoryId, formData) {
      Api.memory.updateFact(this.agentId, memoryId, formData, () => {
        this.$message.success('记忆已更新')
        this.editVisible = false
        this.detailVisible = false
        this.fetch()
      })
    },

    // --- Archive ---
    handleArchive(item) {
      this.$confirm('确定归档这条记忆吗？归档后仍可恢复。', '提示', { type: 'warning' }).then(() => {
        Api.memory.archiveFact(this.agentId, item.memoryId, () => {
          this.$message.success('已归档')
          this.detailVisible = false
          this.fetch()
        })
      }).catch(() => {})
    },

    // --- Delete ---
    handleDelete(item) {
      this.$confirm('确定永久删除这条记忆吗？此操作不可恢复。', '警告', { type: 'warning', confirmButtonClass: 'el-button--danger' }).then(() => {
        Api.memory.deleteFact(this.agentId, item.memoryId, () => {
          this.$message.success('已删除')
          this.detailVisible = false
          this.fetch()
        })
      }).catch(() => {})
    },

    // --- Batch operations ---
    handleBatchArchive(facts) {
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
      if (!facts.length) return
      this.$confirm(`确定永久删除选中的 ${facts.length} 条记忆吗？此操作不可恢复。`, '批量删除', { type: 'warning', confirmButtonClass: 'el-button--danger' }).then(() => {
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
      Api.memory.confirmCandidate(this.agentId, candidate.candidateId, () => {
        this.$message.success('已确认候选记忆')
        this.fetch()
      })
    },
    handleReject(candidate) {
      Api.memory.rejectCandidate(this.agentId, candidate.candidateId, () => {
        this.$message.success('已拒绝候选记忆')
        this.fetch()
      })
    },

    // --- Quick remember/forget ---
    handleRemember() {
      if (!this.rememberText.trim()) return
      Api.memory.remember(this.agentId, { text: this.rememberText.trim(), category: 'manual' }, () => {
        this.$message.success('已写入记忆')
        this.rememberText = ''
        this.showRemember = false
        this.fetch()
      })
    },
    handleForget() {
      if (!this.forgetText.trim()) return
      Api.memory.forget(this.agentId, { text: this.forgetText.trim(), reason: '管理端手动忘记' }, () => {
        this.$message.success('已添加忘记规则')
        this.forgetText = ''
        this.showForget = false
        this.fetch()
      })
    }
  }
}
</script>

<style lang="scss" scoped>
.welcome {
  height: 100vh;
  display: flex;
  flex-direction: column;
}
.operation-bar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 16px 24px 0;
  flex-shrink: 0;
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

.stats-row {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 10px;
  padding: 12px 24px;
  flex-shrink: 0;
}
.stat-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e8ecf4;
  box-shadow: 0 1px 3px rgba(0,0,0,.03);
  em {
    font-size: 26px;
    font-weight: 800;
    color: #1a202c;
    font-style: normal;
    line-height: 1;
  }
  span {
    font-size: 12px;
    color: #718096;
    margin-top: 4px;
  }
}
.stat-card--auto em { color: #3f6fd8; }
.stat-card--warn em { color: #e05555; }
.stat-card--info em { color: #7c6fd6; }

.main-wrapper {
  flex: 1;
  display: flex;
  padding: 0 24px 12px;
  min-height: 0;
  .content-panel {
    flex: 1;
    display: flex;
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
  .stats-row {
    grid-template-columns: repeat(3, 1fr);
  }
  .operation-bar {
    flex-wrap: wrap;
    gap: 8px;
  }
}
</style>
