<template>
  <section class="memory-overview" aria-label="记忆概览" v-loading="loading">
    <div class="overview-heading">
      <div><h2>记忆概览</h2><p>当前已加载 {{ summary.total }} 条事实，统计不代表全量。生效事实是否用于对话，以实际注入预览为准。</p></div>
      <el-tag size="small">{{ scopeLabel }}</el-tag>
    </div>
    <div class="overview-grid">
      <div class="lifecycle-summary">
        <div class="state-bar" role="img" :aria-label="stateDescription">
          <span v-for="item in states" :key="item.key" :style="{ width: percentage(item.count, summary.total) + '%', background: item.color }" />
        </div>
        <div class="state-legend">
          <span v-for="item in states" :key="item.key"><i :style="{ background: item.color }" />{{ item.label }} <strong>{{ item.count }}</strong></span>
        </div>
        <div class="scope-summary">
          <span>生效事实归属</span><strong>共享 {{ sharedCount }}</strong><strong>人物私有 {{ privateCount }}</strong><span v-if="unknownCount">待核实 {{ unknownCount }}</span>
        </div>
        <div class="capacity-title"><span>已加载生效正文</span><strong>{{ summary.activeChars }} 字<span v-if="policy"> / 配置上限 {{ policy.activeMaxChars }} 字</span></strong></div>
        <el-progress v-if="policy" :percentage="Math.round(percentage(summary.activeChars, policy.activeMaxChars))" :show-text="false" :stroke-width="8" :color="summary.activeChars > policy.activeMaxChars ? '#c96c26' : '#4669bc'" />
      </div>
      <div class="lifecycle-actions">
        <div class="maintenance-counts"><span>待确认候选 <strong>{{ pendingCount }}</strong></span><span>启用的遗忘规则 <strong>{{ forgetCount }}</strong></span></div>
        <p v-if="nextExpiry">下次已知到期：{{ nextExpiry.expiresAt }}<br><span class="expiry-text">{{ nextExpiry.canonicalText }}</span></p>
        <p v-else>当前加载的生效事实没有已知到期时间。</p>
        <div class="overview-actions"><el-button size="small" @click="$emit('navigate', 'graph')">查看归属图谱</el-button><el-button size="small" @click="$emit('navigate', 'recall')">查看实际注入</el-button></div>
      </div>
    </div>
  </section>
</template>

<script>
import { summarizeMemory, memoryIdentity, memoryStatus, timestamp, boundedPercent } from './memoryVisualState.mjs'

export default {
  name: 'MemoryOverview',
  props: {
    facts: { type: Array, default: () => [] }, candidates: { type: Array, default: () => [] },
    tombstones: { type: Array, default: () => [] }, policy: { type: Object, default: null },
    loading: { type: Boolean, default: false }, now: { type: Number, default: () => Date.now() }
  },
  computed: {
    summary() { return summarizeMemory(this.facts, this.now) },
    states() { return [['active', '生效', '#40866c'], ['expired', '已到期', '#c78b35'], ['archived', '已归档', '#8b92a4'], ['unknown', '待核实', '#a06bb2']].map(([key, label, color]) => ({ key, label, color, count: this.summary.counts[key] || 0 })) },
    stateDescription() { return this.states.map(item => `${item.label} ${item.count} 条`).join('，') },
    active() { return this.facts.filter(fact => memoryStatus(fact, this.now) === 'active') },
    identities() { return this.active.map(fact => memoryIdentity(fact, this.policy)) },
    sharedCount() { return this.identities.filter(item => item.visibility === 'shared').length },
    privateCount() { return this.identities.filter(item => item.visibility === 'private').length },
    unknownCount() { return this.identities.filter(item => item.visibility === 'unknown').length },
    pendingCount() { return this.candidates.filter(item => item.status === 'pending').length },
    forgetCount() { return this.tombstones.filter(item => item.status === 'active').length },
    scopeLabel() { return !this.policy ? '正在读取记忆策略' : this.policy.memoryScope === 'DEVICE' ? '同智能体 · 按设备隔离' : '同智能体 · 跨设备共享' },
    nextExpiry() { return this.active.filter(fact => timestamp(fact.expiresAt) != null).sort((a, b) => timestamp(a.expiresAt) - timestamp(b.expiresAt))[0] }
  },
  methods: { percentage: boundedPercent }
}
</script>

<style scoped>
.memory-overview { padding: 20px 24px; margin: 18px 0; border: 1px solid #e1e6ef; border-radius: 12px; background: #fff; }
.overview-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 18px; }
h2 { font-size: 17px; color: #293247; margin: 0 0 7px; }
p { margin: 0; color: #747e91; font-size: 12px; line-height: 1.7; }
.overview-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr); gap: 32px; }
.state-bar { display: flex; height: 12px; overflow: hidden; border-radius: 6px; background: #edf0f5; }
.state-bar span { transition: width .2s; }
.state-legend, .scope-summary, .capacity-title, .maintenance-counts { display: flex; flex-wrap: wrap; gap: 12px 20px; font-size: 12px; color: #68738a; }
.state-legend { margin: 12px 0 18px; }
.state-legend i { display: inline-block; width: 8px; height: 8px; margin-right: 6px; border-radius: 50%; }
strong { font-weight: 600; color: #313d54; }
.capacity-title { margin: 14px 0 9px; justify-content: space-between; }
.capacity-title strong span { font-weight: 400; color: #747e91; }
.lifecycle-actions { border-left: 1px solid #e8ecf3; padding-left: 28px; min-width: 0; }
.maintenance-counts { margin-bottom: 12px; }
.expiry-text { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.overview-actions { margin-top: 14px; display: flex; gap: 8px; flex-wrap: wrap; }
.overview-actions .el-button { margin-left: 0; }
@media (max-width: 1100px) { .overview-grid { gap: 20px; } .memory-overview { padding: 18px; } .lifecycle-actions { padding-left: 18px; } }
@media (max-width: 700px) { .overview-grid { grid-template-columns: 1fr; } .lifecycle-actions { border-left: 0; border-top: 1px solid #e8ecf3; padding: 16px 0 0; } .overview-heading { flex-wrap: wrap; } }
</style>
