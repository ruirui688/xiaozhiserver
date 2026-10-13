<template>
  <div class="user-usage-metrics">
    <span v-if="!usage.length" class="metric-empty">暂无用量数据</span>
    <span v-for="(item,index) in usage" :key="index" class="usage-metric">
      <span><template v-if="item.serviceType">{{ label(item.serviceType) }} · </template>{{ label(item.metric) }}</span><strong>{{ number(item.quantity) }} {{ label(item.unit) }}</strong>
      <small v-if="item.source">{{ label(item.source) }}</small>
    </span>
  </div>
</template>
<script>
import { billingLabel, decimalText } from '@/utils/billing';
export default { props: { usage: { type: Array, default: () => [] } }, methods: { label: billingLabel, number: decimalText } };
</script>
<style scoped>
.user-usage-metrics{display:flex;flex-wrap:wrap;gap:4px 14px;align-items:baseline;font-size:12px;line-height:1.6}.usage-metric{display:flex;flex-wrap:wrap;gap:5px;align-items:baseline}.usage-metric>span,.metric-empty{color:#8190a3}.usage-metric strong{font-weight:500;color:#34465d;font-variant-numeric:tabular-nums}.usage-metric small{font-size:10px;color:#94a0ae}
</style>
