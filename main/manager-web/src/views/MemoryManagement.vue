<template>
  <div class="memory-page">
    <div class="memory-header">
      <div>
        <div class="memory-title">记忆管理</div>
        <div class="memory-subtitle">{{ agentName || agentId }}</div>
      </div>
      <div class="header-actions">
        <el-button icon="el-icon-back" @click="$router.push('/home')">返回</el-button>
        <el-button type="primary" icon="el-icon-refresh" @click="loadData">刷新</el-button>
      </div>
    </div>

    <div class="memory-maintenance">
      <div class="maintenance-block">
        <div class="maintenance-label">手动记住</div>
        <el-input v-model="rememberText" placeholder="输入需要长期保留的事实或偏好" clearable />
        <el-button type="primary" icon="el-icon-plus" @click="remember">写入</el-button>
      </div>
      <div class="maintenance-block">
        <div class="maintenance-label">手动忘记</div>
        <el-input v-model="forgetText" placeholder="输入需要屏蔽或遗忘的内容" clearable />
        <el-button type="danger" icon="el-icon-delete" @click="forget">加入名单</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="memory-tabs">
      <el-tab-pane label="图谱" name="graph">
        <div class="overview-grid">
          <div class="overview-item">
            <span>生效事实</span>
            <strong>{{ memoryGraph.stats.facts }}</strong>
          </div>
          <div class="overview-item warning">
            <span>待确认候选</span>
            <strong>{{ memoryGraph.stats.candidates }}</strong>
          </div>
          <div class="overview-item">
            <span>记忆分类</span>
            <strong>{{ memoryGraph.stats.categories }}</strong>
          </div>
          <div class="overview-item danger">
            <span>忘记规则</span>
            <strong>{{ memoryGraph.stats.tombstones }}</strong>
          </div>
        </div>

        <div class="graph-toolbar">
          <div class="graph-filters">
            <span>分类</span>
            <el-radio-group v-model="graphCategoryFilter" size="mini">
              <el-radio-button label="all">全部</el-radio-button>
              <el-radio-button
                v-for="category in memoryGraph.categoryOptions"
                :key="category.value"
                :label="category.value"
              >
                {{ category.label }}
              </el-radio-button>
            </el-radio-group>
          </div>
          <div class="graph-legend">
            <span><i class="legend-dot agent"></i>智能体</span>
            <span><i class="legend-dot category"></i>分类</span>
            <span><i class="legend-dot fact"></i>事实</span>
            <span><i class="legend-dot candidate"></i>候选</span>
            <span><i class="legend-dot tombstone"></i>忘记</span>
          </div>
        </div>

        <div class="graph-layout">
          <div class="graph-canvas" v-loading="loading">
            <svg v-if="memoryGraph.nodes.length" viewBox="0 0 1120 560" role="img">
              <defs>
                <marker id="memory-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                  <path d="M0,0 L8,4 L0,8 Z" fill="#a8b1c7"></path>
                </marker>
              </defs>
              <line
                v-for="edge in memoryGraph.edges"
                :key="edge.id"
                class="graph-edge"
                :x1="edge.x1"
                :y1="edge.y1"
                :x2="edge.x2"
                :y2="edge.y2"
                marker-end="url(#memory-arrow)"
              />
              <g
                v-for="node in memoryGraph.nodes"
                :key="node.id"
                class="graph-node"
                :class="[node.type, { selected: selectedGraphNode && selectedGraphNode.id === node.id }]"
                :transform="`translate(${node.x}, ${node.y})`"
                @click="selectGraphNode(node)"
              >
                <rect :width="node.width" :height="node.height" rx="6" />
                <text class="node-title" x="12" y="18">{{ node.title }}</text>
                <text class="node-subtitle" x="12" y="34">{{ node.subtitle }}</text>
              </g>
            </svg>
            <el-empty v-else description="暂无可视化记忆"></el-empty>
            <div v-if="memoryGraph.stats.hidden" class="graph-hidden-tip">
              已隐藏 {{ memoryGraph.stats.hidden }} 条低优先级记录，可在下方表格查看完整列表。
            </div>
          </div>

          <div class="graph-detail">
            <template v-if="selectedGraphNode">
              <div class="detail-header">
                <div>
                  <div class="detail-type">{{ selectedGraphNode.typeLabel }}</div>
                  <div class="detail-title">{{ selectedGraphNode.rawTitle }}</div>
                </div>
                <el-tag size="mini" :type="selectedGraphNode.tagType">{{ selectedGraphNode.statusLabel }}</el-tag>
              </div>
              <div class="detail-content">{{ selectedGraphNode.content || '无详细内容' }}</div>
              <div class="detail-meta" v-if="selectedGraphNode.meta.length">
                <div v-for="item in selectedGraphNode.meta" :key="item.label">
                  <span>{{ item.label }}</span>
                  <strong>{{ item.value }}</strong>
                </div>
              </div>
              <div class="detail-actions" v-if="selectedGraphNode.actionType === 'fact'">
                <el-button size="mini" icon="el-icon-edit" @click="openGraphFactEdit">编辑</el-button>
                <el-button size="mini" icon="el-icon-folder" @click="archiveGraphFact">归档</el-button>
                <el-button size="mini" type="danger" icon="el-icon-delete" @click="deleteGraphFact">删除</el-button>
              </div>
              <div class="detail-actions" v-else-if="selectedGraphNode.actionType === 'candidate'">
                <el-button size="mini" type="primary" @click="confirmGraphCandidate">确认记忆</el-button>
                <el-button size="mini" @click="rejectGraphCandidate">拒绝</el-button>
              </div>
            </template>
            <template v-else>
              <div class="detail-type">节点详情</div>
              <div class="detail-content">点击左侧分类、事实、候选或忘记规则后，在这里查看完整内容和可用操作。</div>
            </template>
          </div>
        </div>

        <div class="graph-records">
          <div class="records-section">
            <div class="records-title">事实记忆</div>
            <el-table :data="visibleGraphFacts" border height="240" size="mini" empty-text="暂无事实记忆">
              <el-table-column prop="category" label="分类" width="120" :formatter="categoryFormatter" />
              <el-table-column prop="canonicalText" label="内容" min-width="280" show-overflow-tooltip />
              <el-table-column prop="importance" label="重要性" width="80" />
              <el-table-column prop="status" label="状态" width="90" :formatter="statusFormatter" />
              <el-table-column label="操作" width="190" fixed="right">
                <template slot-scope="{ row }">
                  <el-button size="mini" type="text" @click="selectFactRow(row)">查看</el-button>
                  <el-button size="mini" type="text" @click="openEdit(row)">编辑</el-button>
                  <el-button size="mini" type="text" class="danger-action" @click="deleteFact(row)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div class="records-section">
            <div class="records-title">候选记忆</div>
            <el-table :data="visibleGraphCandidates" border height="240" size="mini" empty-text="暂无待确认候选">
              <el-table-column prop="category" label="分类" width="120" :formatter="categoryFormatter" />
              <el-table-column prop="canonicalText" label="内容" min-width="260" show-overflow-tooltip />
              <el-table-column prop="confidence" label="置信" width="80" />
              <el-table-column label="操作" width="150" fixed="right">
                <template slot-scope="{ row }">
                  <el-button size="mini" type="text" @click="selectCandidateRow(row)">查看</el-button>
                  <el-button size="mini" type="text" @click="confirmCandidate(row)">确认</el-button>
                  <el-button size="mini" type="text" @click="rejectCandidate(row)">拒绝</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="事实记忆" name="facts">
        <el-alert type="success" :closable="false" show-icon style="margin-bottom:12px">
          <span slot="title">维护模型自动从对话中抽取事实并直接写入记忆库，同时自动去重、更新和遗忘。你无需手动确认，只需必要时编辑或删除。</span>
        </el-alert>
        <el-table v-loading="loading" :data="dashboard.facts" border height="520">
          <el-table-column prop="category" label="分类" width="120" :formatter="categoryFormatter" />
          <el-table-column prop="memoryType" label="类型" width="120" :formatter="memoryTypeFormatter" />
          <el-table-column prop="canonicalText" label="内容" min-width="280" show-overflow-tooltip />
          <el-table-column prop="confirmationState" label="来源" width="80" :formatter="sourceFormatter" />
          <el-table-column prop="confidence" label="置信" width="80" />
          <el-table-column prop="importance" label="重要性" width="90" />
          <el-table-column prop="status" label="状态" width="90" :formatter="statusFormatter" />
          <el-table-column label="操作" width="210" fixed="right">
            <template slot-scope="{ row }">
              <el-button size="mini" icon="el-icon-edit" @click="openEdit(row)">编辑</el-button>
              <el-button size="mini" icon="el-icon-folder" @click="archiveFact(row)">归档</el-button>
              <el-button size="mini" type="danger" icon="el-icon-delete" @click="deleteFact(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="候选记录" name="candidates" v-if="dashboard.candidates && dashboard.candidates.length > 0">
        <el-table :data="dashboard.candidates" border height="480">
          <el-table-column prop="category" label="分类" width="120" :formatter="categoryFormatter" />
          <el-table-column prop="canonicalText" label="内容" min-width="360" show-overflow-tooltip />
          <el-table-column prop="confidence" label="置信" width="80" />
          <el-table-column prop="status" label="状态" width="100" :formatter="statusFormatter" />
          <el-table-column label="操作" width="160">
            <template slot-scope="{ row }">
              <el-button size="mini" type="primary" :disabled="row.status !== 'pending'" @click="confirmCandidate(row)">确认</el-button>
              <el-button size="mini" :disabled="row.status !== 'pending'" @click="rejectCandidate(row)">拒绝</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="工具经验" name="tools">
        <el-table :data="dashboard.toolBehaviors" border height="520">
          <el-table-column prop="toolName" label="工具" width="180" />
          <el-table-column prop="scenarioKey" label="场景" width="160" />
          <el-table-column prop="successCount" label="成功" width="80" />
          <el-table-column prop="failureCount" label="失败" width="80" />
          <el-table-column prop="preferredParams" label="偏好参数" min-width="260" show-overflow-tooltip />
          <el-table-column prop="updatedAt" label="更新时间" width="180" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="忘记名单" name="forget">
        <el-table :data="dashboard.tombstones" border height="520">
          <el-table-column prop="patternText" label="内容" min-width="360" show-overflow-tooltip />
          <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
          <el-table-column prop="status" label="状态" width="100" :formatter="statusFormatter" />
          <el-table-column prop="createdAt" label="创建时间" width="180" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="审计" name="revisions">
        <el-table :data="dashboard.revisions" border height="520">
          <el-table-column prop="revisionAction" label="动作" width="130" />
          <el-table-column prop="memoryId" label="记忆ID" width="220" show-overflow-tooltip />
          <el-table-column prop="actorType" label="来源" width="90" />
          <el-table-column prop="reason" label="原因" min-width="220" show-overflow-tooltip />
          <el-table-column prop="createdAt" label="时间" width="180" />
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog title="编辑记忆" :visible.sync="editVisible" width="560px">
      <el-form :model="editForm" label-width="90px">
        <el-form-item label="类型">
          <el-select v-model="editForm.memoryType" filterable allow-create default-first-option style="width: 100%">
            <el-option
              v-for="item in memoryTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="editForm.category" filterable allow-create default-first-option style="width: 100%">
            <el-option
              v-for="item in categoryOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="editForm.canonicalText" type="textarea" :rows="5" />
        </el-form-item>
        <el-form-item label="置信度">
          <el-input-number v-model="editForm.confidence" :min="0" :max="1" :step="0.05" />
        </el-form-item>
        <el-form-item label="重要性">
          <el-input-number v-model="editForm.importance" :min="0" :max="1" :step="0.05" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
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
      activeTab: 'graph',
      graphCategoryFilter: 'all',
      rememberText: '',
      forgetText: '',
      editVisible: false,
      editMemoryId: '',
      editForm: {},
      selectedGraphNode: null,
      dashboard: {
        profile: null,
        facts: [],
        candidates: [],
        toolBehaviors: [],
        tombstones: [],
        revisions: []
      }
    };
  },
  computed: {
    memoryTypeOptions() {
      return this.dynamicOptions('memoryType', this.editForm.memoryType);
    },
    categoryOptions() {
      return this.dynamicOptions('category', this.editForm.category);
    },
    filteredFacts() {
      const facts = (this.dashboard.facts || []).filter((item) => item.status !== 'deleted');
      if (this.graphCategoryFilter === 'all') {
        return facts;
      }
      return facts.filter((item) => (item.category || '未分类') === this.graphCategoryFilter);
    },
    filteredCandidates() {
      const candidates = (this.dashboard.candidates || []).filter((item) => item.status === 'pending');
      if (this.graphCategoryFilter === 'all') {
        return candidates;
      }
      return candidates.filter((item) => (item.category || '候选') === this.graphCategoryFilter);
    },
    visibleGraphFacts() {
      return this.filteredFacts.slice(0, 20);
    },
    visibleGraphCandidates() {
      return this.filteredCandidates.slice(0, 20);
    },
    memoryGraph() {
      const facts = (this.dashboard.facts || []).filter((item) => item.status !== 'deleted');
      const candidates = (this.dashboard.candidates || []).filter((item) => item.status === 'pending');
      const tombstones = (this.dashboard.tombstones || []).filter((item) => item.status === 'active');
      const nodes = [];
      const edges = [];
      const nodeMap = new Map();
      const maxFacts = 16;
      const maxCandidates = 8;
      const maxTombstones = 6;
      const selectedFacts = this.filteredFacts;
      const selectedCandidates = this.filteredCandidates;
      const visibleFacts = selectedFacts.slice(0, maxFacts);
      const visibleCandidates = selectedCandidates.slice(0, maxCandidates);
      const visibleTombstones = tombstones.slice(0, maxTombstones);
      const allCategories = Array.from(new Set([
        ...facts.map((item) => item.category || '未分类'),
        ...candidates.map((item) => item.category || '候选')
      ]));
      const categories = (this.graphCategoryFilter === 'all' ? allCategories : [this.graphCategoryFilter]).slice(0, 8);
      const categoryOptions = allCategories.map((category) => ({
        value: category,
        label: this.categoryText(category)
      }));

      const addNode = (node) => {
        nodes.push(node);
        nodeMap.set(node.id, node);
      };
      const addEdge = (from, to, label = '') => {
        const source = nodeMap.get(from);
        const target = nodeMap.get(to);
        if (!source || !target) return;
        edges.push({
          id: `${from}-${to}-${edges.length}`,
          label,
          x1: source.x + source.width,
          y1: source.y + source.height / 2,
          x2: target.x,
          y2: target.y + target.height / 2
        });
      };
      const yFor = (index, count, top = 48, bottom = 512) => {
        if (count <= 1) return 280;
        return top + ((bottom - top) / (count - 1)) * index;
      };

      addNode(this.buildGraphNode({
        id: 'agent',
        type: 'agent',
        typeLabel: '智能体',
        x: 34,
        y: 252,
        title: this.agentName || '当前智能体',
        subtitle: `${facts.length} 条事实`,
        content: this.agentId,
        meta: [
          { label: '事实', value: facts.length },
          { label: '候选', value: candidates.length },
          { label: '忘记', value: tombstones.length }
        ]
      }));

      categories.forEach((category, index) => {
        const categoryFacts = visibleFacts.filter((item) => (item.category || '未分类') === category);
        const categoryCandidates = visibleCandidates.filter((item) => (item.category || '候选') === category);
        addNode(this.buildGraphNode({
          id: `category-${category}`,
          type: 'category',
          typeLabel: '记忆分类',
          x: 245,
          y: yFor(index, categories.length) - 21,
          title: this.categoryText(category),
          subtitle: `${categoryFacts.length + categoryCandidates.length} 条`,
          content: this.categoryText(category),
          meta: [
            { label: '事实', value: categoryFacts.length },
            { label: '候选', value: categoryCandidates.length }
          ]
        }));
        addEdge('agent', `category-${category}`);
      });

      visibleFacts.forEach((fact, index) => {
        const id = `fact-${fact.memoryId || index}`;
        addNode(this.buildGraphNode({
          id,
          type: 'fact',
          typeLabel: '事实记忆',
          actionType: 'fact',
          record: fact,
          statusLabel: this.statusText(fact.status),
          tagType: 'success',
          x: 475,
          y: yFor(index, visibleFacts.length, 28, 516) - 21,
          title: fact.canonicalText || '未命名事实',
          subtitle: `${this.memoryTypeText(fact.memoryType)} · ${this.statusText(fact.status)}`,
          content: fact.canonicalText,
          meta: [
            { label: '记忆类型', value: this.memoryTypeText(fact.memoryType) },
            { label: '记忆分类', value: this.categoryText(fact.category) },
            { label: '适用范围', value: this.scopeText(fact.scopeType, fact.scopeId) },
            { label: '置信度', value: this.scorePercent(fact.confidence) },
            { label: '重要性', value: this.scorePercent(fact.importance) }
          ]
        }));
        addEdge(`category-${fact.category || '未分类'}`, id);
      });

      visibleCandidates.forEach((candidate, index) => {
        const id = `candidate-${candidate.candidateId || index}`;
        addNode(this.buildGraphNode({
          id,
          type: 'candidate',
          typeLabel: '候选记忆',
          actionType: 'candidate',
          record: candidate,
          statusLabel: this.statusText(candidate.status),
          tagType: 'warning',
          x: 735,
          y: yFor(index, Math.max(visibleCandidates.length, 1), 58, 368) - 21,
          title: candidate.canonicalText || '候选记忆',
          subtitle: `${this.categoryText(candidate.category)} · ${this.statusText(candidate.status)}`,
          content: candidate.canonicalText,
          meta: [
            { label: '记忆分类', value: this.categoryText(candidate.category) },
            { label: '记忆类型', value: this.memoryTypeText(candidate.memoryType) },
            { label: '置信度', value: this.scorePercent(candidate.confidence) },
            { label: '重要性', value: this.scorePercent(candidate.importance) }
          ]
        }));
        addEdge(`category-${candidate.category || '候选'}`, id);
      });

      visibleTombstones.forEach((tombstone, index) => {
        const id = `tombstone-${tombstone.tombstoneId || index}`;
        addNode(this.buildGraphNode({
          id,
          type: 'tombstone',
          typeLabel: '忘记规则',
          actionType: 'tombstone',
          record: tombstone,
          statusLabel: this.statusText(tombstone.status),
          tagType: 'danger',
          x: 910,
          y: yFor(index, Math.max(visibleTombstones.length, 1), 408, 520) - 21,
          width: 180,
          title: tombstone.patternText || '忘记规则',
          subtitle: tombstone.reason || this.statusText(tombstone.status),
          content: tombstone.patternText,
          meta: [
            { label: '原因', value: tombstone.reason || '-' },
            { label: '状态', value: this.statusText(tombstone.status) }
          ]
        }));
        addEdge('agent', id);
      });

      return {
        nodes,
        edges,
        categoryOptions,
        stats: {
          facts: facts.length,
          candidates: candidates.length,
          categories: allCategories.length,
          tombstones: tombstones.length,
          hidden: Math.max(0, selectedFacts.length - maxFacts)
            + Math.max(0, selectedCandidates.length - maxCandidates)
            + Math.max(0, tombstones.length - maxTombstones)
        }
      };
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
        this.selectedGraphNode = null;
        this.loading = false;
      }, () => {
        this.loading = false;
        this.$message.error('记忆加载失败');
      });
    },
    remember() {
      if (!this.rememberText.trim()) return;
      Api.memory.remember(this.agentId, { text: this.rememberText.trim(), category: 'manual' }, () => {
        this.$message.success('已写入记忆');
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
    openGraphFactEdit() {
      const row = this.selectedGraphNode && this.selectedGraphNode.record;
      if (!row) return;
      this.openEdit(row);
    },
    archiveGraphFact() {
      const row = this.selectedGraphNode && this.selectedGraphNode.record;
      if (!row) return;
      this.archiveFact(row);
    },
    deleteGraphFact() {
      const row = this.selectedGraphNode && this.selectedGraphNode.record;
      if (!row) return;
      this.deleteFact(row);
    },
    confirmCandidate(row) {
      Api.memory.confirmCandidate(this.agentId, row.candidateId, () => {
        this.$message.success('已确认');
        this.loadData();
      });
    },
    rejectCandidate(row) {
      Api.memory.rejectCandidate(this.agentId, row.candidateId, () => {
        this.$message.success('已拒绝');
        this.loadData();
      });
    },
    confirmGraphCandidate() {
      const row = this.selectedGraphNode && this.selectedGraphNode.record;
      if (!row) return;
      this.confirmCandidate(row);
    },
    rejectGraphCandidate() {
      const row = this.selectedGraphNode && this.selectedGraphNode.record;
      if (!row) return;
      this.rejectCandidate(row);
    },
    buildGraphNode(node) {
      const width = node.width || 190;
      const height = node.height || 42;
      return {
        ...node,
        width,
        height,
        rawTitle: node.title,
        title: this.truncateGraphText(node.title, width > 180 ? 16 : 13),
        subtitle: this.truncateGraphText(node.subtitle, width > 180 ? 18 : 14),
        meta: node.meta || [],
        statusLabel: node.statusLabel || '查看',
        tagType: node.tagType || ''
      };
    },
    selectGraphNode(node) {
      this.selectedGraphNode = node;
    },
    dynamicOptions(field, currentValue) {
      const values = new Set();
      [...(this.dashboard.facts || []), ...(this.dashboard.candidates || [])].forEach((item) => {
        if (item && item[field]) {
          values.add(item[field]);
        }
      });
      if (currentValue) {
        values.add(currentValue);
      }
      return Array.from(values).map((value) => ({
        value,
        label: this.formatMemoryLabel(value)
      }));
    },
    selectFactRow(row) {
      const id = `fact-${row.memoryId}`;
      const node = this.memoryGraph.nodes.find((item) => item.id === id);
      this.selectedGraphNode = node || this.buildGraphNode({
        id,
        type: 'fact',
        typeLabel: '事实记忆',
        actionType: 'fact',
        record: row,
        title: row.canonicalText || '未命名事实',
        subtitle: `${this.memoryTypeText(row.memoryType)} · ${this.statusText(row.status)}`,
        statusLabel: this.statusText(row.status),
        tagType: 'success',
        content: row.canonicalText,
        meta: [
          { label: '记忆类型', value: this.memoryTypeText(row.memoryType) },
          { label: '记忆分类', value: this.categoryText(row.category) },
          { label: '适用范围', value: this.scopeText(row.scopeType, row.scopeId) },
          { label: '置信度', value: this.scorePercent(row.confidence) },
          { label: '重要性', value: this.scorePercent(row.importance) }
        ]
      });
    },
    selectCandidateRow(row) {
      const id = `candidate-${row.candidateId}`;
      const node = this.memoryGraph.nodes.find((item) => item.id === id);
      this.selectedGraphNode = node || this.buildGraphNode({
        id,
        type: 'candidate',
        typeLabel: '候选记忆',
        actionType: 'candidate',
        record: row,
        title: row.canonicalText || '候选记忆',
        subtitle: `${this.categoryText(row.category)} · ${this.statusText(row.status)}`,
        statusLabel: this.statusText(row.status),
        tagType: 'warning',
        content: row.canonicalText,
        meta: [
          { label: '记忆分类', value: this.categoryText(row.category) },
          { label: '记忆类型', value: this.memoryTypeText(row.memoryType) },
          { label: '置信度', value: this.scorePercent(row.confidence) },
          { label: '重要性', value: this.scorePercent(row.importance) }
        ]
      });
    },
    truncateGraphText(text, maxLength) {
      const value = String(text || '');
      return value.length > maxLength ? `${value.slice(0, maxLength)}...` : value;
    },
    scorePercent(value) {
      const number = Number(value);
      if (Number.isNaN(number)) {
        return '-';
      }
      return `${Math.round(number * 100)}%`;
    },
    categoryText(value) {
      const categoryMap = {
        explicit: '用户明确记住',
        manual: '手动维护',
        tool: '工具经验',
        candidate: '候选'
      };
      return categoryMap[value] || this.formatMemoryLabel(value) || '未分类';
    },
    memoryTypeText(value) {
      const typeMap = {
        tool_behavior: '工具经验',
        explicit: '明确记住',
        memory: '长期记忆'
      };
      return typeMap[value] || this.formatMemoryLabel(value) || '长期记忆';
    },
    formatMemoryLabel(value) {
      if (!value) return '';
      return String(value).replace(/_/g, ' ');
    },
    statusText(value) {
      const statusMap = {
        active: '生效中',
        pending: '待确认',
        confirmed: '已确认',
        rejected: '已拒绝',
        archived: '已归档',
        deleted: '已删除'
      };
      return statusMap[value] || value || '生效中';
    },
    scopeText(scopeType, scopeId) {
      if (!scopeId) return '-';
      if (scopeType === 'device') {
        return `设备 ${scopeId}`;
      }
      if (scopeType === 'agent') {
        return '当前智能体';
      }
      return scopeId;
    },
    categoryFormatter(row) {
      return this.categoryText(row.category);
    },
    memoryTypeFormatter(row) {
      return this.memoryTypeText(row.memoryType);
    },
    statusFormatter(row) {
      return this.statusText(row.status);
    },
    scopeFormatter(row) {
      return this.scopeText(row.scopeType, row.scopeId);
    },
    sourceFormatter(row) {
      const state = row.confirmationState || row.status || '';
      if (state === 'auto') return '🤖 自动';
      if (state === 'explicit') return '📝 手动';
      if (state === 'manual') return '📝 手动';
      return state;
    }
  }
};
</script>

<style scoped>
.memory-page {
  min-height: 100vh;
  padding: 24px;
  box-sizing: border-box;
  background: #f5f7fb;
}

.memory-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.memory-title {
  font-size: 24px;
  font-weight: 700;
  color: #2f3548;
}

.memory-subtitle {
  margin-top: 6px;
  font-size: 13px;
  color: #737b91;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.memory-maintenance {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.maintenance-block {
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid #e4e8f1;
  border-radius: 8px;
  background: #fff;
}

.maintenance-label {
  font-size: 13px;
  font-weight: 700;
  color: #2f3548;
}

.memory-tabs {
  background: #fff;
  padding: 16px;
  border-radius: 8px;
}

.overview-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.overview-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 64px;
  padding: 0 16px;
  border: 1px solid #e4e8f1;
  border-radius: 8px;
  background: #f8fafc;
}

.overview-item span {
  font-size: 13px;
  color: #687186;
}

.overview-item strong {
  font-size: 24px;
  line-height: 1;
  color: #2f3548;
}

.overview-item.warning strong {
  color: #b7791f;
}

.overview-item.danger strong {
  color: #c53030;
}

.graph-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  margin-bottom: 12px;
}

.graph-filters,
.graph-legend {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: #687186;
}

.graph-filters > span {
  font-weight: 700;
  color: #2f3548;
}

.graph-legend span {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.legend-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  display: inline-block;
}

.legend-dot.agent { background: #3f6fd8; }
.legend-dot.category { background: #7b6fd6; }
.legend-dot.fact { background: #1f9d7a; }
.legend-dot.candidate { background: #d89a2b; }
.legend-dot.tombstone { background: #d85858; }

.graph-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 14px;
  min-height: 560px;
}

.graph-canvas {
  position: relative;
  min-height: 560px;
  border: 1px solid #e4e8f1;
  border-radius: 8px;
  background: #fbfcff;
  overflow: hidden;
}

.graph-canvas svg {
  width: 100%;
  height: 560px;
  display: block;
}

.graph-edge {
  stroke: #a8b1c7;
  stroke-width: 1.4;
}

.graph-node {
  cursor: pointer;
}

.graph-node rect {
  fill: #fff;
  stroke: #ccd4e5;
  stroke-width: 1;
  filter: drop-shadow(0 2px 4px rgba(39, 50, 78, 0.08));
}

.graph-node.agent rect {
  fill: #eef4ff;
  stroke: #3f6fd8;
}

.graph-node.category rect {
  fill: #f4f1ff;
  stroke: #7b6fd6;
}

.graph-node.fact rect {
  fill: #ecfbf6;
  stroke: #1f9d7a;
}

.graph-node.candidate rect {
  fill: #fff7e9;
  stroke: #d89a2b;
}

.graph-node.tombstone rect {
  fill: #fff0f0;
  stroke: #d85858;
}

.graph-node.selected rect {
  stroke-width: 2.4;
}

.graph-hidden-tip {
  position: absolute;
  left: 14px;
  bottom: 12px;
  padding: 6px 10px;
  border: 1px solid #dce3ef;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.92);
  color: #687186;
  font-size: 12px;
}

.node-title {
  font-size: 12px;
  font-weight: 700;
  fill: #2f3548;
}

.node-subtitle {
  font-size: 11px;
  fill: #738096;
}

.graph-detail {
  border: 1px solid #e4e8f1;
  border-radius: 8px;
  padding: 14px;
  background: #fff;
  min-width: 0;
}

.detail-type {
  font-size: 12px;
  color: #6b7488;
  margin-bottom: 8px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.detail-title {
  font-size: 16px;
  font-weight: 700;
  color: #2f3548;
  line-height: 1.4;
  word-break: break-word;
}

.detail-content {
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.7;
  color: #4d566b;
  word-break: break-word;
}

.detail-meta {
  margin-top: 14px;
  display: grid;
  gap: 8px;
}

.detail-meta div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
  color: #737b91;
}

.detail-meta strong {
  color: #2f3548;
  font-weight: 600;
  text-align: right;
  word-break: break-word;
}

.detail-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid #eef1f7;
}

.graph-records {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
  margin-top: 14px;
}

.records-section {
  min-width: 0;
}

.records-title {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 700;
  color: #2f3548;
}

.danger-action {
  color: #c53030;
}

@media (max-width: 900px) {
  .memory-maintenance,
  .graph-layout,
  .graph-records,
  .overview-grid {
    grid-template-columns: 1fr;
  }

  .maintenance-block {
    grid-template-columns: 1fr;
  }

  .graph-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .graph-detail {
    min-height: 160px;
  }
}
</style>
