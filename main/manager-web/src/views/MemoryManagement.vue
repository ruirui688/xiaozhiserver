<template>
  <div class="memory-page">
    <!-- Header -->
    <div class="top-bar">
      <div>
        <div class="page-title">记忆知识图谱</div>
        <div class="page-subtitle">{{ agentName || agentId }}</div>
      </div>
      <div class="top-actions">
        <el-input v-model="searchText" placeholder="搜索节点..." size="small" clearable prefix-icon="el-icon-search" style="width:200px" @input="onSearch" />
        <el-button size="small" @click="$router.push('/home')">返回</el-button>
      </div>
    </div>

    <!-- Stats -->
    <div class="stats-row">
      <div class="stat-card">
        <div class="stat-num">{{ facts.length }}</div>
        <div class="stat-label">事实记忆</div>
      </div>
      <div class="stat-card accent">
        <div class="stat-num">{{ autoCount }}</div>
        <div class="stat-label">自动生成</div>
      </div>
      <div class="stat-card">
        <div class="stat-num">{{ categoryCount }}</div>
        <div class="stat-label">记忆分类</div>
      </div>
      <div class="stat-card warn">
        <div class="stat-num">{{ tombstones.length }}</div>
        <div class="stat-label">忘记规则</div>
      </div>
    </div>

    <!-- Quick actions -->
    <div class="quick-row">
      <el-input v-model="rememberText" placeholder="输入需要记住的内容，回车写入" size="small" clearable @keyup.enter.native="remember" style="flex:1" />
      <el-button type="primary" size="small" icon="el-icon-plus" @click="remember">记住</el-button>
      <el-input v-model="forgetText" placeholder="输入需要忘记的内容" size="small" clearable @keyup.enter.native="forget" style="flex:1;margin-left:16px" />
      <el-button type="danger" size="small" icon="el-icon-delete" @click="forget">忘记</el-button>
      <el-button size="small" icon="el-icon-refresh" @click="loadData" :loading="loading" style="margin-left:8px">刷新</el-button>
    </div>

    <!-- Legend -->
    <div class="legend-row">
      <span class="legend-item"><i class="dot agent"></i> 智能体</span>
      <span class="legend-item"><i class="dot category"></i> 分类</span>
      <span class="legend-item"><i class="dot fact"></i> 事实</span>
      <span class="legend-item"><i class="dot tombstone"></i> 忘记</span>
      <span class="legend-item hint">拖拽节点 · 滚轮缩放 · 双击复位</span>
    </div>

    <!-- Graph -->
    <div class="graph-wrap" ref="graphWrap" v-loading="loading">
      <svg ref="graphSvg" class="graph-svg" xmlns="http://www.w3.org/2000/svg">
        <defs>
          <filter id="glow">
            <feGaussianBlur stdDeviation="3" result="blur" />
            <feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge>
          </filter>
          <marker id="arrow" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto">
            <path d="M0,0 L6,3 L0,6 Z" fill="#c0c9dd" />
          </marker>
        </defs>
        <g :transform="`translate(${panX},${panY}) scale(${scale})`">
          <line v-for="e in renderedEdges" :key="e.id" :x1="e.x1" :y1="e.y1" :x2="e.x2" :y2="e.y2"
            class="graph-line" :class="{ highlight: e.highlight }"
            marker-end="url(#arrow)" />
          <g v-for="n in nodes" :key="n.id" class="graph-node-group"
            :class="{ highlight: n.highlight, dim: n.dimmed }"
            :transform="`translate(${n.x},${n.y})`"
            @mousedown.prevent="startDrag($event, n)"
            @mouseenter="hoverNode(n, true)"
            @mouseleave="hoverNode(n, false)"
            @dblclick="selectNode(n)">
            <circle :r="n.r" :fill="n.color" :stroke="n.stroke"
              :stroke-width="n === selectedNode ? 3 : 1.5"
              :filter="n === selectedNode ? 'url(#glow)' : ''"
              :opacity="n.opacity" />
            <text class="node-label" :y="n.r + 16" :font-size="n.fontSize">{{ n.label }}</text>
            <text class="node-sublabel" :y="n.r + 30" :font-size="11">{{ n.sublabel }}</text>
          </g>
        </g>
      </svg>
      <div class="graph-watermark" v-if="!nodes.length && !loading">暂无记忆数据，与设备对话后自动生成</div>
    </div>

    <!-- Detail panel -->
    <div class="detail-panel" v-if="selectedNode">
      <div class="detail-head">
        <div>
          <el-tag size="mini" :type="selectedNode.nType === 'fact' ? 'success' : selectedNode.nType === 'tombstone' ? 'danger' : ''">
            {{ nodeTypeLabel(selectedNode.nType) }}
          </el-tag>
          <span class="detail-title">{{ selectedNode.record ? (selectedNode.record.canonicalText || selectedNode.record.patternText || '') : '' }}</span>
        </div>
        <el-button size="mini" icon="el-icon-close" circle @click="selectedNode = null" />
      </div>
      <div class="detail-body" v-if="selectedNode.nType === 'fact' && selectedNode.record">
        <div class="detail-row"><span>分类</span><span>{{ categoryText(selectedNode.record.category) }}</span></div>
        <div class="detail-row"><span>类型</span><span>{{ selectedNode.record.memoryType || '长期记忆' }}</span></div>
        <div class="detail-row"><span>来源</span><span>{{ selectedNode.record.confirmationState === 'auto' ? '🤖 自动抽取' : '📝 手动创建' }}</span></div>
        <div class="detail-row"><span>重要性</span><el-progress :percentage="pct(selectedNode.record.importance)" :stroke-width="6" style="width:140px;display:inline-flex" /></div>
        <div class="detail-row"><span>留存率</span><el-progress :percentage="pct(selectedNode.record.confidence)" :stroke-width="6" style="width:140px;display:inline-flex" /></div>
        <div class="detail-row"><span>状态</span><span>{{ selectedNode.record.status === 'active' ? '生效中' : selectedNode.record.status }}</span></div>
      </div>
      <div class="detail-body" v-else-if="selectedNode.nType === 'tombstone' && selectedNode.record">
        <div class="detail-row"><span>原因</span><span>{{ selectedNode.record.reason || '-' }}</span></div>
        <div class="detail-row"><span>状态</span><span>{{ selectedNode.record.status === 'active' ? '生效中' : selectedNode.record.status }}</span></div>
      </div>
      <div class="detail-actions">
        <template v-if="selectedNode.nType === 'fact'">
          <el-button size="mini" icon="el-icon-edit" @click="openEdit(selectedNode.record)">编辑</el-button>
          <el-button size="mini" icon="el-icon-folder" @click="archiveFact(selectedNode.record)">归档</el-button>
          <el-button size="mini" type="danger" icon="el-icon-delete" @click="deleteFact(selectedNode.record)">删除</el-button>
        </template>
      </div>
    </div>

    <!-- Bottom collapsible -->
    <el-collapse style="margin-top:12px" v-if="forgetList.length || revisions.length">
      <el-collapse-item :title="`忘记名单 (${forgetList.length})`" name="tombstones" v-if="forgetList.length">
        <el-table :data="forgetList" border size="mini" max-height="200">
          <el-table-column prop="patternText" label="匹配内容" min-width="300" show-overflow-tooltip />
          <el-table-column prop="reason" label="原因" width="180" />
          <el-table-column prop="createdAt" label="时间" width="160" />
        </el-table>
      </el-collapse-item>
      <el-collapse-item :title="`审计记录 (${revisions.length})`" name="revisions" v-if="revisions.length">
        <el-table :data="revisions" border size="mini" max-height="200">
          <el-table-column prop="revisionAction" label="动作" width="120" />
          <el-table-column prop="reason" label="原因" min-width="240" show-overflow-tooltip />
          <el-table-column prop="actorType" label="来源" width="80" />
          <el-table-column prop="createdAt" label="时间" width="160" />
        </el-table>
      </el-collapse-item>
    </el-collapse>

    <!-- Edit dialog -->
    <el-dialog title="编辑记忆" :visible.sync="editVisible" width="440px">
      <el-form :model="editForm" label-width="70px" size="small">
        <el-form-item label="类型">
          <el-input v-model="editForm.memoryType" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="editForm.category" />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="editForm.canonicalText" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="重要性">
          <el-slider v-model="editForm.importance" :min="0" :max="1" :step="0.05" show-input />
        </el-form-item>
        <el-form-item label="置信度">
          <el-slider v-model="editForm.confidence" :min="0" :max="1" :step="0.05" show-input />
        </el-form-item>
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
  agent: '#3f6fd8',
  category: '#7b6fd6',
  fact: '#1f9d7a',
  tombstone: '#d85858'
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
      selectedNode: null, hoveredNode: null,
      panX: 0, panY: 0, scale: 1,
      dragging: null, dragStartX: 0, dragStartY: 0,
      panning: false, panStartX: 0, panStartY: 0,
      simTimer: null
    };
  },
  computed: {
    facts() { return (this.dashboard.facts || []).filter(f => f.status !== 'deleted'); },
    autoCount() { return this.facts.filter(f => f.confirmationState === 'auto').length; },
    categoryCount() { return new Set(this.facts.map(f => f.category || '未分类')).size; },
    tombstones() { return (this.dashboard.tombstones || []).filter(t => t.status === 'active'); },
    forgetList() { return (this.dashboard.tombstones || []); },
    revisions() { return this.dashboard.revisions || []; },
    renderedEdges() {
      const nodeMap = {};
      this.nodes.forEach(n => { nodeMap[n.id] = n; });
      return this.edges.map(e => ({
        ...e,
        x1: (nodeMap[e.sourceId] || {}).x || e.x1 || 0,
        y1: (nodeMap[e.sourceId] || {}).y || e.y1 || 0,
        x2: (nodeMap[e.targetId] || {}).x || e.x2 || 0,
        y2: (nodeMap[e.targetId] || {}).y || e.y2 || 0
      }));
    }
  },
  mounted() {
    this.agentId = this.$route.query.agentId || '';
    this.agentName = this.$route.query.agentName || '';
    if (!this.agentId) { this.$message.error('缺少智能体ID'); this.$router.push('/home'); return; }
    this.loadData();
    this._onWheel = this.onWheel.bind(this);
    this._onMouseUp = this.onMouseUp.bind(this);
    this._onMouseMove = this.onMouseMove.bind(this);
    this._onDblClickBg = this.onDblClickBg.bind(this);
    window.addEventListener('mouseup', this._onMouseUp);
    window.addEventListener('mousemove', this._onMouseMove);
  },
  beforeDestroy() {
    window.removeEventListener('mouseup', this._onMouseUp);
    window.removeEventListener('mousemove', this._onMouseMove);
    if (this.simTimer) clearTimeout(this.simTimer);
  },
  methods: {
    pct(v) { return Math.round((Number(v) || 0) * 100); },
    categoryText(v) {
      const m = { explicit: '明确记住', manual: '手动维护', candidate: '候选' };
      return m[v] || v || '未分类';
    },
    nodeTypeLabel(t) {
      const m = { agent: '智能体', category: '分类', fact: '事实记忆', tombstone: '忘记规则' };
      return m[t] || t;
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
      const facts = this.facts;
      const candidates = (this.dashboard.candidates || []).filter(c => c.status === 'pending');
      const tombstones = this.tombstones;
      const svg = this.$refs.graphSvg;
      if (!svg) return;
      const W = svg.clientWidth || 1100;
      const H = 600;
      const cx = W / 2, cy = H / 2;

      // Collect categories
      const catSet = new Map();
      facts.forEach(f => {
        const c = f.category || '未分类';
        if (!catSet.has(c)) catSet.set(c, []);
        catSet.get(c).push(f);
      });
      candidates.forEach(c => {
        const k = c.category || '候选';
        if (!catSet.has(k)) catSet.set(k, []);
        catSet.get(k).push(c);
      });

      const nodes = [], edges = [];
      let nodeId = 0;

      // Agent center
      nodes.push({
        id: ++nodeId, nType: 'agent', label: this.agentName || '智能体', sublabel: `${facts.length} 条事实`,
        r: 38, x: cx, y: cy, color: COLORS.agent, stroke: '#2a50b5', opacity: 1, fontSize: 13,
        fx: cx, fy: cy
      });
      const agentNid = nodeId;

      // Categories ring
      const cats = Array.from(catSet.entries());
      const catRadius = 150;
      const catNodes = [];
      cats.forEach(([catName, catFacts], i) => {
        const angle = (2 * Math.PI / cats.length) * i - Math.PI / 2;
        const x = cx + catRadius * Math.cos(angle);
        const y = cy + catRadius * Math.sin(angle);
        const id = ++nodeId;
        nodes.push({
          id, nType: 'category', label: this.categoryText(catName),
          sublabel: `${catFacts.length} 条`, r: 28, x, y,
          color: COLORS.category, stroke: '#5e4fc1', opacity: 1, fontSize: 12
        });
        catNodes.push({ id, x, y, name: catName, facts: catFacts });
        edges.push({ id: `e${edges.length}`, sourceId: agentNid, targetId: id, highlight: false });
      });

      // Facts & candidates in outer ring
      const outerRadius = 320;
      const allItems = [];
      facts.forEach(f => allItems.push({ type: 'fact', data: f, cat: f.category || '未分类' }));
      candidates.forEach(c => allItems.push({ type: 'candidate', data: c, cat: c.category || '候选' }));

      // Position facts near their category node
      catNodes.forEach(cn => {
        const count = cn.facts.length;
        cn.facts.forEach((item, i) => {
          const angle = (2 * Math.PI / Math.max(count, 1)) * i + (Math.random() - 0.5) * 0.5;
          const dist = 110 + Math.random() * 40;
          const x = cn.x + dist * Math.cos(angle);
          const y = cn.y + dist * Math.sin(angle);
          const isFact = !item.candidateId;
          const id = ++nodeId;
          const importance = Number(item.importance || 0.5);
          const r = 12 + importance * 16;
          nodes.push({
            id, nType: 'fact', label: (item.canonicalText || item.patternText || '?').slice(0, 8),
            sublabel: isFact ? (item.confirmationState === 'auto' ? '自动' : '手动') : '候选',
            r, x, y, color: isFact ? COLORS.fact : '#d89a2b',
            stroke: isFact ? '#147a5e' : '#b87a1b',
            opacity: item.status === 'pending' ? 0.7 : 1, fontSize: 11,
            record: item
          });
          edges.push({ id: `e${edges.length}`, sourceId: cn.id, targetId: id, highlight: false });
        });
      });

      // Tombstones
      tombstones.forEach((t, i) => {
        const angle = (2 * Math.PI / Math.max(tombstones.length, 1)) * i + Math.PI;
        const dist = 180 + Math.random() * 30;
        const x = cx + dist * Math.cos(angle);
        const y = cy + dist * Math.sin(angle);
        const id = ++nodeId;
        nodes.push({
          id, nType: 'tombstone', label: (t.patternText || '忘记').slice(0, 8),
          sublabel: '忘记规则', r: 14, x, y,
          color: COLORS.tombstone, stroke: '#a83838', opacity: 0.8, fontSize: 11,
          record: t
        });
        edges.push({ id: `e${edges.length}`, x1: cx, y1: cy, x2: x, y2: y, highlight: false });
      });

      this.nodes = nodes;
      this.edges = edges;

      // Run force simulation
      this.$nextTick(() => this.runSimulation(nodes, edges, 100));
    },

    runSimulation(nodes, edges, iterations) {
      if (this.simTimer) clearTimeout(this.simTimer);
      const alpha = 0.3;
      const repulsion = 8000;
      const damping = 0.85;
      let iter = 0;

      const tick = () => {
        if (iter >= iterations) return;
        iter++;
        // Repulsion between all nodes
        for (let i = 0; i < nodes.length; i++) {
          if (nodes[i].fx !== undefined && nodes[i].fy !== undefined) continue;
          for (let j = i + 1; j < nodes.length; j++) {
            if (nodes[j].fx !== undefined && nodes[j].fy !== undefined) continue;
            const dx = nodes[i].x - nodes[j].x;
            const dy = nodes[i].y - nodes[j].y;
            const dist = Math.max(Math.sqrt(dx * dx + dy * dy), 1);
            const force = repulsion / (dist * dist);
            const fx = (dx / dist) * force;
            const fy = (dy / dist) * force;
            nodes[i].x += fx * alpha; nodes[i].y += fy * alpha;
            nodes[j].x -= fx * alpha; nodes[j].y -= fy * alpha;
          }
        }
        // Attraction along edges
        edges.forEach(e => {
          const src = nodes.find(n => n.id === e.sourceId);
          const tgt = nodes.find(n => n.id === e.targetId);
          if (!src || !tgt) return;
          const dx = tgt.x - src.x, dy = tgt.y - src.y;
          const dist = Math.sqrt(dx * dx + dy * dy);
          if (dist < 1) return;
          const targetLen = 100;
          const force = (dist - targetLen) * 0.01;
          const fx = (dx / dist) * force, fy = (dy / dist) * force;
          if (src.fx === undefined) { src.x += fx * alpha; src.y += fy * alpha; }
          if (tgt.fx === undefined) { tgt.x -= fx * alpha; tgt.y -= fy * alpha; }
        });
        this.nodes = [...nodes];
        this.simTimer = setTimeout(tick, 16);
      };
      tick();
    },

    selectNode(n) {
      this.selectedNode = n;
      this.searchText = '';
      this.nodes.forEach(nn => { nn.dimmed = false; nn.highlight = false; });
      if (n) {
        n.highlight = true;
        n.dimmed = false;
        this.edges.forEach(e => {
          e.highlight = (e.sourceId === n.id || e.targetId === n.id);
        });
      }
    },
    hoverNode(n, enter) {
      this.hoveredNode = enter ? n : null;
    },
    onSearch() {
      const q = this.searchText.toLowerCase();
      this.nodes.forEach(n => {
        n.dimmed = q ? !(n.label || '').toLowerCase().includes(q) &&
          !(n.sublabel || '').toLowerCase().includes(q) : false;
        n.highlight = q && ((n.label || '').toLowerCase().includes(q) ||
          (n.sublabel || '').toLowerCase().includes(q));
      });
    },
    onDblClickBg() {
      this.panX = 0; this.panY = 0; this.scale = 1;
      this.selectedNode = null;
    },
    startDrag(e, n) {
      if (e.button !== 0) return;
      e.preventDefault();
      if (n) {
        this.dragging = n;
        this.dragStartX = e.clientX - n.x;
        this.dragStartY = e.clientY - n.y;
      } else {
        this.panning = true;
        this.panStartX = e.clientX - this.panX;
        this.panStartY = e.clientY - this.panY;
      }
    },
    onMouseMove(e) {
      if (this.dragging) {
        const svg = this.$refs.graphSvg;
        if (!svg) return;
        const rect = svg.getBoundingClientRect();
        this.dragging.x = (e.clientX - rect.left - this.panX) / this.scale - this.dragStartX;
        this.dragging.y = (e.clientY - rect.top - this.panY) / this.scale - this.dragStartY;
        this.nodes = [...this.nodes];
        this.edges = this.edges.map(e => {
          const src = this.nodes.find(n => n.id === e.id.split('-')[0] || (n.x === e.x1 && n.y === e.y1));
          const tgt = this.nodes.find(n => n.id === e.id.split('-')[1] || (n.x === e.x2 && n.y === e.y2));
          return { ...e, x1: this.dragging.x, y1: this.dragging.y };
        });
      } else if (this.panning) {
        this.panX = e.clientX - this.panStartX;
        this.panY = e.clientY - this.panStartY;
      }
    },
    onMouseUp() {
      this.dragging = null;
      this.panning = false;
    },
    onWheel(e) {
      e.preventDefault();
      const delta = e.deltaY > 0 ? 0.9 : 1.1;
      this.scale = Math.max(0.2, Math.min(3, this.scale * delta));
    },

    // Data operations
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
      this.editForm = {
        memoryType: row.memoryType, category: row.category,
        canonicalText: row.canonicalText,
        confidence: Number(row.confidence || 0.5), importance: Number(row.importance || 0.5)
      };
      this.editVisible = true;
    },
    saveEdit() {
      Api.memory.updateFact(this.agentId, this.editMemoryId, this.editForm, () => {
        this.$message.success('已保存'); this.editVisible = false; this.loadData();
      });
    },
    archiveFact(row) {
      Api.memory.archiveFact(this.agentId, row.memoryId, () => { this.$message.success('已归档'); this.loadData(); });
    },
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
  min-height: 100vh; padding: 20px; box-sizing: border-box;
  background: #0f1119; color: #e2e6f0;
}
.top-bar {
  display: flex; justify-content: space-between; align-items: flex-start;
  margin-bottom: 12px;
}
.page-title { font-size: 22px; font-weight: 700; }
.page-subtitle { font-size: 12px; color: #737b91; margin-top: 2px; }
.top-actions { display: flex; gap: 8px; align-items: center; }

.stats-row {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 12px;
}
.stat-card {
  padding: 14px 16px; border-radius: 10px;
  background: #151829; border: 1px solid #1e2238;
}
.stat-num { font-size: 28px; font-weight: 800; color: #e2e6f0; }
.stat-label { font-size: 12px; color: #687186; margin-top: 2px; }
.stat-card.accent .stat-num { color: #5b8cf0; }
.stat-card.warn .stat-num { color: #e05555; }

.quick-row {
  display: flex; gap: 8px; align-items: center;
  padding: 10px 14px; border-radius: 10px;
  background: #151829; border: 1px solid #1e2238; margin-bottom: 10px;
}

.legend-row {
  display: flex; gap: 18px; align-items: center; margin-bottom: 10px;
  font-size: 12px; color: #687186;
}
.legend-item { display: flex; align-items: center; gap: 5px; }
.legend-item.hint { margin-left: auto; color: #4a5170; }
.dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
.dot.agent { background: #3f6fd8; } .dot.category { background: #7b6fd6; }
.dot.fact { background: #1f9d7a; } .dot.tombstone { background: #d85858; }

.graph-wrap {
  position: relative; width: 100%; height: 580px;
  border-radius: 10px; background: #0d0f16; border: 1px solid #1e2238;
  overflow: hidden; cursor: grab;
}
.graph-wrap:active { cursor: grabbing; }
.graph-svg { width: 100%; height: 100%; }
.graph-line {
  stroke: #2a2f45; stroke-width: 1.2; opacity: 0.7; transition: stroke 0.3s;
}
.graph-line.highlight { stroke: #5b8cf0; stroke-width: 2; opacity: 1; }
.graph-node-group { cursor: pointer; transition: opacity 0.3s; }
.graph-node-group.dim { opacity: 0.15; }
.graph-node-group.highlight { filter: drop-shadow(0 0 6px rgba(100,150,255,0.5)); }
.node-label { fill: #c8d0e0; text-anchor: middle; font-weight: 600; }
.node-sublabel { fill: #687186; text-anchor: middle; }
.graph-watermark {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%,-50%);
  font-size: 14px; color: #2a2f45;
}

.detail-panel {
  margin-top: 12px; padding: 16px; border-radius: 10px;
  background: #151829; border: 1px solid #1e2238;
}
.detail-head {
  display: flex; justify-content: space-between; align-items: center;
  margin-bottom: 12px;
}
.detail-title {
  font-size: 15px; font-weight: 600; color: #e2e6f0; margin-left: 8px;
}
.detail-body { display: grid; gap: 8px; }
.detail-row {
  display: flex; justify-content: space-between; align-items: center;
  font-size: 13px; color: #8f98b0;
}
.detail-row span:last-child { color: #c8d0e0; font-weight: 500; text-align: right; max-width: 280px; word-break: break-word; }
.detail-actions {
  display: flex; gap: 8px; margin-top: 14px; padding-top: 12px;
  border-top: 1px solid #1e2238;
}

@media (max-width: 768px) {
  .stats-row { grid-template-columns: repeat(2, 1fr); }
  .quick-row { flex-wrap: wrap; }
  .graph-wrap { height: 400px; }
}
</style>
