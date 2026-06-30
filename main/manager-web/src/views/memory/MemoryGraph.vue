<template>
  <div class="graph-tab">
    <!-- Toolbar -->
    <div class="graph-toolbar">
      <el-input v-model="searchQuery" placeholder="搜索节点..." prefix-icon="el-icon-search" clearable size="small" style="width:220px" @input="onFuzzySearch" />
      <el-select v-model="filterState" placeholder="来源" clearable size="small" style="width:110px">
        <el-option label="自动抽取" value="auto" />
        <el-option label="手动创建" value="manual" />
      </el-select>
      <el-select v-model="filterTier" placeholder="层级" clearable size="small" style="width:110px">
        <el-option label="长期记忆" value="LONG_TERM" />
        <el-option label="短期记忆" value="SHORT_TERM" />
        <el-option label="工作记忆" value="WORKING" />
      </el-select>
      <el-button size="small" icon="el-icon-rank" @click="buildGraph">自动布局</el-button>
      <el-button size="small" icon="el-icon-full-screen" @click="resetView">适应屏幕</el-button>
      <span class="graph-hint">拖拽画布平移 · 滚轮缩放 · 点击节点查看详情 · 点击分类折叠/展开</span>
    </div>

    <!-- Canvas -->
    <div class="graph-canvas" ref="canvas" @dblclick="resetView">
      <svg ref="svg" class="graph-svg"
        @mousedown="startPan"
        @wheel.prevent="doZoom"
        @mousemove="doMove"
        @mouseup="endDrag"
        @mouseleave="endDrag">
        <defs>
          <filter id="drop"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="#00000018" /></filter>
          <filter id="dropHl"><feDropShadow dx="0" dy="3" stdDeviation="6" flood-color="#3f6fd844" /></filter>
          <filter id="glow"><feGaussianBlur stdDeviation="3" result="blur" /><feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge></filter>
          <marker id="arrowhead" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6 Z" fill="#c8d0e0" /></marker>
        </defs>

        <pattern id="grid" width="40" height="40" patternUnits="userSpaceOnUse">
          <circle cx="20" cy="20" r="1" fill="#e8ecf4" />
        </pattern>
        <rect width="100%" height="100%" fill="url(#grid)" />

        <g :transform="`translate(${px},${py}) scale(${zoom})`">
          <!-- Edges -->
          <path v-for="e in visibleEdges" :key="e.id"
            :d="e.path"
            fill="none"
            :stroke="e.hl ? '#3f6fd8' : '#c8d0e0'"
            :stroke-width="e.hl ? 2.5 : 1.5"
            :opacity="e.hl ? 1 : 0.5"
            marker-end="url(#arrowhead)"
            style="transition: stroke 0.3s, opacity 0.3s" />

          <!-- Nodes -->
          <g v-for="n in visibleNodes" :key="n.id"
            :transform="`translate(${n.x},${n.y})`"
            :opacity="n.dim ? 0.1 : 1"
            class="graph-node"
            @click.stop="pick(n)"
            @mousedown.stop="startDrag($event, n)">

            <!-- Agent center node -->
            <template v-if="n.type==='agent'">
              <circle r="42" fill="#eef4ff" stroke="#3f6fd8" stroke-width="2.5"
                :filter="n===picked?'url(#dropHl)':n.hl?'url(#glow)':'url(#drop)'" />
              <text text-anchor="middle" dy="-4" font-size="14" font-weight="700" fill="#2f3548">{{ n.label }}</text>
              <text text-anchor="middle" dy="14" font-size="11" fill="#687186">{{ n.sub }}</text>
            </template>

            <!-- Category node -->
            <template v-else-if="n.type==='cat'">
              <circle :r="26" :fill="n.bg" :stroke="n===picked?'#3f6fd8':n.hl?'#3f6fd8':n.stroke" stroke-width="2"
                :filter="n===picked?'url(#dropHl)':n.hl?'url(#glow)':'url(#drop)'" />
              <text text-anchor="middle" dy="-4" font-size="12" font-weight="600" fill="#2f3548">{{ n.label }}</text>
              <text text-anchor="middle" dy="12" font-size="10" fill="#687186">
                {{ n.collapsed ? `(${n.factCount})` : n.sub }}
              </text>
            </template>

            <!-- Fact card -->
            <template v-else-if="n.type==='fact'">
              <rect :x="-n.w/2" :y="-n.h/2" :width="n.w" :height="n.h" rx="8"
                :fill="n.bg" :stroke="n===picked?'#3f6fd8':n.hl?'#3f6fd8':n.stroke" stroke-width="1.5"
                :filter="n===picked?'url(#dropHl)':n.hl?'url(#glow)':'url(#drop)'"
                style="transition: stroke 0.2s" />
              <text :x="-n.w/2+14" y="-6" font-size="13" font-weight="600" fill="#2f3548" text-anchor="start">
                <tspan v-if="n.line1.length <= 16">{{ n.line1 }}</tspan>
                <tspan v-else>{{ n.line1.slice(0, 16) }}…</tspan>
              </text>
              <text :x="-n.w/2+14" y="12" font-size="11" fill="#687186" text-anchor="start">{{ n.sub }}</text>
              <rect :x="n.w/2-14" y="-n.h/2+4" width="10" height="10" rx="5" :fill="n.dot" />
            </template>

            <!-- Tombstone -->
            <template v-else-if="n.type==='tomb'">
              <rect :x="-n.w/2" :y="-n.h/2" :width="n.w" :height="n.h" rx="6"
                :fill="n.bg" :stroke="n===picked?'#e05555':n.hl?'#e05555':n.stroke" stroke-width="1.5"
                :filter="n===picked?'url(#dropHl)':n.hl?'url(#glow)':''" />
              <text :x="-n.w/2+10" y="-4" font-size="12" font-weight="600" fill="#c53030" text-anchor="start">{{ n.label }}</text>
              <text :x="-n.w/2+10" y="12" font-size="10" fill="#718096" text-anchor="start">{{ n.sub }}</text>
            </template>
          </g>
        </g>
      </svg>
      <div class="graph-empty" v-if="!visibleNodes.length && !loading">暂无记忆数据，开始对话后自动生成</div>
    </div>

    <!-- Legend -->
    <div class="graph-legend">
      <span><i class="graph-dot" style="background:#3f6fd8"></i> 智能体</span>
      <span><i class="graph-dot" style="background:#7c6fd6"></i> 分类</span>
      <span><i class="graph-dot" style="background:#2ecc71"></i> 自动记忆</span>
      <span><i class="graph-dot" style="background:#f0b060"></i> 手动记忆</span>
      <span><i class="graph-dot" style="background:#e05555"></i> 忘记规则</span>
    </div>
  </div>
</template>

<script>
export default {
  name: 'MemoryGraph',
  props: {
    facts: { type: Array, default: () => [] },
    tombstones: { type: Array, default: () => [] },
    agentName: { type: String, default: '' },
    loading: { type: Boolean, default: false }
  },
  data() {
    return {
      searchQuery: '',
      filterState: '',
      filterTier: '',
      nodes: [], edges: [], picked: null,
      px: 0, py: 0, zoom: 1,
      dragging: null, dragOffX: 0, dragOffY: 0,
      panning: false, panSX: 0, panSY: 0,
      collapsedCats: {},  // { categoryName: true/false }
      searchTimer: null
    }
  },
  computed: {
    visibleNodes() {
      return this.nodes.filter(n => {
        if (n.type !== 'fact' || !n.catName) return true
        return !this.collapsedCats[n.catName]
      })
    },
    visibleEdges() {
      const visibleNodeIds = new Set(this.visibleNodes.map(n => n.id))
      return this.edges.filter(e => {
        const parts = e.id.split('-').filter(p => p)
        return parts.length >= 2 && visibleNodeIds.has(Number(parts[1])) && visibleNodeIds.has(Number(parts[2]))
      })
    }
  },
  watch: {
    facts: { deep: true, handler() { this.$nextTick(() => this.buildGraph()) } },
    tombstones: { deep: true, handler() { this.$nextTick(() => this.buildGraph()) } },
    filterState() { this.applyFilters() },
    filterTier() { this.applyFilters() }
  },
  mounted() {
    this.$nextTick(() => this.buildGraph())
  },
  methods: {
    pct(v) { return Math.round((Number(v) || 0) * 100) },
    catLabel(v) { const m = { explicit: '明确记住', manual: '手动维护' }; return m[v] || v || '未分类' },

    buildGraph() {
      const W = this.$refs.svg ? this.$refs.svg.clientWidth : 1100
      const H = 600
      const activeFacts = this.facts.filter(f => f.status !== 'deleted')
      const activeTombs = (this.tombstones || []).filter(t => t.status === 'active')
      const nodes = [], edges = []
      let nid = 0

      // Agent center
      const ax = 90, ay = H / 2
      nodes.push({ id: ++nid, type: 'agent', label: this.agentName || '智能体', sub: `${activeFacts.length} 条事实`, x: ax, y: ay })

      // Group facts by category
      const groups = new Map()
      activeFacts.forEach(f => {
        const c = f.category || '未分类'
        if (!groups.has(c)) groups.set(c, [])
        groups.get(c).push(f)
      })
      const catList = Array.from(groups.entries())

      // Category layout
      const catX = 260
      const catSpacing = Math.min(110, Math.max(80, (H - 100) / Math.max(catList.length, 1)))
      const catStartY = ay - (catList.length - 1) * catSpacing / 2
      const catNodes = []

      catList.forEach(([name, items], i) => {
        const cy = catStartY + i * catSpacing
        const cid = ++nid
        const hue = (i * 137.508) % 360
        const bg = `hsl(${hue},45%,92%)`, stroke = `hsl(${hue},40%,65%)`
        const collapsed = this.collapsedCats[name] || false
        nodes.push({
          id: cid, type: 'cat',
          label: this.catLabel(name),
          sub: `${items.length} 条`,
          factCount: items.length,
          bg, stroke, x: catX, y: cy,
          catName: name,
          collapsed
        })
        catNodes.push({ id: cid, x: catX, y: cy, items, catName: name })
        edges.push({ id: `e-${nid - catList.length - 1}-${cid}`, path: this.curve(ax + 42, ay, catX - 26, cy), hl: false })
      })

      // Fact cards
      const cardW = 190, cardH = 52, cardGapX = 210, cardGapY = 60
      const factStartX = 440
      let globalCol = 0

      catNodes.forEach(cn => {
        const perCol = Math.min(8, Math.ceil(cn.items.length / Math.ceil(cn.items.length / 8)))
        cn.items.forEach((item, i) => {
          const col = Math.floor(i / perCol)
          const row = i % perCol
          const x = factStartX + col * cardGapX
          const y = cn.y - (perCol - 1) * cardGapY / 2 + row * cardGapY
          const isAuto = item.confirmationState === 'auto'
          const fid = ++nid
          const text = item.canonicalText || item.patternText || '?'
          nodes.push({
            id: fid, type: 'fact',
            line1: text, sub: isAuto ? '自动抽取' : '手动创建',
            w: cardW, h: cardH, x, y,
            bg: isAuto ? '#f0faf5' : '#fef9f0',
            stroke: isAuto ? '#b8e0cc' : '#e8d8b8',
            dot: isAuto ? '#2ecc71' : '#f0b060',
            catName: cn.catName,
            rec: item
          })
          edges.push({ id: `e-${cn.id}-${fid}`, path: this.curve(cn.x + 26, cn.y, x - cardW / 2, y), hl: false })
          globalCol = Math.max(globalCol, col + 1)
        })
      })

      // Tombstones
      const tombX = factStartX + globalCol * cardGapX + 40
      activeTombs.forEach((t, i) => {
        const y = 60 + i * 40
        const tid = ++nid
        nodes.push({
          id: tid, type: 'tomb',
          label: (t.patternText || '?').slice(0, 12),
          sub: t.reason || '忘记规则',
          w: 150, h: 36, x: tombX, y,
          bg: '#fff5f5', stroke: '#f5c0c0', rec: t
        })
        edges.push({ id: `e-1-${tid}`, path: this.curve(ax + 42, ay, tombX - 75, y), hl: false })
      })

      this.nodes = nodes
      this.edges = edges
      this.applyFilters()
    },

    curve(x1, y1, x2, y2) {
      const dx = Math.abs(x2 - x1) * 0.5
      return `M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}`
    },

    pick(n) {
      if (n.type === 'cat') {
        // Toggle category collapse/expand
        const catName = n.catName
        this.$set(this.collapsedCats, catName, !this.collapsedCats[catName])
        n.collapsed = this.collapsedCats[catName]
        return
      }
      this.picked = n
      this.searchQuery = ''
      this.nodes.forEach(x => { x.hl = (x === n); x.dim = false })
      this.edges.forEach(e => { e.hl = e.id.includes(`-${n.id}`) })
      if (n.rec) {
        this.$emit('open-detail', n.rec)
      }
    },

    onFuzzySearch() {
      clearTimeout(this.searchTimer)
      this.searchTimer = setTimeout(() => {
        const q = this.searchQuery.toLowerCase()
        this.nodes.forEach(n => {
          if (!q) { n.dim = false; n.hl = false; return }
          const line1 = (n.line1 || n.label || '').toLowerCase()
          const sub = (n.sub || '').toLowerCase()
          const rec = n.rec || {}
          const cat = (rec.category || '').toLowerCase()
          const type = (rec.memoryType || '').toLowerCase()
          const m = line1.includes(q) || sub.includes(q) || cat.includes(q) || type.includes(q)
          n.dim = !m
          n.hl = !!m
        })
      }, 300)
    },

    applyFilters() {
      this.nodes.forEach(n => {
        if (n.type !== 'fact' || !n.rec) return
        const rec = n.rec
        let dim = false
        if (this.filterState && (rec.confirmationState || 'auto') !== this.filterState) dim = true
        if (this.filterTier && (rec.memoryTier || '').toUpperCase() !== this.filterTier.toUpperCase()) dim = true
        if (this.searchQuery) {
          const q = this.searchQuery.toLowerCase()
          if (!(n.line1 || '').toLowerCase().includes(q)) dim = true
        }
        n.dim = dim
      })
    },

    // Pan/zoom/drag interactions
    startDrag(e, n) {
      if (e.button !== 0) return
      this.dragging = n
      const r = this.$refs.svg.getBoundingClientRect()
      this.dragOffX = (e.clientX - r.left - this.px) / this.zoom - n.x
      this.dragOffY = (e.clientY - r.top - this.py) / this.zoom - n.y
    },
    startPan(e) {
      if (e.target === this.$refs.svg || e.target.tagName === 'svg') {
        this.panning = true
        this.panSX = e.clientX - this.px
        this.panSY = e.clientY - this.py
      }
    },
    doMove(e) {
      if (this.dragging) {
        const r = this.$refs.svg.getBoundingClientRect()
        this.dragging.x = (e.clientX - r.left - this.px) / this.zoom - this.dragOffX
        this.dragging.y = (e.clientY - r.top - this.py) / this.zoom - this.dragOffY
        this.nodes = [...this.nodes]
      } else if (this.panning) {
        this.px = e.clientX - this.panSX
        this.py = e.clientY - this.panSY
      }
    },
    endDrag() { this.dragging = null; this.panning = false },
    doZoom(e) {
      this.zoom = Math.max(0.3, Math.min(2.5, this.zoom * (e.deltaY > 0 ? 0.92 : 1.08)))
    },
    resetView() {
      this.px = 0; this.py = 0; this.zoom = 1; this.picked = null
      this.searchQuery = ''; this.filterState = ''; this.filterTier = ''
      this.collapsedCats = {}
      if (this.nodes.length) {
        this.nodes.forEach(n => { n.dim = false; n.hl = false })
      }
    }
  }
}
</script>

<style scoped>
.graph-tab { }
.graph-toolbar {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 0; margin-bottom: 8px; flex-wrap: wrap;
}
.graph-hint { font-size: 11px; color: #a0aec0; margin-left: auto; white-space: nowrap; }

.graph-canvas {
  position: relative; width: 100%; height: 520px;
  border-radius: 10px; background: #fff;
  border: 1px solid #e8ecf4; overflow: hidden;
  box-shadow: 0 2px 8px rgba(0,0,0,.04);
}
.graph-svg { width: 100%; height: 100%; display: block; }
.graph-node { cursor: pointer; transition: opacity .25s; }
.graph-empty {
  position: absolute; top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  font-size: 15px; color: #cbd5e0;
}

.graph-legend {
  display: flex; gap: 20px; margin-top: 8px;
  font-size: 12px; color: #718096; padding: 8px 14px;
  background: #fff; border-radius: 8px; border: 1px solid #e8ecf4;
}
.graph-dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; margin-right: 5px; }
</style>
