<template>
  <div>
    <!-- Backdrop -->
    <transition name="fade">
      <div v-if="visible" class="detail-overlay" @click.self="$emit('close')" />
    </transition>
    <!-- Panel -->
    <transition name="slide-right">
      <div v-if="visible" class="detail-panel" @keyup.escape="$emit('close')">
        <div class="detail-header">
          <div class="detail-header-left">
            <el-tag v-if="itemType==='fact'" size="mini" :type="confirmationTagType">{{ confirmationLabel }}</el-tag>
            <el-tag v-else-if="itemType==='tomb'" size="mini" type="danger">忘记规则</el-tag>
            <el-tag v-else-if="itemType==='candidate'" size="mini" type="warning">候选记忆</el-tag>
            <el-tag v-else size="mini">记忆</el-tag>
            <span class="detail-title">{{ displayTitle }}</span>
          </div>
          <el-button size="mini" icon="el-icon-close" circle @click="$emit('close')" />
        </div>

        <div class="detail-body">
          <!-- Content -->
          <div class="detail-section">
            <h4 class="section-title">内容</h4>
            <p class="detail-content-text">{{ memory.canonicalText || memory.patternText || '-' }}</p>
          </div>

          <div class="detail-section">
            <h4 class="section-title">归属与可见范围</h4>
            <div class="detail-grid">
              <div class="detail-field"><label>人物</label><span>{{ identity.speakerLabel }}</span></div>
              <div class="detail-field"><label>来源设备</label><span>{{ identity.deviceLabel }}</span></div>
              <div class="detail-field"><label>设备范围</label><span>{{ identity.deviceScopeLabel }}</span></div>
              <div class="detail-field" v-if="identity.speakerId"><label>人物 ID</label><span class="speaker-id">{{ identity.speakerId }}</span></div>
            </div>
          </div>

          <!-- Classification (for facts) -->
          <div class="detail-section" v-if="itemType==='fact'">
            <h4 class="section-title">分类与属性</h4>
            <div class="detail-grid">
              <div class="detail-field"><label>分类</label><span>{{ categoryLabel(memory.category) }}</span></div>
              <div class="detail-field"><label>类型</label><span>{{ memoryTypeLabel(memory.memoryType) }}</span></div>
              <div class="detail-field"><label>来源</label><el-tag size="mini" :type="confirmationTagType">{{ confirmationLabel }}</el-tag></div>
              <div class="detail-field"><label>层级</label><el-tag size="mini" :type="tierTagType">{{ tierLabel }}</el-tag></div>
              <div class="detail-field"><label>状态</label><el-tag size="mini" :type="effectiveState==='active'?'success':effectiveState==='expired'?'warning':'info'">{{ effectiveStateLabel }}</el-tag></div>
              <div class="detail-field"><label>敏感度</label><el-tag size="mini" :type="sensitivityTagType">{{ sensitivityLabel }}</el-tag></div>
            </div>
          </div>

          <!-- Scores (for facts) -->
          <div class="detail-section" v-if="itemType==='fact'">
            <h4 class="section-title">评分（维护模型估计或人工设置）</h4>
            <div class="detail-score-row">
              <label>重要性</label>
              <el-progress v-if="memory.importance != null" :percentage="pct(memory.importance)" :stroke-width="8" :color="importanceColor" /><span v-else>未评分</span>
            </div>
            <div class="detail-score-row">
              <label>置信度</label>
              <el-progress v-if="memory.confidence != null" :percentage="pct(memory.confidence)" :stroke-width="8" :color="confidenceColor" /><span v-else>未评分</span>
            </div>
          </div>

          <!-- Temporal (for facts) -->
          <div class="detail-section" v-if="itemType==='fact'">
            <h4 class="section-title">时间信息</h4>
            <div class="detail-grid">
              <div class="detail-field"><label>创建时间</label><span>{{ memory.createdAt || '-' }}</span></div>
              <div class="detail-field"><label>更新时间</label><span>{{ memory.updatedAt || '-' }}</span></div>
              <div class="detail-field"><label>首次发现</label><span>{{ memory.firstSeenAt || '-' }}</span></div>
              <div class="detail-field"><label>最后出现</label><span>{{ memory.lastSeenAt || '-' }}</span></div>
              <div class="detail-field"><label>到期时间</label><span>{{ memory.expiresAt || '未设置期限' }}</span></div>
              <div class="detail-field" v-if="memory.archivedAt"><label>归档时间</label><span>{{ memory.archivedAt }}</span></div>
              <div class="detail-field" v-if="memory.validFrom"><label>生效时间</label><span>{{ memory.validFrom }}</span></div>
              <div class="detail-field" v-if="memory.validTo"><label>失效时间</label><span>{{ memory.validTo }}</span></div>
            </div>
          </div>

          <div class="detail-section" v-if="itemType==='fact' && (evidence || memory.sourceEventId)">
            <h4 class="section-title">已保存的来源证据</h4>
            <blockquote v-if="evidence" class="evidence-quote">{{ evidence }}</blockquote>
            <p v-if="memory.sourceEventId" class="source-event">来源事件 ID：{{ memory.sourceEventId }}</p>
          </div>

          <!-- Tombstone info -->
          <div class="detail-section" v-if="itemType==='tomb'">
            <h4 class="section-title">忘记规则</h4>
            <div class="detail-grid">
              <div class="detail-field"><label>规则内容</label><span>{{ memory.patternText || '-' }}</span></div>
              <div class="detail-field"><label>原因</label><span>{{ memory.reason || '-' }}</span></div>
              <div class="detail-field"><label>匹配方式</label><span>{{ memory.matchType === 'exact' ? '相同规范化正文' : memory.matchType === 'contains' ? '包含指定文本' : '未标注' }}</span></div>
              <div class="detail-field"><label>状态</label><el-tag size="mini" :type="memory.status==='active'?'success':'info'">{{ statusLabel(memory.status) }}</el-tag></div>
            </div>
          </div>

          <!-- Embedding (collapsed) -->
          <div class="detail-section" v-if="memory.embeddingJson">
            <el-collapse>
              <el-collapse-item title="嵌入向量 (Embedding)" name="embedding">
                <code class="embedding-code">{{ truncatedEmbedding }}</code>
              </el-collapse-item>
            </el-collapse>
          </div>

          <!-- Importance dimensions (collapsed) -->
          <div class="detail-section" v-if="memory.importanceDimensions">
            <el-collapse>
              <el-collapse-item title="多维度评分详情" name="dimensions">
                <pre class="embedding-code">{{ formattedDimensions }}</pre>
              </el-collapse-item>
            </el-collapse>
          </div>
        </div>

        <!-- Actions Footer -->
        <div class="detail-footer" v-if="itemType==='fact'">
          <el-button size="small" icon="el-icon-edit" :disabled="!canWrite" @click="$emit('edit', memory)">编辑</el-button>
          <el-button size="small" icon="el-icon-folder" :disabled="!canWrite" @click="$emit('archive', memory)">归档</el-button>
          <el-button size="small" type="danger" icon="el-icon-delete" :disabled="!canWrite" @click="$emit('delete', memory)">删除</el-button>
        </div>
      </div>
    </transition>
  </div>
</template>

<script>
import { categoryLabel, memoryTypeLabel, sensitivityLabel, tierLabel, statusLabel } from '@/utils/memoryLabels'
import { memoryIdentity, memoryStatus, memoryStatusLabel, memorySourceLabel, parsePayload } from './memoryVisualState.mjs'

export default {
  name: 'MemoryDetailPanel',
  props: {
    canWrite: { type: Boolean, default: false },
    visible: { type: Boolean, default: false },
    memory: { type: Object, default: () => ({}) },
    itemType: { type: String, default: 'fact' }, // 'fact' | 'tomb' | 'candidate'
    policy: { type: Object, default: null },
    now: { type: Number, default: () => Date.now() }
  },
  computed: {
    identity() { return memoryIdentity(this.memory, this.policy) },
    effectiveState() { return memoryStatus(this.memory, this.now) },
    effectiveStateLabel() { return memoryStatusLabel(this.effectiveState) },
    evidence() { const value = parsePayload(this.memory.payload).evidence_quote; return typeof value === 'string' ? value : '' },
    displayTitle() {
      return (this.memory.canonicalText || this.memory.patternText || '未知').slice(0, 60)
    },
    tierLabel() {
      return tierLabel(this.memory.memoryTier)
    },
    tierTagType() {
      const t = this.memory.memoryTier || ''
      if (t === 'LONG_TERM' || t === 'long_term') return 'primary'
      if (t === 'SHORT_TERM' || t === 'short_term') return 'success'
      if (t === 'WORKING' || t === 'working') return 'info'
      return ''
    },
    sensitivityLabel() {
      return sensitivityLabel(this.memory.sensitivity)
    },
    sensitivityTagType() {
      const s = this.memory.sensitivity || 'normal'
      return s === 'sensitive' ? 'danger' : s === 'personal' ? 'warning' : ''
    },
    confirmationLabel() {
      return memorySourceLabel(this.memory.confirmationState)
    },
    confirmationTagType() {
      return this.memory.confirmationState === 'auto' ? 'success' : 'warning'
    },
    importanceColor() {
      const v = this.pct(this.memory.importance)
      return v >= 70 ? '#2ecc71' : v >= 40 ? '#f0b060' : '#909399'
    },
    confidenceColor() {
      const v = this.pct(this.memory.confidence)
      return v >= 50 ? '#3f6fd8' : v >= 25 ? '#f0b060' : '#e05555'
    },
    truncatedEmbedding() {
      const e = this.memory.embeddingJson || ''
      if (e.length <= 200) return e
      try {
        const arr = JSON.parse(e)
        if (Array.isArray(arr)) {
          return `[${arr.slice(0, 5).map(v => Number(v).toFixed(4)).join(', ')} ... ${arr.slice(-3).map(v => Number(v).toFixed(4)).join(', ')}] (${arr.length} dimensions)`
        }
      } catch (_) { /* ignore */ }
      return e.slice(0, 200) + '...'
    },
    formattedDimensions() {
      try {
        const d = typeof this.memory.importanceDimensions === 'string'
          ? JSON.parse(this.memory.importanceDimensions)
          : this.memory.importanceDimensions || {}
        return JSON.stringify(d, null, 2)
      } catch (_) {
        return String(this.memory.importanceDimensions || '-')
      }
    }
  },
  methods: {
    categoryLabel,
    memoryTypeLabel,
    statusLabel,
    pct(v) { return Math.max(0, Math.min(100, Math.round((Number(v) || 0) * 100))) },
    onKeyup(e) {
      if (e.key === 'Escape' && this.visible) {
        this.$emit('close')
      }
    }
  },
  mounted() {
    document.addEventListener('keyup', this.onKeyup)
  },
  beforeDestroy() {
    document.removeEventListener('keyup', this.onKeyup)
  }
}
</script>

<style scoped>
.detail-overlay {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,.3); z-index: 1000;
}
.detail-panel {
  position: fixed; top: 0; right: 0; bottom: 0; width: 420px;
  background: #fff; z-index: 1001;
  box-shadow: -4px 0 24px rgba(0,0,0,.1);
  display: flex; flex-direction: column;
}
.detail-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 20px; border-bottom: 1px solid #e8ecf4; flex-shrink: 0;
}
.detail-header-left { display: flex; align-items: center; gap: 8px; min-width: 0; }
.detail-title { font-size: 15px; font-weight: 600; color: #1a202c; }
.detail-title, .detail-content-text { overflow-wrap: anywhere; }
.detail-body { flex: 1; overflow-y: auto; padding: 10px 20px; }
.detail-section { margin-bottom: 18px; }
.section-title { font-size: 13px; font-weight: 600; color: #718096; margin: 0 0 10px; text-transform: uppercase; letter-spacing: .5px; }
.detail-content-text { font-size: 14px; color: #2f3548; line-height: 1.6; margin: 0; padding: 10px 12px; background: #f7f8fc; border-radius: 6px; }
.evidence-quote { border-left: 3px solid #c5d3ec; padding: 8px 12px; margin: 0; color: #52617a; font-size: 13px; white-space: pre-wrap; overflow-wrap: anywhere; background: #f7f9fc; }
.source-event { font-size: 11px; color: #747e91; overflow-wrap: anywhere; }
.detail-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 8px; }
.detail-field { display: flex; flex-direction: column; gap: 2px; min-width: 0; overflow-wrap: anywhere; }
.detail-field label { font-size: 11px; color: #a0aec0; }
.detail-field span { font-size: 13px; color: #2f3548; font-weight: 500; }
.detail-field .speaker-id { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 11px; word-break: break-all; }
.detail-score-row { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.detail-score-row label { font-size: 12px; color: #718096; width: 40px; flex-shrink: 0; }
.detail-score-row .el-progress { flex: 1; }
.embedding-code { font-size: 11px; color: #718096; word-break: break-all; white-space: pre-wrap; max-height: 200px; overflow: auto; display: block; background: #f7f8fc; padding: 8px; border-radius: 4px; }
.detail-footer { padding: 14px 20px; border-top: 1px solid #e8ecf4; display: flex; gap: 10px; flex-shrink: 0; }

.fade-enter-active, .fade-leave-active { transition: opacity .25s ease; }
.fade-enter, .fade-leave-to { opacity: 0; }

.slide-right-enter-active, .slide-right-leave-active { transition: transform .3s ease; }
.slide-right-enter, .slide-right-leave-to { transform: translateX(100%); }
@media (max-width: 500px) { .detail-panel { width: 100%; } }
</style>
