<template>
  <div class="memory-page">
    <div class="memory-header">
      <div>
        <div class="memory-title">记忆知识图谱</div>
        <div class="memory-subtitle">{{ agentName || agentId }}</div>
      </div>
      <div class="header-actions">
        <el-input v-model="searchText" placeholder="搜索节点..." size="small" clearable prefix-icon="el-icon-search" style="width:200px" @input="onSearch" />
        <el-button type="primary" size="small" icon="el-icon-refresh" @click="loadData" :loading="loading">刷新</el-button>
        <el-button size="small" icon="el-icon-back" @click="$router.push('/home')">返回</el-button>
      </div>
    </div>

    <div class="stats-row">
      <div class="stat-item">
        <span class="stat-num">{{ facts.length }}</span>
        <span class="stat-label">事实记忆</span>
      </div>
      <div class="stat-item primary">
        <span class="stat-num">{{ autoCount }}</span>
        <span class="stat-label">自动生成</span>
      </div>
      <div class="stat-item">
        <span class="stat-num">{{ categoryCount }}</span>
        <span class="stat-label">记忆分类</span>
      </div>
      <div class="stat-item danger">
        <span class="stat-num">{{ tombstones.length }}</span>
        <span class="stat-label">忘记规则</span>
      </div>
    </div>

    <div class="action-row">
      <el-input v-model="rememberText" placeholder="输入需要记住的内容" size="small" clearable @keyup.enter.native="remember" />
      <el-button type="primary" size="small" icon="el-icon-plus" @click="remember">记住</el-button>
      <el-input v-model="forgetText" placeholder="输入需要忘记的内容" size="small" clearable @keyup.enter.native="forget" style="margin-left:12px" />
      <el-button type="danger" size="small" icon="el-icon-delete" @click="forget">忘记</el-button>
      <span class="action-hint">拖拽平移 · 滚轮缩放 · 点击节点查看详情</span>
    </div>

    <div class="graph-container" ref="container">
      <svg ref="svg" class="graph-svg" @mousedown="onPanStart" @wheel.prevent="onWheel">
        <defs>
          <filter id="shadow">
            <feDropShadow dx="0" dy="1" stdDeviation="2" flood-color="#00000022" />
          </filter>
        </defs>
        <g :transform="`translate(${panX},${panY}) scale(${scale})`">
          <!-- Edges -->
          <line v-for="e in renderedEdges" :key="e.id"
            :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2"
            :stroke="e.highlight ? '#3f6fd8' : '#d0d7e8'"
            :stroke-width="e.highlight ? 2 : 1"
            :opacity="e.highlight ? 1 : 0.6" />
          <!-- Nodes -->
          <g v-for="n in nodes" :key="n.id"
            :transform="`translate(${n.x},${n.y})`"
            :opacity="n.dimmed ? 0.15 : 1"
            style="cursor:pointer"
            @mousedown.stop="onNodeDragStart($event, n)"
            @click.stop="selectNode(n)"
            @mouseenter="onNodeHover(n, true)"
            @mouseleave="onNodeHover(n, false)">
            <circle :r="n.r" :fill="n.color" :stroke="n.stroke" stroke-width="2"
              :filter="n === selectedNode ? 'url(#shadow)' : ''" />
            <text text-anchor="middle" :y="n.r + 16" font-size="12" font-weight="600" fill="#2f3548">
              {{ n.label }}
            </text>
            <text text-anchor="middle" :y="n.r + 30" font-size="10" fill="#909399">
              {{ n.sublabel }}
            </text>
          </g>
        </g>
      </svg>
      <div class="graph-empty" v-if="!nodes.length && !loading">暂无记忆数据，与设备开始对话后自动生成</div>
    </div>

    <!-- Legend -->
    <div class="legend">
      <span><i class="dot" style="background:#3f6fd8"></i> 智能体</span>
      <span><i class="dot" style="background:#7b6fd6"></i> 分类</span>
      <span><i class="dot" style="background:#1f9d7a"></i> 事实记忆</span>
      <span><i class="dot" style="background:#d89a2b"></i> 候选</span>
      <span><i class="dot" style="background:#d85858"></i> 忘记规则</span>
    </div>

    <!-- Detail panel -->
    <div class="detail-panel" v-if="selectedNode">
      <div class="detail-header">
        <span>
          <el-tag size="mini" :type="nodeTagType(selectedNode.nType)">{{ nodeTypeLabel(selectedNode.nType) }}</el-tag>
          <strong style="margin-left:8px;font-size:15px">{{ selectedNode.record ? (selectedNode.record.canonicalText || selectedNode.record.patternText || '') : '' }}</strong>
        </span>
        <el-button size="mini" icon="el-icon-close" circle @click="selectedNode = null" />
      </div>
      <div class="detail-grid" v-if="selectedNode.record">
        <template v-if="selectedNode.nType === 'fact'">
          <div class="detail-kv"><span>分类</span><span>{{ categoryText(selectedNode.record.category) }}</span></div>
          <div class="detail-kv"><span>类型</span><span>{{ selectedNode.record.memoryType || '长期记忆' }}</span></div>
          <div class="detail-kv"><span>来源</span><span>{{ selectedNode.record.confirmationState === 'auto' ? '🤖 自动抽取' : '📝 手动创建' }}</span></div>
          <div class="detail-kv"><span>重要性</span><el-progress :percentage="pct(selectedNode.record.importance)" :stroke-width="6" style="width:120px" /></div>
          <div class="detail-kv"><span>留存率</span><el-progress :percentage="pct(selectedNode.record.confidence)" :stroke-width="6" style="width:120px" /></div>
        </template>
        <template v-else>
          <div class="detail-kv"><span>原因</span><span>{{ selectedNode.record.reason || '-' }}</span></div>
          <div class="detail-kv"><span>状态</span><span>{{ selectedNode.record.status || '-' }}</span></div>
        </template>
      </div>
      <div class="detail-actions" v-if="selectedNode.nType === 'fact'">
        <el-button size="mini" icon="el-icon-edit" @click="openEdit(selectedNode.record)">编辑</el-button>
        <el-button size="mini" icon="el-icon-folder" @click="archiveFact(selectedNode.record)">归档</el-button>
        <el-button size="mini" type="danger" icon="el-icon-delete" @click="deleteFact(selectedNode.record)">删除</el-button>
      </div>
    </div>

    <!-- Edit dialog -->
    <el-dialog title="编辑记忆" :visible.sync="editVisible" width="440px">
      <el-form :model="editForm" label-width="70px" size="small">
        <el-form-item label="类型"><el-input v-model="editForm.memoryType" /></el-form-item>
        <el-form-item label="分类"><el-input v-model="editForm.category" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="editForm.canonicalText" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="重要性"><el-slider v-model="editForm.importance" :min="0" :max="1" :step="0.05" show-input /></el-form-item>
        <el-form-item label="置信度"><el-slider v-model="editForm.confidence" :min="0" :max="1" :step="0.05" show-input /></el-form-item>
      </el-form>
      <span slot="footer">
        <el-button size="small" @click="editVisible = false">取消</el-button>
        <el-button type="primary" size="small" @click="saveEdit">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import Api from '@/apis/api';

const COLORS = {
  agent: '#3f6fd8', category: '#7b6fd6', fact: '#1f9d7a', candidate: '#d89a2b', tombstone: '#d85858'
};

export default {
  name: 'MemoryManagement',
  data() {
    return {
      agentId: '', agentName: '', loading: false,
      searchText: '', rememberText: '', forgetText: '',
      editVisible: false, editMemoryId: '', editForm: {},
      dashboard: { facts: [], candidates: [], tombstones: [], revisions: [], toolBehaviors: [] },
      nodes: [], edges: [],
      selectedNode: null,
      panX: 0, panY: 0, scale: 1,
      dragNode: null, dragOffX: 0, dragOffY: 0,
      panning: false, panStartX: 0, panStartY: 0
    };
  },
  computed: {
    facts() { return (this.dashboard.facts || []).filter(f => f.status !== 'deleted'); },
    autoCount() { return this.facts.filter(f => f.confirmationState === 'auto').length; },
    categoryCount() { return new Set(this.facts.map(f => f.category || '未分类')).size; },
    tombstones() { return (this.dashboard.tombstones || []).filter(t => t.status === 'active'); },
    renderedEdges() {
      const m = {}; this.nodes.forEach(n => m[n.id] = n);
      return this.edges.map(e => ({
        ...e,
        x1: (m[e.sourceId] || {}).x || 0, y1: (m[e.sourceId] || {}).y || 0,
        x2: (m[e.targetId] || {}).x || 0, y2: (m[e.targetId] || {}).y || 0
      }));
    }
  },
  mounted() {
    this.agentId = this.$route.query.agentId || '';
    this.agentName = this.$route.query.agentName || '';
    if (!this.agentId) { this.$message.error('缺少智能体ID'); this.$router.push('/home'); return; }
    this.loadData();
    this._mousemove = this.onMouseMove.bind(this);
    this._mouseup = this.onMouseUp.bind(this);
    window.addEventListener('mousemove', this._mousemove);
    window.addEventListener('mouseup', this._mouseup);
  },
  beforeDestroy() {
    window.removeEventListener('mousemove', this._mousemove);
    window.removeEventListener('mouseup', this._mouseup);
  },
  methods: {
    pct(v) { return Math.round((Number(v) || 0) * 100); },
    categoryText(v) {
      const m = { explicit: '明确记住', manual: '手动维护' };
      return m[v] || v || '未分类';
    },
    nodeTypeLabel(t) {
      const m = { agent: '智能体', category: '分类', fact: '事实记忆', candidate: '候选', tombstone: '忘记规则' };
      return m[t] || t;
    },
    nodeTagType(t) {
      if (t === 'fact') return 'success';
      if (t === 'tombstone') return 'danger';
      if (t === 'candidate') return 'warning';
      return '';
    },

    loadData() {
      this.loading = true;
      Api.memory.dashboard(this.agentId, ({ data }) => {
        this.dashboard = data.data || this.dashboard;
        this.loading = false;
        this.$nextTick(() => this.buildGraph());
      }, () => { this.loading = false; });
    },

    buildGraph() {
      const svg = this.$refs.svg;
      if (!svg) return;
      const W = svg.clientWidth || 1000;
      const H = 560;
      const cx = W / 2, cy = H / 2;

      const facts = this.facts;
      const candidates = (this.dashboard.candidates || []).filter(c => c.status === 'pending');
      const tombstones = this.tombstones;

      // Group by category
      const catMap = new Map();
      facts.forEach(f => {
        const c = f.category || '未分类';
        if (!catMap.has(c)) catMap.set(c, []);
        catMap.get(c).push(f);
      });
      candidates.forEach(c => {
        const k = c.category || '候选';
        if (!catMap.has(k)) catMap.set(k, []);
        catMap.get(k).push(c);
      });

      const nodes = [], edges = [];
      let nid = 0;

      // Agent center (fixed)
      const agentId = ++nid;
      nodes.push({ id: agentId, nType: 'agent', label: this.agentName || '智能体', sublabel: `${facts.length} 条`, r: 36, x: cx, y: cy, color: COLORS.agent, stroke: '#2a50b5', fx: cx, fy: cy });

      // Categories ring
      const cats = Array.from(catMap.entries());
      const catRadius = 160;
      const catNodes = [];
      cats.forEach(([name, items], i) => {
        const angle = (2 * Math.PI / Math.max(cats.length, 1)) * i - Math.PI / 2;
        const x = cx + catRadius * Math.cos(angle);
        const y = cy + catRadius * Math.sin(angle);
        const id = ++nid;
        nodes.push({ id, nType: 'category', label: this.categoryText(name), sublabel: `${items.length} 条`, r: 24, x, y, color: COLORS.category, stroke: '#5e4fc1' });
        catNodes.push({ id, x, y, items });
        edges.push({ id: `e-${agentId}-${id}`, sourceId: agentId, targetId: id, highlight: false });
      });

      // Facts around categories
      catNodes.forEach(cn => {
        cn.items.forEach((item, i) => {
          const angle = (2 * Math.PI / Math.max(cn.items.length, 1)) * i + (Math.random() - 0.5) * 0.6;
          const dist = 100 + Math.random() * 50;
          const x = cn.x + dist * Math.cos(angle);
          const y = cn.y + dist * Math.sin(angle);
          const isFact = !item.candidateId;
          const importance = Number(item.importance || 0.5);
          const r = 10 + importance * 18;
          const id = ++nid;
          nodes.push({ id, nType: isFact ? 'fact' : 'candidate',
            label: (item.canonicalText || item.patternText || '?').slice(0, 8),
            sublabel: isFact ? (item.confirmationState === 'auto' ? '自动' : '手动') : '候选',
            r, x, y,
            color: isFact ? COLORS.fact : COLORS.candidate,
            stroke: isFact ? '#147a5e' : '#b87a1b',
            record: item });
          edges.push({ id: `e-${cn.id}-${id}`, sourceId: cn.id, targetId: id, highlight: false });
        });
      });

      // Tombstones
      tombstones.forEach((t, i) => {
        const angle = (2 * Math.PI / Math.max(tombstones.length, 1)) * i + Math.PI;
        const dist = 200;
        const x = cx + dist * Math.cos(angle);
        const y = cy + dist * Math.sin(angle);
        const id = ++nid;
        nodes.push({ id, nType: 'tombstone', label: (t.patternText || '忘记').slice(0, 8), sublabel: '忘记', r: 12, x, y, color: COLORS.tombstone, stroke: '#a83838', record: t });
        edges.push({ id: `e-${agentId}-${id}`, sourceId: agentId, targetId: id, highlight: false });
      });

      this.nodes = nodes;
      this.edges = edges;
      this.selectedNode = null;
      this.$nextTick(() => this.runSimulation(nodes, 80));
    },

    runSimulation(nodes, iters) {
      let iter = 0;
      const tick = () => {
        if (iter >= iters) return;
        iter++;
        const alpha = 0.4;
        // Repulsion
        for (let i = 0; i < nodes.length; i++) {
          const a = nodes[i]; if (a.fx !== undefined) continue;
          for (let j = i + 1; j < nodes.length; j++) {
            const b = nodes[j]; if (b.fx !== undefined) continue;
            let dx = a.x - b.x, dy = a.y - b.y;
            const dist = Math.max(1, Math.sqrt(dx * dx + dy * dy));
            const force = 6000 / (dist * dist);
            dx = (dx / dist) * force * alpha; dy = (dy / dist) * force * alpha;
            a.x += dx; a.y += dy; b.x -= dx; b.y -= dy;
          }
        }
        // Attraction
        this.edges.forEach(e => {
          const src = nodes.find(n => n.id === e.sourceId);
          const tgt = nodes.find(n => n.id === e.targetId);
          if (!src || !tgt) return;
          let dx = tgt.x - src.x, dy = tgt.y - src.y;
          const dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < 1) return;
          const force = (dist - 100) * 0.015 * alpha;
          dx = (dx / dist) * force; dy = (dy / dist) * force;
          if (src.fx === undefined) { src.x += dx; src.y += dy; }
          if (tgt.fx === undefined) { tgt.x -= dx; tgt.y -= dy; }
        });
        // Clamp
        nodes.forEach(n => {
          if (n.fx !== undefined) return;
          n.x = Math.max(20, Math.min(1100, n.x));
          n.y = Math.max(20, Math.min(540, n.y));
        });
        this.nodes = [...nodes];
        requestAnimationFrame(tick);
      };
      tick();
    },

    selectNode(n) {
      this.selectedNode = n;
      this.searchText = '';
      this.nodes.forEach(nn => { nn.highlight = (nn === n); nn.dimmed = false; });
      this.edges.forEach(e => { e.highlight = (e.sourceId === n.id || e.targetId === n.id); });
    },

    onNodeHover(n, enter) {
      if (enter) {
        document.body.style.cursor = 'pointer';
      }
    },

    onSearch() {
      const q = this.searchText.toLowerCase();
      this.nodes.forEach(n => {
        const match = q && ((n.label || '').toLowerCase().includes(q) || (n.sublabel || '').toLowerCase().includes(q));
        n.dimmed = q ? !match : false;
        n.highlight = q && match;
      });
    },

    onNodeDragStart(e, n) {
      if (e.button !== 0) return;
      this.dragNode = n;
      const svg = this.$refs.svg;
      const rect = svg.getBoundingClientRect();
      this.dragOffX = (e.clientX - rect.left - this.panX) / this.scale - n.x;
      this.dragOffY = (e.clientY - rect.top - this.panY) / this.scale - n.y;
    },

    onPanStart(e) {
      if (e.target === this.$refs.svg || e.target.tagName === 'svg') {
        this.panning = true;
        this.panStartX = e.clientX - this.panX;
        this.panStartY = e.clientY - this.panY;
      }
    },

    onMouseMove(e) {
      if (this.dragNode) {
        const svg = this.$refs.svg; if (!svg) return;
        const rect = svg.getBoundingClientRect();
        this.dragNode.x = (e.clientX - rect.left - this.panX) / this.scale - this.dragOffX;
        this.dragNode.y = (e.clientY - rect.top - this.panY) / this.scale - this.dragOffY;
        this.nodes = [...this.nodes];
      } else if (this.panning) {
        this.panX = e.clientX - this.panStartX;
        this.panY = e.clientY - this.panStartY;
      }
    },

    onMouseUp() {
      this.dragNode = null;
      this.panning = false;
    },

    onWheel(e) {
      this.scale = Math.max(0.3, Math.min(3, this.scale * (e.deltaY > 0 ? 0.92 : 1.08)));
    },

    remember() {
      if (!this.rememberText.trim()) return;
      Api.memory.remember(this.agentId, { text: this.rememberText.trim(), category: 'manual' }, () => {
        this.$message.success('已写入'); this.rememberText = ''; this.loadData();
      });
    },
    forget() {
      if (!this.forgetText.trim()) return;
      Api.memory.forget(this.agentId, { text: this.forgetText.trim(), reason: '管理端手动忘记' }, () => {
        this.$message.success('已加入'); this.forgetText = ''; this.loadData();
      });
    },
    openEdit(row) {
      this.editMemoryId = row.memoryId;
      this.editForm = { memoryType: row.memoryType, category: row.category, canonicalText: row.canonicalText, confidence: Number(row.confidence || 0.5), importance: Number(row.importance || 0.5) };
      this.editVisible = true;
    },
    saveEdit() {
      Api.memory.updateFact(this.agentId, this.editMemoryId, this.editForm, () => { this.$message.success('已保存'); this.editVisible = false; this.loadData(); });
    },
    archiveFact(row) { Api.memory.archiveFact(this.agentId, row.memoryId, () => { this.$message.success('已归档'); this.loadData(); }); },
    deleteFact(row) {
      this.$confirm('确定删除？', '提示', { type: 'warning' }).then(() => {
        Api.memory.deleteFact(this.agentId, row.memoryId, () => { this.$message.success('已删除'); this.loadData(); });
      }).catch(() => {});
    }
  }
};
</script>

<style scoped>
.memory-page {
  min-height: 100vh; padding: 20px 24px; box-sizing: border-box;
  background: #f5f7fb;
}
.memory-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 12px;
}
.memory-title { font-size: 22px; font-weight: 700; color: #2f3548; }
.memory-subtitle { font-size: 12px; color: #909399; margin-top: 2px; }
.header-actions { display: flex; gap: 8px; align-items: center; }

.stats-row {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 10px;
}
.stat-item {
  padding: 12px 16px; border-radius: 8px; background: #fff;
  border: 1px solid #e4e8f1; text-align: center;
}
.stat-num { font-size: 26px; font-weight: 700; color: #2f3548; display: block; }
.stat-label { font-size: 12px; color: #909399; margin-top: 2px; }
.stat-item.primary .stat-num { color: #3f6fd8; }
.stat-item.danger .stat-num { color: #e05555; }

.action-row {
  display: flex; gap: 8px; align-items: center;
  padding: 10px 14px; border-radius: 8px; background: #fff;
  border: 1px solid #e4e8f1; margin-bottom: 10px;
}
.action-hint { font-size: 11px; color: #b0b8c8; margin-left: auto; white-space: nowrap; }

.graph-container {
  position: relative; width: 100%; height: 560px;
  border-radius: 8px; background: #fff; border: 1px solid #e4e8f1;
  overflow: hidden;
}
.graph-svg { width: 100%; height: 100%; display: block; }
.graph-empty {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%,-50%);
  font-size: 14px; color: #c0c4cc;
}

.legend {
  display: flex; gap: 16px; margin-top: 10px; font-size: 12px; color: #909399;
  padding: 8px 14px; background: #fff; border-radius: 6px; border: 1px solid #e4e8f1;
}
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; margin-right: 4px; }

.detail-panel {
  margin-top: 12px; padding: 16px; border-radius: 8px;
  background: #fff; border: 1px solid #e4e8f1;
}
.detail-header {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 12px;
}
.detail-grid { display: grid; gap: 8px; }
.detail-kv {
  display: flex; justify-content: space-between; align-items: center;
  font-size: 13px; color: #606266;
}
.detail-kv span:last-child { font-weight: 500; color: #303133; text-align: right; }
.detail-actions {
  display: flex; gap: 8px; margin-top: 14px; padding-top: 12px;
  border-top: 1px solid #ebeef5;
}

@media (max-width: 768px) {
  .stats-row { grid-template-columns: repeat(2, 1fr); }
  .action-row { flex-wrap: wrap; }
  .action-hint { display: none; }
}
</style>
