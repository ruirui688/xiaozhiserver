<template>
  <section class="collection-health" :class="{ warning: warning }">
    <div class="health-summary"><i :class="warning ? 'el-icon-warning-outline' : 'el-icon-data-line'" /><div><strong>{{ title }}</strong><p>{{ description }}</p></div><el-button type="text" :loading="loading" @click="expanded = !expanded">{{ expanded ? '收起' : '采集详情' }} <i :class="expanded ? 'el-icon-arrow-up' : 'el-icon-arrow-down'" /></el-button></div>
    <div v-if="expanded" class="health-details">
      <div v-if="error" class="health-error">{{ error }} <el-button type="text" @click="load">重新读取</el-button></div>
      <template v-else-if="data"><dl><dt>首次取得调用上下文</dt><dd>{{ time(data.contexts && data.contexts.firstContextAt) }}</dd><dt>首条已入账调用</dt><dd>{{ time(data.events && data.events.firstEventAt) }}</dd><dt>最近调用入账</dt><dd>{{ time(data.events && data.events.lastEventAt) }}</dd><dt>统计边界</dt><dd>仅已采集记录；首条事件之前及尚未接入的模型不代表完整覆盖。</dd></dl><p v-if="canManage && data.javaQueue" class="queue-note">管理服务队列：待入账 {{ number(data.javaQueue.pendingCount) }} · 隔离 {{ number(data.javaQueue.rejectedCount) }} · 保存失败 {{ number(data.javaQueue.persistenceFailureCount) }}。此队列不能证明实时服务已接通。</p><el-button type="text" @click="load">刷新采集状态</el-button></template>
    </div>
  </section>
</template>
<script>
import Api from '@/apis/api';
import { billingTime, decimalText } from '@/utils/billing';
export default {
  props: { workspaceId: String, canManage: Boolean },
  data() { return { data: null, error: '', loading: false, expanded: false, generation: 0 }; },
  computed: {
    warning() { return !!this.error || !this.data || ['NO_USAGE_EVIDENCE', 'PENDING', 'INCOMPLETE'].includes(this.data.status); },
    title() { if (this.loading && !this.data) return '正在核对采集状态'; if (this.error) return '采集状态暂不可用'; return ({ NO_USAGE_EVIDENCE: '尚未收到用量证据', COLLECTED_RECORDS_ONLY: '用量来自已采集记录', PENDING: '部分用量等待补传', INCOMPLETE: '部分用量尚未完整入账' })[this.data && this.data.status] || '采集覆盖尚待验证'; },
    description() { if (this.error) return '读取失败不能解释为零使用。可以重试状态查询。'; if (!this.data || this.data.status === 'NO_USAGE_EVIDENCE') return '采集接通待验证。账单同步成功不代表用量采集已接通，也不代表没有发生使用。'; if (this.data.status === 'PENDING') return '当前统计可能尚未包含全部调用，补传入账后将更新。'; if (this.data.status === 'INCOMPLETE') return '存在拒绝或持久化失败，请查看数据接入状态；未知用量不补零。'; return '供应商用量与本地观测分别标识；未接入的模型及采集前历史不包含在统计中。'; }
  },
  watch: { workspaceId() { this.load(); } }, created() { this.load(); }, beforeDestroy() { this.generation++; },
  methods: {
    time: billingTime, number: decimalText,
    async load() {
      const generation = ++this.generation; const workspace = this.workspaceId; this.loading = true; this.error = ''; this.data = null;
      try { const data = await Api.billing.collectionHealth(); if (generation !== this.generation || workspace !== localStorage.getItem('currentWorkspaceId')) return; if (!data || data.scope !== 'WORKSPACE' || data.workspaceId !== workspace) throw new Error('返回的采集范围不匹配'); this.data = data; }
      catch (error) { if (generation === this.generation) this.error = error.message; }
      finally { if (generation === this.generation) this.loading = false; }
    }
  }
};
</script>
<style scoped>
.collection-health{background:#f0f5fc;border:1px solid #e1eaf7;border-radius:12px;padding:16px 20px;margin:18px 0}.collection-health.warning{background:#fff9ee;border-color:#f3e7d1}.health-summary{display:flex;gap:12px;align-items:flex-start}.health-summary>i{font-size:21px;color:#6688bd;margin-top:3px}.warning .health-summary>i{color:#b48b4d}.health-summary>div{flex:1;min-width:0}.health-summary strong{font-size:13px;font-weight:550}.health-summary p{font-size:12px;color:#8290a4;line-height:1.85;margin-top:4px}.health-summary>.el-button{padding:3px 0;font-size:12px;flex-shrink:0}.health-details{border-top:1px solid #e6e8eb;margin-top:16px;padding-top:12px}.health-details dl{display:grid;grid-template-columns:155px minmax(0,1fr);gap:10px;font-size:12px;line-height:1.8}.health-details dt{color:#8491a3}.health-details dd{margin:0;color:#536782;overflow-wrap:anywhere}.queue-note,.health-error{font-size:12px;line-height:1.9;color:#8b775d}@media(max-width:550px){.collection-health{padding:15px}.health-summary{flex-wrap:wrap}.health-summary>.el-button{margin-left:33px}.health-details dl{grid-template-columns:1fr;gap:4px}.health-details dd{margin-bottom:8px}}
</style>
