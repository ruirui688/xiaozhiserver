<template>
  <section class="admin-health">
    <div class="health-heading"><div><h3>用量采集</h3><p>平台运行状态。心跳、调用入账和账单同步是不同事实。</p></div><el-button size="small" :loading="loading" @click="load">刷新状态</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="data"><div class="health-facts"><div><span>实时服务心跳</span><strong>{{ heartbeat(data.python && data.python.status) }}</strong></div><div><span>已有调用上下文</span><strong>{{ number(data.contexts && data.contexts.count) }}</strong></div><div><span>已入账实时调用</span><strong>{{ number(data.events && data.events.pythonEventCount) }}</strong></div></div><p class="health-note">心跳正常只证明状态上报可达；首次入账之前、未接入的模型和中断区间不能视为完整覆盖。</p>
      <details v-for="(item,index) in data.python && data.python.instances || []" :key="item.instanceId" class="health-instance"><summary>实时服务实例 {{ index + 1 }} · {{ heartbeat(item.heartbeatStatus) }}<span>最近上报 {{ time(item.receivedAt) }}</span></summary><dl><dt>实例 / 采集版本</dt><dd>{{ item.instanceId }} / {{ item.collectorVersion }}</dd><dt>进程启动</dt><dd>{{ time(item.startedAt) }}</dd><dt>最近上下文 / 调用</dt><dd>{{ time(item.lastContextAt) }} / {{ time(item.lastCallStartedAt) }}</dd><dt>最近成功投递</dt><dd>{{ time(item.lastDeliveryAt) }}</dd><dt>待补传 / 隔离</dt><dd>{{ item.queueAvailable === true ? number(item.pendingCount) + ' / ' + number(item.rejectedCount) : '队列状态不可用，不能视为空队列' }}</dd><dt>上下文缺失调用</dt><dd>{{ number(item.missingContextCalls) }}</dd><dt>持久化失败</dt><dd>{{ number(item.persistenceFailureCount) }}</dd><dt>已接入适配器</dt><dd>{{ (item.supportedAdapters || []).join('、') || '未提供' }}</dd></dl></details>
      <p v-if="data.javaQueue" class="health-note">管理服务自己的队列：待入账 {{ number(data.javaQueue.pendingCount) }} · 隔离 {{ number(data.javaQueue.rejectedCount) }} · 保存失败 {{ number(data.javaQueue.persistenceFailureCount) }}。不代表实时服务队列状态。</p>
    </template>
  </section>
</template>
<script>
import Api from '@/apis/api';
import { billingTime, decimalText } from '@/utils/billing';
export default {
  data() { return { data: null, error: '', loading: false, generation: 0 }; }, created() { this.load(); }, beforeDestroy() { this.generation++; },
  methods: { time: billingTime, number: decimalText, heartbeat(value) { return ({ NEVER_SEEN: '尚未收到心跳', ALIVE: '已收到近期心跳', STALE: '心跳已过期' })[value] || '待验证'; }, async load() { const generation = ++this.generation; this.loading = true; this.error = ''; this.data = null; try { const data = await Api.billing.collectionHealth({ accountView: true }); if (generation !== this.generation) return; if (!data || data.scope !== 'PLATFORM') throw new Error('返回的采集范围不匹配'); this.data = data; } catch (error) { if (generation === this.generation) this.error = error.message; } finally { if (generation === this.generation) this.loading = false; } } }
};
</script>
<style scoped>
.admin-health{margin:18px 0 24px;padding:18px;border:1px solid #e7ebf2;border-radius:10px;background:#f9fbfe}.health-heading{display:flex;gap:15px;align-items:center;justify-content:space-between}.health-heading h3{margin:0 0 5px;font-size:15px}.health-heading p,.health-note{font-size:12px;color:#8a98ab;line-height:1.9;margin:8px 0}.health-facts{display:flex;gap:25px;flex-wrap:wrap;margin:18px 0}.health-facts span{display:block;color:#94a0b1;font-size:11px}.health-facts strong{display:block;font-size:14px;font-weight:500;color:#56708d;margin-top:7px}.health-instance{font-size:12px;border-top:1px solid #e8edf4;padding-top:12px;margin-top:12px}.health-instance summary{cursor:pointer;color:#557399;line-height:1.9}.health-instance summary>span{color:#97a2b1;display:block}.health-instance dl{display:grid;grid-template-columns:145px minmax(0,1fr);gap:8px;line-height:1.8}.health-instance dt{color:#94a0b1}.health-instance dd{margin:0;overflow-wrap:anywhere}@media(max-width:500px){.health-instance dl{grid-template-columns:1fr}.health-instance dd{margin-bottom:7px}}
</style>
