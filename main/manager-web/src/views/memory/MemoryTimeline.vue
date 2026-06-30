<template>
  <div class="timeline-view">
    <div v-if="groupedRevisions.length === 0 && !loading" class="timeline-empty">
      <i class="el-icon-time" style="font-size:48px;color:#c0c4cc;margin-bottom:10px" />
      <p>暂无记忆变更记录</p>
      <span>编辑或归档记忆后，变更历史将在此显示</span>
    </div>
    <div class="timeline-list" v-else v-loading="loading">
      <div v-for="group in groupedRevisions" :key="group.date" class="timeline-group">
        <div class="timeline-date-header">
          <span class="timeline-date">{{ group.label }}</span>
          <span class="timeline-count">{{ group.items.length }} 条变更</span>
        </div>
        <div class="timeline-items">
          <div v-for="item in group.items" :key="item.revisionId || item.id" class="timeline-item" @click="$emit('open-detail', item.fact)">
            <div class="timeline-dot" :class="'dot--' + item.actionType"></div>
            <div class="timeline-card">
              <div class="timeline-card-header">
                <el-tag size="mini" :type="actionTagType(item.actionType)">{{ actionLabel(item.actionType) }}</el-tag>
                <span class="timeline-time">{{ formatTime(item.createdAt) }}</span>
              </div>
              <div class="timeline-card-body">
                <p class="timeline-text">{{ getFactText(item) }}</p>
                <div class="timeline-meta" v-if="item.reason">
                  <span class="timeline-reason">原因: {{ item.reason }}</span>
                </div>
                <div class="timeline-meta" v-if="item.actorType">
                  <el-tag size="mini" type="info">{{ item.actorType === 'user' ? '用户操作' : item.actorType === 'system' ? '系统自动' : item.actorType }}</el-tag>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'MemoryTimeline',
  props: {
    revisions: { type: Array, default: () => [] },
    facts: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  computed: {
    groupedRevisions() {
      const sorted = [...this.revisions]
        .filter(r => r.createdAt)
        .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))

      const groups = {}
      const today = new Date()
      today.setHours(0, 0, 0, 0)
      const yesterday = new Date(today)
      yesterday.setDate(yesterday.getDate() - 1)

      sorted.forEach(r => {
        const d = new Date(r.createdAt)
        d.setHours(0, 0, 0, 0)
        let key
        if (d.getTime() === today.getTime()) key = '今天'
        else if (d.getTime() === yesterday.getTime()) key = '昨天'
        else key = this.formatDate(d)

        if (!groups[key]) groups[key] = []
        groups[key].push({
          ...r,
          actionType: this.detectAction(r),
          fact: this.facts.find(f => f.memoryId === r.memoryId)
        })
      })

      return Object.entries(groups).map(([date, items]) => ({
        date,
        label: date,
        items
      }))
    }
  },
  methods: {
    detectAction(rev) {
      const a = (rev.revisionAction || rev.action || '').toLowerCase()
      if (a.includes('create') || a.includes('add')) return 'create'
      if (a.includes('update') || a.includes('edit')) return 'update'
      if (a.includes('archive')) return 'archive'
      if (a.includes('delete') || a.includes('remove')) return 'delete'
      return 'update'
    },
    actionLabel(type) {
      const map = { create: '创建', update: '更新', archive: '归档', delete: '删除' }
      return map[type] || '变更'
    },
    actionTagType(type) {
      const map = { create: 'success', update: 'primary', archive: 'info', delete: 'danger' }
      return map[type] || 'info'
    },
    getFactText(item) {
      if (item.fact && item.fact.canonicalText) return item.fact.canonicalText.slice(0, 80)
      if (item.afterPayload) {
        try {
          const p = typeof item.afterPayload === 'string' ? JSON.parse(item.afterPayload) : item.afterPayload
          return (p.canonicalText || p.text || JSON.stringify(p)).slice(0, 80)
        } catch (_) {
          return item.afterPayload.slice(0, 80)
        }
      }
      return '记忆内容已变更'
    },
    formatDate(d) {
      const y = d.getFullYear()
      const m = String(d.getMonth() + 1).padStart(2, '0')
      const day = String(d.getDate()).padStart(2, '0')
      return `${y}-${m}-${day}`
    },
    formatTime(ts) {
      if (!ts) return ''
      const d = new Date(ts)
      return `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
    }
  }
}
</script>

<style scoped>
.timeline-view { min-height: 400px; }
.timeline-empty { display: flex; flex-direction: column; align-items: center; padding: 80px 0; color: #a0aec0; }
.timeline-empty p { font-size: 15px; margin: 0 0 4px; color: #718096; }
.timeline-empty span { font-size: 12px; }

.timeline-list { padding: 0 10px; }
.timeline-group { margin-bottom: 24px; }
.timeline-date-header { display: flex; align-items: baseline; gap: 8px; margin-bottom: 12px; padding-bottom: 6px; border-bottom: 1px solid #e8ecf4; }
.timeline-date { font-size: 14px; font-weight: 700; color: #2f3548; }
.timeline-count { font-size: 11px; color: #a0aec0; }

.timeline-items { position: relative; padding-left: 24px; }
.timeline-items::before { content: ''; position: absolute; left: 7px; top: 4px; bottom: 4px; width: 2px; background: #e8ecf4; }

.timeline-item { display: flex; align-items: flex-start; gap: 14px; margin-bottom: 12px; cursor: pointer; }
.timeline-dot { width: 12px; height: 12px; border-radius: 50%; flex-shrink: 0; margin-top: 6px; margin-left: -22px; z-index: 1; border: 2px solid #fff; }
.dot--create { background: #2ecc71; }
.dot--update { background: #3f6fd8; }
.dot--archive { background: #909399; }
.dot--delete { background: #e05555; }

.timeline-card { flex: 1; padding: 10px 14px; background: #fff; border: 1px solid #e8ecf4; border-radius: 8px; transition: box-shadow .2s; }
.timeline-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,.06); }

.timeline-card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.timeline-time { font-size: 11px; color: #a0aec0; }

.timeline-card-body { }
.timeline-text { font-size: 13px; color: #2f3548; margin: 0 0 4px; line-height: 1.5; }
.timeline-meta { display: inline-flex; gap: 6px; margin-right: 6px; }
.timeline-reason { font-size: 11px; color: #718096; }
</style>
