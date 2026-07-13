<template>
  <div class="candidates-view">
    <div class="candidates-note">候选预览不会参与运行时召回，确认后才进入事实记忆；自动维护会直接写入生效事实。</div>
    <el-table :data="candidates" v-loading="loading" class="transparent-table" empty-text="暂无候选预览">
      <el-table-column label="内容" prop="canonicalText" show-overflow-tooltip min-width="260" />
      <el-table-column label="关联人物" width="120" show-overflow-tooltip>
        <template slot-scope="{row}"><el-tag size="mini" :type="row.speakerId ? 'primary' : 'info'">{{ row.speakerDisplay || '共享记忆' }}</el-tag></template>
      </el-table-column>
      <el-table-column label="分类" width="100">
        <template slot-scope="{row}"><el-tag size="mini" type="info">{{ categoryLabel(row.category) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template slot-scope="{row}"><el-tag size="mini">{{ memoryTypeLabel(row.memoryType) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="置信度" width="100">
        <template slot-scope="{row}">
          <el-progress :percentage="pct(row.confidence)" :stroke-width="6" :color="pct(row.confidence)>=50?'#3f6fd8':'#f0b060'" />
        </template>
      </el-table-column>
      <el-table-column label="重要性" width="100">
        <template slot-scope="{row}">
          <el-progress :percentage="pct(row.importance)" :stroke-width="6" :color="pct(row.importance)>=70?'#2ecc71':pct(row.importance)>=40?'#f0b060':'#909399'" />
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160">
        <template slot-scope="{row}">{{ row.createdAt || '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template slot-scope="{row}">
          <el-button size="mini" type="success" @click="$emit('confirm', row)">确认</el-button>
          <el-button size="mini" type="danger" @click="$emit('reject', row)">拒绝</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="candidates-info" v-if="candidates.length === 0 && !loading">
      <i class="el-icon-check" style="font-size:40px;color:#c0c4cc;margin-bottom:8px" />
      <p>暂无候选预览</p>
    </div>
  </div>
</template>

<script>
import { categoryLabel, memoryTypeLabel } from '@/utils/memoryLabels'

export default {
  name: 'MemoryCandidates',
  props: {
    candidates: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false }
  },
  methods: {
    categoryLabel,
    memoryTypeLabel,
    pct(v) { return Math.round((Number(v) || 0) * 100) }
  }
}
</script>

<style scoped>
.candidates-view { padding: 0; }
.candidates-note { margin-bottom: 12px; color: #718096; font-size: 13px; }
.candidates-info { display: flex; flex-direction: column; align-items: center; padding: 60px 0; color: #a0aec0; font-size: 14px; }
</style>
