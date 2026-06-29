<template>
  <div class="memory-page">
    <div class="memory-header">
      <div>
        <div class="memory-title">记忆管理</div>
        <div class="memory-subtitle">{{ agentName || agentId }}</div>
      </div>
      <div class="header-actions">
        <el-button icon="el-icon-back" @click="$router.push('/home')">返回</el-button>
        <el-button type="primary" icon="el-icon-refresh" @click="loadData" :loading="loading">刷新</el-button>
      </div>
    </div>

    <div class="stats-bar">
      <div class="stat">
        <span>生效事实</span>
        <strong>{{ activeFactCount }}</strong>
      </div>
      <div class="stat accent">
        <span>自动生成</span>
        <strong>{{ autoFactCount }}</strong>
      </div>
      <div class="stat">
        <span>记忆分类</span>
        <strong>{{ categoryCount }}</strong>
      </div>
      <div class="stat warn">
        <span>忘记规则</span>
        <strong>{{ activeTombstoneCount }}</strong>
      </div>
    </div>

    <div class="quick-bar">
      <div class="quick-item">
        <el-input v-model="rememberText" placeholder="输入需要长期保留的事实" clearable size="small" @keyup.enter.native="remember" />
        <el-button type="primary" size="small" icon="el-icon-plus" @click="remember">记住</el-button>
      </div>
      <div class="quick-item">
        <el-input v-model="forgetText" placeholder="输入需要屏蔽或遗忘的内容" clearable size="small" @keyup.enter.native="forget" />
        <el-button type="danger" size="small" icon="el-icon-delete" @click="forget">忘记</el-button>
      </div>
      <div class="quick-filters">
        <el-input v-model="searchKeyword" placeholder="搜索记忆..." size="small" clearable prefix-icon="el-icon-search" style="width:200px" />
        <el-select v-model="categoryFilter" placeholder="分类筛选" size="small" clearable style="width:140px" @change="loadData">
          <el-option v-for="cat in categoryOptions" :key="cat" :label="categoryText(cat)" :value="cat" />
        </el-select>
      </div>
    </div>

    <el-alert type="success" :closable="false" show-icon style="margin-bottom:12px">
      <span slot="title">维护模型自动从对话中抽取事实并写入记忆库。你无需手动确认，只需必要时编辑或删除。</span>
    </el-alert>

    <el-table v-loading="loading" :data="filteredFacts" border stripe size="small" class="fact-table" empty-text="暂无事实记忆，开始对话后维护模型会自动填充">
      <el-table-column prop="category" label="分类" width="130" :formatter="categoryFormatter" />
      <el-table-column prop="memoryType" label="类型" width="110" :formatter="memoryTypeFormatter" />
      <el-table-column prop="canonicalText" label="内容" min-width="300" show-overflow-tooltip />
      <el-table-column label="来源" width="75" align="center">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.confirmationState === 'auto' ? 'success' : ''">
            {{ row.confirmationState === 'auto' ? '自动' : '手动' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="importance" label="重要性" width="80" align="center">
        <template slot-scope="{ row }">
          <el-progress :percentage="scorePercent(row.importance)" :stroke-width="5" :show-text="true" :color="importanceColor(row.importance)" style="width:60px;display:inline-block" />
        </template>
      </el-table-column>
      <el-table-column prop="confidence" label="留存率" width="80" align="center">
        <template slot-scope="{ row }">
          <el-progress :percentage="scorePercent(row.confidence)" :stroke-width="5" :show-text="true" :color="retentionColor(row.confidence)" style="width:60px;display:inline-block" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right" align="center">
        <template slot-scope="{ row }">
          <el-button size="mini" type="text" @click="openEdit(row)">编辑</el-button>
          <el-button size="mini" type="text" @click="archiveFact(row)">归档</el-button>
          <el-button size="mini" type="text" class="danger-action" @click="deleteFact(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-collapse style="margin-top:16px" v-if="dashboard.tombstones && dashboard.tombstones.length">
      <el-collapse-item :title="`忘记名单 (${activeTombstoneCount} 条规则)`" name="forget">
        <el-table :data="dashboard.tombstones" border size="small" empty-text="无忘记规则">
          <el-table-column prop="patternText" label="匹配内容" min-width="300" show-overflow-tooltip />
          <el-table-column prop="reason" label="原因" width="180" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="创建时间" width="170" />
        </el-table>
      </el-collapse-item>
      <el-collapse-item :title="`审计记录 (${(dashboard.revisions || []).length} 条)`" name="revisions" v-if="dashboard.revisions && dashboard.revisions.length">
        <el-table :data="dashboard.revisions" border size="small" empty-text="无审计记录" max-height="300">
          <el-table-column prop="revisionAction" label="动作" width="130" />
          <el-table-column prop="reason" label="原因" min-width="260" show-overflow-tooltip />
          <el-table-column prop="actorType" label="触发者" width="80" />
          <el-table-column prop="createdAt" label="时间" width="170" />
        </el-table>
      </el-collapse-item>
    </el-collapse>

    <el-dialog title="编辑记忆" :visible.sync="editVisible" width="480px">
      <el-form :model="editForm" label-width="80px" size="small">
        <el-form-item label="类型">
          <el-select v-model="editForm.memoryType" filterable allow-create default-first-option style="width:100%">
            <el-option v-for="item in memoryTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="editForm.category" filterable allow-create default-first-option style="width:100%">
            <el-option v-for="item in categoryOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="editForm.canonicalText" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="置信度">
          <el-slider v-model="editForm.confidence" :min="0" :max="1" :step="0.05" show-input style="width:100%" />
        </el-form-item>
        <el-form-item label="重要性">
          <el-slider v-model="editForm.importance" :min="0" :max="1" :step="0.05" show-input style="width:100%" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="editVisible = false" size="small">取消</el-button>
        <el-button type="primary" @click="saveEdit" size="small">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import Api from '@/apis/api';

export default {
  name: 'MemoryManagement',
  data() {
    return {
      agentId: '',
      agentName: '',
      loading: false,
      searchKeyword: '',
      categoryFilter: '',
      rememberText: '',
      forgetText: '',
      editVisible: false,
      editMemoryId: '',
      editForm: {},
      dashboard: {
        profile: null, facts: [], candidates: [], toolBehaviors: [], tombstones: [], revisions: []
      }
    };
  },
  computed: {
    activeFacts() {
      return (this.dashboard.facts || []).filter(f => f.status === 'active');
    },
    filteredFacts() {
      let facts = this.activeFacts;
      if (this.searchKeyword) {
        const kw = this.searchKeyword.toLowerCase();
        facts = facts.filter(f =>
          (f.canonicalText || '').toLowerCase().includes(kw) ||
          (f.category || '').toLowerCase().includes(kw) ||
          (f.memoryType || '').toLowerCase().includes(kw)
        );
      }
      if (this.categoryFilter) {
        facts = facts.filter(f => (f.category || '未分类') === this.categoryFilter);
      }
      return facts;
    },
    activeFactCount() { return this.activeFacts.length; },
    autoFactCount() { return this.activeFacts.filter(f => f.confirmationState === 'auto').length; },
    activeTombstoneCount() {
      return (this.dashboard.tombstones || []).filter(t => t.status === 'active').length;
    },
    categoryCount() {
      return new Set(this.activeFacts.map(f => f.category || '未分类')).size;
    },
    categoryOptions() {
      return [...new Set((this.dashboard.facts || []).filter(f => f.status === 'active').map(f => f.category || '未分类'))];
    },
    memoryTypeOptions() {
      return this.dynamicOptions('memoryType', this.editForm.memoryType);
    },
    dynamicOptions(field, currentValue) {
      const values = new Set();
      (this.dashboard.facts || []).forEach(item => { if (item && item[field]) values.add(item[field]); });
      if (currentValue) values.add(currentValue);
      return Array.from(values).map(v => ({ value: v, label: String(v).replace(/_/g, ' ') }));
    }
  },
  mounted() {
    this.agentId = this.$route.query.agentId || '';
    this.agentName = this.$route.query.agentName || '';
    if (!this.agentId) {
      this.$message.error('缺少智能体ID');
      this.$router.push('/home');
      return;
    }
    this.loadData();
  },
  methods: {
    loadData() {
      this.loading = true;
      Api.memory.dashboard(this.agentId, ({ data }) => {
        this.dashboard = data.data || this.dashboard;
        this.loading = false;
      }, () => { this.loading = false; this.$message.error('记忆加载失败'); });
    },
    remember() {
      if (!this.rememberText.trim()) return;
      Api.memory.remember(this.agentId, { text: this.rememberText.trim(), category: 'manual' }, () => {
        this.$message.success('已写入');
        this.rememberText = '';
        this.loadData();
      });
    },
    forget() {
      if (!this.forgetText.trim()) return;
      Api.memory.forget(this.agentId, { text: this.forgetText.trim(), reason: '管理端手动忘记' }, () => {
        this.$message.success('已加入忘记名单');
        this.forgetText = '';
        this.loadData();
      });
    },
    openEdit(row) {
      this.editMemoryId = row.memoryId;
      this.editForm = {
        memoryType: row.memoryType,
        category: row.category,
        canonicalText: row.canonicalText,
        confidence: Number(row.confidence || 0.5),
        importance: Number(row.importance || 0.5)
      };
      this.editVisible = true;
    },
    saveEdit() {
      Api.memory.updateFact(this.agentId, this.editMemoryId, this.editForm, () => {
        this.$message.success('已保存');
        this.editVisible = false;
        this.loadData();
      });
    },
    archiveFact(row) {
      Api.memory.archiveFact(this.agentId, row.memoryId, () => {
        this.$message.success('已归档');
        this.loadData();
      });
    },
    deleteFact(row) {
      this.$confirm('确定删除这条记忆吗？', '提示', { type: 'warning' }).then(() => {
        Api.memory.deleteFact(this.agentId, row.memoryId, () => {
          this.$message.success('已删除');
          this.loadData();
        });
      }).catch(() => {});
    },
    categoryFormatter(row) { return this.categoryText(row.category); },
    memoryTypeFormatter(row) { return this.memoryTypeText(row.memoryType); },
    categoryText(value) {
      const m = { explicit: '明确记住', manual: '手动维护', tool: '工具经验', candidate: '候选' };
      return m[value] || value || '未分类';
    },
    memoryTypeText(value) {
      const m = { tool_behavior: '工具经验', explicit: '明确记住', memory: '长期记忆' };
      return m[value] || String(value || '长期记忆').replace(/_/g, ' ');
    },
    scorePercent(value) {
      const n = Number(value);
      return Number.isNaN(n) ? 0 : Math.round(n * 100);
    },
    importanceColor(val) {
      const n = Number(val) || 0;
      if (n >= 0.7) return '#1f9d7a';
      if (n >= 0.4) return '#d89a2b';
      return '#909399';
    },
    retentionColor(val) {
      const n = Number(val) || 0;
      if (n >= 0.5) return '#3f6fd8';
      if (n >= 0.2) return '#d89a2b';
      return '#c53030';
    }
  }
};
</script>

<style scoped>
.memory-page {
  min-height: 100vh;
  padding: 20px 24px;
  box-sizing: border-box;
  background: #f5f7fb;
}
.memory-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.memory-title {
  font-size: 22px;
  font-weight: 700;
  color: #2f3548;
}
.memory-subtitle {
  margin-top: 4px;
  font-size: 13px;
  color: #737b91;
}
.header-actions {
  display: flex;
  gap: 8px;
}
.stats-bar {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-bottom: 12px;
}
.stat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-radius: 8px;
  background: #fff;
  border: 1px solid #e4e8f1;
}
.stat span { font-size: 13px; color: #687186; }
.stat strong { font-size: 22px; color: #2f3548; }
.stat.accent strong { color: #3f6fd8; }
.stat.warn strong { color: #c53030; }
.quick-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  align-items: center;
}
.quick-item {
  display: flex;
  gap: 6px;
  flex: 1;
  min-width: 240px;
}
.quick-filters {
  display: flex;
  gap: 8px;
  margin-left: auto;
}
.fact-table {
  background: #fff;
  border-radius: 8px;
}
.danger-action { color: #c53030; }
@media (max-width: 900px) {
  .stats-bar { grid-template-columns: repeat(2, 1fr); }
  .quick-bar { flex-direction: column; }
  .quick-filters { margin-left: 0; }
}
</style>
