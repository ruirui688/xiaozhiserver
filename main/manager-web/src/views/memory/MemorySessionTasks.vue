<template>
  <div class="session-tasks">
    <div class="toolbar"><span>会话结束后的记忆整理任务。失败不会显示为已记住，可在修正原因后重试。</span><el-button size="small" :loading="loading" @click="load">刷新任务</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-table :data="tasks" v-loading="loading" empty-text="暂无会话整理任务" size="small">
      <el-table-column label="状态" width="110"><template slot-scope="{row}"><el-tag size="mini" :type="row.status === 'FAILED' ? 'danger' : 'info'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="尝试次数" prop="attempts" width="90" />
      <el-table-column label="处理项数" prop="factsChanged" width="90" />
      <el-table-column label="最近错误" prop="lastError" min-width="200" show-overflow-tooltip />
      <el-table-column label="更新时间" prop="updatedAt" width="170" />
      <el-table-column label="下次尝试" prop="nextAttemptAt" width="170" />
      <el-table-column label="操作" width="90"><template slot-scope="{row}"><el-button v-if="row.status === 'FAILED'" type="text" size="mini" :disabled="!canWrite || busy || Boolean(retrying)" :loading="retrying === row.taskId" @click="retry(row)">重试</el-button></template></el-table-column>
    </el-table>
  </div>
</template>

<script>
import Api from '@/apis/api'
import { responseData, memoryError } from './memoryPolicy.mjs'

export default {
  name: 'MemorySessionTasks',
  props: {
    agentId: { type: String, required: true },
    canWrite: { type: Boolean, default: false },
    busy: { type: Boolean, default: false }
  },
  data() { return { tasks: [], loading: false, retrying: '', error: '', requestVersion: 0 } },
  watch: { agentId: { immediate: true, handler() { this.tasks = []; this.retrying = ''; this.load() } } },
  beforeDestroy() { this.requestVersion += 1 },
  methods: {
    statusLabel(status) { return { PENDING: '等待整理', RUNNING: '正在整理', SUCCEEDED: '整理完成', NO_CHANGE: '无需变更', FAILED: '整理失败' }[status] || status },
    load() {
      const version = ++this.requestVersion
      const agentId = this.agentId
      this.error = ''
      this.loading = false
      if (!agentId) return
      this.loading = true
      const failed = error => {
        if (version !== this.requestVersion || agentId !== this.agentId) return
        this.loading = false
        this.error = memoryError(error, '整理任务读取失败')
      }
      Api.memory.sessionTasks(agentId, res => {
        if (version !== this.requestVersion || agentId !== this.agentId) return
        this.loading = false
        const data = responseData(res)
        this.tasks = Array.isArray(data) ? data : []
      }, failed, failed)
    },
    retry(task) {
      if (!this.canWrite || this.busy || this.retrying || task.status !== 'FAILED') return
      const agentId = this.agentId
      this.retrying = task.taskId
      const failed = error => {
        if (agentId !== this.agentId || this._isDestroyed) return
        this.retrying = ''
        this.error = memoryError(error, '重试结果未确认，请刷新任务查看')
      }
      Api.memory.retrySessionTask(agentId, task.taskId, () => {
        if (agentId !== this.agentId || this._isDestroyed) return
        this.retrying = ''
        this.load()
      }, failed, failed)
    }
  }
}
</script>

<style scoped>
.session-tasks { padding: 8px 16px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; color: #718096; font-size: 13px; margin-bottom: 16px; }
</style>
