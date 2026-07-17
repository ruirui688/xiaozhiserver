<template>
  <div class="list-view">
    <!-- Filter toolbar -->
    <div class="list-toolbar">
      <div class="list-toolbar-left">
        <el-input v-model="searchQuery" placeholder="搜索记忆内容..." prefix-icon="el-icon-search" clearable size="small" style="width:220px" @input="onSearchInput" />
        <el-select v-model="filterCategory" placeholder="分类" clearable size="small" style="width:130px">
          <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="filterSpeaker" placeholder="人物" clearable size="small" style="width:130px">
          <el-option v-for="s in speakers" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-select v-model="filterState" placeholder="来源" clearable size="small" style="width:110px">
          <el-option label="模型调用" value="auto" />
          <el-option label="手动创建" value="manual" />
        </el-select>
        <el-select v-model="filterTier" placeholder="层级" clearable size="small" style="width:110px">
          <el-option label="长期记忆" value="LONG_TERM" />
          <el-option label="短期记忆" value="SHORT_TERM" />
          <el-option label="工作记忆" value="WORKING" />
        </el-select>
        <el-select v-model="filterStatus" placeholder="状态" clearable size="small" style="width:100px">
          <el-option label="活跃" value="active" />
          <el-option label="已归档" value="archived" />
        </el-select>
      </div>
      <div class="list-toolbar-right">
        <span class="list-summary" v-if="filteredFacts.length !== facts.length">
          显示 {{ filteredFacts.length }} / {{ facts.length }}
        </span>
        <el-button size="small" icon="el-icon-refresh" @click="$emit('refresh')">刷新</el-button>
      </div>
    </div>

    <!-- Table -->
    <el-table
      ref="table"
      :data="paginatedFacts"
      v-loading="loading"
      class="transparent-table"
      @row-click="handleRowClick"
      @selection-change="handleSelectionChange"
      empty-text="暂无记忆数据。主对话模型调用记忆工具后会在这里显示。">
      <el-table-column type="selection" width="40" />
      <el-table-column label="记忆内容" min-width="240" show-overflow-tooltip>
        <template slot-scope="{row}">
          <span class="fact-text">{{ row.canonicalText || row.patternText || '无内容' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="关联人物" width="120" show-overflow-tooltip>
        <template slot-scope="{row}">
          <el-tag size="mini" :type="row.speakerId ? 'primary' : 'info'">
            {{ row.speakerDisplay || '共享记忆' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="分类" width="100">
        <template slot-scope="{row}">
          <el-tag size="mini" type="info" @click.stop="filterCategory=row.category">{{ row.category || '未分类' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="重要性" width="120">
        <template slot-scope="{row}">
          <el-progress :percentage="pct(row.importance)" :stroke-width="6" :color="importanceColor(row.importance)" />
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template slot-scope="{row}">
          <el-tag size="mini" :type="row.confirmationState==='auto'?'success':'warning'">
            {{ row.confirmationState === 'auto' ? '自动' : '手动' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="层级" width="100">
        <template slot-scope="{row}">
          <el-tag size="mini" :type="tierTagType(row.memoryTier)">{{ tierLabel(row.memoryTier) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template slot-scope="{row}">
          <el-tag size="mini" :type="row.status==='active'?'success':'info'">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="150">
        <template slot-scope="{row}">{{ row.updatedAt || row.createdAt || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template slot-scope="{row}">
          <el-button size="mini" type="text" icon="el-icon-view" @click.stop="$emit('open-detail', row)">详情</el-button>
          <el-button size="mini" type="text" icon="el-icon-edit" @click.stop="$emit('edit', row)">编辑</el-button>
          <el-button size="mini" type="text" icon="el-icon-folder" @click.stop="$emit('archive', row)" v-if="row.status!=='archived'">归档</el-button>
          <el-button size="mini" type="text" style="color:#e05555" icon="el-icon-delete" @click.stop="$emit('delete', row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Bottom bar with batch actions + pagination -->
    <div class="table-bottom">
      <div class="ctrl-btns">
        <el-button size="mini" @click="toggleSelectAll">全选/取消</el-button>
        <el-button size="mini" type="danger" icon="el-icon-delete" @click="batchArchive" :disabled="selectedFacts.length===0">批量归档</el-button>
        <el-button size="mini" type="danger" icon="el-icon-delete" @click="batchDelete" :disabled="selectedFacts.length===0">批量删除</el-button>
        <span v-if="selectedFacts.length" style="font-size:12px;color:#718096;margin-left:8px">
          已选 {{ selectedFacts.length }} 条
        </span>
      </div>
      <el-pagination
        background
        layout="total, sizes, prev, pager, next, jumper"
        :current-page="currentPage"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="filteredFacts.length"
        @size-change="handlePageSizeChange"
        @current-change="handleCurrentPageChange"
      />
    </div>
  </div>
</template>

<script>
import { statusLabel, tierLabel } from '@/utils/memoryLabels'

export default {
  name: 'MemoryList',
  props: {
    facts: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  data() {
    return {
      searchQuery: '',
      filterCategory: '',
      filterSpeaker: '',
      filterState: '',
      filterTier: '',
      filterStatus: 'active',
      currentPage: 1,
      pageSize: 20,
      selectedFacts: [],
      searchTimer: null
    }
  },
  computed: {
    categories() {
      return [...new Set(this.facts.map(f => f.category || '未分类'))].sort()
    },
    speakers() {
      const speakers = new Map()
      this.facts.forEach(f => {
        const value = f.speakerId || '__shared__'
        speakers.set(value, f.speakerDisplay || '共享记忆')
      })
      return [...speakers.entries()]
        .map(([value, label]) => ({ value, label }))
        .sort((a, b) => a.label.localeCompare(b.label, 'zh-CN'))
    },
    filteredFacts() {
      let list = this.facts
      if (this.filterStatus) {
        list = list.filter(f => (f.status || 'active') === this.filterStatus)
      }
      if (this.filterState) {
        list = list.filter(f => (f.confirmationState || 'auto') === this.filterState)
      }
      if (this.filterTier) {
        list = list.filter(f => (f.memoryTier || '').toUpperCase() === this.filterTier.toUpperCase())
      }
      if (this.filterCategory) {
        list = list.filter(f => (f.category || '未分类') === this.filterCategory)
      }
      if (this.filterSpeaker) {
        list = list.filter(f => (f.speakerId || '__shared__') === this.filterSpeaker)
      }
      if (this.searchQuery) {
        const q = this.searchQuery.toLowerCase()
        list = list.filter(f =>
          (f.canonicalText || '').toLowerCase().includes(q) ||
          (f.patternText || '').toLowerCase().includes(q) ||
          (f.category || '').toLowerCase().includes(q) ||
          (f.memoryType || '').toLowerCase().includes(q)
          || (f.speakerDisplay || '').toLowerCase().includes(q)
        )
      }
      return list
    },
    paginatedFacts() {
      const start = (this.currentPage - 1) * this.pageSize
      return this.filteredFacts.slice(start, start + this.pageSize)
    }
  },
  watch: {
    filterCategory() { this.currentPage = 1 },
    filterSpeaker() { this.currentPage = 1 },
    filterState() { this.currentPage = 1 },
    filterTier() { this.currentPage = 1 },
    filterStatus() { this.currentPage = 1 }
  },
  methods: {
    pct(v) { return Math.round((Number(v) || 0) * 100) },
    importanceColor(v) {
      const p = this.pct(v)
      return p >= 70 ? '#2ecc71' : p >= 40 ? '#f0b060' : '#909399'
    },
    tierLabel(t) {
      return tierLabel(t)
    },
    statusLabel,
    tierTagType(t) {
      if (!t) return ''
      const tl = t.toLowerCase()
      if (tl === 'long_term') return 'primary'
      if (tl === 'short_term') return 'success'
      if (tl === 'working') return 'info'
      return ''
    },
    handleRowClick(row) {
      this.$emit('open-detail', row)
    },
    handleSelectionChange(rows) {
      this.selectedFacts = rows
    },
    toggleSelectAll() {
      if (this.selectedFacts.length === this.paginatedFacts.length) {
        this.$refs.table.clearSelection()
      } else {
        this.paginatedFacts.forEach(row => this.$refs.table.toggleRowSelection(row, true))
      }
    },
    batchArchive() {
      if (this.selectedFacts.length === 0) return
      this.$emit('batch-archive', this.selectedFacts.map(f => f))
    },
    batchDelete() {
      if (this.selectedFacts.length === 0) return
      this.$emit('batch-delete', this.selectedFacts.map(f => f))
    },
    handlePageSizeChange(val) {
      this.pageSize = val
      this.currentPage = 1
    },
    handleCurrentPageChange(page) {
      this.currentPage = page
    },
    onSearchInput() {
      clearTimeout(this.searchTimer)
      this.searchTimer = setTimeout(() => {
        this.currentPage = 1
      }, 300)
    }
  }
}
</script>

<style scoped>
.list-view { }

.list-toolbar {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 0; margin-bottom: 8px; flex-wrap: wrap; gap: 8px;
}
.list-toolbar-left { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.list-toolbar-right { display: flex; gap: 8px; align-items: center; }
.list-summary { font-size: 12px; color: #718096; }

.fact-text { font-size: 13px; color: #2f3548; cursor: pointer; }
.fact-text:hover { color: #3f6fd8; }

.table-bottom {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: 12px; flex-wrap: wrap; gap: 10px;
}
.ctrl-btns { display: flex; gap: 6px; align-items: center; }
</style>
