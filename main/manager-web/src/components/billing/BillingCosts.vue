<template>
  <div class="cost-list">
    <span v-if="!costs.length" class="muted">— <small>{{ label(status) }}</small></span>
    <div v-for="(cost, index) in costs" :key="index" class="cost-item">
      <strong>{{ money(cost.payableAmount, cost.currency) }}</strong>
      <small>已付 {{ money(cost.paidAmount, cost.currency) }} · 未付 {{ money(cost.unpaidAmount, cost.currency) }}</small>
    </div>
    <small v-if="costs.length && status === 'IMPORTED_STATEMENT'" class="muted">{{ label(status) }}</small>
  </div>
</template>
<script>
import { amountText, billingLabel } from '@/utils/billing';
export default { props: { costs: { type: Array, default: () => [] }, status: { type: String, default: 'PENDING_OR_UNATTRIBUTED' } }, methods: { money: amountText, label: billingLabel } };
</script>
<style scoped>
.cost-list { display: grid; gap: 10px; }.cost-item { display: flex; flex-direction: column; gap: 5px; }.cost-item strong { font-size: 15px; color: #344668; font-weight: 600; }.cost-item small, .muted { color: #858da0; line-height: 1.6; }.muted small { margin-left: 5px; }
</style>
