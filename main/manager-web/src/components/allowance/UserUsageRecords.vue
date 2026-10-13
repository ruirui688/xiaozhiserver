<template>
  <div class="user-usage-records">
    <el-table v-if="kind === 'agents' || kind === 'devices'" :data="rows" size="small" empty-text="当前筛选范围暂无记录">
      <el-table-column :label="kind === 'agents' ? '智能体' : '设备'" min-width="160"><template slot-scope="s"><el-button v-if="s.row.id" type="text" @click="$emit('drill',s.row)">{{ s.row.name || '历史资源' }}</el-button><span v-else>空间公共任务</span><small v-if="s.row.board">{{ s.row.board }}</small></template></el-table-column>
      <el-table-column label="记录 / 会话" width="105"><template slot-scope="s">{{ number(s.row.callCount) }} / {{ number(s.row.sessionCount) }}</template></el-table-column>
      <el-table-column label="已记录用量" min-width="280"><template slot-scope="s"><UserUsageMetrics :usage="s.row.usage || []" /><small v-if="incomplete(s.row)" class="usage-incomplete">{{ number(s.row.usageUnknownCount) }} 条用量尚未完整</small><small v-if="positive(s.row.failedCount)" class="call-failed">{{ number(s.row.failedCount) }} 条失败记录</small></template></el-table-column>
      <el-table-column label="已结算扣额（元）" min-width="160"><template slot-scope="s"><span :title="exact(settledAmount(s.row.platformSettlement))">{{ amount(settledAmount(s.row.platformSettlement)) }}</span><small v-if="positive(s.row.platformSettlement && s.row.platformSettlement.pendingCount)">{{ number(s.row.platformSettlement.pendingCount) }} 条结算中</small><small v-if="positive(s.row.platformSettlement && s.row.platformSettlement.reviewCount)">{{ number(s.row.platformSettlement.reviewCount) }} 条待核对</small><small v-if="positive(s.row.platformSettlement && s.row.platformSettlement.unlinkedCount)">{{ number(s.row.platformSettlement.unlinkedCount) }} 条无关联结算记录</small></template></el-table-column>
    </el-table>
    <el-table v-else :data="rows" size="small" row-key="eventId" empty-text="当前筛选范围暂无调用记录">
      <el-table-column type="expand"><template slot-scope="s"><dl class="usage-record-detail"><dt>智能体 / 设备</dt><dd>{{ s.row.agentName || s.row.agentId || '空间公共任务' }} / {{ s.row.deviceName || s.row.deviceId || '非设备调用' }}</dd><dt>调用记录</dt><dd>{{ s.row.eventId }}</dd><dt>调用用途</dt><dd>{{ label(s.row.purpose) }}</dd><dt>全部用量</dt><dd><UserUsageMetrics :usage="s.row.usage || []" /></dd><dt>预占额度</dt><dd :title="exact(s.row.reservedAmount)">{{ amount(s.row.reservedAmount) }} 元</dd><dt>结算说明</dt><dd>{{ settlementNote(s.row) }}</dd></dl></template></el-table-column>
      <el-table-column label="时间（北京）" min-width="150"><template slot-scope="s">{{ time(s.row.startedAt) }}</template></el-table-column>
      <el-table-column label="服务 / 模型" min-width="165"><template slot-scope="s">{{ label(s.row.serviceType) }}<small>{{ s.row.modelName || s.row.modelId || '未提供模型' }}</small></template></el-table-column>
      <el-table-column label="调用结果" width="100"><template slot-scope="s"><el-tag size="mini" :type="s.row.status === 'SUCCESS' ? 'success' : s.row.status === 'FAILED' ? 'danger' : 'info'">{{ label(s.row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="已记录用量" min-width="260"><template slot-scope="s"><UserUsageMetrics :usage="s.row.usage || []" /><small v-if="incomplete(s.row)" class="usage-incomplete">用量尚未完整</small></template></el-table-column>
      <el-table-column label="平台结算" min-width="125"><template slot-scope="s"><el-tag size="mini" :type="settlementType(s.row.settlementStatus)">{{ settlementLabel(s.row.settlementStatus) }}</el-tag></template></el-table-column>
      <el-table-column label="扣减额度（元）" min-width="130"><template slot-scope="s"><span :title="exact(s.row.settledAmount)">{{ amount(s.row.settledAmount) }}</span><small v-if="s.row.settlementShared === true" class="usage-shared-settlement" :title="s.row.reservationId ? '结算记录：' + s.row.reservationId : '同一结算仅扣减一次'">此调用共享同一结算</small></template></el-table-column>
    </el-table>
  </div>
</template>
<script>
import UserUsageMetrics from './UserUsageMetrics.vue';
import { billingLabel, billingTime, decimalText } from '@/utils/billing';
import { allowanceAmount, allowanceDisplayAmount } from '@/utils/allowance';
import { confirmedSettlementAmount, hasIncompleteUsage, settlementLabel, settlementType, userUsageCount } from '@/utils/userUsage';
export default {
  components: { UserUsageMetrics }, props: { kind: String, rows: { type: Array, default: () => [] } },
  methods: {
    label: billingLabel, time: billingTime, number: decimalText, amount: allowanceDisplayAmount, exact: allowanceAmount,
    incomplete: hasIncompleteUsage, settlementLabel, settlementType, settledAmount: confirmedSettlementAmount,
    positive(value) { return (userUsageCount(value) || 0) > 0; },
    settlementNote(row) {
      const note = row.settlementStatus === 'UNTRACKED' ? '这条调用没有关联的平台结算记录，扣额未知。' : row.settlementStatus === 'UNKNOWN' ? '用量等待核对，预占暂时保留，最终扣额尚未确认。' : '调用结果与额度结算分别记录，扣额以平台结算记录为准。';
      return row.settlementShared === true ? note + ' 此调用与其他记录共享同一结算，金额仅扣减一次，汇总已去重。' : note;
    }
  }
};
</script>
<style scoped>
.user-usage-records{min-width:0}.user-usage-records small{display:block;font-size:11px;color:#8693a4;line-height:1.7;overflow-wrap:anywhere}.user-usage-records .usage-incomplete{color:#98763e}.user-usage-records .call-failed{color:#af5c5c}.user-usage-records .el-button--text{white-space:normal;text-align:left;padding:4px 0}.usage-record-detail{display:grid;grid-template-columns:110px minmax(0,1fr);gap:10px;font-size:12px;margin:10px 16px}.usage-record-detail dt{color:#8190a3}.usage-record-detail dd{margin:0;overflow-wrap:anywhere}.user-usage-records ::v-deep .el-table .cell{line-height:1.7;overflow-wrap:anywhere}@media(max-width:600px){.usage-record-detail{grid-template-columns:1fr;gap:5px;margin:8px}.usage-record-detail dd{margin-bottom:6px}}
</style>
