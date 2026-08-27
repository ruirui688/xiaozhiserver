<template>
  <el-dialog title="维护运行记录" :visible.sync="internalVisible" width="920px" top="7vh"
    append-to-body @close="$emit('close')">
    <el-alert title="定时维护、手动维护与原生 Harness 提交统一保留为审计记录，不会伪装成聊天消息。"
      type="info" :closable="false" show-icon class="audit-hint" />
    <el-table :data="runs" v-loading="loading" max-height="560" empty-text="暂无维护记录">
      <el-table-column label="类型" width="120">
        <template slot-scope="scope">{{ triggerText(scope.row.triggerType) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template slot-scope="scope"><el-tag size="mini" :type="statusType(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="提案" prop="proposalCount" width="80" />
      <el-table-column label="开始时间" min-width="180">
        <template slot-scope="scope">{{ formatDate(scope.row.startedAt || scope.row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="结束时间" min-width="180">
        <template slot-scope="scope">{{ formatDate(scope.row.finishedAt) }}</template>
      </el-table-column>
      <el-table-column label="结果" min-width="220" show-overflow-tooltip>
        <template slot-scope="scope">{{ scope.row.error || '-' }}</template>
      </el-table-column>
    </el-table>
    <span slot="footer"><el-button @click="load">刷新</el-button><el-button @click="internalVisible = false">关闭</el-button></span>
  </el-dialog>
</template>

<script>
import Api from '@/apis/api'

export default {
  name: 'MaintenanceRunPanel',
  props: {
    visible: { type: Boolean, default: false },
    resourceType: { type: String, required: true },
    resourceId: { type: String, required: true }
  },
  data() { return { internalVisible: false, loading: false, runs: [] } },
  watch: { visible: { immediate: true, handler(value) { this.internalVisible = value; if (value) this.load() } } },
  methods: {
    load() {
      this.loading = true
      Api.maintenance.runs(this.resourceType, this.resourceId, res => {
        const payload = (res && res.data && res.data.data) || (res && res.data) || []
        this.runs = Array.isArray(payload) ? payload : []
        this.loading = false
      }, error => {
        this.loading = false
        this.$message.error((error && error.data && error.data.msg) || (error && error.msg) || '加载维护记录失败')
      })
    },
    triggerText(value) { return ({ SCHEDULED: '定时维护', MANUAL: '手动维护', INTERACTIVE: '原生 Harness' })[value] || value },
    statusText(value) { return ({ QUEUED: '排队中', RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败' })[value] || value },
    statusType(value) { return ({ QUEUED: 'info', RUNNING: 'warning', SUCCEEDED: 'success', FAILED: 'danger' })[value] || 'info' },
    formatDate(value) { const date = new Date(value); return !value || Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN', { hour12: false }) }
  }
}
</script>

<style scoped>.audit-hint { margin-bottom: 14px; }</style>
