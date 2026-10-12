<template>
  <div class="usage-list">
    <span v-if="!usage.length" class="muted">暂无计量数据</span>
    <div v-for="(item, index) in usage" :key="index" class="usage-item">
      <span><template v-if="item.serviceType">{{ label(item.serviceType) }} · </template>{{ label(item.metric) }}</span>
      <strong>{{ number(item.quantity) }} {{ label(item.unit) }}</strong>
      <small>{{ label(item.source) }}</small>
    </div>
  </div>
</template>
<script>
import { billingLabel, decimalText } from '@/utils/billing';
export default { props: { usage: { type: Array, default: () => [] } }, methods: { label: billingLabel, number: decimalText } };
</script>
<style scoped>
.usage-list { display: grid; gap: 8px; }.usage-item { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; font-size: 12px; line-height: 1.6; }.usage-item strong { color: #333d59; font-weight: 500; }.usage-item small, .muted { color: #858da0; }.usage-item small { font-size: 11px; }
</style>
