<template>
  <section class="memory-graph" aria-label="记忆归属图谱" v-loading="loading">
    <div class="graph-toolbar">
      <el-input v-model="query" clearable size="small" class="graph-search" placeholder="搜索正文、人物、设备或分类" aria-label="搜索记忆图谱" prefix-icon="el-icon-search" />
      <el-select v-model="speaker" clearable size="small" placeholder="全部人物与共享" aria-label="筛选人物与共享范围">
        <el-option v-for="option in graph.options.speakers" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-select v-model="device" clearable size="small" :placeholder="deviceScoped ? '全部设备分区' : '全部来源设备'" :aria-label="deviceScoped ? '筛选设备分区' : '筛选来源设备'">
        <el-option v-for="option in graph.options.devices" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-select v-model="status" clearable size="small" placeholder="全部事实状态" aria-label="筛选事实状态">
        <el-option label="生效事实" value="active" /><el-option label="已到期事实" value="expired" />
        <el-option label="已归档事实" value="archived" /><el-option label="状态待核实" value="unknown" />
      </el-select>
      <el-select v-model="source" clearable size="small" placeholder="全部事实来源" aria-label="筛选事实来源">
        <el-option v-for="option in sourceOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-select v-model="tier" clearable size="small" placeholder="全部记忆层级" aria-label="筛选记忆层级">
        <el-option label="长期记忆" value="LONG_TERM" /><el-option label="短期记忆" value="SHORT_TERM" /><el-option label="工作记忆" value="WORKING" />
      </el-select>
      <el-button size="small" @click="resetFilters">重置筛选</el-button>
    </div>
    <div class="graph-summary" aria-live="polite">
      <span>当前显示 {{ graph.factCount }} 条事实 · {{ graph.ruleCount }} 条生效遗忘规则</span>
      <span>当前加载 {{ graph.totalFacts }} 条事实 · {{ graph.totalRules }} 条规则</span>
    </div>
    <p class="graph-note">{{ !graph.scopeKnown ? '等待记忆策略，确认设备与人物范围后显示连线。' : deviceScoped ? '按设备隔离展示；缺少设备归属的历史数据单独列出。' : '同一智能体内跨设备使用；人物私有与共享记忆分开展示，设备筛选仅表示数据来源。' }}连线表示归属与分类，不表示语义关联或本轮已注入。</p>
    <div class="graph-actions">
      <span>点击分组展开或收起，点击事实或规则查看详情。人物、设备与搜索共同筛选；状态、来源、层级只筛选事实。</span>
      <el-button-group>
        <el-button size="small" icon="el-icon-minus" aria-label="缩小图谱" @click="zoomBy(0.8)" />
        <el-button size="small" aria-label="适应屏幕" @click="fitView">适应屏幕</el-button>
        <el-button size="small" icon="el-icon-plus" aria-label="放大图谱" @click="zoomBy(1.25)" />
      </el-button-group>
      <el-button size="small" @click="expandAll">展开全部</el-button>
      <span class="zoom-value">{{ Math.round(zoom * 100) }}%</span>
    </div>
    <div ref="canvas" class="graph-canvas">
      <svg ref="svg" class="graph-svg" role="group" tabindex="0" aria-label="记忆图谱：方向键平移，加减键缩放，Home适应屏幕；节点按Enter或空格操作"
        @keydown="onCanvasKey" @pointerdown="startPan" @pointermove="movePan" @pointerup="endPan" @pointercancel="endPan" @wheel.prevent="wheelZoom">
        <g :transform="`translate(${panX},${panY}) scale(${zoom})`">
          <path v-for="edge in graph.edges" :key="edge.id" :d="edgePath(edge)" class="graph-edge" aria-hidden="true" />
          <g v-for="node in graph.nodes" :key="node.id" ref="graphNodes" :data-node-id="node.id" :transform="`translate(${node.x},${node.y})`"
            class="graph-node" :class="[node.type, node.status, { selected: pickedId === node.id }]"
            tabindex="0" role="button" :aria-label="nodeAria(node)" :aria-expanded="node.hasChildren ? String(!node.collapsed) : undefined"
            @pointerdown.stop @click.stop="pick(node)" @keydown.stop="onNodeKey($event, node)">
            <title>{{ nodeAria(node) }}</title>
            <rect :x="-node.width / 2" :y="-node.height / 2" :width="node.width" :height="node.height" rx="10" />
            <text :x="-node.width / 2 + 12" :y="node.rec ? -14 : -10" class="node-title">{{ short(node.label, node.rec ? 19 : 11) }}</text>
            <text :x="-node.width / 2 + 12" :y="node.rec ? 7 : 10" class="node-sub">{{ short(node.sub, node.rec ? 28 : 14) }}</text>
            <text v-if="node.rec" :x="-node.width / 2 + 12" y="27" class="node-sub">{{ short(node.source, 28) }}</text>
            <text v-if="node.hasChildren" :x="node.width / 2 - 12" y="26" class="node-count" text-anchor="end">{{ node.collapsed ? '+' : '−' }} {{ node.count }} 项</text>
          </g>
        </g>
      </svg>
      <div v-if="!graph.nodes.length && !loading" class="graph-empty" role="status">
        <strong>{{ !graph.scopeKnown ? '记忆策略尚未加载' : graph.totalFacts || graph.totalRules ? '没有符合当前筛选的记忆或规则' : '当前加载范围内没有记忆或遗忘规则' }}</strong>
        <span>{{ !graph.scopeKnown ? '请在记忆策略页确认加载成功后重试。' : graph.totalFacts || graph.totalRules ? '调整人物、设备、事实状态或搜索条件。' : '新事实或遗忘规则保存后，可在这里查看归属。' }}</span>
      </div>
    </div>
    <div class="graph-legend">
      <span class="legend-owner">归属分组</span><span class="legend-active">生效事实</span><span class="legend-expired">已到期</span>
      <span class="legend-archived">已归档</span><span class="legend-rule">遗忘规则</span>
      <span>键盘：Tab选择节点，Enter/空格操作；画布方向键平移，+/−缩放。</span>
    </div>
  </section>
</template>

<script>
import { buildMemoryGraph, graphBounds } from './memoryGraphModel.mjs'
import { memorySourceLabel } from './memoryVisualState.mjs'

export default {
  name: 'MemoryGraph',
  props: {
    facts: { type: Array, default: () => [] }, tombstones: { type: Array, default: () => [] },
    policy: { type: Object, default: null }, agentId: { type: String, default: '' },
    agentName: { type: String, default: '' }, loading: { type: Boolean, default: false },
    active: { type: Boolean, default: true }, now: { type: Number, default: null }
  },
  data() {
    return { query: '', speaker: '', device: '', status: 'active', source: '', tier: '', collapsed: {}, pickedId: '',
      panX: 0, panY: 0, zoom: 1, pan: null, clock: Date.now(), fitTimer: null, clockTimer: null,
      resizeObserver: null, lastWidth: 0, lastHeight: 0, disposed: false }
  },
  computed: {
    sourceOptions() { return ['auto', 'manual', 'confirmed'].map(value => ({ value, label: memorySourceLabel(value) })) },
    deviceScoped() { return this.policy && this.policy.memoryScope === 'DEVICE' },
    graph() {
      return buildMemoryGraph({ facts: this.facts, tombstones: this.tombstones, policy: this.policy,
        agentId: this.agentId, agentName: this.agentName, now: this.now == null ? this.clock : this.now,
        collapsed: this.collapsed, filters: { query: this.query, speaker: this.speaker, device: this.device,
          status: this.status, source: this.source, tier: this.tier } })
    },
    nodeMap() { return new Map(this.graph.nodes.map(node => [node.id, node])) }
  },
  watch: {
    graph(next, previous) {
      const positions = graph => graph.nodes.map(node => node.id).join('\n')
      if (!previous || positions(next) !== positions(previous)) this.requestFit()
    },
    active(value) { this.endPan(); if (value) { this.clock = Date.now(); this.$nextTick(this.requestFit) } },
    agentId() { this.resetFilters(); this.collapsed = {}; this.pickedId = '' }
  },
  mounted() {
    if (typeof ResizeObserver !== 'undefined') {
      this.resizeObserver = new ResizeObserver(this.handleResize)
      this.resizeObserver.observe(this.$refs.canvas)
    } else if (typeof window !== 'undefined') window.addEventListener('resize', this.handleResize)
    this.clockTimer = setInterval(() => { if (this.now == null && this.active) this.clock = Date.now() }, 30000)
    this.$nextTick(this.requestFit)
  },
  beforeDestroy() {
    this.disposed = true
    clearTimeout(this.fitTimer); clearInterval(this.clockTimer)
    if (this.resizeObserver) this.resizeObserver.disconnect()
    else if (typeof window !== 'undefined') window.removeEventListener('resize', this.handleResize)
  },
  methods: {
    short(value, length) { const chars = Array.from(String(value || '')); return chars.length > length ? chars.slice(0, length).join('') + '…' : chars.join('') },
    nodeAria(node) { return [node.label, node.sub, node.source, node.hasChildren ? `${node.count}项，${node.collapsed ? '展开' : '收起'}` : '打开详情'].filter(Boolean).join('；') },
    resetFilters() { this.query = ''; this.speaker = ''; this.device = ''; this.status = 'active'; this.source = ''; this.tier = '' },
    expandAll() { this.collapsed = {}; this.requestFit() },
    pick(node) {
      this.pickedId = node.id
      if (node.hasChildren) this.toggleGroup(node, !node.collapsed)
      else if (node.rec) this.$emit('open-detail', node.rec, node.type === 'tombstone' ? 'tombstone' : 'fact')
    },
    toggleGroup(node, collapsed) {
      const findNode = () => (this.$refs.graphNodes || []).find(element => element.getAttribute('data-node-id') === node.id)
      const previous = findNode(); const doc = previous && previous.ownerDocument
      const ownsFocus = element => !element || element === doc.body || element === previous || previous.contains(element)
      const restoreFocus = doc && ownsFocus(doc.activeElement)
      this.$set(this.collapsed, node.id, collapsed)
      if (!restoreFocus) return
      this.$nextTick(() => {
        if (this.disposed || !this.active || !ownsFocus(doc.activeElement)) return
        const current = findNode()
        if (current && typeof current.focus === 'function') current.focus({ preventScroll: true })
      })
    },
    onNodeKey(event, node) {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); this.pick(node) }
      else if (event.key === 'Escape') { event.preventDefault(); this.pickedId = '' }
      else if (node.hasChildren && ['ArrowLeft', 'ArrowRight'].includes(event.key)) {
        event.preventDefault(); this.toggleGroup(node, event.key === 'ArrowLeft')
      }
    },
    edgePath(edge) {
      const a = this.nodeMap.get(edge.from); const b = this.nodeMap.get(edge.to)
      if (!a || !b) return ''
      const x1 = a.x + a.width / 2; const x2 = b.x - b.width / 2; const mid = (x1 + x2) / 2
      return `M${x1},${a.y} C${mid},${a.y} ${mid},${b.y} ${x2},${b.y}`
    },
    requestFit() {
      clearTimeout(this.fitTimer)
      if (!this.disposed && this.active) this.fitTimer = setTimeout(() => this.fitView(), 0)
    },
    handleResize() {
      const canvas = this.$refs.canvas
      if (canvas && (canvas.clientWidth !== this.lastWidth || canvas.clientHeight !== this.lastHeight)) this.requestFit()
    },
    fitView() {
      const canvas = this.$refs.canvas; const bounds = graphBounds(this.graph.nodes)
      if (this.disposed || !this.active || !canvas || !bounds || canvas.clientWidth <= 0 || canvas.clientHeight <= 0) return
      const width = canvas.clientWidth; const height = canvas.clientHeight; const padding = Math.min(32, width / 8)
      const graphWidth = bounds.maxX - bounds.minX; const graphHeight = bounds.maxY - bounds.minY
      this.zoom = Math.min(1, (width - padding * 2) / graphWidth, (height - padding * 2) / graphHeight)
      this.panX = (width - graphWidth * this.zoom) / 2 - bounds.minX * this.zoom
      this.panY = (height - graphHeight * this.zoom) / 2 - bounds.minY * this.zoom
      this.lastWidth = width; this.lastHeight = height
    },
    zoomAt(factor, x, y) {
      const next = Math.max(0.001, Math.min(3, this.zoom * factor)); const ratio = next / this.zoom
      this.panX = x - (x - this.panX) * ratio; this.panY = y - (y - this.panY) * ratio; this.zoom = next
    },
    zoomBy(factor) { const canvas = this.$refs.canvas; if (canvas) this.zoomAt(factor, canvas.clientWidth / 2, canvas.clientHeight / 2) },
    wheelZoom(event) { const rect = this.$refs.svg.getBoundingClientRect(); this.zoomAt(event.deltaY > 0 ? 0.9 : 1.1, event.clientX - rect.left, event.clientY - rect.top) },
    startPan(event) {
      if (event.button !== 0) return
      this.pan = { x: event.clientX, y: event.clientY, originX: this.panX, originY: this.panY }
      if (event.currentTarget.setPointerCapture) event.currentTarget.setPointerCapture(event.pointerId)
    },
    movePan(event) { if (this.pan) { this.panX = this.pan.originX + event.clientX - this.pan.x; this.panY = this.pan.originY + event.clientY - this.pan.y } },
    endPan() { this.pan = null },
    onCanvasKey(event) {
      const moves = { ArrowLeft: [40, 0], ArrowRight: [-40, 0], ArrowUp: [0, 40], ArrowDown: [0, -40] }
      if (moves[event.key]) { event.preventDefault(); this.panX += moves[event.key][0]; this.panY += moves[event.key][1] }
      else if (['+', '=', '-'].includes(event.key)) { event.preventDefault(); this.zoomBy(event.key === '-' ? 0.8 : 1.25) }
      else if (event.key === 'Home') { event.preventDefault(); this.fitView() }
    }
  }
}
</script>

<style scoped>
.graph-toolbar, .graph-summary, .graph-actions, .graph-legend { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.graph-toolbar .el-select { width: 160px; }
.graph-search { width: 250px; }
.graph-summary { margin-top: 16px; justify-content: space-between; font-size: 13px; color: #43516a; }
.graph-note, .graph-actions, .graph-legend { font-size: 12px; color: #59677d; line-height: 1.8; }
.graph-note { margin: 8px 0; }
.graph-actions { margin-bottom: 10px; }
.graph-actions > span:first-child { flex: 1; min-width: 220px; }
.zoom-value { min-width: 38px; }
.graph-canvas { position: relative; height: min(660px, calc(100vh - 260px)); min-height: 440px; border: 1px solid #dfe6f0; border-radius: 12px; overflow: hidden; background: radial-gradient(circle, #e4eaf3 1px, transparent 1px) 0 0 / 24px 24px #fafcff; }
.graph-svg { display: block; width: 100%; height: 100%; cursor: grab; touch-action: none; }
.graph-svg:focus-visible { outline: 3px solid #4876ce; outline-offset: -4px; }
.graph-edge { fill: none; stroke: #b5c4da; stroke-width: 1.5; }
.graph-node { cursor: pointer; outline: none; }
.graph-node rect { fill: #f0f4fb; stroke: #bdcce2; stroke-width: 1.5; }
.graph-node.agent rect { fill: #e8efff; stroke: #6287cf; }
.graph-node.device rect { fill: #f0ebfc; stroke: #a898c8; }
.graph-node.audience rect { fill: #edf6fb; stroke: #a0c8db; }
.graph-node.fact.active rect { fill: #eff9f3; stroke: #8dbda2; }
.graph-node.fact.expired rect { fill: #fff6e5; stroke: #d7ad66; }
.graph-node.fact.archived rect { fill: #f1f2f5; stroke: #acb3c1; stroke-dasharray: 5 3; }
.graph-node.tombstone rect, .graph-node.rules rect { fill: #fff1f0; stroke: #d99999; }
.graph-node.selected rect, .graph-node:focus rect { stroke: #245abe; stroke-width: 3; }
.node-title { font-size: 13px; font-weight: 600; fill: #263953; pointer-events: none; }
.node-sub, .node-count { font-size: 10px; fill: #54627a; pointer-events: none; }
.node-count { font-weight: 600; }
.graph-empty { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; color: #64738c; padding: 24px; text-align: center; pointer-events: none; }
.graph-legend { padding-top: 10px; }
.graph-legend > span:not(:last-child)::before { content: ''; display: inline-block; width: 9px; height: 9px; border-radius: 3px; margin-right: 5px; background: #809fcf; }
.graph-legend .legend-active::before { background: #73a98b !important; }
.graph-legend .legend-expired::before { background: #c89e50 !important; }
.graph-legend .legend-archived::before { background: #919bac !important; }
.graph-legend .legend-rule::before { background: #cf8282 !important; }
@media (max-width: 600px) { .graph-search { width: 100%; } .graph-toolbar .el-select { width: calc(50% - 5px); } .graph-canvas { min-height: 400px; } }
</style>
