<template>
  <div class="recall-view">
    <div class="recall-controls">
      <div class="recall-input-row">
        <el-input v-model="queryText" type="textarea" :rows="3" placeholder="输入测试查询文本，模拟记忆召回效果..." @keyup.ctrl.enter.native="runTest" />
      </div>
      <div class="recall-params">
        <div class="param-group">
          <label>最小重要性</label>
          <el-slider v-model="minImportance" :min="0" :max="1" :step="0.05" show-input :show-input-controls="false" style="width:180px" />
        </div>
        <div class="param-group">
          <label>最小置信度</label>
          <el-slider v-model="minConfidence" :min="0" :max="1" :step="0.05" show-input :show-input-controls="false" style="width:180px" />
        </div>
        <div class="param-group">
          <label>最多结果</label>
          <el-input-number v-model="maxResults" :min="1" :max="50" size="small" style="width:100px" />
        </div>
        <div class="param-actions">
          <el-button type="primary" size="small" icon="el-icon-search" @click="runTest" :loading="running">开始测试</el-button>
          <el-button size="small" icon="el-icon-refresh" @click="reset">重置</el-button>
        </div>
      </div>
    </div>

    <div class="recall-results" v-if="results.length > 0">
      <div class="results-header">
        <strong>{{ results.length }} 条匹配</strong>
        <span class="results-hint">按相关性从高到低排列</span>
      </div>
      <div class="result-item" v-for="(r, i) in results" :key="r.memoryId || i">
        <div class="result-rank">{{ i + 1 }}</div>
        <div class="result-content">
          <div class="result-bar-row">
            <div class="result-bar" :style="{width: r.scorePercent + '%', background: r.scoreColor}"></div>
            <span class="result-score">{{ r.scorePercent }}%</span>
          </div>
          <p class="result-text">{{ r.canonicalText }}</p>
          <div class="result-tags">
            <el-tag size="mini" type="info">{{ r.category || '未分类' }}</el-tag>
            <el-tag size="mini" :type="r.tierTagType">{{ r.tierLabel }}</el-tag>
            <el-tag size="mini" type="warning" v-if="r.confirmationState==='manual'">手动</el-tag>
            <el-tag size="mini" type="success" v-else>自动</el-tag>
            <el-button size="mini" type="text" icon="el-icon-view" @click="$emit('open-detail', r)">详情</el-button>
          </div>
        </div>
      </div>
    </div>

    <div class="recall-placeholder" v-else-if="!running">
      <div v-if="queryText">
        <i class="el-icon-warning-outline" style="font-size:40px;color:#c0c4cc;margin-bottom:8px" />
        <p>没有符合条件的记忆</p>
        <span>尝试降低阈值或修改查询文本</span>
      </div>
      <div v-else>
        <i class="el-icon-search" style="font-size:40px;color:#c0c4cc;margin-bottom:8px" />
        <p>输入查询文本并点击「开始测试」</p>
        <span>模拟记忆召回效果，无需调用真实 LLM</span>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'MemoryRecallTest',
  props: {
    facts: { type: Array, default: () => [] }
  },
  data() {
    return {
      queryText: '',
      minImportance: 0.0,
      minConfidence: 0.0,
      maxResults: 10,
      results: [],
      running: false
    }
  },
  methods: {
    runTest() {
      if (!this.queryText.trim()) return
      this.running = true

      // Simulate processing delay
      setTimeout(() => {
        const results = this.scoreFacts(this.queryText, this.facts, {
          minImportance: this.minImportance,
          minConfidence: this.minConfidence,
          maxResults: this.maxResults
        })
        this.results = results
        this.running = false
      }, 200)
    },
    reset() {
      this.queryText = ''
      this.minImportance = 0.0
      this.minConfidence = 0.0
      this.maxResults = 10
      this.results = []
    },

    // ---- Scoring algorithm ----
    scoreFacts(query, facts, opts) {
      const queryTokens = this.tokenize(query)
      if (queryTokens.length === 0) return []

      const scored = facts
        .filter(f => f.status !== 'deleted')
        .map(f => {
          const factTokens = this.tokenize(f.canonicalText || '')
          const overlap = queryTokens.filter(t => factTokens.includes(t)).length
          const overlapScore = queryTokens.length > 0 ? overlap / queryTokens.length : 0
          const importance = Number(f.importance) || 0.5
          const confidence = Number(f.confidence) || 0.5
          const tierMultiplier = this.getTierMultiplier(f.memoryTier)
          const score = overlapScore * importance * confidence * tierMultiplier
          return { ...f, score }
        })
        .filter(f => f.score > 0 && f.importance >= opts.minImportance && f.confidence >= opts.minConfidence)
        .sort((a, b) => b.score - a.score)
        .slice(0, opts.maxResults)

      // Normalize scores to percentages
      const maxScore = scored.length > 0 ? scored[0].score : 1
      return scored.map(f => ({
        ...f,
        scorePercent: Math.round((f.score / maxScore) * 100),
        scoreColor: this.getScoreColor(f.score / maxScore),
        tierLabel: this.getTierLabel(f.memoryTier),
        tierTagType: this.getTierTagType(f.memoryTier)
      }))
    },

    tokenize(text) {
      // Chinese + English tokenization
      const cleaned = text.toLowerCase().replace(/[，。！？、；：,.!?;:""''()（）《》<>\[\]【】\s]+/g, ' ')
      const tokens = []
      // Extract Chinese characters (bigrams for better matching)
      const chinese = cleaned.replace(/[^一-鿿]/g, '')
      for (let i = 0; i + 1 < chinese.length; i++) {
        tokens.push(chinese.slice(i, i + 2))
      }
      // Also add single Chinese chars for short queries
      if (chinese.length <= 3) {
        for (const c of chinese) tokens.push(c)
      }
      // Extract English/Number words
      const words = cleaned.match(/[a-z0-9]+/g)
      if (words) tokens.push(...words)
      return [...new Set(tokens)]
    },

    getTierMultiplier(tier) {
      if (!tier) return 1.0
      const t = tier.toLowerCase()
      if (t === 'long_term') return 1.5
      if (t === 'short_term') return 1.0
      if (t === 'working') return 0.5
      return 1.0
    },

    getTierLabel(tier) {
      if (!tier) return '标准'
      const t = tier.toLowerCase()
      if (t === 'long_term') return '长期记忆'
      if (t === 'short_term') return '短期记忆'
      if (t === 'working') return '工作记忆'
      return tier
    },

    getTierTagType(tier) {
      if (!tier) return ''
      const t = tier.toLowerCase()
      if (t === 'long_term') return 'primary'
      if (t === 'short_term') return 'success'
      if (t === 'working') return 'info'
      return ''
    },

    getScoreColor(ratio) {
      if (ratio >= 0.8) return '#2ecc71'
      if (ratio >= 0.5) return '#3f6fd8'
      if (ratio >= 0.3) return '#f0b060'
      return '#909399'
    }
  }
}
</script>

<style scoped>
.recall-view { min-height: 420px; }

.recall-controls { margin-bottom: 20px; }
.recall-input-row { margin-bottom: 12px; }
.recall-params { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.param-group { display: flex; align-items: center; gap: 8px; }
.param-group label { font-size: 12px; color: #718096; white-space: nowrap; }
.param-actions { display: flex; gap: 8px; margin-left: auto; }

.recall-results { }
.results-header { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 14px; padding-bottom: 8px; border-bottom: 1px solid #e8ecf4; }
.results-header strong { font-size: 14px; color: #2f3548; }
.results-hint { font-size: 11px; color: #a0aec0; }

.result-item { display: flex; gap: 14px; margin-bottom: 12px; padding: 12px 14px; background: #fff; border: 1px solid #e8ecf4; border-radius: 8px; transition: box-shadow .2s; }
.result-item:hover { box-shadow: 0 2px 8px rgba(0,0,0,.06); }
.result-rank { width: 24px; height: 24px; line-height: 24px; text-align: center; font-size: 12px; font-weight: 700; color: #718096; background: #f7f8fc; border-radius: 50%; flex-shrink: 0; }
.result-content { flex: 1; min-width: 0; }
.result-bar-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.result-bar { height: 6px; border-radius: 3px; min-width: 4px; transition: width .4s ease; }
.result-score { font-size: 12px; font-weight: 600; color: #2f3548; white-space: nowrap; }
.result-text { font-size: 14px; color: #2f3548; margin: 0 0 6px; line-height: 1.5; }
.result-tags { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }

.recall-placeholder { display: flex; justify-content: center; padding: 60px 0; }
.recall-placeholder > div { display: flex; flex-direction: column; align-items: center; text-align: center; }
.recall-placeholder p { font-size: 15px; color: #718096; margin: 0 0 4px; }
.recall-placeholder span { font-size: 12px; color: #a0aec0; }
</style>
