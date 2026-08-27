<template>
  <el-dialog title="审核维护提案" :visible.sync="internalVisible" width="900px" top="7vh"
    append-to-body @close="$emit('close')">
    <el-table :data="proposals" v-loading="loading" max-height="560" empty-text="暂无待处理或历史提案">
      <el-table-column label="动作" prop="actionType" width="130" />
      <el-table-column label="对象" min-width="230">
        <template slot-scope="scope">
          <div>{{ scope.row.title || scope.row.targetId || scope.row.sourceUri || '-' }}</div>
          <el-link v-if="scope.row.sourceUri" :href="scope.row.sourceUri" target="_blank"
            rel="noopener noreferrer" type="primary">查看来源</el-link>
        </template>
      </el-table-column>
      <el-table-column label="理由" prop="rationale" min-width="280" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template slot-scope="scope">{{ statusText(scope.row.status) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template slot-scope="scope">
          <template v-if="scope.row.status === 'PENDING'">
            <el-button type="text" @click="review(scope.row, true)">批准</el-button>
            <el-button type="text" class="reject" @click="review(scope.row, false)">拒绝</el-button>
          </template>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </el-table>
    <span slot="footer"><el-button @click="load">刷新</el-button><el-button @click="internalVisible = false">关闭</el-button></span>
  </el-dialog>
</template>

<script>
import Api from '@/apis/api'

export default {
  name: 'MaintenanceProposalPanel',
  props: {
    visible: { type: Boolean, default: false },
    resourceType: { type: String, required: true },
    resourceId: { type: String, required: true }
  },
  data() { return { internalVisible: false, loading: false, proposals: [] } },
  watch: { visible: { immediate: true, handler(value) { this.internalVisible = value; if (value) this.load() } } },
  methods: {
    fail(error, fallback) { this.$message.error((error && error.data && error.data.msg) || (error && error.msg) || fallback) },
    load() {
      this.loading = true
      Api.maintenance.proposals(this.resourceType, this.resourceId, res => {
        const payload = (res && res.data && res.data.data) || (res && res.data) || []
        this.proposals = Array.isArray(payload) ? payload : []
        this.loading = false
      }, error => { this.loading = false; this.fail(error, '加载维护提案失败') })
    },
    review(proposal, approved) {
      const action = approved ? Api.maintenance.approve : Api.maintenance.reject
      action(this.resourceType, this.resourceId, proposal.id, () => {
        this.$message.success(approved ? '提案已批准，后台正在执行' : '提案已拒绝')
        this.$emit('changed')
        this.load()
      }, error => this.fail(error, approved ? '批准提案失败' : '拒绝提案失败'))
    },
    statusText(value) {
      return ({ PENDING: '待审批', APPLYING: '执行中', APPLIED: '已应用', REJECTED: '已拒绝', FAILED: '失败' })[value] || value
    }
  }
}
</script>

<style scoped>.reject { color: #f56c6c; }</style>
