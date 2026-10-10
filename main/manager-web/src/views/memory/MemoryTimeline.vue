<template>
  <div class="timeline-view" v-loading="loading">
    <p class="timeline-notice">已加载 {{ revisions.length }} 条历史记录，最多显示最近 100 条。时间均为东八区；展开可查看当时保存的快照。</p>
    <div v-if="groupedRevisions.length === 0 && !loading" class="timeline-empty">
      <i class="el-icon-time" aria-hidden="true" />
      <p>暂无已加载的记忆变更记录</p>
    </div>
    <div class="timeline-list" v-else>
      <div v-for="group in groupedRevisions" :key="group.date" class="timeline-group">
        <div class="timeline-date-header">
          <span class="timeline-date">{{ group.date }}</span>
          <span class="timeline-count">{{ group.items.length }} 条记录</span>
        </div>
        <div class="timeline-items">
          <div v-for="item in group.items" :key="item.key" class="timeline-item">
            <div class="timeline-dot" :class="'dot--' + item.kind" aria-hidden="true"></div>
            <div class="timeline-card">
              <button type="button" class="timeline-toggle" :aria-expanded="Boolean(expanded[item.key])" @click="toggle(item.key)">
                <span class="timeline-card-header">
                  <el-tag size="mini" :type="item.tagType">{{ item.label }}</el-tag>
                  <span class="timeline-time">{{ formatTime(item.createdAt) }}</span>
                </span>
                <span class="timeline-text">{{ item.summary }}</span>
                <span class="timeline-toggle-hint">{{ expanded[item.key] ? '收起记录' : '查看记录' }} <i :class="expanded[item.key] ? 'el-icon-arrow-up' : 'el-icon-arrow-down'" aria-hidden="true" /></span>
              </button>
              <div v-if="expanded[item.key]" class="timeline-detail">
                <p class="timeline-meta">{{ item.actorLabel }}<span v-if="item.reason"> · {{ item.reason }}</span></p>
                <p v-if="item.label === '其他操作'" class="timeline-meta">操作标识：{{ item.action || '未记录' }}</p>
                <p v-if="item.sessionId" class="timeline-meta">会话段标识：{{ item.sessionId }}</p>
                <div v-if="item.changes.length" class="snapshot-table-wrapper">
                  <table class="snapshot-table">
                    <caption>审计快照，部分操作仅保存一侧。存储状态不代表当前对话已使用。</caption>
                    <thead><tr><th scope="col">字段</th><th scope="col">记录前值</th><th scope="col">记录后值</th></tr></thead>
                    <tbody>
                      <tr v-for="change in item.changes" :key="change.key" :class="{ 'field-changed': change.changed }">
                        <th scope="row">{{ change.label }}<span v-if="change.changed" class="change-hint">已变化</span></th>
                        <td>{{ change.before === undefined ? '未记录' : change.before }}</td>
                        <td>{{ change.after === undefined ? '未记录' : change.after }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <p v-else class="timeline-meta">此操作没有可展示的事实前后快照。</p>
                <div v-if="item.metrics.length" class="timeline-metrics">
                  <span v-for="metric in item.metrics" :key="metric.label">{{ metric.label }}：{{ metric.value }}</span>
                </div>
                <el-button v-if="item.currentFact" size="mini" type="text" @click="openCurrent(item)">查看当前记忆</el-button>
                <p v-else-if="item.memoryId" class="timeline-meta">关联事实已删除或不在本次加载范围内，仍可查看上述历史记录。</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { formatRevisionTime, groupRevisions } from './memoryRevisionPresentation.mjs'

export default {
  name: 'MemoryTimeline',
  props: {
    revisions: { type: Array, default: () => [] },
    facts: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  data() { return { expanded: {} } },
  computed: {
    groupedRevisions() { return groupRevisions(this.revisions, this.facts) }
  },
  methods: {
    formatTime: formatRevisionTime,
    toggle(key) { this.$set(this.expanded, key, !this.expanded[key]) },
    openCurrent(item) {
      const current = item.memoryId && this.facts.find(fact => fact && fact.memoryId === item.memoryId && fact.status !== 'deleted')
      if (current) this.$emit('open-detail', current)
    }
  }
}
</script>

<style scoped>
.timeline-view { min-height: 400px; }
.timeline-notice { margin: 0 0 18px; color: #64748b; font-size: 12px; line-height: 1.7; }
.timeline-empty { display: flex; flex-direction: column; align-items: center; padding: 80px 0; color: #718096; }
.timeline-empty i { font-size: 48px; color: #c0c4cc; margin-bottom: 10px; }
.timeline-list { padding: 0 10px; }
.timeline-group { margin-bottom: 24px; }
.timeline-date-header { display: flex; align-items: baseline; gap: 8px; margin-bottom: 12px; padding-bottom: 6px; border-bottom: 1px solid #e8ecf4; }
.timeline-date { font-size: 14px; font-weight: 700; color: #2f3548; }
.timeline-count { font-size: 11px; color: #64748b; }
.timeline-items { position: relative; padding-left: 24px; }
.timeline-items::before { content: ''; position: absolute; left: 7px; top: 4px; bottom: 4px; width: 2px; background: #e8ecf4; }
.timeline-item { display: flex; align-items: flex-start; gap: 14px; margin-bottom: 12px; }
.timeline-dot { width: 12px; height: 12px; border-radius: 50%; flex-shrink: 0; margin-top: 16px; margin-left: -22px; z-index: 1; border: 2px solid #fff; background: #3f6fd8; }
.dot--create, .dot--process { background: #2b9d6b; }
.dot--archive { background: #909399; }
.dot--delete, .dot--forget { background: #d95050; }
.dot--candidate { background: #b98126; }
.timeline-card { flex: 1; min-width: 0; background: #fff; border: 1px solid #e8ecf4; border-radius: 8px; overflow: hidden; }
.timeline-toggle { display: block; width: 100%; border: 0; background: transparent; padding: 12px 14px; text-align: left; font: inherit; cursor: pointer; color: inherit; }
.timeline-toggle:hover { background: #f8fafc; }
.timeline-toggle:focus-visible { outline: 2px solid #3f6fd8; outline-offset: -2px; }
.timeline-card-header { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.timeline-time, .timeline-toggle-hint { font-size: 11px; color: #64748b; }
.timeline-text { display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; font-size: 13px; color: #2f3548; margin: 0 0 8px; line-height: 1.6; overflow-wrap: anywhere; white-space: pre-wrap; }
.timeline-detail { border-top: 1px solid #e8ecf4; padding: 10px 14px; }
.timeline-meta { color: #64748b; font-size: 12px; line-height: 1.6; margin: 0 0 8px; overflow-wrap: anywhere; }
.snapshot-table-wrapper { overflow-x: auto; }
.snapshot-table { border-collapse: collapse; table-layout: fixed; width: 100%; font-size: 12px; color: #334155; }
.snapshot-table caption { text-align: left; font-size: 11px; color: #64748b; padding: 0 0 8px; }
.snapshot-table th, .snapshot-table td { border: 1px solid #e8ecf4; padding: 8px; text-align: left; vertical-align: top; white-space: pre-wrap; overflow-wrap: anywhere; font-weight: 400; }
.snapshot-table th:first-child { width: 22%; }
.snapshot-table thead { background: #f5f7fb; }
.field-changed { background: #f1f6ff; }
.change-hint { display: block; color: #345caf; font-size: 10px; margin-top: 4px; }
.timeline-metrics { display: flex; flex-wrap: wrap; gap: 8px 16px; font-size: 12px; color: #475569; margin: 10px 0; }
@media (max-width: 600px) {
  .timeline-list { padding: 0; }
  .timeline-detail, .timeline-toggle { padding: 10px; }
  .snapshot-table th, .snapshot-table td { padding: 5px; }
}
</style>
