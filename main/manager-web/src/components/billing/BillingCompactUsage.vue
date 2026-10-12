<template>
  <div class="compact-usage">
    <span v-if="!usage.length" class="muted">暂无计量数据</span>
    <span v-for="(item, index) in primary" :key="index" class="usage-value">
      <span><template v-if="item.serviceType">{{ label(item.serviceType) }} · </template>{{ label(item.metric) }}</span>
      <strong>{{ number(item.quantity) }} {{ label(item.unit) }}</strong>
      <small>{{ label(item.source) }}</small>
    </span>
    <el-popover v-if="usage.length > primary.length" placement="bottom-start" trigger="click" :width="300" popper-class="billing-usage-popover">
      <div class="usage-detail"><p>全部指标 · 按来源与单位分别列示</p><BillingUsage :usage="usage" /></div>
      <el-button slot="reference" type="text" size="mini">全部 {{ usage.length }} 项 <i class="el-icon-arrow-down" /></el-button>
    </el-popover>
  </div>
</template>
<script>
import BillingUsage from './BillingUsage.vue';
import { billingLabel, decimalText } from '@/utils/billing';

const PRIMARY_METRICS = ['input_tokens', 'output_tokens', 'audio_duration', 'billable_characters', 'total_tokens', 'submitted_audio_duration', 'submitted_characters'];

export default {
  components: { BillingUsage },
  props: { usage: { type: Array, default: () => [] }, maxItems: { type: Number, default: 2 } },
  computed: {
    primary() {
      // Select original rows only: totals, cache subsets, units and sources never get added together.
      const priority = item => { const index = PRIMARY_METRICS.indexOf(item.metric); return index < 0 ? PRIMARY_METRICS.length : index; };
      return this.usage.map((item, index) => ({ item, index }))
        .sort((a, b) => priority(a.item) - priority(b.item) || a.index - b.index)
        .slice(0, this.maxItems).map(entry => entry.item);
    }
  },
  methods: { label: billingLabel, number: decimalText }
};
</script>
<style scoped>
.compact-usage{display:flex;flex-wrap:wrap;align-items:center;gap:4px 14px;font-size:12px;line-height:1.6}.usage-value{display:flex;flex-wrap:wrap;gap:4px;align-items:baseline}.usage-value>span,.muted{color:#778399}.usage-value strong{font-weight:500;color:#334660;font-variant-numeric:tabular-nums}.usage-value small{font-size:10px;color:#8a92a4}.compact-usage .el-button{padding:2px 0;font-size:12px}.usage-detail{max-height:300px;overflow:auto}.usage-detail p{font-size:12px;color:#778399;margin:0 0 12px}
</style>
