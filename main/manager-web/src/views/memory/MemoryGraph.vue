<template>
  <div class="graph-tab">
    <!-- Toolbar -->
    <div class="graph-toolbar">
      <el-input v-model="searchQuery" placeholder="搜索节点..." prefix-icon="el-icon-search" clearable size="small" style="width:220px" @input="onFuzzySearch" />
      <el-select v-model="filterState" placeholder="来源" clearable size="small" style="width:110px">
        <el-option label="模型调用" value="auto" />
        <el-option label="手动创建" value="manual" />
      </el-select>
      <el-select v-model="filterTier" placeholder="层级" clearable size="small" style="width:110px">
        <el-option label="长期记忆" value="LONG_TERM" />
        <el-option label="短期记忆" value="SHORT_TERM" />
        <el-option label="工作记忆" value="WORKING" />
      </el-select>
      <el-tooltip content="重新排列当前图谱" placement="top">
        <el-button size="small" icon="el-icon-rank" @click="buildGraph">自动布局</el-button>
      </el-tooltip>
      <el-tooltip content="把所有节点放回当前屏幕" placement="top">
        <el-button size="small" icon="el-icon-full-screen" @click="fitView">适应屏幕</el-button>
      </el-tooltip>
      <el-button-group class="zoom-controls">
        <el-tooltip content="缩小" placement="top">
          <el-button size="small" icon="el-icon-minus" @click="zoomBy(0.88)" />
        </el-tooltip>
        <el-tooltip content="重置缩放" placement="top">
          <el-button size="small" @click="fitView">{{ Math.round(zoom * 100) }}%</el-button>
        </el-tooltip>
        <el-tooltip content="放大" placement="top">
          <el-button size="small" icon="el-icon-plus" @click="zoomBy(1.12)" />
        </el-tooltip>
      </el-button-group>
    </div>

    <!-- Canvas -->
    <div class="graph-canvas" ref="canvas" @dblclick="fitView">
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
            :d="edgePath(e)"
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
            @mousedown="startNodeInteraction($event, n)">

            <!-- Agent center node -->
            <template v-if="n.type==='agent'">
              <circle r="42" fill="#eef4ff" stroke="#3f6fd8" stroke-width="2.5"
                :filter="n===picked?'url(#dropHl)':n.hl?'url(#glow)':'url(#drop)'" />
              <text text-anchor="middle" dy="-4" font-size="14" font-weight="700" fill="#2f3548">{{ n.label }}</text>
              <text text-anchor="middle" dy="14" font-size="11" fill="#687186">{{ n.sub }}</text>
            </template>

            <!-- Voiceprint speaker node -->
            <template v-else-if="n.type==='speaker'">
              <circle r="32" fill="#edf8f4" :stroke="n===picked?'#267d62':'#58a98d'" stroke-width="2"
                :filter="n===picked?'url(#dropHl)':n.hl?'url(#glow)':'url(#drop)'" />
              <text text-anchor="middle" dy="-3" font-size="12" font-weight="700" fill="#285b4a">{{ n.label }}</text>
              <text text-anchor="middle" dy="13" font-size="10" fill="#5c7f73">{{ n.sub }}</text>
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
                <tspan v-if="n.line1.length <= 22">{{ n.line1 }}</tspan>
                <tspan v-else>{{ n.line1.slice(0, 22) }}…</tspan>
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
      <span><i class="graph-dot" style="background:#58a98d"></i> 声纹人物</span>
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
      panning: false, panSX: 0, panSY: 0, panStartX: 0, panStartY: 0,
      panMoved: false, suppressClick: false,
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
        return visibleNodeIds.has(e.from) && visibleNodeIds.has(e.to)
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
      const activeFacts = this.facts.filter(f => f.status !== 'deleted')
      const activeTombs = (this.tombstones || []).filter(t => t.status === 'active')
      const nodes = [], edges = []
      let nid = 0

      // Agent center
      const ax = 0
      const agentId = ++nid

      // Group facts by voiceprint identity, then by category.
      const speakerGroups = new Map()
      activeFacts.forEach(f => {
        const speakerKey = f.speakerId || '__shared__'
        if (!speakerGroups.has(speakerKey)) {
          speakerGroups.set(speakerKey, {
            key: speakerKey,
            label: f.speakerDisplay || '共享记忆',
            facts: []
          })
        }
        speakerGroups.get(speakerKey).facts.push(f)
      })
      const speakers = Array.from(speakerGroups.values())
        .sort((a, b) => {
          if (a.key === '__shared__') return 1
          if (b.key === '__shared__') return -1
          return a.label.localeCompare(b.label, 'zh-CN')
        })

      const cardW = 300, cardH = 58, cardGapX = 330, cardGapY = 76
      const lanes = []
      speakers.forEach(speaker => {
        const categories = new Map()
        speaker.facts.forEach(f => {
          const category = f.category || '未分类'
          if (!categories.has(category)) categories.set(category, [])
          categories.get(category).push(f)
        })
        Array.from(categories.entries())
          .sort((a, b) => b[1].length - a[1].length || String(a[0]).localeCompare(String(b[0]), 'zh-CN'))
          .forEach(([name, items]) => {
            const columns = items.length > 6 ? Math.ceil(items.length / 6) : 1
            const rows = Math.max(1, Math.ceil(items.length / columns))
            lanes.push({
              speaker,
              name,
              groupKey: `${speaker.key}::${name}`,
              items,
              columns,
              rows,
              height: Math.max(116, rows * cardGapY + 28)
            })
          })
      })
      const totalLaneHeight = lanes.reduce((sum, lane) => sum + lane.height, 0)
      const laneGap = 34
      let nextLaneY = -(totalLaneHeight + Math.max(0, lanes.length - 1) * laneGap) / 2
      const ay = 0
      nodes.push({ id: agentId, type: 'agent', label: this.agentName || '智能体', sub: `${activeFacts.length} 条事实`, x: ax, y: ay })

      // Speaker and category layout
      const speakerX = 165
      const catX = 345
      const factStartX = 565
      const catNodes = []
      const speakerLanePositions = new Map()

      lanes.forEach((lane, i) => {
        const cy = nextLaneY + lane.height / 2
        const cid = ++nid
        const hue = (i * 137.508) % 360
        const bg = `hsl(${hue},45%,92%)`, stroke = `hsl(${hue},40%,65%)`
        const collapsed = this.collapsedCats[lane.groupKey] || false
        nodes.push({
          id: cid, type: 'cat',
          label: this.catLabel(lane.name),
          sub: `${lane.items.length} 条`,
          factCount: lane.items.length,
          bg, stroke, x: catX, y: cy,
          catName: lane.groupKey,
          collapsed
        })
        catNodes.push({
          id: cid,
          x: catX,
          y: cy,
          items: lane.items,
          catName: lane.groupKey,
          rows: lane.rows,
          columns: lane.columns,
          height: lane.height
        })
        if (!speakerLanePositions.has(lane.speaker.key)) speakerLanePositions.set(lane.speaker.key, [])
        speakerLanePositions.get(lane.speaker.key).push({ y: cy, categoryId: cid })
        nextLaneY += lane.height + laneGap
      })

      speakers.forEach(speaker => {
        const positions = speakerLanePositions.get(speaker.key) || []
        if (!positions.length) return
        const sy = positions.reduce((sum, item) => sum + item.y, 0) / positions.length
        const sid = ++nid
        nodes.push({
          id: sid,
          type: 'speaker',
          label: speaker.label,
          sub: `${speaker.facts.length} 条`,
          x: speakerX,
          y: sy,
          speakerKey: speaker.key
        })
        edges.push({ id: `e-${agentId}-${sid}`, from: agentId, to: sid, hl: false })
        positions.forEach(position => {
          edges.push({ id: `e-${sid}-${position.categoryId}`, from: sid, to: position.categoryId, hl: false })
        })
      })

      // Fact cards
      let globalCol = 0

      catNodes.forEach(cn => {
        cn.items.forEach((item, i) => {
          const col = Math.floor(i / cn.rows)
          const row = i % cn.rows
          const x = factStartX + col * cardGapX
          const y = cn.y - (cn.rows - 1) * cardGapY / 2 + row * cardGapY
          const isAuto = item.confirmationState === 'auto'
          const fid = ++nid
          const text = item.canonicalText || item.patternText || '?'
          nodes.push({
            id: fid, type: 'fact',
            line1: text, sub: isAuto ? '模型调用' : '手动创建',
            w: cardW, h: cardH, x, y,
            bg: isAuto ? '#f0faf5' : '#fef9f0',
            stroke: isAuto ? '#b8e0cc' : '#e8d8b8',
            dot: isAuto ? '#2ecc71' : '#f0b060',
            catName: cn.catName,
            rec: item
          })
          edges.push({ id: `e-${cn.id}-${fid}`, from: cn.id, to: fid, hl: false })
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
        edges.push({ id: `e-${agentId}-${tid}`, from: agentId, to: tid, hl: false })
      })

      this.nodes = nodes
      this.edges = edges
      this.applyFilters()
      this.$nextTick(() => this.fitView())
    },

    curve(x1, y1, x2, y2) {
      const dx = Math.abs(x2 - x1) * 0.5
      return `M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}`
    },

    edgePath(e) {
      const from = this.nodes.find(n => n.id === e.from)
      const to = this.nodes.find(n => n.id === e.to)
      if (!from || !to) return ''
      const a = this.nodeAnchor(from, 1)
      const b = this.nodeAnchor(to, -1)
      return this.curve(a.x, a.y, b.x, b.y)
    },

    nodeAnchor(n, dir) {
      if (n.type === 'agent') return { x: n.x + dir * 42, y: n.y }
      if (n.type === 'speaker') return { x: n.x + dir * 32, y: n.y }
      if (n.type === 'cat') return { x: n.x + dir * 26, y: n.y }
      return { x: n.x + dir * ((n.w || 160) / 2), y: n.y }
    },

    pick(n) {
      if (this.suppressClick) {
        this.suppressClick = false
        return
      }
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
      this.edges.forEach(e => { e.hl = e.from === n.id || e.to === n.id })
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
    startNodeInteraction(e, n) {
      if (e.shiftKey || e.altKey) {
        e.stopPropagation()
        this.startDrag(e, n)
      }
    },
    startDrag(e, n) {
      if (e.button !== 0) return
      this.dragging = n
      const r = this.$refs.svg.getBoundingClientRect()
      this.dragOffX = (e.clientX - r.left - this.px) / this.zoom - n.x
      this.dragOffY = (e.clientY - r.top - this.py) / this.zoom - n.y
    },
    startPan(e) {
      if (e.button !== 0 || this.dragging) return
      this.panning = true
      this.panMoved = false
      this.panStartX = e.clientX
      this.panStartY = e.clientY
      this.panSX = e.clientX - this.px
      this.panSY = e.clientY - this.py
    },
    doMove(e) {
      if (this.dragging) {
        const r = this.$refs.svg.getBoundingClientRect()
        this.dragging.x = (e.clientX - r.left - this.px) / this.zoom - this.dragOffX
        this.dragging.y = (e.clientY - r.top - this.py) / this.zoom - this.dragOffY
        this.nodes = [...this.nodes]
      } else if (this.panning) {
        if (Math.abs(e.clientX - this.panStartX) > 3 || Math.abs(e.clientY - this.panStartY) > 3) {
          this.panMoved = true
        }
        this.px = e.clientX - this.panSX
        this.py = e.clientY - this.panSY
      }
    },
    endDrag() {
      if (this.panMoved) {
        this.suppressClick = true
        setTimeout(() => { this.suppressClick = false }, 0)
      }
      this.dragging = null
      this.panning = false
      this.panMoved = false
    },
    doZoom(e) {
      const r = this.$refs.svg.getBoundingClientRect()
      const sx = e.clientX - r.left
      const sy = e.clientY - r.top
      const beforeX = (sx - this.px) / this.zoom
      const beforeY = (sy - this.py) / this.zoom
      const nextZoom = Math.max(0.25, Math.min(2.8, this.zoom * (e.deltaY > 0 ? 0.9 : 1.1)))
      this.zoom = nextZoom
      this.px = sx - beforeX * nextZoom
      this.py = sy - beforeY * nextZoom
    },
    zoomBy(factor) {
      const r = this.$refs.svg.getBoundingClientRect()
      const sx = r.width / 2
      const sy = r.height / 2
      const beforeX = (sx - this.px) / this.zoom
      const beforeY = (sy - this.py) / this.zoom
      const nextZoom = Math.max(0.25, Math.min(2.8, this.zoom * factor))
      this.zoom = nextZoom
      this.px = sx - beforeX * nextZoom
      this.py = sy - beforeY * nextZoom
    },
    graphBounds() {
      const visible = this.visibleNodes.filter(n => !n.dim)
      const nodes = visible.length ? visible : this.visibleNodes
      if (!nodes.length) return null
      return nodes.reduce((box, n) => {
        const halfW = n.type === 'agent' ? 42 : n.type === 'speaker' ? 32 : n.type === 'cat' ? 26 : (n.w || 160) / 2
        const halfH = n.type === 'agent' ? 42 : n.type === 'speaker' ? 32 : n.type === 'cat' ? 26 : (n.h || 48) / 2
        return {
          minX: Math.min(box.minX, n.x - halfW),
          minY: Math.min(box.minY, n.y - halfH),
          maxX: Math.max(box.maxX, n.x + halfW),
          maxY: Math.max(box.maxY, n.y + halfH)
        }
      }, { minX: Infinity, minY: Infinity, maxX: -Infinity, maxY: -Infinity })
    },
    fitView() {
      const box = this.graphBounds()
      const svg = this.$refs.svg
      if (!box || !svg) return
      const w = svg.clientWidth || 1000
      const h = svg.clientHeight || 520
      const padding = 56
      const graphW = Math.max(1, box.maxX - box.minX)
      const graphH = Math.max(1, box.maxY - box.minY)
      const nextZoom = Math.max(0.25, Math.min(1.25, Math.min((w - padding * 2) / graphW, (h - padding * 2) / graphH)))
      this.zoom = nextZoom
      this.px = (w - graphW * nextZoom) / 2 - box.minX * nextZoom
      this.py = (h - graphH * nextZoom) / 2 - box.minY * nextZoom
      this.picked = null
      if (this.nodes.length) {
        this.nodes.forEach(n => { n.hl = false })
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
.zoom-controls { margin-left: 2px; }

.graph-canvas {
  position: relative; width: 100%; height: min(640px, calc(100vh - 300px)); min-height: 520px;
  border-radius: 8px; background: #fff;
  border: 1px solid #e8ecf4; overflow: hidden;
  box-shadow: 0 2px 8px rgba(0,0,0,.04);
}
.graph-svg { width: 100%; height: 100%; display: block; cursor: grab; touch-action: none; }
.graph-svg:active { cursor: grabbing; }
.graph-node { cursor: pointer; transition: opacity .25s; }
.graph-empty {
  position: absolute; top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  font-size: 15px; color: #cbd5e0;
}

.graph-legend {
  display: flex; gap: 20px; margin-top: 8px; flex-wrap: wrap;
  font-size: 12px; color: #718096; padding: 8px 14px;
  background: #fff; border-radius: 8px; border: 1px solid #e8ecf4;
}
.graph-dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; margin-right: 5px; }
</style>
