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
        <el-input-number
          v-model="rebuildSessionLimit"
          size="small"
          :min="1"
          :max="50"
          :controls="false"
          class="session-limit-input"
        />
        <el-button size="small" icon="el-icon-view" :loading="rebuildLoading" @click="handleRebuildPreview">预览重建</el-button>
        <el-button size="small" type="primary" icon="el-icon-magic-stick" :loading="rebuildLoading" @click="handleRebuild">重建记忆</el-button>
        <el-button size="small" icon="el-icon-view" :loading="reviewLoading" @click="handleReviewPreview">预览重审</el-button>
        <el-button size="small" type="primary" icon="el-icon-refresh" :loading="reviewLoading" @click="handleReview">重审记忆</el-button>
        <el-button size="small" type="danger" icon="el-icon-delete" :loading="clearLoading" @click="handleClear">清空记忆</el-button>
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

    <el-dialog
      :title="previewDialogTitle"
      :visible.sync="rebuildPreviewVisible"
      width="860px"
      append-to-body
    >
      <div class="rebuild-summary" v-if="rebuildPreviewResult">
        <el-tag size="mini" type="info">会话 {{ rebuildPreviewResult.sessionsRead || 0 }}</el-tag>
        <el-tag size="mini" type="warning">动作 {{ previewCandidates.length }}</el-tag>
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
        <el-button v-if="previewMode === 'rebuild'" size="small" type="primary" :loading="rebuildLoading" @click="handleRebuild">按此配置重建</el-button>
        <el-button v-else size="small" type="primary" :loading="reviewLoading" @click="handleReview">执行重审</el-button>
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
      editMemoryId: '',
      // Rebuild
      rebuildSessionLimit: 15,
      rebuildMode: 'clear_then_rebuild',
      rebuildLoading: false,
      reviewLoading: false,
      clearLoading: false,
      rebuildPreviewVisible: false,
      rebuildPreviewResult: null,
      previewMode: 'rebuild'
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
    },
    previewCandidates() {
      return ((this.rebuildPreviewResult && this.rebuildPreviewResult.candidates) || [])
        .map(this.decorateMemoryIdentity)
    },
    previewDialogTitle() {
      return this.previewMode === 'review' ? '重审预览' : '重建预览'
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
        const data = (res && res.data && res.data.data) ? res.data.data : (res && res.data) || this.dashboard
        this.dashboard = {
          ...data,
          facts: (data.facts || []).map(this.decorateMemoryIdentity),
          candidates: (data.candidates || []).map(this.decorateMemoryIdentity)
        }
        this.loading = false
      }, () => {
        this.loading = false
      })
    },

    unwrapResult(res) {
      return (res && res.data && res.data.data) ? res.data.data : (res && res.data) || {}
    },

    decorateMemoryIdentity(item) {
      let payload = {}
      try {
        payload = typeof item.payload === 'string' ? JSON.parse(item.payload || '{}') : (item.payload || {})
      } catch (_) {
        payload = {}
      }
      const speakerId = String(payload.speaker_id || '')
      const speakerName = String(payload.speaker_name || '')
      return {
        ...item,
        speakerId,
        speakerName,
        speakerDisplay: speakerId ? (speakerName || '已录入声纹') : '共享记忆'
      }
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

    // --- Clear / rebuild ---
    rebuildPayload(dryRun) {
      return {
        sessionLimit: this.rebuildSessionLimit,
        mode: this.rebuildMode,
        dryRun
      }
    },
    handleClear() {
      this.$confirm('确定清空当前智能体的事实记忆、候选记忆和忘记项吗？此操作会写入删除状态。', '清空记忆', {
        type: 'warning',
        confirmButtonClass: 'el-button--danger'
      }).then(() => {
        this.clearLoading = true
        Api.memory.clear(this.agentId, (res) => {
          const data = this.unwrapResult(res)
          this.$message.success(`已清空 ${data.factsCleared || 0} 条事实记忆`)
          this.clearLoading = false
          this.fetch()
        }, () => {
          this.clearLoading = false
        }, () => {
          this.clearLoading = false
        })
      }).catch(() => {
        this.clearLoading = false
      })
    },
    handleRebuildPreview() {
      this.rebuildLoading = true
      this.previewMode = 'rebuild'
      Api.memory.rebuildPreview(this.agentId, this.rebuildPayload(true), (res) => {
        this.rebuildPreviewResult = this.unwrapResult(res)
        this.rebuildPreviewVisible = true
        this.rebuildLoading = false
      }, () => {
        this.rebuildLoading = false
      }, () => {
        this.rebuildLoading = false
      })
    },
    handleRebuild() {
      this.$confirm(`确定按最近 ${this.rebuildSessionLimit} 个会话重新生成记忆吗？维护模型成功返回动作后才会替换当前记忆。`, '重建记忆', {
        type: 'warning',
        confirmButtonClass: 'el-button--primary'
      }).then(() => {
        this.rebuildLoading = true
        Api.memory.rebuild(this.agentId, this.rebuildPayload(false), (res) => {
          const data = this.unwrapResult(res)
          this.$message.success(`重建完成：读取 ${data.sessionsRead || 0} 个会话，应用 ${data.factsChanged || 0} 条记忆动作`)
          this.rebuildLoading = false
          this.rebuildPreviewVisible = false
          this.fetch()
        }, () => {
          this.rebuildLoading = false
        }, () => {
          this.rebuildLoading = false
        })
      }).catch(() => {
        this.rebuildLoading = false
      })
    },
    handleReviewPreview() {
      this.reviewLoading = true
      this.previewMode = 'review'
      Api.memory.reviewPreview(this.agentId, (res) => {
        this.rebuildPreviewResult = this.unwrapResult(res)
        this.rebuildPreviewVisible = true
        this.reviewLoading = false
      }, () => {
        this.reviewLoading = false
      }, () => {
        this.reviewLoading = false
      })
    },
    handleReview() {
      this.$confirm('确定让维护模型按通用生命周期原则重审当前生效记忆吗？不会使用业务关键词规则。', '重审记忆', {
        type: 'warning',
        confirmButtonClass: 'el-button--primary'
      }).then(() => {
        this.reviewLoading = true
        Api.memory.review(this.agentId, (res) => {
          const data = this.unwrapResult(res)
          this.$message.success(`重审完成：应用 ${data.factsChanged || 0} 条记忆动作`)
          this.reviewLoading = false
          this.rebuildPreviewVisible = false
          this.fetch()
        }, () => {
          this.reviewLoading = false
        }, () => {
          this.reviewLoading = false
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
      if (!this.rememberText.trim()) return
      Api.memory.remember(this.agentId, { text: this.rememberText.trim(), category: 'manual' }, () => {
        this.$message.success('已交给维护模型处理')
        this.rememberText = ''
        this.showRemember = false
        this.fetch()
      })
    },
    handleForget() {
      if (!this.forgetText.trim()) return
      Api.memory.forget(this.agentId, { text: this.forgetText.trim(), reason: '管理端手动忘记' }, () => {
        this.$message.success('已交给维护模型处理')
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
