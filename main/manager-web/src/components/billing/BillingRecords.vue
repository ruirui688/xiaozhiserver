<template>
  <div class="billing-records">
    <el-table v-if="kind === 'agents' || kind === 'devices'" :data="rows" row-key="id" empty-text="当前筛选范围暂无记录">
      <el-table-column :label="kind === 'agents' ? '智能体' : '设备'" min-width="160">
        <template slot-scope="scope"><el-button type="text" @click="$emit('drill', scope.row)">{{ scope.row.name || (scope.row.id ? '历史资源' : '空间公共任务') }}</el-button><small class="secondary">{{ kind === 'devices' ? '按调用时的设备归属' : '含设备调用及后台任务' }}</small></template>
      </el-table-column>
      <el-table-column v-if="kind === 'devices'" prop="board" label="设备型号" min-width="100" />
      <el-table-column label="调用 / 会话" width="110"><template slot-scope="scope">{{ number(scope.row.callCount) }} / {{ number(scope.row.sessionCount) }}</template></el-table-column>
      <el-table-column label="用量（按来源与单位分别统计）" min-width="330"><template slot-scope="scope"><BillingUsage :usage="scope.row.usage || []" /><small v-if="scope.row.usageUnknownCount > 0" class="incomplete">{{ number(scope.row.usageUnknownCount) }} 次调用的供应商用量不完整</small></template></el-table-column>
      <el-table-column v-if="showSupplierCosts" label="可归属官方应付金额" min-width="230"><template slot-scope="scope"><BillingCosts :costs="scope.row.costs || []" :status="scope.row.costStatus" /></template></el-table-column>
    </el-table>
    <el-table v-else-if="kind === 'events'" :data="rows" row-key="eventId" empty-text="当前筛选范围暂无调用记录">
      <el-table-column type="expand"><template slot-scope="scope"><dl class="details"><dt>调用 ID</dt><dd>{{ scope.row.eventId }}</dd><dt>供应商请求 ID</dt><dd>{{ scope.row.providerRequestId || '未返回' }}</dd><dt>会话 ID</dt><dd>{{ scope.row.sessionId || '—' }}</dd><dt>调用用途</dt><dd>{{ label(scope.row.purpose) }}</dd><dt>结束时间</dt><dd>{{ time(scope.row.endedAt) }}</dd></dl></template></el-table-column>
      <el-table-column label="开始时间（北京时间）" min-width="170"><template slot-scope="scope">{{ time(scope.row.startedAt) }}</template></el-table-column>
      <el-table-column label="智能体 / 设备" min-width="180"><template slot-scope="scope"><div>{{ scope.row.agentName || scope.row.agentId || '—' }}</div><small class="secondary">{{ scope.row.deviceName || scope.row.deviceId || '非设备调用' }}</small></template></el-table-column>
      <el-table-column label="服务 / 模型" min-width="170"><template slot-scope="scope"><div>{{ label(scope.row.serviceType) }} · {{ label(scope.row.provider) }}</div><small class="secondary">{{ scope.row.modelName || scope.row.modelId || '—' }}</small></template></el-table-column>
      <el-table-column label="实际用量 / 本地观测" min-width="310"><template slot-scope="scope"><BillingUsage :usage="scope.row.usage || []" /><small v-if="scope.row.usageComplete === false || scope.row.usageComplete === 0" class="incomplete">供应商用量不完整</small></template></el-table-column>
      <el-table-column label="调用状态" width="110"><template slot-scope="scope"><el-tag size="small" :type="scope.row.status === 'SUCCESS' ? 'success' : 'info'">{{ label(scope.row.status) }}</el-tag></template></el-table-column>
      <el-table-column v-if="showSupplierCosts" label="逐调用实际费用" min-width="140"><template slot-scope="scope">—<small class="secondary">{{ label(scope.row.costStatus || 'NOT_MATCHED') }}</small></template></el-table-column>
    </el-table>
    <el-table v-else :data="rows" row-key="id" empty-text="当前筛选范围暂无官方账单">
      <el-table-column type="expand"><template slot-scope="scope"><dl class="details">
        <dt>账单 ID / 明细 ID</dt><dd>{{ scope.row.billId || '—' }} / {{ scope.row.billDetailId || '—' }}</dd>
        <dt>账单粒度</dt><dd>{{ label(scope.row.level) }}</dd>
        <dt>金额汇总</dt><dd>{{ scope.row.aggregationIncluded === true ? '计入汇总' : scope.row.aggregationIncluded === false ? '仅供核对，汇总使用其他账单层级' : '—' }}</dd>
        <dt>计费方式</dt><dd>{{ label(scope.row.billingMode) }}</dd>
        <dt>资源抵扣用量</dt><dd><div v-for="item in supplierUsage(scope.row.supplierUsage || {})" :key="item.name">{{ item.name }}：{{ item.value }}</div><small class="secondary">供应商用量与抵扣字段，不计入金额。</small></dd>
        <dt>原始账单分类</dt><dd>{{ scope.row.billCategory || '—' }}</dd>
        <dt>结算方式 / 支付状态</dt><dd>{{ scope.row.settlementType || '—' }} / {{ scope.row.payStatus || '—' }}</dd>
        <dt>账单 / 结算币种</dt><dd>{{ scope.row.currency || '—' }} / {{ scope.row.settlementCurrency || '—' }}<small v-if="scope.row.paymentCurrencyVerified === false" class="incomplete">支付金额的币种关系未核实，已付和未付金额暂不展示。</small></dd>
        <dt>优惠券金额</dt><dd>{{ money(scope.row.couponAmount, scope.row.currency) }}</dd>
        <dt>来源</dt><dd>{{ label(scope.row.source) }}</dd>
        <dt>{{ scope.row.source === 'STATEMENT_IMPORT' ? '导入时间' : '同步时间' }}</dt><dd>{{ time(scope.row.syncedAt) }}</dd>
      </dl></template></el-table-column>
      <el-table-column label="供应商 / 账期" min-width="135"><template slot-scope="scope">{{ label(scope.row.provider) }}<small class="secondary">{{ scope.row.billPeriod }}</small></template></el-table-column>
      <el-table-column label="消费时间（北京时间）" min-width="170"><template slot-scope="scope">{{ time(scope.row.expenseBegin) }}<small class="secondary">{{ scope.row.expenseEnd ? '至 ' + time(scope.row.expenseEnd) : '结束时间未提供' }}</small></template></el-table-column>
      <el-table-column label="归属范围" min-width="180"><template slot-scope="scope">{{ label(scope.row.attributionLevel) }}<small class="secondary">{{ scope.row.deviceId || scope.row.agentId || scope.row.workspaceId || '未归属工作空间' }}</small></template></el-table-column>
      <el-table-column label="官方应付" min-width="150"><template slot-scope="scope">{{ money(scope.row.payableAmount, scope.row.currency) }}</template></el-table-column>
      <el-table-column label="官方已付" min-width="150"><template slot-scope="scope">{{ money(scope.row.paidAmount, scope.row.currency) }}</template></el-table-column>
      <el-table-column label="官方未付" min-width="150"><template slot-scope="scope">{{ money(scope.row.unpaidAmount, scope.row.currency) }}</template></el-table-column>
    </el-table>
  </div>
</template>
<script>
import BillingUsage from './BillingUsage.vue';
import BillingCosts from './BillingCosts.vue';
import { billingLabel, billingTime, decimalText, amountText, supplierUsageRows } from '@/utils/billing';
export default { components: { BillingUsage, BillingCosts }, props: { kind: String, showSupplierCosts: { type: Boolean, default: true }, rows: { type: Array, default: () => [] } }, methods: { label: billingLabel, time: billingTime, number: decimalText, money: amountText, supplierUsage: supplierUsageRows } };
</script>
<style scoped>
.secondary { display: block; color: #8a92a4; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }.incomplete { display: block; color: #a2783d; margin-top: 8px; }.details { display: grid; grid-template-columns: 150px minmax(0, 1fr); margin: 10px 30px; gap: 12px; }.details dt { color: #8a92a4; }.details dd { margin: 0; overflow-wrap: anywhere; }.billing-records ::v-deep .el-table th { background: #f8f9fc; color: #64708a; }.billing-records ::v-deep .el-table .cell { overflow-wrap: anywhere; }
</style>
